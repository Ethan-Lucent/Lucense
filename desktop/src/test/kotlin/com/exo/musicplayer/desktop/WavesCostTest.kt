package com.exo.musicplayer.desktop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.exo.musicplayer.desktop.audio.SongShape
import com.exo.musicplayer.desktop.ui.AccentChoice
import com.exo.musicplayer.desktop.ui.FrameGuard
import com.exo.musicplayer.desktop.ui.GlowCache
import com.exo.musicplayer.desktop.ui.Palette
import com.exo.musicplayer.desktop.ui.Placement
import com.exo.musicplayer.desktop.ui.Pulse
import com.exo.musicplayer.desktop.ui.drawGlassGlow
import com.exo.musicplayer.desktop.ui.glassLights
import com.exo.musicplayer.desktop.ui.songGraph
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the Waves background costs a frame at screen size: the fullscreen
 * lyrics window is a screen-sized canvas, drawn about sixty times a second.
 * Written to /tmp/waves-timing.txt; the numbers are for looking at, the
 * assertions only that this is not slower than it was.
 */
class WavesCostTest {

    /** A song that is all there: loudness that swells and falls, as a real one does. */
    private class FakeSong : SongShape {
        override val slices = FloatArray(60_000) { i -> (0.15f + 0.12f * sin(i / 37f) + 0.1f * sin(i / 5.3f)).let { abs(it) } }
        override val filled = slices.size
        override val loudest = 0.4f
        override val active = true
        var now = 30_000.0
        override fun positionMs() = now
    }

    private fun reading(t: Float) = FloatArray(14) { i -> (0.7f - i * 0.03f + 0.08f * sin(t * 11f + i)).coerceIn(0f, 1f) }

