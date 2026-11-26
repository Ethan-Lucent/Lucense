package com.exo.musicplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import kotlin.math.min

/**
 * Lays [content] out at the size it was designed for and scales all of it -
 * text, spacing, panels, controls - to fit the window.
 *
 * A layout squeezed below its design size wraps, clips and overlaps; the same
 * layout drawn smaller does none of that, so a small window shows exactly the
 * big one in miniature. The scale follows the window continuously as it is
 * resized, never goes above 1 (a large window keeps everything at its normal
 * size, with more room) and stops at [minScale]; each window sets a minimum
 * size so it cannot be dragged smaller than that still fits.
 *
 * The scale is worked out from the window's size while composing, not while
 * measuring. It used to come from BoxWithConstraints, which composes its
 * content in the middle of the measure pass: a new density there recomposed
 * the whole window inside a measure, and on Windows a resize could then reach
 * a layout already being measured - "layout state is not idle before measure
 * starts", one error box per frame of the drag. Read here, a resize recomposes
 * first and measures after, as everything else does.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ScaledToWindow(
    designWidth: Dp,
    designHeight: Dp,
    minScale: Float = 0.62f,
    content: @Composable () -> Unit
) {
    val outer = LocalDensity.current
    // The window's content area, in pixels; zero before the first layout, which draws at full size.
    val window = LocalWindowInfo.current.containerSize
    val scale = if (window.width <= 0 || window.height <= 0) {
        1f
    } else {
        min(
            window.width / outer.density / designWidth.value,
            window.height / outer.density / designHeight.value
        ).coerceIn(minScale, 1f)
    }
    // One Density per scale, so unchanged sizes don't hand the content a new one.
    val scaled = remember(outer, scale) { Density(outer.density * scale, outer.fontScale) }
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalDensity provides scaled) {
            content()
        }
    }
}
