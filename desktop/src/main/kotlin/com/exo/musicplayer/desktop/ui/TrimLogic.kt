package com.exo.musicplayer.desktop.ui

import androidx.compose.ui.input.key.Key
import com.exo.musicplayer.data.audio.Trim

/*
 * The Trim window's rules, kept apart from its drawing so they can be tested
 * without a window: where a handle may go, what the keys do, when saving is
 * allowed and why not, and where the window opens.
 */

/** How far an arrow key moves the playhead; with Shift, the larger one. */
internal const val TRIM_STEP_MS = 100L
internal const val TRIM_BIG_STEP_MS = 1_000L

/** The start handle, kept at least [Trim.MIN_LENGTH_MS] short of the end. */
internal fun clampStart(ms: Long, endMs: Long): Long =
    ms.coerceIn(0L, (endMs - Trim.MIN_LENGTH_MS).coerceAtLeast(0L))

/** The end handle, kept at least [Trim.MIN_LENGTH_MS] past the start, and inside the song. */
internal fun clampEnd(ms: Long, startMs: Long, lengthMs: Long): Long =
    ms.coerceIn((startMs + Trim.MIN_LENGTH_MS).coerceAtMost(lengthMs), lengthMs)

/** [ms] moved one step earlier ([direction] -1) or later (+1), staying inside 0 to [lengthMs]. */
internal fun nudge(ms: Long, direction: Int, shift: Boolean, lengthMs: Long): Long =
    (ms + direction * (if (shift) TRIM_BIG_STEP_MS else TRIM_STEP_MS)).coerceIn(0L, lengthMs.coerceAtLeast(0L))

/** Where I and O mark: the playhead while the preview runs, else where it was left, else the start. */
internal fun markPosition(playheadMs: Long?, cursorMs: Long?, startMs: Long): Long = playheadMs ?: cursorMs ?: startMs

/**
 * Why saving isn't on offer, in words for the hover note, or null when it is.
 * The whole song is still worth saving when the effects are going to be baked in.
 */
internal fun saveBlocker(lengthMs: Long, startMs: Long, endMs: Long, withEffects: Boolean): String? = when {
    lengthMs <= 0 -> "The song is still being read."
    endMs - startMs < Trim.MIN_LENGTH_MS -> Notes.TRIM_SAVE_SHORT
    startMs <= 0 && endMs >= lengthMs && !withEffects -> Notes.TRIM_SAVE_NOTHING
    else -> null
}

internal fun canSave(lengthMs: Long, startMs: Long, endMs: Long, withEffects: Boolean, saving: Boolean): Boolean =
    !saving && saveBlocker(lengthMs, startMs, endMs, withEffects) == null

/** "removes 0:12.0", or "nothing removed" while the whole song is kept. */
internal fun removedText(lengthMs: Long, startMs: Long, endMs: Long): String {
    val removed = lengthMs - (endMs - startMs)
    return if (removed <= 0) "nothing removed" else "removes ${Trim.clock(removed)}"
}

internal enum class TrimKeyAction { PLAY, SET_START, SET_END, EARLIER, LATER, SAVE, CLOSE }

/** What a key does in the Trim window, or null when it is not one of its keys. */
internal fun trimKeyAction(key: Key): TrimKeyAction? = when (key) {
    Key.Spacebar -> TrimKeyAction.PLAY
    Key.I -> TrimKeyAction.SET_START
    Key.O -> TrimKeyAction.SET_END
    Key.DirectionLeft -> TrimKeyAction.EARLIER
    Key.DirectionRight -> TrimKeyAction.LATER
    Key.Enter, Key.NumPadEnter -> TrimKeyAction.SAVE
    Key.Escape -> TrimKeyAction.CLOSE
    else -> null
}

/** A window's place and size on the desktop, in the window system's own units. */
internal data class TrimBox(val x: Int, val y: Int, val w: Int, val h: Int) {
    fun contains(px: Int, py: Int) = px in x until x + w && py in y until y + h
    override fun toString() = "$x,$y,$w,$h"

    companion object {
        fun parse(text: String): TrimBox? =
            text.split(',').mapNotNull { it.trim().toIntOrNull() }.takeIf { it.size == 4 }
                ?.let { (x, y, w, h) -> TrimBox(x, y, w, h) }
    }
}

internal const val TRIM_MIN_W = 560
internal const val TRIM_MIN_H = 420
internal const val TRIM_DEFAULT_W = 760
internal const val TRIM_DEFAULT_H = 600

/** Room the main window's side panel takes on the right, which the Trim window opens clear of. */
private const val SIDE_PANEL_W = 360

/**
 * Where the Trim window opens: where it was left if that is still on a screen
 * (its title bar can be reached), otherwise at the left of the main window
 * ([owner]), clear of the effects panel on its right so the two can be used
 * together. Always inside a screen, never smaller than the minimum size.
 */
internal fun trimPlacement(saved: String, owner: TrimBox?, screens: List<TrimBox>): TrimBox {
    val screen = fun(box: TrimBox?) = box?.let { b -> screens.firstOrNull { it.contains(b.x + b.w / 2, b.y + b.h / 2) } }
    TrimBox.parse(saved)?.let { was ->
        val onScreen = screens.firstOrNull { it.contains(was.x + was.w / 2, was.y + 16) }
        if (onScreen != null && was.w >= TRIM_MIN_W && was.h >= TRIM_MIN_H) {
            return fit(was, onScreen)
        }
    }
    val home = screen(owner) ?: screens.firstOrNull() ?: return TrimBox(80, 80, TRIM_DEFAULT_W, TRIM_DEFAULT_H)
    val width = if (owner == null) TRIM_DEFAULT_W
    else (owner.w - SIDE_PANEL_W - 48).coerceIn(TRIM_MIN_W, TRIM_DEFAULT_W)
    val first = TrimBox(
        (owner?.x?.plus(24)) ?: (home.x + (home.w - width) / 2),
        (owner?.y?.plus(48)) ?: (home.y + (home.h - TRIM_DEFAULT_H) / 2),
        width,
        TRIM_DEFAULT_H
    )
    return fit(first, home)
}

/** [box] shrunk to [screen] if it is bigger, and moved in if it hangs off. */
private fun fit(box: TrimBox, screen: TrimBox): TrimBox {
    val w = box.w.coerceAtMost(screen.w).coerceAtLeast(minOf(TRIM_MIN_W, screen.w))
    val h = box.h.coerceAtMost(screen.h).coerceAtLeast(minOf(TRIM_MIN_H, screen.h))
    return TrimBox(
        box.x.coerceIn(screen.x, screen.x + screen.w - w),
        box.y.coerceIn(screen.y, screen.y + screen.h - h),
        w,
        h
    )
}
