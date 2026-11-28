package com.exo.musicplayer.desktop.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Density
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Canvas as GraphicsCanvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Liquid glass: the window as panes of frosted glass floating over a soft
 * field of the theme's colours, after the look Apple calls Liquid Glass.
 *
 * Each pane blurs what is behind it - the colour field, or the wallpaper -
 * and tints it with the theme, so the colours glow through without getting
 * in the way of the text. Its edge catches the light at the top left, a sheen
 * runs along its top, and a soft shadow lifts it off the background. Inside a
 * pane, panels, buttons and fields are thinner glass: see-through, with the
 * same lit edge, and rounder than the solid look.
 *
 * The blur is Haze's (github.com/chrisbanes/haze), which does on Skia what
 * the platform does for Apple's.
 */
object Glass {
    /** Whether the window is drawn as glass; set from the setting. */
    var on by mutableStateOf(false)

    /**
     * How see-through the panes are, 0 to 1; set from the setting. Half is the
     * look glass has always had. Below it, the pane's own colour covers more of
     * what is behind, until it is all but solid; above it, there is less of that
     * colour and less frost, until the wallpaper shows nearly as it is.
     */
    var seeThrough by mutableFloatStateOf(SEE_THROUGH_DEFAULT)

    /** Whether the glow behind the glass drifts through several hues; off, it stays close to the theme's colour. Set from the setting. */
    var colourful by mutableStateOf(false)

    const val SEE_THROUGH_DEFAULT = 0.5f

    /** A pane's tint at [seeThrough], for one designed as [alpha] at the default. */
    fun tint(alpha: Float, seeThrough: Float = this.seeThrough): Float {
        val at = seeThrough.coerceIn(0f, 1f)
        return if (at <= SEE_THROUGH_DEFAULT) {
            between(SOLID, alpha, at / SEE_THROUGH_DEFAULT)
        } else {
            between(alpha, alpha * CLEAREST, (at - SEE_THROUGH_DEFAULT) / (1f - SEE_THROUGH_DEFAULT))
        }
    }

    /** The frost at [seeThrough]: as designed up to the default, thinning as the glass clears. */
    fun blur(seeThrough: Float = this.seeThrough): Dp {
        val at = seeThrough.coerceIn(0f, 1f)
        if (at <= SEE_THROUGH_DEFAULT) return BLUR
        return between(BLUR.value, CLEAR_BLUR.value, (at - SEE_THROUGH_DEFAULT) / (1f - SEE_THROUGH_DEFAULT)).dp
    }

    /** As solid as a pane gets: the same as glass with nothing behind it to blur. */
    private const val SOLID = 0.92f

    /** The share of its tint the clearest pane keeps: a breath of the theme. */
    private const val CLEAREST = 0.12f

    private val BLUR = 36.dp
    private val CLEAR_BLUR = 12.dp

    private fun between(from: Float, to: Float, fraction: Float): Float = from + (to - from) * fraction

    /** Corners of the big panes. */
    val PANE = 20.dp

    /** The gap between panes, and round the edge of the window. */
    val GAP = 10.dp
}

/** What the panes blur: the colour field or wallpaper behind everything. Null when not glass. */
val LocalGlass = staticCompositionLocalOf<HazeState?> { null }

/** Light catching the edge: brightest at the top left, fading, a little back at the bottom right. */
fun glassEdge(strength: Float = 1f): Brush {
    // On a light theme a white edge vanishes, so the edge darkens instead.
    val light = Palette.Content.luminance() > 0.5f
    val ink = if (light) Color.Black else Color.White
    val scale = if (light) 0.45f * strength else strength
    return Brush.linearGradient(
        0f to ink.copy(alpha = 0.34f * scale),
        0.45f to ink.copy(alpha = 0.07f * scale),
        1f to ink.copy(alpha = 0.16f * scale),
        start = Offset.Zero,
        end = Offset.Infinite
    )
}

/**
 * A pane of glass: what is behind it blurred and tinted with [tint], an edge
 * that catches the light, a sheen along the top and a soft shadow. Without a
 * [state] to blur, it is the tint alone, nearly solid.
 */
