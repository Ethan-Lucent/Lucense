package com.exo.musicplayer.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.exo.musicplayer.desktop.data.resolveTipsSeen
import com.exo.musicplayer.desktop.data.tipsSeenDefault
import com.exo.musicplayer.desktop.ui.Destination
import com.exo.musicplayer.desktop.ui.FirstRunCards
import com.exo.musicplayer.desktop.ui.LucenseDesktopTheme
import com.exo.musicplayer.desktop.ui.UI_SCALES
import com.exo.musicplayer.desktop.ui.fitScale
import com.exo.musicplayer.desktop.ui.railNote
import com.exo.musicplayer.desktop.ui.stepUiScale
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The first-run tip's default, the sidebar notes, the interface size rule, and the first-run cards. */
@OptIn(ExperimentalTestApi::class)
class FinishingTest {

    @Test
    fun theTipStartsSeenForAnExistingLibraryAndUnseenForANewInstall() {
        assertEquals(true, tipsSeenDefault(hasLibrary = true))
        assertEquals(false, tipsSeenDefault(hasLibrary = false))
    }

    @Test
    fun theTipIsDecidedOnceAndSaved() {
        var saved: Boolean? = null
        assertEquals(false, resolveTipsSeen(null, { false }, { saved = it }))
        assertEquals(false, saved)
        // Once saved it is never asked again, even though a library exists by now.
        assertEquals(false, resolveTipsSeen("false", { error("must not ask") }, { error("must not save") }))
        assertEquals(true, resolveTipsSeen("true", { error("must not ask") }, { error("must not save") }))
    }

    @Test
    fun everyPageHasANonBlankSidebarNote() {
        Destination.entries.forEach { assertTrue(railNote(it).isNotBlank(), "${it.name} has no note") }
        assertEquals("All your songs.", railNote(Destination.LIBRARY))
    }

    /** The rule before the interface size existed. */
    private fun oldScale(w: Float, h: Float, density: Float, dw: Float, dh: Float, minScale: Float) =
        min(w / density / dw, h / density / dh).coerceIn(minScale, 1f)

    @Test
    fun atNormalSizeTheScaleIsExactlyWhatItWasBefore() {
        val windows = listOf(
            400f to 300f, 700f to 500f, 920f to 600f, 1000f to 700f, 1280f to 800f, 1920f to 1080f, 3840f to 2160f
        )
        for ((w, h) in windows) for (density in listOf(1f, 1.25f, 2f)) for (minScale in listOf(0.62f, 0.8f)) {
            assertEquals(
                oldScale(w, h, density, 920f, 600f, minScale),
                fitScale(w, h, density, 920f, 600f, minScale, 1f),
                "window ${w}x$h at density $density"
            )
        }
        assertEquals(1f, fitScale(0f, 0f, 1f, 920f, 600f, 0.8f, 1.3f), "before the first layout it is drawn normally")
    }

    @Test
    fun aBiggerSizeGrowsInARoomyWindowAndStillShrinksInASmallOne() {
        assertEquals(1.3f, fitScale(3840f, 2160f, 1f, 920f, 600f, 0.8f, 1.3f))
        // Room for 1.1 only: it follows the window, below the chosen size.
        assertEquals(1.1f, fitScale(920f * 1.1f, 600f * 1.1f, 1f, 920f, 600f, 0.8f, 1.3f), 0.0001f)
        // A small window shrinks exactly as it always did.
        assertEquals(0.9f, fitScale(828f, 540f, 1f, 920f, 600f, 0.8f, 1.3f), 0.0001f)
        assertEquals(0.8f, fitScale(100f, 100f, 1f, 920f, 600f, 0.8f, 1.3f), "never below the minimum")
        // A smaller size caps even a huge window.
        assertEquals(0.9f, fitScale(3840f, 2160f, 1f, 920f, 600f, 0.62f, 0.9f))
    }

    @Test
    fun theScaledContentNeverOutgrowsTheWindow() {
        for (factor in UI_SCALES.map { it.second }) for (w in listOf(740f, 920f, 1200f, 2000f)) {
            val s = fitScale(w, w * 0.65f, 1f, 920f, 600f, 0.8f, factor)
            assertTrue(920f * s <= w + 0.01f || s == 0.8f, "factor $factor in $w wide: scale $s overflows")
        }
    }

    @Test
    fun theFourSizesAreStepThroughInOrderAndStopAtTheEnds() {
        assertEquals(listOf(0.9f, 1f, 1.15f, 1.3f), UI_SCALES.map { it.second })
        assertEquals(1.15f, stepUiScale(1f, 1))
        assertEquals(0.9f, stepUiScale(1f, -1))
        assertEquals(1.3f, stepUiScale(1.3f, 1))
        assertEquals(0.9f, stepUiScale(0.9f, -1))
    }

    @Test
    fun theFirstRunCardsShowAndEachOpensItsPage() = runComposeUiTest {
        val went = mutableListOf<Destination>()
        setContent { LucenseDesktopTheme { FirstRunCards { went += it } } }
        listOf(
            "Download a song" to Destination.DOWNLOAD,
            "Discover music" to Destination.DISCOVER,
            "Identify a song" to Destination.IDENTIFY
        ).forEach { (title, page) ->
            onNodeWithText(title).assertExists()
            onNodeWithText(title).performClick()
            assertEquals(page, went.last())
        }
        onNodeWithText("Paste a link from YouTube, SoundCloud or Bandcamp.").assertExists()
        assertEquals(3, went.size)
    }
}
