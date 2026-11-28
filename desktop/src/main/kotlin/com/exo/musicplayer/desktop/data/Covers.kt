package com.exo.musicplayer.desktop.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.exo.musicplayer.data.net.Http
import com.exo.musicplayer.desktop.library.AudioKind
import com.exo.musicplayer.desktop.library.DesktopTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cover art for the desktop library.
 *
 * Three places art can come from, in the order they are cheapest to reach: the
 * downloaded cache, the tag embedded in the file, and a `cover.jpg` sitting next
 * to it — the convention every ripper and downloader already follows, so a
 * folder-based library usually has art before Lucense fetches anything.
 *
 * Taking a cover out of a song is the slow part - a tag read and a full-size
 * decode, tens of milliseconds - so it is done once: both sizes Lucense draws
 * are kept on disk ([CoverThumbs]) and come back from there in well under a
 * millisecond. The row-sized ones are small enough for a whole library's to
 * fit in memory, and [warm] brings them all in at startup, so a list scrolled
 * however fast has its covers there already, the way Spotify's has.
 *
 * Decoding happens off the UI thread and results are held in bounded memory
 * caches. This is the same lesson the Android build learned the hard way:
 * touching the filesystem while a list scrolls is what makes a list stutter.
 */
object Covers {

    /**
     * Longest edge, in pixels, of the covers the lists draw: at 30-46dp, which
     * this is sharp for on a screen scaled up to 175 %. Anything drawn larger -
     * the album grid, a player's own cover - has the [Thumbnails.MAX_EDGE] one.
     */
    const val SMALL_EDGE = 80

    /** About 1,900 different row covers: a whole library, for most. */
    private const val SMALL_BUDGET = 48L * 1024 * 1024

    /** Songs [warm] works on at once: quick, and leaving the rest of the machine alone. */
    private const val WARMERS = 3

    /** Covers at [Thumbnails.MAX_EDGE], by song. */
    private val memory = Thumbnails.ByteBudgetCache()

    /**
     * Row covers, held by picture rather than by song - an album's songs share
     * one cover, and so one bitmap - with [smallOf] saying which is whose.
     */
    private val small = Thumbnails.ByteBudgetCache(SMALL_BUDGET)
    private val smallOf = ConcurrentHashMap<String, String>()

    /** Paths already checked and found to have no art, so they aren't re-read. */
    private val misses = Thumbnails.BoundedKeySet(limit = 50_000)

    /** Both sizes of every cover, on disk. */
    private val disk: CoverThumbs by lazy { CoverThumbs(File(AppDirs.cache, "covers")) }

    /** The disk is tidied once a run, after the first whole library is warmed. */
    private val pruned = AtomicBoolean(false)

    /** Covers taken out of songs - the slow way - since the start, for the log. */
    private val rendered = AtomicInteger()

    /**
     * Goes up whenever a cover is fetched, so covers already on screen swap in
     * while a bulk run is still going rather than only after they scroll away.
     */
    var revision by mutableStateOf(0)
        private set

    private val sidecarNames = listOf(
        "cover.jpg", "cover.png", "folder.jpg", "folder.png",
        "front.jpg", "front.png", "album.jpg", "albumart.jpg"
    )

    /** The cover in memory at the size [edge] (pixels) asks for, or null. */
    fun cached(track: DesktopTrack, edge: Int = Thumbnails.MAX_EDGE): ImageBitmap? {
        val path = track.file.absolutePath
        return if (edge <= SMALL_EDGE) smallOf[path]?.let { small[it] } else memory[path]
    }

    fun knownMissing(track: DesktopTrack): Boolean = track.file.absolutePath in misses

    /** The cover's own bytes, as stored: for a picture handed to something else to show. */
    suspend fun bytes(track: DesktopTrack): ByteArray? = withContext(Dispatchers.IO) { sourceOf(track) }

    /** The cover at the size [edge] (pixels) asks for: from memory, else disk, else out of the song. */
    suspend fun load(track: DesktopTrack, edge: Int = Thumbnails.MAX_EDGE): ImageBitmap? =
        withContext(Dispatchers.IO) {
            if (edge <= SMALL_EDGE) loadSmall(track) else loadLarge(track)
        }

