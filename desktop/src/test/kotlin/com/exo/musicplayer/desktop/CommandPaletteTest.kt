package com.exo.musicplayer.desktop

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import com.exo.musicplayer.desktop.ui.LinkAction
import com.exo.musicplayer.desktop.ui.PaletteItem
import com.exo.musicplayer.desktop.ui.PaletteLinks
import com.exo.musicplayer.desktop.ui.PaletteSearch
import com.exo.musicplayer.desktop.ui.PaletteSection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What Ctrl+K finds for what is typed, and what it offers for a link. */
class CommandPaletteTest {

    private fun item(section: PaletteSection, title: String, subtitle: String = "") =
        PaletteItem(section, title, subtitle, Icons.Default.MusicNote)

    private fun song(title: String, by: String = "") = item(PaletteSection.SONGS, title, by)

    private fun titles(query: String, items: List<PaletteItem>) = PaletteSearch.rank(query, items).map { it.title }

    @Test
    fun theStartOfATitleBeatsTheMiddleOfOne() {
        val items = listOf(song("Othello"), song("Say Hello"), song("Hello World"))
        assertEquals(listOf("Hello World", "Say Hello", "Othello"), titles("hello", items))
        assertEquals(listOf("Hello World"), titles("hello world", items + song("Goodbye")))
    }

    @Test
    fun accentsAndCaseDontMatter() {
        val items = listOf(song("Pröz", "Kärtsy"), song("Prose"))
        assertEquals(listOf("Pröz"), titles("proz", items))
        assertEquals(listOf("Pröz"), titles("KARTSY", items), "the artist finds it too")
    }

    @Test
    fun anActionComesBeforeASongThatMatchesAsWell() {
        val items = listOf(song("Shuffle"), item(PaletteSection.ACTIONS, "Shuffle"), item(PaletteSection.PAGES, "Library"))
        assertEquals(PaletteSection.ACTIONS, PaletteSearch.rank("shuffle", items).first().section)
        // A better song still beats a worse action.
        val worse = listOf(item(PaletteSection.ACTIONS, "Quiet shuffle"), song("Shuffle"))
        assertEquals("Shuffle", PaletteSearch.rank("shuffle", worse).first().title)
    }

    @Test
    fun anEmptyBoxShowsTheFewStartersButNoSongsAndNoTipRow() {
        val items = listOf(
            item(PaletteSection.ACTIONS, "Play or pause"), song("Anything"), item(PaletteSection.PAGES, "Library"),
            item(PaletteSection.PAGES, "Albums"), item(PaletteSection.ACTIONS, "Rescan library")
        )
        // The usage notice is the palette's fixed footer, not a row to scroll to or pick.
        assertEquals(listOf("Play or pause", "Library"), titles("  ", items))
    }

    @Test
    fun theFooterTextsAreWrittenAndTheLinkOneDiffers() {
        assertTrue(PaletteSearch.TIP.isNotBlank())
        assertTrue(PaletteSearch.LINK_TIP.isNotBlank())
        assertTrue(PaletteSearch.KEY_HINTS.isNotBlank())
        assertNotEquals(PaletteSearch.TIP, PaletteSearch.LINK_TIP)
    }

    @Test
    fun songsStopAtTheCap() {
        val items = (1..100).map { song("Track $it") }
        assertEquals(PaletteSearch.SONG_CAP, PaletteSearch.rank("track", items).size)
    }

    @Test
    fun aVideoIsDownloadedFirst() {
        assertEquals(LinkAction.DOWNLOAD, PaletteLinks.actionsFor("https://www.youtube.com/watch?v=dQw4w9WgXcQ").first())
        assertEquals(
            listOf(LinkAction.DOWNLOAD, LinkAction.DOWNLOAD_PLAYLIST, LinkAction.MAKE_PLAYLIST, LinkAction.IDENTIFY),
            PaletteLinks.actionsFor("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PLabc_123")
        )
        // Apple Music's song links are its album's with the song on the end.
        assertEquals(LinkAction.DOWNLOAD, PaletteLinks.actionsFor("https://music.apple.com/us/album/x/123?i=456").first())
    }

    @Test
    fun aPlaylistOrAlbumIsMadeIntoAPlaylistFirst() {
        listOf(
            "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M",
            "https://open.spotify.com/album/4aawyAB9vmqN3uQ7FjRGTy",
            "https://www.youtube.com/playlist?list=PLabc_123",
            "https://music.apple.com/us/album/x/123",
            "https://soundcloud.com/someone/sets/mix"
        ).forEach { link ->
            assertEquals(LinkAction.MAKE_PLAYLIST, PaletteLinks.actionsFor(link).first(), link)
        }
    }

    @Test
    fun anyOtherLinkCanBeDownloadedOrIdentified() {
        assertEquals(listOf(LinkAction.DOWNLOAD, LinkAction.IDENTIFY), PaletteLinks.actionsFor("https://example.com/clip/1"))
        assertEquals(listOf(LinkAction.DOWNLOAD, LinkAction.IDENTIFY), PaletteLinks.actionsFor("https://open.spotify.com/track/abc"))
    }

    @Test
    fun onlyAWebLinkCountsAsOne() {
        assertEquals("https://youtu.be/abc", PaletteLinks.urlOf("  https://youtu.be/abc \n"))
        assertNull(PaletteLinks.urlOf("daft punk"))
        assertNull(PaletteLinks.urlOf("https://two words.com"))
        assertNull(PaletteLinks.urlOf("ftp://example.com/file"))
        assertNull(PaletteLinks.urlOf("https://"))
        assertNull(PaletteLinks.urlOf(null))
    }

    @Test
    fun oneSongOrTheWholeListWhateverTheSetting() {
        val link = "https://www.youtube.com/watch?v=abc&list=PLx-1&index=3"
        assertEquals("https://www.youtube.com/watch?v=abc", PaletteLinks.singleVideo(link))
        assertEquals("https://www.youtube.com/playlist?list=PLx-1", PaletteLinks.wholeList(link))
        assertTrue(PaletteLinks.isVideoInList(link))
    }
}
