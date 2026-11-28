package com.exo.musicplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import com.exo.musicplayer.desktop.data.CollectionKind
import com.exo.musicplayer.desktop.system.Platform
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

/*
 * The mouse's two side buttons ("back" and "forward", under the thumb).
 *
 * Which AWT button number they arrive as depends on the system, and that was
 * read out of the JDK rather than assumed: on Windows they are buttons 4 and 5
 * (Compose turns those into PointerButton.Back and Forward); on Linux the X11
 * toolkit shifts extra buttons down by two, so the thumb buttons (X buttons 8
 * and 9) arrive as 6 and 7, which Compose does not know as anything, while 4
 * and 5 there are the wheel's sideways tilt. See [mouseSideOf].
 */

/** Which side button was pressed. */
enum class MouseSide { BACK, FORWARD }

/** What the side buttons do in the main window (Settings > Playback). */
enum class MouseButtonsMode(val label: String) {
    NAVIGATE("Go back / forward"),
    TRACKS("Previous / next song");

    companion object {
        fun fromName(name: String?): MouseButtonsMode = entries.firstOrNull { it.name == name } ?: NAVIGATE
    }
}

/** What one press comes to. */
enum class MouseAction { GO_BACK, GO_FORWARD, PREVIOUS_SONG, NEXT_SONG, CLOSE_MODAL }

internal const val MOUSE_BUTTONS_NOTE =
    "What the side buttons of your mouse do. Go back / forward moves between the pages you have " +
        "been on, as in a browser. Previous / next song plays the song before or after instead. " +
        "In the mini player and the lyrics window they are always previous and next song."

/**
 * Which side button an AWT button number is, if it is one. [linux] is the X11
 * toolkit, where the numbers are 6 and 7 (see above); anywhere else 4 and 5.
 */
fun mouseSideOf(awtButton: Int, linux: Boolean = Platform.isLinux): MouseSide? {
    val back = if (linux) 6 else 4
    return when (awtButton) {
        back -> MouseSide.BACK
        back + 1 -> MouseSide.FORWARD
        else -> null
    }
}

/**
 * What a press of [side] does, or null for nothing.
 *
 * The mini player and the lyrics windows ([playbackOnly]) have no pages, so
 * there the buttons are previous and next song in both modes. In the main
 * window a modal on screen (a dialog, the palette) takes Back as Esc does,
 * and Forward does nothing, so the page behind it never moves unseen.
 */
fun mouseAction(side: MouseSide, mode: MouseButtonsMode, playbackOnly: Boolean, modalOpen: Boolean): MouseAction? = when {
    playbackOnly -> if (side == MouseSide.BACK) MouseAction.PREVIOUS_SONG else MouseAction.NEXT_SONG
    modalOpen -> if (side == MouseSide.BACK) MouseAction.CLOSE_MODAL else null
    mode == MouseButtonsMode.TRACKS -> if (side == MouseSide.BACK) MouseAction.PREVIOUS_SONG else MouseAction.NEXT_SONG
    else -> if (side == MouseSide.BACK) MouseAction.GO_BACK else MouseAction.GO_FORWARD
}

/** One place the user has been: a page, the album or artist open on it, and Settings with its tab. */
data class NavPlace(
    val destination: Destination,
    val groupKind: CollectionKind? = null,
    val groupKey: String? = null,
    val settingsOpen: Boolean = false,
    val settingsTab: SettingsTab? = null
)

/**
 * Where the user has been, for Back and Forward: a back stack, the place on
 * screen, and a forward stack. Not tied to any screen, so it can be tested.
 *
 * [visit] is told about every place the screen shows. A place that is already
 * the current one is ignored - which is also how going back does not record
 * itself: [back] makes the place current first, then the screen shows it and
 * [visit] finds nothing new. A genuinely new place clears what was ahead.
 */
class NavHistory(private val limit: Int = MAX_PLACES) {
    private val behind = ArrayDeque<NavPlace>()
    private val ahead = ArrayDeque<NavPlace>()

