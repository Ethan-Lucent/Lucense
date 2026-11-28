package com.exo.musicplayer.desktop.ui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.onClick
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.QueuePlayNext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Icon
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
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.exo.musicplayer.desktop.data.CollectionKind
import com.exo.musicplayer.desktop.data.DesktopController
import com.exo.musicplayer.desktop.data.SortMode
import com.exo.musicplayer.desktop.library.DesktopTrack
import com.exo.musicplayer.desktop.system.Explorer
import com.exo.musicplayer.util.asDuration
import java.io.File
import java.util.Locale
import com.exo.musicplayer.desktop.system.Platform

/** What a new person can do before there are any files: three ways to get music, each a click to the page for it. */
@Composable
internal fun FirstRunCards(onGoTo: (Destination) -> Unit) {
    Row(
        Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        FirstRunCard(
            Modifier.weight(1f), Destination.DOWNLOAD.icon, "Download a song",
            "Paste a link from YouTube, SoundCloud or Bandcamp."
        ) { onGoTo(Destination.DOWNLOAD) }
        FirstRunCard(
            Modifier.weight(1f), Destination.DISCOVER.icon, "Discover music",
            "Wander through genres and artists you might like."
        ) { onGoTo(Destination.DISCOVER) }
        FirstRunCard(
            Modifier.weight(1f), Destination.IDENTIFY.icon, "Identify a song",
            "Find out what a song is by name, lyrics, a file or a link."
        ) { onGoTo(Destination.IDENTIFY) }
    }
}

@Composable
private fun FirstRunCard(modifier: Modifier, icon: ImageVector, title: String, line: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Panel(
        modifier
            .hoverable(interaction)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = if (hovered) Palette.Accent else Palette.TextDim)
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = Palette.Text)
        Spacer(Modifier.height(2.dp))
        Text(line, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
    }
}

@Composable
internal fun LibraryPane(
    controller: DesktopController,
    tracks: List<DesktopTrack>,
    nowPlaying: DesktopTrack?,
    onAddFolder: () -> Unit,
    onAddToPlaylist: (List<DesktopTrack>) -> Unit,
    onIdentify: (DesktopTrack) -> Unit,
    onOpenGroup: (CollectionKind, String) -> Unit,
    onGoTo: (Destination) -> Unit
) {
    if (controller.tracks.isEmpty() && !controller.scanning) {
        // Scrolls, so the cards under the button are still reachable in a short window.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                EmptyState(
                    icon = Icons.Default.FolderOpen,
                    title = "No music yet",
                    body = "Point Lucense at a folder and it reads what's inside.\n" +
                        "Your files stay exactly where they are.",
                    action = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            AccentButton("Choose folder", onClick = onAddFolder)
                            Spacer(Modifier.height(28.dp))
                            Text(
                                "Or start without any files",
                                style = MaterialTheme.typography.titleSmall,
                                color = Palette.TextDim
                            )
                            Spacer(Modifier.height(10.dp))
                            FirstRunCards(onGoTo)
                        }
                    }
                )
            }
        }
        return
    }

    // Ctrl and Shift are read at click time rather than tracked, because a
    // modifier held down between compositions is not an event.
    val windowInfo = LocalWindowInfo.current

    Column(Modifier.fillMaxSize()) {
        // Said once, to someone new; an existing install starts with it seen (tipsSeenDefault).
        if (!controller.tipsSeen) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Tip: press Ctrl+K to find anything, and right-click a song for more.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Accent,
                    modifier = Modifier.weight(1f)
                )
                GhostButton("Got it") { controller.tipsSeen = true }
            }
        }
        if (controller.hasSelection) {
            SelectionBar(controller, tracks)
        }
        controller.revertNote?.let { note ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Accent,
                    modifier = Modifier.weight(1f)
                )
                GhostButton("Dismiss") { controller.dismissRevertNote() }
            }
        }
        controller.queueNote?.let { note ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Accent,
                    modifier = Modifier.weight(1f)
                )
                GhostButton("Show queue") { controller.togglePanel(SidePanelKind.QUEUE) }
                Spacer(Modifier.width(6.dp))
                GhostButton("Dismiss") { controller.dismissQueueNote() }
            }
        }
        controller.selectionNote?.let { note ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Accent,
                    modifier = Modifier.weight(1f)
                )
                GhostButton("Dismiss") { controller.dismissSelectionNote() }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 32.dp, bottom = 8.dp)
        ) {
            HeaderCell("#", Modifier.width(40.dp))
            Spacer(Modifier.width(46.dp))
            HeaderCell("Title", Modifier.weight(2.2f))
            HeaderCell("Artist", Modifier.weight(1.4f))
            if (LocalPaneWidth.current >= 640.dp) HeaderCell("Album", Modifier.weight(1.4f))
            HeaderCell(controller.sort.trailingColumn, Modifier.width(100.dp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Line))

        val listState = rememberLazyListState()
        // Asked for by the J key or the crosshair button. A library of four
        // thousand songs puts the one playing a very long way off screen.
        LaunchedEffect(controller.jumpRequest) {
            if (controller.jumpRequest == 0) return@LaunchedEffect
            val at = tracks.indexOfFirst { it.file == nowPlaying?.file }
            if (at >= 0) listState.animateScrollToItem(at)
        }
        // A scrollbar to drag, and middle-click scrolling, as Windows lists have.
        ScrollingList(listState, Modifier.fillMaxSize()) { fill ->
        LazyColumn(state = listState, modifier = fill) {
            itemsIndexed(tracks, key = { _, t -> t.file.absolutePath }) { index, track ->
                TrackRow(
                    index = index + 1,
                    track = track,
                    controller = controller,
                    isCurrent = track.file == nowPlaying?.file,
                    isFavourite = track.file.absolutePath in controller.favourites,
                    isSelected = track.file.absolutePath in controller.selectedPaths,
                    onPlay = {
                        // A plain click plays, and drops any selection - leaving
                        // one active behind the thing you just started would be a trap.
                        controller.clearSelection()
                        controller.play(track, tracks)
                    },
                    onAddToPlaylist = { onAddToPlaylist(listOf(track)) },
                    onIdentify = { onIdentify(track) },
                    onToggleSelect = { controller.toggleSelection(track) },
                    onExtendSelect = { controller.extendSelection(track, tracks) },
                    onOpenGroup = onOpenGroup
                )
            }
        }
        }
    }
}

