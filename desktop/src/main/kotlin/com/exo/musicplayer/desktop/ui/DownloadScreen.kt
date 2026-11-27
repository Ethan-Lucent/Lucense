package com.exo.musicplayer.desktop.ui

import com.exo.musicplayer.desktop.system.Platform
import androidx.compose.material.icons.filled.Refresh
import com.exo.musicplayer.desktop.download.YouTubeSignIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.exo.musicplayer.data.download.DownloadFormat
import com.exo.musicplayer.data.download.DownloadQuality
import com.exo.musicplayer.desktop.data.DesktopController
import com.exo.musicplayer.desktop.data.DownloadEntry
import com.exo.musicplayer.desktop.download.ToolStatus
import com.exo.musicplayer.desktop.download.YtDlp
import com.exo.musicplayer.data.download.YouTubeTrouble
import java.io.File

/** Spotify's caveat, kept with the options and shown under the link box when a Spotify link is pasted. */
private const val SPOTIFY_NOTE =
    "YouTube, SoundCloud and Bandcamp download directly. Spotify links go " +
        "through spotdl, which reads the track, album and artwork from " +
        "Spotify and then fetches the same recording from YouTube — " +
        "Spotify's own audio is DRM protected and no tool can download it."

/** What the folded-away options are set to, in a line: "MP3 · High quality · cover art". */
internal fun optionsSummary(
    format: DownloadFormat?,
    quality: DownloadQuality,
    embedArt: Boolean,
    cutNonMusic: Boolean,
    playlist: Boolean
): String = listOfNotNull(
    format?.label ?: "As sent",
    // Lossless formats keep everything, so the quality setting does nothing there.
    if (format?.lossy == false) "lossless" else "${quality.label} quality",
    "cover art".takeIf { embedArt },
    "non-music cut".takeIf { cutNonMusic },
    "whole playlist".takeIf { playlist }
).joinToString(" · ")

/** Whether a queue row is YouTube refusing the connection, by what the row says. */
internal fun refusedByYouTube(entry: DownloadEntry): Boolean =
    listOfNotNull(entry.status, entry.note).any {
        YouTubeTrouble.refused(it) || it.contains("YouTube refused") || it.contains("YouTube began refusing")
    }

/** Whether the most recent finished failure was YouTube refusing: what brings the sign-in forward. */
internal fun lastFailureRefused(entries: List<DownloadEntry>): Boolean =
    entries.lastOrNull { it.done && (it.failed || it.note != null) }?.let(::refusedByYouTube) == true

/**
 * The YouTube sign-in block shows when YouTube just refused, when a sign-in is
 * already in use (to renew or end it), when the advanced controls are on, or
 * when the user asked for it from a refused download.
 */
internal fun youtubeBlockShown(refused: Boolean, signedIn: Boolean, advanced: Boolean, revealed: Boolean): Boolean =
    refused || signedIn || advanced || revealed

/** The downloaders block shows for the advanced, and always when a tool is missing: a broken install is never hidden. */
internal fun downloadersShown(anyProblem: Boolean, advanced: Boolean): Boolean = anyProblem || advanced

private fun anyToolMissing(tools: ToolStatus): Boolean =
    !tools.ytDlp || !tools.ffmpeg || !tools.spotdl || !tools.jsEngine

/**
 * The download window.
 *
 * Paste a YouTube, SoundCloud, Bandcamp or Spotify link and it lands in the
 * music folder. Spotify is routed through spotdl and called out explicitly
 * rather than quietly redirected: its audio is DRM-protected and cannot be
 * downloaded, so what arrives is the same recording fetched from YouTube, which
 * is usually — but not always — the version you expected.
 */
