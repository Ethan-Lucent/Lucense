package com.exo.musicplayer.desktop.download

import com.exo.musicplayer.data.download.DownloadQuality
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The reading order of YouTubeAhead, with a fake in place of yt-dlp: no network, no process. */
class YouTubeAheadTest {

    private val quality = DownloadQuality.MEDIUM
    private val batches = CopyOnWriteArrayList<List<String>>()
    private val open = CountDownLatch(1)
    private val folder = File.createTempFile("ahead", "").apply { delete(); mkdirs() }
    private val original = YouTubeAhead.reader

    @BeforeTest
    fun fake() {
        // Each batch is "being read" until the test lets it finish, then every video in it is answered.
        YouTubeAhead.reader = { ids ->
            batches += ids
            open.await(20, TimeUnit.SECONDS)
            ids.forEach { id ->
                val info = File(folder, "$id.json").apply { writeText("{}") }
                val now = System.currentTimeMillis()
                YouTubeAhead.answered(YouTubeAhead.Found(id, info, "https://x/$id", now + 3_600_000, now))
            }
        }
    }

    @AfterTest
    fun restore() {
        open.countDown()
        // Everything answered, so the next test starts with nothing waiting or being read.
        waitFor { YouTubeAhead.waitingIds().isEmpty() }
        Thread.sleep(200)
        YouTubeAhead.reader = original
        folder.deleteRecursively()
    }

    private fun waitFor(what: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!what() && System.nanoTime() < end) Thread.sleep(10)
    }

    private fun ids(range: IntRange, prefix: String) = range.map { "$prefix$it" }

    @Test
    fun `reads ahead two at a time in batches of three, the rest waiting in order`() {
        val list = ids(1..8, "a")
        YouTubeAhead.lookAhead(list, quality)
        waitFor { batches.size == 2 }
        assertEquals(setOf(list.subList(0, 3), list.subList(3, 6)), batches.toSet())
        assertEquals(list.subList(6, 8), YouTubeAhead.waitingIds())
    }

    @Test
    fun `a video asked for that waits is read at once, ahead of the waiting, without touching what is running`() {
        val list = ids(1..8, "b")
        YouTubeAhead.lookAhead(list, quality)
        waitFor { batches.size == 2 }
        val running = batches.toList()

        var answer: YouTubeAhead.Found? = null
        val asker = thread { answer = runBlocking { YouTubeAhead.find("b8", quality) } }
        waitFor { batches.size == 3 }

        assertEquals(listOf("b8"), batches[2])
        assertEquals(running, batches.subList(0, 2))
        assertEquals(listOf("b7"), YouTubeAhead.waitingIds())
        open.countDown()
        asker.join(10_000)
        assertEquals("https://x/b8", answer?.url)
    }

    @Test
    fun `a video asked for that is being read already is not read twice`() {
        YouTubeAhead.lookAhead(ids(1..3, "c"), quality)
        waitFor { batches.size == 1 }
        val asker = thread { runBlocking { YouTubeAhead.find("c2", quality) } }
        Thread.sleep(200)
        assertEquals(1, batches.size)
        open.countDown()
        asker.join(10_000)
        assertNotNull(YouTubeAhead.kept("c2"))
    }

    @Test
    fun `a newer look ahead goes before what waited`() {
        YouTubeAhead.lookAhead(ids(1..8, "d"), quality)
        waitFor { batches.size == 2 }
        YouTubeAhead.lookAhead(listOf("n1", "n2"), quality)
        // d7 and d8 still wait; the new ones are put in front, unless a worker already took them.
        val waiting = YouTubeAhead.waitingIds()
        val started = batches.flatten()
        val pending = waiting.filter { it.startsWith("n") || it.startsWith("d") }
        val nFirst = pending.indexOfFirst { it.startsWith("n") }
        val dFirst = pending.indexOfFirst { it.startsWith("d") }
        assertTrue(nFirst == -1 || dFirst == -1 || nFirst < dFirst, "waiting: $waiting, started: $started")
        assertTrue(waiting.size + started.size >= 10 || waiting.isEmpty())
    }

    @Test
    fun `a long list cannot fill the queue past its bound`() {
        YouTubeAhead.lookAhead(ids(1..60, "e"), quality)
        waitFor { batches.size == 2 }
        val waiting = YouTubeAhead.waitingIds()
        assertTrue(waiting.size <= YouTubeAhead.MAX_WAITING, "waiting ${waiting.size}")
        // The tail is what is let go; the head keeps its order.
        assertEquals(ids(7..(6 + waiting.size), "e"), waiting)
        assertFalse(waiting.contains("e60"))
    }

    @Test
    fun `previews read the small plain file, downloads still choose from every format`() {
        val selector = DownloadQuality.PREVIEW_FORMAT_SELECTOR
        // Plain https only: DASH ("http_dash_segments") and HLS are the slow ones.
        assertTrue("protocol=https" in selector)
        assertFalse("protocol^=http" in selector, "that would let http_dash_segments in")
        // A small plain file first, then any plain https, then anything at all, so a video always has an answer.
        assertEquals(
            listOf("bestaudio[protocol=https][abr<=160]", "bestaudio[protocol=https]", "bestaudio", "best"),
            selector.split("/")
        )
        // The download's own selector is left as it was.
        assertEquals("bestaudio[abr<=192]/bestaudio/best", DownloadQuality.MEDIUM.formatSelector)
    }
}
