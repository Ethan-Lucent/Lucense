package com.exo.musicplayer.desktop.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import com.exo.musicplayer.data.audio.Trim
import com.exo.musicplayer.desktop.audio.Decoder
import com.exo.musicplayer.desktop.data.DesktopController
import com.exo.musicplayer.desktop.data.EffectTrim
import com.exo.musicplayer.desktop.library.DesktopTrack
import com.exo.musicplayer.desktop.system.Explorer
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Dialog
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/** Bars across the waveform. */
private const val BARS = 220

/** Numbers that keep their width as they change, so a readout doesn't jitter while a handle is dragged. */
private const val TABULAR = "tnum"

/** Red enough to read as a problem on both the dark and the light themes. */
private val Problem = Color(0xFFE5484D)

/**
 * What the Trim window lets its content do with keys and closing: the content
 * sets [onKey] and [busy], the window reads them. Kept outside the content so
 * the keys work wherever the focus happens to be, as the main window's do.
 */
internal class TrimHooks {
    var onKey: (KeyEvent) -> Boolean = { false }
    /** Saving: the window stays open until it is done, so the file isn't left half written. */
    var busy = false
}

/**
 * Cut a song down and keep the piece as a new song: Riff's trimmer
 * (github.com/rootscripts/riff, MIT). The song is drawn as its loudness; the
 * two handles mark what is kept, and the preview plays just that stretch in
 * the small player, leaving the queue alone.
 *
 * The window is owned by [owner], the main window, and is not modal. An owned
 * window stays in front of its owner and is minimised and restored with it,
 * while the owner stays fully usable - so the effects panel can be changed
 * while the cut is open. Compose's own DialogWindow(onCloseRequest, ...) can't
 * give that: with an owner it makes the dialog modal (the main window would
 * stop answering), without one nothing keeps it in front. So the dialog is
 * made here, modeless, with the owner.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrimWindow(controller: DesktopController, track: DesktopTrack, owner: Window?, onClose: () -> Unit) {
    val hooks = remember { TrimHooks() }
    val close by rememberUpdatedState(onClose)
    DialogWindow(
        onPreviewKeyEvent = { hooks.onKey(it) },
        create = {
            val screens = runCatching {
                GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map {
                    it.defaultConfiguration.bounds.let { b -> TrimBox(b.x, b.y, b.width, b.height) }
                }
            }.getOrDefault(emptyList())
            val box = trimPlacement(
                controller.settings.trimWindowAt,
                owner?.let { TrimBox(it.x, it.y, it.width, it.height) },
                screens
            )
            ComposeDialog(owner, Dialog.ModalityType.MODELESS).apply {
                title = "Trim · ${track.title}"
                defaultCloseOperation = javax.swing.WindowConstants.DO_NOTHING_ON_CLOSE
                minimumSize = Dimension(TRIM_MIN_W, TRIM_MIN_H)
                setBounds(box.x, box.y, box.w, box.h)
                addWindowListener(object : WindowAdapter() {
                    override fun windowClosing(e: WindowEvent?) {
                        if (!hooks.busy) close()
                    }
                })
                // Remembered as it is moved and sized, for the next time it opens.
                addComponentListener(object : ComponentAdapter() {
                    private fun remember() {
                        if (isShowing) controller.settings.trimWindowAt = TrimBox(x, y, width, height).toString()
                    }
                    override fun componentMoved(e: ComponentEvent?) = remember()
                    override fun componentResized(e: ComponentEvent?) = remember()
                })
            }
        },
        dispose = { it.dispose() }
    ) {
        LucenseDesktopTheme {
            // Drawn at the size it was laid out for and scaled to the window, as the
            // other windows are, so the interface-size setting and a small window both fit.
            ScaledToWindow(designWidth = 700.dp, designHeight = 520.dp, minScale = 0.75f) {
                if (Glass.on) {
                    // The same glass as the main window: a pane over the colour field.
                    val state = remember { HazeState() }
                    Box(Modifier.fillMaxSize()) {
                        GlassBackground(controller.wallpaper, controller.wallpaperDim, Modifier.matchParentSize().hazeSource(state))
                        Box(
                            Modifier.fillMaxSize().padding(Glass.GAP)
                                .glassPane(state, RoundedCornerShape(Glass.PANE), Palette.Content, 0.45f)
                        ) { Trimmer(controller, track, onClose, hooks) }
                    }
                } else {
                    Trimmer(controller, track, onClose, hooks)
                }
            }
        }
    }
}

/** The Trim window's state and the preview and save it drives; [TrimPanel] draws it. */
@Composable
internal fun Trimmer(controller: DesktopController, track: DesktopTrack, onClose: () -> Unit, hooks: TrimHooks? = null) {
    val wave by rememberWave(track)
    val lengthMs = wave.lengthMs
    var startMs by remember(track.file) { mutableStateOf(0L) }
    // Null is "to the end", so it follows the length once the whole song has been read.
    var endSet by remember(track.file) { mutableStateOf<Long?>(null) }
    val endMs = (endSet ?: lengthMs).coerceAtMost(lengthMs)

    var saving by remember { mutableStateOf(false) }
    // Off unless chosen: a plain trim is lossless, one with the effects is encoded again.
    var withEffects by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<File?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val effectsOn = !controller.fx.isDefault
    val bakeEffects = effectsOn && withEffects

    fun changed() {
        saved = null
        problem = null
    }

    fun setStart(ms: Long) {
        startMs = clampStart(ms, endMs)
        changed()
    }

    fun setEnd(ms: Long) {
        val end = clampEnd(ms, startMs, lengthMs)
        endSet = if (end >= lengthMs) null else end
        changed()
    }

    // The preview, and where in the song it has got to.
    val preview by controller.preview.state.collectAsState()
    val key = controller.trimKey(track)
    val mine = preview.videoId?.startsWith("$key#") == true
    val previewFrom = if (mine) preview.videoId?.substringAfterLast('#')?.substringBefore('-')?.toLongOrNull() else null
    val previewing = mine && !preview.ended
    var playhead by remember { mutableStateOf<Long?>(null) }
    // Where the playhead was last, kept when the preview stops or the arrows move it.
    var cursor by remember(track.file) { mutableStateOf<Long?>(null) }
    LaunchedEffect(mine, preview.playing, previewFrom) {
        if (!mine || previewFrom == null || preview.ended) {
            playhead = null
            return@LaunchedEffect
        }
        while (isActive && preview.playing) {
            withFrameMillis { }
            playhead = previewFrom + controller.preview.playedMs()
            cursor = playhead
        }
    }
    DisposableEffect(key) {
        onDispose { if (controller.preview.state.value.videoId?.startsWith("$key#") == true) controller.stopPreview() }
    }

    fun playCut() {
        if (lengthMs > 0) controller.previewTrim(track, startMs, endMs)
    }

    fun save() {
        if (!canSave(lengthMs, startMs, endMs, bakeEffects, saving)) return
        saving = true
        problem = null
        scope.launch {
            controller.saveTrim(track, startMs, endMs, withEffects = bakeEffects)
                .onSuccess { saved = it }
                .onFailure { problem = "Couldn't save it: ${it.message}" }
            saving = false
        }
    }

    // The keys, handed to the window (see TrimHooks).
    SideEffect {
        hooks?.busy = saving
        hooks?.onKey = onKey@{ event ->
            if (event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) {
                return@onKey false
            }
            when (trimKeyAction(event.key) ?: return@onKey false) {
                TrimKeyAction.PLAY -> {
                    if (previewing) controller.preview.pauseOrResume() else playCut()
                    true
                }
                TrimKeyAction.SET_START -> { setStart(markPosition(playhead, cursor, startMs)); true }
                TrimKeyAction.SET_END -> { setEnd(markPosition(playhead, cursor, startMs)); true }
                TrimKeyAction.EARLIER, TrimKeyAction.LATER -> {
                    val to = nudge(
                        markPosition(playhead, cursor, startMs),
                        if (trimKeyAction(event.key) == TrimKeyAction.EARLIER) -1 else 1,
                        event.isShiftPressed,
                        lengthMs
                    )
                    cursor = to
                    // While it plays, the preview carries on from the new place.
                    if (previewing && to < endMs) controller.previewTrim(track, to, endMs)
                    true
                }
                TrimKeyAction.SAVE -> {
                    if (canSave(lengthMs, startMs, endMs, bakeEffects, saving)) { save(); true } else false
                }
                // Nothing to confirm: the original is never changed, so closing loses only the
                // handles' places. While a save is writing, it is let finish.
                TrimKeyAction.CLOSE -> { if (!saving) onClose(); true }
            }
        }
    }

    TrimPanel(
        title = track.title,
        artist = track.displayArtist,
        bars = wave.bars,
        lengthMs = lengthMs,
        reading = wave.reading,
        startMs = startMs,
        endMs = endMs,
        playheadMs = playhead ?: cursor,
        previewing = previewing,
        effectsOn = effectsOn,
        effectsLabel = if (effectsOn) EffectTrim.describe(controller.fx) else "",
        withEffects = withEffects,
        saving = saving,
        saved = saved,
        problem = problem,
        onStart = ::setStart,
        onEnd = ::setEnd,
        onPlay = ::playCut,
        onHearEnd = { controller.previewTrim(track, (endMs - Trim.END_PREVIEW_MS).coerceAtLeast(startMs), endMs) },
        onStop = { controller.stopPreview() },
        onReset = {
            startMs = 0L
            endSet = null
            changed()
        },
        onWithEffects = { withEffects = it },
        onSave = ::save,
        onClose = { if (!saving) onClose() }
    )
}

