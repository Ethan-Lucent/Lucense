package com.exo.musicplayer.desktop.update

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.exo.musicplayer.desktop.data.DesktopSettings
import com.exo.musicplayer.desktop.ui.LucenseDesktopTheme
import com.exo.musicplayer.desktop.ui.NoticeActions
import com.exo.musicplayer.desktop.ui.UPDATE_NOTICE_HINT
import com.exo.musicplayer.desktop.ui.UPDATE_NOTICE_LABEL
import com.exo.musicplayer.desktop.ui.UpdateNoticeCard
import com.exo.musicplayer.desktop.ui.findSettings
import com.exo.musicplayer.desktop.ui.settingsIndex
import com.exo.musicplayer.desktop.ui.updateLine
import com.exo.musicplayer.desktop.ui.updateNoticeActionLabel
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The launch notice's rules, its card, and its setting: no network, no real window. */
@OptIn(ExperimentalTestApi::class)
class UpdateNoticeTest {

    private fun release(version: String = "1.57.2") = UpdateRelease(version, "https://github.com/x/y/releases/tag/v$version", emptyList())

    private fun offer(version: String = "1.57.2", summary: UpdateSummary? = UpdateSummary(5, 77_000_000, 2, 4_600_000, true), canDelta: Boolean = true) =
        UpdateState.Available(release(version), summary, canDelta, if (canDelta) null else "This release has no update files for linux-x64.")

    private fun announce(
        settingOn: Boolean = true,
        skipped: String = "",
        state: UpdateState = offer(),
        shown: Boolean = false,
        modal: Boolean = false,
        main: Boolean = true
    ) = shouldAnnounce(settingOn, skipped, state, shown, modal, main)

    // ---- the decision ----------------------------------------------------------------

    @Test
    fun anOfferThatCanBeInstalledHereIsAnnouncedWhenNothingGetsInTheWay() {
        assertTrue(announce())
        assertTrue(announce(skipped = "1.50.0"), "an older skip doesn't silence a newer release")
    }

    @Test
    fun theSettingOffSaysNothing() {
        assertFalse(announce(settingOn = false))
    }

    @Test
    fun aReleaseWithoutThisSystemsBuildOrAnInstallThatCantUpdateShowsNothing() {
        assertFalse(announce(state = offer(canDelta = false)), "no build for this OS, a dev run, or a layout it can't handle")
        assertFalse(announce(state = offer(summary = null)), "still comparing the installed files")
        for (state in listOf(UpdateState.Idle, UpdateState.Checking, UpdateState.Applying, UpdateState.Failed("x", true))) {
            assertFalse(announce(state = state), "$state")
        }
        assertNull(announceableVersion(offer(canDelta = false)))
        assertEquals("1.57.2", announceableVersion(offer()))
    }

    @Test
    fun aVersionTheUserSkippedStaysQuietUntilANewerOneComes() {
        assertFalse(announce(skipped = "1.57.2"), "the same version")
        assertFalse(announce(skipped = "1.58.0"), "a newer skip covers an older release")
        assertTrue(announce(skipped = "1.57.1"), "an older skip")
        assertTrue(announce(skipped = ""), "nothing skipped")
        assertTrue(announce(skipped = "garbage"), "an unreadable skip never silences an update")
        assertTrue(isSkipped("1.57.2", "1.57.2"))
    }

    @Test
    fun onlyOncePerLaunchNotOverAModalAndNotOutsideTheMainWindow() {
        assertFalse(announce(shown = true))
        assertFalse(announce(modal = true), "waits until the dialog or Ctrl+K is closed")
        assertTrue(announce(modal = false))
        assertFalse(announce(main = false), "not the mini player or lyrics window")
    }

    @Test
    fun anUpdateDownloadedEarlierIsOfferedAsReadyUnlessPutOff() {
        assertTrue(announce(state = UpdateState.Ready("1.57.2")))
        assertFalse(announce(state = UpdateState.Ready("1.57.2", deferred = true)))
        assertFalse(announce(state = UpdateState.Ready("1.57.2", note = "Update cancelled.")))
    }

