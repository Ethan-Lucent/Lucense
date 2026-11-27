package com.exo.musicplayer.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.exo.musicplayer.desktop.data.DesktopSettings
import com.exo.musicplayer.desktop.ui.AutoGain
import com.exo.musicplayer.desktop.ui.BackdropStyle
import com.exo.musicplayer.desktop.ui.Backdrop
import com.exo.musicplayer.desktop.ui.BallCover
import com.exo.musicplayer.desktop.ui.Pulse
import com.exo.musicplayer.desktop.ui.ballBarLevel
import com.exo.musicplayer.desktop.ui.ballRadius
import com.exo.musicplayer.desktop.ui.reactiveBall
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import java.util.prefs.Preferences
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Reactive's ball: a seamless mirrored ring, quiet music moving it like loud, and the optional cover in the middle. */
class BallTest {

    private val accent = Color(0xFF7C9CFF)
    private val night = Color(0xFF0B0B12)

    /** A bass-heavy bar of music with the bass thumping twice a second, [drop] below full scale (the analyser is in decibels, so volume is a drop). */
    private fun music(t: Float, drop: Float) = FloatArray(14) { i ->
        val tilt = 0.88f - i * 0.03f
        val thump = if (i < 4) 0.08f * sin(t * 4f * PI.toFloat()) else 0.03f * sin(t * 9f + i)
        (tilt + thump - drop).coerceIn(0f, 1f)
    }

    /** Runs [seconds] of [reading] at sixty frames, the analyser changing eleven times a second as it does. */
    private fun play(pulse: Pulse, seconds: Float, from: Float = 0f, reading: (Float) -> FloatArray): Float {
        var t = from
        val frames = (seconds * 60).toInt()
        repeat(frames) {
            t += 1f / 60f
            pulse.update(reading((t * 11).toInt() / 11f), t)
        }
        return t
    }

    private fun settled(drop: Float, seconds: Float = 8f): Pulse = Pulse(14).also { play(it, seconds) { t -> music(t, drop) } }

    private fun ImageBitmap.pixel(x: Int, y: Int): Int {
        val out = IntArray(1)
        readPixels(out, x, y, 1, 1)
        return out[0]
    }

    private fun Int.channels() = intArrayOf((this shr 16) and 255, (this shr 8) and 255, this and 255)

    // ---- the ring ----------------------------------------------------------------

    @Test
    fun theRingIsMirroredAroundTheVerticalAxisWithNoSeamAtTheTop() {
        val levels = FloatArray(14) { (it * 37 % 11) / 11f }
        val bars = 72
        for (i in 0 until bars) {
            assertEquals(ballBarLevel(levels, i, bars), ballBarLevel(levels, bars - 1 - i, bars), "bar $i and its mirror")
        }
        // The join at the top (and bottom) is a matching pair, not one bar with different neighbours.
        assertEquals(ballBarLevel(levels, 35, bars), ballBarLevel(levels, 36, bars))
        assertEquals(
            abs(ballBarLevel(levels, 35, bars) - ballBarLevel(levels, 34, bars)),
            abs(ballBarLevel(levels, 36, bars) - ballBarLevel(levels, 37, bars))
        )
        assertEquals(ballBarLevel(levels, 0, bars), ballBarLevel(levels, 71, bars))
        // Bass at the bottom, the top band at the top, blended in between rather than stepped.
        assertEquals(levels[13], ballBarLevel(levels, 35, bars), 0.25f)
        // A smooth ramp of bands comes out as a smooth ramp of bars: the same small step every time.
        val ramp = FloatArray(14) { it / 13f }
        val steps = (1 until 36).map { ballBarLevel(ramp, it, bars) - ballBarLevel(ramp, it - 1, bars) }
        assertTrue(steps.all { abs(it - steps[0]) < 1e-4f && it > 0f }, "no stair-steps: $steps")
    }

    // ---- level --------------------------------------------------------------------

    @Test
    fun theSameMusicAtTenPercentAndFullVolumeMovesTheBallTheSame() {
        val loud = settled(0f)
        val quiet = settled(0.294f) // 20 dB down on the analyser's 68 dB scale: a tenth of the amplitude
        for (i in loud.levels.indices) {
            val a = loud.levels[i]
            val b = quiet.levels[i]
            assertTrue(abs(a - b) <= 0.15f * max(a, 0.2f), "band $i: loud $a, quiet $b")
        }
        assertTrue(abs(loud.punch - quiet.punch) <= 0.15f, "punch ${loud.punch} vs ${quiet.punch}")
    }

