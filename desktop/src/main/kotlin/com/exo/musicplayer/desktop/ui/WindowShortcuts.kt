package com.exo.musicplayer.desktop.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.exo.musicplayer.desktop.data.DesktopController
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor

/**
 * The main window's keyboard shortcuts, in the two passes Compose offers.
 *
 * [preview] sees every key before whatever has the focus. It takes the space
 * bar, Ctrl+A and Escape-with-a-selection. The space bar has to be taken here: a
 * clicked button keeps the focus, and a focused button treats the space bar as a
 * click, so left to the second pass the space bar pressed whichever button was
 * clicked last instead of playing or pausing.
 *
 * [bubble] sees only what the focus left alone: the arrows, which a focused
 * slider rightly takes first, the one-letter shortcuts, and Escape back from an
 * album's or an artist's page.
 *
 * While a text box has the keyboard ([Typing]) both stand back, apart from that
 * Escape: a text box types the space bar and the letters rather than claiming
 * them, so without this a space in a search box paused the music.
 *
 * Shortcuts are plain keys. With Ctrl, Alt or the Super key held they are left
 * alone - Ctrl+S is not "shuffle", and Ctrl+Space switches the keyboard layout
 * on many Linux desktops - apart from Ctrl+Left and Ctrl+Right (previous, next)
 * and Ctrl+Up and Ctrl+Down, which have always changed the volume too. Ctrl+F
 * goes to the page's search box, from anywhere, even another text box, and
 * Ctrl+K opens the palette (CommandPalette) the same way. Ctrl+V outside a text
 * box, with a link on the clipboard, opens the palette with the link in it;
 * anything else on the clipboard is left alone, and in a box it is the box's.
 *
 * [playbackOnly] is for the other windows - the mini player, the lyrics - where
 * the playing keys work (space, arrows, M, S, R) but not the ones about the main
 * window's list and pages.
 */