fun Modifier.glassPane(
    state: HazeState?,
    shape: Shape,
    tint: Color,
    tintAlpha: Float = 0.5f,
    shadow: Dp = 0.dp
): Modifier = this
    .then(if (shadow > 0.dp) Modifier.shadow(shadow, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.45f), spotColor = Color.Black.copy(alpha = 0.45f)) else Modifier)
    .clip(shape)
    .then(
        if (state != null) {
            Modifier.hazeEffect(
                state,
                HazeStyle(
                    backgroundColor = Palette.Base,
                    // Read here, so a pane follows the see-through slider as it moves.
                    tint = HazeTint(tint.copy(alpha = Glass.tint(tintAlpha))),
                    blurRadius = Glass.blur(),
                    noiseFactor = 0.03f
                )
            )
        } else {
            Modifier.background(tint.copy(alpha = 0.92f))
        }
    )
    .drawBehind {
        // The sheen: a little light along the top of the pane.
        drawRect(
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.07f),
                0.28f to Color.Transparent,
                endY = size.height
            )
        )
    }
    .border(1.dp, glassEdge(), shape)

/**
 * Thinner glass, for what sits on a pane: a panel, a button, a field. It
 * shows the pane through it, with the same lit edge.
 */
fun Modifier.glassChip(shape: Shape, fill: Float = 0.06f, edge: Float = 0.8f): Modifier {
    // White frost reads on both: faint over a dark pane, stronger over a light one.
    val light = Palette.Content.luminance() > 0.5f
    return this
        .clip(shape)
        .background(Color.White.copy(alpha = if (light) (fill * 4f).coerceAtMost(0.5f) else fill))
        .border(1.dp, glassEdge(edge), shape)
}

/**
 * A card or a row on a page: thinner glass when glass is on, rounder; else
 * the solid raised surface with its line. [edge] other than the line colour
 * (a row being previewed, say) is kept in both.
 */
fun Modifier.card(corner: Dp, hovered: Boolean = false, edge: Color = Palette.Line): Modifier =
    if (Glass.on) {
        val shape = RoundedCornerShape(corner + 6.dp)
        val lit = this.glassChip(shape, fill = if (hovered) 0.11f else 0.05f)
        if (edge != Palette.Line) lit.border(1.dp, edge, shape) else lit
    } else {
        val shape = RoundedCornerShape(corner)
        this.clip(shape).background(if (hovered) Palette.Hover else Palette.Raised).border(1.dp, edge, shape)
    }

/**
 * Something floating over the page - a note, a dialog: nearly solid, so what
 * is under it doesn't show through the words, with glass's lit edge and a
 * deeper shadow when glass is on.
 */
fun Modifier.floating(corner: Dp): Modifier =
    if (Glass.on) {
        val shape = RoundedCornerShape(corner + 8.dp)
        this.shadow(24.dp, shape).clip(shape).background(Palette.Raised.copy(alpha = 0.9f)).border(1.dp, glassEdge(1.2f), shape)
    } else {
        val shape = RoundedCornerShape(corner)
        this.clip(shape).background(Palette.Raised).border(1.dp, Palette.Line, shape)
    }

/** What the glass floats over: the wallpaper when there is one, else a slowly drifting field of the theme's colours. */
@Composable
fun GlassBackground(wallpaper: ImageBitmap?, dim: Float, modifier: Modifier = Modifier) {
    Box(modifier.background(Palette.Base)) {
        if (wallpaper != null) {
            // Dimmed less than behind solid panes: the glass does its own dimming.
            Wallpaper(wallpaper, dim * 0.6f, Modifier.matchParentSize())
        } else {
            ColourField(Modifier.matchParentSize())
        }
    }
}

/**
 * Four soft lights in the theme's colours, drifting a full slow circle about
 * once a minute. Redrawn fifteen times a second, and not at all while the
 * window isn't in front: every redraw is a blur for every pane.
 */
