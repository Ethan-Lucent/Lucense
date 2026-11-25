package com.exo.musicplayer.desktop

import com.exo.musicplayer.desktop.system.SandboxShare
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** A dragged song, offered from where a Flatpak chat app can read it - the same file, not a copy. */
class SandboxShareTest {

    private val root = Files.createTempDirectory("lucense-share").toFile()
    private val downloads = File(root, "Downloads").apply { mkdirs() }
    private val share = File(downloads, ".lucense-share")

    private fun song(folder: String, name: String, text: String) =
        File(root, folder).apply { mkdirs() }.let { File(it, name) }.apply { writeText(text) }

    @Test
    fun aSongOutsideDownloadsIsLinkedIn() {
        val song = song("songs", "0126 BoyKoy - Number (Remix).m4a", "the music")
        val offered = SandboxShare.offer(listOf(song), share, downloads).single()
        assertEquals(share, offered.parentFile)
        assertEquals(song.name, offered.name, "the name is what the chat shows")
        assertTrue(Files.isSameFile(offered.toPath(), song.toPath()), "a hard link: the very same file")
        assertEquals("the music", offered.readText())
    }

    @Test
    fun aSongAlreadyInDownloadsIsOfferedAsItIs() {
        val song = File(downloads, "here.mp3").apply { writeText("x") }
        assertEquals(song, SandboxShare.offer(listOf(song), share, downloads).single())
    }

    @Test
    fun twoSongsWithOneNameKeepItBoth() {
        val first = song("a", "track.mp3", "first")
        val second = song("b", "track.mp3", "second")
        val (one, two) = SandboxShare.offer(listOf(first, second), share, downloads)
        assertNotEquals(one, two)
        assertEquals("track.mp3", one.name)
        assertEquals("track.mp3", two.name)
        assertEquals("first", one.readText())
        assertEquals("second", two.readText())
        // Offered again, the same link.
        assertEquals(one, SandboxShare.offer(listOf(first), share, downloads).single())
    }
}
