package com.exo.musicplayer.desktop

import com.exo.musicplayer.data.lyrics.ProviderBreaker
import com.exo.musicplayer.data.lyrics.ProviderPacer
import com.exo.musicplayer.data.net.Http
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/** The circuit breaker and the per-service pacing, on a hand-moved or virtual clock. */
@OptIn(ExperimentalCoroutinesApi::class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class LyricsPacingTest {

    private var now = 1_000L

    @Test
    fun theBreakerOpensAfterThreeFailuresInARow() {
        val breaker = ProviderBreaker(threshold = 3, pauseMs = 45_000, clock = { now })
        repeat(2) { breaker.record("NetEase", failed = true) }
        assertNull(breaker.skippedFor("NetEase"), "two failures are not enough")
        breaker.record("NetEase", failed = true)
        assertEquals(45_000L, breaker.skippedFor("NetEase"))
        assertNull(breaker.skippedFor("LRCLIB"), "one service's failures don't touch another")
    }

    @Test
    fun anAnswerInBetweenStartsTheCountAgain() {
        val breaker = ProviderBreaker(threshold = 3, pauseMs = 45_000, clock = { now })
        repeat(2) { breaker.record("Genius", failed = true) }
        breaker.record("Genius", failed = false)
        repeat(2) { breaker.record("Genius", failed = true) }
        assertNull(breaker.skippedFor("Genius"))
    }

    @Test
    fun theBreakerClosesAfterThePauseAndOnSuccess() {
        val breaker = ProviderBreaker(threshold = 3, pauseMs = 45_000, clock = { now })
        repeat(3) { breaker.record("LRCLIB", failed = true) }
        now += 44_000
        assertEquals(1_000L, breaker.skippedFor("LRCLIB"))
        now += 1_000
        assertNull(breaker.skippedFor("LRCLIB"), "tried again once the pause is over")
        // That one trial failing opens it again straight away.
        breaker.record("LRCLIB", failed = true)
        assertNotNull(breaker.skippedFor("LRCLIB"))
        now += 45_000
        breaker.record("LRCLIB", failed = false)
        assertNull(breaker.skippedFor("LRCLIB"))
        breaker.record("LRCLIB", failed = true)
        assertNull(breaker.skippedFor("LRCLIB"), "closed: one failure after a success doesn't open it")
    }

    @Test
    fun eachServiceGetsItsOwnSpacing() = runTest {
        val pacer = ProviderPacer(mapOf("A" to 150L, "B" to 300L), clock = { currentTime })
        val turns = (1..3).flatMap { listOf("A", "B") }.map { label ->
            async { pacer.await(label, maxWaitMs = 10_000); label to currentTime }
        }.awaitAll()
        assertEquals(listOf(0L, 150L, 300L), turns.filter { it.first == "A" }.map { it.second })
        assertEquals(listOf(0L, 300L, 600L), turns.filter { it.first == "B" }.map { it.second })
    }

    @Test
    fun aServiceThatSaidSlowDownIsLeftAloneThatLong() = runTest {
        val pacer = ProviderPacer(mapOf("A" to 100L), clock = { currentTime })
        pacer.await("A", 10_000)
        pacer.slowDown("A", Http.retryAfterMs("3"))
        assertTrue(pacer.await("A", 10_000))
        assertEquals(3_000L, currentTime)
        // Longer than a lookup may wait: refused at once, no turn taken.
        pacer.slowDown("A", 20_000)
        val before = currentTime
        assertFalse(pacer.await("A", 12_000))
        assertEquals(before, currentTime)
        // A service with no spacing of its own is still held off after a 429.
        pacer.slowDown("Unpaced", 2_000)
        assertTrue(pacer.await("Unpaced", 12_000))
        assertEquals(before + 2_000, currentTime)
    }

    @Test
    fun noMoreThanItsShareOfLookupsAreOutAtOnce() = runTest {
        val pacer = ProviderPacer(spacingMs = emptyMap(), concurrency = mapOf("A" to 2), clock = { currentTime })
        var out = 0
        var most = 0
        (1..6).map {
            async {
                pacer.withTurn("A", 10_000) {
                    out++
                    most = maxOf(most, out)
                    kotlinx.coroutines.delay(1_000)
                    out--
                }
            }
        }.awaitAll()
        assertEquals(2, most)
        assertEquals(3_000L, currentTime, "six one-second lookups, two at a time")
    }

    @Test
    fun aRefusedTurnRunsNothingAndFreesItsPlace() = runTest {
        val pacer = ProviderPacer(spacingMs = emptyMap(), concurrency = mapOf("A" to 1), clock = { currentTime })
        pacer.slowDown("A", 60_000)
        var ran = false
        assertNull(pacer.withTurn("A", 12_000) { ran = true })
        assertFalse(ran)
        kotlinx.coroutines.delay(60_000)
        assertEquals(1, pacer.withTurn("A", 12_000) { 1 })
    }

    @Test
    fun aServiceWithoutSpacingIsNotDelayed() = runTest {
        val pacer = ProviderPacer(emptyMap(), clock = { currentTime })
        repeat(5) { assertTrue(pacer.await("X", 0)) }
        assertEquals(0L, currentTime)
    }

    @Test
    fun retryAfterIsReadAsSecondsOrADateAndCapped() {
        assertEquals(7_000L, Http.retryAfterMs("7"))
        assertEquals(Http.DEFAULT_SLOW_DOWN_MS, Http.retryAfterMs(null))
        assertEquals(Http.DEFAULT_SLOW_DOWN_MS, Http.retryAfterMs("soon"))
        assertEquals(120_000L, Http.retryAfterMs("86400"))
        val at = ZonedDateTime.of(2026, 11, 27, 12, 0, 30, 0, ZoneOffset.UTC)
        val nowMs = at.minusSeconds(30).toInstant().toEpochMilli()
        assertEquals(30_000L, Http.retryAfterMs(at.format(DateTimeFormatter.RFC_1123_DATE_TIME), nowMs))
    }
}
