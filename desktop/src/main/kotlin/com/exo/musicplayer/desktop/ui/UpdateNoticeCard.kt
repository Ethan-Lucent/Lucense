package com.exo.musicplayer.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.exo.musicplayer.desktop.data.DesktopController
import com.exo.musicplayer.desktop.update.NoticeView
import com.exo.musicplayer.desktop.update.offerLine
import kotlinx.coroutines.delay
import java.awt.Desktop
import java.net.URI

/** What the card's buttons do; the card itself knows nothing of the updater. */
class NoticeActions(
    val onUpdate: () -> Unit = {},
    val onLater: () -> Unit = {},
    val onSkip: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onTryAgain: () -> Unit = {},
    val onNotNow: () -> Unit = {},
    val onFullInstaller: () -> Unit = {},
    val onDismiss: () -> Unit = {}
)

/** How long the after-restart result stays before it fades on its own. */
internal const val NOTICE_RESULT_MS = 8_000L

/**
 * The slim update card over the main window: the offer, the progress of the
 * one-click update, and what the last update came to. Nothing is drawn for
 * [NoticeView.Hidden]. It takes no focus when it appears, so the keyboard
 * shortcuts keep working.
 */
@Composable
fun UpdateNoticeCard(view: NoticeView, actions: NoticeActions, modifier: Modifier = Modifier) {
    if (view == NoticeView.Hidden) return
    val tint = when {
        view is NoticeView.Result && view.ok -> Palette.Accent.copy(alpha = 0.14f)
        view is NoticeView.Result || view is NoticeView.Failed -> MaterialTheme.colorScheme.error.copy(alpha = 0.16f)
        else -> Color.Transparent
    }
    Box(
        modifier
            .width(410.dp)
            // The app's floating look (glass-safe); the tint goes on top of it, inside the same rounded edge.
            .floating(12.dp)
            .background(tint)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        when (view) {
            NoticeView.Hidden -> Unit
            is NoticeView.Offer -> Column {
                Title("Lucense ${view.version} is ready")
                Line(offerLine(view))
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AccentButton("Update now", note = Notes.NOTICE_UPDATE, onClick = actions.onUpdate)
                    GhostButton("Later", note = Notes.NOTICE_LATER, onClick = actions.onLater)
                    GhostButton("Skip this version", note = Notes.NOTICE_SKIP, onClick = actions.onSkip)
                }
            }
            is NoticeView.Progress -> Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Title(view.text) }
                    Spacer(Modifier.width(8.dp))
                    GhostButton("Cancel", note = Notes.UPDATE_CANCEL, onClick = actions.onCancel)
                }
                Spacer(Modifier.height(8.dp))
                ThinProgress(view.fraction)
            }
            NoticeView.Installing -> Column {
                Title("Installing… Lucense will restart")
                Spacer(Modifier.height(8.dp))
                ThinProgress(null)
            }
            NoticeView.Cancelled -> Column {
                Title("Update cancelled")
                Line("Lucense is still open, and the update is saved for when you want it.")
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AccentButton("Try again", note = Notes.UPDATE_RETRY, onClick = actions.onTryAgain)
                    GhostButton("Not now", note = Notes.NOTICE_NOT_NOW, onClick = actions.onNotNow)
                }
            }
            is NoticeView.Failed -> Column {
                Title("The update didn't finish")
                Line(view.message)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (view.canRetry) AccentButton("Try again", note = Notes.UPDATE_RETRY, onClick = actions.onTryAgain)
                    GhostButton("Full installer", note = Notes.UPDATE_FULL, onClick = actions.onFullInstaller)
                }
            }
            is NoticeView.Result -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    view.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.Text,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.size(26.dp).clip(CircleShape).clickable(onClick = actions.onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Close, "Dismiss", Modifier.size(14.dp), tint = Palette.TextDim)
                }
            }
        }
    }
}

@Composable
private fun Title(text: String) =
    Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Palette.Text)

@Composable
private fun Line(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)

/**
 * The card wired to the app: shown in the main window only, in the corner
 * above the player bar. It fades in with the app's own timings, and the
 * after-restart result goes by itself after a few seconds.
 */
@Composable
fun UpdateNoticeHost(controller: DesktopController, modalOpen: Boolean, modifier: Modifier = Modifier) {
    val notice = controller.announcer
    // Reading these makes the check below run again when any of them changes.
    val state = controller.updateState
    val on = controller.updateNotice
    LaunchedEffect(state, on, modalOpen, notice.armed) { notice.evaluate(modalOpen, isMainWindow = true) }

    val view = notice.view()
    LaunchedEffect(view) {
        if (view is NoticeView.Result) {
            delay(NOTICE_RESULT_MS)
            notice.dismissResult()
        }
    }
    // The last card shown, so the fade-out has something to draw.
    val last = remember { arrayOfNulls<NoticeView>(1) }
    if (view != NoticeView.Hidden) last[0] = view
    val page = controller.updater.release?.page
    AnimatedVisibility(
        visible = view != NoticeView.Hidden,
        modifier = modifier,
        enter = fadeIn(Motion.change()),
        exit = fadeOut(Motion.quick())
    ) {
        UpdateNoticeCard(
            view = if (view != NoticeView.Hidden) view else last[0] ?: NoticeView.Hidden,
            actions = NoticeActions(
                onUpdate = notice::updateNow,
                onLater = notice::later,
                onSkip = notice::skip,
                onCancel = notice::cancel,
                onTryAgain = notice::updateNow,
                onNotNow = notice::notNow,
                onFullInstaller = { page?.let(::openReleasePage) },
                onDismiss = notice::dismissResult
            )
        )
    }
}

/** Opens the release page in the browser, as the Settings row's link does. */
internal fun openReleasePage(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
        }
    }
}
