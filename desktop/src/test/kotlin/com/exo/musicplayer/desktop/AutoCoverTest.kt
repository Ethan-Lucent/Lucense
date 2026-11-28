package com.exo.musicplayer.desktop

import com.exo.musicplayer.data.download.DownloadFormat
import com.exo.musicplayer.data.download.DownloadQuality
import com.exo.musicplayer.data.recognition.MusicMatch
import com.exo.musicplayer.data.youtube.YouTubeBackend
import com.exo.musicplayer.data.youtube.YouTubeLinkFinder
import com.exo.musicplayer.data.youtube.YouTubeSearch
import com.exo.musicplayer.data.youtube.YouTubeVideo
import com.exo.musicplayer.data.youtube.YtDlpFlatSearch
import com.exo.musicplayer.desktop.data.AutoCover
import com.exo.musicplayer.desktop.data.AutoCoverRule
import com.exo.musicplayer.desktop.data.CoverFinishing
import com.exo.musicplayer.desktop.data.CoverHint
import com.exo.musicplayer.desktop.data.CoverLedger
import com.exo.musicplayer.desktop.data.CoverOutcome
import com.exo.musicplayer.desktop.data.CoverSearch
import com.exo.musicplayer.desktop.data.CoverSearcher
import com.exo.musicplayer.desktop.data.CoverTally
import com.exo.musicplayer.desktop.data.DesktopSettings
import com.exo.musicplayer.desktop.data.DesktopStore
import com.exo.musicplayer.desktop.data.DownloadEngine
import com.exo.musicplayer.desktop.data.DownloadEntry
import com.exo.musicplayer.desktop.data.Downloader
import com.exo.musicplayer.desktop.data.YouTubeSignInSettings
import com.exo.musicplayer.desktop.download.DesktopYouTubeBackend
import com.exo.musicplayer.desktop.download.DownloadProgress
import com.exo.musicplayer.desktop.download.ToolStatus
import com.exo.musicplayer.desktop.download.YtDlp
import com.exo.musicplayer.desktop.library.DesktopTrack
import com.exo.musicplayer.desktop.system.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.prefs.Preferences
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A song that came from YouTube gets its cover without anyone pressing
 * Identify: the arguments that make yt-dlp embed it, the rule for when the
 * six-service lookup steps in, and that every way of downloading ends at that
 * lookup. No network: the downloads and the services are stand-ins.
 */
class AutoCoverTest {

    private fun temp(): File = Files.createTempDirectory("lucense-autocover").toFile().apply { deleteOnExit() }
    private val ffmpeg = File("/opt/lucense/lib/app/resources/ffmpeg")

    // ---- The yt-dlp arguments --------------------------------------------------

    private fun args(format: DownloadFormat?, embed: Boolean, cut: Boolean = false, windows: Boolean = false) =
        YtDlp.tidyArguments(format, embed, cut, ffmpeg, windows)

    @Test
    fun everyFormatThatCanHoldACoverEmbedsItWhenTheSettingIsOn() {
        for (format in DownloadFormat.entries.filter { it.holdsCover } + listOf(null)) {
            val on = args(format, embed = true)
            assertTrue("--embed-thumbnail" in on, "$format embeds")
            // YouTube serves webp, which most containers and taggers can't take.
            assertEquals("jpg", on[on.indexOf("--convert-thumbnails") + 1], "$format converts to jpg")
            // The square cut from the middle of the 16:9 frame.
            assertTrue(on.any { it.startsWith("ThumbnailsConvertor+FFmpeg_o:-vf") && "crop='min(iw,ih)':'min(iw,ih)'" in it }, "$format crops")
            assertTrue("--embed-metadata" in on)
        }
    }

    @Test
    fun nothingCoverRelatedIsAskedForWhenTheSettingIsOff() {
        for (format in DownloadFormat.entries + listOf(null)) {
            val off = args(format, embed = false)
            assertFalse("--embed-thumbnail" in off, "$format")
            assertFalse("--convert-thumbnails" in off, "$format")
            assertFalse(off.any { "crop=" in it }, "$format")
            // Tags still go in: only the picture is optional.
            assertTrue("--embed-metadata" in off)
        }
    }