class WindowShortcuts(
    private val actions: Actions,
    private val playbackOnly: Boolean = false,
    /** The clipboard's text; a parameter so the tests never touch the real one. */
    private val clipboard: () -> String? = ::systemClipboardText
) {

    /** What the keys do. The real one is [DesktopController.shortcutActions]. */
    interface Actions {
        fun togglePlay()
        fun next()
        fun previous()
        fun seekBy(deltaMs: Long)
        fun nudgeVolume(by: Float)
        fun jumpToNowPlaying()
        fun toggleMute()
        fun toggleShuffle()
        fun cycleRepeat()
        fun toggleShortcutsHelp()
        fun selectAllVisible()

        /** Clears the selection; false when there was none. */
        fun clearSelection(): Boolean

        /** Back from an album's or an artist's page to the cards; false when none was open. */
        fun closeGroupPage(): Boolean

        /** Puts the keyboard in the page's search box; false when the page has none. */
        fun focusSearch(): Boolean = false

        /** A, then B and round, then off: see DesktopController.cycleAbLoop. */
        fun cycleAbLoop() = Unit

        /** Ctrl+K, with no [text]: opens the palette, or closes it when open. With [text], opens it with that in it. */
        fun openPalette(text: String? = null) = Unit
    }

    /**
     * Whether the space bar is down, having played or paused. A held key repeats
     * its press, and one press should be one toggle, not a stutter.
     */
    private var spaceHeld = false

    fun preview(event: KeyEvent): Boolean {
        if (!playbackOnly && event.type == KeyEventType.KeyDown && event.isCtrlPressed && !event.isAltPressed &&
            event.key == Key.F && actions.focusSearch()
        ) {
            return true
        }
        if (!playbackOnly && event.type == KeyEventType.KeyDown && event.isCtrlPressed && !event.isAltPressed &&
            event.key == Key.K
        ) {
            actions.openPalette()
            return true
        }
        if (Typing.active) {
            // Its release goes to the box, so it will never be seen here.
            spaceHeld = false
            return false
        }
        val down = event.type == KeyEventType.KeyDown
        return when {
            event.key == Key.Spacebar && !event.withCommandKeys -> {
                if (down) {
                    if (!spaceHeld) actions.togglePlay()
                    spaceHeld = true
                } else if (event.type == KeyEventType.KeyUp) {
                    spaceHeld = false
                }
                // Release and all, so a focused button never sees half a press.
                true
            }
            playbackOnly -> false
            down && event.isCtrlPressed && event.key == Key.A -> {
                actions.selectAllVisible()
                true
            }
            down && event.isCtrlPressed && event.key == Key.V ->
                PaletteLinks.urlOf(clipboard())?.let { link ->
                    actions.openPalette(link)
                    true
                } ?: false
            down && event.key == Key.Escape -> actions.clearSelection()
            else -> false
        }
    }

    fun bubble(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        // Back from an album's page. A text box takes the first Escape itself, to
        // let go of the keyboard; the next one comes here.
        if (event.key == Key.Escape) return !playbackOnly && actions.closeGroupPage()
        if (Typing.active || event.isAltPressed || event.isMetaPressed) return false
        if (event.isCtrlPressed) {
            return when (event.key) {
                Key.DirectionRight -> act { actions.next() }
                Key.DirectionLeft -> act { actions.previous() }
                Key.DirectionUp -> act { actions.nudgeVolume(VOLUME_STEP) }
                Key.DirectionDown -> act { actions.nudgeVolume(-VOLUME_STEP) }
                else -> false
            }
        }
        return when (event.key) {
            Key.DirectionRight -> act { actions.seekBy(SEEK_STEP_MS) }
            Key.DirectionLeft -> act { actions.seekBy(-SEEK_STEP_MS) }
            Key.DirectionUp -> act { actions.nudgeVolume(VOLUME_STEP) }
            Key.DirectionDown -> act { actions.nudgeVolume(-VOLUME_STEP) }
            Key.J -> !playbackOnly && act { actions.jumpToNowPlaying() }
            Key.M -> act { actions.toggleMute() }
            Key.S -> act { actions.toggleShuffle() }
            Key.R -> act { actions.cycleRepeat() }
            Key.L -> act { actions.cycleAbLoop() }
            Key.Slash -> !playbackOnly && act { actions.toggleShortcutsHelp() }
            else -> false
        }
    }

    private inline fun act(action: () -> Unit): Boolean {
        action()
        return true
    }

    private val KeyEvent.withCommandKeys: Boolean
        get() = isCtrlPressed || isAltPressed || isMetaPressed

    private companion object {
        const val SEEK_STEP_MS = 5_000L
        const val VOLUME_STEP = 0.05f
    }
}

/** The window's keys, wired to the app. */
fun DesktopController.shortcutActions(): WindowShortcuts.Actions {
    val controller = this
    return object : WindowShortcuts.Actions {
        override fun togglePlay() = controller.togglePlay()
        override fun next() = controller.next()
        override fun previous() = controller.previous()
        override fun seekBy(deltaMs: Long) = controller.seekBy(deltaMs)
        override fun nudgeVolume(by: Float) {
            controller.volume = (controller.volume + by).coerceIn(0f, 1f)
        }
        override fun jumpToNowPlaying() = controller.jumpToNowPlaying()
        override fun toggleMute() = controller.toggleMute()
        override fun toggleShuffle() {
            controller.shuffle = !controller.shuffle
        }
        override fun cycleRepeat() = controller.cycleRepeat()
        override fun toggleShortcutsHelp() {
            controller.showShortcuts = !controller.showShortcuts
        }
        override fun selectAllVisible() = controller.selectAll(controller.visibleTracks)
        override fun clearSelection(): Boolean {
            if (!controller.hasSelection) return false
            controller.clearSelection()
            return true
        }
        override fun closeGroupPage(): Boolean = controller.closeGroupPage()
        override fun focusSearch(): Boolean = SearchBoxes.focus()
        override fun cycleAbLoop() = controller.cycleAbLoop()
        override fun openPalette(text: String?) = controller.openPalette(text)
    }
}

/** The system clipboard's text, or null when it holds none or can't be read just now. */
private fun systemClipboardText(): String? = try {
    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
    if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) clipboard.getData(DataFlavor.stringFlavor) as? String else null
} catch (e: Exception) {
    // Busy with another app, or no display: nothing to paste either way.
    null
}
