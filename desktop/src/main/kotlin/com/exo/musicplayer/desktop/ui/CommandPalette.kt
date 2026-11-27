package com.exo.musicplayer.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FilterNone
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.exo.musicplayer.data.NameSearch
import com.exo.musicplayer.data.download.LinkResolver
import com.exo.musicplayer.data.library.AutoPlaylist
import com.exo.musicplayer.data.playlist.PlaylistLinks
import com.exo.musicplayer.desktop.data.CollectionKind
import com.exo.musicplayer.desktop.data.DesktopController
import com.exo.musicplayer.desktop.data.IdentifyMode
import com.exo.musicplayer.desktop.data.TrackGroups
import java.net.URI

/** The palette's groups, in the order they are shown when equally good. */
internal enum class PaletteSection(val label: String) {
    LINK("This link"),
    ACTIONS("Actions"),
    PAGES("Pages"),
    PLAYLISTS("Playlists"),
    ALBUMS("Albums"),
    ARTISTS("Artists"),
    SONGS("Songs"),
    SEARCH("Elsewhere")
}

/** One row of the palette: what it says, what key does the same, and what Enter does. */
internal class PaletteItem(
    val section: PaletteSection,
    val title: String,
    val subtitle: String = "",
    val icon: ImageVector,
    val hint: String = "",
    /** Other words it is found by: "pause" finds Play or pause. */
    val words: String = "",
    val run: () -> Unit = {}
) {
    // Folded once, on first search, so typing doesn't redo 10,000 songs' accents a key.
    internal val foldedTitle: String by lazy { NameSearch.fold(title) }
    internal val foldedAll: String by lazy { foldedTitle + " " + NameSearch.fold("$subtitle $words") }
}

/**
 * The palette's ranking, apart from the screen so it can be tested.
 *
 * Every word typed has to be in the row somewhere, in any order and with or
 * without accents, as on the album and artist pages ([NameSearch]). Then the
 * title decides how good a match it is: the whole title, then its start, then
 * the start of a word in it, then anywhere. Groups come in the order of their
 * best row, and actions and pages before songs when that is a tie.
 */
internal object PaletteSearch {

    /** More songs than this is a list, which the library page is better at. */
    const val SONG_CAP = 25
    const val GROUP_CAP = 8

    private val SPACES = Regex("\\s+")

    /**
     * What the empty box offers, in this order: the few things most often wanted,
     * rather than the whole list. Shuffle is named by what it will do next, so it
     * is matched on its first word. Typing still searches everything.
     */
    private val STARTERS = listOf(
        "Play or pause", "Next song", "Previous song", "Shuffle",
        "Library", "Playlists", "Discover", "Identify", "Download", "Settings"
    )
    const val TIP = "Type to find songs, albums, artists, settings and more"

    /** 0 for no match; higher is better. */
    fun score(query: String, item: PaletteItem): Int {
        val folded = NameSearch.fold(query).trim().replace(SPACES, " ")
        if (folded.isEmpty()) return 0
        return score(folded, folded.split(' '), item)
    }

    private fun score(folded: String, words: List<String>, item: PaletteItem): Int {
        val all = item.foldedAll
        if (!words.all { it in all }) return 0
        val title = item.foldedTitle
        return when {
            title == folded -> 4
            title.startsWith(folded) -> 3
            title.contains(" $folded") || words.all { title.startsWith(it) || title.contains(" $it") } -> 2
            else -> 1
        }
    }

