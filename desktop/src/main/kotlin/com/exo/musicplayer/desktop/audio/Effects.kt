package com.exo.musicplayer.desktop.audio

import com.exo.musicplayer.data.audio.CharacterFx
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh
import java.util.concurrent.atomic.AtomicReference

/**
 * Time-stretching for "slowed" and "sped up" without moving the pitch.
 *
 * Built the way SoundTouch's time-domain stretcher is. The song is cut into
 * sequences of 45 to 100 ms, longer the slower it plays. Each is copied
 * untouched apart from a 12 ms crossfade into the next, and where the next one
 * starts is chosen within a 15 to 22 ms search by normalised cross-correlation
 * against the end of the one before, so the join lands in phase. Almost every
 * output sample is an original sample.
 *
 * The stretcher before this crossfaded continuously: every output sample was a
 * blend of two copies of the song a few milliseconds apart, spliced afresh 86
 * times a second, which comb-filters the sound slightly and was heard as a small
 * loss of clarity whenever a track was slowed down. Its search also compared
 * only 11 ms over a 6 ms range with an unnormalised product, so loud passages
 * beat well-matched ones and the bass could not be kept in phase.
 *
 * Operates on interleaved stereo with both channels in the match, so neither
 * side drifts. Buffers are kept and reused, so the playback thread makes no
 * garbage.
 */
class TimeStretcher(private val sampleRate: Int = 44_100) {

    @Volatile
    var factor: Float = 1f      // >1 plays faster

    private val overlap = frames(12.0)

    /** Input not yet consumed, interleaved stereo, and how much of it is valid. */
    private var pending = FloatArray(0)
    private var pendingLength = 0
    private var output = FloatArray(0)

    /** The last [overlap] frames of the previous sequence, to crossfade from. */
    private val previous = FloatArray(overlap * 2)
    private var primed = false
    private var skipCarry = 0.0

    /** Rises from 0 to 1 across the crossfade; with 1 - w it always sums to 1. */
    private val fade = FloatArray(overlap) { i ->
        (0.5 - 0.5 * cos(PI * (i + 0.5) / overlap)).toFloat()
    }

    private fun frames(ms: Double) = (sampleRate * ms / 1000.0).toInt()

    /** 100 ms sequences at half speed down to 45 ms at double, as SoundTouch scales them. */
    private fun sequenceFrames(f: Float) = frames(100.0 - (f.coerceIn(0.5f, 2f) - 0.5) / 1.5 * 55.0)

    /** How far each join may move to find its match: 22 ms at half speed, 15 ms at double. */
    private fun seekFrames(f: Float) = frames(22.0 - (f.coerceIn(0.5f, 2f) - 0.5) / 1.5 * 7.0)

    fun reset() {
        pendingLength = 0
        primed = false
        skipCarry = 0.0
        previous.fill(0f)
    }

    fun process(input: FloatArray): FloatArray {
        val f = factor
        if (f in 0.999f..1.001f) {
            // Leftovers from an earlier speed would be spliced into the next
            // time a speed is set; start that from clean instead.
            if (primed || pendingLength > 0) reset()
            return input
        }

        if (pending.size < pendingLength + input.size) {
            pending = pending.copyOf(maxOf(pendingLength + input.size, pending.size * 2))
        }
        System.arraycopy(input, 0, pending, pendingLength, input.size)
        pendingLength += input.size

        val sequence = sequenceFrames(f)
        val seek = seekFrames(f)
        val body = sequence - 2 * overlap
        val advance = f * (sequence - overlap)

        var written = 0
        var read = 0
        while ((read + seek + sequence) * 2 <= pendingLength) {
            val start = read + if (primed) bestOffset(read, seek) else 0
            val needed = written + (sequence - overlap) * 2
            if (output.size < needed) output = output.copyOf(maxOf(needed, output.size * 2))

            // Crossfade from the end of the previous sequence into this one.
            for (i in 0 until overlap) {
                val w = if (primed) fade[i] else 1f
                val at = (start + i) * 2
                output[written++] = previous[i * 2] * (1f - w) + pending[at] * w
                output[written++] = previous[i * 2 + 1] * (1f - w) + pending[at + 1] * w
            }
            // The middle goes through untouched.
            System.arraycopy(pending, (start + overlap) * 2, output, written, body * 2)
            written += body * 2
            // Its last stretch is held back, to fade from next time.
            System.arraycopy(pending, (start + sequence - overlap) * 2, previous, 0, overlap * 2)
            primed = true

            skipCarry += advance
            val skip = skipCarry.toInt()
            skipCarry -= skip
            read += skip
        }

        if (read > 0) {
            val keepFrom = (read * 2).coerceAtMost(pendingLength)
            System.arraycopy(pending, keepFrom, pending, 0, pendingLength - keepFrom)
            pendingLength -= keepFrom
        }
        return output.copyOf(written)
    }

