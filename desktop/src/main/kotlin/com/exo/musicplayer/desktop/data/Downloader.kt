package com.exo.musicplayer.desktop.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.exo.musicplayer.data.download.DownloadFormat
import com.exo.musicplayer.data.download.DownloadQuality
import com.exo.musicplayer.data.download.LinkResolver
import com.exo.musicplayer.data.download.ResolvedLink
import com.exo.musicplayer.data.download.YouTubeTrouble
import com.exo.musicplayer.data.recognition.MusicMatch
import com.exo.musicplayer.data.youtube.YouTubeFormat
import com.exo.musicplayer.data.youtube.YouTubeLinkFinder
import com.exo.musicplayer.desktop.download.DesktopYouTubeBackend
import com.exo.musicplayer.desktop.download.DownloadArchive
import com.exo.musicplayer.desktop.download.DownloadProgress
import com.exo.musicplayer.desktop.download.ToolStatus
import com.exo.musicplayer.desktop.download.YtDlp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Download page's machinery: the download choices, yt-dlp and ffmpeg, and
 * the queue, from a link or a song's name to files in the library.
 *
 * Part of [DesktopController], which makes it and hands the UI's calls through.
 * [scope] is the controller's own, so a failure here is caught and logged like
 * any of its other work. Finished files go to the controller through
 * [addToLibrary]; a Spotify download asks it to [rescan] instead.
 */
