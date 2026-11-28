package com.exo.musicplayer.data.lyrics

import com.exo.musicplayer.data.net.Http
import com.exo.musicplayer.data.net.HttpTally
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** One place lyrics can come from. */
interface LyricsProvider {
    val label: String
    suspend fun fetch(title: String, artist: String?, album: String?, durationMs: Long): LyricsFetch
}

private val LYRIC_BRACKETS = Regex("""\(.*?\)|\[.*?]|【.*?】""")
private val LYRIC_SPACES = Regex("""\s+""")
private val LYRIC_ARTIST_SPLIT = Regex("""\s*(,|&|\bx\b|\bfeat\.?|\bft\.?)\s*""", RegexOption.IGNORE_CASE)

/**
 * Asks the providers in order of preference; the first that has the lyrics wins.
 *
 * Ordered by what they give back, not by popularity: LRCLIB first because it is
 * the only one of the three that reliably carries *timed* LRC, then NetEase
 * which often does, then lyrics.ovh which is plain text but has broad Western
 * coverage. One service being down never blocks the rest, but it does stop the
 * answer from being "not found": only services that really answered can say
 * a song has no lyrics, and the bulk tool marks a song checked on that.
 *
 * The answer is decided exactly as if each service were asked in turn and
 * waited for (see [fetch]), but the waiting overlaps: a service that hasn't
 * answered within [hedgeMs] no longer holds the next one back, which is asked
 * alongside it. Its answer still counts first if it has the lyrics; the ones
 * after it are only used once everything ahead of them has come back empty, and
 * are cancelled as soon as something ahead of them has the lyrics.
 */
