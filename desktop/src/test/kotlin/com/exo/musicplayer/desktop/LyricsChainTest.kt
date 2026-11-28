package com.exo.musicplayer.desktop

import com.exo.musicplayer.data.lyrics.LyricsFetch
import com.exo.musicplayer.data.lyrics.LyricsProvider
import com.exo.musicplayer.data.lyrics.LyricsProviderChain
import com.exo.musicplayer.data.lyrics.LyricsRunMemo
import com.exo.musicplayer.data.lyrics.ProviderBreaker
import com.exo.musicplayer.data.lyrics.tidyLyrics
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * The lyrics chain asks its services side by side now. These prove that it
 * still decides exactly as the old one-at-a-time chain did, and never later,
 * by running both over thousands of made-up songs in virtual time: no network,
 * no real waiting.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class LyricsChainTest {

    // ---- The old chain, kept verbatim as the reference ----

    /** LyricsProviderChain as it was before the services were asked side by side (master, 1.57.1). */
    private class SequentialChain(
        private val providers: List<LyricsProvider>,
        private val isOn: (String) -> Boolean,
        private val providerTimeoutMs: Long,
        callContext: kotlin.coroutines.CoroutineContext
    ) {
        private val calls = CoroutineScope(SupervisorJob() + callContext)

        suspend fun fetch(title: String, artist: String?, album: String?, durationMs: Long): Pair<LyricsFetch, String?> {
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
                if (!round.anyAnswered) break
            }
            return when {
                !round.anyAnswered -> LyricsFetch.Error(
                    round.lastError ?: "Couldn't reach the lyrics services.", offline = true
                ) to null
                round.down.isNotEmpty() -> LyricsFetch.Error(
                    "${round.down.joinToString()} didn't answer, so this isn't a definite \"not found\"."
                ) to null
                else -> LyricsFetch.NotFound to null
            }
        }

        private class Round {
            var anyAnswered = false
            val down = linkedSetOf<String>()
            var lastError: String? = null
        }

        private suspend fun fetchOnce(
            enabled: List<LyricsProvider>, round: Round, title: String, artist: String?, album: String?, durationMs: Long
        ): Pair<LyricsFetch, String?> {
            var sawInstrumental = false
            for (provider in enabled) {
                if (provider.label in round.down) continue
                when (val result = ask(provider, title, artist, album, durationMs)) {
                    is LyricsFetch.Found -> return result.tidiedForTest() to provider.label
                    is LyricsFetch.Instrumental -> { round.anyAnswered = true; sawInstrumental = true }
                    is LyricsFetch.NotFound -> round.anyAnswered = true
                    is LyricsFetch.Error -> { round.down += provider.label; round.lastError = result.message }
                }
            }
            return (if (sawInstrumental) LyricsFetch.Instrumental else LyricsFetch.NotFound) to null
        }

        private suspend fun ask(provider: LyricsProvider, title: String, artist: String?, album: String?, durationMs: Long): LyricsFetch {
            val call = calls.async { provider.fetch(title, artist, album, durationMs) }
            return try {
                withTimeoutOrNull(providerTimeoutMs) { call.await() }
                    ?: LyricsFetch.Error("${provider.label} took too long to answer.")
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                LyricsFetch.Error("${provider.label} gave up.")
            } catch (failure: Throwable) {
                LyricsFetch.Error("${provider.label} failed: ${failure.message ?: failure.javaClass.simpleName}")
            } finally {
                call.cancel()
            }
        }
    }

    // ---- Made-up services ----

    private sealed interface Act {
        data class Answer(val fetch: LyricsFetch) : Act
        data class Throws(val message: String) : Act
        data object Hangs : Act
    }

    /** What a service does for each spelling it is asked about: an act, after a latency. */
    private class Scripted(override val label: String, val script: Map<Pair<String, String?>, Pair<Act, Long>>) : LyricsProvider {
        val calls = AtomicInteger()
        val cancelled = AtomicInteger()
        val active = AtomicInteger()
        override suspend fun fetch(title: String, artist: String?, album: String?, durationMs: Long): LyricsFetch {
            calls.incrementAndGet()
            active.incrementAndGet()
            val (act, latency) = script[title to artist] ?: (Act.Answer(LyricsFetch.NotFound) to 10L)
            try {
                delay(latency)
                return when (act) {
                    is Act.Answer -> act.fetch
                    is Act.Throws -> error(act.message)
                    Act.Hangs -> awaitCancellation()
                }
            } catch (c: CancellationException) {
                cancelled.incrementAndGet()
                throw c
            } finally {
                active.decrementAndGet()
            }
        }
    }

    private val names = listOf(
        "Song" to "Artist",
        "Song (Official Video)" to "A, B x C",
        "Artist - Song (Official Video) [HD]" to "Uploader",
        "Artist - Song" to null,
        "Song [Live]" to "A feat. B",
        "Song" to null
    )

    /** The spellings the chain will ask about; the same rule as the chain's own. */
    private fun spellings(title: String, artist: String?): List<Pair<String, String?>> = segments(title, artist)

    private fun randomAct(random: Random, p: Int, s: Int): Act = when (random.nextInt(100)) {
        in 0 until 25 -> Act.Answer(
            LyricsFetch.Found(
                // Genius page furniture now and then, which the chain tidies.
                if (random.nextBoolean()) "words $p/$s" else "3 Contributors Song Lyrics words $p/$s Embed",
                if (random.nextBoolean()) "[00:01.00]words $p/$s" else null
            )
        )
        in 25 until 60 -> Act.Answer(LyricsFetch.NotFound)
        in 60 until 70 -> Act.Answer(LyricsFetch.Instrumental)
        in 70 until 85 -> Act.Answer(LyricsFetch.Error("down $p/$s"))
        in 85 until 93 -> Act.Throws("boom $p/$s")
        else -> Act.Hangs
    }

    /** Latencies from instant to past the timeout, never exactly on it. */
    private fun randomLatency(random: Random): Long = when (random.nextInt(10)) {
        0 -> 0L
        in 1..5 -> random.nextLong(1, 1_500)
        in 6..8 -> random.nextLong(1_500, 9_000)
        else -> random.nextLong(TIMEOUT + 1, TIMEOUT + 6_000)
    }

    private class Scenario(
        val title: String,
        val artist: String?,
        val scripts: List<Map<Pair<String, String?>, Pair<Act, Long>>>,
        val off: Set<String>,
        val hedgeMs: Long,
        val maxInFlight: Int
    )

    private fun scenario(random: Random): Scenario {
        val (title, artist) = names[random.nextInt(names.size)]
        val count = 1 + random.nextInt(4)
        val spelled = spellings(title, artist)
        val scripts = (0 until count).map { p ->
            spelled.withIndex().associate { (s, key) -> key to (randomAct(random, p, s) to randomLatency(random)) }
        }
        val off = (0 until count).filter { random.nextInt(8) == 0 }.map { "P$it" }.toSet()
        val hedge = listOf(0L, 1L, 300L, 800L, 2_500L, Long.MAX_VALUE)[random.nextInt(6)]
        return Scenario(title, artist, scripts, off, hedge, listOf(1, 2, 4, 6, 16)[random.nextInt(5)])
    }

    private suspend fun TestScope.timed(block: suspend () -> Pair<LyricsFetch, String?>): Pair<Pair<LyricsFetch, String?>, Long> {
        val started = currentTime
        val answer = block()
        return answer to (currentTime - started)
    }

    @Test
    fun theParallelChainDecidesExactlyAsTheSequentialOneAndNeverLater() {
        val random = Random(20261127)
        var faster = 0L
        var slowerTotal = 0L
        var fasterTotal = 0L
        repeat(ROUNDS) { round ->
            val scenario = scenario(random)
            runTest(timeout = 1.minutes) {
                val calls = StandardTestDispatcher(testScheduler)
                fun services() = scenario.scripts.mapIndexed { p, script -> Scripted("P$p", script) }
                val oldServices = services()
                val reference = SequentialChain(oldServices, { it !in scenario.off }, TIMEOUT, calls)
                val newServices = services()
                val chain = LyricsProviderChain(
                    newServices, { it !in scenario.off }, TIMEOUT,
                    hedgeMs = scenario.hedgeMs, maxInFlight = scenario.maxInFlight,
                    breaker = null, pacer = null, callContext = calls
                )
                val (expected, oldTime) = timed { reference.fetch(scenario.title, scenario.artist, null, 0) }
                val (actual, newTime) = timed { chain.fetch(scenario.title, scenario.artist, null, 0) }
                val where = "round $round, hedge ${scenario.hedgeMs}, cap ${scenario.maxInFlight}, " +
                    "${scenario.title} / ${scenario.artist}, off ${scenario.off}"
                assertEquals(expected, actual, where)
                assertTrue(newTime <= oldTime, "slower: $newTime > $oldTime ms at $where")
                // Everything it started has stopped: nothing is left running once the song is decided.
                runCurrent()
                assertEquals(List(newServices.size) { 0 }, newServices.map { it.active.get() }, "left running at $where")
                slowerTotal += oldTime
                fasterTotal += newTime
                if (newTime < oldTime) faster++
                // One at a time asks exactly what the old chain asked.
                if (scenario.hedgeMs == Long.MAX_VALUE) {
                    assertEquals(oldServices.map { it.calls.get() }, newServices.map { it.calls.get() }, where)
                }
            }
        }
        println("property: $ROUNDS songs, faster in $faster, virtual time old=${slowerTotal} ms new=${fasterTotal} ms")
    }

    /** The precedence, spelled out: a slow first service with lyrics beats a quick second one. */
    @Test
    fun aSlowFirstServiceStillWinsOverAQuickSecond() = runTest {
        val calls = StandardTestDispatcher(testScheduler)
        val slow = Scripted("Slow", mapOf(("Song" to "Artist") to (Act.Answer(LyricsFetch.Found("first", null)) to 5_000L)))
        val quick = Scripted("Quick", mapOf(("Song" to "Artist") to (Act.Answer(LyricsFetch.Found("second", null)) to 100L)))
        val chain = LyricsProviderChain(listOf(slow, quick), breaker = null, pacer = null, callContext = calls)
        val (result, source) = chain.fetch("Song", "Artist", null, 0)
        assertEquals("Slow", source)
        assertEquals("first", (result as LyricsFetch.Found).plain)
        assertEquals(5_000L, currentTime)
        assertEquals(1, quick.calls.get(), "the second was asked alongside")
    }

    /** As soon as a service ahead has the lyrics, the ones behind it are cancelled. */
    @Test
    fun servicesBehindAFoundAreCancelledAtOnce() = runTest {
        val calls = StandardTestDispatcher(testScheduler)
        val first = Scripted("First", mapOf(("Song" to "Artist") to (Act.Answer(LyricsFetch.Found("la", null)) to 2_000L)))
        val second = Scripted("Second", mapOf(("Song" to "Artist") to (Act.Hangs to 0L)))
        val third = Scripted("Third", mapOf(("Song" to "Artist") to (Act.Hangs to 0L)))
        val chain = LyricsProviderChain(listOf(first, second, third), hedgeMs = 500, breaker = null, pacer = null, callContext = calls)
        val (_, source) = chain.fetch("Song", "Artist", null, 0)
        assertEquals("First", source)
        assertEquals(2_000L, currentTime, "decided when the first answered, not at the others' timeout")
        runCurrent()
        assertEquals(1, second.cancelled.get())
        assertEquals(1, third.cancelled.get())
    }

    /** Stop: the lookup is cancelled with every service it had out, and nothing comes back. */
    @Test
    fun stoppingALookupCancelsEveryServiceOut() = runTest {
        val calls = StandardTestDispatcher(testScheduler)
        val hangs = mapOf(("Song" to "Artist") to (Act.Hangs to 0L), ("Song" to null) to (Act.Hangs to 0L))
        val services = (1..3).map { Scripted("S$it", hangs) }
        val chain = LyricsProviderChain(services, hedgeMs = 100, maxInFlight = 4, breaker = null, pacer = null, callContext = calls)
        val lookup = async { chain.fetch("Song", "Artist", null, 0) }
        delay(1_000)
        // "Song" by "Artist" is two spellings: six cells, of which the cap lets four out.
        assertEquals(4, services.sumOf { it.calls.get() })
        lookup.cancel()
        lookup.join()
        runCurrent()
        assertTrue(lookup.isCancelled)
        assertEquals(services.map { it.calls.get() }, services.map { it.cancelled.get() })
        assertEquals(listOf(0, 0, 0), services.map { it.active.get() })
    }

    /** A service switched off is never asked, however the others hedge. */
    @Test
    fun aServiceSwitchedOffIsNeverAsked() = runTest {
        val calls = StandardTestDispatcher(testScheduler)
        val off = Scripted("Off", emptyMap())
        val on = Scripted("On", mapOf(("Song" to "Artist") to (Act.Answer(LyricsFetch.NotFound) to 3_000L)))
        val chain = LyricsProviderChain(listOf(on, off), isOn = { it != "Off" }, hedgeMs = 1, breaker = null, pacer = null, callContext = calls)
        chain.fetch("Artist - Song (Live)", "Artist", null, 0)
        assertEquals(0, off.calls.get())
    }

    // ---- The breaker in the chain ----

    @Test
    fun aServiceThatKeepsFailingIsSkippedAsAnErrorThenAskedAgain() = runTest {
        val calls = StandardTestDispatcher(testScheduler)
        val failing = AtomicInteger()
        val down = object : LyricsProvider {
            override val label = "Down"
            override suspend fun fetch(title: String, artist: String?, album: String?, durationMs: Long): LyricsFetch {
                failing.incrementAndGet()
                return LyricsFetch.Error("Couldn't reach the lyrics service.")
            }
        }
        val breaker = ProviderBreaker(threshold = 3, pauseMs = 30_000, clock = { currentTime })
        val chain = LyricsProviderChain(listOf(down), breaker = breaker, pacer = null, callContext = calls)
        repeat(3) { chain.fetch("Song $it", "Artist", null, 0) }
        assertEquals(3, failing.get())
        val (skipped, _) = chain.fetch("Song 4", "Artist", null, 0)
        assertEquals(3, failing.get(), "not asked while the breaker is open")
        assertTrue(skipped is LyricsFetch.Error && skipped.offline && "temporarily skipped" in skipped.message, "got $skipped")
        delay(30_001)
        chain.fetch("Song 5", "Artist", null, 0)
        assertEquals(4, failing.get(), "asked again after the pause")
    }

    // ---- One run's memory ----

    @Test
    fun aSongHeldTwiceInARunIsLookedUpOnceButErrorsAreAskedAgain() = runTest {
        val calls = StandardTestDispatcher(testScheduler)
        val found = Scripted("Found", mapOf(("Song" to "Artist") to (Act.Answer(LyricsFetch.Found("la", null)) to 10L)))
        val chain = LyricsProviderChain(listOf(found), breaker = null, pacer = null, callContext = calls)
        val memo = LyricsRunMemo()
        val first = chain.fetch("Song", "Artist", null, 200_000, memo)
        val second = chain.fetch("song ", " artist", null, 200_400, memo)
        assertEquals(first, second)
        assertEquals(1, found.calls.get())
        // Without the run's memo (another run, or the Lyrics panel) it is asked again.
        chain.fetch("Song", "Artist", null, 200_000)
        assertEquals(2, found.calls.get())

        val failing = Scripted("Failing", mapOf(("Song" to "Artist") to (Act.Answer(LyricsFetch.Error("down")) to 10L)))
        val failingChain = LyricsProviderChain(listOf(failing), breaker = null, pacer = null, callContext = calls)
        failingChain.fetch("Song", "Artist", null, 0, memo)
        failingChain.fetch("Song", "Artist", null, 0, memo)
        assertEquals(2, failing.calls.get())
    }

    /** The defaults the app runs with still decide as the old chain did on a plain song (real dispatcher). */
    @Test
    fun theDefaultChainOnRealThreadsPicksTheFirstServiceWithLyrics() = kotlinx.coroutines.runBlocking(Dispatchers.Default) {
        val none = object : LyricsProvider {
            override val label = "None"
            override suspend fun fetch(title: String, artist: String?, album: String?, durationMs: Long): LyricsFetch {
                delay(1_200); return LyricsFetch.NotFound
            }
        }
        val second = object : LyricsProvider {
            override val label = "Second"
            override suspend fun fetch(title: String, artist: String?, album: String?, durationMs: Long) =
                LyricsFetch.Found("two", "[00:01.00]two")
        }
        val started = System.nanoTime()
        val (result, source) = LyricsProviderChain(listOf(none, second)).fetch("Song", "Artist", null, 0)
        assertEquals("Second", source)
        assertEquals(LyricsFetch.Found("two", "[00:01.00]two"), result)
        assertTrue(System.nanoTime() - started < 5_000_000_000L)
    }

    private companion object {
        const val ROUNDS = 5_000
        const val TIMEOUT = 12_000L

        private val LYRIC_BRACKETS = Regex("""\(.*?\)|\[.*?]|【.*?】""")
        private val LYRIC_SPACES = Regex("""\s+""")
        private val LYRIC_ARTIST_SPLIT = Regex("""\s*(,|&|\bx\b|\bfeat\.?|\bft\.?)\s*""", RegexOption.IGNORE_CASE)

        /** The old chain's spellings, verbatim. */
        fun segments(title: String, artist: String?): List<Pair<String, String?>> {
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

        /** The old chain's tidying of a Found, verbatim. */
        fun LyricsFetch.Found.tidiedForTest(): LyricsFetch.Found {
            val tidy = tidyLyrics(plain ?: return this)
            return if (tidy.isEmpty()) this else copy(plain = tidy)
        }
    }
}