/**
 * The Trim window's content, from plain values: the waveform with its handles,
 * the readouts, the buttons, what saving has done, and the keys that work.
 */
@Composable
internal fun TrimPanel(
    title: String,
    artist: String,
    bars: FloatArray,
    lengthMs: Long,
    reading: Boolean,
    startMs: Long,
    endMs: Long,
    playheadMs: Long?,
    previewing: Boolean,
    effectsOn: Boolean,
    effectsLabel: String,
    withEffects: Boolean,
    saving: Boolean,
    saved: File?,
    problem: String?,
    onStart: (Long) -> Unit,
    onEnd: (Long) -> Unit,
    onPlay: () -> Unit,
    onHearEnd: () -> Unit,
    onStop: () -> Unit,
    onReset: () -> Unit,
    onWithEffects: (Boolean) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit
) {
    val bake = effectsOn && withEffects
    val blocker = saveBlocker(lengthMs, startMs, endMs, bake)
    val whole = startMs <= 0L && endMs >= lengthMs

    Column(Modifier.fillMaxSize().background(if (Glass.on) Color.Transparent else Palette.Content).padding(16.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(artist, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim, maxLines = 1)
        Spacer(Modifier.height(14.dp))

        // Takes whatever height the window has left, so a taller window shows a taller picture.
        Waveform(
            bars = bars,
            lengthMs = lengthMs,
            startMs = startMs,
            endMs = endMs,
            playheadMs = playheadMs,
            onStart = onStart,
            onEnd = onEnd,
            modifier = Modifier.fillMaxWidth().weight(1f).heightIn(min = 110.dp)
        )
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0:00.0", style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = TABULAR), color = Palette.TextFaint)
            Noted(null, Notes.TRIM_HANDLES) {
                Text(
                    if (reading) "Reading the song…" else "Drag the handles, or click near one to move it there",
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.TextFaint
                )
            }
            Text(Trim.clock(lengthMs), style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = TABULAR), color = Palette.TextFaint)
        }

        Spacer(Modifier.height(14.dp))
        // Start, end, length and what goes: one row, in a card like the rest of the app's.
        Row(
            Modifier.fillMaxWidth().card(10.dp).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CutField("Start", startMs, { onStart(startMs - Trim.NUDGE_MS) }, { onStart(startMs + Trim.NUDGE_MS) })
            Spacer(Modifier.width(16.dp))
            CutField("End", endMs, { onEnd(endMs - Trim.NUDGE_MS) }, { onEnd(endMs + Trim.NUDGE_MS) })
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text("Length", style = MaterialTheme.typography.labelMedium, color = Palette.TextDim)
                Text(
                    Trim.clock(endMs - startMs),
                    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = TABULAR),
                    color = Palette.Text
                )
                Text(
                    removedText(lengthMs, startMs, endMs),
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = TABULAR),
                    color = Palette.TextDim
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (previewing) {
                GhostButton("Stop", icon = Icons.Default.Stop, note = Notes.TRIM_STOP) { onStop() }
            } else {
                GhostButton("Play the cut", icon = Icons.Default.PlayArrow, enabled = lengthMs > 0, note = Notes.TRIM_PLAY) { onPlay() }
            }
            Spacer(Modifier.width(8.dp))
            GhostButton("Hear the end", icon = Icons.Default.SkipNext, enabled = lengthMs > 0, note = Notes.TRIM_HEAR_END) { onHearEnd() }
            Spacer(Modifier.weight(1f))
            GhostButton("Reset", icon = Icons.Default.Replay, enabled = !whole, note = Notes.TRIM_RESET) { onReset() }
        }

        Spacer(Modifier.height(14.dp))
        if (effectsOn) {
            CheckRow(
                label = "With the effects on now ($effectsLabel)",
                checked = withEffects,
                note = "Speed, pitch, reverb, EQ and the rest, exactly as you hear them. A reverb rings on past the cut."
            ) { onWithEffects(it) }
            Spacer(Modifier.height(8.dp))
        }
        Hint(
            if (bake) {
                "Played through the effects and encoded again, at a high setting, in the original's format where " +
                    "ffmpeg can write it, with its tags and cover. Saved beside the original, which stays as it is."
            } else {
                "Lossless: the audio is cut, not encoded again, so it sounds exactly as before. A cut lands " +
                    "within a few hundredths of a second. The piece is saved beside the original, which stays as it is."
            }
        )
        Spacer(Modifier.height(12.dp))

        // A fixed height, so what saving says doesn't move the picture above it.
        Box(Modifier.fillMaxWidth().height(34.dp)) {
            when {
                problem != null -> Row(
                    Modifier.fillMaxSize().clipBox(Problem.copy(alpha = 0.12f)).padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(problem, style = MaterialTheme.typography.bodySmall, color = Problem, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                saved != null -> Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Saved as “${saved.name}”, and added to your library.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.Text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(8.dp))
                    GhostButton("Show in folder", icon = Icons.Default.FolderOpen) { Explorer.reveal(saved) }
                }
                saving -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                    Text("Saving the piece…", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = Palette.Accent,
                        trackColor = Palette.Line
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            GhostButton("Close", enabled = !saving, note = Notes.TRIM_KEY_ESC) { onClose() }
            Spacer(Modifier.width(8.dp))
            AccentButton(
                if (saving) "Saving…" else "Save as new song",
                enabled = canSave(lengthMs, startMs, endMs, bake, saving),
                icon = Icons.Default.ContentCut,
                note = if (saving) null else blocker ?: Notes.TRIM_SAVE
            ) { onSave() }
        }
        Spacer(Modifier.height(10.dp))
        KeyHints()
    }
}

