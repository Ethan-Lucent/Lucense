package com.exo.musicplayer.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.exo.musicplayer.data.discover.DiscoverTab
import com.exo.musicplayer.data.download.DownloadFormat
import com.exo.musicplayer.data.download.DownloadQuality
import com.exo.musicplayer.data.lyrics.LyricsTerm
import com.exo.musicplayer.desktop.data.GroupSort
import com.exo.musicplayer.desktop.data.IdentifyMode
import com.exo.musicplayer.desktop.data.ProxyMode
import com.exo.musicplayer.desktop.data.SortMode
import com.exo.musicplayer.desktop.system.DuckKey
import com.exo.musicplayer.desktop.ui.BackdropStyle
import com.exo.musicplayer.desktop.ui.Glass
import com.exo.musicplayer.desktop.ui.LucenseDesktopTheme
import com.exo.musicplayer.desktop.ui.SegmentedRow
import com.exo.musicplayer.desktop.ui.SettingsTab
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.exo.musicplayer.desktop.ui.SEGMENT_ROW_HEIGHT
import com.exo.musicplayer.desktop.ui.SEGMENT_WRAP_LIMIT
import com.exo.musicplayer.desktop.ui.SegmentFit
import com.exo.musicplayer.desktop.ui.segmentCorner
import com.exo.musicplayer.desktop.ui.segmentFit
import com.exo.musicplayer.desktop.ui.segmentRows
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

/** One option list as a call site passes it: the labels, in order, and which is chosen. */
internal data class SegCase(val name: String, val labels: List<String>, val chosen: Int)

internal val SEG_CASES: List<SegCase> = listOf(
    SegCase("settings tabs", SettingsTab.entries.map { it.label }, 3),
    SegCase("background style", BackdropStyle.entries.map { it.label }, 2),
    SegCase("duck key", DuckKey.entries.map { it.label }, 0),
    SegCase("output rate", listOf("Automatic", "44.1 kHz", "48 kHz", "96 kHz"), 0),
    SegCase("crossfade", listOf(0, 2, 4, 6, 8, 12).map { if (it == 0) "Off" else "$it seconds" }, 0),
    SegCase("lyrics term", LyricsTerm.entries.map { it.label }, 1),
    SegCase("cover service", listOf("Automatic", "Apple Music", "Deezer", "MusicBrainz", "Last.fm"), 0),
    SegCase("proxy", ProxyMode.entries.map { it.label }, 5),
    SegCase("download format", listOf("As sent") + DownloadFormat.entries.map { it.label }, 0),
    SegCase("download quality", DownloadQuality.entries.map { it.label }, 0),
    SegCase(
        "youtube sign-in",
        com.exo.musicplayer.desktop.download.YouTubeSignIn.Source.entries.filter { it.name != "OPERA_GX" }.map { it.label },
        1
    ),
    SegCase("sort", SortMode.entries.map { it.label }, 6),
    SegCase("group sort", GroupSort.entries.map { it.label }, 0),
    SegCase("discover", DiscoverTab.entries.map { it.label }, 0),
    SegCase("identify", IdentifyMode.entries.map { it.label }, 3)
)

internal val SEG_WIDTHS = listOf(260, 360, 480, 700)

