package com.exo.musicplayer.desktop.audio

import com.exo.musicplayer.desktop.system.Platform

/**
 * How much audio the playback thread keeps queued in the output line, ahead of
 * the speakers - which is how late anything done to the sound is heard.
 *
 * The effects, the equalizer and the volume are all applied before the line, so
 * a slider moved now is only heard once the audio already queued has played.
 * The engine used to let its blocking write fill the line's whole 186 ms buffer,
 * and picked a change up once per 93 ms block: measured on Linux, a moved slider
 * took around a quarter of a second to be heard, before PipeWire and a Bluetooth
 * link added their own.
 *
 * Paced instead, the playback thread waits until the queue is down to
 * [targetFrames] before it decodes and processes the next block, so that block
 * carries the newest settings and is heard soon after. The buffer stays 186 ms
 * deep as headroom. If a write ever finds the queue nearly empty - an underrun
 * about to happen, or one that just has - the target steps up by [STEP_MS] and
 * stays up for the session, and says so on stderr, so a slow machine settles at
 * what it needs instead of crackling.
 *
 * Linux starts at [LINUX_START_MS]: on PipeWire, 40, 60 and 80 ms all ran with
 * no underruns under a load average of 5. Other systems keep the old behaviour,
 * filling the buffer, until pacing has been measured there - an underrun on
 * DirectSound repeats the buffer rather than going quiet.
 *
 * `-Dlucense.audio.latencyMs=N` starts the pacing at N ms on any system, and
 * `-Dlucense.audio.latencyMs=fill` turns it off.
 *
 * Used from the playback thread only, apart from reading [targetFrames].
 */
internal class OutputPacing(
    private val rate: Int,
    /** Where the target starts, in ms; null to fill the line, as before pacing. */
    private val startMs: Int?,
    private val log: (String) -> Unit = { System.err.println("Lucense: $it") }
) {
    /** Not pacing: the blocking write alone paces playback, keeping the line full. */
    val filling: Boolean get() = startMs == null

    /** How much is kept queued, in frames. */
    @Volatile var targetFrames: Int = startMs?.let { it * rate / 1000 } ?: Int.MAX_VALUE
        private set

    /** Times the queue was found nearly empty while being kept topped up. */
    @Volatile var underruns: Int = 0
        private set

    /** The line's capacity less a block: the highest the target can usefully go. */
    private var ceilingFrames = Int.MAX_VALUE

    /** Whether the queue was being kept topped up - false right after anything that empties it. */
    private var primed = false

    private var described = false

    /** Called whenever the lines are (re)opened, with the primary line's capacity. */
    fun fit(capacityFrames: Int, blockFrames: Int) {
        ceilingFrames = (capacityFrames - blockFrames).coerceAtLeast(blockFrames)
        if (!filling) targetFrames = targetFrames.coerceAtMost(ceilingFrames)
        primed = false
        if (!described) {
            described = true
            log(
                "audio output holds ${capacityFrames * 1000L / rate} ms; " +
                    if (filling) "keeping it full" else "keeping ${targetFrames * 1000L / rate} ms queued"
            )
        }
    }

    /** The queue is about to run out for a good reason - a start, a seek, a pause - so that isn't an underrun. */
    fun restart() {
        primed = false
    }

    /** How long to wait before making the next block, with [queuedFrames] still in the line; 0 to go now. */
    fun waitNanos(queuedFrames: Int): Long {
        if (filling) return 0L
        val excess = queuedFrames - targetFrames
        return if (excess <= 0) 0L else excess * 1_000_000_000L / rate
    }

    /** Called just before each write with what the line still holds; raises the target if it nearly ran out. */
    fun beforeWrite(queuedFrames: Int) {
        if (filling) return
        if (primed && queuedFrames < NEAR_EMPTY_FRAMES && targetFrames < ceilingFrames) {
            underruns++
            targetFrames = (targetFrames + STEP_MS * rate / 1000).coerceAtMost(ceilingFrames)
            log(
                "audio queue ran nearly dry ($queuedFrames frames left); " +
                    "keeping ${targetFrames * 1000L / rate} ms queued from now on"
            )
        }
        primed = true
    }

    companion object {
        const val LINUX_START_MS = 60
        const val STEP_MS = 20

        /** About 6 ms: less than this left in the line counts as running dry. */
        const val NEAR_EMPTY_FRAMES = 256

        /** Pacing as this system and `-Dlucense.audio.latencyMs` call for. */
        fun forThisSystem(rate: Int): OutputPacing =
            OutputPacing(rate, startMs(System.getProperty("lucense.audio.latencyMs"), Platform.isLinux))

        /** The starting target for [property] on this kind of system; null means fill the line. */
        internal fun startMs(property: String?, linux: Boolean): Int? {
            val default = if (linux) LINUX_START_MS else null
            val asked = property?.trim()?.lowercase()
            return when {
                asked.isNullOrEmpty() -> default
                asked == "fill" || asked == "0" -> null
                else -> asked.toIntOrNull()?.coerceIn(10, 2_000) ?: default
            }
        }
    }
}
