package com.exo.musicplayer.desktop

import com.exo.musicplayer.data.audio.EffectPreset
import com.exo.musicplayer.desktop.audio.Decoder
import com.exo.musicplayer.desktop.data.EffectTrim
import com.exo.musicplayer.desktop.ui.DesktopFxState
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A trim with the effects: the piece as the player would play it, in a file of its own. */
class EffectTrimTest {

    private val dir = Files.createTempDirectory("lucense-trim").toFile()

    private fun tone(seconds: Int): File {
        val frames = 44_100 * seconds
        val bytes = ByteArray(frames * 4)
        for (i in 0 until frames) {
            val v = (sin(2 * PI * 440.0 * i / 44_100) * 9_000).toInt()
            for (c in 0..1) { bytes[i * 4 + c * 2] = v.toByte(); bytes[i * 4 + c * 2 + 1] = (v shr 8).toByte() }
        }
        return File(dir, "tone.wav").also {
            AudioSystem.write(AudioInputStream(ByteArrayInputStream(bytes), AudioFormat(44_100f, 16, 2, true, false), frames.toLong()), AudioFileFormat.Type.WAVE, it)
        }
    }

    private fun framesOf(fx: DesktopFxState, file: File, startMs: Long = 0, endMs: Long = 2_000): Long =
        EffectTrim.render(file, startMs, endMs, fx, ByteArrayOutputStream())

    @Test
    fun theEffectsShapeThePiece() {
        val song = tone(3)
        val plain = framesOf(DesktopFxState(speed = 1f), song)
        assertTrue(abs(plain - 88_200) < 2_000, "two seconds stay two: $plain frames")
        val slowed = framesOf(DesktopFxState(speed = 0.5f), song)
        assertTrue(slowed in 172_000..196_000, "at half speed, twice as long - all of it, the end included: $slowed frames")
        val room = framesOf(DesktopFxState(reverbEnabled = true), song)
        assertTrue(abs(room - (88_200 + EffectTrim.TAIL_MS * 441 / 10)) < 2_000, "the reverb rings on past the cut: $room frames")
    }

    @Test
    fun aPresetNamesThePiece() {
        assertEquals("slowed + reverb", EffectTrim.describe(DesktopFxState().applying(EffectPreset.SLOWED_REVERB)))
        assertEquals("with effects", EffectTrim.describe(DesktopFxState(drive = 0.6f)))
    }

    @Test
    fun anMp3ComesOutAnMp3WithItsTags() {
        val ffmpeg = File("resources/linux-x64/ffmpeg").takeIf { it.canExecute() }
            ?: File("resources/windows-x64/ffmpeg.exe").takeIf { it.isFile }
        assumeTrue(ffmpeg != null, "needs the bundled ffmpeg (tools/fetch-tools.sh)")
        val wav = tone(3)
        val mp3 = File(dir, "Song.mp3")
        val made = ProcessBuilder(ffmpeg!!.path, "-loglevel", "error", "-y", "-i", wav.path, "-metadata", "artist=Someone", "-c:a", "libmp3lame", "-q:a", "2", mp3.path)
            .redirectErrorStream(true).start()
        assertEquals(0, made.waitFor(), made.inputStream.bufferedReader().readText())

        val fx = DesktopFxState().applying(EffectPreset.SLOWED_REVERB)
        val out = File(dir, "Song (slowed + reverb).mp3")
        val process = ProcessBuilder(listOf(ffmpeg.path) + EffectTrim.ffmpegArgs(mp3, out, "Song (slowed + reverb)")).redirectErrorStream(true).start()
        val log = StringBuilder()
        val reader = Thread { log.append(process.inputStream.bufferedReader().readText()) }.apply { start() }
        process.outputStream.buffered().use { EffectTrim.render(mp3, 500, 2_500, fx, it) }
        assertEquals(0, process.waitFor(), log.toString())
        reader.join()

        val decoded = Decoder.open(out).use { d -> var n = 0L; while (true) n += (d.read(4096) ?: break).size / 2; n }
        // Two seconds and the reverb's tail, both at 0.82x speed.
        val expected = (2_000 + EffectTrim.TAIL_MS) / 0.82 * 44.1
        assertTrue(abs(decoded - expected) < 0.08 * expected, "decoded $decoded frames, expected about ${expected.toLong()}")
        val tags = ProcessBuilder(ffmpeg.path, "-hide_banner", "-i", out.path).redirectErrorStream(true).start().inputStream.bufferedReader().readText()
        assertTrue(tags.contains("Someone"), "the artist came across: $tags")
        assertTrue(tags.contains("Song (slowed + reverb)"), "and the new title")
    }
}