    /** Milliseconds each of [frames] frames took to draw at [w] x [h], on the CPU: the worst case a machine without a usable GPU is. */
    private fun time(w: Int, h: Int, frames: Int, whole: Boolean, reduced: Boolean = false): List<Double> {
        Palette.use(AccentChoice.MOCHA)
        val song = FakeSong()
        val pulse = Pulse(14)
        val place = Placement().also { it.x = 0f; it.width = w.toFloat() }
        val image = ImageBitmap(w, h)
        val lights = glassLights(Palette.Accent, Palette.Base, false, false)
        val out = ArrayList<Double>()
        val scope = CanvasDrawScope()
        val glow = GlowCache()
        repeat(frames + 10) { frame ->
            val t = frame / 60f
            song.now += 16.6
            pulse.update(reading(t), t)
            val start = System.nanoTime()
            scope.draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(w.toFloat(), h.toFloat())) {
                // The glow moves fifteen times a second, as the app moves it.
                if (whole) glow.draw(this, Palette.Base, lights, (frame / 4) * 0.0011f, Palette.Base.luminance() > 0.5f)
                songGraph(Palette.Stars, song, pulse, place, reduced)
                if (whole) drawRect(Color.Black.copy(alpha = 0.5f))
            }
            // The first ten are warming up.
            if (frame >= 10) out += (System.nanoTime() - start) / 1e6
        }
        Palette.use(AccentChoice.DEFAULT)
        return out
    }

    private fun List<Double>.percentile(p: Double) = sorted().let { it[((it.size - 1) * p).toInt()] }

    @Test
    fun wavesAtScreenSize() {
        val lines = StringBuilder()
        var p99 = 0.0
        for ((w, h) in listOf(1920 to 1080, 2560 to 1440, 3840 to 2160)) {
            for ((whole, reduced) in listOf(false to false, false to true, true to false)) {
                val ms = time(w, h, 120, whole, reduced)
                lines.append("%dx%d %-26s median %6.1f ms  p99 %6.1f ms  max %6.1f ms\n".format(
                    w, h, if (whole) "waves + glow + veil" else if (reduced) "waves reduced" else "waves only", ms.percentile(0.5), ms.percentile(0.99), ms.max()))
                if (w == 2560 && whole) p99 = ms.percentile(0.99)
            }
        }
        val report = "Waves, 120 frames each, software raster (the CPU worst case)\n$lines"
        File("/tmp/waves-timing.txt").writeText(report)
        println(report)
        val before = File("/tmp/waves-timing-before.txt")
        if (before.exists()) {
            // Not slower than it was, give or take the noise of a shared machine.
            val was = Regex("2560x1440 waves \\+ glow \\+ veil\\s+median\\s+[\\d.]+ ms\\s+p99\\s+([\\d.]+)").find(before.readText())
            if (was != null) assertTrue(p99 <= was.groupValues[1].toDouble() * 1.15, "p99 $p99 ms against ${was.groupValues[1]} ms before")
        }
        assertTrue(p99 > 0.0)
    }

    // ---- the guard ----------------------------------------------------------------

    private val ms = 1_000_000L

    @Test
    fun aSingleLongFrameChangesNothing() {
        val guard = FrameGuard()
        var now = 1_000L * ms
        repeat(30) { guard.frame(now); now += 17 * ms }
        guard.frame(now + 400 * ms)
        assertFalse(guard.reduced)
        repeat(30) { now += 17 * ms; guard.frame(now + 400 * ms) }
        assertFalse(guard.reduced)
    }

    @Test
    fun slowFramesDrawLessThenTryAgainWithALongerWait() {
        val guard = FrameGuard()
        var now = 1_000L * ms
        guard.frame(now)
        repeat(3) { now += 120 * ms; guard.frame(now) }
        assertTrue(guard.reduced, "three slow frames in a row")
        // Four seconds of reduced drawing at a good rate, then full is tried again.
        var t = 0
        while (guard.reduced && t < 1000) { now += 17 * ms; guard.frame(now); t++ }
        assertFalse(guard.reduced)
        assertTrue(t in 200..260, "about four seconds of frames, not $t")
        // And it is still slow: reduced again, for twice as long.
        repeat(3) { now += 120 * ms; guard.frame(now) }
        assertTrue(guard.reduced)
        t = 0
        while (guard.reduced && t < 2000) { now += 17 * ms; guard.frame(now); t++ }
        assertTrue(t in 440..520, "about eight seconds of frames, not $t")
    }

    @Test
    fun resumingAfterAPauseIsNotASlowFrame() {
        val guard = FrameGuard()
        var now = 1_000L * ms
        repeat(10) { guard.frame(now); now += 17 * ms }
        repeat(5) {
            guard.restart()
            now += 20_000 * ms
            guard.frame(now)
            now += 17 * ms
            guard.frame(now)
        }
        assertFalse(guard.reduced)
    }

    // ---- no lock under the draw -----------------------------------------------------

    @Test
    fun theDrawNeverWaitsForTheGraphsLock() {
        val engine = com.exo.musicplayer.desktop.audio.PlaybackEngine()
        val graph = com.exo.musicplayer.desktop.audio.SongGraph(engine)
        // The graph's one lock is the one round its remembered readings; hold it
        // from another thread, as a reader publishing a finished song would.
        val field = graph.javaClass.getDeclaredField("remembered").also { it.isAccessible = true }
        val lock = field.get(graph)
        val holding = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val holder = Thread { synchronized(lock) { holding.countDown(); release.await() } }.also { it.isDaemon = true; it.start() }
        holding.await()
        try {
            val drawn = java.util.concurrent.CountDownLatch(1)
            val painter = Thread {
                val image = ImageBitmap(320, 180)
                CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(320f, 180f)) {
                    graph.slices; graph.filled; graph.loudest; graph.active
                    songGraph(Color.White, graph, null, Placement().also { it.width = 320f })
                    graph.positionMs()
                }
                drawn.countDown()
            }.also { it.isDaemon = true; it.start() }
            assertTrue(drawn.await(5, java.util.concurrent.TimeUnit.SECONDS), "the draw waited for the graph's lock")
            painter.join()
        } finally {
            release.countDown()
            holder.join()
        }
    }

    // ---- looking ------------------------------------------------------------------

    @Test
    fun writesAFrameOfWaves() {
        Palette.use(AccentChoice.MOCHA)
        val song = FakeSong()
        val pulse = Pulse(14)
        for (frame in 0 until 90) pulse.update(reading(frame / 60f), frame / 60f)
        for ((name, reduced) in listOf("" to false, "-reduced" to true)) {
            val image = ImageBitmap(1600, 500)
            CanvasDrawScope().draw(Density(1.25f), LayoutDirection.Ltr, Canvas(image), Size(1600f, 500f)) {
                drawRect(Palette.Base)
                songGraph(Palette.Stars, song, pulse, Placement().also { it.width = 1600f }, reduced)
            }
            File("/tmp/waves-after$name.png").writeBytes(
                org.jetbrains.skia.Image.makeFromBitmap(image.asSkiaBitmap()).encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes
            )
        }
        Palette.use(AccentChoice.DEFAULT)
    }
}