    /**
     * Brings every song's row cover into memory, a few songs at a time in the
     * background: from disk, or - the first time a song is seen - out of the
     * song and onto disk. Stops once memory's share is nearly full; the rest then
     * come from disk as they are drawn. Started again whenever the library
     * changes, and what is in memory already is passed over at once.
     */
    suspend fun warm(tracks: List<DesktopTrack>) {
        if (tracks.isEmpty()) return
        val started = System.nanoTime()
        val renderedBefore = rendered.get()
        val next = AtomicInteger()
        val full = AtomicBoolean(false)
        withContext(Dispatchers.IO) {
            repeat(WARMERS) {
                launch {
                    while (true) {
                        ensureActive()
                        if (small.approximateBytes > SMALL_BUDGET / 10 * 9) {
                            full.set(true)
                            break
                        }
                        val at = next.getAndIncrement()
                        if (at >= tracks.size) break
                        runCatching { loadSmall(tracks[at]) }
                    }
                }
            }
        }
        val inMemory = tracks.count { cached(it, SMALL_EDGE) != null }
        System.err.println(
            "Lucense: row covers ready for $inMemory of ${tracks.size} songs in " +
                "${(System.nanoTime() - started) / 1_000_000} ms (${rendered.get() - renderedBefore} taken out of the songs" +
                (if (full.get()) "; memory's share full, the rest come from disk as they show)" else ")")
        )
        // Every song seen: what is kept for songs no longer here, or changed since, can go.
        if (!full.get() && pruned.compareAndSet(false, true)) {
            withContext(Dispatchers.IO) {
                runCatching { disk.prune(tracks.mapNotNullTo(HashSet()) { disk.keyOf(it.file) }) }
            }
        }
    }

    /** Fetches [url], caches it beside the library and, where possible, embeds it. */
    suspend fun fetchAndStore(
        track: DesktopTrack,
        url: String,
        embedInFile: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        val bytes = download(url)?.let(Thumbnails::squared) ?: return@withContext false
        // Encoded once to prove it is a real image, and those encodings are what
        // is kept, rather than decoding it all over again.
        val sizes = encode(bytes) ?: return@withContext false
        val bitmap = Thumbnails.decodeScaled(sizes.large) ?: return@withContext false

        downloadedFile(track).writeBytes(bytes)
        if (embedInFile) runCatching { embed(track.file, bytes) }

        val path = track.file.absolutePath
        // Embedding changed the song, and with it the key its covers are kept under.
        disk.keyOf(track.file)?.let { key -> runCatching { disk.write(key, sizes) } }
        misses -= path
        memory[path] = bitmap
        keepSmall(path, sizes.small)
        revision++
        true
    }

    fun forget(track: DesktopTrack) {
        val path = track.file.absolutePath
        memory -= path
        smallOf.remove(path)
        misses -= path
        disk.keyOf(track.file)?.let(disk::forget)
    }

    /** Lets go of everything held in memory, as at a fresh start; what is on disk stays. */
    internal fun clearMemory() {
        memory.clear()
        small.clear()
        smallOf.clear()
        misses.clear()
    }

    /** Whether art is available without any network work. */
    fun hasLocalArt(track: DesktopTrack): Boolean {
        val key = disk.keyOf(track.file)
        if (key != null && disk.has(key)) return true
        return downloadedFile(track).exists() ||
            embedded(track.file) != null ||
            sidecar(track.file) != null
    }

    /**
     * Whether the song carries a cover of its own - inside the file, or fetched
     * for it earlier. Unlike [hasLocalArt] a picture lying in the same folder
     * (a `cover.jpg` in a flat download folder belongs to no song in
     * particular) doesn't count: a freshly downloaded song is judged on what it
     * has itself.
     */
    fun hasOwnArt(track: DesktopTrack): Boolean =
        downloadedFile(track).exists() || embedded(track.file) != null

    /** The row cover: from memory, else its few kilobytes on disk, else out of the song and onto disk. */
    private fun loadSmall(track: DesktopTrack): ImageBitmap? {
        cached(track, SMALL_EDGE)?.let { return it }
        val path = track.file.absolutePath
        if (path in misses) return null
        val key = disk.keyOf(track.file)
        val bytes = key?.let { disk.read(it, CoverThumbs.Size.SMALL) }
            ?: render(track, key)?.small
            ?: return null
        return keepSmall(path, bytes)
    }

