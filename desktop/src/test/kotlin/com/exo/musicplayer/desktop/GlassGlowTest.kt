package com.exo.musicplayer.desktop

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.exo.musicplayer.desktop.data.DesktopSettings
import com.exo.musicplayer.desktop.ThemeMeasure.hueGap
import com.exo.musicplayer.desktop.ui.AccentChoice
import com.exo.musicplayer.desktop.ui.Glass
import com.exo.musicplayer.desktop.ui.Palette
import com.exo.musicplayer.desktop.ui.drawGlassGlow
import com.exo.musicplayer.desktop.ui.glassLights
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The glow behind liquid glass: one calm colour by default, a colourful one when asked for, and the words over it still readable. */
class GlassGlowTest {

    private val sheetThemes = listOf(AccentChoice.BLACK, AccentChoice.MOCHA, AccentChoice.SLATE_LIGHT, AccentChoice.SUNSET)

    /** The glow as it was before there was a choice: the accent made richer, a hue 42 degrees on, the soft accent, and one 55 back. */
    private fun legacyLights(accent: Color, soft: Color, light: Boolean): List<Color> {
        fun hsb(c: Color) = java.awt.Color.RGBtoHSB((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt(), null)
        fun richer(c: Color): Color {
            val h = hsb(c)
            val s = (h[1] * 1.6f).coerceIn(0.45f, 0.9f)
            val b = if (light) h[2].coerceIn(0.75f, 1f) else h[2].coerceIn(0.6f, 0.95f)
            return Color(java.awt.Color.HSBtoRGB(h[0], s, b))
        }
        fun shift(c: Color, deg: Float): Color {
            val h = hsb(c)
            return Color(java.awt.Color.HSBtoRGB(((h[0] * 360f + deg) % 360f + 360f) % 360f / 360f, h[1], h[2]))
        }
        val a = richer(accent)
        return listOf(a, shift(a, 42f), richer(soft), shift(a, -55f))
    }

    private fun draw(width: Int, height: Int, block: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit): ImageBitmap {
        val image = ImageBitmap(width, height)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(width.toFloat(), height.toFloat()), block)
        return image
    }

    private fun pixels(image: ImageBitmap): IntArray {
        val out = IntArray(image.width * image.height)
        image.readPixels(out, 0, 0, image.width, image.height)
        return out
    }

