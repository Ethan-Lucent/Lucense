package com.exo.musicplayer.desktop.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.onClick
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.delay
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.exo.musicplayer.desktop.data.DesktopController
import com.exo.musicplayer.desktop.data.DownloadEntry
import com.exo.musicplayer.desktop.data.bulkChipText

/**
 * One run of sidebar pages. [label] is the tiny heading over it (null for the
 * main pages, and never shown in the narrow icon-only rail); a [foldable] group
 * can be tucked away behind its heading.
 */
internal class RailGroup(val label: String?, val pages: List<Destination>, val foldable: Boolean = false)

/**
 * The sidebar's pages in groups, in order: what you play, where you find new
 * music, and the rarely-needed pages tucked under More. Settings sits apart
 * at the bottom. Every page is still one click (or Ctrl+K) away.
 */
internal fun railGroups(): List<RailGroup> = listOf(
    RailGroup(null, listOf(Destination.LIBRARY, Destination.ALBUMS, Destination.ARTISTS, Destination.PLAYLISTS)),
    RailGroup("Find music", listOf(Destination.DISCOVER, Destination.IDENTIFY, Destination.DOWNLOAD)),
    RailGroup("More", listOf(Destination.MOODS, Destination.STATS), foldable = true)
)

@Composable
internal fun NavigationRail(
    controller: DesktopController,
    compact: Boolean,
    current: Destination?,
    settingsOpen: Boolean,
    onSelect: (Destination) -> Unit,
    onSettings: () -> Unit,
    /** Whether the Bulk tools dialog is showing (it shows the progress itself, so no chip). */
    bulkOpen: Boolean = false,
    onOpenBulk: () -> Unit = {}
) {
    // How tightly to set the rail, from its own height: a short window tightens the
    // spacing so every page and Settings still fit, and only then does it scroll.
    val density = LocalDensity.current
    var fit by remember { mutableStateOf(RailFit.ROOMY) }
    Box(
        Modifier
            .width(if (compact) 60.dp else 212.dp)
            .fillMaxHeight()
            .onSizeChanged { fit = railFit(with(density) { it.height.toDp() }, compact) }
            .then(
                if (Glass.on) {
                    Modifier.glassPane(LocalGlass.current, RoundedCornerShape(Glass.PANE), Palette.Sidebar, 0.4f)
                } else {
                    Modifier.background(Palette.Sidebar.copy(alpha = if (controller.wallpaper != null) 0.6f else 1f))
                }
            )
    ) {
        Backdrop(
            style = controller.backdrop,
            color = Palette.Stars,
            spectrum = controller.engine.spectrum,
            graph = controller.songGraph,
            beat = controller.engine.beat,
            reactiveMode = controller.reactiveMode,
            modifier = Modifier.matchParentSize(),
            count = 40,
            centerpiece = false
        )
        RailColumn(
            compact = compact,
            fit = fit,
            current = current,
            settingsOpen = settingsOpen,
            moreOpen = controller.railMoreOpen,
            onMoreOpen = { controller.railMoreOpen = it },
            downloads = controller.downloads,
            shineDownloads = controller.unseenFinishedDownloads > 0,
            onSearch = { controller.openPalette() },
            onSelect = onSelect,
            onSettings = onSettings,
            bulk = { RailBulk(controller, compact, bulkOpen, onOpenBulk) }
        )
    }
}

/**
 * The rail's contents, with nothing from the controller in it so it can be set
 * up with made-up state and looked at. The pages scroll on their own so a short
 * window never pushes Settings off the bottom; [RailScroll] says so when they do.
 */
