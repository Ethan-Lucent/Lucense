package com.exo.musicplayer.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import com.exo.musicplayer.desktop.data.CollectionKind
import com.exo.musicplayer.desktop.data.DesktopSettings
import com.exo.musicplayer.desktop.ui.Destination
import com.exo.musicplayer.desktop.ui.GhostButton
import com.exo.musicplayer.desktop.ui.KEY_GUIDE
import com.exo.musicplayer.desktop.ui.LucenseDesktopTheme
import com.exo.musicplayer.desktop.ui.Modals
import com.exo.musicplayer.desktop.ui.MouseAction
import com.exo.musicplayer.desktop.ui.MouseButtonsMode
import com.exo.musicplayer.desktop.ui.MouseSide
import com.exo.musicplayer.desktop.ui.NavHistory
import com.exo.musicplayer.desktop.ui.NavPlace
import com.exo.musicplayer.desktop.ui.ScrimDialog
import com.exo.musicplayer.desktop.ui.SettingsTab
import com.exo.musicplayer.desktop.ui.WindowShortcuts
import com.exo.musicplayer.desktop.ui.findSettings
import com.exo.musicplayer.desktop.ui.mouseAction
import com.exo.musicplayer.desktop.ui.mouseSideOf
import com.exo.musicplayer.desktop.ui.sideMouseButtons
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The mouse's side buttons: the page history, what a press comes to, the setting, and the window's handler. No real mouse. */
@OptIn(ExperimentalTestApi::class)
class MouseButtonsTest {

    private fun place(page: Destination, key: String? = null, settings: SettingsTab? = null) = NavPlace(
        destination = page,
        groupKind = if (key != null) CollectionKind.ALBUMS else null,
        groupKey = key,
        settingsOpen = settings != null,
        settingsTab = settings
    )

    // ---- NavHistory ----

    @Test
    fun backAndForwardWalkThePlacesVisited() {
        val history = NavHistory()
        val library = place(Destination.LIBRARY)
        val albums = place(Destination.ALBUMS)
        val album = place(Destination.ALBUMS, "abbey road")
        listOf(library, albums, album).forEach(history::visit)
        assertEquals(album, history.current)
        assertEquals(albums, history.back())
        assertEquals(library, history.back())
        assertNull(history.back())
        assertEquals(albums, history.forward())
        assertEquals(album, history.forward())
        assertNull(history.forward())
        assertFalse(history.canGoForward)
    }

    @Test
    fun settingsAndItsTabAreAPlaceToo() {
        val history = NavHistory()
        history.visit(place(Destination.LIBRARY))
        history.visit(place(Destination.LIBRARY, settings = SettingsTab.LIBRARY))
        history.visit(place(Destination.LIBRARY, settings = SettingsTab.KEYS))
        assertEquals(place(Destination.LIBRARY, settings = SettingsTab.LIBRARY), history.back())
        assertEquals(place(Destination.LIBRARY), history.back())
    }

    @Test
    fun theSamePlaceTwiceIsOnePlace() {
        val history = NavHistory()
        history.visit(place(Destination.LIBRARY))
        history.visit(place(Destination.LIBRARY))
        assertFalse(history.canGoBack)
        history.visit(place(Destination.STATS))
        history.visit(place(Destination.STATS))
        assertEquals(place(Destination.LIBRARY), history.back())
        assertNull(history.back())
    }

    @Test
    fun aNewPlaceClearsWhatWasForward() {
        val history = NavHistory()
        history.visit(place(Destination.LIBRARY))
        history.visit(place(Destination.ALBUMS))
        history.back()
        assertTrue(history.canGoForward)
        history.visit(place(Destination.STATS))
        assertFalse(history.canGoForward)
        assertNull(history.forward())
        assertEquals(place(Destination.LIBRARY), history.back())
    }

    @Test
    fun restoringAPlaceIsNotRecordedAgain() {
        val history = NavHistory()
        history.visit(place(Destination.LIBRARY))
        history.visit(place(Destination.ALBUMS))
        history.visit(place(Destination.STATS))
        val to = history.back()!!
        // The screen then shows it and reports it, as DesktopApp does for everything it shows.
        history.visit(to)
        assertTrue(history.canGoForward)
        assertEquals(place(Destination.LIBRARY), history.back())
        assertEquals(place(Destination.ALBUMS), history.forward())
        assertEquals(place(Destination.STATS), history.forward())
    }

    @Test
    fun onlyTheLastFiftyPlacesAreKept() {
        val history = NavHistory()
        val albums = (0..119).map { place(Destination.ALBUMS, "album $it") }
        albums.forEach(history::visit)
        var steps = 0
        var last: NavPlace? = null
        while (true) {
            last = history.back() ?: break
            steps++
        }
        assertEquals(NavHistory.MAX_PLACES, steps)
        // The oldest 70 are gone; what is left starts at the 70th.
        assertEquals(albums[119 - NavHistory.MAX_PLACES], last)
    }

