package com.exo.musicplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import kotlin.math.max
import kotlin.math.min

/**
 * How big the user wants everything (Settings > Appearance > Interface size),
 * 1 being normal. Set from the saved setting when the controller starts, and
 * by the choice; read by [ScaledToWindow], which makes it the most it will
 * scale up to.
 */
object UiScale {
    var factor by mutableStateOf(1f)
}

/** The sizes offered, by name: the choice in Settings and the Ctrl+K rows step through these in order. */
internal val UI_SCALES = listOf("Small" to 0.9f, "Normal" to 1f, "Large" to 1.15f, "Larger" to 1.3f)

/** The size [steps] places away from [current] (-1 smaller, +1 larger), stopping at the ends; for the Ctrl+K rows. */
internal fun stepUiScale(current: Float, steps: Int): Float {
    val at = UI_SCALES.indices.minByOrNull { kotlin.math.abs(UI_SCALES[it].second - current) } ?: 1
    return UI_SCALES[(at + steps).coerceIn(0, UI_SCALES.lastIndex)].second
}

/**
 * The scale [ScaledToWindow] draws at. The window still decides: the layout is
 * shrunk until its design size fits the window, down to [minScale], so it can
 * never overflow or clip. [userFactor] only raises how far a roomy window may
 * enlarge it - at 1 that is the old rule, never above normal size. Before the
 * window has a size (the first frame) it is drawn at normal size.
 */
internal fun fitScale(
    windowW: Float,
    windowH: Float,
    density: Float,
    designW: Float,
    designH: Float,
    minScale: Float,
    userFactor: Float
): Float {
    if (windowW <= 0f || windowH <= 0f) return 1f
    val fit = min(windowW / density / designW, windowH / density / designH)
    return fit.coerceIn(minScale, max(minScale, userFactor))
}

/**
 * Lays [content] out at the size it was designed for and scales all of it -
 * text, spacing, panels, controls - to fit the window.
 *
 * A layout squeezed below its design size wraps, clips and overlaps; the same
 * layout drawn smaller does none of that, so a small window shows exactly the
 * big one in miniature. The scale follows the window continuously as it is
 * resized, never goes above 1 (a large window keeps everything at its normal
 * size, with more room - unless the user chose a bigger interface size, see
 * [fitScale]) and stops at [minScale]; each window sets a minimum
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
    val scale = fitScale(
        window.width.toFloat(), window.height.toFloat(), outer.density,
        designWidth.value, designHeight.value, minScale, UiScale.factor
    )
    // One Density per scale, so unchanged sizes don't hand the content a new one.
    val scaled = remember(outer, scale) { Density(outer.density * scale, outer.fontScale) }
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalDensity provides scaled) {
            content()
        }
    }
}
