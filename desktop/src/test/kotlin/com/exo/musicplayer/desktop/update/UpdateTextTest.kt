package com.exo.musicplayer.desktop.update

import com.exo.musicplayer.desktop.ui.updateLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UpdateTextTest {

    @Test
    fun `sizes read the way file managers show them`() {
        assertEquals("0 bytes", UpdateText.size(0))
        assertEquals("999 bytes", UpdateText.size(999))
        assertEquals("1 KB", UpdateText.size(1_000))
        assertEquals("780 KB", UpdateText.size(780_400))
        assertEquals("999 KB", UpdateText.size(999_499))
        assertEquals("1.0 MB", UpdateText.size(999_500))
        assertEquals("4.6 MB", UpdateText.size(4_600_000))
        assertEquals("78.0 MB", UpdateText.size(78_000_000))
        assertEquals("1.0 GB", UpdateText.size(999_950_000))
        assertEquals("289.0 MB", UpdateText.size(289_000_000))
    }

    @Test
    fun `progress and button wording`() {
        assertEquals("Downloading 3 of 5 files (2.1 of 4.6 MB)", UpdateText.downloading(2, 5, 2_100_000, 4_600_000))
        assertEquals("Downloading 5 of 5 files (4.6 MB)".replace("(4.6 MB)", "(4.6 of 4.6 MB)"), UpdateText.downloading(5, 5, 4_600_000, 4_600_000))
        assertEquals("Downloading 1 file (300 KB of 4.6 MB)", UpdateText.downloading(0, 1, 300_000, 4_600_000))
        assertEquals("Update to 1.58.0  (4.6 MB)", UpdateText.updateButton("1.58.0", 4_600_000))
        assertTrue(UpdateText.rolledBack("1.58.0", "x").startsWith("The update to 1.58.0 didn't work, and your old version was put back: x"))
    }

    @Test
    fun `the Updates row says where things stand`() {
        val release = UpdateRelease("1.58.0", "https://github.com/x", emptyList())
        assertEquals("Up to date.", updateLine(UpdateState.Idle, "Up to date."))
        assertTrue(updateLine(UpdateState.Available(release, UpdateSummary(5, 4_600_000, 2), true, null), null).contains("5 files"))
        assertTrue(updateLine(UpdateState.Available(release, null, false, "No update files."), null).contains("No update files."))
        assertEquals("Downloading 3 of 5 files (2.1 of 4.6 MB)", updateLine(UpdateState.Downloading("1.58.0", 2, 5, 2_100_000, 4_600_000), null))
        assertTrue(updateLine(UpdateState.Ready("1.58.0", note = "Update cancelled."), null).startsWith("Update cancelled."))
        assertEquals("Updating…", updateLine(UpdateState.Applying, null))
    }
}
