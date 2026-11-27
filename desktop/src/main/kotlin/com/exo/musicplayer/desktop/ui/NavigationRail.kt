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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
    onSettings: () -> Unit
) {
    Box(
        Modifier
            .width(if (compact) 60.dp else 212.dp)
            .fillMaxHeight()
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
        Column(Modifier.fillMaxSize().padding(vertical = 14.dp)) {
            Row(
                Modifier.padding(horizontal = if (compact) 25.dp else 18.dp, vertical = 8.dp).height(28.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(Palette.Accent))
                if (!compact) {
                    Spacer(Modifier.width(10.dp))
                    Text("Lucense", style = MaterialTheme.typography.titleLarge, color = Palette.Text)
                }
            }

            Spacer(Modifier.height(10.dp))
            RailSearch(compact) { controller.openPalette() }
            Spacer(Modifier.height(8.dp))
            railGroups().forEachIndexed { index, group ->
                if (index > 0) RailDivider()
                // Starts as it was left, and open when you are on one of its pages or
                // have asked for the advanced options; the heading still folds it.
                var open by remember { mutableStateOf(controller.railMoreOpen || current in group.pages || Advanced.on) }
                if (group.foldable) {
                    LaunchedEffect(current, Advanced.on) {
                        if (current in group.pages || Advanced.on) open = true
                    }
                }
                if (!compact && group.label != null) {
                    if (group.foldable) {
                        RailFold(group.label, open) {
                            open = !open
                            controller.railMoreOpen = open
                        }
                    } else {
                        RailLabel(group.label)
                    }
                }
                // The narrow rail has no headings to fold behind, so it shows every page.
                if (compact || !group.foldable || open) {
                    group.pages.forEach { entry ->
                        if (entry == Destination.DOWNLOAD) {
                            val downloads = controller.downloads
                            RailItem(
                                label = entry.label,
                                icon = entry.icon,
                                selected = entry == current,
                                badge = downloads.count { !it.done },
                                shine = controller.unseenFinishedDownloads > 0,
                                compact = compact
                            ) { onSelect(entry) }
                            if (!compact) RailDownloads(downloads) { onSelect(Destination.DOWNLOAD) }
                        } else {
                            RailItem(entry.label, entry.icon, entry == current, compact = compact) { onSelect(entry) }
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))
            RailItem("Settings", Icons.Default.Settings, settingsOpen, compact = compact, onClick = onSettings)
            Spacer(Modifier.height(10.dp))
        }
    }
}

/** A thin line between groups of pages. */
@Composable
private fun RailDivider() {
    Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().height(1.dp).background(Palette.Line))
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
    /** A line on what the page is for, in the hover note; see [Noted]. */
    note: String? = null,
    onClick: () -> Unit
) = Noted(if (compact) label else null, note, iconOnly = compact) {
    RailRow(label, icon, selected, badge, shine, compact, onClick)
}

@Composable
private fun RailRow(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    badge: Int,
    shine: Boolean,
    compact: Boolean,
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
                .padding(horizontal = 10.dp, vertical = 9.dp),
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
private fun RailSearch(compact: Boolean, onClick: () -> Unit) {
    if (compact) {
        RailItem("Search everything  Ctrl+K", Icons.Default.Search, selected = false, compact = true, onClick = onClick)
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
            .padding(horizontal = 10.dp, vertical = 7.dp),
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