@Composable
internal fun RailColumn(
    compact: Boolean,
    fit: RailFit,
    current: Destination?,
    settingsOpen: Boolean,
    moreOpen: Boolean,
    onMoreOpen: (Boolean) -> Unit,
    downloads: List<DownloadEntry>,
    shineDownloads: Boolean,
    onSearch: () -> Unit,
    onSelect: (Destination) -> Unit,
    onSettings: () -> Unit,
    bulk: @Composable () -> Unit = {},
    /** Off only to picture the rail as it was, before it had a cue. */
    scrollCue: Boolean = true
) {
    Column(Modifier.fillMaxSize().padding(vertical = fit.top)) {
        Row(
            Modifier.padding(horizontal = if (compact) 25.dp else 18.dp, vertical = fit.titleGap).height(fit.titleHeight),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(Palette.Accent))
            if (!compact) {
                Spacer(Modifier.width(10.dp))
                Text("Lucense", style = MaterialTheme.typography.titleLarge, color = Palette.Text)
            }
        }

        Spacer(Modifier.height(fit.afterTitle))
        RailSearch(compact, fit, onSearch)
        Spacer(Modifier.height(fit.afterSearch))
        RailScroll(rememberScrollState(), Modifier.weight(1f), cue = scrollCue) {
            railGroups().forEachIndexed { index, group ->
                if (index > 0) RailDivider(fit)
                // Starts as it was left, and open when you are on one of its pages or
                // have asked for the advanced options; the heading still folds it.
                var open by remember { mutableStateOf(moreOpen || current in group.pages || Advanced.on) }
                if (group.foldable) {
                    LaunchedEffect(current, Advanced.on) {
                        if (current in group.pages || Advanced.on) open = true
                    }
                }
                if (!compact && group.label != null) {
                    if (group.foldable) {
                        RailFold(group.label, open) {
                            open = !open
                            onMoreOpen(open)
                        }
                    } else {
                        RailLabel(group.label)
                    }
                }
                // The narrow rail has no headings to fold behind, so it shows every page.
                if (compact || !group.foldable || open) {
                    group.pages.forEach { entry ->
                        if (entry == Destination.DOWNLOAD) {
                            RailItem(
                                label = entry.label,
                                icon = entry.icon,
                                selected = entry == current,
                                badge = downloads.count { !it.done },
                                shine = shineDownloads,
                                compact = compact,
                                pad = fit.item,
                                note = railNote(entry)
                            ) { onSelect(entry) }
                            if (!compact) RailDownloads(downloads) { onSelect(Destination.DOWNLOAD) }
                        } else {
                            RailItem(entry.label, entry.icon, entry == current, compact = compact, pad = fit.item, note = railNote(entry)) { onSelect(entry) }
                        }
                    }
                }
            }
        }
        bulk()
        RailItem("Settings", Icons.Default.Settings, settingsOpen, compact = compact, pad = fit.item, note = Notes.RAIL_SETTINGS, onClick = onSettings)
        Spacer(Modifier.height(fit.bottom))
    }
}

/**
 * How tightly the rail is set: the spacing that can give when the window is
 * short. [ROOMY] is the rail as it has always looked; the others only trim
 * the gaps (never the icons or the text), so nothing about a normal window changes.
 */
internal enum class RailFit(
    val top: Dp, val titleGap: Dp, val titleHeight: Dp, val afterTitle: Dp, val afterSearch: Dp,
    val item: Dp, val search: Dp, val divider: Dp, val bottom: Dp
) {
    ROOMY(14.dp, 8.dp, 28.dp, 10.dp, 8.dp, 9.dp, 7.dp, 6.dp, 10.dp),
    DENSE(8.dp, 4.dp, 26.dp, 4.dp, 4.dp, 6.dp, 5.dp, 3.dp, 6.dp),
    TIGHT(4.dp, 2.dp, 24.dp, 2.dp, 2.dp, 4.dp, 3.dp, 2.dp, 4.dp)
}

/**
 * Which [RailFit] a rail [height] gets. The limits are the heights at which each
 * setting's pages plus Settings just fit (see UiPolishTest); a window shorter than
 * the tightest one scrolls, with the cue.
 */
internal fun railFit(height: Dp, compact: Boolean): RailFit {
    val roomy = if (compact) RAIL_ROOMY_COMPACT else RAIL_ROOMY_WIDE
    val dense = if (compact) RAIL_DENSE_COMPACT else RAIL_DENSE_WIDE
    return when {
        height >= roomy -> RailFit.ROOMY
        height >= dense -> RailFit.DENSE
        else -> RailFit.TIGHT
    }
}

// The heights each setting needs with every page showing (measured in UiPolishTest: 555 / 445
// compact, 580 / 470 wide with More open), plus a little room for a download or two.
private val RAIL_ROOMY_COMPACT = 560.dp
private val RAIL_DENSE_COMPACT = 455.dp
private val RAIL_ROOMY_WIDE = 590.dp
private val RAIL_DENSE_WIDE = 480.dp

/** Where the scroll thumb sits and how long it is, both as fractions of the track (0..1). */
internal class RailThumb(val offset: Float, val size: Float)

