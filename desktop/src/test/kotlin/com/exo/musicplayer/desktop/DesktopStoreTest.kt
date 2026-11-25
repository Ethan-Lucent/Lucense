package com.exo.musicplayer.desktop

import com.exo.musicplayer.desktop.data.DesktopStore
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Timeout
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The library database: ids that belong to the row just written, and writes that don't each wait on the disk. */
class DesktopStoreTest {

    private fun freshStore(): Pair<DesktopStore, File> {
        val file = File(Files.createTempDirectory("lucense-store").toFile(), "lucense.db")
        return DesktopStore(file) to file
    }

    private fun <T> read(file: File, sql: String, row: (java.sql.ResultSet) -> T): List<T> =
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
            c.createStatement().use { st -> st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(row(rs)) } } }
        }

    /**
     * Every thread uses the one connection. The id of a play used to be read back
     * with a second statement, so an insert from another thread in between - a
     * playlist, here - handed the play someone else's id, and its listening time
     * was then written into the wrong row.
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS) // locks taken in the wrong order hang rather than fail
    fun aPlaysIdIsItsOwnWhateverElseIsBeingWritten() {
        val (store, file) = freshStore()
        val threads = 6
        val each = 150
        val start = CountDownLatch(1)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val workers = (0 until threads).map { t ->
            thread {
                start.await()
                runCatching { repeat(each) { i ->
                    if (t % 2 == 0) {
                        val path = "/music/$t/$i.mp3"
                        val id = store.startPlay(path, null)
                        store.updateListened(id, path.hashCode().toLong() and 0xFFFFFF)
                    } else {
                        store.createPlaylist("list $t-$i")
                    }
                } }.onFailure { failures += it }
            }
        }
        start.countDown()
        workers.forEach { it.join() }
        store.close()
        assertTrue(failures.isEmpty(), "writing failed: ${failures.firstOrNull()}")

        val plays = read(file, "SELECT path, listenedMs FROM play_events") { it.getString(1) to it.getLong(2) }
        assertEquals(threads / 2 * each, plays.size)
        val wrong = plays.filter { (path, listened) -> listened != (path.hashCode().toLong() and 0xFFFFFF) }
        assertTrue(wrong.isEmpty(), "${wrong.size} plays got another row's listening time, e.g. ${wrong.take(3)}")
    }

    @Test
    fun theDatabaseIsWriteAheadLogged() {
        val (store, file) = freshStore()
        val id = store.createPlaylist("Road trip")
        assertTrue(id > 0)
        assertEquals(id + 1, store.createPlaylist("Rain"), "ids come back in order, one per insert")
        store.close()
        assertEquals(listOf("wal"), read(file, "PRAGMA journal_mode") { it.getString(1) })
    }
}
