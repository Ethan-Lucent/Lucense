package com.exo.musicplayer.desktop.update

import java.io.File
import java.nio.file.Files

/**
 * Where the running Lucense is installed, worked out from the folder jpackage
 * tells the app about (`compose.application.resources.dir` = `$APPDIR/resources`):
 *
 *  - Linux (.deb/.rpm): `/opt/lucense/lib/app/resources`, so the root is
 *    `/opt/lucense`, the launcher `bin/Lucense`, the jars in `lib/app`.
 *  - Windows: `<root>\app\resources`, the launcher `Lucense.exe`, the jars in `app`.
 *
 * Anything else - a run from Gradle, a layout this doesn't know - gives no
 * layout at all, and the app keeps to "open the release page": an updater that
 * guesses where it lives could write into the wrong folder.
 */
class InstallLayout(
    val root: File,
    val launcher: File,
    val windows: Boolean,
    val version: String
) {
    /** Where the app's jars are, relative to [root]: the only place stale files are removed from. */
    val appDir: String get() = if (windows) "app" else "lib/app"

    val platform: String get() = if (windows) "windows-x64" else "linux-x64"

    /** The launcher's path relative to [root], as the manifest names it. */
    val launcherPath: String get() = if (windows) "Lucense.exe" else "bin/Lucense"

    fun file(path: String): File = File(root, path)

    /**
     * Whether this user can put each of [paths] (relative to [root]) in place
     * without administrator rights. The helper writes `<file>.lucense-new` next
     * to the file and renames it over, so what counts is the folder (or, for a
     * file in a folder still to be made, the nearest folder that exists), plus
     * the file itself on Windows, where a read-only file can't be replaced.
     *
     * Folders are tried for real, with a file made and removed, because a
     * permission check can say yes where the system then says no (UAC
     * virtualisation, network drives, read-only mounts).
     */
    fun isWritable(paths: Collection<String>): Boolean {
        val folders = LinkedHashSet<File>()
        folders += root
        for (path in paths) {
            val file = file(path)
            if (file.exists() && !Files.isWritable(file.toPath())) return false
            var folder = file.parentFile
            while (folder != null && !folder.isDirectory) folder = folder.parentFile
            if (folder == null) return false
            folders += folder
        }
        return folders.all { probe(it) }
    }

    private fun probe(folder: File): Boolean = runCatching {
        val made = Files.createTempFile(folder.toPath(), ".lucense-probe", ".tmp")
        Files.deleteIfExists(made)
        true
    }.getOrDefault(false)

    companion object {
        /**
         * The layout of the running app, or null when updating in place is not
         * possible: not started by the installed launcher, a layout not
         * recognised, a version that isn't a release number, or a machine that
         * isn't 64-bit x86 (the only builds released).
         */
        fun detect(
            resourcesDir: String? = System.getProperty("compose.application.resources.dir"),
            version: String? = System.getProperty("lucense.version"),
            osName: String = System.getProperty("os.name").orEmpty(),
            arch: String = System.getProperty("os.arch").orEmpty()
        ): InstallLayout? {
            if (resourcesDir.isNullOrBlank()) return null
            if (version == null || !UpdateManifest.isVersion(version)) return null
            if (arch.lowercase() !in setOf("amd64", "x86_64", "x64")) return null
            val resources = File(resourcesDir).absoluteFile.normalize()
            val appDir = resources.parentFile ?: return null
            // Both layouts keep the launcher's settings beside the jars; a folder
            // without it was not laid out by jpackage.
            if (resources.name != "resources" || appDir.name != "app" || !File(appDir, "Lucense.cfg").isFile) return null
            return when {
                osName.startsWith("Windows") -> {
                    val root = appDir.parentFile ?: return null
                    val launcher = File(root, "Lucense.exe")
                    if (launcher.isFile) InstallLayout(root, launcher, windows = true, version = version) else null
                }
                osName.startsWith("Linux") -> {
                    val lib = appDir.parentFile ?: return null
                    if (lib.name != "lib") return null
                    val root = lib.parentFile ?: return null
                    val launcher = File(root, "bin/Lucense")
                    if (launcher.isFile) InstallLayout(root, launcher, windows = false, version = version) else null
                }
                else -> null
            }
        }
    }
}
