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
 * and Ctrl+Up and Ctrl+Down, which have always changed the volume too.
 */
class WindowShortcuts(private val actions: Actions) {

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
    }

    /**
     * Whether the space bar is down, having played or paused. A held key repeats
     * its press, and one press should be one toggle, not a stutter.
     */
    private var spaceHeld = false

    fun preview(event: KeyEvent): Boolean {
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
            down && event.isCtrlPressed && event.key == Key.A -> {
                actions.selectAllVisible()
                true
            }
            down && event.key == Key.Escape -> actions.clearSelection()
            else -> false
        }
    }

    fun bubble(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        // Even from a text box on the page: Escape there was always the way back.
        if (event.key == Key.Escape) return actions.closeGroupPage()
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
            Key.J -> act { actions.jumpToNowPlaying() }
            Key.M -> act { actions.toggleMute() }
            Key.S -> act { actions.toggleShuffle() }
            Key.R -> act { actions.cycleRepeat() }
            Key.Slash -> act { actions.toggleShortcutsHelp() }
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
    }
}