@Composable
fun DownloadScreen(controller: DesktopController, onChooseFolder: () -> File?) {
    LaunchedEffect(Unit) { controller.refreshTools() }
    var showLog by remember { mutableStateOf(false) }
    var signInRevealed by remember { mutableStateOf(false) }
    val showDownloaders = downloadersShown(anyToolMissing(controller.tools), Advanced.on)
    val showSignIn = youtubeBlockShown(
        refused = lastFailureRefused(controller.downloads),
        signedIn = controller.youtubeSignIn != YouTubeSignIn.Source.OFF || controller.signInKeptAt != null,
        advanced = Advanced.on,
        revealed = signInRevealed
    )

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp)
    ) {
        if (showDownloaders) {
            ToolsPanel(controller)
            Spacer(Modifier.height(14.dp))
        }

        Panel(Modifier.fillMaxWidth()) {
            SectionTitle("Add a link")
            Spacer(Modifier.height(4.dp))
            Hint("Paste a link from YouTube, SoundCloud, Bandcamp, Spotify and more.")
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextInput(
                    value = controller.downloadUrl,
                    onValueChange = { controller.downloadUrl = it },
                    placeholder = "https://…",
                    leading = Icons.Default.Link,
                    modifier = Modifier.weight(1f),
                    onSubmit = { controller.startDownload() }
                )
                Spacer(Modifier.width(10.dp))
                AccentButton(
                    "Download",
                    enabled = controller.tools.ready && controller.downloadUrl.isNotBlank(),
                    icon = Icons.Default.Download
                ) { controller.startDownload() }
            }
            // Spotify is the one link that doesn't do what it looks like, so say so when it is pasted.
            if (controller.downloadUrl.contains("spotify", ignoreCase = true)) {
                Spacer(Modifier.height(8.dp))
                Hint(SPOTIFY_NOTE)
            }

            Spacer(Modifier.height(10.dp))
            // Format, quality and the ticks are folded away, so the page opens on
            // the link and the button. The line under the header says what is
            // chosen, so nothing in there is a surprise.
            Collapsible("Options", note = Notes.DL_OPTIONS) { OptionsControls(controller) }
            Text(
                optionsSummary(
                    controller.downloadFormat, controller.downloadQuality, controller.downloadEmbedArt,
                    controller.downloadCutNonMusic, controller.downloadPlaylist
                ),
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextFaint,
                modifier = Modifier.padding(start = 4.dp)
            )

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.FolderOpen, null, Modifier.size(14.dp), tint = Palette.TextFaint
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    controller.downloadDir,
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(10.dp))
                GhostButton("Change") {
                    onChooseFolder()?.let { controller.chooseDownloadDir(it) }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        if (showSignIn) {
            SignInPanel(controller)
            Spacer(Modifier.height(14.dp))
        }

        if (controller.downloads.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Download,
                title = "Nothing downloaded yet",
                body = "Paste a link above. Files land in the folder shown, and if that\n" +
                    "folder is part of your library they appear there automatically."
            )
        } else {
            SectionTitle("Queue") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showDownloaders) {
                        GhostButton(if (showLog) "Hide log" else "Show log") { showLog = !showLog }
                        Spacer(Modifier.width(8.dp))
                    }
                    GhostButton("Clear finished") { controller.clearFinishedDownloads() }
                }
            }
            Spacer(Modifier.height(10.dp))
            controller.downloads.reversed().forEach { entry ->
                DownloadRow(
                    entry,
                    // A refusal is where the sign-in helps; if its block is tucked away, offer it here.
                    onSignIn = if (showSignIn) null else ({ signInRevealed = true }),
                    onRetry = { controller.retryDownload(entry.id) }
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        if (showDownloaders && showLog && controller.downloadLog.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            LogPanel(controller.downloadLog)
        }

        Spacer(Modifier.height(24.dp))
    }
}

