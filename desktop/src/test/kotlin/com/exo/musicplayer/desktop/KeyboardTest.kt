package com.exo.musicplayer.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import com.exo.musicplayer.desktop.ui.GhostButton
import com.exo.musicplayer.desktop.ui.LucenseDesktopTheme
import com.exo.musicplayer.desktop.ui.SearchBoxes
import com.exo.musicplayer.desktop.ui.TextInput
import com.exo.musicplayer.desktop.ui.Typing
import com.exo.musicplayer.desktop.ui.WindowShortcuts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The space bar plays and pauses - unless someone is typing - and never presses a button. */
@OptIn(ExperimentalTestApi::class)
class KeyboardTest {

    /** Writes down what the keys asked for. */
    private open class Recorder : WindowShortcuts.Actions {
        val calls = mutableListOf<String>()
        override fun togglePlay() { calls += "togglePlay" }
        override fun next() { calls += "next" }
        override fun previous() { calls += "previous" }
        override fun seekBy(deltaMs: Long) { calls += "seekBy $deltaMs" }
        override fun nudgeVolume(by: Float) { calls += "volume $by" }
        override fun jumpToNowPlaying() { calls += "jump" }
        override fun toggleMute() { calls += "mute" }
        override fun toggleShuffle() { calls += "shuffle" }
        override fun cycleRepeat() { calls += "repeat" }
        override fun toggleShortcutsHelp() { calls += "help" }
        override fun selectAllVisible() { calls += "selectAll" }
        override fun clearSelection(): Boolean { calls += "clearSelection"; return false }
        override fun closeGroupPage(): Boolean { calls += "closeGroupPage"; return false }
    }

    /** The window's keys as Main.kt hands them over, around [content]. */
    @Composable
    private fun AppWindow(shortcuts: WindowShortcuts, content: @Composable () -> Unit) {
        LucenseDesktopTheme {
            Box(Modifier.fillMaxSize().onPreviewKeyEvent(shortcuts::preview).onKeyEvent(shortcuts::bubble)) {
                content()
            }
        }
    }

    @Test
    fun aSpaceOrALetterTypedInATextBoxNeverReachesTheWindow() = runComposeUiTest {
        val recorder = Recorder()
        var text by mutableStateOf("")
        setContent { AppWindow(WindowShortcuts(recorder)) { TextInput(text, { text = it }, "Search") } }

        val box = onAllNodes(hasSetTextAction())[0]
        box.performSemanticsAction(SemanticsActions.RequestFocus)
        box.assertIsFocused()
        assertTrue(Typing.active, "a focused text box counts as typing")

        // Space used to pause, M to mute, S to shuffle, Up to turn it up.
        box.performKeyInput {
            pressKey(Key.Spacebar)
            pressKey(Key.M)
            pressKey(Key.S)
            pressKey(Key.R)
            pressKey(Key.J)
            pressKey(Key.DirectionUp)
        }
        assertEquals(emptyList(), recorder.calls)

        // Escape lets go of the box - the window has its keys back - rather than
        // doing what Escape does elsewhere while still in it.
        box.performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertFalse(Typing.active, "Escape left the box")
        assertEquals(emptyList(), recorder.calls)
    }

