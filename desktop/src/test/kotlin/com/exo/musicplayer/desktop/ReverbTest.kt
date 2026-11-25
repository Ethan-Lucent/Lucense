package com.exo.musicplayer.desktop

import com.exo.musicplayer.desktop.audio.Decoder
import com.exo.musicplayer.desktop.audio.EffectChain
import com.exo.musicplayer.desktop.audio.Reverb
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.ByteArrayInputStream
import java.io.File
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/** The reverb: a room that always dies away, darkens as it does, and doesn't drown the song. */
class ReverbTest {

    private val rate = 44_100

    private fun reverb(mix: Float, decay: Float) = Reverb(rate).apply { enabled = true; this.mix = mix; this.decay = decay }

    private fun noise(frames: Int, seed: Int = 3): FloatArray {
        val random = Random(seed)
        return FloatArray(frames * 2) { (random.nextFloat() - 0.5f) * 0.5f }
    }

    private fun rms(samples: FloatArray, from: Int = 0, to: Int = samples.size): Double {
        var sum = 0.0
        for (i in from until to) sum += samples[i].toDouble() * samples[i]
        return sqrt(sum / (to - from).coerceAtLeast(1))
    }

    /** The room's answer to a single click, [seconds] long, with the dry click itself left out. */
    private fun impulse(decay: Float, seconds: Double): FloatArray {
        val room = reverb(mix = 1f, decay = decay)
        val frames = (seconds * rate).toInt()
        val out = FloatArray(frames * 2)
        out[0] = 1f; out[1] = 1f
        room.process(out)
        out[0] -= 0.5f; out[1] -= 0.5f // the dry click, at half
        return out
    }

    /** Seconds until the tail has fallen [db] below its loudest 50 ms. */
    private fun fallTime(tail: FloatArray, db: Double): Double {
        val window = rate / 20 * 2
        val levels = (0 until tail.size / window).map { rms(tail, it * window, (it + 1) * window) }
        val peakAt = levels.indices.maxBy { levels[it] }
        val peak = levels[peakAt]
        val at = (peakAt until levels.size).firstOrNull { 20 * log10(levels[it] / peak) < -db } ?: return Double.MAX_VALUE
        return (at - peakAt) * 0.05
    }

    @Test
    fun switchedOffItLeavesTheSoundAlone() {
        val input = noise(4096)
        val output = input.copyOf()
        Reverb(rate).apply { enabled = false; mix = 0.8f; decay = 0.9f }.process(output)
        assertContentEquals(input, output)
    }

    @Test
    fun evenTheLargestRoomDiesAway() {
        val tail = impulse(decay = 1f, seconds = 14.0)
        assertTrue(tail.all { it.isFinite() })
        val early = rms(tail, 0, rate * 2)
        val late = rms(tail, tail.size - rate * 2, tail.size)
        assertTrue(late < early / 1000, "after 12 s the tail is 60 dB down: early $early, late $late")
    }

    @Test
    fun aBiggerRoomRingsLonger() {
        val small = fallTime(impulse(decay = 0.45f, seconds = 8.0), 40.0)
        val large = fallTime(impulse(decay = 0.90f, seconds = 8.0), 40.0)
        println("tail to -40 dB: %.2f s at 0.45, %.2f s at 0.90".format(small, large))
        assertTrue(large > small * 2, "small $small s, large $large s")
    }

    @Test
    fun theHighsDieAwayFasterThanTheLows() {
        val tail = impulse(decay = 0.82f, seconds = 3.0)
        // First difference: weighs each frequency by how high it is, a cheap treble meter.
        fun brightness(from: Int, to: Int): Double {
            var high = 0.0
            var all = 0.0
            var i = from + 2
            while (i < to) { val d = tail[i] - tail[i - 2]; high += d * d; all += tail[i] * tail[i]; i += 2 }
            return high / all
        }
        val early = brightness(rate / 10 * 2, rate / 2 * 2)
        val late = brightness(rate * 2, rate * 3)
        assertTrue(late < early * 0.5, "the tail darkens as it decays: early $early, late $late")
    }

