package com.exo.musicplayer.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import com.exo.musicplayer.desktop.audio.PlaybackEngine
import com.exo.musicplayer.desktop.audio.PlaybackStatus
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * What is playing, without where in it.
 *
 * The engine's status changes four times a second while a song plays, for the
 * position alone. Whatever reads all of it recomposes that often: the shell of
 * the window, the side panel, the queue, every row whose skip check reran. Most
 * of them only show *what* is playing, so they read this, which changes when the
 * song, playing or pausing, its length, the loop or an error does.
 */
@Composable
fun PlaybackEngine.collectNowPlaying(): State<PlaybackStatus> {
    val flow = remember(this) { status.map { it.copy(positionMs = 0L) }.distinctUntilChanged() }
    return flow.collectAsState(initial = status.value.copy(positionMs = 0L))
}

/** The position alone, for the few things that show it: the seek line, its clock, the lyrics. */
@Composable
fun PlaybackEngine.collectPositionMs(): State<Long> {
    val flow = remember(this) { status.map { it.positionMs }.distinctUntilChanged() }
    return flow.collectAsState(initial = status.value.positionMs)
}
