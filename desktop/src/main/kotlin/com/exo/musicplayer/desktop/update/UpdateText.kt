package com.exo.musicplayer.desktop.update

import java.util.Locale

/** The words the updater shows, kept apart so they can be checked without a window. */
object UpdateText {

    /**
     * A size the way people read it: "780 KB", "4.6 MB", "1.2 GB". Decimal
     * units, as file managers on both systems show them, one decimal from MB up,
     * so 4.6 MB and 4.4 MB stay told apart.
     */
    fun size(bytes: Long): String {
        val b = bytes.coerceAtLeast(0)
        return when {
            b < 1_000 -> "$b bytes"
            b < 999_500 -> "${(b + 500) / 1_000} KB"
            b < 999_950_000 -> String.format(Locale.ROOT, "%.1f MB", b / 1_000_000.0)
            else -> String.format(Locale.ROOT, "%.1f GB", b / 1_000_000_000.0)
        }
    }

    /** "Downloading 3 of 5 files (2.1 of 4.6 MB)". */
    fun downloading(filesDone: Int, filesTotal: Int, bytesDone: Long, bytesTotal: Long): String {
        val current = (filesDone + 1).coerceAtMost(filesTotal).coerceAtLeast(if (filesTotal == 0) 0 else 1)
        val files = if (filesTotal == 1) "1 file" else "$current of $filesTotal files"
        val total = size(bytesTotal)
        val unit = total.substringAfter(' ')
        // The same unit on both sides reads as one sum: "2.1 of 4.6 MB".
        val done = size(bytesDone).let { if (it.substringAfter(' ') == unit) it.substringBefore(' ') else it }
        return "Downloading $files ($done of $total)"
    }

    /** A download size: "4.6 MB", or "up to 77 MB" when only the installed (uncompressed) total is known. */
    fun transfer(bytes: Long, exact: Boolean = true): String = if (exact) size(bytes) else "up to ${size(bytes)}"

    /** The main button: "Update to 1.58.0  (4.6 MB)". */
    fun updateButton(version: String, bytes: Long, exact: Boolean = true): String =
        "Update to $version  (${transfer(bytes, exact)})"

    fun updated(version: String) = "Updated to $version."

    fun rolledBack(version: String, error: String) =
        "The update to $version didn't work, and your old version was put back: $error"
}