/** A soft tinted strip, for the line that says something went wrong. */
private fun Modifier.clipBox(fill: Color): Modifier =
    this.then(Modifier.background(fill, RoundedCornerShape(8.dp)))

/** The keys that work in this window, small and faint, each with a note on what it does. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyHints() {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        KeyHint("Space", "play · pause", "Play", Notes.TRIM_KEY_SPACE)
        KeyHint("I  O", "set start · end", "Set the ends", Notes.TRIM_KEY_IO)
        KeyHint("←  →", "move the playhead", "Move the playhead", Notes.TRIM_KEY_ARROWS)
        KeyHint("Enter", "save", "Save", Notes.TRIM_KEY_ENTER)
        KeyHint("Esc", "close", "Close", Notes.TRIM_KEY_ESC)
    }
}

@Composable
private fun KeyHint(keys: String, what: String, name: String, note: String) {
    Noted(name, note) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                keys,
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextFaint,
                modifier = Modifier
                    .border(1.dp, Palette.Line, RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            )
            Spacer(Modifier.width(5.dp))
            Text(what, style = MaterialTheme.typography.labelSmall, color = Palette.TextFaint)
        }
    }
}

@Composable
internal fun CutField(label: String, ms: Long, onEarlier: () -> Unit, onLater: () -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Palette.TextDim)
        Row(verticalAlignment = Alignment.CenterVertically) {
            GhostButton("−", note = Notes.TRIM_NUDGE) { onEarlier() }
            Spacer(Modifier.width(6.dp))
            Text(
                Trim.clock(ms),
                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = TABULAR),
                color = Palette.Text,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(72.dp)
            )
            GhostButton("+", note = Notes.TRIM_NUDGE) { onLater() }
        }
    }
}

/**
 * The song's loudness as bars: the kept stretch lit and the rest dimmed, with
 * a handle at each end. A press moves whichever handle is nearer to it, and a
 * drag carries on from there. A handle widens and brightens when the mouse is
 * on it or it is being dragged, and then shows the time it stands at.
 */