@Composable
private fun ColourField(modifier: Modifier) {
    val focused = LocalWindowInfo.current.isWindowFocused
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(focused) {
        while (focused) {
            delay(66)
            phase = (phase + 0.0011f) % 1f
        }
    }
    val light = Palette.Base.luminance() > 0.5f
    val colourful = Glass.colourful
    val lights = remember(Palette.Accent, Palette.Base, light, colourful) {
        glassLights(Palette.Accent, Palette.Base, light, colourful)
    }
    val base = Palette.Base
    val cache = remember { GlowCache() }
    Canvas(modifier) { cache.draw(this, base, lights, phase, light) }
}

/**
 * The glow drawn small and stretched to the window. It is soft all over, so a
 * quarter of the pixels show it just as well, and it only changes fifteen times
 * a second while the window repaints sixty: at screen size, four big gradients
 * every repaint cost more than a frame on a machine that draws on the CPU
 * (about 150 ms at 2560 x 1440 against under 10 for the stretch).
 */
internal class GlowCache {
    private var small: ImageBitmap? = null
    private var key: List<Any>? = null

    fun draw(scope: DrawScope, base: Color, lights: List<Color>, phase: Float, light: Boolean) = with(scope) {
        val w = size.width.toInt().coerceAtLeast(1)
        val h = size.height.toInt().coerceAtLeast(1)
        val shrink = max(1f, max(w, h) / GLOW_LONG_SIDE)
        val sw = (w / shrink).toInt().coerceAtLeast(1)
        val sh = (h / shrink).toInt().coerceAtLeast(1)
        val now = listOf(sw, sh, base, lights, phase, light)
        if (small == null || key != now) {
            val image = small?.takeIf { it.width == sw && it.height == sh } ?: ImageBitmap(sw, sh)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, GraphicsCanvas(image), Size(sw.toFloat(), sh.toFloat())) {
                drawGlassGlow(base, lights, phase, light)
            }
            small = image
            key = now
        }
        drawImage(small!!, dstSize = IntSize(w, h), filterQuality = FilterQuality.Low)
    }
}

/** The longest side of the small glow, in pixels. */
private const val GLOW_LONG_SIDE = 640f

/**
 * The glow itself: [lights] (as [glassLights] returns them) drifting about
 * [base] at [phase], 0 to 1 round the circle. Apart from the composable so it
 * can be drawn, and looked at, on its own.
 */
internal fun DrawScope.drawGlassGlow(base: Color, lights: List<Color>, phase: Float, light: Boolean) {
    drawRect(base)
    val w = size.width
    val h = size.height
    val reach = max(w, h)
    val t = phase * 2 * PI
    // Brighter lights on a dark theme, where the panes darken what comes through.
    val lift = if (light) 1f else 1.35f
    fun light(colour: Color, x: Double, y: Double, radius: Float, strength: Float) {
        val centre = Offset((w * x).toFloat(), (h * y).toFloat())
        drawCircle(
            Brush.radialGradient(
                0f to colour.copy(alpha = strength),
                0.45f to colour.copy(alpha = strength * 0.45f),
                1f to Color.Transparent,
                center = centre,
                radius = radius
            ),
            radius = radius,
            center = centre
        )
    }
    light(lights[0], 0.18 + 0.12 * sin(t), 0.22 + 0.10 * cos(t * 2), reach * 0.55f, (0.55f * lift).coerceAtMost(0.85f))
    light(lights[1], 0.86 + 0.08 * cos(t), 0.30 + 0.14 * sin(t * 2 + 1), reach * 0.50f, (0.45f * lift).coerceAtMost(0.85f))
    light(lights[2], 0.55 + 0.16 * sin(t + 2), 0.92 + 0.07 * cos(t * 3), reach * 0.60f, (0.50f * lift).coerceAtMost(0.85f))
    light(lights[3], 0.04 + 0.06 * cos(t * 2 + 2), 0.96 + 0.05 * sin(t), reach * 0.40f, (0.35f * lift).coerceAtMost(0.85f))
}

