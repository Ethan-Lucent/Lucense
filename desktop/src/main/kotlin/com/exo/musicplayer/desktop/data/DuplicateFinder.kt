package com.exo.musicplayer.desktop.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.exo.musicplayer.data.library.AudioPrint
import com.exo.musicplayer.desktop.library.DesktopTrack
import com.exo.musicplayer.desktop.library.PrintReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Songs kept more than once, found by how they sound, and the spare copies
 * sent to the Recycle Bin.
 *
 * Part of [DesktopController], which makes it and hands the UI's calls through.
 * [scope] is the controller's own, so a failure here is caught and logged like
 * any of its other work. The library and its play counts are the controller's,
 * read through [tracks] and [playCounts]; [rescan] reads the library again once
 * copies are gone.
 */
internal class DuplicateFinder(
    private val scope: CoroutineScope,
    private val store: DesktopStore,
    private val tracks: () -> List<DesktopTrack>,
    private val playCounts: () -> Map<String, Int>,
    private val rescan: () -> Unit
) {
    var duplicates by mutableStateOf<List<DesktopDuplicateGroup>>(emptyList())
        private set
    var duplicatesScanning by mutableStateOf(false)
        private set
    var duplicatesNote by mutableStateOf<String?>(null)
        private set

    /** Songs listened to so far in a duplicates scan, and how many need it; null when not listening. */
    var duplicatesListening by mutableStateOf<Pair<Int, Int>?>(null)
        private set

    /**
     * Finds the same recording stored more than once, by how it sounds.
     *
     * Each song's print is taken once and kept, so only files that are new or
     * have changed are listened to; a second scan of an unchanged library is
     * nearly instant. Names play no part: "Track 03.mp3" and a correctly
     * tagged copy of the same song are found, and two different songs that
     * happen to share a title are not.
     */
    fun findDuplicates(within: List<DesktopTrack>? = null) {
        if (duplicatesScanning) return
        duplicatesScanning = true
        duplicatesNote = null
        scope.launch {
            val songs = within ?: tracks()
            val stored = io { store.prints() }
            val prints = HashMap<String, IntArray>()
            val todo = mutableListOf<DesktopTrack>()
            songs.forEach { track ->
                val path = track.file.absolutePath
                val saved = stored[path]
                val decoded = saved?.takeIf {
                    it.size == track.file.length() && it.modified == track.file.lastModified()
                }?.let { AudioPrint.decode(it.print) }
                if (decoded != null) prints[path] = decoded else todo += track
            }

            if (todo.isNotEmpty()) {
                duplicatesListening = 0 to todo.size
                var done = 0
                val work = Channel<DesktopTrack>(Channel.UNLIMITED)
                todo.forEach { work.trySend(it) }
                work.close()
                coroutineScope {
                    repeat(PRINTS_AT_ONCE) {
                        launch {
                            for (track in work) {
                                val print = io { PrintReader.read(track.file, track.durationMs) }
                                if (print != null) {
                                    val path = track.file.absolutePath
                                    prints[path] = print
                                    io {
                                        store.savePrint(path, track.file.length(), track.file.lastModified(), AudioPrint.encode(print))
                                    }
                                }
                                done++
                                duplicatesListening = done to todo.size
                            }
                        }
                    }
                }
                duplicatesListening = null
            }

            val counts = playCounts()
            val byPath = songs.associateBy { it.file.absolutePath }
            val found = io {
                // Reading embedded art opens the file, so a comparator that
                // called it directly would re-read the same track O(log n) times.
                val hasArt = HashMap<String, Boolean>()
                // Biggest file first as a stand-in for bitrate, then one with
                // art, then the one actually played.
                val keeperOrder = compareByDescending<DesktopTrack> { it.sizeBytes }
                    .thenByDescending {
                        hasArt.getOrPut(it.file.absolutePath) { Covers.hasLocalArt(it) }
                    }
                    .thenByDescending { counts[it.file.absolutePath] ?: 0 }
                    .thenBy { it.file.lastModified() }
                AudioPrint.groups(
                    prints.mapNotNull { (path, print) ->
                        byPath[path]?.let { AudioPrint.Entry(path, print, it.durationMs) }
                    }
                ).map { paths ->
                    val members = paths.mapNotNull { byPath[it] }.sortedWith(keeperOrder)
                    DesktopDuplicateGroup(members.first(), members.drop(1))
                }.sortedBy { it.keep.title.lowercase(Locale.ROOT) }
            }
            duplicates = found
            duplicatesScanning = false
            if (found.isEmpty()) duplicatesNote = "No song is in the library twice."
        }
    }

    /**
     * Sends the chosen copies to the Recycle Bin where the platform allows it.
     *
     * Deliberately not [File.delete]: these are the user's own files, sitting in
     * their own folders, and a wrong match should be recoverable.
     */
    fun removeDuplicates(groups: List<DesktopDuplicateGroup>) {
        scope.launch {
            val paths = groups.flatMap { it.remove }.map { it.file }
            val removed = io {
                paths.count { file ->
                    runCatching {
                        val desktop = java.awt.Desktop.getDesktop()
                        if (desktop.isSupported(java.awt.Desktop.Action.MOVE_TO_TRASH)) {
                            desktop.moveToTrash(file)
                        } else {
                            file.delete()
                        }
                    }.getOrDefault(false)
                }
            }
            io { store.forgetPaths(paths.map { it.absolutePath }) }
            duplicates = emptyList()
            duplicatesNote = "Removed $removed file${if (removed == 1) "" else "s"}."
            rescan()
        }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}

/** Files listened to at once for duplicates by sound: decoding is the slow part. */
private const val PRINTS_AT_ONCE = 6
