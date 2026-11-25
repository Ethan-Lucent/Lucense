package com.exo.musicplayer.desktop.data

/** How the Identify box interprets what you typed. */
enum class IdentifyMode(val label: String, val placeholder: String) {
    NAME("Name", "Artist and title, or whatever you can remember"),
    LYRICS("Lyrics", "A line you remember, however roughly"),
    LINK("Link", "TikTok, Instagram, YouTube, SoundCloud link"),
    YOUTUBE("YouTube", "Search YouTube — anything, not just music")
}