    @Test
    fun wavNeverTriesToEmbedAThumbnail() {
        val wav = args(DownloadFormat.WAV, embed = true)
        assertFalse("--embed-thumbnail" in wav)
        assertFalse("--convert-thumbnails" in wav)
        assertFalse(wav.any { "crop=" in it })
    }

    @Test
    fun theCropIsQuotedForTheSystemItRunsOn() {
        fun crop(windows: Boolean) = args(DownloadFormat.MP3, true, windows = windows).first { "crop=" in it }
        // On Linux the Windows form reaches ffmpeg with stray quote marks, the
        // thumbnail conversion fails and the song arrives with no cover: the bug.
        assertFalse('\\' in crop(windows = false))
        assertTrue("\\\"" in crop(windows = true))
    }

    @Test
    fun sponsorBlockIsOnlyAskedForWhenChosen() {
        assertFalse("--sponsorblock-remove" in args(DownloadFormat.MP3, true, cut = false))
        assertTrue("--sponsorblock-remove" in args(DownloadFormat.MP3, true, cut = true))
    }

    /**
     * The real yt-dlp and ffmpeg on a local file with a 16:9 picture, with the
     * arguments exactly as the app builds them: the cover arrives in the file,
     * and square. Skipped where the bundled tools aren't.
     */
    @Test
    fun realYtDlpEmbedsASquareCoverInEachFormat() {
        val ytDlp = File("/opt/lucense/lib/app/resources/yt-dlp")
        assumeTrue(ytDlp.canExecute() && ffmpeg.canExecute() && !Platform.isWindows, "needs the bundled yt-dlp and ffmpeg")
        val dir = temp()
        fun run(vararg command: String): String {
            val p = ProcessBuilder(*command).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            return out
        }
        run(ffmpeg.path, "-v", "error", "-f", "lavfi", "-i", "testsrc=size=640x360:rate=1", "-frames:v", "1", File(dir, "thumb.png").path)
        run(ffmpeg.path, "-v", "error", "-f", "lavfi", "-i", "sine=frequency=440:duration=1", "-c:a", "libopus", File(dir, "a.webm").path)
        File(dir, "info.json").writeText(
            """{"id":"abc","title":"Test Song","ext":"webm","url":"file://${dir.path}/a.webm","format_id":"1",
               "acodec":"opus","vcodec":"none","protocol":"file","extractor":"generic","extractor_key":"Generic",
               "webpage_url":"file://${dir.path}/a.webm","duration":1,
               "thumbnails":[{"url":"file://${dir.path}/thumb.png","id":"0"}]}"""
        )
        for (format in listOf(DownloadFormat.MP3, DownloadFormat.M4A, DownloadFormat.OPUS, DownloadFormat.FLAC)) {
            val out = File(dir, "out-${format.name}").apply { mkdirs() }
            val command = listOf(ytDlp.path, "--enable-file-urls", "--ignore-config", "--no-warnings", "-o", "${out.path}/%(title)s.%(ext)s") +
                listOf("-x", "--audio-format", format.ytDlp) +
                YtDlp.tidyArguments(format, embedThumbnail = true, cutNonMusic = false, ffmpeg = ffmpeg, windows = false) +
                listOf("--load-info-json", File(dir, "info.json").path)
            run(*command.toTypedArray())
            val song = out.listFiles()?.firstOrNull { it.extension in setOf("mp3", "m4a", "opus", "flac") }
            assertNotNull(song, "$format was produced")
            val probe = run(ffmpeg.path, "-hide_banner", "-i", song.path)
            val picture = Regex("""Video: mjpeg.*?(\d+)x(\d+)""").find(probe)
            assertNotNull(picture, "$format carries a picture:\n$probe")
            assertEquals(picture.groupValues[1], picture.groupValues[2], "$format cover is square")
        }
    }

    // ---- The rule --------------------------------------------------------------