/** Format, quality, cover art, what to cut, and the Spotify caveat: the page's "Options". */
@Composable
private fun OptionsControls(controller: DesktopController) {
    Spacer(Modifier.height(8.dp))
    Hint(SPOTIFY_NOTE)
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Format", style = MaterialTheme.typography.bodyMedium, color = Palette.Text)
            Spacer(Modifier.height(6.dp))
            SegmentedRow(
                options = listOf<DownloadFormat?>(null) + DownloadFormat.entries,
                selected = controller.downloadFormat,
                label = { it?.label ?: "As sent" },
                onSelect = { controller.downloadFormat = it }
            )
            Spacer(Modifier.height(4.dp))
            Text(
                controller.downloadFormat?.note ?: "whatever the site served, usually Opus, not converted",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextFaint
            )
            Spacer(Modifier.height(10.dp))
            CheckRow(
                label = "Embed cover art",
                checked = controller.downloadEmbedArt,
                note = "Cut square from the middle of a video's thumbnail"
            ) { controller.downloadEmbedArt = it }
            CheckRow(
                label = "Cut the non-music parts of music videos",
                checked = controller.downloadCutNonMusic,
                note = "Spoken intros and skits that SponsorBlock's listeners have marked"
            ) { controller.downloadCutNonMusic = it }
        }
        Column(Modifier.weight(1f)) {
            Text(
                "Quality",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Text
            )
            Spacer(Modifier.height(6.dp))
            SegmentedRow(
                options = DownloadQuality.entries,
                selected = controller.downloadQuality,
                label = { it.label },
                onSelect = { controller.downloadQuality = it }
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (controller.downloadFormat?.lossy == false) "Doesn't apply to ${controller.downloadFormat?.label}, which keeps everything"
                else controller.downloadQuality.description,
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextFaint
            )
            Spacer(Modifier.height(12.dp))
            CheckRow(
                label = "Download the whole playlist",
                checked = controller.downloadPlaylist,
                note = "Off means just the one track a playlist link points at"
            ) { controller.downloadPlaylist = it }
        }
    }
}

@Composable
private fun ToolsPanel(controller: DesktopController) {
    val tools = controller.tools
    Panel(Modifier.fillMaxWidth()) {
        SectionTitle("Downloaders") {
            GhostButton("Update yt-dlp") { controller.updateYtDlp() }
        }
        Spacer(Modifier.height(4.dp))
        Hint(
            "All four ship with Lucense — nothing to install. yt-dlp is the only " +
                "one worth updating by hand: extractors break whenever a site changes " +
                "its player, usually faster than Lucense ships."
        )
        Spacer(Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ToolChip("yt-dlp", tools.ytDlp, tools.ytDlpVersion ?: "YouTube, SoundCloud, 1000+")
            ToolChip("ffmpeg", tools.ffmpeg, "MP3 conversion, video audio")
            ToolChip("spotdl", tools.spotdl, "Spotify links")
            ToolChip("QuickJS", tools.jsEngine, "YouTube's challenges")
        }

        if (anyToolMissing(tools)) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Something is missing from this installation — reinstalling will " +
                    "restore it.",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextDim
            )
        }

        if (controller.toolProgress != null || controller.toolNote != null) {
            Spacer(Modifier.height(12.dp))
            controller.toolNote?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.Accent)
                Spacer(Modifier.height(6.dp))
            }
            if (controller.toolProgress != null) ThinProgress(controller.toolProgress)
        }
    }
}

@Composable
private fun ToolChip(name: String, installed: Boolean, note: String) {
    Row(
        Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(Palette.Well)
            .border(1.dp, if (installed) Palette.AccentSoft else Palette.Line,
                RoundedCornerShape(7.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (installed) Palette.Accent else Palette.TextFaint)
        )
        Spacer(Modifier.width(9.dp))
        Column {
            Text(name, style = MaterialTheme.typography.bodySmall, color = Palette.Text)
            Text(note, style = MaterialTheme.typography.labelSmall, color = Palette.TextFaint)
        }
    }
}