@Composable
internal fun Waveform(
    bars: FloatArray,
    lengthMs: Long,
    startMs: Long,
    endMs: Long,
    playheadMs: Long?,
    onStart: (Long) -> Unit,
    onEnd: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = Palette.Accent
    val faint = Palette.TextFaint.copy(alpha = 0.28f)
    val shade = Palette.Base.copy(alpha = 0.6f)
    val lit = accent.copy(alpha = 0.10f)
    val handle = Palette.Text
    val raised = Palette.Raised
    val line = Palette.Line
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = TABULAR, color = Palette.Text)
    // The latest values, for the gestures, which outlive any one of them.
    val latest = remember { object { var start = 0L; var end = 0L; var length = 0L } }
    latest.start = startMs
    latest.end = endMs
    latest.length = lengthMs
    // Which handle the mouse is on, and which is being dragged: 0 neither, 1 the start, 2 the end.
    var hover by remember { mutableStateOf(0) }
    var dragging by remember { mutableStateOf(0) }
    val hotStart by animateFloatAsState(if (hover == 1 || dragging == 1) 1f else 0f)
    val hotEnd by animateFloatAsState(if (hover == 2 || dragging == 2) 1f else 0f)

    Canvas(
        modifier
            .card(10.dp)
            // A hand on a handle, a crosshair elsewhere, where a click puts the nearer one.
            .pointerHoverIcon(if (hover != 0 || dragging != 0) PointerIcon.Hand else PointerIcon.Crosshair)
            .pointerInput(Unit) {
                // Watches without taking anything, to know which handle the mouse is on.
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val at = event.changes.firstOrNull()
                        hover = if (event.type == PointerEventType.Exit || at == null || latest.length <= 0) {
                            0
                        } else {
                            val pad = 10.dp.toPx()
                            handleNear(at.position.x, pad, size.width - 2 * pad, latest.start, latest.end, latest.length, 9.dp.toPx())
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (latest.length <= 0) return@awaitEachGesture
                    // Inset, so a handle at either end is drawn whole.
                    val pad = 10.dp.toPx()
                    fun at(x: Float): Long =
                        ((x - pad) / (size.width - 2 * pad) * latest.length).toLong().coerceIn(0L, latest.length)
                    val pressed = at(down.position.x)
                    val moveStart = abs(pressed - latest.start) <= abs(pressed - latest.end)
                    dragging = if (moveStart) 1 else 2
                    if (moveStart) onStart(pressed) else onEnd(pressed)
                    drag(down.id) { change ->
                        change.consume()
                        val ms = at(change.position.x)
                        if (moveStart) onStart(ms) else onEnd(ms)
                    }
                    dragging = 0
                }
            }
    ) {
        if (lengthMs <= 0) return@Canvas
        val pad = 10.dp.toPx()
        val span = size.width - 2 * pad
        val step = span / BARS
        val middle = size.height / 2
        val startX = pad + startMs.toFloat() / lengthMs * span
        val endX = pad + endMs.toFloat() / lengthMs * span
        bars.forEachIndexed { i, value ->
            val x = pad + i * step + step / 2
            val height = (value * (size.height - 16f)).coerceAtLeast(2f)
            drawRoundRect(
                color = if (x in startX..endX) accent else faint,
                topLeft = Offset(x - step * 0.32f, middle - height / 2),
                size = Size(step * 0.64f, height),
                cornerRadius = CornerRadius(step * 0.3f)
            )
        }
        // The kept stretch is washed with the accent; what is cut away is shaded as
        // well as faint, so which is which reads at a glance.
        drawRect(lit, Offset(startX, 0f), Size(endX - startX, size.height))
        drawRect(shade, Offset.Zero, Size(startX, size.height))
        drawRect(shade, Offset(endX, 0f), Size(size.width - endX, size.height))

        // The playhead: thin, with a point on top so it isn't taken for a handle.
        playheadMs?.let {
            val x = pad + it.toFloat().coerceIn(0f, lengthMs.toFloat()) / lengthMs * span
            drawRect(handle.copy(alpha = 0.85f), Offset(x - 0.75f, 0f), Size(1.5f, size.height))
            drawPath(
                Path().apply {
                    moveTo(x - 5f, 0f)
                    lineTo(x + 5f, 0f)
                    lineTo(x, 7f)
                    close()
                },
                handle
            )
        }

        for ((x, hot) in listOf(startX to hotStart, endX to hotEnd)) {
            // A halo, a wider and brighter bar, and a bigger grip once it is the one in play.
            if (hot > 0f) drawRect(accent.copy(alpha = 0.22f * hot), Offset(x - 9f, 0f), Size(18f, size.height))
            val colour = lerp(handle.copy(alpha = 0.78f), handle, hot)
            val bar = 3f + 2f * hot
            drawRect(colour, Offset(x - bar / 2, 0f), Size(bar, size.height))
            val gripW = 12f + 5f * hot
            val gripH = 28f + 8f * hot
            drawRoundRect(colour, Offset(x - gripW / 2, middle - gripH / 2), Size(gripW, gripH), CornerRadius(gripW / 2))
            for (dy in listOf(-4f, 0f, 4f)) {
                drawRect(shade.copy(alpha = 0.7f), Offset(x - gripW / 4, middle + dy - 0.5f), Size(gripW / 2, 1f))
            }
        }
        // The time a handle stands at, over it, while it is in play.
        for ((x, at, hot) in listOf(Triple(startX, startMs, hotStart), Triple(endX, endMs, hotEnd))) {
            if (hot < 0.5f) continue
            val text = measurer.measure(Trim.clock(at), labelStyle)
            val w = text.size.width + 14f
            val h = text.size.height + 6f
            val left = (x - w / 2).coerceIn(2f, (size.width - w - 2f).coerceAtLeast(2f))
            drawRoundRect(raised, Offset(left, 12f), Size(w, h), CornerRadius(5f))
            drawRoundRect(line, Offset(left, 12f), Size(w, h), CornerRadius(5f), style = Stroke(1f))
            drawText(text, topLeft = Offset(left + 7f, 15f))
        }
    }
}

