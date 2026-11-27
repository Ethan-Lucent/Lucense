package com.exo.musicplayer.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/*
 * The row of choices for a small enum.
 *
 * It used to be a plain Row of text boxes. In a pane narrower than the row,
 * Row squeezed every box, the words broke ("HTTP" over "S", "Vivaldi" as "V" /
 * "di"), the tallest box set the row's height, and the rounded container - a
 * capsule, whose corners were half of whatever height it ended up - grew into
 * a huge oval with the selected pill as a tall circle inside it.
 *
 * Now every pill is exactly one row tall and its words never wrap. The row is
 * decided in one layout pass from the pills' natural widths and the width the
 * parent offers - nothing carried over from an earlier frame, nothing written
 * while measuring (see ScaledToWindow for why that matters):
 *  - everything fits: one row of natural-width pills, exactly as before;
 *  - doesn't fit, few options: the pills wrap onto more rows, each the same height;
 *  - doesn't fit, many options: one row that scrolls sideways, fading at the
 *    edges that have more, with the chosen pill kept in view.
 */

/** One pill's height; a row is this plus its own 2dp above and below, as it always was. */
private val PILL_HEIGHT = 28.dp
private val PILL_MIN_WIDTH = 32.dp
private val PAD = 2.dp
private val GAP = 2.dp

/** The height of one row of the control; two rows are twice this, and so on. */
internal val SEGMENT_ROW_HEIGHT = PILL_HEIGHT + PAD * 2

/** How many options may wrap onto further rows before a sideways scroll reads better. */
internal const val SEGMENT_WRAP_LIMIT = 8

/** Corner radius of the control's container, in dp - never a share of its height. */
internal fun segmentCorner(rows: Int, glass: Boolean): androidx.compose.ui.unit.Dp = when {
    !glass -> 7.dp
    rows <= 1 -> SEGMENT_ROW_HEIGHT / 2 // the capsule it always was, now fixed
    else -> 14.dp
}

private val FADE = 20.dp

/**
 * The glass container's outline: its corner comes from how many rows it holds
 * (see [segmentCorner]) - a fixed size in dp that does not grow with the height.
 * Worked out from the size when it is drawn, so the layout never has to say.
 */
private object SegmentShape : androidx.compose.ui.graphics.Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        density: androidx.compose.ui.unit.Density
    ): androidx.compose.ui.graphics.Outline {
        val rows = (size.height / with(density) { SEGMENT_ROW_HEIGHT.toPx() }).roundToInt().coerceAtLeast(1)
        val r = with(density) { segmentCorner(rows, true).toPx() }
        return androidx.compose.ui.graphics.Outline.Rounded(
            androidx.compose.ui.geometry.RoundRect(0f, 0f, size.width, size.height, androidx.compose.ui.geometry.CornerRadius(r))
        )
    }
}

internal enum class SegmentFit { ONE_ROW, WRAP, SCROLL }

/**
 * How the pills lay out: all on one row if their [widths] (plus [gap]s and the
 * padding on both sides) fit in [available], else wrapped or scrolling. Pure,
 * so it can be tested without a window. An unbounded [available] is one row.
 */
internal fun segmentFit(widths: List<Int>, available: Int, gap: Int, pad: Int, scrollWhenTight: Boolean): SegmentFit {
    if (available == Constraints.Infinity) return SegmentFit.ONE_ROW
    val total = widths.sum() + gap * (widths.size - 1).coerceAtLeast(0) + pad * 2
    return when {
        total <= available -> SegmentFit.ONE_ROW
        scrollWhenTight -> SegmentFit.SCROLL
        else -> SegmentFit.WRAP
    }
}

/**
 * Where a wrapped layout breaks: the index ranges, one per row, filling each row
 * with as many pills as fit in [inner]. A pill wider than a row has a row to itself.
 */
internal fun segmentRows(widths: List<Int>, inner: Int, gap: Int): List<IntRange> {
    val rows = ArrayList<IntRange>()
    var start = 0
    var used = 0
    for (i in widths.indices) {
        val w = widths[i].coerceAtMost(inner)
        if (i > start && used + gap + w > inner) {
            rows += start until i
            start = i
            used = w
        } else {
            used = if (i == start) w else used + gap + w
        }
    }
    if (widths.isNotEmpty()) rows += start until widths.size
    return rows
}

/** What the layout pass found, kept for the scroll and for scrolling the chosen pill into view. */
private class SegmentScroll {
    var offset by mutableFloatStateOf(0f)
    // Plain fields, not state: the layout pass sets them and nothing reads them while composing.
    var max = 0
    var viewport = 0
    var scrolls = false
    var left = IntArray(0)
    var right = IntArray(0)
    var shown = false
}