    @Test
    fun ctrlFGoesToTheSearchBoxFromAnywhere() = runComposeUiTest {
        val recorder = object : Recorder() {
            override fun focusSearch(): Boolean = SearchBoxes.focus()
        }
        setContent {
            AppWindow(WindowShortcuts(recorder)) {
                Column {
                    GhostButton("Elsewhere") {}
                    TextInput("", {}, "Notes")
                    TextInput("", {}, "Search", search = true)
                }
            }
        }
        val boxes = onAllNodes(hasSetTextAction())
        // From a button...
        onNodeWithText("Elsewhere").performSemanticsAction(SemanticsActions.RequestFocus)
        onNodeWithText("Elsewhere").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.F) } }
        waitForIdle()
        boxes[1].assertIsFocused()
        // ...and from another box, where Ctrl+F would otherwise be the box's.
        boxes[0].performSemanticsAction(SemanticsActions.RequestFocus)
        boxes[0].performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.F) } }
        waitForIdle()
        boxes[1].assertIsFocused()
    }

    @Test
    fun theOtherWindowsHaveThePlayingKeysOnly() = runComposeUiTest {
        val recorder = Recorder()
        setContent { AppWindow(WindowShortcuts(recorder, playbackOnly = true)) { GhostButton("Play") {} } }
        val button = onNodeWithText("Play")
        button.performSemanticsAction(SemanticsActions.RequestFocus)
        button.performKeyInput {
            pressKey(Key.Spacebar)
            pressKey(Key.M)
            pressKey(Key.J)
            pressKey(Key.Slash)
            pressKey(Key.Escape)
            withKeyDown(Key.CtrlLeft) { pressKey(Key.A) }
            withKeyDown(Key.CtrlLeft) { pressKey(Key.DirectionRight) }
        }
        assertEquals(listOf("togglePlay", "mute", "next"), recorder.calls)
    }

    @Test
    fun theSpaceBarPlaysAndPausesInsteadOfPressingTheFocusedButton() = runComposeUiTest {
        val recorder = Recorder()
        var clicks = 0
        setContent { AppWindow(WindowShortcuts(recorder)) { GhostButton("Shuffle all") { clicks++ } } }

        val button = onNodeWithText("Shuffle all")
        button.performClick()
        assertEquals(1, clicks)
        // A clicked button keeps the focus on the desktop; this makes sure of it either way.
        button.performSemanticsAction(SemanticsActions.RequestFocus)
        button.assertIsFocused()

        button.performKeyInput { pressKey(Key.Spacebar) }
        assertEquals(1, clicks, "the space bar did not press the button again")
        assertEquals(listOf("togglePlay"), recorder.calls)

        // Enter is still how the keyboard presses a focused button.
        button.performKeyInput { pressKey(Key.Enter) }
        assertEquals(2, clicks)

        // Plain keys are the shortcuts; with Ctrl held they are left alone.
        button.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.S) } }
        assertEquals(listOf("togglePlay"), recorder.calls, "Ctrl+S is not shuffle")
        button.performKeyInput { pressKey(Key.S) }
        button.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.DirectionRight) } }
        assertEquals(listOf("togglePlay", "shuffle", "next"), recorder.calls)
    }

    @Test
    fun typingEndsWhenTheFocusLeavesTheBoxesWhicheverWayItGoes() = runComposeUiTest {
        var shown by mutableStateOf(true)
        setContent {
            AppWindow(WindowShortcuts(Recorder())) {
                Column {
                    GhostButton("Elsewhere") {}
                    if (shown) {
                        TextInput("", {}, "First")
                        TextInput("", {}, "Second")
                    }
                }
            }
        }
        val boxes = onAllNodes(hasSetTextAction())

        // Straight from one box to the next: still typing throughout.
        boxes[0].performSemanticsAction(SemanticsActions.RequestFocus)
        waitForIdle()
        assertTrue(Typing.active)
        boxes[1].performSemanticsAction(SemanticsActions.RequestFocus)
        waitForIdle()
        assertTrue(Typing.active, "focus moving between boxes stays typing")

        // To a button: the window has its keys back.
        onNodeWithText("Elsewhere").performSemanticsAction(SemanticsActions.RequestFocus)
        waitForIdle()
        assertFalse(Typing.active)

        // A focused box taken off the screen doesn't leave the window deaf either.
        boxes[0].performSemanticsAction(SemanticsActions.RequestFocus)
        waitForIdle()
        assertTrue(Typing.active)
        shown = false
        waitForIdle()
        assertFalse(Typing.active, "a box that has gone stops counting")
    }
}