/**
 * The thumb for a list scrolled to [value] of at most [max] in a viewport of
 * [viewport] pixels: its length is the share of the content that is showing (never
 * under [minSize], so it stays something to see), and it travels the rest of the
 * track as the list does. Null when nothing scrolls.
 */
internal fun railThumb(value: Int, max: Int, viewport: Int, minSize: Float = 0.12f): RailThumb? {
    if (max <= 0 || viewport <= 0) return null
    val size = (viewport.toFloat() / (viewport + max)).coerceIn(minSize, 1f)
    val offset = (value.toFloat() / max).coerceIn(0f, 1f) * (1f - size)
    return RailThumb(offset, size)
}

private val RAIL_FADE = 24.dp

/**
 * The rail's scrolling area, with the signs that it scrolls: the pages fade out
 * at an edge that has more behind it (so a half-hidden icon reads as "more this way"),
 * and a slim thumb sits on the right edge - faint whenever the list overflows,
 * clearer while it is being scrolled or hovered. The mouse wheel scrolls it (the
 * plain vertical scroll takes it) over any part of the rail.
 */
@Composable
internal fun RailScroll(
    state: ScrollState,
    modifier: Modifier = Modifier,
    cue: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    // Lit for a moment after the list moves, then back to faint.
    var recent by remember { mutableStateOf(false) }
    LaunchedEffect(state.value) {
        recent = true
        delay(900)
        recent = false
    }
    val up by animateFloatAsState(if (cue && state.canScrollBackward) 1f else 0f, tween(160), label = "rail-fade-up")
    val down by animateFloatAsState(if (cue && state.canScrollForward) 1f else 0f, tween(160), label = "rail-fade-down")
    val lit by animateFloatAsState(if (hovered || recent || state.isScrollInProgress) 1f else 0f, tween(200), label = "rail-thumb")
    var viewport by remember { mutableStateOf(0) }
    val faint = Palette.TextFaint
    val accent = Palette.Accent

    Box(modifier.hoverable(hover).onSizeChanged { viewport = it.height }.testTag("rail-scroll")) {
        Column(
            Modifier
                .fillMaxSize()
                // Fades the pages themselves to nothing at the edge, so it works on a
                // plain, wallpaper or glass sidebar alike (a painted-on gradient would not).
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    if (up > 0f || down > 0f) {
                        val edge = (RAIL_FADE.toPx() / size.height).coerceAtMost(0.5f)
                        drawRect(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 1f - up),
                                edge to Color.Black,
                                1f - edge to Color.Black,
                                1f to Color.Black.copy(alpha = 1f - down)
                            ),
                            blendMode = BlendMode.DstIn
                        )
                    }
                }
                .verticalScroll(state),
            content = content
        )
        val thumb = if (cue) railThumb(state.value, state.maxValue, viewport) else null
        if (thumb != null) {
            Canvas(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(5.dp).testTag("rail-thumb")) {
                val inset = 4.dp.toPx()
                val track = size.height - inset * 2
                val width = 3.dp.toPx()
                drawRoundRect(
                    color = lerp(faint, accent, lit).copy(alpha = 0.35f + 0.45f * lit),
                    topLeft = Offset(size.width - width - 1.dp.toPx(), inset + thumb.offset * track),
                    size = Size(width, thumb.size * track),
                    cornerRadius = CornerRadius(width / 2)
                )
            }
        }
    }
}

/**
 * The bulk run's status, for when its dialog is closed: the run keeps going
 * without it, and this is how it stays visible and stoppable.
 *
 * Shows "Lyrics 120 / 400" with a thin bar and a stop button while it runs;
 * clicking opens the Bulk tools dialog. When the run ends with the dialog
 * closed it keeps the result for ten seconds, or until clicked or dismissed.
 */
