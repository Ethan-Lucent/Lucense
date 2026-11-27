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


    // The sidebar: one line on what each page is for.
    const val RAIL_LIBRARY = "All your songs."
    const val RAIL_ALBUMS = "Your music grouped by album."
    const val RAIL_ARTISTS = "Your music grouped by artist."
    const val RAIL_PLAYLISTS = "Your playlists, smart lists and imports from a link."
    const val RAIL_DISCOVER = "Genres to wander and artists to follow."
    const val RAIL_IDENTIFY = "Find out what a song is."
    const val RAIL_DOWNLOAD = "Save songs from YouTube, SoundCloud, Bandcamp and more."
    const val RAIL_MOODS = "Songs that match the weather and time."
    const val RAIL_STATS = "What you listen to, and when."
    const val RAIL_SETTINGS = "Folders, look, playback and more."

    // The bar that shows when songs are selected in the library.
    const val SEL_PLAY = "Plays the songs you picked, one after another."
    const val SEL_FAVOURITE = "Adds the songs you picked to your favourites."
    const val SEL_FIX = "Looks up the right title, artist and cover for the songs you picked."
    const val SEL_SHOW = "Opens the folder these songs are in."
    const val SEL_CLEAR = "Lets go of the songs you picked."

    // The Playlists page.
    const val PL_NEW = "Makes an empty playlist you can fill."
    const val PL_NEW_SMART = "A list that fills itself from a rule, such as a genre or a year."
    const val PL_IMPORT = "Reads a playlist file, such as an M3U, from your computer."
    const val PL_FROM_LINK = "Paste a Spotify, Apple Music, YouTube or SoundCloud playlist link."
    const val PL_EXPORT = "Saves this playlist as a file you can keep or use elsewhere."
    const val PL_RENAME = "Gives this playlist a new name."
    const val PL_DELETE = "Removes the playlist. The songs stay in your library."
    const val PL_STREAM = "Plays the whole list online, without downloading it."
    const val PL_SAVE_PROMPT = "Keeps these songs as a playlist you can open again."
    const val PL_PLAY = "Plays this playlist from the top."

    // The Identify page.
    const val ID_USE = "Puts this name and these details on your song."
    const val ID_SEARCH = "Searches for this one instead."
    const val ID_DOWNLOAD = "Saves this song to your library."
    const val ID_STREAM_ALL = "Plays every result online, one after another."

    // The Moods page.
    const val MOODS_REFRESH = "Checks the weather again."
    const val MOODS_PLAY = "Plays the songs picked for now."

    // The effects panel.
    const val FX_REMEMBER = "Brings these settings back whenever this song plays."
    const val FX_FORGET = "This song goes back to using the settings you have on now."
    const val FX_CLEAR = "Puts speed, pitch and every effect back to normal."
    const val FX_FINE_TUNE = "Reverb, distortion, lo-fi, tremolo, 8D, echo and the equaliser."

    // The download page.
    const val DL_OPTIONS = "Format, quality, cover art, and what to cut or keep."
    const val DL_SIGN_IN = "Shows the YouTube sign-in, for when YouTube refuses downloads."
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