@OptIn(ExperimentalTestApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
class SegmentedLayoutTest {

    /**
     * For looking at: every list at every width, to /tmp/seg-<tag>-<w>[-b][-glass][-x13].png, the tag
     * being the text of /tmp/seg-tag.txt ("before" / "after"). Two sheets per width so all fit the window.
     */
    @Test
    fun snapshots() = runComposeUiTest {
        val tag = File("/tmp/seg-tag.txt").takeIf { it.exists() }?.readText()?.trim() ?: "after"
        var glass by mutableStateOf(false)
        var width by mutableStateOf(260)
        var scale by mutableStateOf(1f)
        var sheet by mutableStateOf(0)
        setContent {
            Glass.on = glass
            CompositionLocalProvider(LocalDensity provides Density(scale)) {
                LucenseDesktopTheme {
                    Box(
                        Modifier.fillMaxSize().background(
                            if (glass) Brush.linearGradient(listOf(Color(0xFF3A2A6A), Color(0xFF1F5F7A)))
                            else Brush.linearGradient(listOf(Color(0xFF17171B), Color(0xFF17171B)))
                        ).padding(8.dp)
                    ) {
                        Column(Modifier.width(width.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            SEG_CASES.chunked(8)[sheet].forEach { c ->
                                Text(c.name, color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                                SegmentedRow(c.labels, c.labels[c.chosen], { it }, {})
                            }
                        }
                    }
                }
            }
        }
        for ((g, s) in listOf(false to 1f, true to 1f, false to 1.3f)) {
            glass = g; scale = s
            for (w in SEG_WIDTHS) for (part in 0..1) {
                width = w; sheet = part
                waitForIdle()
                val suffix = (if (part == 1) "-b" else "") + (if (g) "-glass" else "") + (if (s != 1f) "-x13" else "")
                val whole = onRoot().captureToImage().toAwtImage()
                val cropW = ((w + 16) * s).toInt().coerceAtMost(whole.width)
                javax.imageio.ImageIO.write(whole.getSubimage(0, 0, cropW, whole.height), "png", File("/tmp/seg-$tag-$w$suffix.png"))
            }
        }
        Glass.on = false
    }

    // ---- the maths ----

    @Test
    fun fitsOnOneRowWhenTheWidthsPlusGapsPlusPaddingFit() {
        val w = listOf(40, 50, 60)
        // 150 + 2 gaps of 2 + 2 pads of 2 = 158
        assertEquals(SegmentFit.ONE_ROW, segmentFit(w, 158, 2, 2, false))
        assertEquals(SegmentFit.WRAP, segmentFit(w, 157, 2, 2, false))
        assertEquals(SegmentFit.SCROLL, segmentFit(w, 157, 2, 2, true))
        assertEquals(SegmentFit.ONE_ROW, segmentFit(w, Int.MAX_VALUE, 2, 2, true))
        assertEquals(SegmentFit.ONE_ROW, segmentFit(emptyList(), 10, 2, 2, true))
    }

    @Test
    fun wrapBreaksGreedilyAndKeepsEveryPill() {
        val rows = segmentRows(listOf(40, 50, 60, 30), inner = 100, gap = 2)
        assertEquals(listOf(0..1, 2..3), rows)
        // A pill wider than a row has a row of its own.
        assertEquals(listOf(0..0, 1..1, 2..2), segmentRows(listOf(300, 300, 50), inner = 100, gap = 2))
        assertEquals(listOf(0..2), segmentRows(listOf(10, 10, 10), inner = 100, gap = 2))
        assertTrue(segmentRows(emptyList(), 100, 2).isEmpty())
    }

    @Test
    fun cornerIsAFixedSizeInDpNotAShareOfTheHeight() {
        assertEquals(SEGMENT_ROW_HEIGHT / 2, segmentCorner(1, true)) // the old capsule, one row
        assertEquals(segmentCorner(2, true), segmentCorner(5, true))
        assertTrue(segmentCorner(3, true) in 12.dp..14.dp)
        assertEquals(7.dp, segmentCorner(1, false))
        assertEquals(7.dp, segmentCorner(4, false))
    }

    // ---- the widget ----

    private class Seen(val container: DpRect, val pills: List<DpRect>, val lines: List<Int>)

    private fun ComposeUiTest.show(c: SegCase, w: Int, scale: Float, glass: Boolean) {
        setContent {
            Glass.on = glass
            CompositionLocalProvider(LocalDensity provides Density(scale)) {
                LucenseDesktopTheme {
                    Box(Modifier.width(w.dp)) { SegmentedRow(c.labels, c.labels[c.chosen], { it }, {}) }
                }
            }
        }
        waitForIdle()
    }

    /** Where a node really is, in dp, however much of it a parent clips: the semantic bounds are cut to what shows. */
    private fun SemanticsNodeInteraction.raw(scale: Float): DpRect {
        val node = fetchSemanticsNode()
        val at = node.positionInRoot
        return DpRect(
            (at.x / scale).dp, (at.y / scale).dp,
            ((at.x + node.size.width) / scale).dp, ((at.y + node.size.height) / scale).dp
        )
    }

    private fun ComposeUiTest.look(c: SegCase, scale: Float): Seen {
        val container = onNodeWithTag("segmented").raw(scale)
        val nodes: List<SemanticsNodeInteraction> = c.labels.map { onAllNodesWithText(it)[0] }
        val lines = nodes.map {
            val out = ArrayList<TextLayoutResult>()
            it.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(out)
            out.single().lineCount
        }
        return Seen(container, nodes.map { it.raw(scale) }, lines)
    }

    private fun rowsOf(seen: Seen): Int {
        val rows = Math.round(seen.container.height / SEGMENT_ROW_HEIGHT)
        // Whole pixels at a fractional scale can be a third of a dp off per row, no more.
        assertEquals(rows * SEGMENT_ROW_HEIGHT.value, seen.container.height.value, 0.5f * rows, "height ${seen.container.height} is not a whole number of rows")
        return rows
    }

    /** All the rules, for one list at one width. */
    private fun check(c: SegCase, w: Int, scale: Float, glass: Boolean) = runComposeUiTest {
        val what = "${c.name} at $w dp, scale $scale, glass $glass"
        show(c, w, scale, glass)
        val seen = look(c, scale)
        val rows = rowsOf(seen)
        // Words never wrap, and every pill is the same height.
        assertTrue(seen.lines.all { it == 1 }, "$what: a label wraps: ${seen.lines}")
        assertEquals(1, seen.pills.map { Math.round(it.height.value) }.distinct().size, "$what: pill heights differ")
        assertEquals(PILL_DP, seen.pills.first().height.value, 0.5f, "$what: pill height")
        // The container is never wider than the pane.
        assertTrue(seen.container.width.value <= w + 0.01f, "$what: container ${seen.container.width} wider than $w")
        // The chosen pill is composed, and in view.
        onAllNodesWithText(c.labels[c.chosen])[0].assertExists()
        val scrolls = seen.pills.last().right > seen.container.right + 0.5.dp
        fun inside(r: DpRect) = r.left >= seen.container.left - 1.dp && r.right <= seen.container.right + 1.dp
        if (!scrolls) {
            assertTrue(seen.pills.all(::inside), "$what: a pill sticks out")
            // Wrapped pills are in reading order: a row is a left-to-right run.
            assertTrue(rows >= 1)
        } else {
            assertEquals(1, rows, "$what: a scrolling row is one row")
            assertTrue(c.labels.size > SEGMENT_WRAP_LIMIT, "$what: short lists wrap rather than scroll")
            assertTrue(inside(seen.pills[c.chosen]), "$what: the chosen pill is out of view")
            // Everything is reachable by scrolling: after enough swipes the last pill is in view.
            repeat(8) { onNodeWithTag("segmented").performTouchInput { swipeLeft() } }
            waitForIdle()
            val end = look(c, scale)
            assertTrue(inside(end.pills.last()), "$what: cannot scroll to the last option")
        }
    }

    private val PILL_DP = 28f

    @Test
    fun everyListAtEveryWidthKeepsToWholeRowsOfWholeWords() {
        for (c in SEG_CASES) for (w in SEG_WIDTHS) check(c, w, 1f, false)
    }

    @Test
    fun theSameInGlass() {
        for (c in SEG_CASES) for (w in SEG_WIDTHS) check(c, w, 1f, true)
    }

    @Test
    fun theSameAtTheLargestInterfaceSize() {
        for (c in SEG_CASES) for (w in listOf(260, 480)) check(c, w, 1.3f, false)
    }

    @Test
    fun whereEverythingFitsItIsOneRowAtItsNaturalWidth() = runComposeUiTest {
        for (c in SEG_CASES) {
            show(c, 2400, 1f, false)
            val roomy = look(c, 1f)
            assertEquals(1, rowsOf(roomy), "${c.name} with room")
            val natural = roomy.container.width
            // Give it exactly its natural width, and a little more: still the same single row.
            for (extra in listOf(0f, 40f)) {
                show(c, natural.value.toInt() + 1 + extra.toInt(), 1f, false)
                val seen = look(c, 1f)
                assertEquals(1, rowsOf(seen), "${c.name} at its natural width")
                assertEquals(natural.value, seen.container.width.value, 1.01f, "${c.name} keeps its natural width")
            }
        }
    }

    // ---- the menus that share the idea: size to the rows, within sensible limits ----

    @Test
    fun aMenuSizesToItsRowsBetweenAMinimumAndAMaximumWidth() = runComposeUiTest {
        val long = "A very long row label that goes on and on and on, well past what a menu should ever be wide for"
        var items by mutableStateOf(listOf(com.exo.musicplayer.desktop.ui.MoreItem("Short") {}))
        setContent {
            LucenseDesktopTheme { com.exo.musicplayer.desktop.ui.MoreMenu(items = items) }
        }
        onNodeWithContentDescription("More").performClick()
        val short = onNodeWithText("Short").raw(1f)
        assertTrue(short.width.value >= 182f, "a short menu keeps its minimum width, was ${short.width}")
        assertTrue(short.width.value <= 190f, "a short menu is not stretched to the window, was ${short.width}")
        assertEquals(30f, short.height.value, 0.5f, "rows keep their height")

        items = listOf(com.exo.musicplayer.desktop.ui.MoreItem(long) {})
        waitForIdle()
        val wide = onNodeWithText(long).raw(1f)
        assertTrue(wide.width.value <= 360f, "a long label is cut, not allowed to widen the menu: ${wide.width}")
        assertEquals(30f, wide.height.value, 0.5f, "a long label does not wrap or squash its row")
    }

    /** For looking at: the same paragraph at the scales ScaledToWindow can draw at, to /tmp/text-scale-<tag>.png (4x magnified). */
    @Test
    fun textAtFractionalScales() = runComposeUiTest {
        val tag = File("/tmp/seg-tag.txt").takeIf { it.exists() }?.readText()?.trim() ?: "after"
        setContent {
            LucenseDesktopTheme {
                Column(Modifier.background(Color(0xFF17171B)).padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (scale in listOf(1f, 0.9f, 0.8f, 0.62f, 1.15f)) {
                        CompositionLocalProvider(LocalDensity provides Density(scale, 1f)) {
                            Column(Modifier.width((330 / scale).dp)) {
                                Text("scale $scale", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                                Text(
                                    "Library Playback Appearance Downloads — Wellington's illuminated mill, 1.2 million tiny fillies.",
                                    color = Color(0xFFE8E8EE), style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
        }
        waitForIdle()
        val img = onRoot().captureToImage().toAwtImage()
        val crop = img.getSubimage(0, 0, 350.coerceAtMost(img.width), 330.coerceAtMost(img.height))
        val big = java.awt.image.BufferedImage(crop.width * 3, crop.height * 3, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until big.height) for (x in 0 until big.width) big.setRGB(x, y, crop.getRGB(x / 3, y / 3))
        javax.imageio.ImageIO.write(big, "png", File("/tmp/text-scale-$tag.png"))
    }
}
