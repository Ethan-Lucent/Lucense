package com.exo.musicplayer.desktop.ui

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.Window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Fades the main window in when it comes back and out when it goes, so it
 * neither appears nor vanishes in a single frame.
 *
 * The fade is the window's own opacity ([setOpacity]) when the desktop can do
 * that ([translucent]). Without a compositor (some X11 setups, some Wayland)
 * a window's opacity does nothing, so then the picture inside is faded
 * instead ([contentAlpha], drawn by the root in Main.kt) - the window frame
 * itself still goes at once, but nothing pops.
 *
 * Everything runs on [scope] (the UI thread, in the app), and the clock is a
 * parameter so a test can run it in made-up time. Whatever happens - cancelled,
 * interrupted, the desktop refusing the opacity - it never stays part-way:
 * a fade-in ends at 1, a fade-out ends at 0, and [cancel] puts it back at 1.
 */
internal class WindowFade(
    private val setOpacity: (Float) -> Unit,
    translucent: Boolean,
    private val scope: CoroutineScope,
    /** Milliseconds, any origin. */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val frameMs: Long = 16,
    private val hardTimeoutMs: Long = Motion.WINDOW_FADE_HARD_TIMEOUT_MS.toLong(),
) {
    private var viaWindow = translucent

    /** How much of the picture shows when the window's own opacity can't be used; 1 otherwise. */
    var contentAlpha by mutableFloatStateOf(1f)
        private set

    /** Where the fade is now: 1 fully shown, 0 gone. */
    var level = 1f
        private set

    private var job: Job? = null
    private var watchdog: Job? = null
    private var direction = 0
    private var onDone: (() -> Unit)? = null

    /**
     * Brings the window up. Already fully up, or already fading in: nothing to do.
     * [restart] starts again from nothing - for a window that was only minimised,
     * which is still at full opacity when it is asked back.
     * A fade-out under way is turned round from where it has got to, and its
     * `onDone` is never called.
     */
    fun fadeIn(restart: Boolean = false, durationMs: Int = Motion.WINDOW_FADE_IN_MS) {
        if (!restart && (direction > 0 || (direction == 0 && level >= 1f))) return
        stop()
        onDone = null
        if (restart) apply(0f)
        run(1, durationMs)
    }

    /**
     * Takes the window down, then calls [onDone] - exactly once, and within the hard
     * timeout whatever the animation does, because this is the way out of the app.
     * Asked again while fading, only the newest [onDone] is kept (Quit after close).
     */
    fun fadeOut(durationMs: Int = Motion.WINDOW_FADE_OUT_MS, onDone: () -> Unit) {
        this.onDone = onDone
        if (direction < 0) return
        if (level <= 0f) {
            // Already gone (in the tray): nothing to fade.
            finishOut()
            return
        }
        stop()
        this.onDone = onDone
        watchdog = scope.launch {
            delay(hardTimeoutMs)
            finishOut()
        }
        run(-1, durationMs)
    }

    /** Stops whatever is running, drops any `onDone`, and leaves the window fully shown. */
    fun cancel() {
        stop()
        onDone = null
        apply(1f)
    }

    private fun stop() {
        job?.cancel()
        watchdog?.cancel()
        job = null
        watchdog = null
        direction = 0
    }

    private fun run(towards: Int, durationMs: Int) {
        direction = towards
        val from = level
        val to = if (towards > 0) 1f else 0f
        val easing = if (towards > 0) LinearOutSlowInEasing else FastOutLinearInEasing
        val length = durationMs.coerceAtLeast(1)
        job = scope.launch {
            val start = clock()
            while (true) {
                val t = ((clock() - start).toFloat() / length).coerceIn(0f, 1f)
                if (t >= 1f) break
                apply(from + (to - from) * easing.transform(t))
                delay(frameMs)
            }
            apply(to)
            direction = 0
            if (towards < 0) finishOut()
        }
    }

    private fun finishOut() {
        val done = onDone ?: return
        onDone = null
        // The animation may be stuck (that is what the timeout is for): end it where it should end.
        job?.cancel()
        watchdog?.cancel()
        job = null
        watchdog = null
        direction = 0
        apply(0f)
        done()
    }

    private fun apply(value: Float) {
        level = value
        if (viaWindow) {
            try {
                setOpacity(value)
                return
            } catch (e: Exception) {
                // The desktop turned the opacity down after all: use the picture instead from here on,
                // and don't leave the window part-way.
                viaWindow = false
                runCatching { setOpacity(1f) }
            }
        }
        contentAlpha = value
    }

    companion object {
        /**
         * Whether this window can be faded as a whole: the platform does uniform translucency,
         * the window is undecorated (Java refuses it otherwise), and setting it works. Never throws.
         */
        fun canFadeWindow(window: Window): Boolean = try {
            val device = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
            if (!device.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.TRANSLUCENT)) {
                false
            } else {
                window.opacity = 1f
                true
            }
        } catch (e: Throwable) {
            false
        }
    }
}