    @Test
    fun aLookupIsNeededOnlyForASongWithoutArtThatWasNeverTried() {
        assertTrue(AutoCoverRule.needsLookup(hasArt = false, alreadyTried = false))
        assertFalse(AutoCoverRule.needsLookup(hasArt = true, alreadyTried = false))
        assertFalse(AutoCoverRule.needsLookup(hasArt = false, alreadyTried = true))
        assertFalse(AutoCoverRule.needsLookup(hasArt = true, alreadyTried = true))
    }

    @Test
    fun aMissIsOnlyDefinitiveWhenTheNetworkDidItsPart() {
        assertEquals(CoverSearch.FOUND, AutoCoverRule.judge(true, 3, 9, true))
        assertEquals(CoverSearch.MISSING, AutoCoverRule.judge(false, 3, 3, false))
        assertEquals(CoverSearch.NETWORK, AutoCoverRule.judge(false, 3, 4, false))
        assertEquals(CoverSearch.NETWORK, AutoCoverRule.judge(false, 3, 3, true))
    }

    // ---- The service around it, with stand-ins -------------------------------------

    private fun track(file: File, title: String = "Song") = DesktopTrack(
        file = file, title = title, artist = "Artist", album = null, durationMs = 1000,
        trackNumber = null, year = null, sizeBytes = 1
    )

    private class FakeLedger(val withArt: MutableSet<String> = mutableSetOf(), val marked: MutableSet<String> = mutableSetOf()) : CoverLedger {
        override suspend fun hasArt(track: DesktopTrack) = track.file.absolutePath in withArt
        override suspend fun tried(track: DesktopTrack) = track.file.absolutePath in marked
        override suspend fun markTried(track: DesktopTrack) { marked += track.file.absolutePath }
    }

    private class FakeSearcher(var answer: CoverSearch) : CoverSearcher {
        val asked = CopyOnWriteArrayList<String>()
        val hints = CopyOnWriteArrayList<CoverHint?>()
        val running = AtomicInteger()
        var mostAtOnce = 0
        override suspend fun search(track: DesktopTrack, hint: CoverHint?): CoverSearch {
            val now = running.incrementAndGet()
            mostAtOnce = maxOf(mostAtOnce, now)
            delay(15)
            asked += track.file.absolutePath
            hints += hint
            running.decrementAndGet()
            return answer
        }
    }

    private fun service(
        searcher: FakeSearcher,
        ledger: FakeLedger,
        enabled: () -> Boolean = { true },
        changed: (List<File>) -> Unit = {}
    ) = AutoCover(searcher, ledger, { files -> files.map(::track) }, enabled, changed)

    @Test
    fun aSongWithoutArtAndNeverTriedIsLookedUpAndMarked() = runBlocking {
        val file = File(temp(), "a.mp3")
        val searcher = FakeSearcher(CoverSearch.FOUND)
        val ledger = FakeLedger()
        val changed = CopyOnWriteArrayList<File>()
        val out = service(searcher, ledger, changed = { changed += it }).ensure(listOf(file))
        assertEquals(listOf(CoverOutcome.ADDED), out)
        assertEquals(listOf(file.absolutePath), searcher.asked)
        assertTrue(file.absolutePath in ledger.marked)
        // The library row is read again.
        assertEquals(listOf(file), changed.toList())
    }

    @Test
    fun aSongThatHasEmbeddedArtIsNotLookedUp() = runBlocking {
        val file = File(temp(), "a.mp3")
        val searcher = FakeSearcher(CoverSearch.FOUND)
        val ledger = FakeLedger(withArt = mutableSetOf(file.absolutePath))
        assertEquals(listOf(CoverOutcome.HAD_ART), service(searcher, ledger).ensure(listOf(file)))
        assertTrue(searcher.asked.isEmpty())
    }

    @Test
    fun aSongAlreadyTriedIsNotLookedUpAgain() = runBlocking {
        val file = File(temp(), "a.mp3")
        val searcher = FakeSearcher(CoverSearch.FOUND)
        val ledger = FakeLedger(marked = mutableSetOf(file.absolutePath))
        assertEquals(listOf(CoverOutcome.SKIPPED), service(searcher, ledger).ensure(listOf(file)))
        assertTrue(searcher.asked.isEmpty())
        // Asked for by hand (applying a match), it goes ahead all the same.
        assertEquals(listOf(CoverOutcome.ADDED), service(searcher, ledger).ensure(listOf(file), force = true))
    }