    // ---- what a press comes to ----

    @Test
    fun buttonNumbersAreTheThumbButtons() {
        assertEquals(MouseSide.BACK, mouseSideOf(4, linux = false))
        assertEquals(MouseSide.FORWARD, mouseSideOf(5, linux = false))
        // X11 shifts the extra buttons by two; its 4 and 5 are the wheel tilting.
        assertEquals(MouseSide.BACK, mouseSideOf(6, linux = true))
        assertEquals(MouseSide.FORWARD, mouseSideOf(7, linux = true))
        assertNull(mouseSideOf(4, linux = true))
        assertNull(mouseSideOf(5, linux = true))
        assertNull(mouseSideOf(6, linux = false))
        listOf(0, 1, 2, 3).forEach {
            assertNull(mouseSideOf(it, linux = false))
            assertNull(mouseSideOf(it, linux = true))
        }
    }

    @Test
    fun whatAPressComesTo() {
        val back = MouseSide.BACK
        val forward = MouseSide.FORWARD
        fun act(side: MouseSide, mode: MouseButtonsMode, playbackOnly: Boolean, modal: Boolean) =
            mouseAction(side, mode, playbackOnly, modal)

        // The main window, nothing open.
        assertEquals(MouseAction.GO_BACK, act(back, MouseButtonsMode.NAVIGATE, false, false))
        assertEquals(MouseAction.GO_FORWARD, act(forward, MouseButtonsMode.NAVIGATE, false, false))
        assertEquals(MouseAction.PREVIOUS_SONG, act(back, MouseButtonsMode.TRACKS, false, false))
        assertEquals(MouseAction.NEXT_SONG, act(forward, MouseButtonsMode.TRACKS, false, false))
        // A dialog or the palette on top: Back closes it as Esc does, Forward does nothing - in both modes.
        MouseButtonsMode.entries.forEach { mode ->
            assertEquals(MouseAction.CLOSE_MODAL, act(back, mode, false, true))
            assertNull(act(forward, mode, false, true))
        }
        // The mini player and the lyrics windows have no pages: songs, whatever the setting.
        MouseButtonsMode.entries.forEach { mode ->
            listOf(false, true).forEach { modal ->
                assertEquals(MouseAction.PREVIOUS_SONG, act(back, mode, true, modal))
                assertEquals(MouseAction.NEXT_SONG, act(forward, mode, true, modal))
            }
        }
    }

    // ---- the setting ----

    @Test
    fun theSettingDefaultsToNavigatingAndIsKept() {
        val node = Preferences.userRoot().node("com/exo/musicplayer-mousetest")
        try {
            node.clear()
            assertEquals(MouseButtonsMode.NAVIGATE, MouseButtonsMode.fromName(DesktopSettings(node).mouseButtons))
            DesktopSettings(node).mouseButtons = MouseButtonsMode.TRACKS.name
            assertEquals(MouseButtonsMode.TRACKS, MouseButtonsMode.fromName(DesktopSettings(node).mouseButtons))
            // An unknown name (from a newer version, say) falls back rather than failing.
            DesktopSettings(node).mouseButtons = "SOMETHING_ELSE"
            assertEquals(MouseButtonsMode.NAVIGATE, MouseButtonsMode.fromName(DesktopSettings(node).mouseButtons))
        } finally {
            node.removeNode()
        }
    }

    @Test
    fun theSettingCanBeFoundAndTheButtonsAreInTheKeyGuide() {
        assertTrue(findSettings("mouse").any { it.label == "Mouse side buttons" && it.tab == SettingsTab.PLAYBACK })
        assertTrue(findSettings("thumb").isNotEmpty())
        assertTrue(KEY_GUIDE.any { it.title == "Mouse" && it.keys.any { (keys, _) -> keys.startsWith("Back") } })
    }

    // ---- the window's handler ----

    private open class Recorder(
        var mode: MouseButtonsMode = MouseButtonsMode.NAVIGATE,
        var modal: Boolean = false
    ) : WindowShortcuts.Actions {
        val calls = mutableListOf<String>()
        override fun togglePlay() { calls += "togglePlay" }
        override fun next() { calls += "next" }
        override fun previous() { calls += "previous" }
        override fun seekBy(deltaMs: Long) = Unit
        override fun nudgeVolume(by: Float) = Unit
        override fun jumpToNowPlaying() = Unit
        override fun toggleMute() = Unit
        override fun toggleShuffle() = Unit
        override fun cycleRepeat() = Unit
        override fun toggleShortcutsHelp() = Unit
        override fun selectAllVisible() = Unit
        override fun clearSelection() = false
        override fun closeGroupPage() = false
        override fun goBack(): Boolean { calls += "goBack"; return true }
        override fun goForward(): Boolean { calls += "goForward"; return true }
        override fun modalOpen() = modal
        override fun closeModal(): Boolean { calls += "closeModal"; return true }
        override fun mouseMode() = mode
    }

