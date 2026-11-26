package com.exo.musicplayer.desktop.data

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicLong

/**
 * Covers kept on disk at the two sizes Lucense draws them, so each is taken out
 * of its song once - a tag read and a full-size decode, tens of milliseconds -
 * and after that is a few kilobytes read back in well under one.
 *
 * Kept under a key made of the song's path, size and modification time: a song
 * changed in any way, new art embedded in it included, has a new key, and what
 * the old one kept is cleared by [prune]. A song found to have no art gets a
 * marker instead, so it isn't read again at every start. The marker holds the
 * folder's modification time ([noneStamp]): a cover.jpg put beside the song
 * later changes the folder, and is still found.
 */
class CoverThumbs(private val dir: File) {

    enum class Size(internal val suffix: String) { SMALL(".s"), LARGE(".l") }

    /** One cover, encoded at both sizes. */
    class Sizes(val small: ByteArray, val large: ByteArray)

    /** Striped, so two threads never render the same key at once and different keys never wait. */
    private val locks = Array(32) { Any() }

    /** Names the files written beside their target, so two writers never share one. */
    private val writes = AtomicLong()

    /** The key [file]'s covers are kept under; null when the file can't be read as it is. */
    fun keyOf(file: File): String? {
        val modified = file.lastModified()
        if (modified == 0L) return null
        return digest("${file.absolutePath}\u0000${file.length()}\u0000$modified".toByteArray())
    }

    fun read(key: String, size: Size): ByteArray? = runCatching {
        File(dir, key + size.suffix).takeIf { it.isFile }?.readBytes()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** Whether a cover is kept for [key], without reading it. */
    fun has(key: String): Boolean = File(dir, key + Size.SMALL.suffix).isFile

    fun write(key: String, sizes: Sizes) {
        dir.mkdirs()
        put(File(dir, key + Size.SMALL.suffix), sizes.small)
        put(File(dir, key + Size.LARGE.suffix), sizes.large)
        File(dir, key + NONE).delete()
    }

    /** The folder time [markNone] was given for [key], or null when it was never marked. */
    fun noneStamp(key: String): Long? = runCatching {
        File(dir, key + NONE).takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()
    }.getOrNull()

    fun markNone(key: String, folderStamp: Long) {
        dir.mkdirs()
        put(File(dir, key + NONE), folderStamp.toString().toByteArray())
    }

    fun forget(key: String) {
        Size.entries.forEach { File(dir, key + it.suffix).delete() }
        File(dir, key + NONE).delete()
    }

    /** Runs [work] for [key] with no other thread working on the same key meanwhile. */
    fun <T> once(key: String, work: () -> T): T =
        synchronized(locks[(key.hashCode() and Int.MAX_VALUE) % locks.size]) { work() }

    /**
     * Clears whatever is kept for keys not in [keep] - songs gone, or changed
     * since - and writes a crash left half done. Returns how many files went.
     */
    fun prune(keep: Set<String>): Int {
        val files = dir.listFiles() ?: return 0
        val stale = System.currentTimeMillis() - 60_000
        var gone = 0
        for (file in files) {
            val orphan = file.name.substringBefore('.') !in keep
            val leftover = file.name.endsWith(TEMP) && file.lastModified() < stale
            if ((orphan || leftover) && file.delete()) gone++
        }
        return gone
    }

    /** Written beside the target and moved over it, so a reader never sees half a file. */
    private fun put(target: File, bytes: ByteArray) {
        val temp = File(dir, "${target.name}.${writes.incrementAndGet()}$TEMP")
        try {
            temp.writeBytes(bytes)
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            temp.delete()
            throw e
        }
    }

    companion object {
        private const val NONE = ".none"
        private const val TEMP = ".tmp"

        fun digest(bytes: ByteArray): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes))
    }
}
