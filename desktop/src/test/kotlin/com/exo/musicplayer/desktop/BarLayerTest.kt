package com.exo.musicplayer.desktop

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.exo.musicplayer.desktop.ui.Advanced
import com.exo.musicplayer.desktop.ui.BarControl
import com.exo.musicplayer.desktop.ui.LucenseContextMenu
import com.exo.musicplayer.desktop.ui.LucenseDesktopTheme
import com.exo.musicplayer.desktop.ui.MoreMenu
import com.exo.musicplayer.desktop.ui.NotedMenuItem
import com.exo.musicplayer.desktop.ui.RatingMenuItem
import com.exo.musicplayer.desktop.ui.SubmenuItem
import com.exo.musicplayer.desktop.ui.barShows
import com.exo.musicplayer.desktop.ui.songMenu
import com.exo.musicplayer.desktop.ui.tidyUpItems
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The simpler player bar, the song menu's "More" and the library header's "Tidy up". */
@OptIn(ExperimentalTestApi::class)
class BarLayerTest {

    @Test
    fun theLoopAndOutputAreHiddenUntilAskedForOrInUse() {
        for (control in listOf(BarControl.AB_LOOP, BarControl.OUTPUT)) {
            assertFalse(barShows(control, advanced = false, active = false), "$control hidden by default")
            assertTrue(barShows(control, advanced = true, active = false), "$control shown with advanced on")
            assertTrue(barShows(control, advanced = false, active = true), "$control shown while in use")
            assertTrue(barShows(control, advanced = true, active = true))
        }
    }

    @Test
    fun theEverydayControlsAreAlwaysThere() {
        for (control in listOf(BarControl.QUEUE, BarControl.LYRICS, BarControl.EFFECTS)) {
            assertTrue(barShows(control, advanced = false, active = false))
        }
    }

    @Test
    fun aSubmenuOpensOnClickRunsARowOnceAndClosesEverything() = runComposeUiTest {
        var trimmed = 0
        var played = 0
        setContent {
            LucenseDesktopTheme {
                CompositionLocalProvider(LocalContextMenuRepresentation provides LucenseContextMenu) {
                    ContextMenuArea(items = {
                        listOf(
                            ContextMenuItem("Play") { played++ },
                            SubmenuItem("More", listOf(NotedMenuItem("Trim...", "Cut it.") { trimmed++ }))
                        )
                    }) {
                        Box(Modifier.size(300.dp).testTag("area"))
                    }
                }
            }
        }
        onNodeWithTag("area").performMouseInput { rightClick(Offset(20f, 20f)) }
        onNodeWithText("Trim...").assertDoesNotExist()

        onNodeWithText("More").performClick()
        waitForIdle()
        onNodeWithText("Trim...").assertExists()
        // The first level is still there beside it.
        onNodeWithText("Play").assertExists()

        onNodeWithText("Trim...").performClick()
        waitForIdle()
        assertEquals(1, trimmed)
        assertEquals(0, played)
        onNodeWithText("Trim...").assertDoesNotExist()
        onNodeWithText("Play").assertDoesNotExist()
        onNodeWithText("More").assertDoesNotExist()
    }

    @Test
    fun theKeyboardWalksIntoASubmenuAndBackOut() = runComposeUiTest {
        var trimmed = 0
        setContent {
            LucenseDesktopTheme {
                CompositionLocalProvider(LocalContextMenuRepresentation provides LucenseContextMenu) {
                    ContextMenuArea(items = {
                        listOf(
                            ContextMenuItem("Play") {},
                            SubmenuItem("More", listOf(NotedMenuItem("Trim...", "Cut it.") { trimmed++ }))
                        )
                    }) {
                        Box(Modifier.size(300.dp).testTag("area"))
                    }
                }
            }
        }
        onNodeWithTag("area").performMouseInput { rightClick(Offset(20f, 20f)) }
        fun press(key: Key) {
            onNodeWithText("Play").performKeyInput { keyDown(key); keyUp(key) }
            waitForIdle()
        }
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        press(Key.DirectionRight)
        onNodeWithText("Trim...").assertExists()
        // Left closes the second level and leaves the first; Right goes back in.
        press(Key.DirectionLeft)
        onNodeWithText("Trim...").assertDoesNotExist()
        onNodeWithText("Play").assertExists()
        press(Key.DirectionRight)
        onNodeWithText("Trim...").assertExists()
        press(Key.Enter)
        assertEquals(1, trimmed)
        onNodeWithText("Play").assertDoesNotExist()
    }