    /** [items] for [query]: an empty one gives the short starter list and a line on typing, and no songs. */
    fun rank(query: String, items: List<PaletteItem>): List<PaletteItem> {
        val folded = NameSearch.fold(query).trim().replace(SPACES, " ")
        if (folded.isEmpty()) {
            val starters = STARTERS.mapNotNull { name ->
                items.firstOrNull {
                    (it.section == PaletteSection.ACTIONS || it.section == PaletteSection.PAGES) &&
                        (it.title == name || (name == "Shuffle" && it.title.startsWith("Shuffle ")))
                }
            }
            return starters + PaletteItem(PaletteSection.SEARCH, TIP, icon = Icons.Default.Search)
        }
        val words = folded.split(' ')
        val bySection = HashMap<PaletteSection, MutableList<Pair<PaletteItem, Int>>>()
        for (item in items) {
            val s = score(folded, words, item)
            if (s > 0) bySection.getOrPut(item.section) { ArrayList() } += item to s
        }
        return bySection.entries
            .map { (section, found) ->
                val cap = when (section) {
                    PaletteSection.SONGS -> SONG_CAP
                    PaletteSection.ALBUMS, PaletteSection.ARTISTS, PaletteSection.PLAYLISTS -> GROUP_CAP
                    else -> Int.MAX_VALUE
                }
                // sortedByDescending is stable: equal matches stay in the library's order.
                section to found.sortedByDescending { it.second }.take(cap)
            }
            .sortedWith(compareByDescending<Pair<PaletteSection, List<Pair<PaletteItem, Int>>>> { it.second.first().second }
                .thenBy { it.first.ordinal })
            .flatMap { (_, found) -> found.map { it.first } }
    }
}

/** What can be done with a link, most likely first. */
internal enum class LinkAction { DOWNLOAD, DOWNLOAD_PLAYLIST, MAKE_PLAYLIST, IDENTIFY }

/** Telling what a pasted or typed link is, and so what to offer for it. */
internal object PaletteLinks {

    private val LIST_ID = Regex("""[?&]list=([\w-]+)""")

    /** [text] as a web link, trimmed; null when it is anything else. */
    fun urlOf(text: String?): String? {
        val link = text?.trim().orEmpty()
        if (link.isEmpty() || link.any(Char::isWhitespace)) return null
        if (!link.startsWith("http://", ignoreCase = true) && !link.startsWith("https://", ignoreCase = true)) return null
        val host = runCatching { URI(link).host }.getOrNull()
        return link.takeIf { !host.isNullOrBlank() }
    }

    /** Whether [url] is a whole playlist or album that the Playlists page can read. */
    fun isList(url: String): Boolean {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        val path = uri.path.orEmpty().lowercase().split('/').filter { it.isNotEmpty() }
        return when (PlaylistLinks.serviceOf(url)) {
            PlaylistLinks.Service.SPOTIFY -> "playlist" in path || "album" in path
            // An Apple Music song's link is its album's with ?i= on the end.
            PlaylistLinks.Service.APPLE_MUSIC ->
                "playlist" in path || ("album" in path && uri.query.orEmpty().split('&').none { it.startsWith("i=") })
            PlaylistLinks.Service.YOUTUBE -> LinkResolver.isYouTubePlaylist(url, wholeList = false)
            PlaylistLinks.Service.SOUNDCLOUD -> "sets" in path
            else -> false
        }
    }

    /** A YouTube video played from inside a playlist: one song, or the whole list. */
    fun isVideoInList(url: String): Boolean =
        LinkResolver.isYouTubePlaylist(url, wholeList = true) && !LinkResolver.isYouTubePlaylist(url, wholeList = false)

    fun actionsFor(url: String): List<LinkAction> = when {
        isList(url) -> listOf(LinkAction.MAKE_PLAYLIST, LinkAction.DOWNLOAD, LinkAction.IDENTIFY)
        isVideoInList(url) -> listOf(LinkAction.DOWNLOAD, LinkAction.DOWNLOAD_PLAYLIST, LinkAction.MAKE_PLAYLIST, LinkAction.IDENTIFY)
        else -> listOf(LinkAction.DOWNLOAD, LinkAction.IDENTIFY)
    }

