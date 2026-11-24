package com.exo.musicplayer.desktop.audio

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Mixer
import javax.sound.sampled.SourceDataLine

/** A Windows audio endpoint. */
data class DesktopAudioOutput(
    val name: String,
    val mixerInfo: Mixer.Info?
) {
    val isDefault: Boolean get() = mixerInfo == null
}

/**
 * Output enumeration and line opening.
 *
 * Worth contrasting with Android: there, the audio policy can silently override
 * a per-track device choice, which is why simultaneous multi-device output had
 * to ship as "experimental". On Windows each mixer is an independent endpoint
 * that can be opened directly, so playing to several devices at once is a
 * first-class capability rather than a workaround.
 */
object AudioDevices {

    /** Everything is decoded to this before it reaches a device. */
    val FORMAT: AudioFormat = AudioFormat(
        AudioFormat.Encoding.PCM_SIGNED,
        44100f, 16, 2, 4, 44100f, false
    )

    fun outputs(): List<DesktopAudioOutput> {
        val found = AudioSystem.getMixerInfo().mapNotNull { info ->
            val mixer = runCatching { AudioSystem.getMixer(info) }.getOrNull()
                ?: return@mapNotNull null
            val canPlay = mixer.sourceLineInfo.any { lineInfo ->
                lineInfo is DataLine.Info &&
                    SourceDataLine::class.java.isAssignableFrom(lineInfo.lineClass)
            }
            if (!canPlay) return@mapNotNull null
            DesktopAudioOutput(info.name.trim().ifBlank { info.description }, info)
        }.distinctBy { it.name }

        return listOf(DesktopAudioOutput("System default", null)) + found
    }

    /** 16-bit stereo at [rate], the shape everything is written to a device in. */
    fun format(rate: Int): AudioFormat =
        AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate.toFloat(), 16, 2, 4, rate.toFloat(), false)

    /**
     * The rate playback runs at, chosen once when the engine starts.
     *
     * Everything used to be 44.1 kHz. A desktop's sound server runs at a rate of
     * its own - PipeWire at 48 kHz unless told otherwise - and converts whatever
     * it is handed to that, so a 48 kHz song was converted twice (to 44.1 here,
     * back to 48 there) and a 44.1 kHz one once by the server. Played at the
     * server's own rate, the server converts nothing, and the one conversion
     * left is Lucense's, with its 64-tap filter.
     *
     * [preferred] (Settings, 0 for automatic) or -Dlucense.audio.rate wins;
     * automatic on Linux asks pactl for the sound server's rate, elsewhere it
     * stays 44.1 kHz until measured there. Whatever is chosen is tried on the
     * default device first, and 44.1 kHz is what's left if that fails.
     */
    fun playbackRate(preferred: Int = 0): Int {
        val asked = System.getProperty("lucense.audio.rate")?.trim()?.toIntOrNull()?.takeIf { it in RATES }
            ?: preferred.takeIf { it in RATES }
        val chosen = asked ?: if (com.exo.musicplayer.desktop.system.Platform.isLinux) serverRate() ?: 48_000 else 44_100
        val usable = chosen == 44_100 || openLine(null, format(chosen))?.let { it.close(); true } == true
        val rate = if (usable) chosen else 44_100
        System.err.println("Lucense: audio at $rate Hz" + if (rate != chosen) " ($chosen Hz couldn't be opened)" else "")
        return rate
    }

    /** The sound server's own rate, from pactl's default sample specification; null when there is no saying. */
    private fun serverRate(): Int? = runCatching {
        val process = ProcessBuilder("pactl", "info").redirectErrorStream(true).start()
        val finished = process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return null
        }
        val text = process.inputStream.bufferedReader().readText()
        Regex("Default Sample Specification:.*?(\\d+)Hz").find(text)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in RATES }
    }.getOrNull()

    /** Rates playback will run at. */
    val RATES = setOf(44_100, 48_000, 88_200, 96_000)

    fun openLine(output: DesktopAudioOutput?, format: AudioFormat = FORMAT): SourceDataLine? = runCatching {
        val info = DataLine.Info(SourceDataLine::class.java, format)
        val line = if (output?.mixerInfo == null) {
            AudioSystem.getLine(info) as SourceDataLine
        } else {
            AudioSystem.getMixer(output.mixerInfo).getLine(info) as SourceDataLine
        }
        // Generous buffer: an underrun is far more audible than added latency.
        // 8192 frames at 44.1 kHz, 186 ms; as long a time at any other rate.
        line.open(format, format.frameSize * (8192L * format.sampleRate.toLong() / 44_100L).toInt())
        line.start()
        line
    }.getOrNull()
}