@Composable
private fun DownloadRow(entry: DownloadEntry, onSignIn: (() -> Unit)?, onRetry: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .card(8.dp)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val icon = when {
                entry.failed -> Icons.Default.ErrorOutline
                entry.done -> Icons.Default.CheckCircle
                else -> Icons.Default.Download
            }
            Icon(
                icon,
                null,
                Modifier.size(15.dp),
                tint = when {
                    entry.failed -> Palette.TextDim
                    entry.done -> Palette.Accent
                    else -> Palette.TextDim
                }
            )
            Spacer(Modifier.width(10.dp))
            Text(
                entry.display,
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(10.dp))
            // Its own retry, for an item that failed or a playlist that missed some.
            if (entry.done && (entry.failed || entry.missed > 0)) {
                GhostButton(
                    if (entry.failed) "Retry" else "Retry the ${entry.missed} missed",
                    icon = Icons.Default.Refresh,
                    onClick = onRetry
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(
                when {
                    // The reason is spelled out underneath; here it would squeeze the name out.
                    entry.failed -> "Failed"
                    // A playlist counts songs, which says more than a percentage.
                    !entry.done && entry.percent > 0f && !entry.status.contains(" of ") ->
                        "${(entry.percent * 100).toInt()}%"
                    else -> entry.status
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (entry.failed) Palette.TextDim else Palette.Accent
            )
        }

        if (!entry.done) {
            Spacer(Modifier.height(9.dp))
            ThinProgress(entry.percent.takeIf { it > 0f })
        }

        entry.note?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.labelSmall, color = Palette.TextFaint)
        }

        if (entry.files.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            // A playlist saves a hundred; the last few show it is moving.
            entry.files.takeLast(FILES_SHOWN).forEach { file ->
                Text(
                    file.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.TextFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (entry.files.size > FILES_SHOWN) {
                Text(
                    "and ${entry.files.size - FILES_SHOWN} more",
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.TextFaint
                )
            }
        }

        if (entry.failed) {
            Spacer(Modifier.height(6.dp))
            Text(entry.status, style = MaterialTheme.typography.labelSmall, color = Palette.TextDim)
        }

        if (onSignIn != null && refusedByYouTube(entry)) {
            Spacer(Modifier.height(8.dp))
            Row { GhostButton("Sign in to YouTube…", icon = Icons.Default.Login, note = Notes.DL_SIGN_IN, onClick = onSignIn) }
        }
    }
}

/** How many saved files a queue row names. */
private const val FILES_SHOWN = 4

/** Raw yt-dlp output, for when a download fails and the reason matters. */
@Composable
private fun LogPanel(lines: List<String>) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.Base)
            .border(1.dp, Palette.Line, RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        LazyColumn(Modifier.fillMaxWidth()) {
            items(lines) { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = Palette.TextDim,
                    maxLines = 1,
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                )
            }
        }
    }
}

/**
 * Downloads as the user's YouTube account, for when YouTube refuses the VPN's
 * address: a sign-in of Lucense's own, a browser's copied and renewed now and
 * then, or a cookies.txt file.
 */
