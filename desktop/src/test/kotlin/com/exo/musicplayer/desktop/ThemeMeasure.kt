package com.exo.musicplayer.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.exo.musicplayer.desktop.ui.AccentChoice
import com.exo.musicplayer.desktop.ui.Palette
import com.exo.musicplayer.desktop.ui.ThemeColors
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.hypot
import kotlin.math.pow

/**
 * The measures the theme rules are written in: WCAG contrast, CIELAB L* for
 * how far apart two surfaces look, and OKLCH chroma and hue for how colourful
 * something is. Shared by the balance test and the contact sheet's report.
 */
internal object ThemeMeasure {

    fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** CIELAB lightness, 0 (black) to 100 (white). */
    fun lstar(c: Color): Double {
        val y = c.luminance().toDouble()
        val f = if (y > 216.0 / 24389.0) cbrt(y) else (24389.0 / 27.0 * y + 16.0) / 116.0
        return 116.0 * f - 16.0
    }

    private fun linear(v: Float): Double = if (v <= 0.04045f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)

    /** OKLCH: lightness 0-1, chroma (0 is grey), hue in degrees. */
    fun oklch(c: Color): Triple<Double, Double, Double> {
        val r = linear(c.red)
        val g = linear(c.green)
        val b = linear(c.blue)
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        val okL = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s
        val okA = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s
        val okB = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
        val hue = (Math.toDegrees(atan2(okB, okA)) + 360.0) % 360.0
        return Triple(okL, hypot(okA, okB), hue)
    }

    fun chroma(c: Color): Double = oklch(c).second

    fun hue(c: Color): Double = oklch(c).third

    /** The shorter way round the hue circle between two hues. */
    fun hueGap(a: Double, b: Double): Double {
        val d = kotlin.math.abs(a - b) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }

    fun ThemeColors.surfaces(): List<Color> = listOf(base, sidebar, content, raised, hover, line)

    /** The text on a main button, as the app draws it. */
    fun onButton(choice: AccentChoice): Color {
        Palette.setCoverAccent(null)
        Palette.use(choice)
        return Palette.OnButton
    }

    fun hex(c: Color): String = "#" + ThemeColors.hex(c).drop(2)

    /** One line of numbers for [choice], for the report. */
    fun report(choice: AccentChoice): String {
        val t = choice.colors
        fun f(v: Double) = "%.2f".format(v)
        return listOf(
            "%-14s".format(choice.name),
            "text/base=" + f(contrast(t.text, t.base)),
            "text/content=" + f(contrast(t.text, t.content)),
            "dim=" + f(contrast(t.textDim, t.content)),
            "faint=" + f(contrast(t.textFaint, t.content)),
            "accent=" + f(contrast(t.accent, t.content)),
            "onAccent=" + f(contrast(t.onAccent, t.accent)),
            "onButton=" + f(contrast(onButton(choice), t.button)),
            "line=" + f(contrast(t.line, t.content)),
            "dL*raised=" + f(kotlin.math.abs(lstar(t.raised) - lstar(t.content))),
            "dL*hover=" + f(kotlin.math.abs(lstar(t.hover) - lstar(t.raised))),
            "accentC=" + "%.3f".format(chroma(t.accent)),
            "surfaceC=" + "%.3f".format(t.surfaces().maxOf { chroma(it) }),
            "text/selected=" + f(contrast(t.text, t.selected))
        ).joinToString("  ")
    }
}
