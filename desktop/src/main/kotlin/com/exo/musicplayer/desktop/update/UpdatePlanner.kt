package com.exo.musicplayer.desktop.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** What an update would change: the files to download and the old jars to remove. */
data class UpdatePlan(
    val manifest: UpdateManifest,
    /** Files that are missing or differ from the release's. */
    val toFetch: List<ManifestFile>,
    /** Old jars beside the app's that the new version no longer lists, relative to the root. */
    val stale: List<String>,
    /** How many files are already exactly right. */
    val unchanged: Int
) {
    val version: String get() = manifest.version
    val bytes: Long get() = toFetch.sumOf { it.size }
    val isEmpty: Boolean get() = toFetch.isEmpty() && stale.isEmpty()
}

object UpdatePlanner {

    /**
     * Compares the installed files under [layout] with [manifest]. A file whose
     * size differs is changed without being read; one of the same size is
     * hashed. [onProgress] gets bytes hashed so far and bytes to hash in all.
     *
     * Stale files are only ever `*.jar` directly in the app's jar folder: those
     * names carry a content hash and change every build, so the old ones would
     * otherwise pile up. Nothing else is ever deleted, whatever the manifest
     * leaves out - a file the release doesn't list may be something the user or
     * the system put there.
     */
    suspend fun plan(
        manifest: UpdateManifest,
        layout: InstallLayout,
        onProgress: (hashed: Long, total: Long) -> Unit = { _, _ -> }
    ): UpdatePlan = withContext(Dispatchers.IO) {
        if (manifest.platform != layout.platform) {
            throw ManifestException("The update is for ${manifest.platform}, this is ${layout.platform}.")
        }
        // The launcher must stay runnable: a release that would install it
        // without the executable bit would leave a Lucense that can't start.
        if (!layout.windows && manifest.files.any { it.path == layout.launcherPath && !it.exec }) {
            throw ManifestException("The update would leave the launcher unable to start.")
        }
        val sameSize = manifest.files.filter { entry ->
            val local = layout.file(entry.path)
            local.isFile && local.length() == entry.size
        }
        val total = sameSize.sumOf { it.size }
        var hashed = 0L
        onProgress(0, total)
        val matching = HashSet<String>()
        for (entry in sameSize) {
            coroutineContext.ensureActive()
            val sha = sha256(layout.file(entry.path)) { read ->
                hashed += read
                onProgress(hashed, total)
            }
            if (sha == entry.sha256) matching += entry.path
        }
        val toFetch = manifest.files.filter { it.path !in matching }
        val listed = manifest.files.mapTo(HashSet()) { it.path.lowercase() }
        val stale = layout.file(layout.appDir).listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".jar", ignoreCase = true) }
            .map { "${layout.appDir}/${it.name}" }
            .filter { it.lowercase() !in listed && UpdateManifest.isSafePath(it) }
            .sorted()
        UpdatePlan(manifest, toFetch, stale, matching.size)
    }

    /** The lower-case hex SHA-256 of [file], reporting each block read to [onRead]. */
    fun sha256(file: File, onRead: (Int) -> Unit = {}): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                onRead(read)
            }
        }
        return hex(digest.digest())
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