    /**
     * Where, within [seek] frames of [read], the next sequence's opening best
     * continues the end of the previous one: every fourth offset first, then
     * each frame around the best of those.
     */
    private fun bestOffset(read: Int, seek: Int): Int {
        var best = 0
        var bestScore = Double.NEGATIVE_INFINITY
        var offset = 0
        while (offset < seek) {
            val score = similarity(read + offset)
            if (score > bestScore) {
                bestScore = score
                best = offset
            }
            offset += 4
        }
        val coarse = best
        for (fine in (coarse - 3)..(coarse + 3)) {
            if (fine == coarse || fine < 0 || fine >= seek) continue
            val score = similarity(read + fine)
            if (score > bestScore) {
                bestScore = score
                best = fine
            }
        }
        return best
    }

    /**
     * Correlation of the held-back end with the input at [start], both channels,
     * normalised by the input's energy so a loud stretch doesn't win over a
     * matching one.
     */
    private fun similarity(start: Int): Double {
        var correlation = 0.0
        var energy = 0.0
        val from = start * 2
        for (i in 0 until overlap * 2) {
            val sample = pending[from + i].toDouble()
            correlation += previous[i] * sample
            energy += sample * sample
        }
        return correlation / sqrt(energy + 1e-12)
    }
}

/**
 * Reverb: Jezar's Freeverb, with a pre-delay and a high-passed input.
 *
 * The reverb before this was four comb filters into two all-passes, with no
 * damping. Every reflection kept all of its treble, so the tail rang like a
 * metal pipe; four combs left the echoes sparse enough to hear one by one, the
 * flutter of a cheap spring; and the all-passes, at 0.7, rang on top of that.
 * Its wet signal was then added over a dry one barely turned down, so it was
 * loud and boomy as well. Heard as "cursed", fairly.
 *
 * Freeverb is eight combs a side, each with a low-pass in its feedback, so the
 * room soaks up the highs as the sound dies away, the way a real room does;
 * then four all-passes at 0.5, which smear the echoes into a smooth wash. The
 * right side's delays are 23 samples longer than the left's, which is what
 * makes it wide. Ahead of it: 20 ms of pre-delay, so the first reflections
 * don't smudge the start of every note; a high-pass at about 180 Hz, so the
 * bass doesn't swell into a boom under everything; and four short all-passes
 * (Dattorro's input diffusers) that smear each sound before it reaches the
 * room, so the tail is a smooth wash from the start. Measured as echo density
 * over the first 300 ms, 1 being smooth: the old reverb 0.31, this one 0.95.
 *
 * [decay], 0.40 to 0.94 on the Room size slider (the presets' room sizes are
 * mapped onto the same range), sets how long the tail is: under a second at the
 * bottom, about six at the top - long, but it always dies away. [mix] brings
 * the room in and the dry sound down to half, never further, so the song never
 * disappears behind its own echo. Both glide to a new value over about 20 ms,
 * so a slider being dragged doesn't crackle.
 *
 * Interleaved stereo, processed in place, sample by sample with all its state
 * kept between calls, so how the playback thread cuts the audio into blocks
 * makes no difference to the sound. Nothing is allocated while playing.
 */
class Reverb(sampleRate: Int = 44_100) {

    @Volatile
    var enabled: Boolean = false

    /** How much of the room is heard, 0..1. */
    @Volatile
    var mix: Float = 0.35f

    /** The Room size slider's value, 0.40..0.94; see [feedbackFor]. */
    @Volatile
    var decay: Float = 0.82f

    /** The tunings are Freeverb's, which are in samples at 44.1 kHz. */
    private val scale = sampleRate / 44_100.0
    private fun tuned(samples: Int) = (samples * scale).roundToInt().coerceAtLeast(1)

    private val combLeft = Array(COMBS.size) { FloatArray(tuned(COMBS[it])) }
    private val combRight = Array(COMBS.size) { FloatArray(tuned(COMBS[it] + SPREAD)) }
    private val combAtLeft = IntArray(COMBS.size)
    private val combAtRight = IntArray(COMBS.size)
    /** Each comb's damping low-pass. */
    private val combLowLeft = FloatArray(COMBS.size)
    private val combLowRight = FloatArray(COMBS.size)

