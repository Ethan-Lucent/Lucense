package com.exo.musicplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/**
 * One row of a [MoreMenu]. [note] is the line shown when the mouse rests on
 * the row, as in the right-click menu; a row that is not [enabled] is dimmed
 * and does nothing.
 */
class MoreItem(
    val label: String,
    val note: String? = null,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

/**
 * The "⋯" button that holds what is not needed every day: one click opens a
 * small menu of [items] under it. It is drawn as the right-click menu is
 * (the same rows), closes when a row is picked, on a click outside it, or on
 * Escape, and Up, Down and Enter walk and pick its rows.
 *
 * With [iconOnly] false the button is a ghost button reading [label] instead,
 * for where there is room for a word. When it is only the icon, [label] is
 * its name in the hover note.
 */
@Composable
fun MoreMenu(label: String = "More", items: List<MoreItem>, iconOnly: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    Box {
        if (iconOnly) {
            Noted(label, null, iconOnly = true) {
                IconChip(label, Icons.Default.MoreHoriz, enabled = true) { open = !open }
            }
        } else {
            GhostButton(label, icon = Icons.Default.MoreHoriz) { open = !open }
        }
        if (open) MoreItems(items) { open = false }
    }
}

/** The popup under the button; [close] is asked for on a pick, an outside click or Escape. */
@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun MoreItems(items: List<MoreItem>, close: () -> Unit) {
    val rows = items.indices.filter { items[it].enabled }
    var selected by remember { mutableStateOf(-1) }
    // Read out here: inside the popup the window info may be the popup's own.
    val window = LocalWindowInfo.current.containerSize
    Popup(
        popupPositionProvider = remember { UnderButton },
        onDismissRequest = close,
        properties = PopupProperties(focusable = true),
        onKeyEvent = { event ->
            if (event.type != KeyEventType.KeyDown) {
                false
            } else {
                when (event.key) {
                    Key.DirectionDown -> { selected = step(rows, selected, 1); true }
                    Key.DirectionUp -> { selected = step(rows, selected, -1); true }
                    Key.Enter, Key.NumPadEnter -> {
                        val item = items.getOrNull(selected)
                        if (item != null && item.enabled) {
                            close()
                            item.onClick()
                        }
                        true
                    }
                    Key.Escape -> { close(); true }
                    else -> false
                }
            }
        }
    ) {
        Column(
            Modifier
                // Limits first, then the row-width: the other way round, IntrinsicSize pins the width and the minimum is lost.
                .menuPanelSize(window)
                .width(IntrinsicSize.Max)
                .floating(8.dp)
                .verticalScroll(rememberScrollState())
                .padding(4.dp)
        ) {
            items.forEachIndexed { i, item ->
                MenuRow(
                    item.label,
                    note = item.note,
                    lit = i == selected,
                    onHover = { over -> if (over) selected = i else if (selected == i) selected = -1 },
                    enabled = item.enabled
                ) {
                    close()
                    item.onClick()
                }
            }
        }
    }
}

/**
 * Just under the button, its left edges aligned; flush with the right edge
 * instead where it would run off the window, and above where there is no room
 * below.
 */
private object UnderButton : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val x = if (anchorBounds.left + popupContentSize.width <= windowSize.width) {
            anchorBounds.left
        } else {
            (anchorBounds.right - popupContentSize.width).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        }
        val below = anchorBounds.bottom + 2
        val y = if (below + popupContentSize.height <= windowSize.height) {
            below
        } else {
            (anchorBounds.top - 2 - popupContentSize.height).coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
        }
        return IntOffset(x, y)
    }
}
