package com.exo.musicplayer.desktop

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.dp
import com.exo.musicplayer.desktop.data.AppDirs
import com.exo.musicplayer.desktop.data.CoverThumbs
import com.exo.musicplayer.desktop.data.Covers
import com.exo.musicplayer.desktop.data.Thumbnails
import com.exo.musicplayer.desktop.library.DesktopTrack
import com.exo.musicplayer.desktop.ui.Glass
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Color
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Covers kept on disk at two sizes, and brought into memory ahead of the
 * lists: what makes a fast scroll show covers rather than placeholders.
 */
class CoversTest {

    private fun folder(): File = Files.createTempDirectory("lucense-covers-test").toFile().apply { deleteOnExit() }

    /** A plain picture, [width] by [height], in [format]. */
    private fun picture(
        width: Int,
        height: Int,
        colour: Int = Color.makeRGB(200, 40, 60),
        format: EncodedImageFormat = EncodedImageFormat.JPEG
    ): ByteArray {
        val surface = Surface.makeRasterN32Premul(width, height)
        try {
            surface.canvas.drawRect(Rect.makeWH(width.toFloat(), height.toFloat()), Paint().apply { color = colour })
            return surface.makeImageSnapshot().use { checkNotNull(it.encodeToData(format, 95)).bytes }
        } finally {
            surface.close()
        }
    }

    /** A "song" Lucense can't read tags from, so its cover can only come from beside it. */
    private fun song(dir: File, name: String): DesktopTrack {
        val file = File(dir, name).apply { writeBytes(ByteArray(2048) { (it % 251).toByte() }) }
        return DesktopTrack(file, name, null, null, 0L, null, null, file.length())
    }

    private val store get() = CoverThumbs(File(AppDirs.cache, "covers"))

    @Test
    fun `a song's key changes when the song does`() {
        val dir = folder()
        val thumbs = CoverThumbs(File(dir, "thumbs"))
        val file = File(dir, "a.mp3").apply { writeText("one") }
        val before = assertNotNull(thumbs.keyOf(file))
        assertEquals(before, thumbs.keyOf(file))
        file.writeText("one, and more")
        file.setLastModified(file.lastModified() + 5_000)
        assertNotEquals(before, thumbs.keyOf(file))
        assertNull(thumbs.keyOf(File(dir, "missing.mp3")))
    }

    @Test
    fun `kept covers come back as written, and a song with none is marked`() {
        val dir = folder()
        val thumbs = CoverThumbs(File(dir, "thumbs"))
        val sizes = CoverThumbs.Sizes(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6, 7))
        thumbs.write("abc", sizes)
        assertContentEquals(sizes.small, thumbs.read("abc", CoverThumbs.Size.SMALL))
        assertContentEquals(sizes.large, thumbs.read("abc", CoverThumbs.Size.LARGE))
        assertTrue(thumbs.has("abc"))

        assertNull(thumbs.noneStamp("def"))
        thumbs.markNone("def", 1234L)
        assertEquals(1234L, thumbs.noneStamp("def"))
        // A cover found after all replaces the mark.
        thumbs.write("def", sizes)
        assertNull(thumbs.noneStamp("def"))

