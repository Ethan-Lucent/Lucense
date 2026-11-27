package com.exo.musicplayer.desktop.ui

import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.LocalContextMenuRepresentation
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.HazeState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.exo.musicplayer.desktop.data.CollectionKind
import com.exo.musicplayer.desktop.data.DesktopController
import com.exo.musicplayer.desktop.library.DesktopTrack
import kotlinx.coroutines.delay
import java.io.File
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut

enum class Destination(val label: String, val icon: ImageVector) {
    LIBRARY("Library", Icons.Default.LibraryMusic),
    ALBUMS("Albums", Icons.Default.Album),
    ARTISTS("Artists", Icons.Default.Person),
    PLAYLISTS("Playlists", Icons.AutoMirrored.Filled.QueueMusic),
    DISCOVER("Discover", Icons.Default.Explore),
    IDENTIFY("Identify", Icons.Default.Fingerprint),
    MOODS("Moods", Icons.Default.Cloud),
    STATS("Stats", Icons.Default.BarChart),
    DOWNLOAD("Download", Icons.Default.Download);

    /** The cards this page shows, for Albums and Artists. */
    val collectionKind: CollectionKind?
        get() = when (this) {
            ALBUMS -> CollectionKind.ALBUMS
            ARTISTS -> CollectionKind.ARTISTS
            else -> null
        }
}

