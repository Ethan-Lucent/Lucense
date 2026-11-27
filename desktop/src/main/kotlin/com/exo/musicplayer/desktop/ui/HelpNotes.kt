package com.exo.musicplayer.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The short notes that show when the mouse rests on a menu item, and whether
 * they show at all (Settings > Appearance). Kept here in one place so the
 * wording stays plain and alike, and is easy to find and change.
 */
object Notes {
    /** Set from the saved setting when the controller starts, and by the checkbox. */
    var on by mutableStateOf(true)

    const val PLAY_NEXT = "Plays right after the song that's on now."
    const val ADD_TO_QUEUE = "Puts the song at the end of what's coming up."
    const val ADD_TO_PLAYLIST = "Pick a playlist to put the song in."
    const val EDIT_DETAILS = "Change the title, artist, album, year or genre."
    const val TRIM = "Cut the song down and save the cut as a new song."
    const val IDENTIFY = "Find the real name and details of this song by its name or sound."
    const val REVERT = "Undo what Lucense changed and use what the file said."
    const val DELETE = "Moves the file to the trash. You can get it back from there."
}
