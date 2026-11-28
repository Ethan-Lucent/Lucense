package com.exo.musicplayer.desktop

import com.exo.musicplayer.data.lyrics.LyricsFetch
import com.exo.musicplayer.data.lyrics.LyricsProvider
import com.exo.musicplayer.data.lyrics.LyricsProviderChain
import com.exo.musicplayer.desktop.data.BulkJob
import com.exo.musicplayer.desktop.data.BulkOutcome
import com.exo.musicplayer.desktop.data.DesktopStore
import com.exo.musicplayer.desktop.data.bulkChipText
import com.exo.musicplayer.desktop.data.bulkQueue
import com.exo.musicplayer.desktop.data.stoppedNote
import com.exo.musicplayer.desktop.data.workThroughQueue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What a bulk run marks as done: only songs whose check really finished, so a stopped run can be run again. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class BulkRunTest {

    private val songs = (1..10).map { "/music/song$it.mp3" }

    private fun freshStore() = DesktopStore(File(Files.createTempDirectory("lucense-bulk").toFile(), "lucense.db"))

    /** Runs [step] over [songs] the way runBulk does, marking into [marked]. */
    private suspend fun run(
        queue: List<String>,
        marked: MutableSet<String>,
        workers: Int = 3,
        step: suspend (String) -> BulkOutcome
    ) = workThroughQueue(
        queue, workers, label = { it }, onProgress = {},
        afterEach = { marked += it }, step = step
    )

    @Test
    fun aCompletedSongIsMarked() = runBlocking {
        val marked = mutableSetOf<String>()
        val tally = run(songs, marked) { BulkOutcome.UPDATED }
        assertEquals(songs.toSet(), marked)
        assertEquals(10, tally.updated)
    }

    /** "Nothing there" is an answer and is marked; a song that threw got no answer and is not. */
    @Test
    fun aSongThatFoundNothingIsMarkedButOneThatThrewIsNot() = runBlocking {
        val marked = mutableSetOf<String>()
        val tally = run(songs, marked) { if (it.endsWith("3.mp3")) error("no") else BulkOutcome.NOTHING_FOUND }
        assertEquals(songs.toSet() - "/music/song3.mp3", marked)
        assertEquals(9, tally.nothing)
        assertEquals(1, tally.failed)
        assertEquals(listOf("failed (no)" to 1), tally.reasons)
    }

    /** The lookup itself reports a stop as "nothing found" (as the lyrics chain used to); it must still not be marked. */
    @Test
    fun aSongWhoseWorkWasCancelledIsNotMarkedEvenIfItReportsNothingFound() = runBlocking {
        val marked = mutableSetOf<String>()
        val inFlight = CompletableDeferred<Unit>()
        val job = launch {
            run(songs, marked, workers = 1) { song ->
                if (song.endsWith("3.mp3")) {
                    inFlight.complete(Unit)
                    try { awaitCancellation() } catch (e: Exception) { BulkOutcome.NOTHING_FOUND }
                } else {
                    BulkOutcome.UPDATED
                }
            }
        }
        inFlight.await()
        job.cancel()
        job.join()
        assertEquals(setOf("/music/song1.mp3", "/music/song2.mp3"), marked)
    }

    @Test
    fun aTimeoutInsideOneSongIsThatSongFailingNotTheRunStopping() = runBlocking {
        val marked = mutableSetOf<String>()
        val tally = run(songs, marked) { song ->
            if (song.endsWith("4.mp3")) withTimeout(5) { delay(10_000); BulkOutcome.UPDATED } else BulkOutcome.UPDATED
        }
        // The timed-out song got no answer, so it is left for the next run.
        assertEquals(songs.toSet() - "/music/song4.mp3", marked)
        assertEquals(1, tally.failed)
        assertEquals(9, tally.updated)
    }

    /** Against the real store: stop mid-way, and the next queue is exactly the songs not really done. */
    @Test
    fun afterAStopTheNextRunPicksUpExactlyTheUnmarkedSongs() = runBlocking {
        val store = freshStore()
        val column = "lyricsCheckedAt"
        val reached = CompletableDeferred<Unit>()
        val job = launch {
            workThroughQueue(
                songs, 1, label = { it }, onProgress = {},
                afterEach = { store.mark(it, column) }
            ) { song ->
                if (song.endsWith("4.mp3")) {
                    reached.complete(Unit)
                    try { awaitCancellation() } catch (e: Exception) { BulkOutcome.NOTHING_FOUND }
                } else {
                    BulkOutcome.UPDATED
                }
            }
        }
        reached.await()
        job.cancel(); job.join()

        val marked = store.markedPaths(column)
        assertEquals(songs.take(3).toSet(), marked)
        assertEquals(songs.drop(3), bulkQueue(songs, { it }, marked, redo = false))
        // The "redo" tick ignores the marks.
        assertEquals(songs, bulkQueue(songs, { it }, marked, redo = true))
    }

    /** The lyrics chain used to turn a cancelled lookup into NotFound; now the cancellation gets through. */
    @Test
    fun cancellingALyricsLookupIsNotNothingFound() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val hanging = object : LyricsProvider {
            override val label = "Hangs"
            override suspend fun fetch(title: String, artist: String?, album: String?, durationMs: Long): LyricsFetch {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        val lookup = async(start = CoroutineStart.DEFAULT) {
            LyricsProviderChain(listOf(hanging)).fetch("Song", "Artist", null, 0)
        }
        started.await()
        lookup.cancel()
        lookup.join()
        assertTrue(lookup.isCancelled)
    }

    /** A service that throws gave no answer: that is an Error, never "not found", which would mark the song. */
    @Test
    fun aProviderThatThrowsIsAnErrorNotNothingFound() = runBlocking {
        val broken = object : LyricsProvider {
            override val label = "Broken"
            override suspend fun fetch(title: String, artist: String?, album: String?, durationMs: Long): LyricsFetch =
                error("down")
        }
        val (result, provider) = LyricsProviderChain(listOf(broken)).fetch("Song", "Artist", null, 0)
        assertTrue(result is LyricsFetch.Error && result.offline, "got $result")
        assertEquals(null, provider)
    }

    @Test
    fun stoppedNoteSaysHowManyWereDone() {
        assertEquals("Stopped after 120 of 400.", stoppedNote(120, 400))
    }

    @Test
    fun theChipSaysHowFarARunningJobHasGot() {
        assertEquals("Lyrics 120 / 400", bulkChipText(BulkJob(label = "Lyrics", running = true, total = 400, done = 120)))
        assertEquals(0.3f, BulkJob(running = true, total = 400, done = 120).progress)
    }

    @Test
    fun theChipShowsTheFinishedNoteOnceItIsDone() {
        val done = BulkJob(label = "Lyrics", running = false, finishedNote = "42 updated, 3 with nothing found.")
        assertEquals("42 updated, 3 with nothing found.", bulkChipText(done))
        assertFalse(bulkChipText(BulkJob()).isNotEmpty())
    }
}