    @Test
    fun theTopBandsOfQuietMusicAreNotLeftAsDots() {
        val quiet = settled(0.3f)
        assertTrue(quiet.levels[10] > 0.15f, "a treble band of quiet music: ${quiet.levels[10]}")
    }

    @Test
    fun silenceStaysAtNothingAndSettles() {
        val pulse = Pulse(14)
        play(pulse, 1f) { FloatArray(14) }
        assertTrue(pulse.levels.all { it == 0f }, "silence: ${pulse.levels.toList()}")
        assertEquals(0f, pulse.kick)
        assertTrue(pulse.change < 0.0005f)
        // After music, and then silence, the ball comes to rest by itself.
        val t = play(pulse, 4f) { music(it, 0f) }
        assertTrue(pulse.energy > 0.2f)
        play(pulse, 8f, t) { FloatArray(14) }
        assertTrue(pulse.change < 0.0005f, "settled: ${pulse.change}")
        assertTrue(pulse.levels.all { it < 0.001f })
    }

    @Test
    fun theGainLetsGoSlowlySoAQuietBarAfterALoudOneIsNotPumpedUp() {
        val gain = AutoGain(reference = 0.85f, maxBoost = 0.55f)
        repeat(120) { gain.update(0.85f, 1f / 60f) }
        assertEquals(0f, gain.boost, 1e-4f)
        repeat(60) { gain.update(0.5f, 1f / 60f) } // a second of something much quieter
        assertTrue(gain.boost < 0.1f, "after a second: ${gain.boost}")
        repeat(60 * 40) { gain.update(0.5f, 1f / 60f) } // but a quiet song does get there
        assertEquals(0.35f, gain.boost, 0.03f)
        // Hiss is not lifted; neither is a bar that is nothing.
        val hiss = AutoGain(0.85f, 0.55f)
        repeat(60) { hiss.update(0.05f, 1f / 60f) }
        assertEquals(0f, hiss.boost)
        assertEquals(0f, AutoGain.lift(0f, 0.5f))
    }

    @Test
    fun aBeatStillHitsOnQuietMusic() {
        val pulse = Pulse(14)
        var t = 0f
        var strongest = 0f
        // Quiet music with the bass kicking once a second.
        repeat(60 * 6) {
            t += 1f / 60f
            val on = (t % 1f) < 0.12f
            val reading = FloatArray(14) { i -> if (i < 3) (if (on) 0.62f else 0.30f) else 0.2f }
            pulse.update(reading, t)
            strongest = max(strongest, pulse.kick)
        }
        assertTrue(strongest > 0.5f, "kick $strongest")
    }

    // ---- the setting --------------------------------------------------------------

    @Test
    fun ballCoverDefaultsToOffAndKeepsWhatIsSet() {
        val root = Preferences.userRoot().node("com/exo/musicplayer-balltest")
        try {
            assertFalse(DesktopSettings(root).ballCover)
            DesktopSettings(root).ballCover = true
            assertTrue(DesktopSettings(root).ballCover)
            DesktopSettings(root).ballCover = false
            assertFalse(DesktopSettings(root).ballCover)
        } finally {
            root.removeNode()
        }
    }

    // ---- pictures -----------------------------------------------------------------