    // ---- the words ---------------------------------------------------------------------

    @Test
    fun theOfferSaysWhatItCostsAndWhetherAPasswordWillBeAsked() {
        val plain = NoticeView.Offer("1.57.2", "4.6 MB", needsAdmin = false, downloaded = false)
        assertEquals("A small update: 4.6 MB. It installs itself and Lucense restarts.", offerLine(plain))
        assertEquals(
            "A small update: 4.6 MB. It installs itself and Lucense restarts. You'll be asked for your password once.",
            offerLine(plain.copy(needsAdmin = true))
        )
        assertEquals("A small update: up to 77.0 MB. It installs itself and Lucense restarts.", offerLine(plain.copy(size = UpdateText.transfer(77_000_000, false))))
        assertTrue(offerLine(plain.copy(downloaded = true)).startsWith("Already downloaded."))
    }

    @Test
    fun theSettingsRowSaysWhetherItLooksByItself() {
        assertEquals("Looked up when Lucense starts.", updateLine(UpdateState.Idle, null))
        assertTrue(updateLine(UpdateState.Idle, null, lookedUpAtStart = false).contains("Check now"))
        assertEquals("Up to date.", updateLine(UpdateState.Idle, "Up to date.", lookedUpAtStart = false))
    }

    // ---- the card ----------------------------------------------------------------------

    private fun card(view: NoticeView, actions: NoticeActions = NoticeActions(), check: androidx.compose.ui.test.ComposeUiTest.() -> Unit) = runComposeUiTest {
        setContent { LucenseDesktopTheme { UpdateNoticeCard(view, actions) } }
        check()
    }

    @Test
    fun theOfferShowsTheVersionAndSizeAndEachButtonCallsBackOnce() {
        val calls = mutableListOf<String>()
        val actions = NoticeActions(onUpdate = { calls += "update" }, onLater = { calls += "later" }, onSkip = { calls += "skip" })
        card(NoticeView.Offer("1.57.2", "4.6 MB", needsAdmin = false, downloaded = false), actions) {
            onNodeWithText("Lucense 1.57.2 is ready").assertIsDisplayed()
            onNodeWithText("A small update: 4.6 MB. It installs itself and Lucense restarts.").assertIsDisplayed()
            onNodeWithText("Update now").performClick()
            assertEquals(listOf("update"), calls)
            onNodeWithText("Later").performClick()
            onNodeWithText("Skip this version").performClick()
            assertEquals(listOf("update", "later", "skip"), calls)
        }
    }

    @Test
    fun nothingIsDrawnWhenThereIsNothingToOffer() = card(NoticeView.Hidden) {
        onNodeWithText("Update now").assertDoesNotExist()
        onNodeWithText("Later").assertDoesNotExist()
    }

    @Test
    fun laterHidesTheCard() = runComposeUiTest {
        var view by mutableStateOf<NoticeView>(NoticeView.Offer("1.57.2", "4.6 MB", needsAdmin = false, downloaded = false))
        setContent { LucenseDesktopTheme { UpdateNoticeCard(view, NoticeActions(onLater = { view = NoticeView.Hidden })) } }
        onNodeWithText("Later").performClick()
        waitForIdle()
        onNodeWithText("Lucense 1.57.2 is ready").assertDoesNotExist()
    }

    @Test
    fun progressShowsTheDownloadAndCancelCallsBack() {
        var cancelled = 0
        card(NoticeView.Progress("Downloading 3 of 5 files (2.1 of 4.6 MB)", 0.45f), NoticeActions(onCancel = { cancelled++ })) {
            onNodeWithText("Downloading 3 of 5 files (2.1 of 4.6 MB)").assertIsDisplayed()
            onNodeWithText("Cancel").performClick()
            assertEquals(1, cancelled)
        }
    }