/** The right-hand column swaps to whatever the current sort is about. */
private val SortMode.trailingColumn: String
    get() = when (this) {
        SortMode.PLAYS -> "Plays"
        SortMode.LISTEN_TIME -> "Listened"
        SortMode.RATING -> "Rating"
        SortMode.YEAR -> "Year"
        else -> "Time"
    }

@Composable
private fun HeaderCell(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(Locale.getDefault()),
        style = MaterialTheme.typography.labelSmall,
        color = Palette.TextFaint,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
    )
}

/**
 * The right-click menu of a song in the library: what is done to nearly every
 * song first, in one short list, and the rest under "More" - cutting it,
 * naming it from its sound, finding the file, undoing what Lucense changed.
 * Delete stays last, out of the way of the rows used most. Pure on purpose:
 * the rows and their order can be checked without a window.
 */
internal fun songMenu(
    favourite: Boolean,
    rating: Int,
    canRevert: Boolean,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onToggleFavourite: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onEdit: () -> Unit,
    onRate: (Int) -> Unit,
    onTrim: () -> Unit,
    onIdentify: () -> Unit,
    onShowInFolder: () -> Unit,
    onRevert: () -> Unit,
    onDelete: () -> Unit
): List<ContextMenuItem> = listOf(
    ContextMenuItem("Play") { onPlay() },
    NotedMenuItem("Play next", Notes.PLAY_NEXT) { onPlayNext() },
    NotedMenuItem("Add to queue", Notes.ADD_TO_QUEUE) { onAddToQueue() },
    ContextMenuItem(if (favourite) "Remove from favourites" else "Add to favourites") { onToggleFavourite() },
    NotedMenuItem("Add to playlist...", Notes.ADD_TO_PLAYLIST) { onAddToPlaylist() },
    RatingMenuItem(rating) { onRate(it) },
    NotedMenuItem("Edit details...", Notes.EDIT_DETAILS) { onEdit() },
    SubmenuItem(
        "More",
        listOf(
            NotedMenuItem("Trim...", Notes.TRIM) { onTrim() },
            NotedMenuItem("Identify this track", Notes.IDENTIFY) { onIdentify() },
            ContextMenuItem(Platform.showInFolder) { onShowInFolder() }
        ) + if (canRevert) {
            // Only where Lucense has changed the song's details, as before.
            listOf(NotedMenuItem("Revert to the file's own details", Notes.REVERT) { onRevert() })
        } else {
            emptyList()
        }
    ),
    NotedMenuItem("Delete (move to ${Platform.trashName})", Notes.DELETE) { onDelete() }
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackRow(
    index: Int,
    track: DesktopTrack,
    controller: DesktopController,
    isCurrent: Boolean,
    isFavourite: Boolean,
    isSelected: Boolean,
    onPlay: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onIdentify: () -> Unit,
    onToggleSelect: () -> Unit = {},
    onExtendSelect: () -> Unit = {},
    /** Goes to an album's or an artist's page, from its name in the row. */
    onOpenGroup: (CollectionKind, String) -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    // Right-click opens Lucense's own menu (LucenseContextMenu, provided at the
    // root), which keeps the platform menu's habits - opens at the pointer,
    // arrows and Enter - and can hold the rating as one slider.
    ContextMenuArea(
        items = {
            songMenu(
                favourite = isFavourite,
                rating = controller.ratingOf(track),
                canRevert = controller.canRevert(track),
                onPlay = onPlay,
                onPlayNext = { controller.playNext(listOf(track)) },
                onAddToQueue = { controller.addToQueue(listOf(track)) },
                onToggleFavourite = { controller.toggleFavourite(track) },
                onAddToPlaylist = onAddToPlaylist,
                onEdit = { controller.editTarget = track },
                onRate = { controller.setRating(track, it) },
                onTrim = { controller.trimming = track },
                onIdentify = onIdentify,
                onShowInFolder = { revealInExplorer(track.file) },
                onRevert = { controller.revertToOriginal(listOf(track)) },
                onDelete = { controller.deleteTracks(listOf(track)) }
            )
        }
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    when {
                        // Selection outranks "now playing" here: while a
                        // selection exists it is the thing being acted on.
                        isSelected -> Palette.Accent.copy(alpha = 0.20f)
                        isCurrent -> Palette.Selected
                        hovered -> Palette.Hover
                        else -> Color.Transparent
                    }
                )
                .hoverable(interaction)
                // Dragged out of the window, the song goes as its file - or, from
                // a picked row, every picked song does.
                .dragOutFiles {
                    if (isSelected) controller.selectedPaths.map(::File) else listOf(track.file)
                }
                // Ctrl and Shift are read from the click itself. They used to come
                // from the window's keyboard state, which a Ctrl+click did not
                // reliably reach, so it was taken for a plain click: it played the
                // track and dropped the selection instead of adding to it.
                .onClick(keyboardModifiers = { isCtrlPressed }, onClick = onToggleSelect)
                .onClick(keyboardModifiers = { isShiftPressed && !isCtrlPressed }, onClick = onExtendSelect)
                .onClick(keyboardModifiers = { !isCtrlPressed && !isShiftPressed }, onClick = onPlay)
                .padding(start = 24.dp, end = 32.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.width(40.dp)) {
                // The row number gives way to a play affordance on hover, which
                // is how desktop players signal "click here" without a button.
                if (isSelected) {
                    Icon(
                        Icons.Default.Check,
                        null,
                        Modifier.size(15.dp),
                        tint = Palette.Accent
                    )
                } else if (hovered) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(15.dp), tint = Palette.Text)
                } else {
                    Text(
                        index.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isCurrent) Palette.Accent else Palette.TextFaint
                    )
                }
            }
            Artwork(track, 34.dp)
            Spacer(Modifier.width(12.dp))
            Cell(
                track.title,
                Modifier.weight(2.2f),
                if (isCurrent) Palette.Accent else Palette.Text
            )
            // The names go to their pages; the rest of the row still plays the song.
            LinkedCell(artistLinks(track) { onOpenGroup(CollectionKind.ARTISTS, it) }, Modifier.weight(1.4f), Palette.TextDim)
            if (LocalPaneWidth.current >= 640.dp) {
                LinkedCell(albumLink(track) { onOpenGroup(CollectionKind.ALBUMS, it) }, Modifier.weight(1.4f), Palette.TextDim)
            }

            Row(
                Modifier.width(100.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (hovered || isFavourite) {
                    Box(
                        Modifier
                            .size(20.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .clickable { controller.toggleFavourite(track) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (isFavourite) {
                                Icons.Default.Favorite
                            } else {
                                Icons.Default.FavoriteBorder
                            },
                            "Favourite",
                            Modifier.size(13.dp),
                            tint = if (isFavourite) Palette.Accent else Palette.TextFaint
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                }
                if (hovered) {
                    // On hover only: a delete button showing on every row would be
                    // a hazard in a long list. It goes to the Recycle Bin anyway.
                    Box(
                        Modifier
                            .size(20.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .clickable { controller.deleteTracks(listOf(track)) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            "Move to ${Platform.trashName}",
                            Modifier.size(14.dp),
                            tint = Palette.TextFaint
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    trailingValue(controller, track),
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextFaint,
                    maxLines = 1
                )
            }
        }
    }
}

private fun trailingValue(controller: DesktopController, track: DesktopTrack): String =
    when (controller.sort) {
        SortMode.PLAYS -> controller.playCountOf(track).toString()
        SortMode.LISTEN_TIME -> controller.listenedMsOf(track).asDuration()
        SortMode.RATING -> "★".repeat(controller.ratingOf(track)).ifEmpty { "—" }
        SortMode.YEAR -> track.year?.toString() ?: "—"
        else -> track.durationMs.asDuration()
    }

@Composable
private fun Cell(text: String, modifier: Modifier, colour: Color) = Cell(AnnotatedString(text), modifier, colour)

@Composable
private fun Cell(text: AnnotatedString, modifier: Modifier, colour: Color) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = colour,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(end = 16.dp)
    )
}

/** A cell of names that open their pages ([LinkedText]), laid out as [Cell]. */
@Composable
private fun LinkedCell(links: NameLinks, modifier: Modifier, colour: Color) =
    LinkedText(links, MaterialTheme.typography.bodyMedium, colour, modifier.padding(end = 16.dp))

private fun revealInExplorer(file: File) = Explorer.reveal(file)

/**
 * Actions for a multi-track selection.
 *
 * Ctrl+click to add one, Shift+click for a run, Ctrl+A for everything on
 * screen, Escape to drop it - the shortcuts a Windows user already has in their
 * fingers, so the bar states them rather than teaching them.
 */
@Composable
private fun SelectionBar(controller: DesktopController, visible: List<DesktopTrack>) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Palette.Selected)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "${controller.selectedPaths.size} selected",
            style = MaterialTheme.typography.titleMedium,
            color = Palette.Text
        )
        Spacer(Modifier.width(16.dp))

        // Nine buttons do not fit beside each other once a side panel is open,
        // and a Row answers that by squeezing its children until their labels
        // wrap a letter at a time. The same answer as the sort chips above:
        // the middle scrolls, and the two that must always be reachable sit
        // outside it where they keep their own width. The click hints that used
        // to live here are in the shortcut list on the / key now.
        Row(
            Modifier.weight(1f).fadingHorizontalScroll(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GhostButton("Play", icon = Icons.Default.PlayArrow, note = Notes.SEL_PLAY) {
                controller.playSelection(visible)
            }
            Spacer(Modifier.width(6.dp))
            GhostButton("Play next", icon = Icons.Default.QueuePlayNext, note = Notes.PLAY_NEXT) {
                controller.playNext(controller.selectedTracks(visible))
            }
            Spacer(Modifier.width(6.dp))
            GhostButton("Queue", icon = Icons.Default.PlaylistAdd, note = Notes.ADD_TO_QUEUE) {
                controller.addToQueue(controller.selectedTracks(visible))
            }
            Spacer(Modifier.width(14.dp))
            GhostButton("Favourite", icon = Icons.Default.FavoriteBorder, note = Notes.SEL_FAVOURITE) {
                controller.favouriteSelection(visible)
            }
            Spacer(Modifier.width(6.dp))
            GhostButton("Edit details", icon = Icons.Default.Edit, note = Notes.BULK) {
                controller.editSelection(visible)
            }
            Spacer(Modifier.width(6.dp))
            GhostButton("Fix", icon = Icons.Default.AutoFixHigh, note = Notes.SEL_FIX) {
                controller.fixSelection(visible)
            }
            Spacer(Modifier.width(14.dp))
            GhostButton("Revert details", icon = Icons.Default.Undo, note = Notes.REVERT) {
                controller.revertToOriginal(controller.selectedTracks(visible))
            }
            Spacer(Modifier.width(6.dp))
            GhostButton(Platform.showInFolder, icon = Icons.Default.FolderOpen, note = Notes.SEL_SHOW) {
                controller.revealSelection(visible)
            }
            Spacer(Modifier.width(6.dp))
            GhostButton("Zip and ship", icon = Icons.Default.Archive, note = Notes.ZIP_AND_SHIP) {
                controller.zipSelection(visible)
            }
            Spacer(Modifier.width(6.dp))
        }
        Spacer(Modifier.width(10.dp))
        GhostButton("Delete", icon = Icons.Default.Delete, note = Notes.DELETE) {
            controller.deleteSelection(visible)
        }
        Spacer(Modifier.width(6.dp))
        GhostButton("Clear", note = Notes.SEL_CLEAR) { controller.clearSelection() }
    }
}
