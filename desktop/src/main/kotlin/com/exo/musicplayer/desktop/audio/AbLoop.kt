package com.exo.musicplayer.desktop.audio

/**
 * An A-B loop: the stretch between two moments of a song, played round and round.
 *
 * Going back to A by seeking would mean reopening the file - an MP3 can't be read
 * backwards - and decoding everything up to A again, every time round: a gap at
 * each turn, longer the later in the song the loop is. So the stretch is kept in
 * memory instead. From the moment A is set, every block read from the file is
 * recorded; when B is set the recording is trimmed to end there, and from then
 * on the blocks come from memory, seamless, however long the song.
 *
 * A and B are the moments being *heard*, which trail what has been decoded by
 * whatever is still queued in the output. So the last second decoded is always
 * kept ([HISTORY_SECONDS]), and setting A starts the recording from the heard
 * moment, out of that.
 *
 * A loop longer than [MAX_SECONDS] isn't kept in memory - [fromMemory] is false -
 * and the engine goes back to A by seeking instead, gap and all.
 *
 * Frames are interleaved stereo floats, as they come from [Decoder], before any
 * gain or effect. Used from the playback thread only.
 */
internal class AbLoop(private val rate: Int = 44_100) {

    enum class State { OFF, RECORDING, LOOPING }

    var state = State.OFF
        private set

    /** A, as a frame of the source; -1 when unset. */
    var startFrame = -1L
        private set

    /** B, as a frame of the source; -1 until it is set. */
    var endFrame = -1L
        private set

    /** Whether the loop plays from memory, rather than by seeking back to A. */
    var fromMemory = false
        private set

    private val historyFrames = HISTORY_SECONDS * rate
    private val history = FloatArray(historyFrames * 2)
    /** Where the next history frame goes, and how many it holds. */
    private var historyHead = 0
    private var historyFill = 0
    /** The source frame just after the newest frame in [history]. */
    private var historyEnd = 0L

    private var cache = FloatArray(0)
    private var cacheFrames = 0
    private var overflowed = false
    private val maxFrames = MAX_SECONDS * rate

    /** Where in the loop playback is, when [State.LOOPING] from memory. */
    private var at = 0

    /** The source frame playback is at inside the loop. */
    val positionFrame: Long get() = startFrame + at

    /** Every block read from the file, as read, with the source frame just after it. */
    fun heard(block: FloatArray, endFrameOfBlock: Long) {
        val frames = block.size / 2
        // The newest second, as a ring.
        var i = 0
        while (i < frames) {
            val chunk = minOf(frames - i, historyFrames - historyHead)
            System.arraycopy(block, i * 2, history, historyHead * 2, chunk * 2)
            historyHead = (historyHead + chunk) % historyFrames
            i += chunk
        }
        historyFill = minOf(historyFrames, historyFill + frames)
        historyEnd = endFrameOfBlock
        if (state == State.RECORDING) record(block, 0, frames)
    }

    /** A, at the source frame being heard now. */
    fun open(frame: Long) {
        clear()
        state = State.RECORDING
        startFrame = frame.coerceAtLeast(0L)
        // What lies between A and what has been decoded is in the history.
        val behind = historyEnd - startFrame
        if (behind < 0 || behind > historyFill) {
            overflowed = true
            return
        }
        val count = behind.toInt()
        var from = (historyHead - count + historyFrames) % historyFrames
        var left = count
        while (left > 0) {
            val chunk = minOf(left, historyFrames - from)
            record(history, from, chunk)
            from = (from + chunk) % historyFrames
            left -= chunk
        }
    }

    /**
     * B, at the source frame being heard now, and round from A. False, and
     * nothing changes, when there is no A, or B is within a tenth of a second
     * of it - a double click rather than a loop.
     */
    fun close(frame: Long): Boolean {
        if (state != State.RECORDING || frame < startFrame + rate / 10) return false
        endFrame = frame
        val length = frame - startFrame
        fromMemory = !overflowed && length <= cacheFrames
        // Recorded past B by whatever was still queued: that part isn't the loop.
        if (fromMemory) cacheFrames = length.toInt()
        at = 0
        state = State.LOOPING
        return true
    }

    fun clear() {
        state = State.OFF
        startFrame = -1L
        endFrame = -1L
        cacheFrames = 0
        overflowed = false
        fromMemory = false
        at = 0
    }

    /** Up to [frames] frames of the loop from memory, going round from B to A. */
    fun next(frames: Int): FloatArray {
        val count = minOf(frames, cacheFrames - at)
        val out = cache.copyOfRange(at * 2, (at + count) * 2)
        at += count
        if (at >= cacheFrames) at = 0
        return out
    }

    /** Moves inside a loop playing from memory; false when [frame] is outside it. */
    fun seek(frame: Long): Boolean {
        if (state != State.LOOPING || !fromMemory || frame < startFrame || frame >= endFrame) return false
        at = (frame - startFrame).toInt()
        return true
    }

    private fun record(source: FloatArray, fromFrame: Int, frames: Int) {
        if (overflowed) return
        if (cacheFrames + frames > maxFrames) {
            // Too long to keep: the loop will seek instead, and the memory goes.
            overflowed = true
            cache = FloatArray(0)
            return
        }
        val needed = (cacheFrames + frames) * 2
        if (cache.size < needed) cache = cache.copyOf(maxOf(needed, cache.size * 2, rate * 2))
        System.arraycopy(source, fromFrame * 2, cache, cacheFrames * 2, frames * 2)
        cacheFrames += frames
    }

    companion object {
        /** How much of what was decoded last is kept, to start a recording from what is heard. */
        const val HISTORY_SECONDS = 1

        /** The longest loop kept in memory: two minutes of stereo floats is 42 MB. */
        const val MAX_SECONDS = 120
    }
}
