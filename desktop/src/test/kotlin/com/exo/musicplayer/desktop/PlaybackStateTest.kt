package com.exo.musicplayer.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import com.exo.musicplayer.desktop.audio.PlaybackEngine
import com.exo.musicplayer.desktop.ui.collectNowPlaying
import com.exo.musicplayer.desktop.ui.collectPositionMs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The position moves four times a second; only what shows it should redraw. */
@OptIn(ExperimentalTestApi::class)
class PlaybackStateTest {

    private class Counts {
        var song = 0
        var position = 0
    }

    @Composable
    private fun SongOnly(engine: PlaybackEngine, counts: Counts) {
        val status by engine.collectNowPlaying()
        SideEffect { counts.song++ }
        Text(status.track?.title ?: "nothing")
    }

    @Composable
    private fun PositionOnly(engine: PlaybackEngine, counts: Counts) {
        val position by engine.collectPositionMs()
        SideEffect { counts.position++ }
        Text("$position")
    }

    @Test
    fun movingThePositionRedrawsOnlyWhatShowsIt() = runComposeUiTest {
        val engine = PlaybackEngine()
        val counts = Counts()
        setContent {
            Column {
                SongOnly(engine, counts)
                PositionOnly(engine, counts)
            }
        }
        waitForIdle()
        val songBefore = counts.song
        val positionBefore = counts.position
        repeat(5) {
            engine.seekTo(1_000L * (it + 1))
            waitForIdle()
        }
        assertEquals(songBefore, counts.song, "the song didn't change, so nothing showing it redrew")
        assertTrue(counts.position >= positionBefore + 5, "the position redrew each time: ${counts.position - positionBefore}")
        engine.release()
    }
}
