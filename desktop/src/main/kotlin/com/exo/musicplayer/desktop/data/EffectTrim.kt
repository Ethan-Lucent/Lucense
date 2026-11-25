package com.exo.musicplayer.desktop.data

import com.exo.musicplayer.data.audio.EffectPreset
import com.exo.musicplayer.desktop.audio.Decoder
import com.exo.musicplayer.desktop.audio.EffectChain
import com.exo.musicplayer.desktop.ui.DesktopFxState
import java.io.File
import java.io.OutputStream

/** Sets [chain] up as [fx] says: the one place the effects panel's settings become the chain's. */
fun EffectChain.use(fx: DesktopFxState) {
    speed = fx.speed
    pitchSemitones = fx.pitchSemitones
    reverb.enabled = fx.reverbEnabled
    reverb.mix = fx.reverbMix
    reverb.decay = fx.reverbDecay
    equalizer.enabled = fx.eqEnabled
    equalizer.setGains(fx.eqGains)
    character.drive = fx.drive
    character.lofi = fx.lofi
    character.tremolo = fx.tremolo
    character.rotate = fx.rotate
    character.echo = fx.echo
}

/**
 * A trim that keeps the effects: the piece played through the same chain the
 * player uses, and encoded again.
 *
 * A plain trim is cut, not encoded - lossless, but nothing can be done to the
 * sound on the way. With the effects, the piece is decoded, run through an
 * [EffectChain] set up exactly as the player's ([use]), and streamed into
 * ffmpeg as 16-bit PCM, which encodes it in the original's format at a high
 * setting and copies the original's tags and cover across. A reverb gets its
 * tail: the room rings on past the cut rather than stopping with it.
 */
internal object EffectTrim {

    const val RATE = 44_100

    /** How long a reverb is let ring after the cut (in the song's time; the speed stretches it too). */
    const val TAIL_MS = 2_500L

    /** Enough silence to see the stretcher's last held sequence out. */
    private const val FLUSH_FRAMES = 8_192L

    /**
     * Streams [startMs]..[endMs] of [file] through [fx] into [out] as 16-bit
     * little-endian stereo at [RATE]; returns the frames written.
     */
    fun render(file: File, startMs: Long, endMs: Long, fx: DesktopFxState, out: OutputStream): Long {
        val chain = EffectChain(RATE).apply { use(fx) }
        var written = 0L
        val bytes = ByteArray(8192 * 4)
        fun write(samples: FloatArray) {
            var i = 0
            while (i < samples.size) {
                val n = minOf(samples.size - i, bytes.size / 2)
                for (k in 0 until n) {
                    val v = (samples[i + k].coerceIn(-1f, 1f) * 32767f).toInt()
                    bytes[k * 2] = v.toByte()
                    bytes[k * 2 + 1] = (v shr 8).toByte()
                }
                out.write(bytes, 0, n * 2)
                i += n
            }
            written += samples.size / 2
        }
        val start = startMs * RATE / 1000
        var left = (endMs - startMs) * RATE / 1000
        Decoder.open(file, start, RATE).use { decoder ->
            while (left > 0) {
                val block = decoder.read(minOf(4096L, left).toInt()) ?: break
                left -= block.size / 2
                write(chain.process(block))
            }
        }
        // Silence after the cut: a reverb's tail rings out into it, and the
        // stretcher hands over the last of the song it was still holding.
        var tail = when {
            fx.reverbEnabled -> TAIL_MS * RATE / 1000
            kotlin.math.abs(fx.speed - 1f) > 0.005f || kotlin.math.abs(fx.pitchSemitones) > 0.05f -> FLUSH_FRAMES
            else -> 0L
        }
        run {
            while (tail > 0) {
                val n = minOf(4096L, tail).toInt()
                tail -= n
                write(chain.process(FloatArray(n * 2)))
            }
        }
        out.flush()
        return written
    }

    /** The extension the piece is saved with: the original's, where ffmpeg can write it, else MP3. */
    fun extension(original: File): String = when (val ext = original.extension.lowercase()) {
        "mp3", "flac", "ogg", "oga", "opus", "wav", "aiff", "aif", "m4a" -> ext
        "mp4", "m4b", "aac" -> "m4a"
        else -> "mp3"
    }

    /** How ffmpeg encodes [extension]: high settings, since it's encoded once more. */
    private fun encoder(extension: String): List<String> = when (extension) {
        "mp3" -> listOf("-c:a", "libmp3lame", "-q:a", "0")
        "m4a" -> listOf("-c:a", "aac", "-b:a", "256k")
        "flac" -> listOf("-c:a", "flac")
        "ogg", "oga" -> listOf("-c:a", "libvorbis", "-q:a", "7")
        "opus" -> listOf("-c:a", "libopus", "-b:a", "192k")
        "wav" -> listOf("-c:a", "pcm_s16le")
        "aiff", "aif" -> listOf("-c:a", "pcm_s16be")
        else -> listOf("-c:a", "libmp3lame", "-q:a", "0")
    }

    /** ffmpeg reading the PCM from its input, the original's tags and cover from [original]. */
    fun ffmpegArgs(original: File, output: File, title: String): List<String> = buildList {
        addAll(listOf("-hide_banner", "-loglevel", "error", "-y"))
        addAll(listOf("-f", "s16le", "-ar", RATE.toString(), "-ac", "2", "-i", "pipe:0"))
        addAll(listOf("-i", original.absolutePath))
        add("-map"); add("0:a")
        val ext = output.extension.lowercase()
        // The cover, where the format keeps one as a picture stream.
        if (ext in setOf("mp3", "m4a", "flac")) {
            addAll(listOf("-map", "1:v?", "-c:v", "copy", "-disposition:v", "attached_pic"))
        }
        addAll(listOf("-map_metadata", "1"))
        addAll(encoder(ext))
        addAll(listOf("-metadata", "title=$title", "-metadata:s:a", "title=$title"))
        add(output.absolutePath)
    }

    /** What the effects are called: a preset's name when they are one, else just "with effects". */
    fun describe(fx: DesktopFxState): String =
        EffectPreset.entries.firstOrNull { it != EffectPreset.NORMAL && fx.matches(it) }?.label?.lowercase() ?: "with effects"
}
