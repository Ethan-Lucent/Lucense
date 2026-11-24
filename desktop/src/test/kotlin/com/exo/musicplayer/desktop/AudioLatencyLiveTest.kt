package com.exo.musicplayer.desktop

import com.exo.musicplayer.desktop.audio.AudioDevices
import com.exo.musicplayer.desktop.audio.OutputPacing
import com.exo.musicplayer.desktop.audio.PlaybackEngine
import com.exo.musicplayer.desktop.library.DesktopTrack
import com.exo.musicplayer.desktop.system.Platform
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The real engine through the real default output, playing silence: how much
 * audio sits queued ahead of the speakers, which is how late a moved effect
 * slider is heard. Skipped where there is no audio output, as on CI.
 */
class AudioLatencyLiveTest {

    private fun silence(seconds: Int): File {
        val format = AudioDevices.FORMAT
        val frames = format.sampleRate.toLong() * seconds
        val bytes = ByteArray((frames * format.frameSize).toInt())
        val file = Files.createTempFile("lucense-silence", ".wav").toFile().also { it.deleteOnExit() }
        AudioSystem.write(AudioInputStream(ByteArrayInputStream(bytes), format, frames), AudioFileFormat.Type.WAVE, file)
        return file
    }

    @Test
    fun onLinuxWhatIsQueuedAheadOfTheSpeakersStaysNearTheTarget() {
        assumeTrue(Platform.isLinux, "Linux paces its output; Windows keeps the line full")
        assumeTrue(System.getProperty("lucense.audio.latencyMs") == null, "measures the default")
        val probe = AudioDevices.openLine(null)
        assumeTrue(probe != null, "needs an audio output")
        probe?.close()

        val engine = PlaybackEngine()
        try {
            engine.play(DesktopTrack(silence(4), "Silence", null, null, 4_000L, null, null, 0L))
            Thread.sleep(800)
            // BeatTap measures what the line holds just before each write.
            val queuedMs = (1..40).map {
                Thread.sleep(50)
                engine.beat.delayNanos / 1_000_000.0
            }
            val mean = queuedMs.average()
            println("queued ahead of the speakers: mean %.1f ms, max %.1f ms".format(mean, queuedMs.max()))
            assertTrue(
                mean <= OutputPacing.LINUX_START_MS + 5,
                "about ${OutputPacing.LINUX_START_MS} ms should be queued before each block, was %.1f ms".format(mean)
            )
        } finally {
            engine.release()
        }
    }
}