/**
 * The desktop shell: navigation rail, content pane, docked panel, transport bar.
 *
 * This is a desktop layout, not the phone's translated. Navigation is a
 * persistent left rail rather than bottom tabs, the library is a dense sortable
 * table rather than 72dp list rows, right-click does what right-click does on
 * Windows, and playback controls live in a fixed bar across the bottom — the
 * arrangement every desktop music player has converged on, because it survives a
 * 1400px-wide window where a phone layout does not.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DesktopApp(
    controller: DesktopController,
    onChooseFolder: () -> File?,
    onChooseMedia: () -> File?,
    onPickPlaylistFile: (save: Boolean, suggested: String) -> File?
) {
    // The page comes back as it was left. Download is the exception: it is a
    // place you go for a task, not one to land on when the window opens.
    var destination by remember {
        mutableStateOf(
            Destination.entries.firstOrNull { it.name == controller.settings.screen }
                ?.takeIf { it != Destination.DOWNLOAD }
                ?: Destination.LIBRARY
        )
    }
    LaunchedEffect(destination) { controller.settings.screen = destination.name }
    var showSettings by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<DialogKind?>(null) }
    var playlistTargets by remember { mutableStateOf<List<DesktopTrack>>(emptyList()) }
    // What each page saved when it was last left - see the page switch below.
    val pages = rememberSaveableStateHolder()

    // An album's or an artist's page, on whichever of Albums and Artists it belongs:
    // from its card, or from its name in the song list or in the bar below.
    val openGroup: (CollectionKind, String) -> Unit = { kind, key ->
        controller.openGroup(kind, key)
        destination = if (kind == CollectionKind.ALBUMS) Destination.ALBUMS else Destination.ARTISTS
        showSettings = false
    }

    // What is playing, not where in it: see collectNowPlaying.
    val status by controller.engine.collectNowPlaying()
    val visible = controller.visibleTracks

    // Starting the library's own music stops a preview, rather than playing over it.
    LaunchedEffect(status.playing) { if (status.playing) controller.stopPreview() }

    // Being on the Download page counts as having seen what finished, so the
    // Download item stops shining once you have been there.
    val onDownloadPage = !showSettings && destination == Destination.DOWNLOAD
    LaunchedEffect(onDownloadPage, controller.downloads) {
        if (onDownloadPage) controller.acknowledgeDownloads()
    }

    // Songs, folders and links dropped anywhere on the window.
    val dropTarget = remember(controller) {
        fileDropTarget(
            onHover = { controller.dropHover = it },
            onFiles = controller::importDropped,
            onText = controller::importDroppedText
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .dragAndDropTarget(shouldStartDragAndDrop = { true }, target = dropTarget)
    ) {
        // Liquid glass: panes that blur what is behind them, over a field of
        // the theme's colours or the wallpaper. Otherwise the solid layers.
        val glass = Glass.on
        val glassState = remember { HazeState() }
        if (glass) {
            GlassBackground(controller.wallpaper, controller.wallpaperDim, Modifier.matchParentSize().hazeSource(glassState))
        } else {
            Wallpaper(controller.wallpaper, controller.wallpaperDim, Modifier.matchParentSize())
        }
        // Every right-click menu, text fields' included, is Lucense's own (LucenseMenu.kt).
        CompositionLocalProvider(
            LocalGlass provides glassState.takeIf { glass },
            LocalContextMenuRepresentation provides LucenseContextMenu
        ) {
        // The window's width in the steps the layout changes at, so resizing it
        // rebuilds nothing until one is crossed (see LocalWindowWidth).
        val density = LocalDensity.current
        var windowWidth by remember { mutableStateOf(WINDOW_STEPS.last()) }
        Box(Modifier.fillMaxSize().onSizeChanged { windowWidth = with(density) { it.width.toDp() }.steppedAt(WINDOW_STEPS) }) {
        // Below this the sidebar goes down to its icons and the side panel narrows.
        val roomy = windowWidth >= 1100.dp
        CompositionLocalProvider(LocalWindowWidth provides windowWidth) {
        Column(
            Modifier
                .fillMaxSize()
                .background(if (glass || controller.wallpaper != null) Color.Transparent else Palette.Base)
                .padding(if (glass) PaddingValues(start = Glass.GAP, end = Glass.GAP, bottom = Glass.GAP) else PaddingValues())
        ) {
            // In place of Windows' own title bar: drag here, double-click to maximise.
            WindowTitleBar(background = if (glass) Color.Transparent else Palette.Base)
            Row(Modifier.weight(1f).fillMaxWidth()) {
                NavigationRail(
                    controller = controller,
                    compact = !roomy,
                    current = if (showSettings) null else destination,
                    settingsOpen = showSettings,
                    onSelect = {
                        // Its own item again goes back to the cards from an album or artist open there.
                        if (it == destination && !showSettings) it.collectionKind?.let { kind -> controller.openGroup(kind, null) }
                        destination = it
                        showSettings = false
                    },
                    onSettings = { showSettings = !showSettings },
                    bulkOpen = dialog == DialogKind.BULK,
                    onOpenBulk = { dialog = DialogKind.BULK }
                )

                if (glass) Spacer(Modifier.width(Glass.GAP))
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .then(
                            if (glass) {
                                Modifier.glassPane(LocalGlass.current, RoundedCornerShape(Glass.PANE), Palette.Content, 0.34f)
                            } else {
                                Modifier.background(Palette.Content.copy(alpha = if (controller.wallpaper != null) 0.3f else 1f))
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
                        ballCover = rememberBallCover(status.track, controller.ballCover),
                        modifier = Modifier.matchParentSize()
                    )
                    var paneWidth by remember { mutableStateOf(PANE_STEPS.last()) }
                    Box(Modifier.fillMaxSize().onSizeChanged { paneWidth = with(density) { it.width.toDp() }.steppedAt(PANE_STEPS) }) {
                    CompositionLocalProvider(LocalPaneWidth provides paneWidth) {
                    Column(Modifier.fillMaxSize()) {
                        // An album's or an artist's page has its own banner in place of the page's title.
                        val groupOpen = !showSettings &&
                            destination.collectionKind?.let { controller.openGroupKey(it) != null } == true
                        if (!groupOpen) ContentHeader(
                            controller = controller,
                            title = if (showSettings) "Settings" else destination.label,
                            destination = destination,
                            showSettings = showSettings,
                            shownCount = visible.size,
                            onAddFolder = { onChooseFolder()?.let(controller::addFolder) },
                            onBulk = { dialog = DialogKind.BULK },
                            onDuplicates = {
                                controller.findDuplicates()
                                dialog = DialogKind.DUPLICATES
                            }
                        )

                        ArchiveStrip(controller)

                        if (controller.scanning) {
                            LinearProgressIndicator(
                                Modifier.fillMaxWidth().height(2.dp),
                                color = Palette.Accent,
                                trackColor = Palette.Line
                            )
                        }

                        // Each page keeps its scroll position, and whatever else it saves,
                        // while another is shown. Leaving a page used to throw that away,
                        // so the song list was back at the top on every return to it.
                        Crossfade(if (showSettings) "settings" else destination.name, Modifier.fillMaxSize(), Motion.change(), label = "page") { page ->
                            val at = Destination.entries.firstOrNull { it.name == page }
                            pages.SaveableStateProvider(page) {
                            when {
                                page == "settings" -> SettingsScreen(controller, onChooseFolder)
                                at == Destination.LIBRARY ->
                                    LibraryPane(
                                        controller = controller,
                                        tracks = visible,
                                        nowPlaying = status.track,
                                        onAddFolder = { onChooseFolder()?.let(controller::addFolder) },
                                        onAddToPlaylist = { items ->
                                            playlistTargets = items
                                            controller.refreshPlaylists()
                                            dialog = DialogKind.ADD_TO_PLAYLIST
                                        },
                                        onIdentify = { track ->
                                            controller.identifyTarget = track
                                            destination = Destination.IDENTIFY
                                            controller.identifyFile(track)
                                        },
                                        onOpenGroup = openGroup,
                                        onGoTo = { destination = it }
                                    )
                                at == Destination.ALBUMS || at == Destination.ARTISTS ->
                                    CollectionScreen(
                                        controller = controller,
                                        kind = if (at == Destination.ALBUMS) {
                                            CollectionKind.ALBUMS
                                        } else {
                                            CollectionKind.ARTISTS
                                        },
                                        onMerge = { dialog = DialogKind.MERGE },
                                        onClearDuplicates = { within ->
                                            controller.findDuplicates(within)
                                            dialog = DialogKind.DUPLICATES
                                        },
                                        onOpenGroup = openGroup,
                                        onAddToPlaylist = { items ->
                                            playlistTargets = items
                                            controller.refreshPlaylists()
                                            dialog = DialogKind.ADD_TO_PLAYLIST
                                        }
                                    )
                                at == Destination.PLAYLISTS ->
                                    PlaylistsScreen(controller, onPickPlaylistFile)
                                at == Destination.DISCOVER ->
                                    DiscoverScreen(controller, onOpenSignIn = { destination = Destination.DOWNLOAD }) { destination = Destination.IDENTIFY }
                                at == Destination.IDENTIFY ->
                                    IdentifyScreen(controller, onChooseMedia)
                                at == Destination.MOODS -> MoodsScreen(controller)
                                at == Destination.STATS -> StatsScreen(controller)
                                at == Destination.DOWNLOAD ->
                                    DownloadScreen(controller, onChooseFolder)
                            }
                        }
                        }
                    }
                    }
                    }
                    // Whatever is being previewed, from Identify or Discover,
                    // with its pause and its X, in the corner of every page.
                    PreviewCard(controller, Modifier.align(Alignment.BottomEnd).padding(16.dp))
                }

                val panel = controller.sidePanel
                // The last panel open, so a closing one slides away showing what it was.
                val lastPanel = remember { arrayOfNulls<SidePanelKind>(1) }
                if (panel != null) lastPanel[0] = panel
                AnimatedVisibility(
                    visible = panel != null,
                    enter = expandHorizontally(Motion.slide(), expandFrom = Alignment.Start) + fadeIn(Motion.slide()),
                    exit = shrinkHorizontally(Motion.slide(), shrinkTowards = Alignment.Start) + fadeOut(Motion.slide())
                ) {
                    Row {
                    if (glass) {
                        Spacer(Modifier.width(Glass.GAP))
                    } else {
                        Box(Modifier.width(1.dp).fillMaxHeight().background(Palette.Line))
                    }
                    SidePanel(
                        kind = panel ?: lastPanel[0] ?: SidePanelKind.EFFECTS,
                        narrow = !roomy,
                        controller = controller,
                        track = status.track,
                        onClose = { controller.sidePanel = null }
                    )
                    }
                }
            }

            if (glass) Spacer(Modifier.height(Glass.GAP))
            TransportBar(
                controller = controller,
                track = status.track,
                isPlaying = status.playing,
                durationMs = status.durationMs,
                onOpenGroup = openGroup,
                loopStartMs = status.loopStartMs,
                loopEndMs = status.loopEndMs
            )
        }
        }
        }

        // Editing is driven off the controller rather than the local dialog
        // state, because it is opened from the row context menu and from the
        // Identify screen, and neither should have to know about the other.
        controller.editTarget?.let { track ->
            EditTrackDialog(controller, track) { controller.dismissEdit() }
        }

        if (controller.editMany.isNotEmpty()) {
            EditTracksDialog(controller, controller.editMany) { controller.dismissEditMany() }
        }

        if (controller.fixTargets.isNotEmpty()) {
            FixDialog(controller, controller.fixTargets) { controller.dismissFix() }
        }

        if (controller.showShortcuts) {
            ShortcutsDialog { controller.showShortcuts = false }
        }

        when (dialog) {
            DialogKind.BULK -> BulkToolsDialog(controller) { dialog = null }
            DialogKind.DUPLICATES -> DuplicatesDialog(controller) { dialog = null }
            DialogKind.MERGE -> MergeDialog(controller) { dialog = null }
            DialogKind.ADD_TO_PLAYLIST ->
                AddToPlaylistDialog(controller, playlistTargets) { dialog = null }
            null -> Unit
        }

        if (controller.paletteOpen) {
            CommandPalette(
                controller = controller,
                onGo = {
                    destination = it
                    showSettings = false
                },
                onSettings = { tab ->
                    tab?.let { controller.settingsTab = it }
                    showSettings = true
                },
                onOpenGroup = openGroup,
                onBulk = { dialog = DialogKind.BULK },
                onDuplicates = {
                    controller.findDuplicates()
                    dialog = DialogKind.DUPLICATES
                }
            )
        }

        if (controller.dropHover) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Palette.Base.copy(alpha = 0.82f))
                    .padding(28.dp)
                    .border(2.dp, Palette.Accent, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Drop to add to your library",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Palette.Text
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Songs are copied into your music folder, folders are added where " +
                            "they are, and links start downloading.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.TextDim
                    )
                }
            }
        }

        controller.dropNote?.let { note ->
            LaunchedEffect(note) {
                delay(5000)
                controller.dismissDropNote()
            }
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 96.dp)
                    .floating(10.dp)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(note, style = MaterialTheme.typography.bodyMedium, color = Palette.Text)
            }
        }
    }
        }
}

/**
 * Progress and outcome of a zip, under the toolbar.
 *
 * Shown here rather than in a dialog because archiving a large library takes a
 * while and there is no reason to block the app during it - you can carry on
 * browsing and come back when the path appears.
 */
