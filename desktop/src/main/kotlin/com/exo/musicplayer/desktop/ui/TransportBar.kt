package com.exo.musicplayer.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.onClick
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
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
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
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
import androidx.compose.foundation.border
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import androidx.compose.animation.Crossfade

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
    // A narrow window's bar: a shorter seek line and title.
    val width = LocalWindowWidth.current
    val narrow = width < 1000.dp
    val showAb = barShows(BarControl.AB_LOOP, Advanced.on, loopStartMs != null)
    // Shown when asked for, or when the sound is not going to the one default
    // device - several at once, or another than the first - so it is never a mystery.
    val outputInUse = controller.selectedOutputs.size > 1 ||
        controller.selectedOutputs.firstOrNull() != controller.outputs.firstOrNull()?.name
    Column {
        if (!Glass.on) Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Line))
        BarSlots(
            Modifier
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
            centreWidth = centreWidth(narrow, showAb),
            left = {
                val inLibrary = remember(track, controller.tracks) {
                    track != null && controller.tracks.any { it.file == track.file }
                }
                BarNowPlaying(
                    narrow = narrow,
                    // The song playing can be dragged out by its cover or its name.
                    // A click on the cover opens the lyrics fullscreen; a drag still takes the file out.
                    cover = {
                        Noted(null, if (track != null) Notes.COVER else null) {
                            Artwork(
                                track,
                                46.dp,
                                modifier = Modifier
                                    .then(
                                        if (track != null) {
                                            Modifier
                                                .clip(RoundedCornerShape(5.dp))
                                                .clickable { controller.lyricsFullscreen = true }
                                                .pointerHoverIcon(PointerIcon.Hand)
                                        } else {
                                            Modifier
                                        }
                                    )
                                    .dragOutFiles { listOfNotNull(track?.file) }
                            )
                        }
                    },
                    title = track?.title ?: "Nothing playing",
                    // Each artist named goes to their page - when the song is one of the
                    // library's, so there is a page to go to.
                    artist = when {
                        track == null -> AnnotatedString("—")
                        inLibrary -> artistLinks(track) { onOpenGroup(CollectionKind.ARTISTS, it) }
                        else -> AnnotatedString(track.displayArtist)
                    },
                    titleModifier = Modifier.dragOutFiles { listOfNotNull(track?.file) },
                    favourite = track?.let { it.file.absolutePath in controller.favourites },
                    onFavourite = { track?.let { controller.toggleFavourite(it) } }
                )
            },
            centre = {
                BarCentre(
                    centreWidth(narrow, showAb),
                    buttons = {
                        TransportButtons(
                            shuffle = controller.shuffle,
                            repeat = controller.repeat,
                            isPlaying = isPlaying,
                            showAb = showAb,
                            loopStartMs = loopStartMs,
                            loopEndMs = loopEndMs,
                            abEnabled = track != null,
                            onShuffle = { controller.shuffle = !controller.shuffle },
                            onPrevious = { controller.previous() },
                            onPlay = { controller.togglePlay() },
                            onNext = { controller.next() },
                            onRepeat = { controller.cycleRepeat() },
                            onAb = { controller.cycleAbLoop() }
                        )
                    },
                    seek = { SeekRow(controller, track, durationMs, loopStartMs, loopEndMs) }
                )
            },
            right = {
                BarRight(
                    narrow = narrow,
                    panel = panel,
                    lyricsHighlight = controller.lyricsPlain != null,
                    effectsHighlight = !controller.fx.isDefault,
                    showOutput = barShows(BarControl.OUTPUT, Advanced.on, outputInUse || panel == SidePanelKind.OUTPUT),
                    outputHighlight = controller.selectedOutputs.size > 1,
                    ducked = controller.ducked,
                    volume = controller.volume,
                    onPanel = { controller.togglePanel(it) },
                    onVolume = { controller.volume = it },
                    onMute = { controller.toggleMute() },
                    more = { SleepAndMore(controller) }
                )
            }
        )
    }
}

/** The middle slot's width: as wide as the seek line wants, and never narrower than the buttons need. */
internal fun centreWidth(narrow: Boolean, showAb: Boolean): Dp =
    maxOf(if (narrow) 284.dp else 380.dp, BUTTONS_NATURAL + if (showAb) AB_BUTTON_WIDTH * 2 else 0.dp)

/** The A-B button's slot: an icon button, 48 dp wide like the others. [TransportButtons] mirrors it on the left. */
private val AB_BUTTON_WIDTH = 48.dp

/** shuffle, previous, play, next, repeat side by side with no spare room: 4 round buttons of 48 and the 34 play disc. */
private val BUTTONS_NATURAL = 48.dp * 4 + 34.dp