@Composable
private fun SignInPanel(controller: DesktopController) {
    val chosen = controller.youtubeSignIn
    Panel(Modifier.fillMaxWidth()) {
        SectionTitle("YouTube account (optional)")
        Spacer(Modifier.height(4.dp))
        Hint(
            "If YouTube refuses downloads with \"confirm you're not a bot\", usually because of the VPN's " +
                "address, yt-dlp can download as your YouTube account instead. Your YouTube suggestions in " +
                "Discover come from it too. Nothing leaves this PC except to YouTube. YouTube can flag " +
                "accounts that download a lot, so a spare account is safer."
        )
        Spacer(Modifier.height(10.dp))
        SegmentedRow(
            // Opera GX is made for Windows only.
            options = YouTubeSignIn.Source.entries.filter { Platform.isWindows || it != YouTubeSignIn.Source.OPERA_GX },
            selected = chosen,
            label = { it.label },
            onSelect = { controller.youtubeSignIn = it }
        )
        Spacer(Modifier.height(8.dp))
        when {
            chosen == YouTubeSignIn.Source.OWN -> Hint(
                "Recommended. Opens your default browser in a window of its own, apart from your usual one: " +
                    "sign in to YouTube there once, close the window, and Lucense keeps that sign-in. Nothing you " +
                    "browse with touches it, so YouTube doesn't cycle it out the way it does a browser's - it lasts " +
                    "until you sign out."
            )
            chosen.copied -> Hint(
                "Lucense copies ${chosen.label}'s sign-in and keeps the copy, renewing it every few days and " +
                    "whenever YouTube stops taking it, so ${chosen.label} doesn't have to be closed each time. " +
                    "Chrome-family browsers lock their cookies while open: close it when a copy is made."
            )
            else -> Unit
        }
        if (chosen == YouTubeSignIn.Source.FILE) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    controller.youtubeCookieFile.ifBlank { "No file chosen" },
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(10.dp))
                GhostButton("Choose file…") { chooseCookieFile()?.let { controller.youtubeCookieFile = it.absolutePath } }
            }
        }
        if (chosen != YouTubeSignIn.Source.OFF) {
            Spacer(Modifier.height(10.dp))
            val keptAt = controller.signInKeptAt
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    controller.signingIn -> {
                        AccentButton("Done, I've signed in", icon = Icons.Default.Check) { controller.doneSigningIn() }
                        Spacer(Modifier.width(8.dp))
                        GhostButton("Stop", icon = Icons.Default.Close) { controller.stopSigningIn() }
                    }
                    chosen == YouTubeSignIn.Source.OWN -> {
                        val again = keptAt != null
                        if (again) {
                            GhostButton(if (controller.checkingSignIn) "Checking…" else "Check sign-in", enabled = !controller.checkingSignIn) {
                                controller.checkYouTubeSignIn()
                            }
                            Spacer(Modifier.width(8.dp))
                            GhostButton("Sign in again", enabled = !controller.checkingSignIn) { controller.signInHere() }
                            Spacer(Modifier.width(8.dp))
                            GhostButton("Sign out", enabled = !controller.checkingSignIn) { controller.forgetYouTubeSignIn() }
                        } else {
                            AccentButton("Sign in to YouTube", icon = Icons.Default.Login, enabled = !controller.checkingSignIn) {
                                controller.signInHere()
                            }
                        }
                    }
                    else -> {
                        GhostButton(
                            when {
                                controller.checkingSignIn -> "Checking…"
                                chosen.copied && keptAt != null -> "Renew and check"
                                chosen.copied -> "Copy sign-in"
                                else -> "Check sign-in"
                            },
                            enabled = !controller.checkingSignIn
                        ) { controller.checkYouTubeSignIn() }
                        if (chosen.copied && keptAt != null) {
                            Spacer(Modifier.width(8.dp))
                            GhostButton("Forget the copy", enabled = !controller.checkingSignIn) { controller.forgetYouTubeSignIn() }
                        }
                    }
                }
            }
            val said = controller.signInNote ?: keptAt?.let { at ->
                val day = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                val words = java.time.format.DateTimeFormatter.ofPattern("d MMMM").format(day)
                if (chosen == YouTubeSignIn.Source.OWN) "Signed in here since $words." else "Copy made or renewed on $words."
            }
            if (said != null) {
                Spacer(Modifier.height(8.dp))
                Text(said, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
            }
        }
    }
}

/** Windows' own open dialog, for a cookies.txt file. */
private fun chooseCookieFile(): File? {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Choose a cookies.txt file", java.awt.FileDialog.LOAD)
    dialog.file = "*.txt"
    dialog.isVisible = true
    val name = dialog.file ?: return null
    return File(dialog.directory, name)
}
