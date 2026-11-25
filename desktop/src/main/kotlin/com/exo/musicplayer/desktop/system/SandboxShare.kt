package com.exo.musicplayer.desktop.system

import java.io.File
import java.nio.file.Files

/**
 * Where a song dragged out of Lucense is handed over from, on Linux.
 *
 * Telegram, Discord and most other chat apps are installed as Flatpaks, which
 * see the Downloads folder and little else. Drop a song from anywhere else into
 * one and it can't open the file: Telegram's "is empty and can't be sent". So a
 * dragged song outside Downloads is offered from Downloads/.lucense-share as a
 * hard link: the same file under a second name - nothing copied, no space used,
 * the original untouched. A link can't cross from one disk to another, and then
 * the song is offered as it is, as before.
 *
 * The links of an earlier session are cleared the first time anything is
 * dragged; by then whatever they were dropped into has long read them.
 * `-Dlucense.share.dir` puts the folder elsewhere, or `off` (the tests do).
 */
object SandboxShare {

    @Volatile private var swept = false

    /** [files] as a sandboxed app can read them; anything that can't be linked, as it is. */
    fun offer(files: List<File>): List<File> {
        if (!Platform.isLinux) return files
        val setting = System.getProperty("lucense.share.dir")
        if (setting == "off") return files
        val downloads = downloadsFolder()
        val folder = setting?.let(::File) ?: File(downloads, ".lucense-share")
        return offer(files, folder, downloads)
    }

    internal fun offer(files: List<File>, folder: File, downloads: File): List<File> {
        if (!swept) {
            swept = true
            folder.listFiles()?.forEach { it.deleteRecursively() }
        }
        return files.map { file -> runCatching { link(file, folder, downloads) }.getOrNull() ?: file }
    }

    private fun link(file: File, folder: File, downloads: File): File? {
        val source = file.absoluteFile
        // Already where a sandboxed app can see it.
        if (source.path.startsWith(downloads.absolutePath + File.separator)) return source
        // Same name from two folders: each in a numbered folder of its own, keeping its name.
        var at = folder
        var n = 1
        while (true) {
            val target = File(at, source.name)
            if (!target.exists()) {
                at.mkdirs()
                Files.createLink(target.toPath(), source.toPath())
                return target
            }
            if (Files.isSameFile(target.toPath(), source.toPath())) return target
            at = File(folder, (n++).toString())
        }
    }

    /** The desktop's Downloads folder, as user-dirs.dirs names it. */
    fun downloadsFolder(): File {
        val home = System.getProperty("user.home")
        val configured = runCatching {
            val config = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.config"
            File(config, "user-dirs.dirs").readLines()
                .firstOrNull { it.startsWith("XDG_DOWNLOAD_DIR=") }
                ?.substringAfter('=')?.trim('"')?.replace("\$HOME", home)
        }.getOrNull()
        return File(configured?.takeIf { it.isNotBlank() } ?: "$home/Downloads")
    }
}
