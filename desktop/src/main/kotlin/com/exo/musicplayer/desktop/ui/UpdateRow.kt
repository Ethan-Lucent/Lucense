package com.exo.musicplayer.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.exo.musicplayer.desktop.data.DesktopController
import com.exo.musicplayer.desktop.update.UpdateState
import com.exo.musicplayer.desktop.update.UpdateText

/**
 * Settings > About > Updates. With a newer release that can be installed in
 * place: "Update to X (size)" beside "Full installer"; then the download's
 * progress, "Restart to update", and on failure the reason and "Try again".
 * Without one (no update files in the release, or a copy that wasn't installed
 * by the installer): the old "Get X" link to the release page.
 */
@Composable
fun UpdateRow(controller: DesktopController, openLink: (String) -> Unit) {
    val updater = controller.updater
    val state = controller.updateState
    val release = updater.release?.takeIf { updater.newer }
    val page = release?.page
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Updates", style = MaterialTheme.typography.bodyMedium, color = Palette.Text)
            Hint(updateLine(state, controller.updateNote))
        }
        when (state) {
            is UpdateState.Available -> {
                val summary = state.summary
                when {
                    state.canDelta && summary != null -> {
                        AccentButton(UpdateText.updateButton(state.release.version, summary.bytes), note = Notes.UPDATE_DELTA) { updater.download() }
                        Spacer(Modifier.width(8.dp))
                        GhostButton("Full installer", note = Notes.UPDATE_FULL) { openLink(state.release.page) }
                    }
                    state.canDelta -> {
                        // Still comparing the installed files.
                        GhostButton("Full installer", note = Notes.UPDATE_FULL) { openLink(state.release.page) }
                    }
                    else -> AccentButton("Get ${state.release.version}", note = Notes.UPDATE_FULL) { openLink(state.release.page) }
                }
                Spacer(Modifier.width(8.dp))
                GhostButton("Check now", note = Notes.UPDATE_CHECK) { controller.checkForUpdate() }
            }
            is UpdateState.Downloading -> GhostButton("Cancel", note = Notes.UPDATE_CANCEL) { updater.cancel() }
            is UpdateState.Ready -> {
                if (state.deferred) {
                    GhostButton("Restart to update", note = Notes.UPDATE_RESTART) { updater.applyAndRestart() }
                } else {
                    AccentButton("Restart to update", note = Notes.UPDATE_RESTART) { updater.applyAndRestart() }
                    Spacer(Modifier.width(8.dp))
                    GhostButton("Not now", note = Notes.UPDATE_LATER) { updater.later() }
                }
            }
            UpdateState.Applying -> Unit
            is UpdateState.Failed -> {
                if (state.canRetry) {
                    AccentButton("Try again", note = Notes.UPDATE_RETRY) { updater.retry() }
                    Spacer(Modifier.width(8.dp))
                }
                if (page != null) GhostButton("Full installer", note = Notes.UPDATE_FULL) { openLink(page) }
            }
            UpdateState.Idle, UpdateState.Checking -> {
                // A newer release found by the old route only (nothing in place to update): its link.
                if (release != null) {
                    AccentButton("Get ${release.version}", note = Notes.UPDATE_FULL) { openLink(release.page) }
                    Spacer(Modifier.width(8.dp))
                }
                GhostButton("Check now", enabled = state != UpdateState.Checking, note = Notes.UPDATE_CHECK) { controller.checkForUpdate() }
            }
        }
    }
    when (state) {
        is UpdateState.Downloading -> {
            Spacer(Modifier.height(8.dp))
            ThinProgress(if (state.bytesTotal > 0) state.bytesDone.toFloat() / state.bytesTotal else null)
        }
        UpdateState.Applying -> {
            Spacer(Modifier.height(8.dp))
            ThinProgress(null)
        }
        is UpdateState.Failed -> {
            Spacer(Modifier.height(8.dp))
            // Tinted from the theme's own error colour, light enough to keep a glass pane readable.
            val error = MaterialTheme.colorScheme.error
            Text(
                state.message,
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Text,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(error.copy(alpha = 0.16f))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
        else -> Unit
    }
}

/** The line under "Updates", for each state. */
internal fun updateLine(state: UpdateState, note: String?): String = when (state) {
    UpdateState.Idle -> note ?: "Looked up when Lucense starts."
    UpdateState.Checking -> "Looking for a newer version…"
    is UpdateState.Available -> when {
        !state.canDelta -> "Version ${state.release.version} is out." + (state.whyNot?.let { " $it" } ?: "")
        state.summary == null -> "Version ${state.release.version} is out. Checking which files changed…"
        else -> "Version ${state.release.version} is out. Only the changed files are downloaded: " +
            "${state.summary.files} ${if (state.summary.files == 1) "file" else "files"}."
    }
    is UpdateState.Downloading -> UpdateText.downloading(state.done, state.total, state.bytesDone, state.bytesTotal)
    is UpdateState.Ready -> state.note?.let { "$it Version ${state.version} is downloaded and ready." }
        ?: "Version ${state.version} is downloaded. Restart Lucense to finish updating."
    UpdateState.Applying -> "Updating…"
    is UpdateState.Failed -> "The update didn't finish."
}
