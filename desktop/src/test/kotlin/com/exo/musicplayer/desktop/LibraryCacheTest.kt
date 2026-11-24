package com.exo.musicplayer.desktop

import com.exo.musicplayer.desktop.data.DesktopStore
import com.exo.musicplayer.desktop.library.DesktopTrack
import com.exo.musicplayer.desktop.library.FolderLibrary
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The library cache: a file read once is read again only when it changes. */
class LibraryCacheTest {

    private fun song(dir: File, name: String, frames: Long = 4_410) = File(dir, "$name.wav").also {
        val format = AudioFormat(44_100f, 16, 2, true, false)
        AudioSystem.write(AudioInputStream(ByteArrayInputStream(ByteArray((frames * 4).toInt())), format, frames), AudioFileFormat.Type.WAVE, it)
    }

    private fun folder(count: Int): File {
        val dir = Files.createTempDirectory("lucense-cache").toFile()
        repeat(count) { song(dir, "Song %03d".format(it)) }
        return dir
    }

    @Test
    fun onlyWhatChangedIsReadAgain() = runBlocking {
        val dir = folder(5)
        val reads = mutableListOf<String>()
        val counting: (File) -> DesktopTrack? = { file ->
            reads += file.name
            DesktopTrack(file, file.nameWithoutExtension, "Artist", null, 100L, null, null, file.length())
        }

        val (first, kept) = FolderLibrary.scan(listOf(dir), emptyMap(), reader = counting)
        assertEquals(5, reads.size)
        assertEquals(5, first.size)

        reads.clear()
        val (again, keptAgain) = FolderLibrary.scan(listOf(dir), kept, reader = counting)
        assertEquals(emptyList(), reads, "nothing changed, nothing read")
        assertEquals(first, again)
        assertEquals(kept, keptAgain)

        // One file changes, one arrives, one goes.
        File(dir, "Song 001.wav").appendBytes(ByteArray(400))
        song(dir, "Song 999")
        File(dir, "Song 004.wav").delete()
        reads.clear()
        val (after, keptAfter) = FolderLibrary.scan(listOf(dir), keptAgain, reader = counting)
        assertEquals(listOf("Song 001.wav", "Song 999.wav"), reads.sorted())
        assertEquals(5, after.size)
        assertTrue(after.none { it.title == "Song 004" }, "a deleted file is gone")
        assertTrue(File(dir, "Song 004.wav").absolutePath !in keptAfter, "and forgotten")
        assertEquals(first.first { it.title == "Song 000" }, after.first { it.title == "Song 000" }, "an unchanged song as it was")
    }

    @Test
    fun theCacheSurvivesTheDatabase() = runBlocking {
        val dir = folder(3)
        File(dir, "notes.mp3").writeText("not a song at all")
        val (_, kept) = FolderLibrary.scan(listOf(dir), emptyMap(), reader = { file ->
            if (file.name == "notes.mp3") null
            else DesktopTrack(file, file.nameWithoutExtension, null, "Album", 2_000L, 3, 2021, file.length(), "Band", "Rock; Pop")
        })
        val store = DesktopStore(File(Files.createTempDirectory("lucense-store").toFile(), "lucense.db"))
        store.saveLibraryCache(kept)
        assertEquals(kept, store.libraryCache())
        // Saved again, smaller: what isn't there any more doesn't linger.
        store.saveLibraryCache(kept.filterKeys { !it.endsWith("notes.mp3") })
        assertEquals(3, store.libraryCache().size)
        store.close()
    }

    @Test
    fun aWarmScanIsMuchFasterThanACold() = runBlocking {
        // A real library, read only, when LUCENSE_BENCH_DIR names one; 500 small files otherwise.
        val dir = System.getenv("LUCENSE_BENCH_DIR")?.let(::File)?.takeIf { it.isDirectory } ?: folder(500)
        val coldStart = System.nanoTime()
        val (_, kept) = FolderLibrary.scan(listOf(dir), emptyMap())
        val coldMs = (System.nanoTime() - coldStart) / 1e6
        val warmStart = System.nanoTime()
        FolderLibrary.scan(listOf(dir), kept)
        val warmMs = (System.nanoTime() - warmStart) / 1e6
        println("${kept.size} files in $dir: %.0f ms reading every tag, %.0f ms with the cache".format(coldMs, warmMs))
        assertTrue(warmMs * 3 < coldMs, "cold $coldMs ms, warm $warmMs ms")
    }
}
