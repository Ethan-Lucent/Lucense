package com.exo.musicplayer.desktop

import com.exo.musicplayer.data.lyrics.LyricsFetch
import com.exo.musicplayer.data.lyrics.LyricsProvider
import com.exo.musicplayer.data.lyrics.LyricsProviderChain
import com.exo.musicplayer.data.net.Http
import com.exo.musicplayer.data.net.HttpTally
import com.exo.musicplayer.data.recognition.MetadataProvider
import com.exo.musicplayer.data.recognition.MetadataProviderChain
import com.exo.musicplayer.data.recognition.MusicMatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.exo.musicplayer.desktop.data.BulkFailure
import com.exo.musicplayer.desktop.data.BulkKind
import com.exo.musicplayer.desktop.data.BulkOutcome
import com.exo.musicplayer.desktop.data.BulkTally
import com.exo.musicplayer.desktop.data.DesktopStore
import com.exo.musicplayer.desktop.data.GIVE_UP_SAMPLE
import com.exo.musicplayer.desktop.data.GiveUp
import com.exo.musicplayer.desktop.data.bulkNote
import com.exo.musicplayer.desktop.data.bulkQueue
import com.exo.musicplayer.desktop.data.lyricsStep
import com.exo.musicplayer.desktop.data.words
import com.exo.musicplayer.desktop.data.workThroughQueue
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The bulk tools' "done" marks: only a real answer marks a song. Everything
 * here runs on fakes and a throwaway database: no network, no real library.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class BulkAuditTest {

    private val column = BulkKind.LYRICS.markColumn!!
    private val songs = (1..20).map { "/music/song$it.mp3" }

    private fun dbFile() = File(Files.createTempDirectory("lucense-audit").toFile(), "lucense.db")

    private fun provider(label: String, answer: suspend (String) -> LyricsFetch) = object : LyricsProvider {
        override val label = label
        val calls = AtomicInteger()
        override suspend fun fetch(title: String, artist: String?, album: String?, durationMs: Long): LyricsFetch {
            calls.incrementAndGet()
            return answer(title)
        }
    }

    private val found = provider("Found") { LyricsFetch.Found("la la", null) }
    private val none = provider("None") { LyricsFetch.NotFound }
    private val down = provider("Down") { LyricsFetch.Error("Couldn't reach the lyrics service.") }

    /** One Lyrics run over [queue] the way runBulk does it: through the real step, marking into [store]. */
    private suspend fun lyricsRun(store: DesktopStore, chain: LyricsProviderChain, queue: List<String>): BulkTally =
        workThroughQueue(
            queue, workers = 3, label = { it }, onProgress = {},
            afterEach = { store.mark(it, column) }
        ) { path -> lyricsStep(chain, store, path, File(path).nameWithoutExtension, "Artist", null, 0) }

    private fun nextQueue(store: DesktopStore, redo: Boolean = false) =
        bulkQueue(songs, { it }, store.markedPaths(column), redo)

    // ---- The chain: an answer or an error, never a guess ----

    @Test
    fun allProvidersFailingIsAnOfflineErrorNotNotFound() = runBlocking {
        val throws = provider("Throws") { error("socket closed") }
        val (result, _) = LyricsProviderChain(listOf(down, throws)).fetch("Song", "Artist", null, 0)
        assertTrue(result is LyricsFetch.Error && result.offline, "got $result")
    }

    @Test
    fun someAnsweringAndSomeFailingIsNotDefinitive() = runBlocking {
        val (result, _) = LyricsProviderChain(listOf(none, down)).fetch("Song", "Artist", null, 0)
        assertTrue(result is LyricsFetch.Error && !result.offline, "got $result")
    }

    @Test
    fun everyProviderAnsweringNotFoundIsNotFound() = runBlocking {
        val (result, _) = LyricsProviderChain(listOf(none, provider("None2") { LyricsFetch.NotFound }))
            .fetch("Song", "Artist", null, 0)
        assertEquals(LyricsFetch.NotFound, result)
    }

    @Test
    fun allServicesTurnedOffIsAnErrorNotNotFound() = runBlocking {
        val (result, _) = LyricsProviderChain(listOf(none), isOn = { false }).fetch("Song", "Artist", null, 0)
        assertTrue(result is LyricsFetch.Error, "got $result")
    }

    /** NetEase, lyrics.ovh and Genius say NotFound when their request failed; the chain sees through that. */
    @Test
    fun aProviderSayingNotFoundAfterItsRequestFailedIsAnError() = runBlocking {
        val masked = provider("Masks") {
            // Port 1 on this machine refuses at once: a failed request, no network needed.
            Http.get("http://127.0.0.1:1/lyrics")
            LyricsFetch.NotFound
        }
        val (result, _) = LyricsProviderChain(listOf(masked)).fetch("Song", "Artist", null, 0)
        assertTrue(result is LyricsFetch.Error && result.offline, "got $result")
    }

    @Test
    fun aHungProviderTimesOutAndTheNextOneIsAsked() = runBlocking {
        val hangs = provider("Hangs") { awaitCancellation() }
        val started = System.nanoTime()
        val (result, source) = LyricsProviderChain(listOf(hangs, found), providerTimeoutMs = 200)
            .fetch("Song", "Artist", null, 0)
        assertTrue(result is LyricsFetch.Found, "got $result")
        assertEquals("Found", source)
        assertTrue(System.nanoTime() - started < 5_000_000_000L)
    }

    /** A request stuck in a blocking socket read can't be interrupted; the chain still moves on. */
    @Test
    fun aProviderBlockedInsideAPlainReadStillTimesOut() = runBlocking {
        val blocked = provider("Blocked") { Thread.sleep(8_000); LyricsFetch.NotFound }
        val started = System.nanoTime()
        val (result, _) = LyricsProviderChain(listOf(blocked), providerTimeoutMs = 200).fetch("Song", "Artist", null, 0)
        assertTrue(result is LyricsFetch.Error, "got $result")
        assertTrue(System.nanoTime() - started < 4_000_000_000L, "waited for the blocked read")
    }

    @Test
    fun offlineDoesNotTryEveryOtherSpellingOfTheName() = runBlocking {
        val throws = provider("Throws") { error("offline") }
        LyricsProviderChain(listOf(throws)).fetch("Artist - Song (Official Video)", "A, B", null, 0)
        assertEquals(1, throws.calls.get())
    }

    // ---- Covers and Names & tags: telling "nothing" from "offline" ----

    private fun catalogue(label: String, search: () -> List<MusicMatch>) = object : MetadataProvider {
        override val label = label
        override suspend fun search(query: String, limit: Int): List<MusicMatch> = withContext(Dispatchers.IO) { search() }
    }

    /** Covers and Names & tags only mark "nothing found" when some service was reached ([HttpTally.reached]). */
    @Test
    fun aCatalogueLookupMadeOfflineReachesNobody() = runBlocking {
        val offline = catalogue("Offline") { Http.get("http://127.0.0.1:1/search"); emptyList() }
        val reach = HttpTally()
        val art = MetadataProviderChain(listOf(offline))
            .findArtwork("Artist Song", patienceMs = 50, giveUpMs = 2_000, reach = reach) { it }
        assertNull(art)
        assertEquals(0, reach.reached)
        assertTrue(reach.failed > 0)
    }

    @Test
    fun aCatalogueThatAnsweredCountsAsReached() = runBlocking {
        val answers = catalogue("Answers") { listOf(MusicMatch(title = "Song", artist = "Artist", album = null, source = MusicMatch.Source.SEARCH)) }
        val reach = HttpTally()
        MetadataProviderChain(listOf(answers)).findArtwork("Artist Song", patienceMs = 50, giveUpMs = 2_000, reach = reach) { it }
        assertTrue(reach.reached > 0)
    }

    // ---- The Lyrics tool on a real store ----

    @Test
    fun anErrorSongIsNotMarkedAndIsQueuedAgain() = runBlocking {
        val store = DesktopStore(dbFile())
        val tally = lyricsRun(store, LyricsProviderChain(listOf(down)), songs)
        assertEquals(emptySet(), store.markedPaths(column))
        assertEquals(songs, nextQueue(store))
        assertEquals(0, tally.answered)
    }

    @Test
    fun aTimedOutSongIsNotMarked() = runBlocking {
        val store = DesktopStore(dbFile())
        val hangs = provider("Hangs") { awaitCancellation() }
        lyricsRun(store, LyricsProviderChain(listOf(hangs), providerTimeoutMs = 100), songs.take(3))
        assertEquals(emptySet(), store.markedPaths(column))
    }

    @Test
    fun notFoundFromAnsweringServicesIsMarkedSkippedNextTimeAndRedoneWithRedo() = runBlocking {
        val store = DesktopStore(dbFile())
        val tally = lyricsRun(store, LyricsProviderChain(listOf(none)), songs)
        assertEquals(songs.size, tally.nothing)
        assertEquals(songs.toSet(), store.markedPaths(column))
        assertEquals(emptyList(), nextQueue(store))
        assertEquals(songs, nextQueue(store, redo = true))
    }

    @Test
    fun foundLyricsAreStoredAndMarked() = runBlocking {
        val store = DesktopStore(dbFile())
        val tally = lyricsRun(store, LyricsProviderChain(listOf(none, found)), songs.take(4))
        assertEquals(4, tally.updated)
        assertEquals(songs.take(4).toSet(), store.markedPaths(column))
        assertEquals("la la", store.lyricsFor(songs[0])?.plain)
        assertEquals("Found", store.lyricsFor(songs[0])?.source)
    }

    @Test
    fun lyricsAlreadyKeptAreNotLookedUpOrOverwritten() = runBlocking {
        val store = DesktopStore(dbFile())
        store.saveLyrics(songs[0], "pasted by hand", null, "manual")
        val counted = provider("Found") { LyricsFetch.Found("la la", null) }
        val chain = LyricsProviderChain(listOf(counted))
        val outcome = lyricsStep(chain, store, songs[0], "Song", "Artist", null, 0)
        assertEquals(BulkOutcome.ALREADY_HAD, outcome)
        assertEquals(0, counted.calls.get())
        assertEquals("pasted by hand", store.lyricsFor(songs[0])?.plain)
        // Fix looks again whatever is kept.
        assertEquals(BulkOutcome.UPDATED, lyricsStep(chain, store, songs[0], "Song", "Artist", null, 0, keepStored = false))
    }

    /** The user's case: a first run made offline, then one made online. */
    @Test
    fun aRunMadeOfflineLeavesEverythingForTheNextRun() = runBlocking {
        val store = DesktopStore(dbFile())
        val offline = lyricsRun(store, LyricsProviderChain(listOf(down)), songs)
        assertNotNull(offline.gaveUp)
        val online = lyricsRun(store, LyricsProviderChain(listOf(found)), nextQueue(store))
        assertEquals(songs.size, online.updated)
        assertEquals(songs.toSet(), store.markedPaths(column))
    }

    // ---- Giving up, and the note ----

    @Test
    fun mostOfTheFirstSongsUnreachableStopsTheRunAndMarksNothing() = runBlocking {
        val store = DesktopStore(dbFile())
        val queue = (1..200).map { "/music/many$it.mp3" }
        val tried = AtomicInteger()
        val tally = workThroughQueue(
            queue, workers = 3, label = { it }, onProgress = {},
            afterEach = { store.mark(it, column) }
        ) {
            tried.incrementAndGet()
            throw BulkFailure("couldn't be reached (check your connection)", GiveUp("Lyrics services can't be reached right now."))
        }
        assertNotNull(tally.gaveUp)
        assertTrue(tried.get() <= GIVE_UP_SAMPLE + 3, "tried ${tried.get()}")
        assertEquals(emptySet(), store.markedPaths(column))
        assertEquals(queue.size, tally.answered + tally.failed + tally.notTried)
        val note = bulkNote(BulkKind.LYRICS.words, tally, skipped = 0)
        assertTrue(note.startsWith("Lyrics services can't be reached right now. Nothing was marked"), note)
    }

    /** One flaky service among four makes songs "not fully checked", which mustn't stop a run. */
    @Test
    fun failuresThatDontAffectEverySongNeverStopTheRun() = runBlocking {
        val tally = workThroughQueue(songs, 3, { it }, {}) { throw BulkFailure("weren't fully checked") }
        assertNull(tally.gaveUp)
        assertEquals(songs.size, tally.failed)
        assertEquals(0, tally.notTried)
    }

    @Test
    fun theNotesNumbersAddUpToTheQueue() = runBlocking {
        val tally = workThroughQueue(songs, 4, { it }, {}) { path ->
            when (path.removePrefix("/music/song").removeSuffix(".mp3").toInt() % 5) {
                0 -> BulkOutcome.UPDATED
                1 -> BulkOutcome.ALREADY_HAD
                2 -> BulkOutcome.NOTHING_FOUND
                3 -> throw BulkFailure("couldn't be reached (check your connection)")
                else -> error("disk on fire")
            }
        }
        val note = bulkNote(BulkKind.LYRICS.words, tally, skipped = 7)
        val numbers = Regex("""\d+""").findAll(note).map { it.value.toInt() }.toList()
        assertEquals(songs.size + 7, numbers.sum(), note)
        assertTrue("4 found" in note && "4 already had lyrics" in note && "4 with nothing found" in note, note)
        assertTrue("4 couldn't be reached (check your connection)" in note, note)
        assertTrue("4 failed (disk on fire)" in note, note)
        assertTrue(note.endsWith("7 skipped as already done."), note)
    }

    @Test
    fun aWorkerSurvivesAThrowingSongAndAFailedMark() = runBlocking {
        val marked = mutableSetOf<String>()
        val tally = workThroughQueue(
            songs, workers = 1, label = { it }, onProgress = {},
            afterEach = { if (it.endsWith("song5.mp3")) error("database is locked") else marked += it }
        ) { if (it.endsWith("song2.mp3")) throw IllegalStateException("bad tag") else BulkOutcome.UPDATED }
        assertEquals(songs.size - 2, marked.size)
        assertEquals(songs.size - 2, tally.updated)
        assertEquals(2, tally.failed)
        assertEquals(songs.size, tally.answered + tally.failed)
    }

    // ---- The one-time repair of old false marks ----

    private fun forgetMigration(db: File) {
        DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { c ->
            c.createStatement().use { it.executeUpdate("DELETE FROM meta WHERE key = '${DesktopStore.FALSE_MARKS_CLEARED}'") }
        }
    }

    @Test
    fun oldLyricsMarksWithoutLyricsAreQueuedAgainExactlyOnce() {
        val db = dbFile()
        // A database as the old version left it: every song marked, only some with lyrics.
        DesktopStore(db).apply {
            songs.forEach { mark(it, column); mark(it, "artCheckedAt"); mark(it, "identifiedAt") }
            saveLyrics(songs[0], "kept", null, "LRCLIB")
            close()
        }
        forgetMigration(db)

        val upgraded = DesktopStore(db)
        assertEquals(setOf(songs[0]), upgraded.markedPaths(column))
        assertEquals(emptySet(), upgraded.markedPaths("artCheckedAt"))
        // Names & tags rewrites files, so its marks are left as they were.
        assertEquals(songs.toSet(), upgraded.markedPaths("identifiedAt"))
        // A real "not found" from now on is kept on every later start.
        upgraded.mark(songs[1], column)
        upgraded.mark(songs[1], "artCheckedAt")
        upgraded.close()

        val again = DesktopStore(db)
        assertEquals(setOf(songs[0], songs[1]), again.markedPaths(column))
        assertEquals(setOf(songs[1]), again.markedPaths("artCheckedAt"))
        again.close()
    }
}