    @Test
    fun theHandlerTakesTheThumbButtonsAndNothingElse() {
        val recorder = Recorder()
        val shortcuts = WindowShortcuts(recorder)
        assertTrue(shortcuts.mouseButton(4, linux = false))
        assertTrue(shortcuts.mouseButton(5, linux = false))
        assertFalse(shortcuts.mouseButton(1, linux = false))
        assertFalse(shortcuts.mouseButton(3, linux = false))
        assertFalse(shortcuts.mouseButton(6, linux = false))
        assertEquals(listOf("goBack", "goForward"), recorder.calls)

        recorder.calls.clear()
        recorder.mode = MouseButtonsMode.TRACKS
        shortcuts.mouseButton(6, linux = true)
        shortcuts.mouseButton(7, linux = true)
        assertEquals(listOf("previous", "next"), recorder.calls)

        // Forward under a dialog is still the mouse's press (so nothing underneath gets it) and does nothing.
        recorder.calls.clear()
        recorder.modal = true
        assertTrue(shortcuts.mouseButton(4, linux = false))
        assertTrue(shortcuts.mouseButton(5, linux = false))
        assertEquals(listOf("closeModal"), recorder.calls)
    }

    @Test
    fun theOtherWindowsAreSongsInBothModes() {
        MouseButtonsMode.entries.forEach { mode ->
            val recorder = Recorder(mode)
            val shortcuts = WindowShortcuts(recorder, playbackOnly = true)
            shortcuts.mouseButton(4, linux = false)
            shortcuts.mouseButton(5, linux = false)
            assertEquals(listOf("previous", "next"), recorder.calls)
        }
    }

    /** What the window puts around its content: the handler, as Main.kt does. */
    @androidx.compose.runtime.Composable
    private fun Window(shortcuts: WindowShortcuts, content: @androidx.compose.runtime.Composable () -> Unit) {
        LucenseDesktopTheme {
            Box(Modifier.fillMaxSize().sideMouseButtons(shortcuts, linux = false)) { content() }
        }
    }

    @Test
    fun aBackPressAnywhereInTheWindowFiresOnceAndIsNotAClick() = runComposeUiTest {
        val recorder = Recorder()
        var clicks = 0
        setContent { Window(WindowShortcuts(recorder)) { GhostButton("Play") { clicks++ } } }
        // An ordinary click works, and still does after the side buttons.
        onNodeWithText("Play").performClick()
        assertEquals(1, clicks)
        // Pressed over a button: the button must not act on it, and one press is one call.
        onNodeWithText("Play").performMouseInput {
            press(MouseButton(3))
            release(MouseButton(3))
        }
        assertEquals(listOf("goBack"), recorder.calls)
        assertEquals(1, clicks)
        onNodeWithText("Play").performMouseInput {
            press(MouseButton(4))
            release(MouseButton(4))
        }
        assertEquals(listOf("goBack", "goForward"), recorder.calls)
        assertEquals(1, clicks)
        onNodeWithText("Play").performClick()
        assertEquals(2, clicks)
        assertEquals(2, recorder.calls.size)
    }

    @Test
    fun aBackPressClosesADialogInsteadOfNavigating() = runComposeUiTest {
        var open by mutableStateOf(true)
        val recorder = object : Recorder() {
            override fun modalOpen() = Modals.any
            override fun closeModal() = Modals.closeTop()
        }
        setContent {
            Window(WindowShortcuts(recorder)) {
                GhostButton("Behind") {}
                if (open) ScrimDialog(title = "A dialog", onDismiss = { open = false }) {}
            }
        }
        assertTrue(Modals.any)
        // Forward does nothing to it.
        onNodeWithText("A dialog").performMouseInput {
            press(MouseButton(4))
            release(MouseButton(4))
        }
        waitForIdle()
        assertTrue(open)
        // Back closes it, as Esc would, and goes nowhere.
        onNodeWithText("A dialog").performMouseInput {
            press(MouseButton(3))
            release(MouseButton(3))
        }
        waitForIdle()
        assertFalse(open)
        assertFalse(Modals.any)
        assertTrue(recorder.calls.isEmpty())
        // With it gone, Back is the page's again.
        onNodeWithText("Behind").performMouseInput {
            press(MouseButton(3))
            release(MouseButton(3))
        }
        assertEquals(listOf("goBack"), recorder.calls)
    }
}
