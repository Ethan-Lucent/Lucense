package com.exo.musicplayer.desktop

import com.exo.musicplayer.desktop.audio.OutputPacing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How much audio is kept queued ahead of the speakers: what an effect's delay comes down to. */
class OutputPacingTest {

    private val rate = 44_100
    private val logged = mutableListOf<String>()

    /** As the engine sets it up: a 186 ms line, 23 ms blocks. */
    private fun pacing(startMs: Int?) =
        OutputPacing(rate, startMs) { logged += it }.apply { fit(capacityFrames = 8192, blockFrames = 1024) }

    private fun frames(ms: Int) = ms * rate / 1000

    @Test
    fun itWaitsForExactlyWhatIsQueuedAboveTheTarget() {
        val pacing = pacing(60)
        assertEquals(frames(60), pacing.targetFrames)
        assertEquals(0L, pacing.waitNanos(0))
        assertEquals(0L, pacing.waitNanos(frames(60)))
        assertEquals(10_000_000L, pacing.waitNanos(frames(60) + 441))
        assertTrue(logged.single().contains("keeping 60 ms queued"), logged.toString())
    }

    @Test
    fun runningNearlyDryRaisesTheTargetForTheRestOfTheSessionAndSaysSo() {
        val pacing = pacing(60)
        pacing.beforeWrite(frames(50))
        pacing.beforeWrite(100)
        assertEquals(1, pacing.underruns)
        assertEquals(frames(80), pacing.targetFrames)
        assertTrue(logged.last().contains("keeping 80 ms queued"), logged.last())

        // A new song or a seek doesn't put it back down.
        pacing.restart()
        pacing.fit(capacityFrames = 8192, blockFrames = 1024)
        assertEquals(frames(80), pacing.targetFrames)
    }

    @Test
    fun anEmptyLineAfterAStartASeekOrAPauseIsNoUnderrun() {
        val pacing = pacing(60)
        pacing.beforeWrite(0) // the first write after the line opened
        pacing.beforeWrite(frames(50))
        pacing.restart() // a seek, or a pause the line ran dry in
        pacing.beforeWrite(0)
        assertEquals(0, pacing.underruns)
        assertEquals(frames(60), pacing.targetFrames)
    }

    @Test
    fun theTargetNeverOutgrowsTheLine() {
        val pacing = pacing(160)
        repeat(10) {
            pacing.beforeWrite(frames(50))
            pacing.beforeWrite(0)
        }
        assertEquals(8192 - 1024, pacing.targetFrames)
        assertEquals(1, pacing.underruns, "at the ceiling there is nowhere left to go, so it stops counting")
    }

    @Test
    fun keptFullItNeitherWaitsNorAdapts() {
        val pacing = pacing(null)
        assertTrue(pacing.filling)
        assertEquals(0L, pacing.waitNanos(8192))
        pacing.beforeWrite(frames(50))
        pacing.beforeWrite(0)
        assertEquals(0, pacing.underruns)
        assertTrue(logged.single().contains("keeping it full"), logged.toString())
    }

    @Test
    fun linuxPacesByDefaultAndThePropertyOverridesEitherWay() {
        assertEquals(OutputPacing.LINUX_START_MS, OutputPacing.startMs(null, linux = true))
        assertNull(OutputPacing.startMs(null, linux = false))
        assertEquals(90, OutputPacing.startMs("90", linux = false))
        assertEquals(90, OutputPacing.startMs(" 90 ", linux = true))
        assertNull(OutputPacing.startMs("fill", linux = true))
        assertNull(OutputPacing.startMs("0", linux = true))
        assertEquals(10, OutputPacing.startMs("1", linux = true))
        assertEquals(OutputPacing.LINUX_START_MS, OutputPacing.startMs("soon", linux = true))
    }
}