        thumbs.forget("abc")
        assertFalse(thumbs.has("abc"))
        assertNull(thumbs.read("abc", CoverThumbs.Size.LARGE))
    }

    @Test
    fun `pruning keeps what is asked for and clears the rest`() {
        val dir = folder()
        val thumbs = CoverThumbs(File(dir, "thumbs"))
        val sizes = CoverThumbs.Sizes(byteArrayOf(1), byteArrayOf(2))
        thumbs.write("keep", sizes)
        thumbs.write("gone", sizes)
        thumbs.markNone("alsogone", 1L)
        val leftover = File(dir, "thumbs/keep.s.17.tmp").apply { writeText("half") }
        leftover.setLastModified(System.currentTimeMillis() - 600_000)

        assertEquals(4, thumbs.prune(setOf("keep")))
        assertTrue(thumbs.has("keep"))
        assertNotNull(thumbs.read("keep", CoverThumbs.Size.LARGE))
        assertFalse(thumbs.has("gone"))
        assertNull(thumbs.noneStamp("alsogone"))
        assertFalse(leftover.exists())
    }

    @Test
    fun `both sizes from one decode, JPEG for a photo and PNG where it is see-through`() {
        val sizes = assertNotNull(Thumbnails.encodeSizes(picture(1000, 1000), Covers.SMALL_EDGE, Thumbnails.MAX_EDGE))
        assertEquals(2, sizes.size)
        Image.makeFromEncoded(sizes[0]).use { assertEquals(Covers.SMALL_EDGE, it.width); assertEquals(Covers.SMALL_EDGE, it.height) }
        Image.makeFromEncoded(sizes[1]).use { assertEquals(Thumbnails.MAX_EDGE, it.width) }
        // JPEG starts FF D8.
        assertEquals(0xFF.toByte(), sizes[0][0])
        assertEquals(0xD8.toByte(), sizes[0][1])

        // Wider than tall: the longest edge is the one that fits, and it is never enlarged.
        val wide = assertNotNull(Thumbnails.encodeSizes(picture(400, 200), Covers.SMALL_EDGE, 1000))
        Image.makeFromEncoded(wide[0]).use { assertEquals(80, it.width); assertEquals(40, it.height) }
        Image.makeFromEncoded(wide[1]).use { assertEquals(400, it.width) }

        val clear = assertNotNull(Thumbnails.encodeSizes(picture(300, 300, Color.makeARGB(90, 0, 200, 0), EncodedImageFormat.PNG), Covers.SMALL_EDGE))
        assertEquals(0x89.toByte(), clear[0][0]) // PNG's signature
        assertEquals('P'.code.toByte(), clear[0][1])

        assertNull(Thumbnails.encodeSizes("not a picture".toByteArray(), Covers.SMALL_EDGE))
    }

    @Test
    fun `scaled down smoothly, not by picking every twelfth pixel`() {
        // One-pixel black and white squares: nearest-pixel scaling gives pure black
        // or pure white; averaging them, as a cover's fine detail needs, gives grey.
        val side = 960
        val pixels = ByteArray(side * side * 4)
        for (y in 0 until side) for (x in 0 until side) {
            val v = if ((x + y) % 2 == 0) 255.toByte() else 0
            val at = (y * side + x) * 4
            pixels[at] = v; pixels[at + 1] = v; pixels[at + 2] = v; pixels[at + 3] = 255.toByte()
        }
        val checker = Image.makeRaster(ImageInfo.makeN32(side, side, ColorAlphaType.PREMUL), pixels, side * 4)
            .use { checkNotNull(it.encodeToData(EncodedImageFormat.PNG)).bytes }

        val small = assertNotNull(Thumbnails.decodeScaled(checker, Covers.SMALL_EDGE))
        assertEquals(Covers.SMALL_EDGE, small.width)
        val map = small.toPixelMap()
        for ((x, y) in listOf(10 to 10, 40 to 40, 70 to 20, 33 to 66)) {
            val red = map[x, y].red
            assertTrue(red in 0.3f..0.7f, "pixel $x,$y should be grey, was $red")
        }
    }

    @Test
    fun `a cover beside the songs is taken once, kept on disk at both sizes, and shared by the album`(): Unit = runBlocking {
        val dir = folder()
        File(dir, "cover.jpg").writeBytes(picture(600, 600))
        val one = song(dir, "one.mp3")
        val two = song(dir, "two.mp3")

        val small = assertNotNull(Covers.load(one, Covers.SMALL_EDGE))
        assertEquals(Covers.SMALL_EDGE, small.width)
        assertSame(small, Covers.cached(one, Covers.SMALL_EDGE))
        val large = assertNotNull(Covers.load(one))
        assertEquals(Thumbnails.MAX_EDGE, large.width)

        val key = assertNotNull(store.keyOf(one.file))
        assertNotNull(store.read(key, CoverThumbs.Size.SMALL))
        assertNotNull(store.read(key, CoverThumbs.Size.LARGE))

        // The same picture for the album's other song: one bitmap between them.
        assertSame(small, Covers.load(two, Covers.SMALL_EDGE))

        // A fresh start, and the picture beside them gone: they come back from disk.
        Covers.clearMemory()
        File(dir, "cover.jpg").delete()
        assertNull(Covers.cached(one, Covers.SMALL_EDGE))
        assertNotNull(Covers.load(one, Covers.SMALL_EDGE))
        assertNotNull(Covers.load(two))
    }

    @Test
    fun `a song with no cover is marked, and a cover put beside it later is still found`(): Unit = runBlocking {
        val dir = folder()
        val bare = song(dir, "bare.mp3")
        assertNull(Covers.load(bare, Covers.SMALL_EDGE))
        assertTrue(Covers.knownMissing(bare))
        val key = assertNotNull(store.keyOf(bare.file))
        assertEquals(dir.lastModified(), store.noneStamp(key))

        // Later, a cover.jpg appears; the folder's time moves on with it.
        Covers.clearMemory()
        File(dir, "cover.jpg").writeBytes(picture(300, 300))
        dir.setLastModified(dir.lastModified() + 10_000)
        assertNotNull(Covers.load(bare, Covers.SMALL_EDGE))
        assertFalse(Covers.knownMissing(bare))
        assertNull(store.noneStamp(key))
    }

    @Test
    fun `warming brings every song's row cover into memory`(): Unit = runBlocking {
        Covers.clearMemory()
        val songs = (1..6).flatMap { album ->
            val dir = folder()
            File(dir, "cover.jpg").writeBytes(picture(500, 500, Color.makeRGB(album * 30, 80, 200 - album * 20)))
            listOf(song(dir, "a.mp3"), song(dir, "b.mp3"))
        } + song(folder(), "none.mp3")
        songs.forEach { assertNull(Covers.cached(it, Covers.SMALL_EDGE)) }

        Covers.warm(songs)

        songs.dropLast(1).forEach { assertNotNull(Covers.cached(it, Covers.SMALL_EDGE), it.title) }
        assertTrue(Covers.knownMissing(songs.last()))
    }

    @Test
    fun `glass is as it always was at half, nearly solid at none, nearly clear at all`() {
        assertEquals(0.4f, Glass.tint(0.4f, Glass.SEE_THROUGH_DEFAULT), 1e-6f)
        assertEquals(0.92f, Glass.tint(0.4f, 0f), 1e-6f)
        assertEquals(0.4f * 0.12f, Glass.tint(0.4f, 1f), 1e-6f)
        // Clearer all the way along, and the frost thins only past half.
        val steps = (0..20).map { Glass.tint(0.34f, it / 20f) }
        assertTrue(steps.zipWithNext().all { (a, b) -> b < a }, "tint should fall as see-through rises: $steps")
        assertEquals(36.dp, Glass.blur(0f))
        assertEquals(36.dp, Glass.blur(0.5f))
        assertEquals(12.dp, Glass.blur(1f))
        assertTrue(Glass.blur(0.75f) < 36.dp && Glass.blur(0.75f) > 12.dp)
        // Out of range is held to the ends.
        assertEquals(Glass.tint(0.4f, 1f), Glass.tint(0.4f, 3f))
    }
}