@Composable
private fun RailBulk(controller: DesktopController, compact: Boolean, dialogOpen: Boolean, onOpen: () -> Unit) {
    val job = controller.bulk
    val dialogNow by rememberUpdatedState(dialogOpen)
    var finished by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(job.running) {
        if (job.running) {
            finished = null
        } else if (job.finishedNote != null && !dialogNow) {
            finished = bulkChipText(job)
            delay(10_000)
            finished = null
        }
    }
    if (dialogOpen || !(job.running || finished != null)) return
    val text = if (job.running) bulkChipText(job) else finished.orEmpty()
    val open = { finished = null; onOpen() }

    if (compact) {
        // No room for words: a small ring, the same text in its tooltip.
        Noted(text, "Click to open Bulk tools", iconOnly = true) {
            Box(
                Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable(onClick = open),
                contentAlignment = Alignment.Center
            ) {
                if (job.running) {
                    CircularProgressIndicator(
                        progress = { job.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.size(18.dp),
                        color = Palette.Accent,
                        strokeWidth = 2.dp,
                        trackColor = Palette.Line
                    )
                } else {
                    Icon(Icons.Default.Check, text, Modifier.size(16.dp), tint = Palette.Accent)
                }
            }
        }
        return
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.Well)
            .clickable(onClick = open)
            .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                color = if (job.running) Palette.TextDim else Palette.Accent,
                maxLines = if (job.running) 1 else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Noted("Stop".takeIf { job.running } ?: "Dismiss", null, iconOnly = true) {
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .clickable { if (job.running) controller.cancelBulk() else finished = null },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.Close, if (job.running) "Stop" else "Dismiss", Modifier.size(14.dp), tint = Palette.TextFaint) }
            }
        }
        if (job.running) {
            Spacer(Modifier.height(5.dp))
            ThinProgress(job.progress, Modifier.padding(end = 6.dp))
        }
    }
}

/** A thin line between groups of pages. */
@Composable
private fun RailDivider(fit: RailFit) {
    Box(Modifier.padding(horizontal = 16.dp, vertical = fit.divider).fillMaxWidth().height(1.dp).background(Palette.Line))
}

/** The tiny heading over a group of pages. */
@Composable
private fun RailLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = Palette.TextFaint,
        modifier = Modifier.padding(start = 20.dp, top = 2.dp, bottom = 2.dp)
    )
}

/** A heading that folds its group away, with a small arrow that turns when open. */
@Composable
private fun RailFold(text: String, open: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(role = Role.Button, onClick = onToggle)
            .padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.ChevronRight, if (open) "Fold $text" else "Unfold $text",
            Modifier.size(14.dp).rotate(if (open) 90f else 0f),
            tint = Palette.TextFaint
        )
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = Palette.TextFaint)
    }
}

/** The line the hover note gives for each page in the sidebar (Settings has its own, [Notes.RAIL_SETTINGS]). */
internal fun railNote(page: Destination): String = when (page) {
    Destination.LIBRARY -> Notes.RAIL_LIBRARY
    Destination.ALBUMS -> Notes.RAIL_ALBUMS
    Destination.ARTISTS -> Notes.RAIL_ARTISTS
    Destination.PLAYLISTS -> Notes.RAIL_PLAYLISTS
    Destination.DISCOVER -> Notes.RAIL_DISCOVER
    Destination.IDENTIFY -> Notes.RAIL_IDENTIFY
    Destination.DOWNLOAD -> Notes.RAIL_DOWNLOAD
    Destination.MOODS -> Notes.RAIL_MOODS
    Destination.STATS -> Notes.RAIL_STATS
}

@Composable
private fun RailItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    /** A count at the top right; for the Download item, how many are running. */
    badge: Int = 0,
    /** A band of light sweeping across, while there is something new to look at. */
    shine: Boolean = false,
    /** Just the icon, centred, its name in a tooltip: the sidebar of a narrow window. */
    compact: Boolean = false,
    /** The row's own vertical padding, which [RailFit] trims in a short window. */
    pad: Dp = 9.dp,
    /** A line on what the page is for, in the hover note; see [Noted]. */
    note: String? = null,
    onClick: () -> Unit
) = Noted(if (compact) label else null, note, iconOnly = compact) {
    RailRow(label, icon, selected, badge, shine, compact, pad, onClick)
}

