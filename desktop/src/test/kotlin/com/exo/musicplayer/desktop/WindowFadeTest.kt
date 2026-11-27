package com.exo.musicplayer.desktop

import com.exo.musicplayer.desktop.ui.Motion
import com.exo.musicplayer.desktop.ui.WindowFade
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest

/** The window's fade, in made-up time: no window, no desktop. */
@OptIn(ExperimentalCoroutinesApi::class)
class WindowFadeTest {
    private class Rig(scope: TestScope, translucent: Boolean = true, clockRuns: Boolean = true, refuseBelowOne: Boolean = false) {
        val seen = mutableListOf<Float>()
        val fade = WindowFade(
            setOpacity = { value ->
                if (refuseBelowOne && value < 1f) throw UnsupportedOperationException("no compositor")
                seen += value
            },
            translucent = translucent,
            scope = scope,
            // A stopped clock is an animation that can never finish.
            clock = if (clockRuns) ({ scope.currentTime }) else ({ 0L }),
        )
    }

    @Test
    fun fadeInEndsFullyShownAndOnlyEverGoesUp() = runTest {
        val rig = Rig(this)
        rig.fade.fadeIn(restart = true)
        advanceTimeBy(Motion.WINDOW_FADE_IN_MS + 100L)
        testScheduler.runCurrent()
        assertEquals(1f, rig.seen.last())
        assertEquals(1f, rig.fade.level)
        assertEquals(rig.seen.sorted(), rig.seen, "never steps back")
        assertTrue(rig.seen.size > 3, "it moves in steps, not at once")
        assertEquals(0f, rig.seen.first())
    }

    @Test
    fun fadeOutEndsGoneAndCallsDoneOnce() = runTest {
        val rig = Rig(this)
        var done = 0
        rig.fade.fadeOut { done++ }
        advanceTimeBy(Motion.WINDOW_FADE_OUT_MS + 100L)
        testScheduler.runCurrent()
        assertEquals(1, done)
        assertEquals(0f, rig.seen.last())
        assertEquals(rig.seen.sortedDescending(), rig.seen, "never steps back")
        // Nothing comes later, including the hard timeout.
        advanceTimeBy(1_000)
        testScheduler.runCurrent()
        assertEquals(1, done)
    }

    @Test
    fun askingToCloseTwiceStillCallsTheLastDoneOnce() = runTest {
        val rig = Rig(this)
        var first = 0
        var second = 0
        rig.fade.fadeOut { first++ }
        advanceTimeBy(50)
        rig.fade.fadeOut { second++ }
        advanceTimeBy(1_000)
        testScheduler.runCurrent()
        assertEquals(0, first)
        assertEquals(1, second)
    }

    @Test
    fun openingAgainDuringFadeOutEndsShownAndNeverCallsDone() = runTest {
        val rig = Rig(this)
        var done = 0
        rig.fade.fadeOut { done++ }
        advanceTimeBy(Motion.WINDOW_FADE_OUT_MS / 2L)
        assertTrue(rig.fade.level in 0.01f..0.99f, "part-way: ${rig.fade.level}")
        rig.fade.fadeIn()
        advanceTimeBy(2_000)
        testScheduler.runCurrent()
        assertEquals(1f, rig.seen.last())
        assertEquals(0, done)
    }

    @Test
    fun cancelLeavesTheWindowFullyShown() = runTest {
        val rig = Rig(this)
        var done = 0
        rig.fade.fadeOut { done++ }
        advanceTimeBy(Motion.WINDOW_FADE_OUT_MS / 2L)
        rig.fade.cancel()
        advanceTimeBy(2_000)
        testScheduler.runCurrent()
        assertEquals(1f, rig.seen.last())
        assertEquals(1f, rig.fade.level)
        assertEquals(0, done)
    }

    @Test
    fun theHardTimeoutQuitsEvenIfTheAnimationCannotRun() = runTest {
        val rig = Rig(this, clockRuns = false)
        var done = 0
        rig.fade.fadeOut { done++ }
        advanceTimeBy(Motion.WINDOW_FADE_HARD_TIMEOUT_MS - 10L)
        assertEquals(0, done, "not before the timeout")
        advanceTimeBy(20)
        testScheduler.runCurrent()
        assertEquals(1, done)
        advanceTimeBy(2_000)
        testScheduler.runCurrent()
        assertEquals(1, done, "once")
    }

    @Test
    fun alreadyGoneThereIsNothingToWaitFor() = runTest {
        val rig = Rig(this)
        rig.fade.fadeOut { }
        advanceTimeBy(1_000)
        testScheduler.runCurrent()
        var done = 0
        rig.fade.fadeOut { done++ }
        assertEquals(1, done, "at once, in the tray")
    }

    @Test
    fun withoutTranslucencyTheContentFadesAndTheWindowIsLeftAlone() = runTest {
        val rig = Rig(this, translucent = false)
        var done = 0
        rig.fade.fadeOut { done++ }
        advanceTimeBy(Motion.WINDOW_FADE_OUT_MS / 2L)
        assertTrue(rig.fade.contentAlpha in 0.01f..0.99f, "content part-way: ${rig.fade.contentAlpha}")
        advanceTimeBy(1_000)
        testScheduler.runCurrent()
        assertEquals(0f, rig.fade.contentAlpha)
        assertEquals(1, done)
        rig.fade.fadeIn()
        advanceTimeBy(1_000)
        testScheduler.runCurrent()
        assertEquals(1f, rig.fade.contentAlpha)
        assertTrue(rig.seen.isEmpty(), "the window's opacity is never touched")
    }

    @Test
    fun aDesktopThatRefusesOpacityFallsBackToTheContent() = runTest {
        // Every opacity but 1 throws, as when there turns out to be no compositor.
        val rig = Rig(this, refuseBelowOne = true)
        var done = 0
        rig.fade.fadeOut { done++ }
        advanceTimeBy(1_000)
        testScheduler.runCurrent()
        assertEquals(1, done)
        assertEquals(0f, rig.fade.contentAlpha)
        assertTrue(rig.seen.all { it == 1f }, "the window is put back to fully opaque: ${rig.seen}")
    }
}
