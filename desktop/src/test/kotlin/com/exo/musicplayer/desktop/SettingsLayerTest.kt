package com.exo.musicplayer.desktop

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import com.exo.musicplayer.desktop.ui.Destination
import com.exo.musicplayer.desktop.ui.PaletteItem
import com.exo.musicplayer.desktop.ui.PaletteSearch
import com.exo.musicplayer.desktop.ui.PaletteSection
import com.exo.musicplayer.desktop.ui.SettingsTab
import com.exo.musicplayer.desktop.ui.findSettings
import com.exo.musicplayer.desktop.ui.railGroups
import com.exo.musicplayer.desktop.ui.rowShown
import com.exo.musicplayer.desktop.ui.settingsIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which settings show in simple mode, how the search finds them, how the sidebar is grouped, what the empty palette offers. */
class SettingsLayerTest {

    @Test
    fun anAdvancedRowShowsWhenAdvancedIsOnOrItIsDoingSomething() {
        assertFalse(rowShown(advanced = false, nonDefault = false))
        assertTrue(rowShown(advanced = true, nonDefault = false))
        assertTrue(rowShown(advanced = false, nonDefault = true), "a proxy that is set is never hidden")
        assertTrue(rowShown(advanced = true, nonDefault = true))
    }

    @Test
    fun searchFindsCrossfadeOnPlayback() {
        val hit = findSettings("crossfade").first()
        assertEquals("Crossfade", hit.label)
        assertEquals(SettingsTab.PLAYBACK, hit.tab)
        assertFalse(hit.advanced)
    }

    @Test
    fun searchTagsProxyAsAdvancedAndIgnoresAccentsAndWordOrder() {
        val proxy = findSettings("proxy").first { it.label == "Proxy" }
        assertTrue(proxy.advanced)
        assertEquals(SettingsTab.DOWNLOADS, proxy.tab)
        assertTrue(findSettings("CROSSFÁDE").any { it.label == "Crossfade" })
        assertTrue(findSettings("volumes even").any { it.label == "Even out volumes" })
        assertTrue(findSettings("   ").isEmpty())
    }

    @Test
    fun theEverydayRowsAreNotAdvancedAndTheTuningOnesAre() {
        val advanced = settingsIndex.filter { it.advanced }.map { it.label }
        listOf("Output sample rate", "Proxy", "Lyrics search", "Lyrics services", "Cover art services", "Edit colours", "Logs")
            .forEach { assertTrue(it in advanced, it) }
        listOf("Crossfade", "Music folders", "Media keys", "Backups", "Show advanced options")
            .forEach { name -> assertFalse(settingsIndex.first { it.label == name }.advanced, name) }
    }

    @Test
    fun theSidebarGroupsAreInOrder() {
        val groups = railGroups()
        assertEquals(listOf(null, "Find music", "More"), groups.map { it.label })
        assertEquals(
            listOf(Destination.LIBRARY, Destination.ALBUMS, Destination.ARTISTS, Destination.PLAYLISTS),
            groups[0].pages
        )
        assertEquals(listOf(Destination.DISCOVER, Destination.IDENTIFY, Destination.DOWNLOAD), groups[1].pages)
        assertEquals(listOf(Destination.MOODS, Destination.STATS), groups[2].pages)
        assertEquals(listOf(false, false, true), groups.map { it.foldable })
        // Nothing dropped, nothing twice.
        assertEquals(Destination.entries.toSet(), groups.flatMap { it.pages }.toSet())
        assertEquals(Destination.entries.size, groups.sumOf { it.pages.size })
    }

    private fun action(title: String) = PaletteItem(PaletteSection.ACTIONS, title, icon = Icons.Default.MusicNote)
    private fun page(title: String) = PaletteItem(PaletteSection.PAGES, title, icon = Icons.Default.MusicNote)

    @Test
    fun theEmptyPaletteIsShortAndTypingStillSearchesEverything() {
        val items = listOf(
            action("Play or pause"), action("Next song"), action("Previous song"), action("Shuffle off"),
            action("Repeat"), action("Mute or unmute"), action("A-B loop"), action("Rescan library"),
            action("Sleep in 15 minutes"), action("Play Most played")
        ) + Destination.entries.map { page(it.label) } + page("Settings") +
            SettingsTab.entries.map { page("Settings: ${it.label}") }
        val empty = PaletteSearch.rank("", items).map { it.title }
        assertTrue(empty.size <= 12, "${empty.size} rows")
        assertEquals(
            listOf(
                "Play or pause", "Next song", "Previous song", "Shuffle off", "Library", "Playlists",
                "Discover", "Identify", "Download", "Settings"
            ),
            empty
        )
        // Typing finds what the empty box leaves out.
        assertEquals("Stats", PaletteSearch.rank("stats", items).first().title)
        assertEquals("Settings: Playback", PaletteSearch.rank("settings playback", items).first().title)
        assertTrue(PaletteSearch.rank("sleep", items).any { it.title == "Sleep in 15 minutes" })
    }
}