/**
 * The bar as three slots. The middle one takes its own width first and the two
 * outer ones share what is left equally, so the middle is exactly in the middle
 * of the bar whatever the sides hold. (Two spacers around it only split what the
 * sides left over, which centred it between them, not in the window.) A side
 * too full for its half gives way inside its slot - [BarNowPlaying]'s title
 * shortens, the volume line shrinks - and is clipped as a last resort, so the
 * middle never moves or gets overlapped.
 */
@Composable
internal fun BarSlots(
    modifier: Modifier,
    centreWidth: Dp,
    left: @Composable RowScope.() -> Unit,
    centre: @Composable () -> Unit,
    right: @Composable RowScope.() -> Unit
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).fillMaxHeight().clipToBounds().testTag("bar-left"),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
            content = left
        )
        // A little air each side so a full slot never touches the buttons; equal both sides.
        Box(Modifier.padding(horizontal = 12.dp).width(centreWidth).testTag("bar-centre")) { centre() }
        Row(
            Modifier.weight(1f).fillMaxHeight().clipToBounds().testTag("bar-right"),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
            content = right
        )
    }
}

/** Cover, title and artist, and the heart: the left slot. The title gives way first when the slot is short. */
@Composable
internal fun RowScope.BarNowPlaying(
    narrow: Boolean,
    cover: @Composable () -> Unit,
    title: String,
    artist: AnnotatedString,
    titleModifier: Modifier,
    /** Null when nothing is playing (no heart); otherwise whether it is a favourite. */
    favourite: Boolean?,
    onFavourite: () -> Unit
) {
    cover()
    Spacer(Modifier.width(13.dp))
    // 200 (140 narrow) when there is room, shrinking - not pushing the middle - when there is
    // not. The weight alone would hand it all the slot's spare room, so the width is set
    // on the column inside and the box around it only ever offers up to what is left.
    Box(Modifier.weight(1f, fill = false)) {
        Column(Modifier.width(if (narrow) 140.dp else 200.dp).then(titleModifier)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                artist,
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    if (favourite != null) {
        Spacer(Modifier.width(6.dp))
        Noted("Favourite", Notes.FAVOURITE, iconOnly = true) {
            IconButton(onClick = onFavourite) {
                Icon(
                    if (favourite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    "Favourite",
                    Modifier.size(16.dp),
                    tint = if (favourite) Palette.Accent else Palette.TextDim
                )
            }
        }
    }
}

/** The middle slot: the buttons spread over the full width, the seek line under them, the same width. */
@Composable
internal fun BarCentre(width: Dp, buttons: @Composable RowScope.() -> Unit, seek: @Composable () -> Unit) {
    Column(Modifier.width(width), horizontalAlignment = Alignment.CenterHorizontally) {
        // Spread evenly so the outer buttons sit over the ends of the seek line.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
            content = buttons
        )
        Spacer(Modifier.height(4.dp))
        seek()
    }
}

@Composable
internal fun RowScope.TransportButtons(
    shuffle: Boolean,
    repeat: RepeatMode,
    isPlaying: Boolean,
    showAb: Boolean,
    loopStartMs: Long?,
    loopEndMs: Long?,
    abEnabled: Boolean,
    onShuffle: () -> Unit,
    onPrevious: () -> Unit,
    onPlay: () -> Unit,
    onNext: () -> Unit,
    onRepeat: () -> Unit,
    onAb: () -> Unit
) {
    // The buttons are spread evenly, so A-B on the right alone would drag play off the middle
    // of the seek line (it sat about 37 px left of it). An empty slot of the same width on the
    // left makes the row symmetric - spacer and A-B, shuffle and repeat, previous and next
    // around the play disc - and the disc stays exactly under the middle of the line.
    if (showAb) Spacer(Modifier.width(AB_BUTTON_WIDTH))
    Noted("Shuffle", Notes.SHUFFLE, iconOnly = true) {
        IconButton(onClick = onShuffle) {
            Icon(
                Icons.Default.Shuffle,
                if (shuffle) "Shuffle on" else "Shuffle off",
                Modifier.size(17.dp),
                tint = if (shuffle) Palette.Accent else Palette.TextDim
            )
        }
    }
    IconButton(onClick = onPrevious) {
        Icon(Icons.Default.SkipPrevious, "Previous", Modifier.size(20.dp), tint = Palette.TextDim)
    }
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Palette.Accent)
            .clickable(onClick = onPlay),
        contentAlignment = Alignment.Center
    ) {
        Crossfade(isPlaying, animationSpec = Motion.quick(), label = "play") { playing ->
            Icon(
                if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                if (playing) "Pause" else "Play",
                Modifier.size(18.dp),
                tint = Palette.OnAccent
            )
        }
    }
    IconButton(onClick = onNext) {
        Icon(Icons.Default.SkipNext, "Next", Modifier.size(20.dp), tint = Palette.TextDim)
    }
    Noted("Repeat", Notes.REPEAT, iconOnly = true) {
        IconButton(onClick = onRepeat) {
            Icon(
                if (repeat == RepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                repeat.label,
                Modifier.size(17.dp),
                tint = if (repeat == RepeatMode.OFF) Palette.TextDim else Palette.Accent
            )
        }
    }
    // Out of sight until asked for or in use; the L key works either way.
    if (showAb) AbLoopButton(loopStartMs = loopStartMs, loopEndMs = loopEndMs, enabled = abEnabled, onClick = onAb)
}

/** The right slot: the panel toggles, the volume, More. Its volume line is what shrinks when the slot is short. */
@Composable
internal fun RowScope.BarRight(
    narrow: Boolean,
    panel: SidePanelKind?,
    lyricsHighlight: Boolean,
    effectsHighlight: Boolean,
    showOutput: Boolean,
    outputHighlight: Boolean,
    ducked: Boolean,
    volume: Float,
    onPanel: (SidePanelKind) -> Unit,
    onVolume: (Float) -> Unit,
    onMute: () -> Unit,
    more: @Composable () -> Unit
) {
    BarToggle(
        icon = Icons.AutoMirrored.Filled.QueueMusic,
        label = "Queue",
        active = panel == SidePanelKind.QUEUE,
        highlight = false,
        note = Notes.QUEUE
    ) { onPanel(SidePanelKind.QUEUE) }
    BarToggle(
        icon = Icons.Default.Lyrics,
        label = "Lyrics",
        active = panel == SidePanelKind.LYRICS,
        highlight = lyricsHighlight,
        note = Notes.LYRICS
    ) { onPanel(SidePanelKind.LYRICS) }
    BarToggle(
        icon = Icons.Default.GraphicEq,
        label = "Effects",
        active = panel == SidePanelKind.EFFECTS,
        highlight = effectsHighlight,
        note = Notes.EFFECTS
    ) { onPanel(SidePanelKind.EFFECTS) }
    if (showOutput) {
        BarToggle(
            icon = Icons.Default.Speaker,
            label = "Output",
            active = panel == SidePanelKind.OUTPUT,
            highlight = outputHighlight,
            note = Notes.OUTPUT
        ) { onPanel(SidePanelKind.OUTPUT) }
    }

    Spacer(Modifier.width(6.dp))
    // Ducking happens while another window has focus, so there has to
    // be something to look at afterwards that says it worked.
    // The minus sits beside the speaker rather than replacing it. The
    // speaker is busy saying how loud the music is, and one glyph
    // cannot carry that and "ducked" at the same time without losing
    // one of them. A fixed width keeps the slider still either way.
    Text(
        if (ducked) "−" else "",
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
            .clickable(onClick = onMute)
            .pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            volumeIcon(volume),
            when {
                ducked -> "Ducked for gaming"
                volume <= 0.001f -> "Unmute"
                else -> "Mute"
            },
            Modifier.size(16.dp),
            tint = if (ducked) Palette.Accent else Palette.TextDim
        )
    }
    // The same line as the seek bar, so the two read as one family
    // rather than one of them being a different kind of control. It
    // follows the drag live, since setting a volume costs nothing, and
    // takes the mouse wheel a notch at a time.
    LineSlider(
        value = volume,
        onSet = onVolume,
        live = true,
        wheelStep = 0.05f,
        restColor = Palette.TextDim,
        activeColor = Palette.Accent,
        // Its full width when there is room; a short slot shrinks it rather than the buttons.
        modifier = Modifier.weight(1f, fill = false).width(if (narrow) 72.dp else 96.dp)
    )
    Spacer(Modifier.width(6.dp))
    more()
}