    /** The place on screen, as far as the history knows. */
    var current: NavPlace? = null
        private set

    val canGoBack: Boolean get() = behind.isNotEmpty()
    val canGoForward: Boolean get() = ahead.isNotEmpty()

    fun visit(place: NavPlace) {
        val was = current
        if (place == was) return
        current = place
        if (was == null) return
        behind.addLast(was)
        // The oldest places fall off the far end.
        while (behind.size > limit) behind.removeFirst()
        ahead.clear()
    }

    /** The place to go back to, now current; null when there is none. */
    fun back(): NavPlace? {
        val to = behind.removeLastOrNull() ?: return null
        current?.let { ahead.addLast(it) }
        current = to
        return to
    }

    /** The place to go forward to, now current; null when there is none. */
    fun forward(): NavPlace? {
        val to = ahead.removeLastOrNull() ?: return null
        current?.let { behind.addLast(it) }
        current = to
        return to
    }

    companion object {
        const val MAX_PLACES = 50
    }
}

/**
 * The modals on screen, innermost last, so Back can close one as Esc does. A
 * ScrimDialog adds itself while it is shown ([Register]); the palette is the
 * controller's own. Touched from the UI thread only.
 */
object Modals {
    private val open = mutableListOf<() -> Unit>()

    val any: Boolean get() = open.isNotEmpty()

    /** Closes the innermost; false when none is open. */
    fun closeTop(): Boolean {
        val close = open.lastOrNull() ?: return false
        close()
        return true
    }

    @Composable
    fun Register(dismiss: () -> Unit) {
        val latest by rememberUpdatedState(dismiss)
        DisposableEffect(Unit) {
            val entry: () -> Unit = { latest() }
            open += entry
            onDispose { open -= entry }
        }
    }
}

/**
 * Takes the side buttons for a window whose pointer events Compose delivers
 * with the right button: Windows and macOS, where Back is AWT button 4. The
 * Initial pass sees the press before anything under the pointer, and it is
 * consumed there so a button or a row never also gets it. Release is ignored.
 * On Linux this does nothing and [SideMouseButtonsAwt] does the work instead.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.sideMouseButtons(shortcuts: WindowShortcuts, linux: Boolean = Platform.isLinux): Modifier =
    if (linux) {
        this
    } else {
        onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { event ->
            // Compose numbers its buttons from 0; AWT's from 1.
            val button = event.button ?: return@onPointerEvent
            if (shortcuts.mouseButton(button.index + 1, linux = false)) event.changes.forEach { it.consume() }
        }
    }

/**
 * The Linux way: Compose never reports the thumb buttons there (X11 hands
 * them over as AWT buttons 6 and 7, with no Compose button for them), so the
 * press is taken from AWT itself, for [window] only: one listener per window,
 * removed when the window goes. Compose ignores these presses, so there is
 * nothing to also consume.
 */
@Composable
fun SideMouseButtonsAwt(window: Window, shortcuts: WindowShortcuts) {
    if (!Platform.isLinux) return
    val current by rememberUpdatedState(shortcuts)
    DisposableEffect(window) {
        val listener = AWTEventListener { event ->
            if (event is MouseEvent && event.id == MouseEvent.MOUSE_PRESSED &&
                SwingUtilities.windowForComponent(event.component) === window
            ) {
                current.mouseButton(event.button, linux = true)
            }
        }
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK)
        onDispose { Toolkit.getDefaultToolkit().removeAWTEventListener(listener) }
    }
}

/**
 * Puts a window's content under the side buttons: the Compose way where that
 * works, the AWT listener on Linux. Wrap each window's content in this once.
 */
@Composable
fun SideButtonsHost(window: Window, shortcuts: WindowShortcuts, content: @Composable () -> Unit) {
    SideMouseButtonsAwt(window, shortcuts)
    // Min constraints pass through, so the content is laid out exactly as it was without the Box.
    Box(Modifier.fillMaxSize().sideMouseButtons(shortcuts), propagateMinConstraints = true) { content() }
}