    @Test
    fun theLibrarySongMenuIsShortWithTheRestUnderMore() {
        val menu = songMenu(
            favourite = false, rating = 3, canRevert = true,
            onPlay = {}, onPlayNext = {}, onAddToQueue = {}, onToggleFavourite = {}, onAddToPlaylist = {},
            onEdit = {}, onRate = {}, onTrim = {}, onIdentify = {}, onShowInFolder = {}, onRevert = {}, onDelete = {}
        )
        val top = menu.map { it.label }
        assertTrue(menu.any { it is RatingMenuItem }, "the rating row is on the top level")
        assertTrue("More" in top)
        assertTrue(top.none { it == "Trim..." || it == "Identify this track" || it.startsWith("Revert") })
        assertEquals("Delete", top.last().substringBefore(" ("))
        assertEquals(9, menu.size)

        val more = (menu.single { it is SubmenuItem } as SubmenuItem).items.map { it.label }
        assertTrue("Trim..." in more && "Identify this track" in more)
        assertTrue(more.any { it.startsWith("Revert") }, "revert is offered when it applies")

        val untouched = songMenu(
            favourite = true, rating = 0, canRevert = false,
            onPlay = {}, onPlayNext = {}, onAddToQueue = {}, onToggleFavourite = {}, onAddToPlaylist = {},
            onEdit = {}, onRate = {}, onTrim = {}, onIdentify = {}, onShowInFolder = {}, onRevert = {}, onDelete = {}
        )
        assertTrue("Remove from favourites" in untouched.map { it.label })
        assertTrue((untouched.single { it is SubmenuItem } as SubmenuItem).items.none { it.label.startsWith("Revert") })
    }

    @Test
    fun tidyUpHoldsBulkAndDuplicatesAndZipOnlyForAdvanced() {
        fun labels(advanced: Boolean, zipRunning: Boolean = false) =
            tidyUpItems(advanced, zipRunning, {}, {}, {}).map { it.label }

        assertEquals(listOf("Bulk tools", "Find duplicates"), labels(advanced = false))
        assertEquals(listOf("Bulk tools", "Find duplicates", "Zip and ship"), labels(advanced = true))
        assertTrue("Zip and ship" in labels(advanced = false, zipRunning = true), "a zip under way is not hidden")
        assertFalse(tidyUpItems(false, true, {}, {}, {}).last().enabled)
    }

    @Test
    fun theTidyUpButtonOpensItsRowsAndAdvancedAddsZip() = runComposeUiTest {
        val before = Advanced.on
        try {
            Advanced.on = false
            var bulk = 0
            setContent {
                LucenseDesktopTheme {
                    MoreMenu(
                        label = "Tidy up",
                        items = tidyUpItems(Advanced.on, false, { bulk++ }, {}, {}),
                        iconOnly = false
                    )
                }
            }
            onNodeWithText("Tidy up").performClick()
            waitForIdle()
            onNodeWithText("Bulk tools").assertExists()
            onNodeWithText("Find duplicates").assertExists()
            onNodeWithText("Zip and ship").assertDoesNotExist()

            onNodeWithText("Bulk tools").performClick()
            waitForIdle()
            assertEquals(1, bulk)

            Advanced.on = true
            waitForIdle()
            onNodeWithText("Tidy up").performClick()
            waitForIdle()
            onNodeWithText("Zip and ship").assertExists()
        } finally {
            Advanced.on = before
        }
    }
}