    private val passLeft = Array(PASSES.size) { FloatArray(tuned(PASSES[it])) }
    private val passRight = Array(PASSES.size) { FloatArray(tuned(PASSES[it] + SPREAD)) }
    private val passAtLeft = IntArray(PASSES.size)
    private val passAtRight = IntArray(PASSES.size)

    private val preDelay = FloatArray(tuned(PRE_DELAY_SAMPLES))
    private var preDelayAt = 0

    private val diffusers = Array(DIFFUSERS.size) { FloatArray(tuned(DIFFUSERS[it])) }
    private val diffuserAt = IntArray(DIFFUSERS.size)

    /** One-pole high-pass on what goes into the room. */
    private val highPass = (1.0 / (1.0 + 2.0 * PI * HIGH_PASS_HZ / sampleRate)).toFloat()
    private var highPassIn = 0f
    private var highPassOut = 0f

    /** Per-sample step towards a new setting: about 20 ms to get there. */
    private val glide = (1.0 - exp(-1.0 / (GLIDE_SECONDS * sampleRate))).toFloat()
    private var feedback = 0f
    private var wet = 0f
    private var dry = 1f
    /** False until the first block after a reset, which starts at its settings rather than gliding in. */
    private var settled = false

    /** Whether the last block went through; a room switched back on starts empty. */
    private var wasOn = false

    fun reset() {
        combLeft.forEach { it.fill(0f) }
        combRight.forEach { it.fill(0f) }
        passLeft.forEach { it.fill(0f) }
        passRight.forEach { it.fill(0f) }
        preDelay.fill(0f)
        diffusers.forEach { it.fill(0f) }
        diffuserAt.fill(0)
        combAtLeft.fill(0); combAtRight.fill(0)
        combLowLeft.fill(0f); combLowRight.fill(0f)
        passAtLeft.fill(0); passAtRight.fill(0)
        preDelayAt = 0
        highPassIn = 0f
        highPassOut = 0f
        settled = false
    }

    fun process(buffer: FloatArray) {
        if (!enabled || mix <= 0.001f) {
            wasOn = false
            return
        }
        if (!wasOn) {
            // Whatever was left in the room from before it was switched off is
            // stale by now; it would play as a burst of the wrong moment.
            reset()
            wasOn = true
        }
        val amount = mix.coerceIn(0f, 1f)
        val targetFeedback = feedbackFor(decay)
        val targetWet = amount * WET_SCALE
        val targetDry = 1f - DRY_DIP * amount
        if (!settled) {
            feedback = targetFeedback
            wet = targetWet
            dry = targetDry
            settled = true
        }

        var i = 0
        while (i + 1 < buffer.size) {
            feedback += (targetFeedback - feedback) * glide
            wet += (targetWet - wet) * glide
            dry += (targetDry - dry) * glide

            val left = buffer[i]
            val right = buffer[i + 1]

            // One room, fed in mono: high-passed, then pre-delayed.
            val mono = (left + right) * INPUT_GAIN
            highPassOut = highPass * (highPassOut + mono - highPassIn)
            highPassIn = mono
            if (highPassOut > -DENORMAL && highPassOut < DENORMAL) highPassOut = 0f
            var delayed = preDelay[preDelayAt]
            preDelay[preDelayAt] = highPassOut
            preDelayAt = if (preDelayAt + 1 == preDelay.size) 0 else preDelayAt + 1
            for (d in DIFFUSERS.indices) delayed = diffuse(diffusers[d], diffuserAt, d, delayed, DIFFUSION[d])

            var roomLeft = 0f
            var roomRight = 0f
            for (c in COMBS.indices) {
                roomLeft += comb(combLeft[c], combAtLeft, combLowLeft, c, delayed, feedback)
                roomRight += comb(combRight[c], combAtRight, combLowRight, c, delayed, feedback)
            }
            for (p in PASSES.indices) {
                roomLeft = allPass(passLeft[p], passAtLeft, p, roomLeft)
                roomRight = allPass(passRight[p], passAtRight, p, roomRight)
            }

            buffer[i] = left * dry + roomLeft * wet
            buffer[i + 1] = right * dry + roomRight * wet
            i += 2
        }
    }

