package com.exo.musicplayer.data.lyrics

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Milliseconds on a clock that only goes forward; tests hand in a virtual one. */
typealias LyricsClock = () -> Long

/** The real clock: monotonic, so a change of the wall clock can't open or shut anything. */
val SYSTEM_LYRICS_CLOCK: LyricsClock = { System.nanoTime() / 1_000_000 }

/**
 * Stops asking a service that keeps failing, for a while.
 *
 * When a service is down (or the proxy can't reach it), every song still waited
 * for it to fail, up to its whole timeout, and a library-sized run paid that a
 * thousand times. After [threshold] failures in a row it is skipped for
 * [pauseMs]: each lookup gets an Error for it at once, which keeps the
 * accounting honest (the song isn't "not found", it isn't marked, and a run
 * that can reach nothing still stops early). When the pause is over the next
 * lookup asks it again; one answer of any kind closes the breaker, one more
 * failure opens it for another pause.
 */
class ProviderBreaker(
    val threshold: Int = 3,
    val pauseMs: Long = 45_000,
    private val clock: LyricsClock = SYSTEM_LYRICS_CLOCK
) {
    private class State(var failures: Int = 0, var openedAt: Long = 0)

    private val states = HashMap<String, State>()

    /** How much longer [label] is being skipped, or null when it may be asked. */
    @Synchronized
    fun skippedFor(label: String): Long? {
        val state = states[label] ?: return null
        if (state.failures < threshold) return null
        val left = state.openedAt + pauseMs - clock()
        return left.takeIf { it > 0 }
    }

    /** What asking [label] came to: [failed] for an Error, otherwise it answered. */
    @Synchronized
    fun record(label: String, failed: Boolean) {
        val state = states.getOrPut(label) { State() }
        if (!failed) {
            state.failures = 0
            return
        }
        state.failures++
        if (state.failures >= threshold) state.openedAt = clock()
    }
}

/**
 * A polite request rate for each service, shared by every song in flight.
 *
 * The bulk tool used to rest each worker 250 ms between songs, which says
 * nothing about how hard any one service is asked once its songs overlap.
 * Here each service gets its own minimum spacing between lookups ([spacingMs],
 * by label) and its own limit on lookups out at once ([concurrency]), whatever
 * the number of songs in flight; and a service that answered 429 is left
 * alone for as long as it asked ([slowDown]).
 */
class ProviderPacer(
    private val spacingMs: Map<String, Long> = DEFAULT_SPACING_MS,
    private val concurrency: Map<String, Int> = DEFAULT_CONCURRENCY,
    private val clock: LyricsClock = SYSTEM_LYRICS_CLOCK
) {
    private val nextAt = HashMap<String, Long>()
    private val quietUntil = HashMap<String, Long>()
    private val slots = HashMap<String, Semaphore>()

    private fun slotsFor(label: String): Semaphore? = synchronized(this) {
        val limit = concurrency[label] ?: return null
        slots.getOrPut(label) { Semaphore(limit.coerceAtLeast(1)) }
    }

    /**
     * Runs [block] as one lookup on [label]: once one of its places is free and its
     * turn has come. Null, without running it, when its turn is more than
     * [maxWaitMs] away (see [await]).
     */
    suspend fun <T> withTurn(label: String, maxWaitMs: Long, block: suspend () -> T): T? {
        val places = slotsFor(label) ?: return if (await(label, maxWaitMs)) block() else null
        return places.withPermit { if (await(label, maxWaitMs)) block() else null }
    }

    /**
     * Waits for [label]'s next turn and takes it. False, without waiting or taking
     * a turn, when that turn is more than [maxWaitMs] away (the service asked to be
     * left alone for longer than a lookup may wait).
     */
    suspend fun await(label: String, maxWaitMs: Long): Boolean {
        val wait = synchronized(this) {
            val spacing = spacingMs[label] ?: 0L
            val now = clock()
            val quiet = quietUntil[label] ?: Long.MIN_VALUE
            if (spacing <= 0 && quiet <= now) return true
            val slot = maxOf(now, nextAt[label] ?: Long.MIN_VALUE, quiet)
            if (slot - now > maxWaitMs) return false
            nextAt[label] = slot + spacing
            slot - now
        }
        if (wait > 0) delay(wait)
        return true
    }

    /** [label] said "too many requests": nothing goes to it for [ms]. */
    @Synchronized
    fun slowDown(label: String, ms: Long) {
        val until = clock() + ms
        quietUntil[label] = maxOf(quietUntil[label] ?: Long.MIN_VALUE, until)
    }

    companion object {
        /**
         * Lookups per service, at most one per this many ms. A lookup is one to
         * three requests (LRCLIB's exact match then its search; NetEase's search
         * then the lyric). LRCLIB is asked for every song and is the quickest, so
         * it gets the most room; NetEase and Genius are touchy about scraping.
         */
        val DEFAULT_SPACING_MS: Map<String, Long> = mapOf(
            "LRCLIB" to 150L,
            "NetEase" to 300L,
            "lyrics.ovh" to 250L,
            "Genius" to 300L
        )

        /**
         * Lookups out at once per service. The old bulk tool had five songs going,
         * each asking one service at a time, so five was the most any service saw;
         * these stay around that. lyrics.ovh takes five seconds or so to say
         * "not found", so it may have one more waiting on it.
         */
        val DEFAULT_CONCURRENCY: Map<String, Int> = mapOf(
            "LRCLIB" to 6,
            "NetEase" to 4,
            "lyrics.ovh" to 6,
            "Genius" to 4
        )
    }
}

/**
 * Answers already had in one bulk run, so a song that is in the library twice
 * is looked up once. Only answers are kept (an Error is asked again), and it
 * lives as long as the run that made it.
 */
class LyricsRunMemo {
    private val answers = ConcurrentHashMap<String, Pair<LyricsFetch, String?>>()

    internal fun get(key: String): Pair<LyricsFetch, String?>? = answers[key]

    internal fun put(key: String, answer: Pair<LyricsFetch, String?>) {
        if (answer.first !is LyricsFetch.Error) answers[key] = answer
    }

    val size: Int get() = answers.size

    internal companion object {
        private val SPACES = Regex("""\s+""")

        /**
         * Everything the services are given, tidied the way they treat it anyway:
         * case and runs of spaces don't change an answer. Seconds, as LRCLIB
         * matches on; the services that are asked are part of it too.
         */
        fun key(title: String, artist: String?, album: String?, durationMs: Long, asked: List<String>): String {
            fun norm(text: String?) = text?.trim()?.replace(SPACES, " ")?.lowercase(Locale.ROOT).orEmpty()
            return listOf(norm(title), norm(artist), norm(album), (durationMs / 1000).toString(), asked.joinToString(","))
                .joinToString("\u0000")
        }
    }
}