    /** A row cover's bytes decoded once per picture, however many songs share it. */
    private fun keepSmall(path: String, bytes: ByteArray): ImageBitmap? {
        val picture = CoverThumbs.digest(bytes)
        val bitmap = small[picture]
            ?: Thumbnails.decodeScaled(bytes, SMALL_EDGE)?.also { small[picture] = it }
            ?: return null
        smallOf[path] = picture
        return bitmap
    }

    private fun loadLarge(track: DesktopTrack): ImageBitmap? {
        val path = track.file.absolutePath
        memory[path]?.let { return it }
        if (path in misses) return null
        val key = disk.keyOf(track.file)
        val bytes = key?.let { disk.read(it, CoverThumbs.Size.LARGE) }
            ?: render(track, key)?.large
            ?: return null
        val bitmap = Thumbnails.decodeScaled(bytes) ?: return null
        memory[path] = bitmap
        return bitmap
    }

    /**
     * Takes [track]'s cover out of the song - the slow part - and keeps both
     * sizes on disk under [key]. Null when it has none, which is kept too, so the
     * song isn't read for it again; only a cover put beside it since is looked for.
     */
    private fun render(track: DesktopTrack, key: String?): CoverThumbs.Sizes? {
        val path = track.file.absolutePath
        if (key == null) {
            // Not a file that can be keyed as it is: taken out, and not kept.
            return sourceOf(track)?.let(::encode).also { if (it == null) misses += path }
        }
        return disk.once(key) {
            // Done meanwhile by whoever had the key.
            val small = disk.read(key, CoverThumbs.Size.SMALL)
            val large = disk.read(key, CoverThumbs.Size.LARGE)
            if (small != null && large != null) return@once CoverThumbs.Sizes(small, large)

            val folder = folderStamp(track.file)
            rendered.incrementAndGet()
            val source = when (disk.noneStamp(key)) {
                // Never looked at: everywhere, the song's own tag included.
                null -> sourceOf(track)
                // Looked at, nothing found, and nothing new in its folder since.
                folder -> {
                    misses += path
                    return@once null
                }
                // The folder changed. The song didn't - it would have another key - so
                // only a cover beside it, or one fetched for it, can be new.
                else -> downloaded(track) ?: sidecar(track.file)
            }
            val sizes = source?.let(::encode)
            if (sizes == null) {
                runCatching { disk.markNone(key, folder) }
                misses += path
            } else {
                runCatching { disk.write(key, sizes) }
            }
            sizes
        }
    }

    private fun encode(bytes: ByteArray): CoverThumbs.Sizes? =
        Thumbnails.encodeSizes(bytes, SMALL_EDGE, Thumbnails.MAX_EDGE)
            ?.let { (small, large) -> CoverThumbs.Sizes(small, large) }

    /** Changes whenever a file is put in the song's folder, a cover.jpg included. */
    private fun folderStamp(file: File): Long = file.parentFile?.lastModified() ?: 0L

    private fun sourceOf(track: DesktopTrack): ByteArray? =
        downloaded(track) ?: embedded(track.file) ?: sidecar(track.file)

    private fun downloaded(track: DesktopTrack): ByteArray? =
        downloadedFile(track).takeIf { it.isFile }?.let { runCatching { it.readBytes() }.getOrNull() }

    private fun downloadedFile(track: DesktopTrack): File =
        File(AppDirs.covers, hash(track.file.absolutePath) + ".img")

    private fun embedded(file: File): ByteArray? = runCatching {
        AudioKind.read(file).tag?.firstArtwork?.binaryData
    }.getOrNull()

    private fun sidecar(file: File): ByteArray? {
        val dir = file.parentFile ?: return null
        for (name in sidecarNames) {
            val candidate = File(dir, name)
            if (candidate.isFile) return runCatching { candidate.readBytes() }.getOrNull()
        }
        return null
    }