class LyricsProviderChain(
    private val providers: List<LyricsProvider>,
    /** Whether the service by this name is to be asked; Settings can turn each off. */
    private val isOn: (String) -> Boolean = { true },
    /** How long one service gets to answer; a hung request must not hold the song forever. */
    private val providerTimeoutMs: Long = PROVIDER_TIMEOUT_MS,
    /**
     * How long a service is waited for before the next one is asked alongside it.
     * [Long.MAX_VALUE] asks them strictly one after another.
     */
    private val hedgeMs: Long = HEDGE_MS,
    /** At most this many services out at once for one song (the one the answer waits on always goes). */
    private val maxInFlight: Int = MAX_IN_FLIGHT,
    /** Skips a service that keeps failing; null asks every time. */
    private val breaker: ProviderBreaker? = ProviderBreaker(),
    /** Keeps each service to a polite request rate across all songs; null doesn't pace. */
    private val pacer: ProviderPacer? = ProviderPacer(),
    /** Where the services' requests run; tests hand in a virtual-time dispatcher. */
    callContext: CoroutineContext = Dispatchers.IO
) {
    /**
     * Where each service's lookup runs. Not the caller's scope: the services
     * block in plain socket reads that a timeout can't interrupt, so a service
     * that is given up on finishes (or fails) on its own, and its answer is dropped.
     */
    private val calls = CoroutineScope(SupervisorJob() + callContext)

    /** What the services are called, in the order they are asked. */
    val labels: List<String> get() = providers.map { it.label }

    /**
     * Looks the song up as named, then by pieces of its name when that finds
     * nothing: downloaded files are often called "Artist - Song (Official
     * Video) [Channel]" or credit "A, B x C", which no lyrics service knows.
     *
     * Every (spelling, service) pair is one cell, in the order the old one-at-a-
     * time lookup asked them: spelling by spelling, service by service. The
     * decision below is that lookup, word for word, except that it reads each
     * cell's answer from [Cells] instead of asking then; [Cells] has usually
     * started it already. So the same answer comes out, never later.
     *
     * [memo], given by a bulk run, answers a song that was already looked up under
     * the same name in that run (a library holding a song twice).
     */
    suspend fun fetch(
        title: String,
        artist: String?,
        album: String?,
        durationMs: Long,
        memo: LyricsRunMemo? = null
    ): Pair<LyricsFetch, String?> {
        val enabled = providers.filter { isOn(it.label) }
        if (enabled.isEmpty()) {
            return LyricsFetch.Error("All the lyrics services are turned off in Settings.", offline = true) to null
        }
        val pieces = segments(title, artist)
        if (pieces.isEmpty()) return LyricsFetch.NotFound to null
        val key = memo?.let { LyricsRunMemo.key(title, artist, album, durationMs, enabled.map { it.label }) }
        if (memo != null && key != null) memo.get(key)?.let { return it }
        val answer = coroutineScope {
            val cells = Cells(this, pieces, enabled, album, durationMs)
            try {
                decide(cells, pieces.size, enabled)
            } finally {
                // Decided (or stopped): nothing still out is wanted.
                coroutineContext.cancelChildren()
            }
        }
        if (memo != null && key != null) memo.put(key, answer)
        return answer
    }

    /** The one-at-a-time lookup's rules, reading answers from [cells]. */
    private suspend fun decide(cells: Cells, spellings: Int, enabled: List<LyricsProvider>): Pair<LyricsFetch, String?> {
        val round = Round()
        for (spelling in 0 until spellings) {
            val result = decideOnce(cells, spelling, enabled, round)
            if (result.first is LyricsFetch.Found || result.first is LyricsFetch.Instrumental) return result
            // Nobody answered at all: other spellings of the name won't reach them either.
            if (!round.anyAnswered) break
        }
        return when {
            !round.anyAnswered -> LyricsFetch.Error(
                round.lastError ?: "Couldn't reach the lyrics services.", offline = true
            ) to null
            // Some services never answered, so "not found" would be a guess.
            round.down.isNotEmpty() -> LyricsFetch.Error(
                "${round.down.joinToString()} didn't answer, so this isn't a definite \"not found\"."
            ) to null
            else -> LyricsFetch.NotFound to null
        }
    }

    private suspend fun decideOnce(
        cells: Cells,
        spelling: Int,
        enabled: List<LyricsProvider>,
        round: Round
    ): Pair<LyricsFetch, String?> {
        var sawInstrumental = false
        for ((index, provider) in enabled.withIndex()) {
            if (provider.label in round.down) continue
            when (val result = cells.answer(spelling * enabled.size + index)) {
                is LyricsFetch.Found -> return result.tidied() to provider.label
                is LyricsFetch.Instrumental -> { round.anyAnswered = true; sawInstrumental = true }
                is LyricsFetch.NotFound -> round.anyAnswered = true
                is LyricsFetch.Error -> { round.down += provider.label; round.lastError = result.message }
            }
        }
        return (if (sawInstrumental) LyricsFetch.Instrumental else LyricsFetch.NotFound) to null
    }

    /** What one song's lookup has learnt so far, across the spellings of its name. */
    private class Round {
        var anyAnswered = false
        /** Services that failed once: not asked again for this song, and "not found" is off the table. */
        val down = linkedSetOf<String>()
        var lastError: String? = null
    }

    /**
     * One song's cells, started ahead of need. Cell i is started when the
     * decision needs it, or when cell i-1 (in order) has been out for [hedgeMs]
     * without answering, or has answered without lyrics (at most [maxInFlight]
     * out ahead of need). Cells that can no longer matter are not started, and
     * cancelled if they were: everything after a cell that found lyrics (the
     * decision stops there or earlier), and a service's later spellings once it
     * failed (the decision skips a service that failed).
     */
    private inner class Cells(
        private val scope: CoroutineScope,
        private val pieces: List<Pair<String, String?>>,
        private val enabled: List<LyricsProvider>,
        private val album: String?,
        private val durationMs: Long
    ) {
        private val size = pieces.size * enabled.size
        private val jobs = arrayOfNulls<Deferred<LyricsFetch>>(size)
        private val results = arrayOfNulls<LyricsFetch>(size)
        private var out = 0
        /** The first cell whose lyrics the decision is sure to reach: nothing after it matters. */
        private var foundAt = Int.MAX_VALUE
        /** The first spelling each service failed on. */
        private val failedAt = HashMap<String, Int>()

        private fun spellingOf(i: Int) = i / enabled.size
        private fun providerOf(i: Int) = enabled[i % enabled.size]

        private fun pointless(i: Int): Boolean =
            i > foundAt || (failedAt[providerOf(i).label]?.let { it < spellingOf(i) } == true)

        /**
         * Whether the decision will stop at cell [i] if it gets that far: it has
         * lyrics, and its service answered every earlier spelling (one that failed
         * would make the decision skip this service, and so this cell).
         */
        private fun decisive(i: Int): Boolean {
            if (results[i] !is LyricsFetch.Found) return false
            val column = i % enabled.size
            return (0 until spellingOf(i)).all { s ->
                val earlier = results[s * enabled.size + column]
                earlier != null && earlier !is LyricsFetch.Error
            }
        }

        suspend fun answer(i: Int): LyricsFetch = synchronized(this) { jobs[i] ?: start(i) }.await()

        /** Called holding the lock. */
        private fun start(i: Int): Deferred<LyricsFetch> {
            out++
            val (title, artist) = pieces[spellingOf(i)]
            val provider = providerOf(i)
            val job = scope.async {
                var settled = false
                try {
                    val result = ask(provider, title, artist, album, durationMs)
                    settled = true
                    settled(i, result)
                    result
                } finally {
                    // Cancelled or failed on the way: no longer out, and nothing to settle.
                    if (!settled) synchronized(this@Cells) { out-- }
                }
            }
            jobs[i] = job
            if (hedgeMs != Long.MAX_VALUE && i + 1 < size) {
                scope.launch {
                    delay(hedgeMs)
                    if (!job.isCompleted) hedge(i)
                }
            }
            return job
        }

        /** Cell [after] is slow: the next one that can still matter goes out alongside it. */
        private fun hedge(after: Int) {
            synchronized(this) {
                if (out >= maxInFlight) return
                val next = (after + 1 until size).firstOrNull { jobs[it] == null && !pointless(it) } ?: return
                start(next)
            }
        }

        private fun settled(i: Int, result: LyricsFetch) {
            synchronized(this) {
                out--
                results[i] = result
                if (result is LyricsFetch.Error) {
                    val label = providerOf(i).label
                    val spelling = spellingOf(i)
                    if (spelling < (failedAt[label] ?: Int.MAX_VALUE)) {
                        failedAt[label] = spelling
                        // The decision skips this service from here on.
                        for (j in i + enabled.size until size step enabled.size) jobs[j]?.cancel()
                    }
                }
                // A cell with lyrics can become sure only now, when an earlier spelling on its service answered.
                val before = foundAt
                foundAt = (0 until minOf(before, size)).firstOrNull(::decisive) ?: before
                if (foundAt < before) for (j in foundAt + 1 until size) jobs[j]?.cancel()
            }
            // Answered without lyrics: the next one goes now, as it would have one at a time.
            // Without this, a quick answer ahead of a slow one let nothing else start
            // until the slow one was done.
            if (result !is LyricsFetch.Found && hedgeMs != Long.MAX_VALUE) hedge(i)
        }
    }

    /** The name as given, then without brackets, split at " - ", and by the first artist alone. */
    private fun segments(title: String, artist: String?): List<Pair<String, String?>> {
        fun clean(text: String) = text.replace(LYRIC_BRACKETS, " ").replace(LYRIC_SPACES, " ").trim()
        fun lead(text: String?) =
            text?.split(LYRIC_ARTIST_SPLIT)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        val cleanTitle = clean(title)
        val dash = cleanTitle.split(" - ", limit = 2).takeIf { it.size == 2 }
        return listOfNotNull(
            title to artist,
            cleanTitle to lead(artist),
            dash?.let { clean(it[1]) to lead(it[0]) },
            cleanTitle to null
        ).filter { it.first.isNotBlank() }.distinct()
    }

    /**
     * One service's answer, with its failures said out loud. Most services say
     * NotFound when their request failed; the [HttpTally] catches that, and a
     * service that throws or takes longer than [providerTimeoutMs] is an Error.
     *
     * A service the [breaker] is skipping is an Error at once. The [pacer]'s wait
     * for this service's turn (a free place, its spacing) comes before its timeout starts.
     */
    private suspend fun ask(
        provider: LyricsProvider,
        title: String,
        artist: String?,
        album: String?,
        durationMs: Long
    ): LyricsFetch {
        val label = provider.label
        breaker?.skippedFor(label)?.let { left ->
            return LyricsFetch.Error("$label is temporarily skipped after failing repeatedly (${(left + 999) / 1000} s more).")
        }
        val answer = if (pacer == null) {
            call(provider, title, artist, album, durationMs)
        } else {
            pacer.withTurn(label, providerTimeoutMs) { call(provider, title, artist, album, durationMs) }
                // Not a failure of the service's, so the breaker doesn't hear of it.
                ?: return LyricsFetch.Error("$label asked for fewer requests; it is left alone for a while.")
        }
        breaker?.record(label, failed = answer is LyricsFetch.Error)
        return answer
    }

    /** The asking itself: within [providerTimeoutMs], its requests counted, its 429s passed to the [pacer]. */
    private suspend fun call(
        provider: LyricsProvider,
        title: String,
        artist: String?,
        album: String?,
        durationMs: Long
    ): LyricsFetch {
        val label = provider.label
        val tally = HttpTally()
        val call = calls.async(Http.counting(tally)) { provider.fetch(title, artist, album, durationMs) }
        val result = try {
            withTimeoutOrNull(providerTimeoutMs) { call.await() }
                ?: LyricsFetch.Error("$label took too long to answer.")
        } catch (cancelled: CancellationException) {
            // Stopping the run is not a failed service: caught as one, a cancelled
            // lookup was recorded as checked when it never had been.
            currentCoroutineContext().ensureActive()
            LyricsFetch.Error("$label gave up.")
        } catch (failure: Throwable) {
            LyricsFetch.Error("$label failed: ${failure.message ?: failure.javaClass.simpleName}")
        } finally {
            call.cancel()
            // Its sockets are closed too: a timed-out or unwanted request stops reading now.
            tally.abandon()
        }
        if (tally.slowDownMs >= 0) pacer?.slowDown(label, tally.slowDownMs)
        return if (result is LyricsFetch.NotFound && tally.failed > 0) {
            LyricsFetch.Error("Couldn't reach $label.")
        } else {
            result
        }
    }

    companion object {
        const val PROVIDER_TIMEOUT_MS = 12_000L

        /**
         * Through a SOCKS5 proxy with connections kept, an LRCLIB lookup took a
         * median 0.74 s (p90 1.2 s): one that takes longer than this is probably
         * a miss or a stall, and the next service is worth asking alongside.
         */
        const val HEDGE_MS = 800L

        /**
         * One song never has more than this many cells out at once ahead of need.
         * Modelled on the measured latencies, 6 makes a song that needs every
         * spelling about 2.3x quicker than one at a time for ~4% more requests;
         * past 6 it buys little and asks more.
         */
        const val MAX_IN_FLIGHT = 6
    }
}

