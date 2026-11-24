package com.exo.musicplayer.desktop

import com.exo.musicplayer.desktop.audio.AbLoop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The A-B loop: exactly the stretch from A to B, from memory, seamlessly round. */
class AbLoopTest {

    /** At 1,000 frames a second: one second of history, two minutes kept at most. */
    private val rate = 1_000

    /** Frames whose left sample is their own number, so what comes back can be checked exactly. */
    private fun block(from: Int, frames: Int) = FloatArray(frames * 2) { i -> if (i % 2 == 0) (from + i / 2).toFloat() else -1f }

    /** Plays the file from [from] to [to] through the loop's ears. */
    private fun AbLoop.feed(from: Int, to: Int, size: Int = 100) {
        var at = from
        while (at < to) {
            val n = minOf(size, to - at)
            heard(block(at, n), (at + n).toLong())
            at += n
        }
    }

    private fun frameNumbers(samples: FloatArray) = (0 until samples.size / 2).map { samples[it * 2].toInt() }

    @Test
    fun itGoesRoundExactlyFromAToB() {
        val loop = AbLoop(rate)
        loop.feed(0, 10_000)
        // A is heard while the output still holds what came after it: 9,500 heard, 10,000 decoded.
        loop.open(9_500)
        assertEquals(AbLoop.State.RECORDING, loop.state)
        loop.feed(10_000, 15_300)
        assertTrue(loop.close(15_000), "B closes the loop")
        assertTrue(loop.fromMemory, "kept in memory")
        assertEquals(9_500L, loop.startFrame)
        assertEquals(15_000L, loop.endFrame)

        // Two turns and a bit, in blocks of 1,024: every frame of A..B, in order, then A again.
        val played = ArrayList<Int>()
        repeat(13) { played += frameNumbers(loop.next(1_024)) }
        val turn = (9_500 until 15_000).toList()
        assertEquals(turn, played.take(5_500))
        assertEquals(turn, played.drop(5_500).take(5_500))
        assertEquals(9_500, played[11_000])
    }

    @Test
    fun aJumpInsideTheLoopStaysInIt() {
        val loop = AbLoop(rate)
        loop.feed(0, 3_000)
        loop.open(2_000)
        loop.feed(3_000, 6_000)
        loop.close(5_000)
        assertTrue(loop.seek(4_200))
        assertEquals(4_200L, loop.positionFrame)
        assertEquals(4_200, frameNumbers(loop.next(10)).first())
        assertFalse(loop.seek(1_000), "before A is outside the loop")
        assertFalse(loop.seek(5_000), "B itself is the start of the next turn, not inside")
    }

    @Test
    fun aDoubleClickIsNotALoop() {
        val loop = AbLoop(rate)
        loop.feed(0, 2_000)
        loop.open(1_500)
        assertFalse(loop.close(1_550), "50 ms after A")
        assertEquals(AbLoop.State.RECORDING, loop.state)
    }

    @Test
    fun anAFurtherBackThanTheHistoryLoopsBySeekingInstead() {
        val loop = AbLoop(rate)
        loop.feed(0, 10_000)
        loop.open(3_000) // seven seconds back; the history holds one
        loop.feed(10_000, 12_000)
        assertTrue(loop.close(11_000))
        assertFalse(loop.fromMemory)
        assertEquals(AbLoop.State.LOOPING, loop.state)
    }

    @Test
    fun aLoopTooLongToKeepLoopsBySeekingInstead() {
        val loop = AbLoop(rate)
        loop.feed(0, 1_000)
        loop.open(500)
        loop.feed(1_000, 1_000 + AbLoop.MAX_SECONDS * rate + 1_000, size = 10_000)
        assertTrue(loop.close(AbLoop.MAX_SECONDS * rate + 1_500L))
        assertFalse(loop.fromMemory)
    }

    @Test
    fun offIsOff() {
        val loop = AbLoop(rate)
        loop.feed(0, 3_000)
        loop.open(2_000)
        loop.feed(3_000, 5_000)
        loop.close(4_000)
        loop.clear()
        assertEquals(AbLoop.State.OFF, loop.state)
        assertEquals(-1L, loop.startFrame)
        assertFalse(loop.seek(2_500))
    }
}
