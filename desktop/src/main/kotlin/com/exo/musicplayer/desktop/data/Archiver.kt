package com.exo.musicplayer.desktop.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.exo.musicplayer.data.archive.ArchiveEntry
import com.exo.musicplayer.data.archive.MusicArchive
import com.exo.musicplayer.desktop.library.DesktopTrack
import com.exo.musicplayer.desktop.system.Explorer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Zip and ship: songs packed into one zip in the downloads folder, with how
 * it is going.
 *
 * Part of [DesktopController], which picks what goes in (the library, a
 * playlist, the selection) and hands the UI's calls through. [scope] is the
 * controller's own, so a failure here is caught and logged like any of its
 * other work.
 */
internal class Archiver(
    private val scope: CoroutineScope,
    private val settings: DesktopSettings
) {
    /** Progress and outcome of the current archive job. */
    var archiveRunning by mutableStateOf(false)
        private set
    var archiveProgress by mutableStateOf(0f)
        private set
    var archiveCurrent by mutableStateOf("")
        private set
    var archiveNote by mutableStateOf<String?>(null)
    var archiveFile by mutableStateOf<File?>(null)
        private set

    private var archiveJob: Job? = null
    @Volatile private var archiveCancelled = false

    /**
     * Packs [chosen] into a zip beside the downloads folder.
     *
     * Entry names carry the artist so an archive is navigable once unpacked —
     * a folder of two hundred files called "01.mp3" is not. Numbering keeps a
     * playlist's order, which the filesystem would otherwise sort away.
     */
    internal fun startArchive(label: String, chosen: List<DesktopTrack>) {
        if (archiveRunning) return
        if (chosen.isEmpty()) {
            archiveNote = "Nothing to archive."
            return
        }

        archiveCancelled = false
        archiveRunning = true
        archiveProgress = 0f
        archiveNote = null
        archiveFile = null

        archiveJob = scope.launch {
            val stamp = java.time.LocalDate.now().toString()
            val destination = File(
                File(settings.downloadDir, "archives"),
                MusicArchive.safeName("Lucense $label $stamp") + ".zip"
            )
            val digits = chosen.size.toString().length
            val entries = chosen.mapIndexed { index, track ->
                val number = (index + 1).toString().padStart(digits, '0')
                val artist = track.artist?.takeIf { it.isNotBlank() }
                val stem = listOfNotNull(artist, track.title).joinToString(" - ")
                ArchiveEntry(
                    source = track.file,
                    entryName = "$number ${MusicArchive.safeName(stem)}.${track.file.extension}"
                )
            }

            val result = io {
                MusicArchive.zip(
                    entries = entries,
                    destination = destination,
                    onProgress = { progress ->
                        archiveProgress = progress.fraction
                        archiveCurrent = progress.currentName
                    },
                    shouldContinue = { !archiveCancelled }
                )
            }

            archiveRunning = false
            archiveCurrent = ""
            result.fold(
                onSuccess = { done ->
                    archiveFile = done.file
                    archiveNote = buildString {
                        append("${done.included} tracks, ")
                        append("%.1f MB".format(done.bytes / 1_048_576.0))
                        if (done.skipped.isNotEmpty()) {
                            append(" — ${done.skipped.size} missing from storage")
                        }
                    }
                },
                onFailure = { archiveNote = it.message ?: "Couldn't build the archive." }
            )
            archiveJob = null
        }
    }

    fun cancelArchive() {
        archiveCancelled = true
        archiveJob?.cancel()
        archiveJob = null
        archiveRunning = false
        archiveNote = "Cancelled."
    }

    /** Opens the folder the archive landed in, and selects it. */
    fun revealArchive() {
        val file = archiveFile ?: return
        Explorer.reveal(file)
    }

    fun dismissArchive() {
        archiveNote = null
        archiveFile = null
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}