    private fun embed(file: File, bytes: ByteArray) {
        val temp = File.createTempFile("lucense-art", ".jpg")
        try {
            temp.writeBytes(bytes)
            val audio = AudioKind.read(file)
            val tag = audio.tagOrCreateAndSetDefault
            tag.deleteArtworkField()
            tag.setField(ArtworkFactory.createArtworkFromFile(temp))
            audio.commit()
        } finally {
            temp.delete()
        }
    }

    private fun download(url: String): ByteArray? = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Lucense/1.0 (desktop)")
        }
        try {
            val code = connection.responseCode
            // Told apart from "there is no such picture", for the lookups that
            // must not call a song coverless because the network was down.
            if (code >= 500 || code == 429) Http.noteTrouble()
            if (code !in 200..299) return null
            connection.inputStream.use { it.readBytes() }.takeIf { it.size > 512 }
        } finally {
            connection.disconnect()
        }
    }.onFailure { if (it is java.io.IOException) Http.noteTrouble() }.getOrNull()

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}

/**
 * Writes metadata back into a file's tags.
 *
 * Two callers with opposite needs, hence [clearBlanks]. Automatic
 * identification must never wipe a field it has no opinion about, so a null or
 * blank value there means "leave it alone". Hand editing must be able to empty
 * a field, because correcting a wrong album name to nothing is a legitimate
 * edit and a writer that silently ignored it would look broken.
 */
/** A song's tags exactly as its own file had them. */
data class OriginalTags(
    val title: String?,
    val artist: String?,
    val album: String?,
    val year: String?,
    val genre: String?
)

object TagWriter {

    /**
     * Told what a file's tags say, immediately before anything changes them.
     *
     * A hook here rather than a call at each of the seven places tags get
     * written: the entire value of the snapshot is that it is never missed,
     * and a write path added later would otherwise quietly not be covered.
     */
    var beforeChange: ((File, OriginalTags) -> Unit)? = null

    private fun snapshot(file: File, tag: Tag) {
        val hook = beforeChange ?: return
        fun field(key: FieldKey): String? =
            runCatching { tag.getFirst(key)?.trim()?.takeIf { it.isNotEmpty() } }.getOrNull()
        // Never let remembering the old tags stop the new ones being written.
        runCatching {
            hook(
                file,
                OriginalTags(
                    title = field(FieldKey.TITLE),
                    artist = field(FieldKey.ARTIST),
                    album = field(FieldKey.ALBUM),
                    year = field(FieldKey.YEAR),
                    genre = field(FieldKey.GENRE)
                )
            )
        }
    }

    fun write(
        file: File,
        title: String?,
        artist: String?,
        album: String?,
        year: Int?,
        genre: String? = null,
        clearBlanks: Boolean = false
    ): Result<Unit> = runCatching {
        val audio = AudioKind.read(file)
        val tag = audio.tagOrCreateAndSetDefault
        snapshot(file, tag)

        fun apply(key: FieldKey, value: String?) {
            val text = value?.trim()
            when {
                !text.isNullOrEmpty() -> tag.setField(key, text)
                clearBlanks -> runCatching { tag.deleteField(key) }
            }
        }

        apply(FieldKey.TITLE, title)
        apply(FieldKey.ARTIST, artist)
        apply(FieldKey.ALBUM, album)
        apply(FieldKey.YEAR, year?.toString())
        apply(FieldKey.GENRE, genre)
        audio.commit()
    }

    /** Writes only the fields [change] names, leaving the rest of the tag alone. */
    fun change(change: TagChange): Result<Unit> = runCatching {
        val audio = AudioKind.read(change.file)
        val tag = audio.tagOrCreateAndSetDefault
        snapshot(change.file, tag)
        change.title?.let { tag.setField(FieldKey.TITLE, it) }
        change.artist?.let { tag.setField(FieldKey.ARTIST, it) }
        change.album?.let { tag.setField(FieldKey.ALBUM, it) }
        change.albumArtist?.let { tag.setField(FieldKey.ALBUM_ARTIST, it) }
        change.year?.let { tag.setField(FieldKey.YEAR, it) }
        change.genre?.let { tag.setField(FieldKey.GENRE, it) }
        audio.commit()
    }
}
