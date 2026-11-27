package com.exo.musicplayer.desktop.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuRepresentation
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.areAnyPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.rememberPopupPositionProviderAtPosition
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * A song's rating as one row of the menu: press on the stars and slide, or
 * just click one. Drawn by [LucenseContextMenu]; any other menu draws only its
 * label and does nothing with it.
 */
class RatingMenuItem(val current: Int, val onRate: (Int) -> Unit) : ContextMenuItem("Rating", {})

/** A plain menu row with a short note on what it does, shown when the mouse rests on it. */
class NotedMenuItem(label: String, val note: String, onClick: () -> Unit) : ContextMenuItem(label, onClick)

/**
 * Lucense's own right-click menu, in place of the stock one, which only draws
 * a line of text per item and closes on any click, so it could not hold the
 * rating slider. Provided once at the root, so every ContextMenuArea - and the
 * copy and paste menu of a text field - comes out looking like the app.
 *
 * It opens at the pointer and is kept on screen near the window's edges,
 * closes on a click outside it or Escape, and Up, Down and Enter walk and pick
 * its plain rows.
 */
object LucenseContextMenu : ContextMenuRepresentation {
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    override fun Representation(state: ContextMenuState, items: () -> List<ContextMenuItem>) {
        // Outlives the menu itself, so a rating that is landing still gets
        // saved if a click outside closes the menu in the meantime.
        val scope = rememberCoroutineScope()
        val status = state.status
        if (status !is ContextMenuState.Status.Open) return
        val close = { state.status = ContextMenuState.Status.Closed }
        val list = items()
        val rows = list.indices.filter { list[it] !is RatingMenuItem }
        var selected by remember(status) { mutableStateOf(-1) }
        var landing by remember(status) { mutableStateOf(false) }

        Popup(
            popupPositionProvider = rememberPopupPositionProviderAtPosition(status.rect.center),
            onDismissRequest = close,
            properties = PopupProperties(focusable = true),
            onPreviewKeyEvent = { false },
            onKeyEvent = { event ->
                if (event.type != KeyEventType.KeyDown) {
                    false
                } else {
                    when (event.key) {
                        Key.DirectionDown -> { selected = step(rows, selected, 1); true }
                        Key.DirectionUp -> { selected = step(rows, selected, -1); true }
                        Key.Enter, Key.NumPadEnter -> {
                            val item = list.getOrNull(selected)
                            if (item != null && item !is RatingMenuItem) {
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
                    .width(IntrinsicSize.Max)
                    .widthIn(min = MENU_MIN_WIDTH)
                    .floating(8.dp)
                    .padding(4.dp)
            ) {
                list.forEachIndexed { i, item ->
                    if (item is RatingMenuItem) {
                        RatingSlider(
                            item.current,
                            onPick = { stars ->
                                // Held open a moment so the stars are seen to land,
                                // then closed, then saved. Once per menu.
                                if (!landing) {
                                    landing = true
                                    scope.launch {
                                        delay(RATING_LAND_MS)
                                        close()
                                        item.onRate(stars)
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        MenuRow(
                            item.label,
                            note = (item as? NotedMenuItem)?.note,
                            lit = i == selected,
                            onHover = { over -> if (over) selected = i else if (selected == i) selected = -1 }
                        ) {
                            close()
                            item.onClick()
                        }
                    }
                }
            }
        }
    }
}

/** The next plain row up or down from [from], round the ends; the first or last when none is picked yet. */
internal fun step(rows: List<Int>, from: Int, by: Int): Int {
    if (rows.isEmpty()) return -1
    val at = rows.indexOf(from)
    return if (at < 0) {
        if (by > 0) rows.first() else rows.last()
    } else {
        rows[(at + by).mod(rows.size)]
    }
}

/**
 * One plain row. The mouse over it and the keyboard's pick light the same
 * fill, so the two never show two rows at once. Its note waits until the
 * mouse has rested a moment, and never comes while a button is held - that
 * is someone dragging or clicking, not reading.
 *
 * Shared with [MoreMenu], so every menu in the app is this one row. A row that
 * is not [enabled] is dimmed and does nothing, but still shows its note.
 */
@Composable
internal fun MenuRow(
    label: String,
    note: String?,
    lit: Boolean,
    onHover: (Boolean) -> Unit,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    var over by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf(false) }
    var noting by remember { mutableStateOf(false) }
    val latestHover by rememberUpdatedState(onHover)
    val resting = over && !held
    LaunchedEffect(resting, note) {
        noting = false
        if (note != null && resting) {
            delay(NOTE_DELAY_MS)
            noting = true
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(MENU_ROW)
            .clip(RoundedCornerShape(5.dp))
            .background(hoverFill(lit && enabled))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        held = event.buttons.areAnyPressed
                        when (event.type) {
                            PointerEventType.Enter, PointerEventType.Move -> {
                                if (!over) {
                                    over = true
                                    latestHover(true)
                                }
                            }
                            PointerEventType.Exit -> {
                                over = false
                                latestHover(false)
                            }
                        }
                    }
                }
            }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) Palette.Text else Palette.TextFaint,
            maxLines = 1,
            softWrap = false
        )
        if (noting && Notes.on && note != null) NoteBubble(note)
    }
}

/** A row's note, in a small bubble beside the menu - on the left where the right has no room. */
@Composable
private fun NoteBubble(note: String) {
    val gap = with(LocalDensity.current) { NOTE_GAP.roundToPx() }
    Popup(
        popupPositionProvider = remember(gap) { BesideRow(gap) },
        onDismissRequest = {},
        properties = PopupProperties(focusable = false)
    ) {
        Text(
            note,
            style = MaterialTheme.typography.labelMedium,
            color = Palette.TextDim,
            modifier = Modifier
                .widthIn(max = 260.dp)
                .floating(6.dp)
                .padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

private class BesideRow(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val right = anchorBounds.right + gap
        val x = if (right + popupContentSize.width <= windowSize.width) {
            right
        } else {
            (anchorBounds.left - gap - popupContentSize.width).coerceAtLeast(0)
        }
        val y = anchorBounds.top.coerceAtMost(windowSize.height - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

/**
 * The rating as one control: the word, five stars and what they mean. Press
 * anywhere on the stars and slide: they fill to the pointer and keep
 * following it off the row until the button comes up, as the seek bar does
 * (the same [followPress]). Letting go picks what is shown. A press and
 * release without moving is a click on that star - the same path - and a
 * click on the star already chosen takes the rating away, as does sliding off
 * the left of the first star.
 */
@Composable
internal fun RatingSlider(current: Int, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    val latestCurrent by rememberUpdatedState(current)
    val latestPick by rememberUpdatedState(onPick)
    var dragging by remember { mutableStateOf<Int?>(null) }
    var hovering by remember { mutableStateOf<Int?>(null) }
    // What was picked, shown until the rating it was given to comes back.
    var picked by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(current) { if (picked == current) picked = null }

    val settled = picked ?: current
    val press = dragging
    // Eased over the seek bar ball's 140 ms, so the stars run up rather than blink.
    val fill by animateFloatAsState((press ?: settled).toFloat(), tween(140))
    val preview by animateFloatAsState((press ?: hovering ?: settled).toFloat(), tween(140))
    val accent = Palette.Accent
    val empty = Palette.TextFaint

    Row(modifier.height(MENU_ROW).padding(start = 10.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Rating", style = MaterialTheme.typography.bodyMedium, color = Palette.Text, maxLines = 1, softWrap = false)
        Spacer(Modifier.weight(1f).widthIn(min = 12.dp))
        Box(
            Modifier
                .testTag(RATING_STARS_TAG)
                .size(width = RATING_CLEAR + RATING_STAR * 5, height = MENU_ROW)
                .pointerHoverIcon(PointerIcon.Hand)
                .pointerInput(Unit) {
                    fun at(x: Float): Float = (x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    fun zone(): Float = RATING_CLEAR.toPx() / size.width.coerceAtLeast(1)
                    var pressed = false
                    var first = 0
                    var moved = false
                    followPress(
                        at = ::at,
                        onMove = { f ->
                            val stars = starsAt(f, zone())
                            if (!pressed) {
                                pressed = true
                                first = stars
                                moved = false
                            } else if (stars != first) {
                                moved = true
                            }
                            dragging = stars
                        },
                        onRelease = { f ->
                            val stars = starsAt(f, zone())
                            val pick = if (moved || stars != first) stars else ratingAfterClick(stars, latestCurrent)
                            picked = pick
                            latestPick(pick)
                        },
                        onEnd = {
                            pressed = false
                            dragging = null
                        }
                    )
                }
                .pointerInput(Unit) {
                    // Resting over the stars shows lightly what a click would give.
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val x = event.changes.firstOrNull()?.position?.x ?: continue
                            hovering = if (event.type == PointerEventType.Exit) {
                                null
                            } else {
                                starsAt((x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f), RATING_CLEAR.toPx() / size.width.coerceAtLeast(1))
                            }
                        }
                    }
                }
        ) {
            Canvas(Modifier.matchParentSize()) {
                val clear = RATING_CLEAR.toPx()
                val each = (size.width - clear) / 5f
                val r = each * 0.42f
                val outline = Stroke(1.3.dp.toPx(), join = StrokeJoin.Round)
                for (i in 0 until 5) {
                    val cx = clear + each * (i + 0.5f)
                    val path = starPath(cx, size.height / 2f, r)
                    val solid = (fill - i).coerceIn(0f, 1f)
                    val light = (preview - i).coerceIn(0f, 1f)
                    drawPath(path, empty, style = outline)
                    if (light > solid) {
                        clipRect(left = cx - r, right = cx - r + 2f * r * light) { drawPath(path, accent.copy(alpha = 0.35f)) }
                    }
                    if (solid > 0f) {
                        clipRect(right = cx - r + 2f * r * solid) {
                            drawPath(path, accent)
                            drawPath(path, accent, style = outline)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            starsLabel(press ?: hovering ?: settled),
            style = MaterialTheme.typography.labelMedium,
            color = if (press != null || hovering == null) Palette.TextDim else Palette.TextFaint,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.width(60.dp)
        )
    }
}

/** A five-pointed star around ([cx], [cy]), its points [r] out, the first straight up. */
private fun starPath(cx: Float, cy: Float, r: Float): Path = Path().apply {
    // Nudged down a little: the points' box sits high of the star's middle.
    val y0 = cy + r * 0.08f
    for (k in 0 until 10) {
        val radius = if (k % 2 == 0) r else r * 0.45f
        val angle = -PI / 2 + k * PI / 5
        val x = cx + radius * cos(angle).toFloat()
        val y = y0 + radius * sin(angle).toFloat()
        if (k == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/**
 * The stars under a point [fraction] of the way along the control, whose
 * first [clearZone] (also a fraction) is the gap left of the first star that
 * means no rating. The rest is five equal stars, each counting from its left edge.
 */
internal fun starsAt(fraction: Float, clearZone: Float): Int {
    if (fraction < clearZone) return 0
    val along = (fraction - clearZone) / (1f - clearZone).coerceAtLeast(1e-6f)
    return ceil(along * 5f).toInt().coerceIn(1, 5)
}

/** A click on a star: that rating, or none when it is the one already given. */
internal fun ratingAfterClick(clicked: Int, current: Int): Int = if (clicked == current) 0 else clicked

internal fun starsLabel(stars: Int): String = when (stars) {
    0 -> "No rating"
    1 -> "1 star"
    else -> "$stars stars"
}

private val MENU_ROW = 30.dp
internal val MENU_MIN_WIDTH = 190.dp
private val NOTE_GAP = 10.dp
private const val NOTE_DELAY_MS = 600L
private const val RATING_LAND_MS = 300L

/** The gap left of the first star that means no rating, and each star's width. */
internal val RATING_CLEAR = 12.dp
internal val RATING_STAR = 20.dp
internal const val RATING_STARS_TAG = "rating-stars"
