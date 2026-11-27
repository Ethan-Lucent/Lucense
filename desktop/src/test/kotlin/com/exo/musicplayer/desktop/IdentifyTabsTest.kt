package com.exo.musicplayer.desktop

import com.exo.musicplayer.data.recognition.MusicMatch
import com.exo.musicplayer.desktop.data.IdentifyMode
import com.exo.musicplayer.desktop.data.IdentifyTab
import com.exo.musicplayer.desktop.data.accepts
import com.exo.musicplayer.desktop.data.clearLeftIdentifyTab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What leaving an Identify tab clears, and that a late answer cannot undo it.
 *
 * DesktopController is not built here: it opens the real settings, database and
 * audio device. So the tab logic lives in IdentifyTab and clearLeftIdentifyTab,
 * which the controller calls, and those are what is tested.
 */
class IdentifyTabsTest {

    private fun match(title: String) = MusicMatch(
        title = title, artist = "A", album = null,
        source = MusicMatch.Source.entries.first()
    )

    private fun IdentifyTab.fill() {
        query = "some words"
        results = listOf(match("x"))
        status = "1 matches"
        busy = true
        needsFfmpeg = true
    }

    @Test
    fun `clear empties everything and bumps the generation`() {
        val tab = IdentifyTab().apply { fill() }
        val before = tab.generation

        tab.clear()

        assertEquals("", tab.query)
        assertTrue(tab.results.isEmpty())
        assertEquals(null, tab.status)
        assertFalse(tab.busy)
        assertFalse(tab.needsFfmpeg)
        assertEquals(before + 1, tab.generation)
    }

    @Test
    fun `an answer from before the clear is dropped, one from after is kept`() {
        val tab = IdentifyTab()
        val started = tab.generation
        assertTrue(tab.accepts(started))

        tab.clear()

        assertFalse(tab.accepts(started))
        assertTrue(tab.accepts(tab.generation))
    }

    /** The same shape the controller's searches use: note, wait, check, write. */
    private fun deliver(tab: IdentifyTab, startedAt: Int, answer: List<MusicMatch>) {
        if (!tab.accepts(startedAt)) return
        tab.results = answer
        tab.busy = false
    }

    @Test
    fun `a late search result does not bring the cleared tab back`() {
        val tab = IdentifyTab()
        val started = tab.generation
        tab.busy = true

        tab.clear()
        deliver(tab, started, listOf(match("late")))

        assertTrue(tab.results.isEmpty())
        assertFalse(tab.busy)
    }

    @Test
    fun `leaving a tab clears only that tab and lets the controller clear its extras`() {
        val tabs = IdentifyMode.entries.associateWith { IdentifyTab() }
        tabs.values.forEach { it.fill() }
        val extras = mutableListOf<IdentifyMode>()

        clearLeftIdentifyTab(IdentifyMode.LYRICS, tabs) { extras += it }

        assertEquals("", tabs.getValue(IdentifyMode.LYRICS).query)
        assertTrue(tabs.getValue(IdentifyMode.LYRICS).results.isEmpty())
        for (other in IdentifyMode.entries - IdentifyMode.LYRICS) {
            val t = tabs.getValue(other)
            assertEquals("some words", t.query)
            assertEquals(1, t.results.size)
            assertTrue(t.busy)
            assertEquals(0, t.generation)
        }
        assertEquals(listOf(IdentifyMode.LYRICS), extras)
    }
}
