package com.exo.musicplayer.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import com.exo.musicplayer.desktop.ui.LucenseDesktopTheme
import com.exo.musicplayer.desktop.ui.Notes
import com.exo.musicplayer.desktop.ui.TRIM_MIN_H
import com.exo.musicplayer.desktop.ui.TRIM_MIN_W
import com.exo.musicplayer.desktop.ui.TrimBox
import com.exo.musicplayer.desktop.ui.TrimKeyAction
import com.exo.musicplayer.desktop.ui.TrimPanel
import com.exo.musicplayer.desktop.ui.canSave
import com.exo.musicplayer.desktop.ui.clampEnd
import com.exo.musicplayer.desktop.ui.clampStart
import com.exo.musicplayer.desktop.ui.handleNear
import com.exo.musicplayer.desktop.ui.markPosition
import com.exo.musicplayer.desktop.ui.nudge
import com.exo.musicplayer.desktop.ui.removedText
import com.exo.musicplayer.desktop.ui.saveBlocker
import com.exo.musicplayer.desktop.ui.trimKeyAction
import com.exo.musicplayer.desktop.ui.trimPlacement
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Trim window: its rules, and that its content offers Save only for a real cut. */
@OptIn(ExperimentalTestApi::class)
class TrimPolishTest {

    private val song = 180_000L

    @Test
    fun theStartStaysAtLeastASecondShortOfTheEnd() {
        assertEquals(0L, clampStart(-500, 100_000))
        assertEquals(99_000L, clampStart(100_000, 100_000))
        assertEquals(40_000L, clampStart(40_000, 100_000))
        // A song shorter than the minimum has nowhere to put it but 0.
        assertEquals(0L, clampStart(300, 500))
    }

    @Test
    fun theEndStaysAtLeastASecondPastTheStartAndInsideTheSong() {
        assertEquals(11_000L, clampEnd(5_000, 10_000, song))
        assertEquals(song, clampEnd(999_999, 10_000, song))
        assertEquals(120_000L, clampEnd(120_000, 10_000, song))
        assertEquals(song, clampEnd(0, 179_900, song), "near the very end the end handle can only sit at the end")
    }

    @Test
    fun arrowKeysMoveATenthAndShiftAWholeSecond() {
        assertEquals(10_100L, nudge(10_000, 1, shift = false, lengthMs = song))
        assertEquals(9_900L, nudge(10_000, -1, shift = false, lengthMs = song))
        assertEquals(11_000L, nudge(10_000, 1, shift = true, lengthMs = song))
        assertEquals(9_000L, nudge(10_000, -1, shift = true, lengthMs = song))
        assertEquals(0L, nudge(50, -1, shift = false, lengthMs = song))
        assertEquals(song, nudge(song - 10, 1, shift = true, lengthMs = song))
    }

    @Test
    fun theMarkKeysUseThePlayheadThenWhereItWasLeftThenTheStart() {
        assertEquals(5_000L, markPosition(5_000, 9_000, 1_000))
        assertEquals(9_000L, markPosition(null, 9_000, 1_000))
        assertEquals(1_000L, markPosition(null, null, 1_000))
    }

    @Test
    fun wholeSongOrTooShortCannotBeSaved() {
        // Handles at both ends: nothing is cut.
        assertFalse(canSave(song, 0, song, withEffects = false, saving = false))
        assertEquals(Notes.TRIM_SAVE_NOTHING, saveBlocker(song, 0, song, false))
        // Under a second.
        assertFalse(canSave(song, 10_000, 10_999, withEffects = false, saving = false))
        assertEquals(Notes.TRIM_SAVE_SHORT, saveBlocker(song, 10_000, 10_999, false))
        // A real piece.
        assertTrue(canSave(song, 10_000, 60_000, withEffects = false, saving = false))
        assertTrue(canSave(song, 0, 60_000, withEffects = false, saving = false))
        assertTrue(canSave(song, 10_000, song, withEffects = false, saving = false))
        // Not while one is being written, nor before the song has been read.
        assertFalse(canSave(song, 10_000, 60_000, withEffects = false, saving = true))
        assertFalse(canSave(0, 0, 0, withEffects = false, saving = false))
        // The whole song with the effects baked in is a new song too.
        assertTrue(canSave(song, 0, song, withEffects = true, saving = false))
    }

    @Test
    fun readoutsSayWhatIsRemoved() {
        assertEquals("nothing removed", removedText(song, 0, song))
        assertEquals("removes 0:12.0", removedText(song, 0, song - 12_000))
        assertEquals("removes 1:00.0", removedText(song, 20_000, 140_000))
    }

    @Test
    fun theKeysMapToTheirActions() {
        assertEquals(TrimKeyAction.PLAY, trimKeyAction(Key.Spacebar))
        assertEquals(TrimKeyAction.SET_START, trimKeyAction(Key.I))
        assertEquals(TrimKeyAction.SET_END, trimKeyAction(Key.O))
        assertEquals(TrimKeyAction.EARLIER, trimKeyAction(Key.DirectionLeft))
        assertEquals(TrimKeyAction.LATER, trimKeyAction(Key.DirectionRight))
        assertEquals(TrimKeyAction.SAVE, trimKeyAction(Key.Enter))
        assertEquals(TrimKeyAction.CLOSE, trimKeyAction(Key.Escape))
        assertNull(trimKeyAction(Key.A))
    }

