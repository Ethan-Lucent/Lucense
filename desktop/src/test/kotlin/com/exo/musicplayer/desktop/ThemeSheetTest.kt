package com.exo.musicplayer.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.exo.musicplayer.desktop.ui.AccentChoice
import com.exo.musicplayer.desktop.ui.LucenseDesktopTheme
import com.exo.musicplayer.desktop.ui.Palette
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Not a pixel test: it draws a small mock of the window in every theme and
 * lays them out on one contact sheet, dark variants above their light ones,
 * so a person can look at the balance. /tmp/themes-sheet.png and the numbers
 * in /tmp/themes-report.txt.
 */
@OptIn(ExperimentalTestApi::class)
class ThemeSheetTest {

    @Composable
    private fun Mock() {
        Row(Modifier.size(MOCK_W.dp, MOCK_H.dp).background(Palette.Base).padding(5.dp)) {
            // The sidebar: a few entries, the open one marked with the accent.
            Column(
                Modifier.width(54.dp).fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(Palette.Sidebar).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                repeat(5) { i ->
                    Box(Modifier.size(width = 38.dp, height = 8.dp).clip(RoundedCornerShape(3.dp)).background(if (i == 1) Palette.Accent else Palette.TextFaint))
                }
            }
            Spacer(Modifier.width(5.dp))
            Column(Modifier.fillMaxHeight().weight(1f).clip(RoundedCornerShape(6.dp)).background(Palette.Content)) {
                Column(Modifier.weight(1f).padding(8.dp)) {
                    Row {
                        // The list: a plain row, the selected one, the hovered one.
                        Column(Modifier.weight(1f)) {
                            listOf("Plain row" to null, "Selected" to Palette.Selected, "Hovered" to Palette.Hover).forEach { (name, fill) ->
                                Column(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                                        .then(if (fill != null) Modifier.background(fill) else Modifier)
                                        .padding(horizontal = 6.dp, vertical = 3.dp)
                                ) {
                                    Text(name, style = MaterialTheme.typography.labelMedium, color = Palette.Text)
                                    Text("Artist", style = MaterialTheme.typography.labelSmall, color = Palette.TextDim)
                                }
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        // A raised panel: heading, dim and faint text, a line, a button and a slider.
                        Column(Modifier.width(150.dp).clip(RoundedCornerShape(6.dp)).background(Palette.Raised).padding(8.dp)) {
                            Text("Heading", style = MaterialTheme.typography.titleMedium, color = Palette.Text)
                            Text("Dim text", style = MaterialTheme.typography.labelMedium, color = Palette.TextDim)
                            Text("Faint text", style = MaterialTheme.typography.labelSmall, color = Palette.TextFaint)
                            Spacer(Modifier.height(5.dp))
                            Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Line))
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Box(Modifier.clip(RoundedCornerShape(5.dp)).background(Palette.Accent).padding(horizontal = 9.dp, vertical = 3.dp)) {
                                    Text("Play", style = MaterialTheme.typography.labelMedium, color = Palette.OnAccent)
                                }
                                Spacer(Modifier.width(5.dp))
                                Box(Modifier.clip(RoundedCornerShape(5.dp)).background(Palette.Button).padding(horizontal = 7.dp, vertical = 3.dp)) {
                                    Text("Button", style = MaterialTheme.typography.labelMedium, color = Palette.OnButton)
                                }
                            }
                            Slider(
                                0.6f, {}, Modifier.height(22.dp),
                                colors = SliderDefaults.colors(
                                    thumbColor = Palette.Accent, activeTrackColor = Palette.Accent, inactiveTrackColor = Palette.Line
                                )
                            )
                        }
                    }
                }
                // The play bar.
                Row(
                    Modifier.fillMaxWidth().height(30.dp).background(Palette.Raised).padding(horizontal = 8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Box(Modifier.size(16.dp).clip(CircleShape).background(Palette.Accent))
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Palette.Line)) {
                        Box(Modifier.fillMaxWidth(0.4f).fillMaxHeight().background(Palette.Accent))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("1:24", style = MaterialTheme.typography.labelSmall, color = Palette.TextDim)
                }
            }
        }
    }

    @Test
    fun contactSheet() = runDesktopComposeUiTest(MOCK_W, MOCK_H) {
        setContent { LucenseDesktopTheme { Mock() } }
        val shots = mutableMapOf<AccentChoice, BufferedImage>()
        val report = StringBuilder()
        AccentChoice.entries.forEach { choice ->
            Palette.setCoverAccent(null)
            Palette.use(choice)
            waitForIdle()
            shots[choice] = onRoot().captureToImage().toAwtImage()
            report.appendLine(ThemeMeasure.report(choice))
        }
        Palette.use(AccentChoice.DEFAULT)

        // Columns are families; the dark variant above, the light one below.
        val families = AccentChoice.families
        val perRow = 5
        val cell = shots.values.first()
        val caption = 18
        val gap = 8
        val bands = (families.size + perRow - 1) / perRow
        val width = perRow * (cell.width + gap) + gap
        val height = bands * (2 * (cell.height + caption) + 3 * gap) + gap
        val sheet = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = sheet.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = java.awt.Color(0x80, 0x80, 0x80)
        g.fillRect(0, 0, width, height)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 13)
        families.forEachIndexed { i, family ->
            val x = gap + (i % perRow) * (cell.width + gap)
            val top = gap + (i / perRow) * (2 * (cell.height + caption) + 3 * gap)
            listOf(family.dark, family.light).distinct().forEachIndexed { row, choice ->
                val y = top + row * (cell.height + caption + gap)
                g.color = java.awt.Color.WHITE
                g.drawString("${family.name} - ${choice.label}${if (choice.light) " (light)" else ""}", x + 2, y + 13)
                g.drawImage(shots.getValue(choice), x, y + caption, null)
            }
        }
        g.dispose()
        val out = File("/tmp/themes-sheet.png")
        ImageIO.write(sheet, "png", out)
        File("/tmp/themes-report.txt").writeText(report.toString())
        assertTrue(out.length() > 0)
    }

    private companion object {
        const val MOCK_W = 400
        const val MOCK_H = 190
    }
}
