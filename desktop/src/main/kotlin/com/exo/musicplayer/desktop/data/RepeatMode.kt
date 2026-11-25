package com.exo.musicplayer.desktop.data

/** What happens when a track, or the queue, runs out. */
enum class RepeatMode(val label: String) {
    OFF("Repeat off"),
    ALL("Repeat all"),
    ONE("Repeat one");

    companion object {
        fun fromName(name: String?): RepeatMode = entries.firstOrNull { it.name == name } ?: OFF
    }
}
