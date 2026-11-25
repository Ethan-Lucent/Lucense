package com.exo.musicplayer.desktop.data

/** The four bulk tools, and the mark each one records so reruns can skip. */
enum class BulkKind(val label: String, val markColumn: String?) {
    COVERS("Covers", "artCheckedAt"),
    TAGS("Names & tags", "identifiedAt"),
    LYRICS("Lyrics", "lyricsCheckedAt"),
    IDENTIFY("Identify by sound", "fingerprintedAt"),

    /** Marks nothing: it only ever visits files that are still broken. */
    REPAIR("Mass fix Telegram songs", null),

    /** Marks nothing either: a measured track is skipped by its own reckoning. */
    LEVELS("Level volumes", null),

    /** Marks nothing: it only ever visits songs that are still missing something. */
    FOLDERS("Names from folders", null)
}