    @Test
    fun theSettingOffMeansNoLookupUnlessAskedForByHand() = runBlocking {
        val file = File(temp(), "a.mp3")
        val searcher = FakeSearcher(CoverSearch.FOUND)
        val service = service(searcher, FakeLedger(), enabled = { false })
        assertEquals(listOf(CoverOutcome.SKIPPED), service.ensure(listOf(file)))
        assertTrue(searcher.asked.isEmpty())
        assertEquals(listOf(CoverOutcome.ADDED), service.ensure(listOf(file), force = true))
    }

    @Test
    fun aDefinitiveMissIsMarkedAndNeverAskedAgain() = runBlocking {
        val file = File(temp(), "a.mp3")
        val searcher = FakeSearcher(CoverSearch.MISSING)
        val ledger = FakeLedger()
        val service = service(searcher, ledger)
        assertEquals(listOf(CoverOutcome.NOT_FOUND), service.ensure(listOf(file)))
        assertTrue(file.absolutePath in ledger.marked)
        assertEquals(listOf(CoverOutcome.SKIPPED), service.ensure(listOf(file)))
        assertEquals(1, searcher.asked.size)
    }

    @Test
    fun aNetworkFailureIsNotMarkedAndIsTriedAgainWithTheNextDownload() = runBlocking {
        val first = File(temp(), "first.mp3")
        val second = File(temp(), "second.mp3")
        val searcher = FakeSearcher(CoverSearch.NETWORK)
        val ledger = FakeLedger()
        val changed = CopyOnWriteArrayList<File>()
        val service = service(searcher, ledger, changed = { changed += it })

        assertEquals(listOf(CoverOutcome.RETRY_LATER), service.ensure(listOf(first)))
        assertTrue(ledger.marked.isEmpty(), "a network error says nothing about the song")

        // The network is back: the next song's lookup also picks up the first.
        searcher.answer = CoverSearch.FOUND
        assertEquals(listOf(CoverOutcome.ADDED), service.ensure(listOf(second)))
        assertEquals(setOf(first.absolutePath, second.absolutePath), ledger.marked)
        assertEquals(setOf(first, second), changed.toSet())
    }

    @Test
    fun songsAreLookedUpOneAtATimeWhateverLandsTogether() = runBlocking {
        val searcher = FakeSearcher(CoverSearch.FOUND)
        val service = service(searcher, FakeLedger())
        val dir = temp()
        (1..6).map { async(Dispatchers.Default) { service.ensure(listOf(File(dir, "$it.mp3"))) } }.awaitAll()
        assertEquals(6, searcher.asked.size)
        assertEquals(1, searcher.mostAtOnce)
    }

    @Test
    fun theQueueEntryNamesHowTheCoverWent() {
        fun note(vararg outcomes: CoverOutcome) = CoverTally().also { it.add(outcomes.toList()) }.note()
        assertEquals("Cover added", note(CoverOutcome.ADDED))
        assertEquals("Cover added", note(CoverOutcome.HAD_ART))
        assertEquals("No cover found", note(CoverOutcome.NOT_FOUND))
        assertNull(note(CoverOutcome.SKIPPED))
        assertEquals(
            "Covers: 2 of 3 added, 1 not found",
            note(CoverOutcome.ADDED, CoverOutcome.HAD_ART, CoverOutcome.NOT_FOUND)
        )
    }

    // ---- The store's mark -------------------------------------------------------------

    @Test
    fun theStoreAnswersWhetherOneSongWasMarked() {
        val store = DesktopStore(File(temp(), "t.db"))
        assertFalse(store.isMarked("/m/a.mp3", "artCheckedAt"))
        store.mark("/m/a.mp3", "artCheckedAt")
        assertTrue(store.isMarked("/m/a.mp3", "artCheckedAt"))
        assertFalse(store.isMarked("/m/a.mp3", "identifiedAt"))
        assertFalse(store.isMarked("/m/b.mp3", "artCheckedAt"))
    }