/**
 * Lyrics copied from Genius's pages come with the page around them: a
 * "12 ContributorsSong Title Lyrics" header glued to the first line, sometimes
 * a "…Read More" blurb, "You might also like" in the middle, and "Embed" at
 * the end. Several services carry them verbatim, so they are taken off here,
 * whichever one answered. Timed lyrics never have them and are left alone.
 */
private fun LyricsFetch.Found.tidied(): LyricsFetch.Found {
    val tidy = tidyLyrics(plain ?: return this)
    return if (tidy.isEmpty()) this else copy(plain = tidy)
}

/** [text] without Genius's page furniture; also applied to lyrics saved before this existed. */
fun tidyLyrics(text: String): String =
    text
        .replaceFirst(GENIUS_HEADER, "")
        .replaceFirst(GENIUS_BLURB, "")
        .replace(GENIUS_ALSO_LIKE, "\n")
        .replaceFirst(GENIUS_EMBED, "")
        .trim()
        .ifEmpty { text }

private val GENIUS_HEADER = Regex("""^\s*\d+\s+Contributors?[^\n]*? Lyrics""")
private val GENIUS_BLURB = Regex("""^[^\n]*Read More\s*\n""")
private val GENIUS_ALSO_LIKE = Regex("""\s*You might also like\s*""")
private val GENIUS_EMBED = Regex("""\d*\s*Embed\s*$""")

