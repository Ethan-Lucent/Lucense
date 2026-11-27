package com.exo.musicplayer.desktop

import com.exo.musicplayer.desktop.audio.Volume
import com.exo.musicplayer.desktop.audio.rampGain
import com.exo.musicplayer.desktop.audio.scalePcm16
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PreviewVolumeTest {

    /** Interleaved little-endian 16-bit samples, the way ffmpeg writes them. */
    private fun pcm(vararg samples: Int): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s ->
            out[i * 2] = s.toByte()
            out[i * 2 + 1] = (s shr 8).toByte()
        }
        return out
    }

    private fun samples(bytes: ByteArray, count: Int = bytes.size / 2): List<Int> =
        (0 until count).map { (bytes[it * 2].toInt() and 0xFF) or (bytes[it * 2 + 1].toInt() shl 8) }

    @Test
    fun unityGainLeavesBytesIdentical() {
        val original = pcm(1000, -1000, 32767, -32768, 5, -5)
        val copy = original.copyOf()
        scalePcm16(copy, copy.size, 1f)
        assertContentEquals(original, copy)
    }

    @Test
    fun halfGainHalvesBothChannelsPositiveAndNegative() {
        val b = pcm(1000, -2000, -1000, 2000)
        scalePcm16(b, b.size, 0.5f)
        assertEquals(listOf(500, -1000, -500, 1000), samples(b))
    }

    @Test
    fun zeroGainIsSilence() {
        val b = pcm(1000, -2000, 32767, -32768)
        scalePcm16(b, b.size, 0f)
        assertContentEquals(ByteArray(8), b)
    }

    @Test
    fun gainAboveOneClampsInsteadOfWrapping() {
        val b = pcm(20000, -20000, 100, -100)
        scalePcm16(b, b.size, 2f)
        assertEquals(listOf(32767, -32768, 200, -200), samples(b))
        val c = pcm(32767, -32768)
        scalePcm16(c, c.size, 4f)
        assertEquals(listOf(32767, -32768), samples(c))
    }

    @Test
    fun onlyTheFirstLengthBytesChange() {
        val b = pcm(1000, 1000, 1000, 1000)
        scalePcm16(b, 4, 0.5f)
        assertEquals(listOf(500, 500, 1000, 1000), samples(b))
    }

    @Test
    fun oddLengthDoesNotCrashAndLeavesTheHalfSample() {
        val b = pcm(1000, 1000, 1000)
        val last = b[5]
        scalePcm16(b, 5, 0.5f)
        assertEquals(listOf(500, 500), samples(b, 2))
        assertEquals(last, b[5])
        scalePcm16(b, 0, 0.5f)
        scalePcm16(b, 1, 0.5f)
    }

    @Test
    fun rampSlidesFromOldGainToNewAcrossTheBuffer() {
        val frames = 100
        val b = ByteArray(frames * 4)
        for (f in 0 until frames) {
            val s = pcm(10000, 10000)
            System.arraycopy(s, 0, b, f * 4, 4)
        }
        scalePcm16(b, b.size, 0f, 1f)
        val left = (0 until frames).map { samples(b)[it * 2] }
        val right = (0 until frames).map { samples(b)[it * 2 + 1] }
        assertEquals(left, right, "both channels follow the same ramp")
        assertTrue(left.zipWithNext().all { (a, c) -> c <= a }, "monotonic down")
        assertTrue(left.first() in 9000..10000, "starts near the old level")
        assertEquals(0, left.last(), "ends at the new gain")
    }

    @Test
    fun rampGainGoesMonotonicallyFromToTo() {
        val up = (0 until 50).map { rampGain(0.2f, 0.8f, it, 50) }
        assertTrue(up.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(0.8f, up.last(), 1e-6f)
        val down = (0 until 50).map { rampGain(1f, 0f, it, 50) }
        assertTrue(down.zipWithNext().all { (a, b) -> b <= a })
        assertEquals(0f, down.last(), 1e-6f)
    }

    @Test
    fun sliderBottomIsSilentAndTopIsUnity() {
        assertEquals(0f, Volume.gainFor(0f))
        assertEquals(1f, Volume.gainFor(1f), 1e-6f)
    }
}