    /** Rows of themes, a column per moment of the drift; each tile has a pane (the theme's content colour at its real tint) over the glow. */
    private fun sheet(path: String, lights: (AccentChoice) -> List<Color>) {
        val tileW = 480
        val tileH = 300
        val phases = listOf(0.05f, 0.55f)
        val sheet = draw(tileW * phases.size, tileH * sheetThemes.size) {
            sheetThemes.forEachIndexed { row, choice ->
                Palette.use(choice)
                val light = Palette.Base.luminance() > 0.5f
                val colours = lights(choice)
                phases.forEachIndexed { col, phase ->
                    val tile = draw(tileW, tileH) { drawGlassGlow(Palette.Base, colours, phase, light) }
                    drawImage(tile, Offset((col * tileW).toFloat(), (row * tileH).toFloat()))
                    // A pane over the middle of it, and a "line of text" in the theme's ink.
                    val left = col * tileW + 150f
                    val top = row * tileH + 70f
                    drawRoundRect(Palette.Content.copy(alpha = 0.34f), Offset(left, top), Size(270f, 160f), CornerRadius(20f))
                    drawRoundRect(Palette.Text, Offset(left + 20f, top + 30f), Size(150f, 10f), CornerRadius(5f))
                    drawRoundRect(Palette.TextDim, Offset(left + 20f, top + 56f), Size(210f, 8f), CornerRadius(4f))
                    drawRoundRect(Palette.Accent, Offset(left + 20f, top + 100f), Size(60f, 22f), CornerRadius(11f))
                }
            }
        }
        File(path).writeBytes(Image.makeFromBitmap(sheet.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        Palette.use(AccentChoice.DEFAULT)
    }

    @Test
    fun writesTheContactSheets() {
        sheet("/tmp/glass-before.png") { legacyLights(Palette.Accent, Palette.AccentSoft, Palette.Base.luminance() > 0.5f) }
        sheet("/tmp/glass-after.png") { glassLights(Palette.Accent, Palette.Base, Palette.Base.luminance() > 0.5f, colourful = false) }
        sheet("/tmp/glass-after-colourful.png") { glassLights(Palette.Accent, Palette.Base, Palette.Base.luminance() > 0.5f, colourful = true) }
        assertTrue(File("/tmp/glass-after.png").length() > 1000)
    }

    private fun spread(lights: List<Color>): Double {
        val hues = lights.filter { ThemeMeasure.chroma(it) > 0.02 }.map { ThemeMeasure.hue(it) }
        var widest = 0.0
        for (a in hues) for (b in hues) widest = maxOf(widest, hueGap(a, b))
        return widest
    }

    @Test
    fun calmLightsStayInOneColourMood() {
        for (choice in AccentChoice.entries) {
            val t = choice.colors
            val light = t.base.luminance() > 0.5f
            val calm = glassLights(t.accent, t.base, light, colourful = false)
            assertEquals(4, calm.size)
            assertTrue(spread(calm) <= 55.0, "${choice.name}: calm hue spread ${spread(calm)}")
            assertTrue(calm.all { ThemeMeasure.chroma(it) <= 0.145 }, "${choice.name}: calm chroma ${calm.map { ThemeMeasure.chroma(it) }}")
            assertEquals(calm, glassLights(t.accent, t.base, light, colourful = false), "deterministic")
        }
    }

    @Test
    fun colourfulLightsSpreadRoundTheWheelWithoutNeon() {
        for (choice in AccentChoice.entries) {
            val t = choice.colors
            if (ThemeMeasure.chroma(t.accent) < 0.05) continue
            val light = t.base.luminance() > 0.5f
            val colourful = glassLights(t.accent, t.base, light, colourful = true)
            assertTrue(spread(colourful) > 90.0, "${choice.name}: colourful hue spread ${spread(colourful)}")
            assertTrue(colourful.all { ThemeMeasure.chroma(it) <= 0.17 }, "${choice.name}: chroma ${colourful.map { ThemeMeasure.chroma(it) }}")
            assertEquals(colourful, glassLights(t.accent, t.base, light, colourful = true), "deterministic")
        }
    }

    /**
     * The brightest (or, on a light theme, darkest) place the glow gets to once
     * a pane blurs it - a 90 px cell's average - under the content pane's tint:
     * the words must still read there.
     */
    private fun worstContrast(choice: AccentChoice, colourful: Boolean, legacy: Boolean = false, ink: (AccentChoice) -> Color): Double {
        Palette.use(choice)
        val light = Palette.Base.luminance() > 0.5f
        val lights = if (legacy) legacyLights(Palette.Accent, Palette.AccentSoft, light) else glassLights(Palette.Accent, Palette.Base, light, colourful)
        var worst = 99.0
        for (phase in listOf(0f, 0.2f, 0.4f, 0.6f, 0.8f)) {
            val image = draw(900, 540) { drawGlassGlow(Palette.Base, lights, phase, light) }
            val px = pixels(image)
            for (cy in 0 until 540 / 90) for (cx in 0 until 900 / 90) {
                var r = 0.0
                var g = 0.0
                var b = 0.0
                for (y in 0 until 90) for (x in 0 until 90) {
                    val p = px[(cy * 90 + y) * 900 + cx * 90 + x]
                    r += (p shr 16) and 255
                    g += (p shr 8) and 255
                    b += p and 255
                }
                val n = 90.0 * 90.0
                val cell = Color((r / n / 255).toFloat(), (g / n / 255).toFloat(), (b / n / 255).toFloat())
                val under = Palette.Content.copy(alpha = 0.34f).compositeOver(cell)
                worst = minOf(worst, ThemeMeasure.contrast(ink(choice), under))
            }
        }
        Palette.use(AccentChoice.DEFAULT)
        return worst
    }

    @Test
    fun wordsStayReadableOverTheCalmGlow() {
        val report = StringBuilder()
        File("/tmp/glass-contrast.txt").writeText("")
        for (choice in AccentChoice.entries) {
            val text = worstContrast(choice, false) { Palette.Text }
            val dim = worstContrast(choice, false) { Palette.TextDim }
            val colourful = worstContrast(choice, true) { Palette.Text }
            val oldText = worstContrast(choice, false, legacy = true) { Palette.Text }
            val oldDim = worstContrast(choice, false, legacy = true) { Palette.TextDim }
            report.append("%-14s text %.1f dim %.1f  (colourful text %.1f; the old glow: text %.1f dim %.1f)\n".format(choice.name, text, dim, colourful, oldText, oldDim))
            assertTrue(text >= oldText - 0.01 && dim >= oldDim - 0.01, "${choice.name}: the calm glow is harder to read over than the old one")
            val plain = ThemeMeasure.contrast(choice.colors.textDim, choice.colors.content)
            report.append("               (dim on the plain page: %.1f)\n".format(plain))
            File("/tmp/glass-contrast.txt").writeText(report.toString())
            assertTrue(text >= 4.5, "${choice.name}: text over the calm glow $text")
            // The dim tone may lose a little to the glow, never most of what it has.
            assertTrue(dim >= minOf(3.5, plain * 0.7), "${choice.name}: dim text over the calm glow $dim")
        }
    }

    @Test
    fun theChoiceIsKeptAndCalmIsTheDefault() {
        val root = Preferences.userRoot().node("lucense-glass-glow-test-${System.nanoTime()}")
        try {
            assertFalse(DesktopSettings(root).glassRainbow)
            DesktopSettings(root).glassRainbow = true
            assertTrue(DesktopSettings(root).glassRainbow)
        } finally {
            root.removeNode()
        }
        assertFalse(Glass.colourful)
    }
}