    /**
     * Normalised echo density (Abel and Huang, 2006), averaged over 50 - 300 ms:
     * the share of samples in each 23 ms window louder than the window's own
     * standard deviation, over what noise would have (erfc(1/sqrt 2) = 0.3173).
     * About 1 is a smooth wash; well under 1 is echoes heard one by one - the
     * flutter of a cheap spring.
     */
    private fun echoDensity(tail: FloatArray): Double {
        val window = 1024
        val values = ArrayList<Double>()
        var start = (0.05 * rate).toInt()
        while (start + window <= (0.3 * rate).toInt()) {
            var sum = 0.0
            for (i in start until start + window) sum += tail[i * 2].toDouble() * tail[i * 2]
            val deviation = sqrt(sum / window)
            var above = 0
            for (i in start until start + window) if (abs(tail[i * 2]) > deviation) above++
            values += above.toDouble() / window / 0.3173
            start += window / 2
        }
        return values.average()
    }

    @Test
    fun theTailIsAWashNotAFlutter() {
        val newTail = impulse(decay = 0.82f, seconds = 0.4)
        val oldTail = FloatArray(newTail.size).also { it[0] = 1f; it[1] = 1f; OldReverb().apply { enabled = true; mix = 1f; decay = 0.82f }.process(it) }
        val now = echoDensity(newTail)
        val before = echoDensity(oldTail)
        println("echo density over 50-300 ms (1 = smooth): %.2f now, %.2f before".format(now, before))
        assertTrue(now > before + 0.3 && now > 0.8, "now $now, before $before")
    }

    @Test
    fun itAddsARoomWithoutDrowningTheSong() {
        val input = noise(rate * 3)
        val output = input.copyOf()
        reverb(mix = 0.5f, decay = 0.82f).process(output)
        val ratio = rms(output, rate * 2, rate * 6) / rms(input, rate * 2, rate * 6)
        println("loudness at mix 0.5: %.2f x the dry sound".format(ratio))
        assertTrue(ratio in 0.7..1.5, "ratio $ratio")
    }

    @Test
    fun howTheAudioIsCutIntoBlocksMakesNoDifference() {
        val input = noise(20_000)
        val whole = input.copyOf()
        reverb(0.6f, 0.85f).process(whole)
        val chunked = input.copyOf()
        val room = reverb(0.6f, 0.85f)
        var at = 0
        val sizes = intArrayOf(1024, 333, 2048, 7, 1500)
        var n = 0
        while (at < 20_000) {
            val frames = minOf(sizes[n++ % sizes.size], 20_000 - at)
            val block = chunked.copyOfRange(at * 2, (at + frames) * 2)
            room.process(block)
            System.arraycopy(block, 0, chunked, at * 2, block.size)
            at += frames
        }
        for (i in whole.indices) assertTrue(abs(whole[i] - chunked[i]) < 1e-6f, "sample $i")
    }

    /**
     * Before and after, on a real song, for ears rather than asserts: written to
     * /tmp/lucense-reverb-ab when the song named in LUCENSE_AB_SONG exists.
     */
    @Test
    fun renderBeforeAndAfter() {
        val song = File(System.getenv("LUCENSE_AB_SONG") ?: "")
        assumeTrue(song.isFile, "set LUCENSE_AB_SONG to a song file to render the comparison")
        val out = File("/tmp/lucense-reverb-ab").apply { mkdirs() }
        val dry = Decoder.open(song, 60L * rate).use { decoder ->
            val frames = ArrayList<FloatArray>()
            var total = 0
            while (total < rate * 15) { val block = decoder.read(1024) ?: break; frames += block; total += block.size / 2 }
            val joined = FloatArray(total * 2); var at = 0
            for (block in frames) { System.arraycopy(block, 0, joined, at, block.size); at += block.size }
            joined
        }
        fun write(name: String, samples: FloatArray) {
            val bytes = ByteArray(samples.size * 2)
            for (i in samples.indices) {
                val v = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
                bytes[i * 2] = v.toByte(); bytes[i * 2 + 1] = (v shr 8).toByte()
            }
            val format = AudioFormat(rate.toFloat(), 16, 2, true, false)
            AudioSystem.write(AudioInputStream(ByteArrayInputStream(bytes), format, samples.size / 2L), AudioFileFormat.Type.WAVE, File(out, name))
        }
        fun old(mix: Float, decay: Float, input: FloatArray) = input.copyOf().also { OldReverb().apply { enabled = true; this.mix = mix; this.decay = decay }.process(it) }
        fun new(mix: Float, decay: Float, input: FloatArray) = input.copyOf().also { reverb(mix, decay).process(it) }
        // Slowed + reverb: speed 0.82, pitch -2, reverb 0.55 at room size 0.78.
        val slowed = EffectChain().run { speed = 0.82f; pitchSemitones = -2f; process(dry.copyOf()) }
        val presetDecay = 0.40f + 0.78f * 0.54f
        write("1 dry.wav", dry)
        write("2 default reverb - before.wav", old(0.35f, 0.82f, dry))
        write("3 default reverb - after.wav", new(0.35f, 0.82f, dry))
        write("4 slowed + reverb - before.wav", old(0.55f, presetDecay, slowed))
        write("5 slowed + reverb - after.wav", new(0.55f, presetDecay, slowed))
        println("A/B written to $out")
    }
}

