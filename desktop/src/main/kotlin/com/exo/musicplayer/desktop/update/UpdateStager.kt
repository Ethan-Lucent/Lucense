package com.exo.musicplayer.desktop.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.coroutineContext

/** How far a download has got. */
data class StageProgress(val filesDone: Int, val filesTotal: Int, val bytesDone: Long, val bytesTotal: Long)

/**
 * Downloads the files of a plan into `<updates>/<version>/files/<path>`.
 *
 * Each file is written to `<name>.part` and only renamed to its own name once
 * its CRC-32 and the release's SHA-256 have both matched, so a file under its
 * real name in the staging folder is always a verified one. That is also what
 * makes it resumable: after a restart, files already there with the right
 * size and hash are kept and not downloaded again.
 */
class UpdateStager(private val updatesRoot: File) {

    fun folder(version: String): File = File(updatesRoot, version)

    fun filesFolder(version: String): File = File(folder(version), "files")

    fun staged(version: String, path: String): File = File(filesFolder(version), path)

    /** Whether [file] is already staged as the release has it. */
    fun isStaged(version: String, file: ManifestFile): Boolean {
        val staged = staged(version, file.path)
        return staged.isFile && staged.length() == file.size && UpdatePlanner.sha256(staged) == file.sha256
    }

    /** Whether every file of [plan] is staged and verified (hashes them again). */
    suspend fun isComplete(plan: UpdatePlan): Boolean = withContext(Dispatchers.IO) {
        plan.toFetch.all { coroutineContext.ensureActive(); isStaged(plan.version, it) }
    }

    /**
     * Fetches what is missing from [zip]. [installRoot] is the drive the files
     * will end up on, checked for room too: the helper writes each new file
     * beside the old one before swapping, and keeps backups in the staging folder.
     */
    suspend fun stage(
        plan: UpdatePlan,
        zip: ZipOverHttp,
        installRoot: File,
        onProgress: (StageProgress) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        val version = plan.version
        val files = filesFolder(version).apply { mkdirs() }
        if (!files.isDirectory) throw UpdateException("Couldn't make the folder for the update: $files")
        File(folder(version), "manifest.json").writeText(plan.manifest.toJson())
        val missing = plan.toFetch.filter { !isStaged(version, it) }
        val total = plan.bytes
        var bytesDone = total - missing.sumOf { it.size }
        var filesDone = plan.toFetch.size - missing.size
        onProgress(StageProgress(filesDone, plan.toFetch.size, bytesDone, total))
        checkRoom(missing.sumOf { it.size }, plan, installRoot)
        for (file in missing) {
            coroutineContext.ensureActive()
            val entry = zip.entries[plan.manifest.prefix + file.path]
                ?: throw UpdateException("The update zip doesn't have ${file.path}.")
            if (entry.size != file.size) throw UpdateException("The update zip and its list disagree about ${file.path}.")
            val target = staged(version, file.path)
            target.parentFile.mkdirs()
            val part = File(target.parentFile, target.name + ".part")
            val before = bytesDone
            try {
                part.outputStream().buffered().use { out ->
                    zip.read(entry, out, file.sha256) { bytesDone += it; onProgress(StageProgress(filesDone, plan.toFetch.size, bytesDone, total)) }
                }
                Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: Throwable) {
                part.delete()
                bytesDone = before
                throw e
            }
            filesDone++
            onProgress(StageProgress(filesDone, plan.toFetch.size, bytesDone, total))
        }
    }

    /**
     * Refuses to start when a drive is too full: running out half-way through
     * the swap is exactly the half-updated install this is built to avoid.
     */
    private fun checkRoom(download: Long, plan: UpdatePlan, installRoot: File) {
        val margin = 32L * 1024 * 1024
        // Backups of what is replaced or removed go to the staging folder.
        val backups = plan.toFetch.sumOf { File(installRoot, it.path).takeIf(File::isFile)?.length() ?: 0L } +
            plan.stale.sumOf { File(installRoot, it).length() }
        val stagingStore = runCatching { Files.getFileStore(updatesRoot.toPath()) }.getOrNull()
        val installStore = runCatching { Files.getFileStore(installRoot.toPath()) }.getOrNull()
        val stagingNeed = download + backups + margin
        val installNeed = plan.bytes + margin
        if (stagingStore != null && stagingStore == installStore) {
            if (stagingStore.usableSpace < stagingNeed + installNeed) throw UpdateException(notEnough(stagingNeed + installNeed, stagingStore.usableSpace))
            return
        }
        if (stagingStore != null && stagingStore.usableSpace < stagingNeed) throw UpdateException(notEnough(stagingNeed, stagingStore.usableSpace))
        if (installStore != null && installStore.usableSpace < installNeed) throw UpdateException(notEnough(installNeed, installStore.usableSpace) + " (on the drive Lucense is installed on)")
    }

    private fun notEnough(need: Long, free: Long) =
        "Not enough free space: the update needs ${UpdateText.size(need)}, ${UpdateText.size(free)} is free"
}
