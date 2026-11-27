package com.exo.musicplayer.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.exo.musicplayer.desktop.data.advancedDefault
import com.exo.musicplayer.desktop.data.resolveAdvanced
import com.exo.musicplayer.desktop.ui.Collapsible
import com.exo.musicplayer.desktop.ui.LucenseDesktopTheme
import com.exo.musicplayer.desktop.ui.MoreItem
import com.exo.musicplayer.desktop.ui.MoreMenu
import com.exo.musicplayer.desktop.ui.noteLines
import kotlin.test.Test
import kotlin.test.assertEquals

/** The shared foundation: the advanced switch's default, the hover-note rule, the More menu and the collapsible section. */
@OptIn(ExperimentalTestApi::class)
class FoundationTest {

    @Test
    fun advancedStartsOnForAnExistingLibraryAndOffForANewInstall() {
        assertEquals(true, advancedDefault(hasLibrary = true))
        assertEquals(false, advancedDefault(hasLibrary = false))
    }

    @Test
    fun theDefaultIsDecidedOnceAndSaved() {
        var saved: Boolean? = null
        // First start, nothing saved: decided from the library and stored.
        assertEquals(false, resolveAdvanced(null, { false }, { saved = it }))
        assertEquals(false, saved)

        // Later the user adds a folder; what was stored wins and the library is not even asked.
        var asked = false
        var resaved = false
        val later = resolveAdvanced(saved.toString(), { asked = true; true }, { resaved = true })
        assertEquals(false, later)
        assertEquals(false, asked)
        assertEquals(false, resaved)

        // An existing install is stored as on, whatever happens to its library afterwards.
        saved = null
        assertEquals(true, resolveAdvanced(null, { true }, { saved = it }))
        assertEquals(true, saved)
        assertEquals(true, resolveAdvanced(saved.toString(), { false }, { }))
    }

    @Test
    fun withNotesOnTheNameAndTheNoteShow() {
        assertEquals(listOf("Lyrics", "The words"), noteLines("Lyrics", "The words", iconOnly = true, notesOn = true))
        assertEquals(listOf("Lyrics", "The words"), noteLines("Lyrics", "The words", iconOnly = false, notesOn = true))
    }

    @Test
    fun withNotesOffAnIconStillShowsItsNameAndALabelledControlShowsNothing() {
        assertEquals(listOf("Lyrics"), noteLines("Lyrics", "The words", iconOnly = true, notesOn = false))
        assertEquals(emptyList(), noteLines("Lyrics", "The words", iconOnly = false, notesOn = false))
    }

    @Test
    fun aMissingNameOrNoteLeavesThatLineOut() {
        assertEquals(listOf("The words"), noteLines(null, "The words", iconOnly = false, notesOn = true))
        assertEquals(listOf("Lyrics"), noteLines("Lyrics", null, iconOnly = true, notesOn = true))
        assertEquals(emptyList(), noteLines(null, null, iconOnly = true, notesOn = true))
        assertEquals(emptyList(), noteLines(null, "The words", iconOnly = true, notesOn = false))
        assertEquals(listOf("The words"), noteLines("  ", "The words", iconOnly = true, notesOn = true))
    }

    @Test
    fun aCollapsibleHidesItsContentUntilTheTitleIsClickedAndHidesItAgain() = runComposeUiTest {
        setContent {
            LucenseDesktopTheme {
                Column { Collapsible("Details") { Text("inside") } }
            }
        }
        onNodeWithText("inside").assertDoesNotExist()
        onNodeWithText("Details").performClick()
        waitUntil(timeoutMillis = 2_000) { onAllNodesWithText("inside").fetchSemanticsNodes().size == 1 }
        onNodeWithText("Details").performClick()
        waitUntil(timeoutMillis = 2_000) { onAllNodesWithText("inside").fetchSemanticsNodes().size == 0 }
    }

    @Test
    fun aCollapsibleCanStartOpen() = runComposeUiTest {
        setContent {
            LucenseDesktopTheme {
                Column { Collapsible("Details", startOpen = true) { Text("inside") } }
            }
        }
        onNodeWithText("inside").assertExists()
    }

    @Test
    fun theMoreMenuShowsItsRowsOnClickRunsOneAndCloses() = runComposeUiTest {
        var ran = 0
        var skipped = 0
        setContent {
            LucenseDesktopTheme {
                MoreMenu(
                    items = listOf(
                        MoreItem("First") { ran++ },
                        MoreItem("Second", enabled = false) { skipped++ }
                    )
                )
            }
        }
        onNodeWithText("First").assertDoesNotExist()
        onNodeWithContentDescription("More").performClick()
        onNodeWithText("First").assertExists()
        onNodeWithText("Second").assertExists()

        onNodeWithText("Second").performClick()
        assertEquals(0, skipped, "a row that is not enabled does nothing")

        onNodeWithText("First").performClick()
        assertEquals(1, ran)
        waitForIdle()
        onNodeWithText("First").assertDoesNotExist()
    }
}