    /** One comb: a delay whose output is low-passed and fed back in. */
    private fun comb(line: FloatArray, at: IntArray, low: FloatArray, c: Int, input: Float, feedback: Float): Float {
        val index = at[c]
        val out = line[index]
        var damped = out * (1f - DAMPING) + low[c] * DAMPING
        if (damped > -DENORMAL && damped < DENORMAL) damped = 0f
        low[c] = damped
        line[index] = input + damped * feedback
        at[c] = if (index + 1 == line.size) 0 else index + 1
        return out
    }

    /** A true all-pass (Schroeder's): every frequency at the same level, only smeared in time. */
    private fun diffuse(line: FloatArray, at: IntArray, d: Int, input: Float, gain: Float): Float {
        val index = at[d]
        val held = line[index]
        var v = input + gain * held
        if (v > -DENORMAL && v < DENORMAL) v = 0f
        line[index] = v
        at[d] = if (index + 1 == line.size) 0 else index + 1
        return held - gain * v
    }

    /** One of Freeverb's all-passes, after the combs: smears the echoes into each other. */
    private fun allPass(line: FloatArray, at: IntArray, p: Int, input: Float): Float {
        val index = at[p]
        val held = line[index]
        var next = input + held * PASS_FEEDBACK
        if (next > -DENORMAL && next < DENORMAL) next = 0f
        line[index] = next
        at[p] = if (index + 1 == line.size) 0 else index + 1
        return held - input
    }

    companion object {
        /** Freeverb's comb and all-pass lengths, in samples at 44.1 kHz. */
        private val COMBS = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
        private val PASSES = intArrayOf(556, 441, 341, 225)
        private const val SPREAD = 23
        /** Dattorro's input diffusers, from his 29.8 kHz figures, and their gains. */
        private val DIFFUSERS = intArrayOf(210, 159, 562, 410)
        private val DIFFUSION = floatArrayOf(0.75f, 0.75f, 0.625f, 0.625f)
        private const val PASS_FEEDBACK = 0.5f
        /** Freeverb's fixed input gain: eight combs summed would otherwise be far too hot. */
        private const val INPUT_GAIN = 0.015f
        /** How much treble each pass round the room loses; Freeverb's 0.5 on its own scale, a touch darker. */
        private const val DAMPING = 0.24f
        private const val PRE_DELAY_SAMPLES = 882 // 20 ms
        private const val HIGH_PASS_HZ = 180.0
        private const val GLIDE_SECONDS = 0.02
        /** The room at full [mix], against a dry sound at 1. */
        private const val WET_SCALE = 1.0f
        /** How far the dry sound comes down at full [mix]: to half, and no further. */
        private const val DRY_DIP = 0.5f
        /** Below this a value is as good as silence, and is made exactly that: denormals are slow. */
        private const val DENORMAL = 1e-15f

        /**
         * The comb feedback for a Room size of [decay]. The slider's 0.40..0.94
         * becomes Freeverb's room size 0.20..0.95, and that its feedback
         * 0.756..0.966: about 0.8 s of tail at the bottom, 6 s at the top.
         */
        internal fun feedbackFor(decay: Float): Float {
            val along = ((decay - 0.40f) / 0.54f).coerceIn(0f, 1f)
            val room = 0.20f + along * 0.75f
            return room * 0.28f + 0.70f
        }
    }
}

/**
 * Ten-band graphic equalizer: peaking filters an octave apart from 31 Hz to
 * 16 kHz, +-12 dB each.
 *
 * Gains arrive from the UI thread and are picked up at the start of the next
 * buffer, where the filters are redesigned; their memory is kept, so moving a
 * band while music plays doesn't click. A band at 0 dB is skipped. Boosts can
 * push peaks past full scale, so anything above the knee eases into a soft
 * ceiling instead of clipping hard.
 */
class Equalizer(private val sampleRate: Double = 44_100.0) {

    @Volatile
    var enabled: Boolean = false

    private val incoming = AtomicReference<FloatArray?>(null)
    private var gains = FloatArray(BANDS.size)
    private val b0 = DoubleArray(BANDS.size)
    private val b1 = DoubleArray(BANDS.size)
    private val b2 = DoubleArray(BANDS.size)
    private val a1 = DoubleArray(BANDS.size)
    private val a2 = DoubleArray(BANDS.size)

    // Transposed direct form II state: two values per band per channel.
    private val z1 = DoubleArray(BANDS.size * 2)
    private val z2 = DoubleArray(BANDS.size * 2)

    init {
        design()
    }

    fun setGains(db: List<Float>) {
        incoming.set(FloatArray(BANDS.size) { db.getOrElse(it) { 0f }.coerceIn(-12f, 12f) })
    }