/** Primary: free, no key, and the best source of synced LRC. */
class LrcLibProvider(private val client: LrcLibClient = LrcLibClient()) : LyricsProvider {
    override val label = "LRCLIB"
    override suspend fun fetch(
        title: String,
        artist: String?,
        album: String?,
        durationMs: Long
    ): LyricsFetch = client.fetch(title, artist, album, durationMs)
}

/**
 * Secondary: NetEase Cloud Music. Free and often has timed lyrics, including
 * for tracks Western databases miss entirely.
 */
class NeteaseLyricsProvider : LyricsProvider {

    override val label = "NetEase"

    override suspend fun fetch(
        title: String,
        artist: String?,
        album: String?,
        durationMs: Long
    ): LyricsFetch = withContext(Dispatchers.IO) {
        val query = listOfNotNull(artist?.takeIf { it.isNotBlank() }, title).joinToString(" ")
        val headers = mapOf("Referer" to "https://music.163.com/")

        val searchBody = Http.get(
            "https://music.163.com/api/search/get?s=${encodeQuery(query)}&type=1&limit=5",
            headers
        ) ?: return@withContext LyricsFetch.NotFound

        val songs = runCatching {
            JSONObject(searchBody).optJSONObject("result")?.optJSONArray("songs")
        }.getOrNull() ?: return@withContext LyricsFetch.NotFound
        if (songs.length() == 0) return@withContext LyricsFetch.NotFound

        // Prefer the candidate whose duration is closest to ours.
        var bestId = -1L
        var bestDelta = Long.MAX_VALUE
        for (i in 0 until songs.length()) {
            val song = songs.optJSONObject(i) ?: continue
            val id = song.optLong("id", -1L)
            if (id <= 0) continue
            val delta = if (durationMs > 0) {
                kotlin.math.abs(song.optLong("duration", 0L) - durationMs)
            } else {
                0L
            }
            if (delta < bestDelta) { bestDelta = delta; bestId = id }
        }
        if (bestId <= 0) return@withContext LyricsFetch.NotFound

        val lyricBody = Http.get(
            "https://music.163.com/api/song/lyric?id=$bestId&lv=1&kv=1&tv=-1",
            headers
        ) ?: return@withContext LyricsFetch.NotFound

        val root = runCatching { JSONObject(lyricBody) }.getOrNull()
            ?: return@withContext LyricsFetch.NotFound
        if (root.optBoolean("nolyric", false)) return@withContext LyricsFetch.Instrumental

        val synced = root.optJSONObject("lrc")?.optString("lyric")?.takeIf { it.isNotBlank() }
            ?: return@withContext LyricsFetch.NotFound
        // NetEase always ships LRC; derive the plain form from it.
        val plain = synced.lineSequence()
            .map { it.replace(Regex("""\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?]"""), "").trim() }
            .filter { it.isNotEmpty() && !Regex("""^\[[a-zA-Z#]+:.*]$""").matches(it) }
            .joinToString("\n")
            .takeIf { it.isNotBlank() }

        LyricsFetch.Found(plain, synced)
    }
}

/** Tertiary: plain text only, but good coverage of mainstream Western tracks. */
class LyricsOvhProvider : LyricsProvider {

    override val label = "lyrics.ovh"

    override suspend fun fetch(
        title: String,
        artist: String?,
        album: String?,
        durationMs: Long
    ): LyricsFetch = withContext(Dispatchers.IO) {
        if (artist.isNullOrBlank()) return@withContext LyricsFetch.NotFound
        val body = Http.get(
            "https://api.lyrics.ovh/v1/${encodeQuery(artist)}/${encodeQuery(title)}"
        ) ?: return@withContext LyricsFetch.NotFound

        val text = runCatching { JSONObject(body).optString("lyrics") }.getOrNull()
            ?.replace("\r\n", "\n")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return@withContext LyricsFetch.NotFound

        LyricsFetch.Found(text, null)
    }
}

private fun encodeQuery(value: String): String =
    java.net.URLEncoder.encode(value, "UTF-8")