@Composable
private fun ArchiveStrip(controller: DesktopController) {
    val note = controller.archiveNote
    val file = controller.archiveFile
    if (!controller.archiveRunning && note == null) return

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 10.dp)
            .card(8.dp)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Archive,
                null,
                Modifier.size(15.dp),
                tint = Palette.Accent
            )
            Spacer(Modifier.width(10.dp))
            Text(
                if (controller.archiveRunning) "Building archive" else "Archive ready",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Text,
                modifier = Modifier.weight(1f)
            )
            if (controller.archiveRunning) {
                GhostButton("Stop") { controller.cancelArchive() }
            } else {
                if (file != null) {
                    GhostButton("Open folder", icon = Icons.Default.FolderOpen) {
                        controller.revealArchive()
                    }
                    Spacer(Modifier.width(8.dp))
                }
                GhostButton("Dismiss") { controller.dismissArchive() }
            }
        }

        if (controller.archiveRunning) {
            Spacer(Modifier.height(9.dp))
            ThinProgress(controller.archiveProgress)
            Spacer(Modifier.height(6.dp))
            Text(
                controller.archiveCurrent,
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        note?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.Accent)
        }
        // The path is the point of the feature, so it is spelled out in full
        // rather than left for the user to go and find.
        file?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                it.absolutePath,
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextDim
            )
        }
    }
}
