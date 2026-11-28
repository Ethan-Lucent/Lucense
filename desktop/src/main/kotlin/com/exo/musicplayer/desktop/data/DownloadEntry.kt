package com.exo.musicplayer.desktop.data

import com.exo.musicplayer.data.youtube.YouTubeLinkFinder
import java.io.File

/** One line in the download window's history. */
data class DownloadEntry(
    val id: Long,
    val target: String,
    var display: String,
    var percent: Float = 0f,
    var status: String = "Queued",
    var done: Boolean = false,
    var failed: Boolean = false,
    var files: List<File> = emptyList(),
    var note: String? = null,
    /** The song it was asked for by name, when it was: kept so a retry asks the same way. */
    val song: YouTubeLinkFinder.Wanted? = null,
    /** A playlist's songs that weren't downloaded, for "Retry the missed". */
    var missed: Int = 0,
    /** How the cover art went ("Cover added", "No cover found"); null until known, or when none was looked for. */
    var cover: String? = null,
    /** What the song was found by, so a retry looks for its cover the same way. */
    val hint: CoverHint? = null
)