/** Segmented row of choices — the desktop idiom for a small enum. */
@Composable
fun <T> SegmentedRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    /** Scroll sideways, rather than wrap, when the options don't fit. */
    scrollWhenTight: Boolean = options.size > SEGMENT_WRAP_LIMIT
) {
    val glass = Glass.on
    val scroll = remember { SegmentScroll() }
    val scrollable = rememberScrollableState { delta ->
        val was = scroll.offset
        val now = (was + delta).coerceIn(0f, scroll.max.toFloat())
        scroll.offset = now
        now - was
    }
    var viewportW by remember { mutableIntStateOf(0) }
    val selectedIndex = options.indexOf(selected)
    val density = LocalDensity.current
    val fadePx = with(density) { FADE.toPx() }

    // Bring the chosen pill into view when the choice or the width changes. After a frame,
    // so the layout it reads has happened.
    LaunchedEffect(selectedIndex, viewportW) {
        withFrameNanos { }
        if (!scroll.scrolls || selectedIndex !in scroll.left.indices) return@LaunchedEffect
        val from = scroll.left[selectedIndex] - fadePx
        val to = scroll.right[selectedIndex] + fadePx
        val view = scroll.viewport.toFloat()
        val delta = when {
            from < scroll.offset -> from - scroll.offset
            to > scroll.offset + view -> to - view - scroll.offset
            else -> 0f
        }
        if (delta == 0f) return@LaunchedEffect
        if (scroll.shown) scrollable.animateScrollBy(delta) else scrollable.scrollBy(delta)
        scroll.shown = true
    }

    val container: androidx.compose.ui.graphics.Shape = if (glass) SegmentShape else RoundedCornerShape(segmentCorner(1, false))
    Layout(
        content = {
            options.forEach { option ->
                SegmentPill(label(option), option == selected, glass) { onSelect(option) }
            }
        },
        modifier = Modifier
            .testTag("segmented")
            .then(
                if (glass) {
                    Modifier.glassChip(container, fill = 0.04f)
                } else {
                    Modifier
                        .clip(container)
                        .background(Palette.Content)
                        .border(1.dp, Palette.Line, container)
                }
            )
            .onSizeChanged { viewportW = it.width }
            .scrollable(scrollable, Orientation.Horizontal, reverseDirection = true)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                // Fade the pills out where there is more to scroll to; the border and fill are drawn outside this.
                if (scroll.scrolls && scroll.max > 0) {
                    val off = scroll.offset
                    if (off > 0.5f) {
                        drawRect(
                            Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), 0f, fadePx),
                            size = Size(fadePx, size.height), blendMode = BlendMode.DstIn
                        )
                    }
                    if (off < scroll.max - 0.5f) {
                        drawRect(
                            Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), size.width - fadePx, size.width),
                            topLeft = Offset(size.width - fadePx, 0f), size = Size(fadePx, size.height),
                            blendMode = BlendMode.DstIn
                        )
                    }
                }
            }
    ) { measurables, constraints ->
        val pad = PAD.roundToPx()
        val gap = GAP.roundToPx()
        // Twice the padding between rows, in whole pixels, so n rows are exactly n times one row at any scale.
        val rowGap = pad * 2
        val pillH = PILL_HEIGHT.roundToPx()
        val natural = measurables.map { it.maxIntrinsicWidth(pillH) }
        val fit = segmentFit(natural, constraints.maxWidth, gap, pad, scrollWhenTight)

        val rows: List<IntRange> = when (fit) {
            SegmentFit.WRAP -> segmentRows(natural, constraints.maxWidth - pad * 2, gap)
            else -> listOf(measurables.indices)
        }
        val inner = if (constraints.maxWidth == Constraints.Infinity) Int.MAX_VALUE else constraints.maxWidth - pad * 2
        val widths = natural.map { if (fit == SegmentFit.WRAP) it.coerceAtMost(inner) else it }
        val placeables = measurables.mapIndexed { i, m -> m.measure(Constraints.fixed(widths[i], pillH)) }

        val rowWidth = rows.maxOfOrNull { r -> r.sumOf { widths[it] } + gap * (r.count() - 1).coerceAtLeast(0) } ?: 0
        val height = (pad * 2 + rows.size * pillH + (rows.size - 1).coerceAtLeast(0) * rowGap)
            .coerceIn(constraints.minHeight, maxOf(constraints.minHeight, constraints.maxHeight))
        val width = when (fit) {
            SegmentFit.ONE_ROW -> (rowWidth + pad * 2)
            else -> constraints.maxWidth
        }.coerceIn(constraints.minWidth, maxOf(constraints.minWidth, constraints.maxWidth))

        val lefts = IntArray(placeables.size)
        val rights = IntArray(placeables.size)
        rows.forEachIndexed { r, range ->
            var x = pad
            for (i in range) {
                lefts[i] = x
                rights[i] = x + widths[i]
                x += widths[i] + gap
            }
        }
        val scrolls = fit == SegmentFit.SCROLL
        scroll.scrolls = scrolls
        scroll.viewport = width
        scroll.max = if (scrolls) (rights.last() + pad - width).coerceAtLeast(0) else 0
        scroll.left = lefts
        scroll.right = rights

        layout(width, height) {
            val shift = if (scrolls) scroll.offset.coerceIn(0f, scroll.max.toFloat()).toInt() else 0
            rows.forEachIndexed { r, range ->
                val y = pad + r * (pillH + rowGap)
                for (i in range) placeables[i].placeRelative(lefts[i] - shift, y)
            }
        }
    }
}

/** One choice: a pill of fixed height whose words stay on one line, cut with an ellipsis if even a whole row is too narrow. */
@Composable
private fun SegmentPill(text: String, isSelected: Boolean, glass: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(if (glass) PILL_HEIGHT / 2 else 5.dp)
    Box(
        Modifier
            .widthIn(min = PILL_MIN_WIDTH)
            .then(
                if (glass && isSelected) {
                    Modifier.glassChip(shape, fill = 0.12f, edge = 1.1f)
                        .background(Palette.Selected.copy(alpha = 0.55f))
                } else {
                    Modifier.clip(shape)
                }
            )
            .background(if (isSelected && !glass) Palette.Selected else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isSelected) Palette.Text else Palette.TextDim,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}
