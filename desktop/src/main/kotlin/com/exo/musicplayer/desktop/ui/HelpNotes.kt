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

    // The buttons on the playback bar.
    const val MINI = "A small player that stays on top while you work in something else."
    const val QUEUE = "What plays next. Drag songs to change the order."
    const val LYRICS = "Shows the words as they're sung. Click the cover for fullscreen."
    const val EFFECTS = "Equaliser and other ways to change how the music sounds."
    const val OUTPUT = "Choose which speakers or headphones the music plays through."
    const val SLEEP = "Stops the music after a while, or when this song ends."
    const val SHUFFLE = "Plays your songs in a random order."
    const val REPEAT = "Click to go round: repeat everything, repeat this song, or no repeat."
    const val FAVOURITE = "Adds the song to your favourites, or takes it out."
    const val COVER = "Click for fullscreen lyrics."
    const val MORE_SLEEP = "Stops the music after this long. Pick it again to start over."
    const val MORE_SLEEP_SONG = "Stops the music when the song that's on now ends."
    const val MORE_SLEEP_OFF = "Cancels the sleep timer."
    const val MORE_MINI = "Shows or hides the small player that stays on top."
    const val SLEEP_RUNNING = "The music will stop by itself. Click to change or cancel."
    const val BULK = "Change the details of many songs at once."
    const val DUPLICATES = "Finds songs that are in your library twice."
    const val ZIP_AND_SHIP = "Packs songs into a zip file to share or copy elsewhere."
}

/**
 * What a hover note shows, one line each: the name, then the note. The rule
 * [Noted] follows. With notes on, both. With notes off, a button that is only
 * an icon still shows its name - nothing else says what it is - and a control
 * that already has a word on it shows nothing. Empty lines are left out.
 */
internal fun noteLines(name: String?, note: String?, iconOnly: Boolean, notesOn: Boolean): List<String> {
    val name = name?.takeIf { it.isNotBlank() }
    val note = note?.takeIf { it.isNotBlank() }
    return when {
        notesOn -> listOfNotNull(name, note)
        iconOnly -> listOfNotNull(name)
        else -> emptyList()
    }
}