    fun reset() {
        z1.fill(0.0)
        z2.fill(0.0)
    }

    fun process(buffer: FloatArray) {
        incoming.getAndSet(null)?.let {
            gains = it
            design()
        }
        if (!enabled) return
        var boosted = false
        for (band in BANDS.indices) {
            if (abs(gains[band]) < 0.05f) continue
            if (gains[band] > 0f) boosted = true
            val c0 = b0[band]
            val c1 = b1[band]
            val c2 = b2[band]
            val d1 = a1[band]
            val d2 = a2[band]
            var i = 0
            while (i + 1 < buffer.size) {
                for (channel in 0..1) {
                    val k = band * 2 + channel
                    val x = buffer[i + channel].toDouble()
                    val y = c0 * x + z1[k]
                    z1[k] = c1 * x - d1 * y + z2[k]
                    z2[k] = c2 * x - d2 * y
                    buffer[i + channel] = y.toFloat()
                }
                i += 2
            }
        }
        if (boosted) {
            for (i in buffer.indices) {
                val x = buffer[i]
                val magnitude = abs(x)
                if (magnitude > KNEE) {
                    buffer[i] = sign(x) * (KNEE + (1f - KNEE) * tanh((magnitude - KNEE) / (1f - KNEE)))
                }
            }
        }
    }

    /** RBJ cookbook peaking filters. */
    private fun design() {
        for (band in BANDS.indices) {
            val amplitude = 10.0.pow(gains[band] / 40.0)
            val w0 = 2.0 * PI * BANDS[band] / sampleRate
            val alpha = sin(w0) / (2.0 * Q)
            val cosine = cos(w0)
            val a0 = 1.0 + alpha / amplitude
            b0[band] = (1.0 + alpha * amplitude) / a0
            b1[band] = -2.0 * cosine / a0
            b2[band] = (1.0 - alpha * amplitude) / a0
            a1[band] = -2.0 * cosine / a0
            a2[band] = (1.0 - alpha / amplitude) / a0
        }
    }

    companion object {
        val BANDS = doubleArrayOf(31.0, 62.0, 125.0, 250.0, 500.0, 1_000.0, 2_000.0, 4_000.0, 8_000.0, 16_000.0)
        private const val Q = 1.41
        private const val KNEE = 0.9f
    }
}

/**
 * Speed, pitch and reverb, composed the same way as the Android build: three
 * independent axes that stack.
 *
 * Pitch is a resample (which moves tempo too), then the stretcher restores the
 * requested tempo. So `resample(p)` followed by `stretch(speed / p)` yields
 * tempo = speed and pitch = p, with either adjustable on its own.
 */
class EffectChain(private val sampleRate: Int = 44_100) {

    val stretcher = TimeStretcher(sampleRate)
    val reverb = Reverb(sampleRate)
    val equalizer = Equalizer(sampleRate.toDouble())

    /**
     * Continuous across buffers. The pitch shift used to resample each buffer on
     * its own with the fingerprinting filter, which was both slow and seamed at
     * every buffer boundary.
     */
    private val pitchResampler = StreamingResampler(2)
    private var pitchActive = false

    @Volatile
    var speed: Float = 1f

    @Volatile
    var pitchSemitones: Float = 0f

    @Volatile
    var volume: Float = 1f

    /** Distortion, lo-fi, tremolo, 8D and echo; see CharacterFx. */
    val character = CharacterFx()

    fun reset() {
        stretcher.reset()
        reverb.reset()
        equalizer.reset()
        pitchResampler.reset()
        pitchActive = false
        character.reset()
    }

    fun process(input: FloatArray): FloatArray {
        val pitch = 2f.pow(pitchSemitones / 12f)
        var buffer = input

        if (pitch !in 0.999f..1.001f) {
            // History from an earlier stretch of pitched audio is stale by now.
            if (!pitchActive) {
                pitchResampler.reset()
                pitchActive = true
            }
            buffer = pitchResampler.process(buffer, buffer.size / 2, pitch.toDouble())
        } else {
            pitchActive = false
        }
        stretcher.factor = speed / pitch
        buffer = stretcher.process(buffer)

        equalizer.process(buffer)
        // After the EQ, so a boosted band drives harder, and before the
        // reverb, so the room is of the distorted sound, as with an amp.
        character.process(buffer, 2, sampleRate)
        reverb.process(buffer)

        if (volume !in 0.999f..1.001f) {
            for (i in buffer.indices) buffer[i] *= volume
        }
        return buffer
    }
}
