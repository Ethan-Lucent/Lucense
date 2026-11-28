package com.exo.musicplayer.data.lyrics

import com.exo.musicplayer.data.net.Http
import com.exo.musicplayer.data.net.HttpTally
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** One place lyrics can come from. */
interface LyricsProvider {
    val label: String
    suspend fun fetch(title: String, artist: String?, album: String?, durationMs: Long): LyricsFetch
}

/**
 * Tries each provider in turn until one returns lyrics.
 *
 * Ordered by what they give back, not by popularity: LRCLIB first because it is
 * the only one of the three that reliably carries *timed* LRC, then NetEase
 * which often does, then lyrics.ovh which is plain text but has broad Western
 * coverage. One service being down never blocks the rest, but it does stop the
 * answer from being "not found": only services that really answered can say
 * a song has no lyrics, and the bulk tool marks a song checked on that.
 */
private val LYRIC_BRACKETS = Regex("""\(.*?\)|\[.*?]|【.*?】""")
private val LYRIC_SPACES = Regex("""\s+""")
private val LYRIC_ARTIST_SPLIT = Regex("""\s*(,|&|\bx\b|\bfeat\.?|\bft\.?)\s*""", RegexOption.IGNORE_CASE)

class LyricsProviderChain(
    private val providers: List<LyricsProvider>,
    /** Whether the service by this name is to be asked; Settings can turn each off. */
    private val isOn: (String) -> Boolean = { true },
    /** How long one service gets to answer; a hung request must not hold the song forever. */
    private val providerTimeoutMs: Long = PROVIDER_TIMEOUT_MS
) {
    /**
     * Where each service's lookup runs. Not the caller's scope: the services
     * block in plain socket reads that a timeout can't interrupt, so a service
     * that is given up on finishes (or fails) on its own, and its answer is dropped.
     */
    private val calls = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** What the services are called, in the order they are asked. */
    val labels: List<String> get() = providers.map { it.label }

    /**
     * Looks the song up as named, then by pieces of its name when that finds
     * nothing: downloaded files are often called "Artist - Song (Official
     * Video) [Channel]" or credit "A, B x C", which no lyrics service knows.
     */
    suspend fun fetch(
        title: String,
        artist: String?,
        album: String?,
        durationMs: Long
    ): Pair<LyricsFetch, String?> {
        val enabled = providers.filter { isOn(it.label) }
        if (enabled.isEmpty()) {
            return LyricsFetch.Error("All the lyrics services are turned off in Settings.", offline = true) to null
        }
        val pieces = segments(title, artist)
        if (pieces.isEmpty()) return LyricsFetch.NotFound to null
        val round = Round()
        for ((pieceTitle, pieceArtist) in pieces) {
            val result = fetchOnce(enabled, round, pieceTitle, pieceArtist, album, durationMs)
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

    /** What one song's lookup has learnt so far, across the spellings of its name. */
    private class Round {
        var anyAnswered = false
        /** Services that failed once: not asked again for this song, and "not found" is off the table. */
        val down = linkedSetOf<String>()
        var lastError: String? = null
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

    private suspend fun fetchOnce(
        enabled: List<LyricsProvider>,
        round: Round,
        title: String,
        artist: String?,
        album: String?,
        durationMs: Long
    ): Pair<LyricsFetch, String?> {
        var sawInstrumental = false
        for (provider in enabled) {
            if (provider.label in round.down) continue
            when (val result = ask(provider, title, artist, album, durationMs)) {
                is LyricsFetch.Found -> return result.tidied() to provider.label
                is LyricsFetch.Instrumental -> { round.anyAnswered = true; sawInstrumental = true }
                is LyricsFetch.NotFound -> round.anyAnswered = true
                is LyricsFetch.Error -> { round.down += provider.label; round.lastError = result.message }
            }
        }
        return (if (sawInstrumental) LyricsFetch.Instrumental else LyricsFetch.NotFound) to null
    }

    /**
     * One service's answer, with its failures said out loud. Most services say
     * NotFound when their request failed; the [HttpTally] catches that, and a
     * service that throws or takes longer than [providerTimeoutMs] is an Error.
     */
    private suspend fun ask(
        provider: LyricsProvider,
        title: String,
        artist: String?,
        album: String?,
        durationMs: Long
    ): LyricsFetch {
        val tally = HttpTally()
        val call = calls.async(Http.counting(tally)) { provider.fetch(title, artist, album, durationMs) }
        val result = try {
            withTimeoutOrNull(providerTimeoutMs) { call.await() }
                ?: LyricsFetch.Error("${provider.label} took too long to answer.")
        } catch (cancelled: CancellationException) {
            // Stopping the run is not a failed service: caught as one, a cancelled
            // lookup was recorded as checked when it never had been.
            currentCoroutineContext().ensureActive()
            LyricsFetch.Error("${provider.label} gave up.")
        } catch (failure: Throwable) {
            LyricsFetch.Error("${provider.label} failed: ${failure.message ?: failure.javaClass.simpleName}")
        } finally {
            call.cancel()
        }
        return if (result is LyricsFetch.NotFound && tally.failed > 0) {
            LyricsFetch.Error("Couldn't reach ${provider.label}.")
        } else {
            result
        }
    }

    private companion object {
        const val PROVIDER_TIMEOUT_MS = 12_000L
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