    /**
     * Just the video, its list left off. Download's "whole playlist" setting
     * would otherwise decide, and "Download it" next to "Download the whole
     * playlist" should mean the one song whatever that is set to.
     */
    fun singleVideo(url: String): String {
        val link = url.trim()
        val at = link.indexOf('?')
        if (at < 0) return link
        val kept = link.substring(at + 1).split('&').filterNot {
            val name = it.substringBefore('=')
            name == "list" || name == "index" || name == "start_radio" || name == "pp"
        }
        return link.substring(0, at) + if (kept.isEmpty()) "" else "?" + kept.joinToString("&")
    }

    /** The playlist itself, which is always downloaded whole, again whatever the setting. */
    fun wholeList(url: String): String =
        LIST_ID.find(url)?.let { "https://www.youtube.com/playlist?list=" + it.groupValues[1] } ?: url.trim()
}

/**
 * Ctrl+K: one box that finds anything - a song, an album, an artist, a
 * playlist, a page, a setting or something to do - and Enter does it. A link
 * in the box (pasted with Ctrl+V anywhere outside a text box opens it with the
 * link already in) offers what can be done with the link instead.
 *
 * The shell's own state - which page, which dialog - stays the shell's: the
 * palette is handed what it needs to change it.
 */
@Composable
fun CommandPalette(
    controller: DesktopController,
    onGo: (Destination) -> Unit,
    onSettings: (SettingsTab?) -> Unit,
    onOpenGroup: (CollectionKind, String) -> Unit,
    onBulk: () -> Unit,
    onDuplicates: () -> Unit
) {
    val close = { controller.paletteOpen = false }
    var field by remember {
        controller.paletteText.let { mutableStateOf(TextFieldValue(it, TextRange(it.length))) }
    }
    val text = field.text
    var selected by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        controller.refreshPlaylists()
        focus.requestFocus()
    }

    // Run, then close: the palette goes first so whatever the row opens has the window.
    fun runAndClose(item: PaletteItem) {
        close()
        item.run()
    }

    val actions = remember(controller.shuffle) {
        paletteActions(controller, onGo, onSettings, onBulk, onDuplicates)
    }
    val library = remember(controller.tracks, controller.playlists, controller.smartPlaylists) {
        lazy { paletteLibrary(controller, onGo, onOpenGroup) }
    }
    val results = remember(text, actions, library) {
        val url = PaletteLinks.urlOf(text)
        if (url != null) {
            linkItems(controller, url, onGo)
        } else {
            val found = PaletteSearch.rank(text, if (text.isBlank()) actions else actions + library.value)
            if (text.isBlank()) found else found + searchItems(controller, text.trim(), onGo)
        }
    }
    LaunchedEffect(results) { selected = 0 }

    val list = rememberLazyListState()
    LaunchedEffect(selected) {
        val shown = list.layoutInfo.visibleItemsInfo
        if (shown.isEmpty()) return@LaunchedEffect
        // Only as far as it takes to bring the row into sight, as a list does.
        when {
            selected <= shown.first().index -> list.animateScrollToItem(selected)
            selected >= shown.last().index -> list.animateScrollToItem((selected - shown.size + 2).coerceAtLeast(0))
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x5507050C))
            // Clicks on the page underneath close it, rather than reaching the page.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = close),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            Modifier
                .padding(top = 64.dp, start = 24.dp, end = 24.dp)
                .widthIn(max = 620.dp)
                .fillMaxWidth()
                .floating(12.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                .padding(8.dp)
        ) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Search, null, Modifier.size(18.dp), tint = Palette.TextFaint)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (text.isEmpty()) {
                        Text(
                            "Songs, albums, pages, settings - or paste a link",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Palette.TextFaint
                        )
                    }
                    BasicTextField(
                        value = field,
                        onValueChange = { field = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Palette.Text),
                        cursorBrush = SolidColor(Palette.Accent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus)
                            .typingField()
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (event.key) {
                                    Key.DirectionDown -> {
                                        if (results.isNotEmpty()) selected = (selected + 1) % results.size
                                        true
                                    }
                                    Key.DirectionUp -> {
                                        if (results.isNotEmpty()) selected = (selected - 1 + results.size) % results.size
                                        true
                                    }
                                    Key.Enter, Key.NumPadEnter -> {
                                        results.getOrNull(selected)?.let(::runAndClose)
                                        true
                                    }
                                    // Taken here, so the window's Escape (back from an album) doesn't also happen.
                                    Key.Escape -> {
                                        close()
                                        true
                                    }
                                    else -> false
                                }
                            }
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text("Esc", style = MaterialTheme.typography.labelSmall, color = Palette.TextFaint)
            }
            if (results.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 6.dp).height(1.dp).background(Palette.Line))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp).padding(top = 4.dp), state = list) {
                    itemsIndexed(results) { index, item ->
                        if (index == 0 || results[index - 1].section != item.section) {
                            Text(
                                item.section.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = Palette.TextFaint,
                                modifier = Modifier.padding(start = 12.dp, top = if (index == 0) 4.dp else 10.dp, bottom = 3.dp)
                            )
                        }
                        PaletteRow(item, index == selected, onHover = { selected = index }) { runAndClose(item) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PaletteRow(item: PaletteItem, selected: Boolean, onHover: () -> Unit, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    LaunchedEffect(hovered) { if (hovered) onHover() }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Palette.Selected else Color.Transparent)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(item.icon, null, Modifier.size(16.dp), tint = if (selected) Palette.Accent else Palette.TextDim)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = Palette.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (item.subtitle.isNotEmpty()) {
                Text(
                    item.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.TextFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        val hint = item.hint.ifEmpty { if (selected) "Enter" else "" }
        if (hint.isNotEmpty()) {
            Spacer(Modifier.width(10.dp))
            Text(hint, style = MaterialTheme.typography.labelSmall, color = if (selected) Palette.Accent else Palette.TextFaint)
        }
    }
}

/** Things to do, each wired to what its button or key already does, and the pages. */
private fun paletteActions(
    controller: DesktopController,
    onGo: (Destination) -> Unit,
    onSettings: (SettingsTab?) -> Unit,
    onBulk: () -> Unit,
    onDuplicates: () -> Unit
): List<PaletteItem> {
    val a = PaletteSection.ACTIONS
    val list = mutableListOf(
        PaletteItem(a, "Play or pause", icon = Icons.Default.PlayArrow, hint = "Space", words = "resume stop") { controller.togglePlay() },
        PaletteItem(a, "Next song", icon = Icons.Default.SkipNext, hint = "Ctrl+\u2192", words = "skip") { controller.next() },
        PaletteItem(a, "Previous song", icon = Icons.Default.SkipPrevious, hint = "Ctrl+\u2190", words = "back") { controller.previous() },
        PaletteItem(
            a, if (controller.shuffle) "Shuffle off" else "Shuffle on",
            subtitle = if (controller.shuffle) "Shuffle is on" else "Shuffle is off",
            icon = Icons.Default.Shuffle, hint = "S", words = "shuffle random"
        ) { controller.shuffle = !controller.shuffle },
        PaletteItem(a, "Repeat", "All, then one, then off", Icons.Default.Repeat, "R", "loop") { controller.cycleRepeat() },
        PaletteItem(a, "Mute or unmute", icon = Icons.AutoMirrored.Filled.VolumeOff, hint = "M", words = "sound volume quiet") { controller.toggleMute() },
        PaletteItem(a, "A-B loop", "A here, then B here and round, then off", Icons.Default.Repeat, "L") { controller.cycleAbLoop() },
        PaletteItem(a, "Jump to the song playing", icon = Icons.Default.MyLocation, hint = "J", words = "now playing current") {
            onGo(Destination.LIBRARY)
            controller.jumpToNowPlaying()
        },
        PaletteItem(a, "Lyrics", "Show or hide the lyrics panel", Icons.Default.Lyrics, words = "words panel") { controller.togglePanel(SidePanelKind.LYRICS) },
        PaletteItem(a, "Queue", "Show or hide what plays next", Icons.AutoMirrored.Filled.QueueMusic, words = "up next panel") { controller.togglePanel(SidePanelKind.QUEUE) },
        PaletteItem(a, "Effects", "Equaliser and sound", Icons.Default.GraphicEq, words = "eq equalizer equaliser bass panel") { controller.togglePanel(SidePanelKind.EFFECTS) },
        PaletteItem(a, "Output", "Speakers and headphones", Icons.Default.Speaker, words = "device audio panel") { controller.togglePanel(SidePanelKind.OUTPUT) },
        PaletteItem(a, "Mini player", "Open or close it", Icons.Default.PictureInPictureAlt, words = "small window") { controller.miniPlayer = !controller.miniPlayer },
        PaletteItem(a, "Keyboard shortcuts", icon = Icons.Default.Keyboard, hint = "/", words = "keys help") { controller.showShortcuts = true },
        PaletteItem(
            a, if (Advanced.on) "Hide advanced options" else "Show advanced options",
            subtitle = "The extra controls for tuning everything",
            icon = Icons.Default.Tune, words = "advanced expert more extra options settings"
        ) { controller.advancedMode = !controller.advancedMode },
        PaletteItem(a, "Bulk tools", "Change many songs at once", Icons.Default.AutoFixHigh, words = "edit tags") { onBulk() },
        PaletteItem(a, "Find duplicates", icon = Icons.Default.FilterNone, words = "doubles copies") { onDuplicates() },
        PaletteItem(a, "Rescan library", "Look through the music folders again", Icons.Default.Refresh, words = "refresh scan") { controller.rescan() },
        PaletteItem(a, "Back up now", "Settings: About lists the backups", Icons.Default.Save, words = "backup") {
            controller.backUpNow()
            onSettings(SettingsTab.ABOUT)
        }
    )
    listOf(15, 30, 45, 60, 90).forEach { minutes ->
        list += PaletteItem(a, "Sleep in $minutes minutes", "Then pause", Icons.Default.Bedtime, words = "sleep timer") {
            controller.setSleepTimer(minutes)
        }
    }
    list += PaletteItem(a, "Sleep when this song ends", icon = Icons.Default.Bedtime, words = "sleep timer stop after") { controller.sleepAfterTrack() }
    list += PaletteItem(a, "Sleep timer off", icon = Icons.Default.Bedtime, words = "cancel") { controller.setSleepTimer(null) }
    AutoPlaylist.entries.forEach { kind ->
        list += PaletteItem(a, "Play ${kind.label}", kind.note, Icons.AutoMirrored.Filled.PlaylistPlay, words = "made for you") {
            controller.playAuto(kind)
        }
    }

    val p = PaletteSection.PAGES
    Destination.entries.forEach { page ->
        list += PaletteItem(p, page.label, "Page", page.icon, words = "go to") { onGo(page) }
    }
    list += PaletteItem(p, "Settings", icon = Icons.Default.Settings, words = "preferences options") { onSettings(null) }
    SettingsTab.entries.forEach { tab ->
        list += PaletteItem(p, "Settings: ${tab.label}", icon = Icons.Default.Tune, words = "preferences options") { onSettings(tab) }
    }
    return list
}

/** The library's songs, albums, artists and playlists, built once a library rather than a key. */
private fun paletteLibrary(
    controller: DesktopController,
    onGo: (Destination) -> Unit,
    onOpenGroup: (CollectionKind, String) -> Unit
): List<PaletteItem> {
    val tracks = controller.tracks
    val list = ArrayList<PaletteItem>(tracks.size + 200)
    controller.playlists.forEach { playlist ->
        list += PaletteItem(PaletteSection.PLAYLISTS, playlist.name, "Playlist · ${playlist.size} songs", Icons.AutoMirrored.Filled.QueueMusic) {
            controller.show(playlist)
            onGo(Destination.PLAYLISTS)
        }
    }
    controller.smartPlaylists.forEach { smart ->
        list += PaletteItem(PaletteSection.PLAYLISTS, smart.name, "Smart list · plays it", Icons.AutoMirrored.Filled.PlaylistPlay) {
            controller.playSmart(smart)
        }
    }
    TrackGroups.albums(tracks).forEach { album ->
        list += PaletteItem(PaletteSection.ALBUMS, album.name, album.artist.ifEmpty { "Album" }, Icons.Default.Album) {
            onOpenGroup(CollectionKind.ALBUMS, album.key)
        }
    }
    TrackGroups.artists(tracks).forEach { artist ->
        list += PaletteItem(PaletteSection.ARTISTS, artist.name, "Artist · ${artist.tracks.size} songs", Icons.Default.Person) {
            onOpenGroup(CollectionKind.ARTISTS, artist.key)
        }
    }
    tracks.forEach { track ->
        val by = listOfNotNull(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" · ")
        list += PaletteItem(PaletteSection.SONGS, track.title, by, Icons.Default.MusicNote) {
            // In the library list as it is shown, so what plays next is what is under it there.
            val shown = controller.visibleTracks
            controller.play(track, if (track in shown) shown else controller.tracks)
        }
    }
    return list
}

/** What to do with a link, most likely first. */
private fun linkItems(controller: DesktopController, url: String, onGo: (Destination) -> Unit): List<PaletteItem> {
    val where = runCatching { URI(url).host.removePrefix("www.") }.getOrDefault(url)
    val l = PaletteSection.LINK
    return PaletteLinks.actionsFor(url).map { action ->
        when (action) {
            LinkAction.DOWNLOAD -> PaletteItem(l, "Download it", where, Icons.Default.Download) {
                controller.startDownload(if (PaletteLinks.isVideoInList(url)) PaletteLinks.singleVideo(url) else url)
                onGo(Destination.DOWNLOAD)
            }
            LinkAction.DOWNLOAD_PLAYLIST -> PaletteItem(l, "Download the whole playlist", "Every song in the list it was played from", Icons.Default.Download) {
                controller.startDownload(PaletteLinks.wholeList(url))
                onGo(Destination.DOWNLOAD)
            }
            LinkAction.MAKE_PLAYLIST -> PaletteItem(l, "Make a playlist from it", "Matched against your library", Icons.AutoMirrored.Filled.QueueMusic) {
                controller.show(null)
                controller.importPlaylistLink(url)
                onGo(Destination.PLAYLISTS)
            }
            LinkAction.IDENTIFY -> PaletteItem(l, "Identify the song in it", "Listens to it, then throws it away", Icons.Default.Fingerprint) {
                controller.identifyMode = IdentifyMode.LINK
                controller.identifyTab(IdentifyMode.LINK).query = url
                controller.identifyLink(url)
                onGo(Destination.IDENTIFY)
            }
        }
    }
}

/** When nothing here is it: the library's own search, the catalogues, YouTube. */
private fun searchItems(controller: DesktopController, text: String, onGo: (Destination) -> Unit): List<PaletteItem> {
    val s = PaletteSection.SEARCH
    return listOf(
        PaletteItem(s, "Search the library for \u201C$text\u201D", icon = Icons.Default.LibraryMusic) {
            controller.query = text
            onGo(Destination.LIBRARY)
        },
        PaletteItem(s, "Identify \u201C$text\u201D", "Seven music catalogues", Icons.Default.Fingerprint) {
            controller.identifyMode = IdentifyMode.NAME
            controller.identifyArtist = ""
            controller.identifyTitle = text
            controller.searchCatalogues(text, controller.identifyTab(IdentifyMode.NAME))
            onGo(Destination.IDENTIFY)
        },
        PaletteItem(s, "Search YouTube for \u201C$text\u201D", icon = Icons.Default.SmartDisplay) {
            controller.searchYouTubeFor(text)
            onGo(Destination.IDENTIFY)
        }
    )
}