    // ---- Every way of downloading ends at the cover lookup -------------------------------

    private class FakeEngine(private val dir: File) : DownloadEngine {
        val targets = CopyOnWriteArrayList<String>()
        var fail = false
        private fun file(name: String) = File(dir, name).apply { writeText("x") }
        override suspend fun download(
            target: String, destination: File, format: DownloadFormat?, embedThumbnail: Boolean,
            wholePlaylist: Boolean, quality: DownloadQuality, cutNonMusic: Boolean, onProgress: (DownloadProgress) -> Unit
        ): Result<List<File>> {
            targets += target
            return if (fail) Result.failure(IllegalStateException("nope")) else Result.success(listOf(file("song-${targets.size}.mp3")))
        }

        override suspend fun downloadSpotify(
            url: String, destination: File, format: DownloadFormat?, quality: DownloadQuality, onProgress: (DownloadProgress) -> Unit
        ): Result<List<File>> {
            targets += url
            return Result.success(listOf(file("spotify-${targets.size}.mp3")))
        }

        var songs: List<YouTubeVideo> = emptyList()
        override suspend fun playlist(url: String): Pair<YtDlpFlatSearch.Playlist?, String?> =
            if (songs.isEmpty()) null to "not here" else YtDlpFlatSearch.Playlist("A playlist", songs, 0) to null
    }

    private class Harness {
        val dir = Files.createTempDirectory("lucense-autocover-dl").toFile().apply { deleteOnExit() }
        val node: Preferences = Preferences.userRoot().node("lucense-autocover-test-${System.nanoTime()}")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val settings = DesktopSettings(node).also { it.downloadDir = dir.absolutePath }
        val engine = FakeEngine(dir)
        val added = CopyOnWriteArrayList<List<File>>()
        val rescans = AtomicInteger()
        val finished = CopyOnWriteArrayList<Pair<List<File>, CoverHint?>>()

        val video = YouTubeVideo(id = "vid", title = "Artist - Song", channel = "Artist", durationSeconds = 200)
        private val backend = object : YouTubeBackend {
            override val label = "fake"
            override suspend fun search(query: String, limit: Int) = listOf(video)
        }

        val downloader = Downloader(
            scope = scope,
            settings = settings,
            youtubeBackend = DesktopYouTubeBackend(),
            linkFinder = YouTubeLinkFinder(YouTubeSearch(listOf(backend))),
            signIn = YouTubeSignInSettings(scope, settings),
            addToLibrary = { added += it },
            rescan = { rescans.incrementAndGet() },
            engine = engine,
            covers = CoverFinishing { files, hint ->
                finished += files to hint
                files.map { CoverOutcome.ADDED }
            }
        ).also { it.tools = ToolStatus(ytDlp = true, ffmpeg = true) }

        fun entry(id: Long?): DownloadEntry? = downloader.downloads.firstOrNull { it.id == id }

        /** Waits for the entry to finish and for its cover line to be written. */
        fun settled(id: Long?): DownloadEntry = runBlocking {
            repeat(400) {
                entry(id)?.takeIf { it.done && (it.failed || it.cover != null) }?.let { return@runBlocking it }
                delay(25)
            }
            error("the download didn't settle: ${entry(id)}")
        }

        fun close() {
            scope.cancel()
            node.removeNode()
            dir.deleteRecursively()
        }
    }

    private fun withHarness(block: Harness.() -> Unit) {
        val h = Harness()
        try { h.block() } finally { h.close() }
    }

    @Test
    fun aPastedYouTubeLinkGetsItsCoverLookedFor() = withHarness {
        downloader.startDownload("https://www.youtube.com/watch?v=abc")
        val entry = settled(downloader.downloads.single().id)
        assertEquals(1, finished.size)
        assertEquals(listOf(entry.files), finished.map { it.first })
        assertEquals("Cover added", entry.cover)
        assertEquals(listOf(entry.files), added.toList())
    }

    @Test
    fun aSongFromDiscoverIsLookedUpByItsOwnNames() = withHarness {
        val id = downloader.downloadSong("Artist", "Song", 200_000)
        settled(id)
        assertEquals(1, finished.size)
        assertEquals("Artist Song", finished.single().second?.query)
    }

