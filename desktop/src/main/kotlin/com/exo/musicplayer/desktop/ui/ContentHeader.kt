package com.exo.musicplayer.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.onClick
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.exo.musicplayer.desktop.data.CollectionKind
import com.exo.musicplayer.desktop.data.DesktopController
import com.exo.musicplayer.desktop.data.SortMode
import kotlinx.coroutines.delay

@Composable
internal fun ContentHeader(
    controller: DesktopController,
    title: String,
    destination: Destination,
    showSettings: Boolean,
    shownCount: Int,
    onAddFolder: () -> Unit,
    onBulk: () -> Unit,
    onDuplicates: () -> Unit
) {
    val subtitle = when {
        showSettings -> "Folders, downloads and weather"
        controller.scanning -> "Scanning — ${controller.scanned} files"
        destination == Destination.LIBRARY && controller.tracks.isEmpty() ->
            "No folders added yet"
        destination == Destination.LIBRARY && controller.query.isNotBlank() ->
            "$shownCount of ${controller.tracks.size} tracks"
        destination == Destination.LIBRARY ->
            "${controller.tracks.size} tracks · ${controller.folders.size} folders"
        destination == Destination.DISCOVER -> "Genres to wander, artists to follow, and what's new"
        destination == Destination.IDENTIFY -> "Seven catalogues, plus fingerprinting"
        destination == Destination.DOWNLOAD -> "YouTube, SoundCloud, Bandcamp, Spotify"
        destination == Destination.MOODS -> "Songs that match the weather"
        destination == Destination.ALBUMS || destination == Destination.ARTISTS ->
            controller.mergeNote
                ?: if (controller.merging) {
                    "Merging…"
                } else {
                    "Ctrl+click to pick several, then merge them"
                }
        else -> "${controller.tracks.size} tracks"
    }
    // A page narrower than this has its header's buttons as icons.
    val narrowHeader = LocalPaneWidth.current < 860.dp

    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Palette.Text,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if ((destination == Destination.ALBUMS || destination == Destination.ARTISTS) && !showSettings) {
                val kind = if (destination == Destination.ALBUMS) CollectionKind.ALBUMS else CollectionKind.ARTISTS
                TextInput(
                    value = controller.collectionQuery(kind),
                    onValueChange = { controller.searchCollection(kind, it) },
                    placeholder = if (kind == CollectionKind.ALBUMS) "Search albums" else "Search artists",
                    search = true,
                    leading = Icons.Default.Search,
                    modifier = Modifier.width(if (narrowHeader) 170.dp else 240.dp)
                )
            }

            if (destination == Destination.LIBRARY && !showSettings) {
                TextInput(
                    value = controller.query,
                    onValueChange = { controller.query = it },
                    placeholder = if (narrowHeader) "Search" else "Search · artist: album: fav:",
                    search = true,
                    leading = Icons.Default.Search,
                    modifier = Modifier.width(if (narrowHeader) 170.dp else 240.dp)
                )
                Spacer(Modifier.width(10.dp))
                IconButton(onClick = { controller.jumpToNowPlaying() }) {
                    Icon(
                        Icons.Default.MyLocation, "Jump to the song playing",
                        tint = Palette.TextDim, modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(onClick = { controller.rescan() }) {
                    Icon(
                        Icons.Default.Refresh, "Rescan",
                        tint = Palette.TextDim, modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(onClick = onAddFolder) {
                    Icon(
                        Icons.Default.CreateNewFolder, "Add folder",
                        tint = Palette.TextDim, modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(6.dp))
                if (narrowHeader) {
                    IconGhostButton("Music folder", Icons.Default.FolderOpen) { controller.openMusicFolder() }
                } else {
                    GhostButton("Music folder", icon = Icons.Default.FolderOpen) {
                        controller.openMusicFolder()
                    }
                }
            }
        }

        // Kept after a pause rather than on every keystroke, so the list
        // holds searches and not the first three letters of one.
        LaunchedEffect(controller.query) {
            val text = controller.query
            if (text.isNotBlank()) {
                delay(1_200)
                controller.rememberSearch(text)
            }
        }

        if (destination == Destination.LIBRARY && !showSettings &&
            controller.query.isBlank() && controller.searchHistory.isNotEmpty()
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, bottom = 10.dp)
                    .fadingHorizontalScroll(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Searched before",
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.TextFaint
                )
                Spacer(Modifier.width(8.dp))
                controller.searchHistory.forEach { past ->
                    GhostButton(past) { controller.query = past }
                    Spacer(Modifier.width(6.dp))
                }
                GhostButton("Forget") { controller.forgetSearches() }
            }
        }

        if (destination == Destination.LIBRARY && !showSettings && controller.tracks.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Seven sort options, a filter and two buttons do not fit
                // across the content pane once a side panel is open, and a Row
                // resolves that by crushing its children - which turned "Bulk
                // tools" into one letter per line. The sort chips scroll
                // instead, and the buttons sit outside the weighted region so
                // they keep their intrinsic width whatever else happens.
                Row(
                    Modifier.weight(1f).fadingHorizontalScroll(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SegmentedRow(
                        options = SortMode.entries,
                        selected = controller.sort,
                        label = { it.label },
                        onSelect = { controller.sort = it }
                    )
                    Spacer(Modifier.width(10.dp))
                    FilterToggle(
                        label = "Favourites",
                        active = controller.favouritesOnly
                    ) { controller.favouritesOnly = !controller.favouritesOnly }
                }
                // Outside the scrolling part, so a chip cut off by a narrow
                // window fades out short of the buttons instead of running
                // under the first of them.
                Spacer(Modifier.width(10.dp))

                if (narrowHeader) {
                    IconGhostButton("Bulk tools", Icons.Default.Tune, onClick = onBulk)
                    Spacer(Modifier.width(6.dp))
                    IconGhostButton("Duplicates", Icons.Default.ContentCopy, onClick = onDuplicates)
                    Spacer(Modifier.width(6.dp))
                    IconGhostButton("Zip and ship", Icons.Default.Archive, enabled = !controller.archiveRunning) {
                        controller.zipLibrary()
                    }
                } else {
                    GhostButton("Bulk tools", icon = Icons.Default.Tune, onClick = onBulk)
                    Spacer(Modifier.width(8.dp))
                    GhostButton(
                        "Duplicates",
                        icon = Icons.Default.ContentCopy,
                        onClick = onDuplicates
                    )
                    Spacer(Modifier.width(8.dp))
                    GhostButton(
                        "Zip and ship",
                        enabled = !controller.archiveRunning,
                        icon = Icons.Default.Archive
                    ) { controller.zipLibrary() }
                }
            }
        }
    }
}

@Composable
private fun FilterToggle(label: String, active: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(
                when {
                    active -> Palette.Selected
                    hovered -> Palette.Hover
                    else -> Palette.Content
                }
            )
            .hoverable(interaction)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (active) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            null,
            Modifier.size(13.dp),
            tint = if (active) Palette.Accent else Palette.TextDim
        )
        Spacer(Modifier.width(7.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (active) Palette.Text else Palette.TextDim
        )
    }
}
