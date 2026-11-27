package com.exo.musicplayer.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.testTag
import com.exo.musicplayer.desktop.data.RepeatMode
import com.exo.musicplayer.desktop.ui.BarCentre
import com.exo.musicplayer.desktop.ui.BarNowPlaying
import com.exo.musicplayer.desktop.ui.BarRight
import com.exo.musicplayer.desktop.ui.BarSlots
import com.exo.musicplayer.desktop.ui.SeekLine
import com.exo.musicplayer.desktop.ui.SidePanelKind
import com.exo.musicplayer.desktop.ui.TransportButtons
import com.exo.musicplayer.desktop.ui.centreWidth
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.exo.musicplayer.data.weather.WeatherCondition
import com.exo.musicplayer.desktop.data.DownloadEntry
import com.exo.musicplayer.desktop.ui.Destination
import com.exo.musicplayer.desktop.ui.LucenseDesktopTheme
import com.exo.musicplayer.desktop.ui.Palette
import com.exo.musicplayer.desktop.ui.RailColumn
import com.exo.musicplayer.desktop.ui.WeatherGlyph
import com.exo.musicplayer.desktop.ui.RailFit
import com.exo.musicplayer.desktop.ui.icon
import com.exo.musicplayer.desktop.ui.railFit
import com.exo.musicplayer.desktop.ui.railThumb
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class UiPolishTest {
    private fun save(image: ImageBitmap, name: String) {
        javax.imageio.ImageIO.write(image.toAwtImage(), "png", File("/tmp/ui-$name.png"))
    }

    // ---- 1. sidebar --------------------------------------------------------------

    @Test
    fun thumbGeometry() {
        assertNull(railThumb(0, 0, 500))      // nothing scrolls, no thumb
        assertNull(railThumb(0, 100, 0))      // not measured yet
        val top = railThumb(0, 100, 400)!!    // 400 of 500 showing
        assertEquals(0f, top.offset, 1e-6f)
        assertEquals(0.8f, top.size, 1e-6f)
        val bottom = railThumb(100, 100, 400)!!
        assertEquals(0.2f, bottom.offset, 1e-6f)            // sits flush: offset + size = 1
        assertEquals(1f, bottom.offset + bottom.size, 1e-6f)
        val middle = railThumb(50, 100, 400)!!
        assertEquals(0.1f, middle.offset, 1e-6f)
        // A very long list keeps a thumb that can be seen, and still ends flush.
        val long = railThumb(9_900, 9_900, 100)!!
        assertEquals(0.12f, long.size, 1e-6f)
        assertEquals(1f, long.offset + long.size, 1e-6f)
        // Overscroll values never put it off the track.
        val over = railThumb(500, 100, 400)!!
        assertEquals(1f, over.offset + over.size, 1e-6f)
    }

    @Test
    fun fitStepsDownAsTheRailGetsShorter() {
        for (compact in listOf(true, false)) {
            assertEquals(RailFit.ROOMY, railFit(900.dp, compact))
            assertEquals(RailFit.TIGHT, railFit(300.dp, compact))
            var last = RailFit.ROOMY.ordinal
            for (h in 900 downTo 300 step 5) {
                val now = railFit(h.dp, compact).ordinal
                assertTrue(now >= last, "never loosens as it shortens")
                last = now
            }
        }
    }

    private fun rail(compact: Boolean, height: Dp, fit: RailFit?, cue: Boolean = true, downloads: List<DownloadEntry> = emptyList()): @androidx.compose.runtime.Composable () -> Unit = {
        LucenseDesktopTheme {
            Box(Modifier.size(if (compact) 60.dp else 212.dp, height).background(Palette.Sidebar)) {
                RailColumn(
                    compact, fit ?: railFit(height, compact), Destination.LIBRARY, false, true, {}, downloads, false,
                    {}, {}, {}, scrollCue = cue
                )
            }
        }
    }

    /** Every page and Settings fit, no scrolling, at the smallest heights the window can give the rail. */
    @Test
    fun everythingFitsDownToTheMinimumWindow() = runDesktopComposeUiTest(400, 1000) {
        var h by mutableStateOf(900.dp)
        var compact by mutableStateOf(true)
        setContent { rail(compact, h, null)() }
        // 600 dp design height, less the title bar (34) and the bar (77), is the least the rail is given.
        for (c in listOf(true, false)) {
            compact = c
            for (height in listOf(489, 520, 560, 600, 700, 900)) {
                h = height.dp
                waitForIdle()
                assertEquals(0, onAllNodesWithTag("rail-thumb").fetchSemanticsNodes().size, "compact=$c at $height dp overflows")
            }
        }
        // Settings stays inside the rail, under the last page.
        compact = false; h = 489.dp; waitForIdle()
        assertTrue(onNodeWithText("Settings").getBoundsInRoot().bottom <= 489.dp)
        assertTrue(onNodeWithText("Stats").getBoundsInRoot().bottom <= onNodeWithText("Settings").getBoundsInRoot().top)
    }

    /** Too short even when tight: the thumb shows, and the wheel scrolls the pages from anywhere over the rail. */
    @Test
    fun overflowShowsTheThumbAndTheWheelScrolls() = runDesktopComposeUiTest(400, 600) {
        setContent { rail(false, 300.dp, null)() }
        waitForIdle()
        onNodeWithTag("rail-thumb").assertExists()
        val before = onNodeWithText("Library").getBoundsInRoot().top
        onNodeWithText("Discover").performMouseInput { moveTo(center); scroll(120f) }
        waitForIdle()
        val after = onNodeWithText("Library").getBoundsInRoot().top
        assertTrue(after < before, "wheel over an item scrolled the rail: $before -> $after")
    }

    @Test
    fun lookAtTheRail() = runDesktopComposeUiTest(520, 700) {
        // Pictures only: before = the old spacing with no cue, after = the new.
        val downloads = listOf(DownloadEntry(1, "x", "Some song - Artist", percent = 0.4f))
        var variant by mutableStateOf(0)
        setContent {
            LucenseDesktopTheme {
                androidx.compose.foundation.layout.Row(Modifier.background(Palette.Base)) {
                    val sizes = listOf(Triple(true, 489.dp, 0), Triple(false, 489.dp, 0), Triple(true, 300.dp, 0), Triple(false, 300.dp, 0))
                    sizes.forEach { (compact, height, _) ->
                        val old = variant == 0
                        Box(Modifier.size(if (compact) 60.dp else 212.dp, 700.dp)) {
                            Box(Modifier.size(if (compact) 60.dp else 212.dp, height).background(Palette.Sidebar)) {
                                RailColumn(
                                    compact, if (old) RailFit.ROOMY else railFit(height, compact), Destination.LIBRARY, false, true, {},
                                    if (compact) emptyList() else downloads, false, {}, {}, {}, scrollCue = !old
                                )
                            }
                        }
                    }
                }
            }
        }
        waitForIdle()
        save(onRoot().captureToImage(), "rail-before")
        variant = 1
        waitForIdle()
        save(onRoot().captureToImage(), "rail-after")
    }

    // ---- 2. play bar -------------------------------------------------------------

    @androidx.compose.runtime.Composable
    private fun fakeBar(widthDp: Dp, narrow: Boolean, showAb: Boolean, showOutput: Boolean, title: String, old: Boolean) {
        LucenseDesktopTheme {
            val centreW = centreWidth(narrow, showAb)
            val left: @androidx.compose.runtime.Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
                BarNowPlaying(
                    narrow, { Box(Modifier.size(46.dp).background(Palette.Accent)) }, title,
                    androidx.compose.ui.text.AnnotatedString("Some Artist"), Modifier, false, {}
                )
            }
            val centre: @androidx.compose.runtime.Composable () -> Unit = {
                BarCentre(
                    centreW,
                    buttons = {
                        TransportButtons(true, RepeatMode.ALL, true, showAb, null, null, true, {}, {}, {}, {}, {}, {})
                    },
                    seek = { SeekLine(83_000, 243_000, true, null, null) {} }
                )
            }
            val right: @androidx.compose.runtime.Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
                BarRight(narrow, SidePanelKind.QUEUE, false, false, showOutput, false, false, 0.6f, {}, {}, {}) {
                    Box(Modifier.size(32.dp))
                }
            }
            val mod = Modifier.size(widthDp, 76.dp).background(Palette.Raised).padding(horizontal = 20.dp)
            if (old) {
                // The bar as it was: the middle between two spacers, the left a fixed 310 dp.
                androidx.compose.foundation.layout.Row(mod, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    left()
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    Box(Modifier.testTag("bar-centre").width(centreW)) { centre() }
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    right()
                }
            } else {
                BarSlots(mod, centreW, left, centre, right)
            }
        }
    }

    /** The middle slot is in the middle of the bar to the pixel, whatever the sides hold, and nothing overlaps it. */
    @Test
    fun barCentreIsCentredAtEveryWidth() {
        for (width in listOf(760, 920, 1280, 1920)) for (variant in 0..3) {
            val narrow = width < 1000
            val ab = variant and 1 == 1
            val out = variant and 2 == 2
            runDesktopComposeUiTest(width, 120) {
                setContent { fakeBar(width.dp, narrow, ab, out, "A Very Long Song Title That Has To Be Cut Short", old = false) }
                waitForIdle()
                val centre = onNodeWithTag("bar-centre").getBoundsInRoot()
                val mid = (centre.left + centre.right) / 2
                assertEquals(width / 2f, mid.value, 1f, "centre of the middle slot at $width dp (ab=$ab, output=$out)")
                val left = onNodeWithTag("bar-left").getBoundsInRoot()
                val right = onNodeWithTag("bar-right").getBoundsInRoot()
                assertTrue(left.right <= centre.left + 0.5f.dp, "left slot ends before the middle at $width")
                assertTrue(right.left >= centre.right - 0.5f.dp, "right slot starts after the middle at $width")
            }
        }
    }

    /**
     * The play button is under the middle of the seek line with or without the A-B button: A-B on the
     * right alone used to push the evenly spread row to the left (play sat ~37 px off).
     */
    @Test
    fun playButtonStaysUnderTheMiddleOfTheLineWithAbShown() {
        for (width in listOf(760, 920, 1280)) for (ab in listOf(false, true)) {
            runDesktopComposeUiTest(width, 120) {
                setContent { fakeBar(width.dp, width < 1000, ab, false, "Midnight City", old = false) }
                waitForIdle()
                val centre = onNodeWithTag("bar-centre").getBoundsInRoot()
                val play = onNodeWithContentDescription("Pause").getBoundsInRoot()
                assertEquals(
                    ((centre.left + centre.right) / 2).value, ((play.left + play.right) / 2).value, 1f,
                    "play under the middle of the line at $width dp (ab=$ab)"
                )
                // And the row is symmetric: shuffle and repeat are as far from play on either side.
                val shuffle = onNodeWithContentDescription("Shuffle on").getBoundsInRoot()
                val repeat = onNodeWithContentDescription("Repeat all").getBoundsInRoot()
                assertEquals(
                    (play.left - shuffle.right).value, (repeat.left - play.right).value, 1.5f,
                    "shuffle and repeat equally far from play at $width dp (ab=$ab)"
                )
                if (ab && width == 920) save(onRoot().captureToImage(), "bar-ab")
            }
        }
    }

    /** The clocks and the line fill the middle slot exactly, so the row is as wide as the cluster. */
    @Test
    fun seekRowSpansTheCluster() = runDesktopComposeUiTest(1280, 120) {
        setContent { fakeBar(1280.dp, false, false, false, "Midnight City", old = false) }
        waitForIdle()
        val centre = onNodeWithTag("bar-centre").getBoundsInRoot()
        val shuffle = onNodeWithContentDescription("Shuffle on").getBoundsInRoot()
        val repeat = onNodeWithContentDescription("Repeat all").getBoundsInRoot()
        val clock = onNodeWithText("1:23").getBoundsInRoot()
        val total = onNodeWithText("4:03").getBoundsInRoot()
        assertTrue(shuffle.left >= centre.left && repeat.right <= centre.right, "buttons inside the slot")
        // The seek row's ends are the slot's ends: the clocks' 38 dp boxes start and end at it.
        assertEquals(centre.left.value, (clock.right - 38.dp).value, 1f)
        assertEquals(centre.right.value, (total.left + 38.dp).value, 1f)
    }

    /** The title is capped at 140 dp in a narrow bar (200 otherwise), and the slots' items share one centre line. */
    @Test
    fun titleCapAndOneCentreLine() {
        runDesktopComposeUiTest(920, 120) {
            setContent { fakeBar(920.dp, true, false, false, "A Very Long Song Title That Has To Be Cut Short", old = false) }
            waitForIdle()
            val title = onNodeWithText("A Very Long Song Title That Has To Be Cut Short").getBoundsInRoot()
            assertTrue(title.right - title.left <= 140.5f.dp, "narrow title is ${title.right - title.left}")
            val middle = 38f
            // The middle is a block (buttons over the seek line); its middle is the line the sides' items sit on.
            val block = onNodeWithTag("bar-centre").getBoundsInRoot()
            assertEquals(middle, (block.top + block.bottom).value / 2, 1.5f, "middle block")
            for (name in listOf("Queue", "Lyrics", "Mute")) {
                val b = onNodeWithContentDescription(name).getBoundsInRoot()
                assertEquals(middle, (b.top + b.bottom).value / 2, 1.5f, "$name on the centre line")
            }
        }
        runDesktopComposeUiTest(1280, 120) {
            setContent { fakeBar(1280.dp, false, false, false, "A Very Long Song Title That Has To Be Cut Short", old = false) }
            waitForIdle()
            val wide = onNodeWithText("A Very Long Song Title That Has To Be Cut Short").getBoundsInRoot()
            assertEquals(200f, (wide.right - wide.left).value, 1f)
        }
    }

    @Test
    fun lookAtTheBar() {
        for (old in listOf(true, false)) {
            runDesktopComposeUiTest(1280, 100) {
                setContent {
                    androidx.compose.foundation.layout.Column(Modifier.background(Palette.Base)) {
                        fakeBar(1280.dp, false, false, false, "Midnight City", old)
                    }
                }
                waitForIdle()
                save(onRoot().captureToImage(), if (old) "bar-before" else "bar-after")
            }
        }
        runDesktopComposeUiTest(920, 100) {
            setContent { fakeBar(920.dp, true, true, true, "A Very Long Song Title That Has To Be Cut Short", old = false) }
            waitForIdle()
            save(onRoot().captureToImage(), "bar-after-narrow")
        }
    }

    // ---- 3. weather icons --------------------------------------------------------

    @Test
    fun everyWeatherHasItsOwnDrawnIcon() {
        val icons = WeatherCondition.entries.map { it.icon() }
        assertEquals(WeatherCondition.entries.size, icons.distinct().size, "each condition reads differently")
        icons.forEach { assertNotNull(it) }
    }

    @Test
    fun lookAtTheWeather() = runDesktopComposeUiTest(520, 120) {
        var old by mutableStateOf(true)
        setContent {
            LucenseDesktopTheme {
                androidx.compose.foundation.layout.Row(
                    Modifier.background(Palette.Content).padding(16.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    WeatherCondition.entries.forEach { c ->
                        val now = c == WeatherCondition.RAIN
                        if (old) {
                            androidx.compose.material3.Text(c.emoji, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                        } else {
                            WeatherGlyph(c, if (now) Palette.Accent else Palette.TextDim)
                        }
                        androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
                        androidx.compose.material3.Text(c.label, style = androidx.compose.material3.MaterialTheme.typography.labelMedium, color = Palette.TextDim)
                        androidx.compose.foundation.layout.Spacer(Modifier.width(14.dp))
                    }
                }
            }
        }
        waitForIdle()
        save(onRoot().captureToImage(), "weather-before")
        old = false
        waitForIdle()
        save(onRoot().captureToImage(), "weather-after")
    }
}