/** The bar's controls that are not there for everyone; see [barShows]. */
internal enum class BarControl(val everyday: Boolean) {
    QUEUE(true), LYRICS(true), EFFECTS(true),
    AB_LOOP(false), OUTPUT(false)
}

/**
 * Whether a control is on the bar. The everyday ones always are. The rest
 * show once "Show advanced options" is on, and also whenever they are doing
 * something ([active]: a loop set, the sound going to another device) -
 * hiding is only visual, and what is in use must never be out of sight.
 */
internal fun barShows(control: BarControl, advanced: Boolean, active: Boolean): Boolean =
    control.everyday || advanced || active

/** "Sleep timer: 15 min" ... "when this song ends", "off": the rows that set the sleep timer. */
private fun sleepRows(controller: DesktopController): List<MoreItem> =
    listOf(15, 30, 45, 60, 90).map { minutes ->
        MoreItem("Sleep timer: $minutes min", Notes.MORE_SLEEP) { controller.setSleepTimer(minutes) }
    } + listOf(
        MoreItem("Sleep timer: when this song ends", Notes.MORE_SLEEP_SONG) { controller.sleepAfterTrack() },
        MoreItem("Sleep timer: off", Notes.MORE_SLEEP_OFF) { controller.setSleepTimer(null) }
    )

