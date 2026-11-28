package com.exo.musicplayer.desktop.update

/**
 * What an update really costs to download. The manifest's sizes are the files
 * as they will be installed; the zip deflates some of them hard (the class
 * archive is ~72 MB installed and ~20 MB in the zip), so the size people are
 * shown is the zip's own (compressed) size for the files that are fetched.
 * The installed size stays what the free-space check uses.
 */
internal object UpdateTransfer {

    /**
     * The compressed size of each file of [plan]'s `toFetch`, in the same
     * order, from the zip's central directory; null when the zip lacks one of
     * them (the caller then falls back to the installed sizes).
     */
    fun compressedSizes(plan: UpdatePlan, zip: ZipOverHttp): List<Long>? =
        plan.toFetch.map { zip.entries[plan.manifest.prefix + it.path]?.compressedSize ?: return null }

    /**
     * How much of the compressed total [installedDone] installed bytes stand
     * for, going through the files in order: whole files count their whole
     * compressed size, the one in progress its share. Monotonic, and exactly
     * the total once everything is done, so the bar and the "2.1 of 4.6 MB"
     * agree with the size announced up front.
     */
    fun shown(plan: UpdatePlan, compressed: List<Long>, installedDone: Long): Long {
        var left = installedDone
        var sum = 0L
        for ((index, file) in plan.toFetch.withIndex()) {
            if (left <= 0) break
            if (left >= file.size) {
                sum += compressed[index]
                left -= file.size
            } else {
                sum += (compressed[index].toDouble() * left / file.size).toLong()
                left = 0
            }
        }
        return sum
    }
}
