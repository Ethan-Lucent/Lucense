package com.exo.musicplayer.desktop

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.MouseInjectionScope
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.exo.musicplayer.desktop.ui.LineSlider
import com.exo.musicplayer.desktop.ui.LucenseContextMenu
import com.exo.musicplayer.desktop.ui.LucenseDesktopTheme
import com.exo.musicplayer.desktop.ui.RATING_CLEAR
import com.exo.musicplayer.desktop.ui.RATING_STAR
import com.exo.musicplayer.desktop.ui.RATING_STARS_TAG
import com.exo.musicplayer.desktop.ui.RatingMenuItem
import com.exo.musicplayer.desktop.ui.RatingSlider
import com.exo.musicplayer.desktop.ui.ratingAfterClick
import com.exo.musicplayer.desktop.ui.starsAt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The song menu's rating: one control you click or slide, sharing the seek bar's drag. */
@OptIn(ExperimentalTestApi::class)
class RatingSliderTest {

    private val clear = RATING_CLEAR.value / (RATING_CLEAR.value + 5 * RATING_STAR.value)

    @Test
    fun aPointAlongTheStarsIsTheStarUnderIt() {
        assertEquals(0, starsAt(0f, 0.1f))
        assertEquals(0, starsAt(0.09f, 0.1f), "left of the first star is no rating")
        assertEquals(1, starsAt(0.1f, 0.1f))
        assertEquals(1, starsAt(0.2f, 0.1f))
        assertEquals(3, starsAt(0.55f, 0.1f))
        assertEquals(5, starsAt(1f, 0.1f))
        assertEquals(1, starsAt(0.01f, 0f), "with no gap there is always a star")
    }

    @Test
    fun clickingTheStarAlreadyGivenTakesItAway() {
        assertEquals(0, ratingAfterClick(3, 3))
        assertEquals(4, ratingAfterClick(4, 3))
        assertEquals(1, ratingAfterClick(1, 0))
        assertEquals(0, ratingAfterClick(0, 2))
    }

    @Test
    fun slidingFromOneStarToAnotherPicksWhereItLetGo() = slider(current = 0) { picks ->
        stars { moveTo(star(2)); press(); moveTo(star(3)); moveTo(star(4)); release() }
        assertEquals(listOf(4), picks)
    }

    @Test
    fun aClickOnAStarIsThatRating() = slider(current = 1) { picks ->
        stars { moveTo(star(3)); press(); release() }
        assertEquals(listOf(3), picks)
    }

    @Test
    fun slidingLeftOfTheFirstStarIsNoRating() = slider(current = 4) { picks ->
        stars { moveTo(star(2)); press(); moveTo(Offset(2f, centerY)); release() }
        assertEquals(listOf(0), picks)
    }

    @Test
    fun clickingTheCurrentRatingClearsItButSlidingBackToItDoesNot() = slider(current = 3) { picks ->
        stars { moveTo(star(3)); press(); release() }
        assertEquals(listOf(0), picks)
        stars { moveTo(star(3)); press(); moveTo(star(5)); moveTo(star(3)); release() }
        assertEquals(listOf(0, 3), picks)
    }

    @Test
    fun thePressIsFollowedOffTheRowUntilTheButtonComesUp() = slider(current = 0) { picks ->
        stars { moveTo(star(1)); press(); moveTo(Offset(width + 150f, centerY + 60f)); release() }
        assertEquals(listOf(5), picks)
    }

    @Test
    fun aRightClickOnTheStarsDoesNothing() = slider(current = 2) { picks ->
        stars { moveTo(star(4)); press(MouseButton.Secondary); release(MouseButton.Secondary) }
        assertEquals(emptyList(), picks)
    }

    @Test
    fun theSeekBarStillSeeksOncePerClick() = runComposeUiTest {
        val sets = mutableListOf<Float>()
        setContent {
            LucenseDesktopTheme {
                LineSlider(0.2f, onSet = { sets += it }, modifier = Modifier.width(200.dp).testTag("seek"))
            }
        }
        onNodeWithTag("seek").performMouseInput { moveTo(center); press(); release() }
        assertEquals(1, sets.size)
        assertTrue(sets.single() in 0.45f..0.55f, "set ${sets.single()}")
    }

    @Test
    fun theMenuHoldsTheStarsAndSavesTheRatingAfterItLands() = runComposeUiTest {
        var rated: Int? = null
        var played = 0
        setContent {
            LucenseDesktopTheme {
                CompositionLocalProvider(LocalContextMenuRepresentation provides LucenseContextMenu) {
                    ContextMenuArea(items = {
                        listOf(ContextMenuItem("Play") { played++ }, RatingMenuItem(2) { rated = it })
                    }) {
                        Box(Modifier.size(300.dp).testTag("area"))
                    }
                }
            }
        }
        onNodeWithTag("area").performMouseInput { rightClick(Offset(20f, 20f)) }
        stars { moveTo(star(1)); press(); moveTo(star(5)); release() }
        waitUntil(timeoutMillis = 2_000) { rated != null }
        assertEquals(5, rated)
        onNodeWithText("Play").assertDoesNotExist()
        assertEquals(0, played)

        onNodeWithTag("area").performMouseInput { rightClick(Offset(20f, 20f)) }
        onNodeWithText("Play").performMouseInput { moveTo(center); press(); release() }
        waitForIdle()
        assertEquals(1, played)
        onNodeWithText("Play").assertDoesNotExist()
    }

    private fun slider(current: Int, body: ComposeUiTest.(MutableList<Int>) -> Unit) = runComposeUiTest {
        val picks = mutableListOf<Int>()
        setContent { LucenseDesktopTheme { RatingSlider(current, onPick = { picks += it }) } }
        body(picks)
    }

    private fun ComposeUiTest.stars(input: MouseInjectionScope.() -> Unit) {
        onNodeWithTag(RATING_STARS_TAG).performMouseInput(input)
        waitForIdle()
    }

    /** The middle of the [n]th star. */
    private fun MouseInjectionScope.star(n: Int) = Offset(width * (clear + (n - 0.5f) / 5f * (1f - clear)), centerY)
}