internal class Downloader(
    private val scope: CoroutineScope,
    private val settings: DesktopSettings,
    private val youtubeBackend: DesktopYouTubeBackend,
    private val linkFinder: YouTubeLinkFinder,
    private val signIn: YouTubeSignInSettings,
    private val addToLibrary: (List<File>) -> Unit,
    private val rescan: () -> Unit,
    /** yt-dlp and spotdl; a parameter so a test can stand in for them. */
    private val engine: DownloadEngine = YtDlpEngine(youtubeBackend),
    /** Looks for a cover for songs that landed without one. */
    private val covers: CoverFinishing = CoverFinishing { _, _ -> emptyList() }
) {
    var tools by mutableStateOf(ToolStatus())
        internal set
    var toolNote by mutableStateOf<String?>(null)
    var toolProgress by mutableStateOf<Float?>(null)
        private set
    var downloadUrl by mutableStateOf("")
    private val formatState = mutableStateOf(
        settings.downloadFormatName.takeIf { it != ORIGINAL_FORMAT }?.let(DownloadFormat::fromName)
    )

    /** What downloads are converted to; null keeps whatever the site served. */
    var downloadFormat: DownloadFormat?
        get() = formatState.value
        set(value) {
            formatState.value = value
            settings.downloadFormatName = value?.name ?: ORIGINAL_FORMAT
        }

    private val cutNonMusicState = mutableStateOf(settings.downloadCutNonMusic)

    /** Whether SponsorBlock's non-music parts of music videos are cut out. */
    var downloadCutNonMusic: Boolean
        get() = cutNonMusicState.value
        set(value) {
            cutNonMusicState.value = value
            settings.downloadCutNonMusic = value
        }

    private val embedArtState = mutableStateOf(settings.downloadEmbedArt)

    /** Kept, like the rest of the download choices, so they are as left next time. */
    var downloadEmbedArt: Boolean
        get() = embedArtState.value
        set(value) {
            embedArtState.value = value
            settings.downloadEmbedArt = value
        }

    private val wholePlaylistState = mutableStateOf(settings.downloadPlaylist)

    var downloadPlaylist: Boolean
        get() = wholePlaylistState.value
        set(value) {
            wholePlaylistState.value = value
            settings.downloadPlaylist = value
        }

    private val qualityState = mutableStateOf(
        DownloadQuality.fromName(settings.downloadQualityName)
    )
    var downloadQuality: DownloadQuality
        get() = qualityState.value
        set(value) {
            qualityState.value = value
            settings.downloadQualityName = value.name
        }
    /** Finished downloads the Download page has already shown. */
    private var acknowledgedDownloads by mutableStateOf<Set<Long>>(emptySet())

    /** Downloads that finished since the Download page was last looked at. */
    val unseenFinishedDownloads: Int
        get() = downloads.count { it.done && !it.failed && it.id !in acknowledgedDownloads }

    fun acknowledgeDownloads() {
        val finished = downloads.filter { it.done }.map { it.id }.toSet()
        // Written only on change: this runs from an effect keyed on the list, and
        // an unconditional write would keep re-triggering it.
        if (!acknowledgedDownloads.containsAll(finished)) {
            acknowledgedDownloads = acknowledgedDownloads + finished
        }
    }

    var downloads by mutableStateOf<List<DownloadEntry>>(emptyList())
        private set
    var downloadLog by mutableStateOf<List<String>>(emptyList())
        private set

    fun refreshTools() {
        scope.launch {
            tools = io { YtDlp.status() }
            if (tools.ytDlp) tools = tools.copy(ytDlpVersion = YtDlp.version())
        }
    }

    /**
     * Looks for a newer yt-dlp when the one here hasn't been checked for a
     * couple of days. YouTube changes under it every few weeks, and the usual
     * sign is every download failing at once; nobody should have to know
     * there is an update button to get past that. Quiet: it says nothing
     * unless it installed something.
     */
    fun updateYtDlpIfStale() {
        scope.launch {
            val mark = File(AppDirs.tools, "yt-dlp.checked")
            val stale = System.currentTimeMillis() - mark.lastModified() > YT_DLP_STALE_AFTER_MS
            if (!stale || !io { ToolPaths.ytDlp.isFile }) return@launch
            val before = YtDlp.version()
            val result = YtDlp.update { }
            if (result.isSuccess) {
                io { runCatching { mark.writeText(System.currentTimeMillis().toString()) } }
                val after = YtDlp.version()
                if (after != null && after != before) toolNote = "yt-dlp updated itself to $after."
                refreshTools()
            }
        }
    }

    fun updateYtDlp() {
        scope.launch {
            toolNote = "Updating yt-dlp…"
            val result = YtDlp.update { line -> downloadLog = (downloadLog + line).takeLast(200) }
            toolNote = result.fold(
                onSuccess = { "yt-dlp updated." },
                onFailure = { "Update failed: ${it.message}" }
            )
            refreshTools()
        }
    }

    /** Queues [rawUrl], resolving Spotify links to a searchable name first. */
    fun startDownload(rawUrl: String = downloadUrl) {
        if (queueDownload(rawUrl.trim(), song = null) != null) downloadUrl = ""
    }

    /**
     * A song known by its artist, title and length rather than by a link,
     * which is what Discover has. Found on YouTube first and checked against
     * all three, then downloaded like anything else. Returns the entry's id,
     * for following it in [downloads], or null when nothing was queued.
     */
    fun downloadSong(artist: String, title: String, durationMs: Long?): Long? = queueDownload(
        listOf(artist, title).filter { it.isNotBlank() }.joinToString(" - "),
        YouTubeLinkFinder.Wanted(
            title = title,
            artist = artist.takeIf { it.isNotBlank() },
            durationMs = durationMs?.takeIf { it > 0 }
        )
    )

    /**
     * Songs downloading by name at once. A whole album asked for together
     * would otherwise be a dozen searches and a dozen yt-dlps side by side.
     */
    private val songSlots = Semaphore(2)

    fun queueDownload(url: String, song: YouTubeLinkFinder.Wanted?): Long? = queueDownload(url, song, hint = null)

    internal fun queueDownload(url: String, song: YouTubeLinkFinder.Wanted?, hint: CoverHint?): Long? {
        if (url.isBlank()) return null
        if (!tools.ready) {
            toolNote = "Install yt-dlp first — one click, about 12 MB."
            return null
        }

        val entry = DownloadEntry(
            id = System.nanoTime(),
            target = url,
            display = url,
            song = song,
            hint = hint
        )
        synchronized(queueLock) { downloads = downloads + entry }

        scope.launch {
            if (song != null) {
                update(entry.id) { it.status = "Waiting…" }
                songSlots.withPermit { runDownload(entry, url, song) }
            } else {
                runDownload(entry, url, null)
            }
        }
        return entry.id
    }

    /** How one link's download went, and whether YouTube refused the connection on the way. */
    private class Fetched(val result: Result<List<File>>, val refusedByYouTube: Boolean)

    /**
     * One link fetched. A failure carries what the site said, rather than how
     * yt-dlp exited, which is all its own error amounts to.
     */
    private suspend fun fetch(
        target: String,
        wholePlaylist: Boolean,
        onProgress: (DownloadProgress) -> Unit
    ): Fetched {
        var refused = false
        var said: String? = null
        val result = engine.download(
            target = target,
            destination = File(settings.downloadDir),
            format = downloadFormat?.takeIf { tools.ffmpeg },
            embedThumbnail = downloadEmbedArt,
            wholePlaylist = wholePlaylist,
            quality = downloadQuality,
            cutNonMusic = downloadCutNonMusic,
            onProgress = { progress ->
                if (YouTubeTrouble.refused(progress.line) && !refused) {
                    refused = true
                    signIn.renewSignInQuietly(refused = true)
                }
                if (progress.line.startsWith("ERROR")) said = progress.line
                onProgress(progress)
            }
        )
        val line = said
        return Fetched(
            if (result.isFailure && line != null) {
                Result.failure(
                    IllegalStateException(
                        if (LinkResolver.isYouTube(target)) YouTubeTrouble.explain(line) else YouTubeTrouble.reason(line)
                    )
                )
            } else {
                result
            },
            refused && LinkResolver.isYouTube(target)
        )
    }

    /**
     * A playlist, song by song.
     *
     * It used to be handed to yt-dlp whole. Nothing was saved until the last
     * song finished, songs that failed were dropped without a word, and when
     * YouTube refused the lot - it does after a few dozen downloads in a row -
     * a hundred songs came back as one failure about "this song". Now each
     * song is its own download, several at a time, lands in the library as it
     * finishes, and is remembered, so asking for the same playlist again
     * fetches only what is missing.
     */
    private suspend fun downloadSongsOf(entry: DownloadEntry, link: String) {
        update(entry.id) { it.status = "Reading the playlist…" }
        val (playlist, problem) = engine.playlist(link)
        val songs = playlist?.songs.orEmpty()
        if (songs.isEmpty()) {
            val said = problem?.let(YouTubeTrouble::reason)?.takeIf { it.isNotBlank() }
            update(entry.id) {
                it.done = true
                it.failed = true
                it.status = if (said != null) {
                    "The playlist couldn't be read: $said"
                } else {
                    "The playlist couldn't be read. A private playlist has to be made public or unlisted first."
                }
            }
            return
        }
        update(entry.id) { it.display = "Playlist · ${playlist?.title ?: link}" }

        val saved = ArrayList<File>()
        val missed = ArrayList<String>()
        var already = 0
        var finished = 0

        // What YouTube will put up with, worked out as it goes. A refusal
        // makes every worker hold off for a while. Several running mean it is
        // refusing the connection, and asking for the rest would only prolong
        // that, so the playlist stops there, to be picked up again later.
        var refusalsRunning = 0
        var stopped = false
        var holdUntil = 0L
        var lastStart = 0L

        val tally = Mutex()
        val gate = Mutex()
        val next = AtomicInteger(0)

        fun report(now: String?) = update(entry.id) {
            it.status = "$finished of ${songs.size}"
            if (now != null) it.note = now
            // Never quite nothing, so the row shows how far it is rather than a bar going nowhere.
            it.percent = (finished + 0.05f) / songs.size
            it.files = saved.toList()
        }
        report(null)

        // Several at a time, each landing in the library as it finishes. The
        // songs still start no closer together than the gap, so the speed
        // comes from overlapping the downloads and conversions rather than
        // from asking YouTube faster.
        coroutineScope {
            repeat(minOf(PLAYLIST_WORKERS, songs.size)) {
                launch {
                    while (true) {
                        if (tally.withLock { stopped }) break
                        val index = next.getAndIncrement()
                        if (index >= songs.size) break
                        val video = songs[index]

                        if (DownloadArchive.fileFor(video.id) != null) {
                            tally.withLock {
                                already++
                                finished++
                                report(null)
                            }
                            continue
                        }

                        gate.withLock {
                            val hold = tally.withLock { holdUntil }
                            val wait = maxOf(lastStart + PLAYLIST_GAP_MS, hold) - System.currentTimeMillis()
                            if (wait > 0) delay(wait)
                            lastStart = System.currentTimeMillis()
                        }
                        if (tally.withLock { stopped }) break
                        tally.withLock { report(video.title) }

                        val fetched = fetch(video.watchUrl, wholePlaylist = false) { progress ->
                            if (progress.line.isNotBlank()) downloadLog = (downloadLog + progress.line).takeLast(200)
                        }
                        val files = fetched.result.getOrDefault(emptyList())

                        tally.withLock {
                            if (files.isEmpty()) {
                                missed += video.title
                            } else {
                                saved += files
                                DownloadArchive.add(video.id, files.first())
                            }
                            if (fetched.refusedByYouTube) {
                                holdUntil = System.currentTimeMillis() + PLAYLIST_HOLD_MS
                                if (++refusalsRunning >= REFUSALS_BEFORE_GIVING_UP) stopped = true
                            } else {
                                refusalsRunning = 0
                            }
                            finished++
                            report(null)
                        }
                        // Into the library as each one lands, not when the last one has;
                        // its cover is looked for in the background, one song at a time.
                        landed(entry.id, files, null)
                    }
                }
            }
        }

        val gone = playlist?.gone ?: 0
        val untried = songs.size - finished
        update(entry.id) {
            it.done = true
            it.percent = 1f
            it.files = saved.toList()
            it.failed = saved.isEmpty() && already == 0
            it.missed = missed.size + untried
            it.status = buildString {
                append("Saved ${saved.size} of ${songs.size}")
                if (already > 0) append(", $already already downloaded")
                if (missed.size + untried > 0) append(", ${missed.size + untried} not downloaded")
            }
            it.note = listOfNotNull(
                if (stopped) {
                    "YouTube began refusing this connection" +
                        (if (untried > 0) ", so $untried ${if (untried == 1) "song wasn't" else "songs weren't"} tried. " else ". ") +
                        YouTubeTrouble.WHY_REFUSED +
                        " Then download the playlist again: the songs already saved are skipped."
                } else {
                    null
                },
                missed.takeIf { list -> list.isNotEmpty() }?.let { list ->
                    "Not downloaded: " + list.take(MISSED_NAMED).joinToString("; ") +
                        if (list.size > MISSED_NAMED) "; and ${list.size - MISSED_NAMED} more." else "."
                },
                if (gone > 0) "$gone of the playlist's videos ${if (gone == 1) "is" else "are"} private or deleted." else null
            ).joinToString("\n").takeIf { text -> text.isNotBlank() }
        }
    }

    /** A `ytsearch1:` prefix left over from before, accepted and ignored. */
    private val searchPrefix = Regex("""^ytsearch\d*:""", RegexOption.IGNORE_CASE)

    private suspend fun runDownload(entry: DownloadEntry, url: String, song: YouTubeLinkFinder.Wanted?) {
        if (isSpotify(url)) {
            downloadWithSpotdl(entry.id, url)
            return
        }

        // A song name rather than a link: found on YouTube first, and the
        // link that was found is what yt-dlp is given.
        val target = if (song != null) {
            update(entry.id) { it.status = "Finding it on YouTube…" }
            findYouTubeLink(entry.id, song)
                ?: return
        } else if (!url.startsWith("http", ignoreCase = true)) {
            val text = url.replace(searchPrefix, "").trim()
            update(entry.id) { it.status = "Finding it on YouTube…" }
            findYouTubeLink(entry.id, YouTubeLinkFinder.Wanted(title = text))
                ?: return
        } else when (val resolved = io { LinkResolver.resolve(url) }) {
            is ResolvedLink.Direct -> {
                update(entry.id) { it.display = "${resolved.service} · $url" }
                resolved.url
            }
            is ResolvedLink.Search -> {
                update(entry.id) {
                    it.display = resolved.display
                    it.note = resolved.note
                }
                findYouTubeLink(entry.id, YouTubeLinkFinder.Wanted(title = resolved.query))
                    ?: return
            }
            is ResolvedLink.Unsupported -> {
                update(entry.id) {
                    it.failed = true
                    it.done = true
                    it.status = resolved.reason
                }
                return
            }
        }

        // A playlist is a list of songs, not one long download: each is
        // fetched, and can fail, on its own.
        if (song == null && LinkResolver.isYouTubePlaylist(target, downloadPlaylist)) {
            downloadSongsOf(entry, target)
            return
        }

        update(entry.id) { it.status = "Starting…" }
        val result = fetch(target, wholePlaylist = downloadPlaylist && song == null) { progress ->
            onDownloadProgress(entry.id, progress)
        }.result
        // A song asked for by name is looked for by that name; the file's tags
        // are only what the video's title made of it.
        val hint = entry.hint ?: song?.let { CoverHint(query = it.query) }

        result.fold(
            onSuccess = { files ->
                update(entry.id) {
                    it.done = true
                    it.percent = 1f
                    it.files = files
                    it.status = if (files.isEmpty()) {
                        "Finished, but nothing was produced."
                    } else {
                        "Saved ${files.size} file${if (files.size == 1) "" else "s"}"
                    }
                }
                // Straight into the library, wherever the download folder is.
                landed(entry.id, files, hint)
            },
            onFailure = { error ->
                update(entry.id) {
                    it.done = true
                    it.failed = true
                    it.status = error.message ?: "Download failed."
                }
            }
        )
    }

    /**
     * Sends a search result to the downloader.
     *
     * Services that host audio (YouTube, Audius, the Internet Archive) carry a
     * real page URL. Pure metadata catalogues — iTunes, MusicBrainz, Genius —
     * do not, so the artist and title are handed to yt-dlp as a search instead,
     * which is the same fallback a Spotify link takes.
     */
    /**
     * Finds the YouTube video that is the song and returns its link.
     *
     * When nothing suitable turns up the entry fails with the reason, rather
     * than saving an edit or a cover that would look like success.
     */
    private suspend fun findYouTubeLink(id: Long, wanted: YouTubeLinkFinder.Wanted): String? =
        when (val outcome = linkFinder.find(wanted)) {
            is YouTubeLinkFinder.Outcome.Found -> {
                val video = outcome.pick.video
                update(id) { entry ->
                    entry.display = "YouTube · ${video.title}"
                    entry.note = listOfNotNull(
                        entry.note,
                        buildString {
                            append("Found: ${video.channel}")
                            video.durationSeconds?.let { append(" · ${YouTubeFormat.duration(it)}") }
                            video.viewCount?.let { append(" · ${YouTubeFormat.views(it)}") }
                            append(" · ${video.watchUrl}")
                        }
                    ).joinToString("\n")
                    entry.status = "Starting…"
                }
                video.watchUrl
            }

            is YouTubeLinkFinder.Outcome.NothingSuitable -> {
                update(id) { entry ->
                    entry.done = true
                    entry.failed = true
                    entry.status = outcome.message
                }
                null
            }
        }

    fun downloadMatch(match: MusicMatch) {
        if (match.drmProtected) {
            toolNote = "${match.provider} streams DRM-protected audio - there is no file " +
                "to download. Search by name to find it somewhere that hosts it."
            return
        }
        if (!tools.ready) {
            toolNote = "Install yt-dlp first — one click, about 12 MB."
            return
        }
        val hint = hintOf(match)
        val direct = match.downloadUrl?.takeIf { it.startsWith("http", ignoreCase = true) }
        if (direct != null) {
            if (queueDownload(direct, song = null, hint = hint) != null) downloadUrl = ""
            return
        }

        val entry = DownloadEntry(
            id = System.nanoTime(),
            target = match.display,
            display = match.display,
            note = "${match.provider} has no downloadable file, so the same track is " +
                "found on YouTube first and that link is downloaded.",
            hint = hint
        )
        synchronized(queueLock) { downloads = downloads + entry }
        scope.launch {
            update(entry.id) { it.status = "Finding it on YouTube…" }
            // The match's own title, artist and length, so the finder can refuse
            // an edit that happens to share the name.
            val link = findYouTubeLink(
                entry.id,
                YouTubeLinkFinder.Wanted(
                    title = match.title,
                    artist = match.artist,
                    durationMs = match.durationMs
                )
            ) ?: return@launch
            val result = engine.download(
                target = link,
                destination = File(settings.downloadDir),
                format = downloadFormat?.takeIf { tools.ffmpeg },
                embedThumbnail = downloadEmbedArt,
                wholePlaylist = false,
                quality = downloadQuality,
                cutNonMusic = downloadCutNonMusic,
                onProgress = { progress -> onDownloadProgress(entry.id, progress) }
            )
            result.fold(
                onSuccess = { files ->
                    update(entry.id) {
                        it.done = true
                        it.percent = 1f
                        it.files = files
                        it.status = "Saved ${files.size} file${if (files.size == 1) "" else "s"}"
                    }
                    landed(entry.id, files, hint)
                },
                onFailure = { error ->
                    update(entry.id) {
                        it.done = true
                        it.failed = true
                        it.status = error.message ?: "Download failed."
                    }
                }
            )
        }
    }

    private fun isSpotify(url: String): Boolean =
        runCatching { java.net.URI(url).host.orEmpty().lowercase() }
            .getOrDefault("")
            .contains("spotify.com")

    /**
     * Hands a Spotify link to spotdl.
     *
     * spotdl reads Spotify's metadata and fetches the matching recording from
     * YouTube; Spotify's own audio is encrypted and no tool can download it. So
     * what arrives is a match rather than the Spotify file — usually the same
     * recording, occasionally a live take or a remaster. The note says so on the
     * row rather than leaving it to be discovered on listening.
     */
    private suspend fun downloadWithSpotdl(id: Long, url: String) {
        update(id) {
            it.status = "Reading Spotify…"
            it.note = "Spotify's audio is DRM protected, so spotdl looks the track up " +
                "and fetches the same recording from YouTube. Check it is the version " +
                "you wanted."
        }
        val result = engine.downloadSpotify(
            url = url,
            destination = File(settings.downloadDir),
            format = downloadFormat,
            quality = downloadQuality,
            onProgress = { progress -> onDownloadProgress(id, progress) }
        )
        result.fold(
            onSuccess = { files ->
                update(id) {
                    it.done = true
                    it.percent = 1f
                    it.files = files
                    it.status = if (files.isEmpty()) {
                        "Finished, but nothing was produced."
                    } else {
                        "Saved ${files.size} file${if (files.size == 1) "" else "s"}"
                    }
                }
                rescan()
                // spotdl embeds Spotify's own picture when it can; a song it
                // left bare is looked for like any other.
                findCovers(id, files, null)
            },
            onFailure = { error ->
                update(id) {
                    it.done = true
                    it.failed = true
                    it.status = error.message ?: "Download failed."
                }
            }
        )
    }

    /**
     * What a finished download needs doing to it, whichever way it came:
     * into the library, then - in the background, so the queue isn't held up -
     * a cover if it landed without one. Every path ends here.
     */
    private fun landed(id: Long, files: List<File>, hint: CoverHint?) {
        if (files.isEmpty()) return
        addToLibrary(files)
        findCovers(id, files, hint)
    }

    private val coverTallies = HashMap<Long, CoverTally>()

    private fun findCovers(id: Long, files: List<File>, hint: CoverHint?) {
        if (files.isEmpty()) return
        scope.launch {
            val outcomes = try {
                covers.finish(files, hint)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            val note = synchronized(coverTallies) {
                coverTallies.getOrPut(id) { CoverTally() }.also { it.add(outcomes) }.note()
            }
            update(id) { it.cover = note }
        }
    }

    /** What a catalogue match tells the cover lookup: its own names, and its picture unless that is just a video frame. */
    private fun hintOf(match: MusicMatch) = CoverHint(
        query = listOfNotNull(match.artist?.takeIf { it.isNotBlank() }, match.title).joinToString(" "),
        // YouTube's picture is the video's thumbnail, which yt-dlp has already embedded, square.
        artworkUrl = match.artworkUrl?.takeIf { !match.provider.equals("YouTube", ignoreCase = true) },
        provider = match.provider.takeIf { it.isNotBlank() }
    )

    private fun onDownloadProgress(id: Long, progress: DownloadProgress) {
        if (progress.line.isNotBlank()) {
            downloadLog = (downloadLog + progress.line).takeLast(200)
        }
        update(id) {
            progress.percent?.let { percent -> it.percent = percent }
            if (progress.line.startsWith("[ExtractAudio]")) it.status = "Converting…"
            else if (progress.percent != null) it.status = "Downloading"
        }
    }

    /**
     * Guards the queue. Downloads report from their own threads, several at
     * once, and each report reads the list and writes it back: without this,
     * two landing together lose one, and a song could finish without the row
     * ever saying so.
     */
    private val queueLock = Any()

    private fun update(id: Long, change: (DownloadEntry) -> Unit) = synchronized(queueLock) {
        downloads = downloads.map { entry ->
            if (entry.id != id) entry else entry.copy().also(change)
        }
    }

    /**
     * Tries one item of the queue again, in place: a song or a link that
     * failed, or the songs a playlist missed (those it saved are skipped, so
     * only the missed ones are fetched).
     */
    fun retryDownload(id: Long) {
        val entry = synchronized(queueLock) { downloads.firstOrNull { it.id == id } } ?: return
        if (!entry.done || !(entry.failed || entry.missed > 0)) return
        if (!tools.ready) {
            toolNote = "Install yt-dlp first — one click, about 12 MB."
            return
        }
        update(id) {
            it.done = false
            it.failed = false
            it.percent = 0f
            it.status = "Waiting…"
            it.note = null
            it.missed = 0
        }
        scope.launch {
            val fresh = synchronized(queueLock) { downloads.firstOrNull { it.id == id } } ?: return@launch
            val song = entry.song
            if (song != null) {
                songSlots.withPermit { runDownload(fresh, entry.target, song) }
            } else {
                runDownload(fresh, entry.target, null)
            }
        }
    }

    fun clearFinishedDownloads() = synchronized(queueLock) {
        downloads = downloads.filterNot { it.done }
    }

    fun chooseDownloadDir(dir: File) {
        settings.downloadDir = dir.absolutePath
    }

    val downloadDir: String get() = settings.downloadDir

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private companion object {
        /** YouTube refusals in a row before a playlist stops asking it. */
        const val REFUSALS_BEFORE_GIVING_UP = 5

        /** How long a yt-dlp is trusted before it is checked for a newer one. */
        const val YT_DLP_STALE_AFTER_MS = 2L * 24 * 60 * 60 * 1000

        /** What the format setting holds for "keep what the site served". */
        const val ORIGINAL_FORMAT = "ORIGINAL"

        /** Songs of a playlist downloading at once. */
        const val PLAYLIST_WORKERS = 4

        /** The least time between one song of a playlist starting and the next. */
        const val PLAYLIST_GAP_MS = 1_000L

        /** How long YouTube is left alone after it refuses a song. */
        const val PLAYLIST_HOLD_MS = 20_000L

        /** How many of a playlist's missed songs are named before "and N more". */
        const val MISSED_NAMED = 12
    }
}