    @Test
    fun aHandleIsGrabbedOnlyWhenTheMouseIsNearIt() {
        // Span 1000 px from pad 10 over 100 s: the handles at 10 s and 60 s are at x=110 and x=610.
        assertEquals(1, handleNear(112f, 10f, 1000f, 10_000, 60_000, 100_000, 9f))
        assertEquals(2, handleNear(605f, 10f, 1000f, 10_000, 60_000, 100_000, 9f))
        assertEquals(0, handleNear(300f, 10f, 1000f, 10_000, 60_000, 100_000, 9f))
        assertEquals(0, handleNear(112f, 10f, 1000f, 10_000, 60_000, 0, 9f))
    }

    private val screen = TrimBox(0, 0, 1920, 1080)
    private val main = TrimBox(100, 80, 1280, 820)

    @Test
    fun opensAtTheLeftOfTheMainWindowClearOfTheEffectsPanel() {
        val box = trimPlacement("", main, listOf(screen))
        assertEquals(124, box.x)
        assertEquals(128, box.y)
        // Its right edge stays left of the main window's side panel (the last 360).
        assertTrue(box.x + box.w <= main.x + main.w - 360 + 48, "$box")
        assertTrue(box.w >= TRIM_MIN_W && box.h >= TRIM_MIN_H)
    }

    @Test
    fun aSmallMainWindowStillGetsAWindowOfTheMinimumSizeOnScreen() {
        val box = trimPlacement("", TrimBox(1800, 1000, 760, 500), listOf(screen))
        assertTrue(box.x >= 0 && box.y >= 0 && box.x + box.w <= 1920 && box.y + box.h <= 1080, "$box")
        assertTrue(box.w >= TRIM_MIN_W && box.h >= TRIM_MIN_H)
    }

    @Test
    fun whereItWasLeftIsRememberedWhileThatIsStillOnAScreen() {
        assertEquals(TrimBox(300, 200, 700, 500), trimPlacement("300,200,700,500", main, listOf(screen)))
        // A monitor that has gone: back to beside the main window.
        assertEquals(124, trimPlacement("3000,200,700,500", main, listOf(screen)).x)
        // Rubbish, or too small to be real.
        assertEquals(124, trimPlacement("nonsense", main, listOf(screen)).x)
        assertEquals(124, trimPlacement("300,200,100,100", main, listOf(screen)).x)
        assertEquals("300,200,700,500", TrimBox(300, 200, 700, 500).toString())
    }

    private fun wave() = FloatArray(220) {
        (0.2f + 0.7f * kotlin.math.abs(kotlin.math.sin(it / 9.0)).toFloat() * (0.5f + 0.5f * kotlin.math.sin(it / 31.0).toFloat().let { v -> v * v }))
    }

    @Test
    fun saveIsOffForTheWholeSongAndOnForAPiece() = runComposeUiTest {
        var start by androidx.compose.runtime.mutableStateOf(0L)
        var end by androidx.compose.runtime.mutableStateOf(song)
        var saves = 0
        setContent {
            LucenseDesktopTheme {
                TrimPanel(
                    title = "Midnight City", artist = "M83", bars = wave(), lengthMs = song, reading = false,
                    startMs = start, endMs = end, playheadMs = null, previewing = false,
                    effectsOn = false, effectsLabel = "", withEffects = false, saving = false, saved = null,
                    problem = null, onStart = {}, onEnd = {}, onPlay = {}, onHearEnd = {}, onStop = {},
                    onReset = {}, onWithEffects = {}, onSave = { saves++ }, onClose = {}
                )
            }
        }
        onNodeWithText("Save as new song").assertIsNotEnabled()
        onNodeWithText("Reset").assertIsNotEnabled()
        start = 20_000L
        end = 140_000L
        waitForIdle()
        onNodeWithText("Save as new song").assertIsEnabled()
        onNodeWithText("Reset").assertIsEnabled()
        onNodeWithText("removes 1:00.0").assertExists()
    }

    /** For looking at: the picture of the window as it is now. Asserts nothing about pixels. */
    @Test
    fun lookAtIt() = runComposeUiTest {
        setContent {
            LucenseDesktopTheme {
                androidx.compose.foundation.layout.Box(Modifier.size(760.dp, 600.dp)) {
                    TrimPanel(
                        title = "Midnight City", artist = "M83", bars = wave(), lengthMs = 243_000L, reading = false,
                        startMs = 20_000L, endMs = 180_000L, playheadMs = 60_000L, previewing = true,
                        effectsOn = true, effectsLabel = "1.2× speed, reverb", withEffects = false, saving = false,
                        saved = File("/music/Midnight City (trimmed).mp3"),
                        problem = null, onStart = {}, onEnd = {}, onPlay = {}, onHearEnd = {}, onStop = {},
                        onReset = {}, onWithEffects = {}, onSave = {}, onClose = {}
                    )
                }
            }
        }
        val image = onRoot().captureToImage().toAwtImage()
        assertNotNull(image)
        javax.imageio.ImageIO.write(image, "png", File("/tmp/trim-after.png"))
    }
}