    @Test
    fun installingCancelledAndFailedSayWhatHappened() {
        card(NoticeView.Installing) { onNodeWithText("Installing… Lucense will restart").assertIsDisplayed() }
        val calls = mutableListOf<String>()
        card(NoticeView.Cancelled, NoticeActions(onTryAgain = { calls += "again" }, onNotNow = { calls += "not now" })) {
            onNodeWithText("Update cancelled").assertIsDisplayed()
            onNodeWithText("Try again").performClick()
            onNodeWithText("Not now").performClick()
            assertEquals(listOf("again", "not now"), calls)
        }
        val failed = mutableListOf<String>()
        card(NoticeView.Failed("Not enough free space: the update needs 90 MB"), NoticeActions(onTryAgain = { failed += "again" }, onFullInstaller = { failed += "installer" })) {
            onNodeWithText("Not enough free space: the update needs 90 MB").assertIsDisplayed()
            onNodeWithText("Try again").performClick()
            onNodeWithText("Full installer").performClick()
            assertEquals(listOf("again", "installer"), failed)
        }
        card(NoticeView.Failed("The release has no way to resume", canRetry = false)) {
            onNodeWithText("Try again").assertDoesNotExist()
            onNodeWithText("Full installer").assertIsDisplayed()
        }
    }

    @Test
    fun theResultAfterARestartShowsItsMessageWithADismiss() {
        var dismissed = 0
        card(NoticeView.Result("Updated to 1.57.2.", ok = true), NoticeActions(onDismiss = { dismissed++ })) {
            onNodeWithText("Updated to 1.57.2.").assertIsDisplayed()
            onNodeWithContentDescription("Dismiss").performClick()
            assertEquals(1, dismissed)
        }
        card(NoticeView.Result(UpdateText.rolledBack("1.57.2", "disk full"), ok = false)) {
            onNodeWithText("The update to 1.57.2 didn't work, and your old version was put back: disk full").assertIsDisplayed()
        }
    }

    // ---- the setting -------------------------------------------------------------------

    @Test
    fun theSettingIsOnByDefaultAndKeepsWhatIsSet() {
        val node = Preferences.userRoot().node("com/exo/musicplayer-noticetest-${System.nanoTime()}")
        try {
            assertTrue(DesktopSettings(node).updateNotice)
            assertEquals("", DesktopSettings(node).updateSkipped)
            DesktopSettings(node).updateNotice = false
            DesktopSettings(node).updateSkipped = "1.57.2"
            assertFalse(DesktopSettings(node).updateNotice)
            assertEquals("1.57.2", DesktopSettings(node).updateSkipped)
            DesktopSettings(node).updateNotice = true
            assertTrue(DesktopSettings(node).updateNotice)
        } finally {
            node.removeNode()
        }
    }

    @Test
    fun theSettingCanBeFoundInSettingsSearchAndCtrlK() {
        assertTrue(settingsIndex.any { it.label == UPDATE_NOTICE_LABEL })
        assertEquals("Tell me when an update is available", UPDATE_NOTICE_LABEL)
        assertTrue(UPDATE_NOTICE_HINT.startsWith("When Lucense starts it looks for a newer version"))
        assertTrue(findSettings("update notice").any { it.label == UPDATE_NOTICE_LABEL })
        assertTrue(findSettings("tell me").any { it.label == UPDATE_NOTICE_LABEL })
        assertEquals("Turn update notices off", updateNoticeActionLabel(true))
        assertEquals("Turn update notices on", updateNoticeActionLabel(false))
    }

    @Test
    fun theSizeShownIsTheDownloadAndOnlyUpToWhenItIsTheInstalledTotal() {
        assertEquals("Update to 1.57.2  (4.6 MB)", UpdateText.updateButton("1.57.2", 4_600_000))
        assertEquals("Update to 1.57.2  (up to 77.0 MB)", UpdateText.updateButton("1.57.2", 77_000_000, exact = false))
    }
}