    private fun solid(color: Color, size: Int = 64): ImageBitmap {
        val image = ImageBitmap(size, size)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(size.toFloat(), size.toFloat())) {
            drawRect(color)
        }
        return image
    }

    private fun ImageBitmap.save(path: String) =
        File(path).writeBytes(Image.makeFromBitmap(asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)!!.bytes)

    /** The ball at the pulse's size in a 600 px square, [cover] in it, and a pane of [pane] drawn over everything after it if given. */
    private fun render(pulse: Pulse, cover: BallCover?, pane: Color? = null): ImageBitmap {
        val image = ImageBitmap(600, 600)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(600f, 600f)) {
            drawRect(night)
            // t = 0 and a still pulse: no shake, so the centre is where it is expected.
            reactiveBall(0f, accent, pulse, cover)
            if (pane != null) drawRect(pane)
        }
        return image
    }

    @Test
    fun writesTheBallAtSeveralLevels() {
        val levels = listOf("silence" to 2f, "veryquiet" to 0.62f, "quiet" to 0.42f, "normal" to 0.2f, "loud" to 0f)
        for ((name, drop) in levels) {
            val pulse = Pulse(14)
            val t = play(pulse, 8f) { music(it, drop) }
            val image = ImageBitmap(700, 700)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(700f, 700f)) {
                drawRect(night)
                reactiveBall(t, accent, pulse)
            }
            image.save("/tmp/ball-after-$name.png")
        }
    }

    @Test
    fun theCoverIsInTheMiddleOfTheBallAndNowhereElse() {
        val pulse = Pulse(14)
        val red = Color(0xFFE53935)
        val with = render(pulse, BallCover(to = solid(red)))
        val without = render(pulse, null)
        with.save("/tmp/ball-cover.png")
        val (r, g, b) = with.pixel(300, 300).channels()
        assertTrue(r > 200 && g < 70 && b < 70, "centre is the cover's colour: $r $g $b")
        val (r0, _, b0) = without.pixel(300, 300).channels()
        assertTrue(b0 > r0, "no cover, the plain ball as before")
        assertEquals(night.toArgb(), with.pixel(8, 8), "far outside the ball is untouched")
        // Round, not square: the corner of the cover's own square is not the cover.
        val reach = (ballRadius(pulse, 600f, 600f) * 0.62f).toInt()
        val corner = with.pixel(300 + reach - 2, 300 + reach - 2).channels()
        assertTrue(corner[0] < 200 || corner[2] > 70, "the corner of the square is clipped away: ${corner.toList()}")
        // The ball without a cover is untouched by having the option exist.
        assertEquals(without.pixel(300 + 3 * reach, 300), with.pixel(300 + 3 * reach, 300))
    }

    @Test
    fun aPaneDrawnOverTheBallCoversTheCoverJustLikeTheRing() {
        val pulse = settled(0.1f, 3f)
        val pane = Color(0xFF1E1E2A).copy(alpha = 0.6f)
        val cover = BallCover(to = solid(Color(0xFFE53935)))
        val below = render(pulse, cover)
        val over = render(pulse, cover, pane)
        val radius = ballRadius(pulse, 600f, 600f)
        // The cover, and a point on the ring's bars, both seen through the pane: pane over what is below.
        for ((x, y) in listOf(300 to 300, 300 to (300 + radius * 1.4f).toInt())) {
            val under = below.pixel(x, y).channels()
            val seen = over.pixel(x, y).channels()
            val p = intArrayOf((0x1E), (0x1E), (0x2A))
            for (c in 0..2) {
                val expected = 0.6f * p[c] + 0.4f * under[c]
                assertTrue(abs(seen[c] - expected) <= 3f, "($x,$y) channel $c: seen ${seen[c]}, pane-over-below $expected")
            }
        }
        // Nothing of the cover in front of the pane: the centre is nowhere near the cover's own red.
        assertTrue(over.pixel(300, 300).channels()[0] < 0.6f * 0x1E + 0.4f * 255 + 3)
        over.save("/tmp/ball-cover-under-pane.png")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun inTheRealTreeAPaneOverTheBackdropStaysInFrontOfTheCover() = runComposeUiTest {
        var pane by mutableStateOf(false)
        val cover = solid(Color(0xFFE53935))
        setContent {
            Box(Modifier.size(400.dp).background(night)) {
                Backdrop(
                    style = BackdropStyle.REACTIVE,
                    color = accent,
                    spectrum = null,
                    modifier = Modifier.fillMaxSize(),
                    ballCover = cover
                )
                if (pane) Box(Modifier.fillMaxSize().background(Color(0xFF1E1E2A).copy(alpha = 0.6f)))
            }
        }
        waitForIdle()
        mainClock.advanceTimeBy(600)
        waitForIdle()
        val below = onRoot().captureToImage()
        below.save("/tmp/ball-tree-below.png")
        pane = true
        waitForIdle()
        val over = onRoot().captureToImage()
        val mid = 200 // the middle of the 400 px square, which sits at the top left of the test window
        val under = below.pixel(mid, mid).channels()
        assertTrue(under[0] > 180 && under[1] < 90, "the cover is drawn by the Backdrop: ${under.toList()}")
        val seen = over.pixel(mid, mid).channels()
        val p = intArrayOf(0x1E, 0x1E, 0x2A)
        for (c in 0..2) assertTrue(abs(seen[c] - (0.6f * p[c] + 0.4f * under[c])) <= 3f, "channel $c: ${seen[c]}")
    }
}