/** The handle within [grab] pixels of [x]: 1 the start, 2 the end, 0 neither; the nearer one if both are. */
internal fun handleNear(x: Float, pad: Float, span: Float, startMs: Long, endMs: Long, lengthMs: Long, grab: Float): Int {
    if (lengthMs <= 0 || span <= 0f) return 0
    val toStart = abs(x - (pad + startMs.toFloat() / lengthMs * span))
    val toEnd = abs(x - (pad + endMs.toFloat() / lengthMs * span))
    return when {
        minOf(toStart, toEnd) > grab -> 0
        toStart <= toEnd -> 1
        else -> 2
    }
}

private class Wave(val bars: FloatArray, val lengthMs: Long, val reading: Boolean)

/**
 * The song's loudness, read once in the background: a value per 10 ms, shown
 * as it fills in. The length comes from what was actually read once it is
 * done, which beats a tag that has it wrong.
 */
@Composable
private fun rememberWave(track: DesktopTrack): State<Wave> {
    val state = remember(track.file) { mutableStateOf(Wave(FloatArray(BARS), track.durationMs, reading = true)) }
    LaunchedEffect(track.file) {
        withContext(Dispatchers.IO) {
            val slices = FloatArray(((track.durationMs.coerceAtLeast(1_000L)) / 10 + 200).toInt())
            var filled = 0
            var published = 0L
            runCatching {
                Decoder.open(track.file).use { decoder ->
                    var sum = 0.0
                    var count = 0
                    while (isActive && filled < slices.size) {
                        val chunk = decoder.read(4_410) ?: break
                        var i = 0
                        while (i + 1 < chunk.size) {
                            val mono = (chunk[i] + chunk[i + 1]) * 0.5f
                            sum += mono * mono
                            if (++count == 441) {
                                if (filled < slices.size) slices[filled++] = sqrt(sum / count).toFloat()
                                sum = 0.0
                                count = 0
                            }
                            i += 2
                        }
                        val now = System.currentTimeMillis()
                        if (now - published > 120) {
                            published = now
                            state.value = Wave(Trim.bars(slices, filled, BARS), track.durationMs, reading = true)
                        }
                    }
                }
            }
            state.value = if (filled > 0) {
                Wave(Trim.bars(slices.copyOf(filled), filled, BARS), filled * 10L, reading = false)
            } else {
                Wave(FloatArray(BARS), track.durationMs, reading = false)
            }
        }
    }
    return state
}