/**
 * The reverb as it was before Freeverb replaced it, kept for the A/B render. Schroeder reverb: four parallel comb filters into two series all-pass stages.
 *
 * The classic arrangement, and the right size of tool here — a convolution
 * reverb would need impulse responses shipped with the app for a difference
 * most listeners would not pick out over a music player's output.
 */
private class OldReverb {

    @Volatile
    var enabled: Boolean = false

    @Volatile
    var mix: Float = 0.35f      // wet proportion

    @Volatile
    var decay: Float = 0.82f    // comb feedback

    private val combDelays = intArrayOf(1557, 1617, 1491, 1422)
    private val allPassDelays = intArrayOf(225, 556)

    private val combL = combDelays.map { FloatArray(it) }
    private val combR = combDelays.map { FloatArray(it + 23) }   // detune for width
    private val combIndexL = IntArray(combDelays.size)
    private val combIndexR = IntArray(combDelays.size)

    private val apL = allPassDelays.map { FloatArray(it) }
    private val apR = allPassDelays.map { FloatArray(it + 11) }
    private val apIndexL = IntArray(allPassDelays.size)
    private val apIndexR = IntArray(allPassDelays.size)

    fun reset() {
        (combL + combR + apL + apR).forEach { it.fill(0f) }
        combIndexL.fill(0); combIndexR.fill(0); apIndexL.fill(0); apIndexR.fill(0)
    }

    fun process(buffer: FloatArray) {
        if (!enabled || mix <= 0.001f) return
        val wet = mix.coerceIn(0f, 1f)
        val dry = 1f - wet * 0.5f      // gentle dry trim, not a full crossfade

        var i = 0
        while (i + 1 < buffer.size) {
            buffer[i] = dry * buffer[i] + wet * channel(
                buffer[i], combL, combIndexL, apL, apIndexL
            )
            buffer[i + 1] = dry * buffer[i + 1] + wet * channel(
                buffer[i + 1], combR, combIndexR, apR, apIndexR
            )
            i += 2
        }
    }

    private fun channel(
        input: Float,
        combs: List<FloatArray>,
        combIndex: IntArray,
        allPass: List<FloatArray>,
        apIndex: IntArray
    ): Float {
        var sum = 0f
        for (c in combs.indices) {
            val line = combs[c]
            val idx = combIndex[c]
            val delayed = line[idx]
            line[idx] = input + delayed * decay
            combIndex[c] = (idx + 1) % line.size
            sum += delayed
        }
        sum /= combs.size

        for (a in allPass.indices) {
            val line = allPass[a]
            val idx = apIndex[a]
            val delayed = line[idx]
            val out = delayed - sum * ALL_PASS_GAIN
            line[idx] = sum + delayed * ALL_PASS_GAIN
            apIndex[a] = (idx + 1) % line.size
            sum = out
        }
        return sum
    }

    private companion object {
        const val ALL_PASS_GAIN = 0.7f
    }
}