    @Test
    fun aCatalogueMatchWithADownloadLinkCarriesItsPictureToTheLookup() = withHarness {
        downloader.downloadMatch(
            MusicMatch(
                title = "Song", artist = "Artist", album = "Album", artworkUrl = "https://example.invalid/a.jpg",
                source = MusicMatch.Source.SEARCH, provider = "Audius", downloadUrl = "https://audius.co/a/song"
            )
        )
        settled(downloader.downloads.single().id)
        val hint = finished.single().second
        assertEquals("https://example.invalid/a.jpg", hint?.artworkUrl)
        assertEquals("Artist Song", hint?.query)
        assertEquals("Audius", hint?.provider)
    }

    @Test
    fun aYouTubeResultDoesNotCarryItsThumbnailAsTheAlbumPicture() = withHarness {
        downloader.downloadMatch(
            MusicMatch(
                title = "Song", artist = "Artist", album = null, artworkUrl = "https://i.ytimg.com/vi/x/hq.jpg",
                source = MusicMatch.Source.SEARCH, provider = "YouTube", downloadUrl = "https://www.youtube.com/watch?v=x"
            )
        )
        settled(downloader.downloads.single().id)
        // yt-dlp already embedded that frame, square; the lookup is for a real cover.
        assertNull(finished.single().second?.artworkUrl)
        assertEquals("Artist Song", finished.single().second?.query)
    }

    @Test
    fun aCatalogueMatchWithNoFileIsFoundOnYouTubeAndGetsItsCoverLookedFor() = withHarness {
        downloader.downloadMatch(
            MusicMatch(
                title = "Song", artist = "Artist", album = "Album", durationMs = 200_000,
                artworkUrl = "https://example.invalid/b.jpg", source = MusicMatch.Source.SEARCH, provider = "iTunes"
            )
        )
        val entry = settled(downloader.downloads.single().id)
        assertEquals(1, engine.targets.size)
        assertEquals(1, finished.size)
        assertEquals("https://example.invalid/b.jpg", finished.single().second?.artworkUrl)
        assertEquals("Cover added", entry.cover)
    }

    @Test
    fun aSpotifyLinkIsLookedUpToo() = withHarness {
        downloader.startDownload("https://open.spotify.com/track/abc")
        val entry = settled(downloader.downloads.single().id)
        assertEquals(1, rescans.get())
        assertEquals(listOf(entry.files), finished.map { it.first })
    }

    @Test
    fun aFailedDownloadLooksForNothing() = withHarness {
        engine.fail = true
        downloader.startDownload("https://www.youtube.com/watch?v=abc")
        val entry = runBlocking {
            repeat(200) { if (downloader.downloads.singleOrNull()?.done == true) return@repeat else delay(25) }
            downloader.downloads.single()
        }
        assertTrue(entry.failed)
        runBlocking { delay(150) }
        assertTrue(finished.isEmpty())
        assertNull(entry.cover)
    }

    @Test
    fun everySongOfAPlaylistGetsItsCoverLookedFor() = withHarness {
        // Fresh ids: the record of what was downloaded lives in the test data folder.
        val stamp = System.nanoTime()
        engine.songs = listOf(
            YouTubeVideo(id = "p1-$stamp", title = "One", channel = "Artist"),
            YouTubeVideo(id = "p2-$stamp", title = "Two", channel = "Artist")
        )
        downloader.downloadPlaylist = true
        downloader.startDownload("https://www.youtube.com/playlist?list=PLabc$stamp")
        val entry = runBlocking {
            repeat(400) {
                downloader.downloads.singleOrNull()?.takeIf { it.done && it.cover?.startsWith("Covers: 2 of 2") == true }
                    ?.let { return@runBlocking it }
                delay(25)
            }
            error("the playlist didn't settle: ${downloader.downloads}")
        }
        assertEquals(2, finished.size)
        assertEquals(2, added.size)
        assertEquals("Covers: 2 of 2 added", entry.cover)
    }
}
