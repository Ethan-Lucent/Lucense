package com.exo.musicplayer.desktop.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** One part of the key guide: a heading, and the keys under it with what they do. */
data class KeyGroup(val title: String, val keys: List<Pair<String, String>>)

/**
 * Every key Lucense answers to, in one place: the / dialog and Settings > Keys
 * both show this, so the two can't drift apart. WindowShortcuts is what makes
 * them work; a key added there belongs here too.
 */
val KEY_GUIDE: List<KeyGroup> = listOf(
    KeyGroup(
        "Playing",
        listOf(
            "Space" to "Play or pause",
            "\u2190  \u2192" to "Back or forward five seconds",
            "Ctrl + \u2190  \u2192" to "Previous or next song",
            "\u2191  \u2193" to "Volume up or down",
            "M" to "Mute, or put the volume back",
            "S" to "Shuffle on or off",
            "R" to "Repeat: all, then one, then off",
            "L" to "Loop: A here, then B here and round, then off"
        )
    ),
    KeyGroup(
        "Finding",
        listOf(
            "Ctrl + F" to "The page's search box, from anywhere",
            "J" to "Jump the list to the song playing",
            "/" to "This list"
        )
    ),
    KeyGroup(
        "Choosing songs",
        listOf(
            "Ctrl + A" to "Select every song in view",
            "Ctrl + click" to "Add one song to the selection",
            "Shift + click" to "Select everything between",
            "Esc" to "Clear the selection, or back from an album or artist"
        )
    ),
    KeyGroup(
        "In a text box",
        listOf(
            "Enter" to "Search, or whatever the box is for",
            "Esc" to "Leave the box, so the keys above work again"
        )
    ),
    KeyGroup(
        "Mini player and lyrics windows",
        listOf(
            "Space  \u2190 \u2192  \u2191 \u2193" to "As in the main window",
            "M  S  R  L" to "As in the main window",
            "Esc" to "Close full-screen lyrics"
        )
    )
)

/** The key guide: each heading, then its keys on the left and what they do on the right. */
@Composable
fun KeyGuide(groups: List<KeyGroup> = KEY_GUIDE) {
    groups.forEachIndexed { index, group ->
        if (index > 0) Spacer(Modifier.height(12.dp))
        Text(group.title, style = MaterialTheme.typography.titleMedium, color = Palette.Text)
        Spacer(Modifier.height(4.dp))
        group.keys.forEach { (keys, what) ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    keys,
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Accent,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.width(150.dp)
                )
                Text(what, style = MaterialTheme.typography.bodySmall, color = Palette.Text)
            }
        }
    }
}