/**
 * The four colours of the glow: the accent's light, a neighbour of it, the
 * theme's own surface lit from within, and a second neighbour.
 *
 * Calm (the default): every light is the accent's hue, give or take 22 degrees,
 * at about the accent's own strength of colour, capped, so the glow reads as one
 * colour mood, the theme's. The themes are built as one accent over quiet
 * surfaces; four unrelated hues behind them undid that. Dark themes get a
 * brighter, light themes a paler glow, as the panes darken or lighten what
 * comes through. [colourful] spreads the hues right round instead, with the
 * colour held below neon.
 *
 * Worked in OKLCH, where a hue turn doesn't change how light a colour looks, so
 * the lights stay as bright as each other whatever the accent.
 */
internal fun glassLights(accent: Color, base: Color, light: Boolean, colourful: Boolean): List<Color> {
    val (_, accentChroma, hue) = toOklch(accent)
    val baseLightness = toOklch(base).first
    // The accent's own colour, held down; a grey accent keeps a grey glow.
    val cap = when {
        colourful -> if (light) 0.13 else 0.17
        else -> if (light) 0.10 else 0.14
    }
    val chroma = (accentChroma * 0.95).coerceIn(min(accentChroma, 0.04), cap)
    // On a light theme the glow sits just under the page's own lightness, so the dark words keep their contrast.
    val bright = if (light) (baseLightness - 0.08).coerceIn(0.8, 0.9) else 0.53
    val soft = if (light) (bright + 0.03).coerceAtMost(0.93) else 0.46
    if (colourful) {
        return listOf(
            fromOklch(bright, chroma, hue),
            fromOklch(bright, chroma, hue + 55),
            fromOklch(soft, chroma, hue + 130),
            fromOklch(soft, chroma, hue - 60)
        )
    }
    // The surface light: the theme's own base, raised a little and tinted with the accent.
    val surface = if (light) (baseLightness - 0.05).coerceIn(0.8, 0.95) else (baseLightness + 0.24).coerceIn(0.32, 0.5)
    return listOf(
        fromOklch(bright, chroma, hue),
        fromOklch(bright - 0.04, chroma * 0.8, hue + 22),
        fromOklch(surface, chroma * 0.55, hue),
        fromOklch(soft, chroma * 0.8, hue - 22)
    )
}

private fun linear(v: Float): Double = if (v <= 0.04045f) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)

private fun gamma(v: Double): Float {
    val c = v.coerceIn(0.0, 1.0)
    return (if (c <= 0.0031308) c * 12.92 else 1.055 * Math.pow(c, 1 / 2.4) - 0.055).toFloat()
}

/** Lightness, chroma and hue in degrees (Ottosson's OKLCH). */
internal fun toOklch(c: Color): Triple<Double, Double, Double> {
    val r = linear(c.red)
    val g = linear(c.green)
    val b = linear(c.blue)
    val l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
    val m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
    val s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
    val lightness = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s
    val a = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s
    val bb = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
    return Triple(lightness, Math.hypot(a, bb), (Math.toDegrees(Math.atan2(bb, a)) + 360.0) % 360.0)
}

/** The sRGB colour at that OKLCH; one outside the screen's range loses colour, not lightness or hue, until it fits. */
internal fun fromOklch(lightness: Double, chroma: Double, hue: Double): Color {
    fun at(c: Double): DoubleArray {
        val a = c * Math.cos(Math.toRadians(hue))
        val b = c * Math.sin(Math.toRadians(hue))
        val l = Math.pow(lightness + 0.3963377774 * a + 0.2158037573 * b, 3.0)
        val m = Math.pow(lightness - 0.1055613458 * a - 0.0638541728 * b, 3.0)
        val s = Math.pow(lightness - 0.0894841775 * a - 1.2914855480 * b, 3.0)
        return doubleArrayOf(
            4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
            -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
            -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s
        )
    }
    var c = chroma
    var rgb = at(c)
    var tries = 0
    while (rgb.any { it < -0.0005 || it > 1.0005 } && tries++ < 40) {
        c *= 0.93
        rgb = at(c)
    }
    return Color(gamma(rgb[0]), gamma(rgb[1]), gamma(rgb[2]))
}
