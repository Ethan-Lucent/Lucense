package com.exo.musicplayer.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.exo.musicplayer.data.audio.EffectPreset
import com.exo.musicplayer.data.download.DownloadFormat
import com.exo.musicplayer.data.download.DownloadQuality
import com.exo.musicplayer.desktop.data.DownloadEntry
import com.exo.musicplayer.desktop.ui.Collapsible
import com.exo.musicplayer.desktop.ui.DesktopFxState
import com.exo.musicplayer.desktop.ui.LucenseDesktopTheme
import com.exo.musicplayer.desktop.ui.downloadersShown
import com.exo.musicplayer.desktop.ui.fineTuneOpen
import com.exo.musicplayer.desktop.ui.fxSummary
import com.exo.musicplayer.desktop.ui.lastFailureRefused
import com.exo.musicplayer.desktop.ui.optionsSummary
import com.exo.musicplayer.desktop.ui.refusedByYouTube
import com.exo.musicplayer.desktop.ui.youtubeBlockShown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The effects and download pages' simple layout: what is folded away, and that
 * nothing that is on or broken is ever hidden. The panels themselves need a
 * controller, so the rules are tested as the pure helpers they call, plus the
 * Fine-tune section opened or closed by them.
 */
@OptIn(ExperimentalTestApi::class)
class PanelsLayerTest {

    // ---- Effects ------------------------------------------------------------

    @Test
    fun fineTuneStaysFoldedForPlainSettingsAndOpensForAnythingOn() {
        assertFalse(fineTuneOpen(DesktopFxState(), advanced = false))
        // Speed and pitch live above Fine-tune, so they don't open it.
        assertFalse(fineTuneOpen(DesktopFxState(speed = 0.8f, pitchSemitones = -2f), advanced = false))
        assertTrue(fineTuneOpen(DesktopFxState(reverbEnabled = true), advanced = false))
        assertTrue(fineTuneOpen(DesktopFxState(eqEnabled = true), advanced = false))
        assertTrue(fineTuneOpen(DesktopFxState(drive = 0.5f), advanced = false))
        assertTrue(fineTuneOpen(DesktopFxState(lofi = 0.5f), advanced = false))
        assertTrue(fineTuneOpen(DesktopFxState(tremolo = 0.5f), advanced = false))
        assertTrue(fineTuneOpen(DesktopFxState(rotate = 0.5f), advanced = false))
        assertTrue(fineTuneOpen(DesktopFxState(echo = 0.5f), advanced = false))
    }

    @Test
    fun fineTuneIsAlwaysOpenWhenAdvancedIsOn() {
        assertTrue(fineTuneOpen(DesktopFxState(), advanced = true))
        assertTrue(fineTuneOpen(DesktopFxState(echo = 0.5f), advanced = true))
    }

    @Test
    fun theReadoutNamesThePresetCustomOrPresetWithTweaks() {
        assertEquals("Normal", fxSummary(DesktopFxState()))
        val slowedReverb = DesktopFxState().applying(EffectPreset.SLOWED_REVERB)
        assertEquals(EffectPreset.SLOWED_REVERB.label, fxSummary(slowedReverb))
        assertEquals("Custom", fxSummary(DesktopFxState(speed = 1.23f)))
        assertEquals("${EffectPreset.SLOWED_REVERB.label} + tweaks", fxSummary(slowedReverb.copy(echo = 0.6f)))
    }

    @Test
    fun fineTuneContentIsHiddenWhenPlainAndShownWhenSomethingIsOn() = runComposeUiTest {
        val fx = DesktopFxState()
        setContent {
            LucenseDesktopTheme {
                Column { Collapsible("Fine-tune", startOpen = fineTuneOpen(fx, advanced = false)) { Text("echo slider") } }
            }
        }
        onNodeWithText("echo slider").assertDoesNotExist()
    }

    @Test
    fun fineTuneContentIsShownWhenAnEffectIsOn() = runComposeUiTest {
        val fx = DesktopFxState(echo = 0.5f)
        setContent {
            LucenseDesktopTheme {
                Column { Collapsible("Fine-tune", startOpen = fineTuneOpen(fx, advanced = false)) { Text("echo slider") } }
            }
        }
        onNodeWithText("echo slider").assertExists()
    }

    // ---- Download page ------------------------------------------------------

    @Test
    fun theYouTubeBlockShowsOnlyForARefusalASignInOrTheAdvancedOrARequest() {
        assertFalse(youtubeBlockShown(refused = false, signedIn = false, advanced = false, revealed = false))
        assertTrue(youtubeBlockShown(refused = true, signedIn = false, advanced = false, revealed = false))
        assertTrue(youtubeBlockShown(refused = false, signedIn = true, advanced = false, revealed = false))
        assertTrue(youtubeBlockShown(refused = false, signedIn = false, advanced = true, revealed = false))
        assertTrue(youtubeBlockShown(refused = false, signedIn = false, advanced = false, revealed = true))
    }

    @Test
    fun theDownloadersShowForTheAdvancedAndAlwaysWhenSomethingIsWrong() {
        assertFalse(downloadersShown(anyProblem = false, advanced = false))
        assertTrue(downloadersShown(anyProblem = true, advanced = false))
        assertTrue(downloadersShown(anyProblem = false, advanced = true))
        assertTrue(downloadersShown(anyProblem = true, advanced = true))
    }

    @Test
    fun theOptionsLineSaysWhatIsChosen() {
        assertEquals(
            "MP3 · High quality",
            optionsSummary(DownloadFormat.MP3, DownloadQuality.HIGH, embedArt = false, cutNonMusic = false, playlist = false)
        )
        assertEquals(
            "FLAC · lossless · cover art · non-music cut · whole playlist",
            optionsSummary(DownloadFormat.FLAC, DownloadQuality.HIGH, embedArt = true, cutNonMusic = true, playlist = true)
        )
        assertEquals(
            "As sent · High quality",
            optionsSummary(null, DownloadQuality.HIGH, embedArt = false, cutNonMusic = false, playlist = false)
        )
    }

    @Test
    fun aRefusalIsRecognisedOnTheLastFinishedFailureOnly() {
        val refused = DownloadEntry(1, "a", "a", status = "YouTube refused this connection. Wait an hour.", done = true, failed = true)
        val other = DownloadEntry(2, "b", "b", status = "Video unavailable", done = true, failed = true)
        val fine = DownloadEntry(3, "c", "c", status = "Done", done = true)
        val stopped = DownloadEntry(4, "d", "d", done = true, note = "YouTube began refusing this connection, so 3 songs weren't tried.")
        assertTrue(refusedByYouTube(refused))
        assertFalse(refusedByYouTube(other))
        assertTrue(lastFailureRefused(listOf(other, refused, fine)))
        // A later, different failure takes over.
        assertFalse(lastFailureRefused(listOf(refused, other)))
        assertTrue(lastFailureRefused(listOf(stopped)))
        assertFalse(lastFailureRefused(emptyList()))
    }
}
