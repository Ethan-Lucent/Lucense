package com.exo.musicplayer.desktop.data

/** How the library table is ordered. */
enum class SortMode(val label: String) {
    ARTIST("Artist"),
    TITLE("Title"),
    ALBUM("Album"),
    DURATION("Duration"),
    PLAYS("Times played"),
    LISTEN_TIME("Time listened"),
    ADDED("Date added"),
    RATING("Rating"),
    YEAR("Year")
}
