package com.exo.musicplayer.desktop

import com.exo.musicplayer.desktop.audio.AudioDevices
import com.exo.musicplayer.desktop.audio.Decoder
import com.exo.musicplayer.desktop.audio.PlaybackEngine
import com.exo.musicplayer.desktop.library.DesktopTrack
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/** Playback at the sound system's own rate: the same pitch, the same length, the same clock. */
class OutputRateTest {

    /** [seconds] of a [hz] tone at 44.1 kHz, as a WAV. */
    private fun tone(hz: Double, seconds: Int): File {
        val frames = 44_100 * seconds
        val bytes = ByteArray(frames * 4)
        for (i in 0 until frames) {
            val v = (sin(2 * PI * hz * i / 44_100) * 12_000).toInt()
            for (c in 0..1) {
                bytes[i * 4 + c * 2] = v.toByte()
                bytes[i * 4 + c * 2 + 1] = (v shr 8).toByte()
            }
        }
        val file = Files.createTempFile("lucense-tone", ".wav").toFile().also { it.deleteOnExit() }
        AudioSystem.write(AudioInputStream(ByteArrayInputStream(bytes), AudioFormat(44_100f, 16, 2, true, false), frames.toLong()), AudioFileFormat.Type.WAVE, file)
        return file
    }

    private fun decodeAll(file: File, rate: Int): FloatArray = Decoder.open(file, 0L, rate).use { decoder ->
        val parts = ArrayList<FloatArray>()
        while (true) parts += decoder.read(1024) ?: break
        val out = FloatArray(parts.sumOf { it.size })
        var at = 0
        for (p in parts) { System.arraycopy(p, 0, out, at, p.size); at += p.size }
        out
    }

    /** Upward zero crossings of the left channel, a second's worth, as a frequency. */
    private fun frequency(stereo: FloatArray, rate: Int): Double {
        var crossings = 0
        val from = rate / 4
        val to = from + rate
        for (i in from + 1 until to) if (stereo[(i - 1) * 2] < 0f && stereo[i * 2] >= 0f) crossings++
        return crossings.toDouble()
    }

    @Test
    fun aSongDecodedAt48kHzKeepsItsPitchAndLength() {
        val file = tone(1_000.0, 2)
        for (rate in listOf(44_100, 48_000, 96_000)) {
            val decoded = decodeAll(file, rate)
            val frames = decoded.size / 2
            assertTrue(abs(frames - 2 * rate) < rate / 100, "$rate Hz: $frames frames for two seconds")
            val hz = frequency(decoded, rate)
            assertTrue(abs(hz - 1_000) <= 2, "$rate Hz: the tone is $hz Hz")
        }
    }

    @Test
    fun theEnginesClockKeepsTimeAtItsRate() {
        assumeTrue(AudioDevices.openLine(null)?.also { it.close() } != null, "needs an audio output")
        val engine = PlaybackEngine()
        try {
            val silence = File.createTempFile("lucense-silence", ".wav").also { it.deleteOnExit() }
            val frames = 44_100L * 6
            AudioSystem.write(AudioInputStream(ByteArrayInputStream(ByteArray((frames * 4).toInt())), AudioFormat(44_100f, 16, 2, true, false), frames), AudioFileFormat.Type.WAVE, silence)
            engine.play(DesktopTrack(silence, "Silence", null, null, 6_000L, null, null, 0L))
            Thread.sleep(800)
            val from = engine.status.value.positionMs
            val started = System.nanoTime()
            Thread.sleep(3_000)
            val moved = engine.status.value.positionMs - from
            val elapsed = (System.nanoTime() - started) / 1_000_000
            println("at ${engine.rate} Hz: the position moved $moved ms in $elapsed ms")
            assertTrue(abs(moved - elapsed) < 350, "moved $moved ms in $elapsed ms at ${engine.rate} Hz")
        } finally {
            engine.release()
        }
    }
}
