package com.exo.musicplayer.desktop

import com.exo.musicplayer.desktop.ui.FramePacer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Why the moving backgrounds ran low and uneven on Windows only: they slept for
 * ~10 ms between steps, a sleep is rounded up to the system timer's tick (15.6 ms
 * on Windows, 1 ms on Linux), and the frame after it then waited for the next
 * display refresh. These tests model both ways of pacing on both timers and on
 * the usual refresh rates.
 */
class FramePacerTest {

    /** What a run looked like: steps a second, and the longest gap between two steps in ms. */
    private data class Run(val stepsPerSecond: Double, val worstGapMs: Double, val gapSpreadMs: Double)

    /**
     * Display frames every [refreshHz]. [wake], when given, models the old scheme: after each step it is asked
     * for the time the sleep ends (given the time now); the next frame is the first display frame after that.
     */
    private fun simulate(refreshHz: Double, seconds: Int = 10, wake: ((now: Double) -> Double)?, pacer: FramePacer?): Run {
        val frameMs = 1000.0 / refreshHz
        val end = seconds * 1000.0
        var frame = 0L          // index of the display frame last shown
        var now = 0.0
        var lastStep = -1.0
        val gaps = ArrayList<Double>()
        var steps = 0
        while (now < end) {
            val t = if (wake != null) wake(now) else now
            // The next display frame after t; one frame can't be shown twice.
            frame = maxOf(frame + 1, kotlin.math.floor(t / frameMs).toLong() + 1)
            now = frame * frameMs
            if (pacer == null || pacer.due((now * 1_000_000).toLong())) {
                if (lastStep >= 0) gaps += now - lastStep
                lastStep = now
                steps++
            }
        }
        val mean = gaps.average()
        return Run(steps / seconds.toDouble(), gaps.max(), gaps.maxOf { kotlin.math.abs(it - mean) })
    }

    /**
     * The old wait, asked straight after a frame: 16.6 ms minus the 4 ms slack, a sleep that ends on the next
     * tick of the system timer. The timer's ticks sit on a fixed grid ([tickMs] apart) that has nothing to do
     * with the screen's refresh, so on a coarse timer the wake lands early or late in a frame at random;
     * [skewMs] is where the grid sits against the screen's first frame.
     */
    private fun oldWake(tickMs: Double, skewMs: Double) = { now: Double ->
        val target = now + 12.6
        kotlin.math.ceil((target - skewMs) / tickMs) * tickMs + skewMs
    }

    @Test
    fun `the old sleeping scheme is low and uneven on Windows' 15 ms timer but fine on Linux's 1 ms one`() {
        // Windows: a 15.6 ms tick; try where the tick grid sits against the screen and take the typical (median) result.
        val windowsRuns = listOf(0.0, 2.5, 5.0, 7.5, 10.0, 12.5).map { simulate(60.0, wake = oldWake(15.625, it), pacer = null) }
        val windows = windowsRuns.sortedBy { it.stepsPerSecond }[windowsRuns.size / 2]
        val linux = simulate(60.0, wake = oldWake(1.0, 0.0), pacer = null)
        println("old scheme at 60 Hz: windows ${"%.1f".format(windows.stepsPerSecond)}/s worst gap ${"%.1f".format(windows.worstGapMs)} ms; linux ${"%.1f".format(linux.stepsPerSecond)}/s worst gap ${"%.1f".format(linux.worstGapMs)} ms")
        assertTrue(windows.stepsPerSecond < 50.0, "the model reproduces a low rate on Windows: ${windows.stepsPerSecond}")
        assertTrue(windows.worstGapMs >= 30.0, "with a visible stall: ${windows.worstGapMs}")
        assertTrue(linux.stepsPerSecond > 55.0, "and a fine one on Linux: ${linux.stepsPerSecond}")
    }

    @Test
    fun `paced on the frame clock alone, 60 Hz steps every frame whatever the system timer`() {
        val run = simulate(60.0, wake = null, pacer = FramePacer())
        println("new scheme at 60 Hz: ${"%.1f".format(run.stepsPerSecond)}/s worst gap ${"%.1f".format(run.worstGapMs)} ms")
        assertTrue(run.stepsPerSecond in 59.0..61.0, "${run.stepsPerSecond}")
        assertTrue(run.worstGapMs < 18.0, "no skipped frames: ${run.worstGapMs}")
    }

    @Test
    fun `other refresh rates are held in a smooth band and never stepped at the screen's full speed`() {
        for ((hz, minSteps, maxSteps) in listOf(
            Triple(30.0, 29.0, 31.0),
            Triple(75.0, 74.0, 76.0),
            Triple(90.0, 89.0, 91.0),
            Triple(100.0, 99.0, 101.0),
            Triple(120.0, 59.0, 61.0),
            Triple(144.0, 71.0, 73.0),
            Triple(165.0, 54.0, 84.0),
            Triple(240.0, 79.0, 121.0)
        )) {
            val run = simulate(hz, wake = null, pacer = FramePacer())
            println("new scheme at ${hz.toInt()} Hz: ${"%.1f".format(run.stepsPerSecond)}/s worst gap ${"%.1f".format(run.worstGapMs)} ms")
            assertTrue(run.stepsPerSecond in minSteps..maxSteps, "$hz Hz gave ${run.stepsPerSecond}")
            // No stutter: never a gap of more than two display frames, or 17 ms where the screen is so fast that
            // two frames are shorter than that (a 240 Hz screen steps on every third frame, 12.5 ms).
            assertTrue(run.worstGapMs <= maxOf(2.0 * 1000.0 / hz, 17.0) + 1.0, "$hz Hz worst gap ${run.worstGapMs}")
        }
    }

    @Test
    fun `restart makes the next frame step at once`() {
        val pacer = FramePacer()
        assertTrue(pacer.due(1_000_000_000L))
        assertTrue(!pacer.due(1_005_000_000L), "5 ms later is too soon")
        assertTrue(pacer.due(1_011_000_000L), "11 ms later is due")
        pacer.restart()
        assertTrue(pacer.due(1_013_000_000L), "after a restart the next frame steps")
    }

    @Test
    fun `the default cap is about 105 steps a second`() {
        assertEquals(9_500_000L, FramePacer.MIN_STEP_NANOS)
    }
}
