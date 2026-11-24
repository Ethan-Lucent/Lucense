package com.exo.musicplayer.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.onClick
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.exo.musicplayer.desktop.data.CollectionKind
import com.exo.musicplayer.desktop.data.DesktopController
import com.exo.musicplayer.desktop.data.RepeatMode
import com.exo.musicplayer.desktop.library.DesktopTrack
import com.exo.musicplayer.util.asDuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.border
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay

@Composable
internal fun TransportBar(
    controller: DesktopController,
    track: DesktopTrack?,
    isPlaying: Boolean,
    durationMs: Long,
    onOpenGroup: (CollectionKind, String) -> Unit,
    loopStartMs: Long? = null,
    loopEndMs: Long? = null
) {
    val panel = controller.sidePanel
    // A narrow window's bar: a shorter seek line and title, and the two least-used toggles go.
    val width = LocalWindowWidth.current
    val narrow = width < 1000.dp
    val tight = width < 900.dp
    Column {
        if (!Glass.on) Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Line))
        Row(
            Modifier
                .fillMaxWidth()
                .height(76.dp)
                .then(
                    // A floating capsule of glass, as the controls are on Apple's.
                    if (Glass.on) {
                        Modifier.glassPane(LocalGlass.current, RoundedCornerShape(28.dp), Palette.Raised, 0.42f)
                    } else {
                        Modifier.background(Palette.Raised)
                    }
                )
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // The song playing can be dragged out by its cover or its name.
            Artwork(track, 46.dp, modifier = Modifier.dragOutFiles { listOfNotNull(track?.file) })
            Spacer(Modifier.width(13.dp))
            Column(Modifier.width(if (narrow) 140.dp else 200.dp).dragOutFiles { listOfNotNull(track?.file) }) {
                Text(
                    track?.title ?: "Nothing playing",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // Each artist named goes to their page - when the song is one of the
                // library's, so there is a page to go to.
                val inLibrary = remember(track, controller.tracks) {
                    track != null && controller.tracks.any { it.file == track.file }
                }
                Text(
                    when {
                        track == null -> AnnotatedString("—")
                        inLibrary -> artistLinks(track) { onOpenGroup(CollectionKind.ARTISTS, it) }
                        else -> AnnotatedString(track.displayArtist)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (track != null) {
                Spacer(Modifier.width(6.dp))
                val favourite = track.file.absolutePath in controller.favourites
                IconButton(onClick = { controller.toggleFavourite(track) }) {
                    Icon(
                        if (favourite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        "Favourite",
                        Modifier.size(16.dp),
                        tint = if (favourite) Palette.Accent else Palette.TextDim
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { controller.shuffle = !controller.shuffle }) {
                        Icon(
                            Icons.Default.Shuffle,
                            if (controller.shuffle) "Shuffle on" else "Shuffle off",
                            Modifier.size(17.dp),
                            tint = if (controller.shuffle) Palette.Accent else Palette.TextDim
                        )
                    }
                    Spacer(Modifier.width(2.dp))
                    IconButton(onClick = { controller.previous() }) {
                        Icon(
                            Icons.Default.SkipPrevious, "Previous",
                            Modifier.size(20.dp), tint = Palette.TextDim
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Palette.Accent)
                            .clickable { controller.togglePlay() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            if (isPlaying) "Pause" else "Play",
                            Modifier.size(18.dp),
                            tint = Palette.OnAccent
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = { controller.next() }) {
                        Icon(
                            Icons.Default.SkipNext, "Next",
                            Modifier.size(20.dp), tint = Palette.TextDim
                        )
                    }
                    Spacer(Modifier.width(2.dp))
                    IconButton(onClick = { controller.cycleRepeat() }) {
                        Icon(
                            if (controller.repeat == RepeatMode.ONE) {
                                Icons.Default.RepeatOne
                            } else {
                                Icons.Default.Repeat
                            },
                            controller.repeat.label,
                            Modifier.size(17.dp),
                            tint = if (controller.repeat == RepeatMode.OFF) Palette.TextDim else Palette.Accent
                        )
                    }
                    AbLoopButton(
                        loopStartMs = loopStartMs,
                        loopEndMs = loopEndMs,
                        enabled = track != null,
                        onClick = { controller.cycleAbLoop() }
                    )
                }
                Spacer(Modifier.height(4.dp))
                SeekRow(controller, track, durationMs, narrow, loopStartMs, loopEndMs)
            }

            Spacer(Modifier.weight(1f))

            var sleepMenu by remember { mutableStateOf(false) }
            // Counted down on its own: minutes left are the clock's, and nothing
            // else redraws the bar for the clock.
            val sleepLeft by produceState(controller.sleepMinutesLeft, controller.sleepEndsAt) {
                while (true) {
                    value = controller.sleepMinutesLeft
                    delay(15_000)
                }
            }
            if (!tight) Box {
                BarToggle(
                    icon = Icons.Default.Bedtime,
                    label = sleepLeft?.let { "$it min" }
                        ?: if (controller.stopAfterTrack) "End of song" else "Sleep",
                    active = controller.sleepEndsAt != null || controller.stopAfterTrack,
                    highlight = false
                ) { sleepMenu = true }
                DropdownMenu(expanded = sleepMenu, onDismissRequest = { sleepMenu = false }) {
                    listOf(15, 30, 45, 60, 90).forEach { minutes ->
                        DropdownMenuItem(
                            text = { Text("$minutes minutes", color = Palette.Text) },
                            onClick = {
                                controller.setSleepTimer(minutes)
                                sleepMenu = false
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("When this song ends", color = Palette.Text) },
                        onClick = {
                            controller.sleepAfterTrack()
                            sleepMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Off", color = Palette.TextDim) },
                        onClick = {
                            controller.setSleepTimer(null)
                            sleepMenu = false
                        }
                    )
                }
            }
            if (!tight) BarToggle(
                icon = Icons.Default.PictureInPictureAlt,
                label = "Mini",
                active = controller.miniPlayer,
                highlight = false
            ) { controller.miniPlayer = !controller.miniPlayer }
            BarToggle(
                icon = Icons.AutoMirrored.Filled.QueueMusic,
                label = "Queue",
                active = panel == SidePanelKind.QUEUE,
                highlight = false
            ) { controller.togglePanel(SidePanelKind.QUEUE) }
            BarToggle(
                icon = Icons.Default.Lyrics,
                label = "Lyrics",
                active = panel == SidePanelKind.LYRICS,
                highlight = controller.lyricsPlain != null
            ) { controller.togglePanel(SidePanelKind.LYRICS) }
            BarToggle(
                icon = Icons.Default.GraphicEq,
                label = "Effects",
                active = panel == SidePanelKind.EFFECTS,
                highlight = !controller.fx.isDefault
            ) { controller.togglePanel(SidePanelKind.EFFECTS) }
            BarToggle(
                icon = Icons.Default.Speaker,
                label = "Output",
                active = panel == SidePanelKind.OUTPUT,
                highlight = controller.selectedOutputs.size > 1
            ) { controller.togglePanel(SidePanelKind.OUTPUT) }

            Spacer(Modifier.width(6.dp))
            // Ducking happens while another window has focus, so there has to
            // be something to look at afterwards that says it worked.
            // The minus sits beside the speaker rather than replacing it. The
            // speaker is busy saying how loud the music is, and one glyph
            // cannot carry that and "ducked" at the same time without losing
            // one of them. A fixed width keeps the slider still either way.
            Text(
                if (controller.ducked) "−" else "",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.Accent,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(9.dp)
            )
            // Clicking the speaker mutes, and clicking it again puts back the
            // level it was at - which is what a speaker next to a volume line
            // is expected to do.
            Box(
                Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .clickable { controller.toggleMute() }
                    .pointerHoverIcon(PointerIcon.Hand),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    volumeIcon(controller.volume),
                    when {
                        controller.ducked -> "Ducked for gaming"
                        controller.volume <= 0.001f -> "Unmute"
                        else -> "Mute"
                    },
                    Modifier.size(16.dp),
                    tint = if (controller.ducked) Palette.Accent else Palette.TextDim
                )
            }
            // The same line as the seek bar, so the two read as one family
            // rather than one of them being a different kind of control. It
            // follows the drag live, since setting a volume costs nothing, and
            // takes the mouse wheel a notch at a time.
            LineSlider(
                value = controller.volume,
                onSet = { controller.volume = it },
                live = true,
                wheelStep = 0.05f,
                restColor = Palette.TextDim,
                activeColor = Palette.Accent,
                modifier = Modifier.width(if (narrow) 72.dp else 96.dp)
            )
        }
    }
}

/**
 * Which speaker the slider is currently worth.
 *
 * Four steps rather than two, so the icon is telling you something across the
 * whole travel instead of only at the very bottom.
 */
private fun volumeIcon(level: Float): ImageVector = when {
    level <= 0.001f -> Icons.AutoMirrored.Filled.VolumeOff
    level < 0.34f -> Icons.AutoMirrored.Filled.VolumeMute
    level < 0.67f -> Icons.AutoMirrored.Filled.VolumeDown
    else -> Icons.AutoMirrored.Filled.VolumeUp
}

/** Icon toggle that also shows accent colour when the feature is doing something. */
@Composable
private fun BarToggle(
    icon: ImageVector,
    label: String,
    active: Boolean,
    highlight: Boolean,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(
                when {
                    active -> Palette.Selected
                    hovered -> Palette.Hover
                    else -> Color.Transparent
                }
            )
            .hoverable(interaction)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = label,
            modifier = Modifier.size(17.dp),
            tint = if (active || highlight) Palette.Accent else Palette.TextDim
        )
    }
}

/**
 * The A-B loop button, beside repeat: "A-B" dim while off, "A" lit once A is
 * set, "A-B" lit while going round. The L key does the same.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AbLoopButton(loopStartMs: Long?, loopEndMs: Long?, enabled: Boolean, onClick: () -> Unit) {
    val looping = loopStartMs != null && loopEndMs != null
    val armed = loopStartMs != null && loopEndMs == null
    val tip = when {
        looping -> "Looping ${loopStartMs!!.asDuration()} to ${loopEndMs!!.asDuration()} - click to stop (L)"
        armed -> "A at ${loopStartMs!!.asDuration()} - click again for B (L)"
        else -> "Loop part of the song: A here, then B (L)"
    }
    TooltipArea(
        tooltip = {
            Text(
                tip,
                style = MaterialTheme.typography.labelMedium,
                color = Palette.Text,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Palette.Raised)
                    .border(1.dp, Palette.Line, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    ) {
        IconButton(onClick = onClick, enabled = enabled) {
            Text(
                if (armed) "A" else "A-B",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = if (looping || armed) Palette.Accent else Palette.TextDim
            )
        }
    }
}

/** A and B on the seek line, and the stretch between them while it goes round. */
@Composable
private fun LoopMarks(loopStartMs: Long?, loopEndMs: Long?, durationMs: Long, modifier: Modifier) {
    if (loopStartMs == null || durationMs <= 0L) return
    val accent = Palette.Accent
    Canvas(modifier) {
        val a = (loopStartMs.toFloat() / durationMs).coerceIn(0f, 1f) * size.width
        val b = loopEndMs?.let { (it.toFloat() / durationMs).coerceIn(0f, 1f) * size.width }
        val middle = size.height / 2
        val half = 6.dp.toPx()
        if (b != null) {
            drawRect(accent.copy(alpha = 0.22f), topLeft = Offset(a, middle - half / 2), size = Size(b - a, half))
            drawLine(accent, Offset(b, middle - half), Offset(b, middle + half), strokeWidth = 2.dp.toPx())
        }
        drawLine(accent, Offset(a, middle - half), Offset(a, middle + half), strokeWidth = 2.dp.toPx())
    }
}

/**
 * The seek line and its two clocks - the one part of the bar that follows the
 * position, which changes four times a second. It reads the position itself, so
 * only this recomposes as a song plays, not the bar around it.
 */
@Composable
private fun SeekRow(
    controller: DesktopController,
    track: DesktopTrack?,
    durationMs: Long,
    narrow: Boolean,
    loopStartMs: Long?,
    loopEndMs: Long?
) {
    val positionMs by controller.engine.collectPositionMs()
    // Where the drag currently is, or null when nobody is dragging.
    // The clock on the left follows this while it is set, so the
    // number and the bar say the same thing mid-drag.
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            (scrubbing?.let { (it * durationMs).toLong() } ?: positionMs).asDuration(),
            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
            color = if (scrubbing != null) Palette.Accent else Palette.TextFaint,
            textAlign = TextAlign.End,
            modifier = Modifier.width(38.dp)
        )
        Spacer(Modifier.width(4.dp))
        Box(Modifier.width(if (narrow) 200.dp else 330.dp)) {
            LineSlider(
                value = if (durationMs > 0) {
                    (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                } else {
                    0f
                },
                enabled = track != null,
                onPreview = { scrubbing = it },
                onSet = { controller.seekFraction(it) },
                modifier = Modifier.fillMaxWidth()
            )
            LoopMarks(loopStartMs, loopEndMs, durationMs, Modifier.matchParentSize())
        }
        Spacer(Modifier.width(4.dp))
        Text(
            durationMs.asDuration(),
            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
            color = Palette.TextFaint,
            textAlign = TextAlign.Start,
            modifier = Modifier.width(38.dp)
        )
    }
}