@Composable
private fun RailRow(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    badge: Int,
    shine: Boolean,
    compact: Boolean,
    pad: Dp,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background = when {
        selected -> Palette.Selected
        hovered -> Palette.Hover
        else -> Color.Transparent
    }
    // Only animated while shining, so an idle rail costs no frames. The sweep is
    // read inside the draw call, which repaints the item without recomposing it.
    val sweep = if (shine) {
        rememberInfiniteTransition(label = "rail-shine").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing)),
            label = "sweep"
        )
    } else {
        null
    }
    val shape = RoundedCornerShape(7.dp)

    Box(
        Modifier
            .padding(horizontal = 10.dp, vertical = 1.dp)
            .fillMaxWidth()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(background)
                .then(
                    if (shine) Modifier.border(1.dp, Palette.Accent.copy(alpha = 0.55f), shape)
                    else Modifier
                )
                .drawWithContent {
                    drawContent()
                    val progress = sweep?.value ?: return@drawWithContent
                    val band = size.width * 0.45f
                    val x = -band + (size.width + band) * progress
                    drawRect(
                        Brush.horizontalGradient(
                            listOf(
                                Color.Transparent,
                                Palette.Accent.copy(alpha = 0.30f),
                                Color.Transparent
                            ),
                            startX = x,
                            endX = x + band
                        )
                    )
                }
                .hoverable(interaction)
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = pad),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (compact) {
                Spacer(Modifier.weight(1f))
                Icon(
                    icon,
                    contentDescription = label,
                    tint = if (selected || shine) Palette.Accent else Palette.TextDim,
                    modifier = Modifier.size(19.dp)
                )
                Spacer(Modifier.weight(1f))
            } else {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (selected || shine) Palette.Accent else Palette.TextDim,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(Modifier.width(11.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) Palette.Text else Palette.TextDim
                )
            }
        }
        if (badge > 0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 3.dp, end = 6.dp)
                    .clip(CircleShape)
                    .background(Palette.Accent)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    badge.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Palette.OnAccent
                )
            }
        }
    }
}

/**
 * The latest few downloads, under the Download item.
 *
 * A glance rather than the list: newest first, four at most, each one a click
 * away from the full Download page.
 */
@Composable
private fun RailDownloads(downloads: List<DownloadEntry>, onOpen: () -> Unit) {
    if (downloads.isEmpty()) return
    val recent = downloads.asReversed().take(4)

    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 26.dp, end = 12.dp, top = 2.dp, bottom = 6.dp)
    ) {
        recent.forEach { entry ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(5.dp))
                    .clickable(onClick = onOpen)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(12.dp), contentAlignment = Alignment.Center) {
                    when {
                        entry.failed -> Icon(
                            Icons.Default.ErrorOutline, "Failed",
                            Modifier.size(12.dp), tint = Palette.TextFaint
                        )
                        entry.done -> Icon(
                            Icons.Default.Check, "Done",
                            Modifier.size(12.dp), tint = Palette.Accent
                        )
                        else -> CircularProgressIndicator(
                            Modifier.size(10.dp),
                            color = Palette.Accent,
                            strokeWidth = 1.5.dp,
                            trackColor = Palette.Line
                        )
                    }
                }
                Spacer(Modifier.width(7.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.display,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (entry.done) Palette.TextFaint else Palette.TextDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!entry.done) {
                        Spacer(Modifier.height(3.dp))
                        LinearProgressIndicator(
                            progress = { entry.percent.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(2.dp),
                            color = Palette.Accent,
                            trackColor = Palette.Line
                        )
                    }
                }
            }
        }
        if (downloads.size > recent.size) {
            Text(
                "+${downloads.size - recent.size} more",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextFaint,
                modifier = Modifier.padding(start = 25.dp, top = 2.dp)
            )
        }
    }
}

/**
 * Ctrl+K for the mouse: shaped like a search box, and opens the palette. In a
 * narrow window just its icon, with the key in its tooltip like the others.
 */
@Composable
private fun RailSearch(compact: Boolean, fit: RailFit, onClick: () -> Unit) {
    if (compact) {
        RailItem("Search everything  Ctrl+K", Icons.Default.Search, selected = false, compact = true, pad = fit.item, onClick = onClick)
        return
    }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(if (Glass.on) 12.dp else 7.dp)
    Row(
        Modifier
            .padding(horizontal = 10.dp)
            .fillMaxWidth()
            .then(
                if (Glass.on) {
                    Modifier.glassChip(shape, fill = if (hovered) 0.1f else 0.05f)
                } else {
                    Modifier.clip(shape).background(if (hovered) Palette.Hover else Palette.Content).border(1.dp, Palette.Line, shape)
                }
            )
            .hoverable(interaction)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = fit.search),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Search, null, Modifier.size(15.dp), tint = Palette.TextFaint)
        Spacer(Modifier.width(8.dp))
        Text(
            "Search everything",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.TextDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text("Ctrl+K", style = MaterialTheme.typography.labelSmall, color = Palette.TextFaint)
    }
}