/**
 * The right end of the bar: a moon with the time left while a sleep timer
 * runs (it opens the same sleep rows, so it can be cancelled), and "More",
 * which holds the sleep timer and the mini player. "More" is lit while the
 * mini player is up, so something on in it is never hidden.
 */
@Composable
private fun SleepAndMore(controller: DesktopController) {
    // Counted down on its own: minutes left are the clock's, and nothing
    // else redraws the bar for the clock.
    val sleepLeft by produceState(controller.sleepMinutesLeft, controller.sleepEndsAt) {
        while (true) {
            value = controller.sleepMinutesLeft
            delay(15_000)
        }
    }
    var moonOpen by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    if (controller.sleepEndsAt != null || controller.stopAfterTrack) {
        Box {
            BarToggle(
                icon = Icons.Default.Bedtime,
                label = sleepLeft?.let { "Sleep timer: $it min left" } ?: "Sleep timer: when this song ends",
                active = false,
                highlight = true,
                note = Notes.SLEEP_RUNNING
            ) { moonOpen = !moonOpen }
            if (moonOpen) MoreItems(sleepRows(controller)) { moonOpen = false }
        }
    }
    Box {
        BarToggle(
            icon = Icons.Default.MoreHoriz,
            label = "More",
            active = moreOpen,
            highlight = controller.miniPlayer,
            note = if (controller.miniPlayer) "The mini player is on." else null
        ) { moreOpen = !moreOpen }
        if (moreOpen) {
            MoreItems(
                sleepRows(controller) + MoreItem(
                    if (controller.miniPlayer) "Mini player (on)" else "Mini player",
                    Notes.MORE_MINI
                ) { controller.miniPlayer = !controller.miniPlayer }
            ) { moreOpen = false }
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
    /** A line on what it does, under its name in the hover note; see [Noted]. */
    note: String? = null,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Noted(label, note, iconOnly = true) {
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
}

/**
 * The A-B loop button, beside repeat: "A-B" dim while off, "A" lit once A is
 * set, "A-B" lit while going round. The L key does the same.
 */
@Composable
private fun AbLoopButton(loopStartMs: Long?, loopEndMs: Long?, enabled: Boolean, onClick: () -> Unit) {
    val looping = loopStartMs != null && loopEndMs != null
    val armed = loopStartMs != null && loopEndMs == null
    val tip = when {
        looping -> "Looping ${loopStartMs!!.asDuration()} to ${loopEndMs!!.asDuration()} - click to stop (L)"
        armed -> "A at ${loopStartMs!!.asDuration()} - click again for B (L)"
        else -> "Loop part of the song: A here, then B (L)"
    }
    Noted("A-B loop", tip, iconOnly = true) {
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
    loopStartMs: Long?,
    loopEndMs: Long?
) {
    val positionMs by controller.engine.collectPositionMs()
    SeekLine(positionMs, durationMs, track != null, loopStartMs, loopEndMs) { controller.seekFraction(it) }
}

/**
 * The line and its clocks, as wide as the slot it is in: the clocks take their
 * fixed width at each end and the line the rest, so the row's ends are the
 * buttons' ends above it.
 */
@Composable
internal fun SeekLine(
    positionMs: Long,
    durationMs: Long,
    enabled: Boolean,
    loopStartMs: Long?,
    loopEndMs: Long?,
    onSeek: (Float) -> Unit
) {
    // Where the drag currently is, or null when nobody is dragging.
    // The clock on the left follows this while it is set, so the
    // number and the bar say the same thing mid-drag.
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            (scrubbing?.let { (it * durationMs).toLong() } ?: positionMs).asDuration(),
            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
            color = if (scrubbing != null) Palette.Accent else Palette.TextFaint,
            textAlign = TextAlign.End,
            modifier = Modifier.width(38.dp)
        )
        Spacer(Modifier.width(4.dp))
        Box(Modifier.weight(1f)) {
            LineSlider(
                value = if (durationMs > 0) {
                    (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                } else {
                    0f
                },
                enabled = enabled,
                onPreview = { scrubbing = it },
                onSet = onSeek,
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
