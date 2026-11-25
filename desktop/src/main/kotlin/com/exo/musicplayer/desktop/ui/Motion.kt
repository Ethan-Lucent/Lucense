package com.exo.musicplayer.desktop.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The few timings everything that moves shares, so the app moves as one thing.
 *
 * Short on purpose: long enough to see where something came from, never so
 * long it is waited on. Everything here animates in the draw phase or through
 * Compose's own animation clock - nothing recomposes a page per frame.
 */
object Motion {
    /** A hover, a press, a toggle: felt rather than watched. */
    const val QUICK_MS = 120

    /** A page or a tab changing, a cover arriving. */
    const val CHANGE_MS = 180

    /** A panel sliding in or out. */
    const val SLIDE_MS = 220

    fun <T> quick(): TweenSpec<T> = tween(QUICK_MS, easing = FastOutSlowInEasing)
    fun <T> change(): TweenSpec<T> = tween(CHANGE_MS, easing = FastOutSlowInEasing)
    fun <T> slide(): TweenSpec<T> = tween(SLIDE_MS, easing = FastOutSlowInEasing)
}

/**
 * A row that scrolls sideways fades out at whichever edge has more beyond it.
 *
 * Cut off square, a row of chips simply looked broken in a narrow window -
 * "Date add" stopping at a button, nothing to say the rest was there. Put
 * before the horizontalScroll, on the same [state], so it shades the visible
 * part rather than the content.
 */
fun Modifier.fadingEdges(state: ScrollState, width: Dp = 28.dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fade = width.toPx().coerceAtMost(size.width / 3)
        if (state.value > 0) {
            drawRect(
                Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = fade),
                size = Size(fade, size.height),
                blendMode = BlendMode.DstIn
            )
        }
        if (state.value < state.maxValue) {
            drawRect(
                Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - fade, endX = size.width),
                topLeft = Offset(size.width - fade, 0f),
                size = Size(fade, size.height),
                blendMode = BlendMode.DstIn
            )
        }
    }

/** A sideways-scrolling row with [fadingEdges] on its own scroll state. */
@Composable
fun Modifier.fadingHorizontalScroll(): Modifier {
    val state = rememberScrollState()
    return this.fadingEdges(state).horizontalScroll(state)
}
