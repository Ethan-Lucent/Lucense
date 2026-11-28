package com.exo.musicplayer.desktop.ui

import com.exo.musicplayer.data.ProviderNotes
import com.exo.musicplayer.desktop.system.Platform
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.exo.musicplayer.data.NameSearch
import com.exo.musicplayer.data.lyrics.LyricsTerm
import com.exo.musicplayer.desktop.AppVersion
import com.exo.musicplayer.desktop.data.AppDirs
import com.exo.musicplayer.desktop.data.DesktopController
import com.exo.musicplayer.desktop.data.WALLPAPER_DIM_DEFAULT
import com.exo.musicplayer.desktop.data.ProxyConfig
import com.exo.musicplayer.desktop.data.ProxyMode
import com.exo.musicplayer.desktop.system.DuckKey
import kotlin.math.roundToInt
import java.awt.Desktop
import java.io.File
import java.net.URI
import com.exo.musicplayer.desktop.system.LogFile
import androidx.compose.animation.Crossfade

/** The Settings page's tabs, in the order they are shown. */
enum class SettingsTab(val label: String) {
    LIBRARY("Library"),
    PLAYBACK("Playback"),
    APPEARANCE("Appearance"),
    DOWNLOADS("Downloads"),
    INTEGRATIONS("Integrations"),
    KEYS("Keys"),
    ABOUT("About")
}

/**
 * Whether a setting meant for people who tune things is shown: when advanced
 * options are on, or when it is already doing something - a proxy that is set,
 * ducking that is on - so a behaviour is never left unexplained by hiding.
 * The one place this rule is written.
 */
internal fun rowShown(advanced: Boolean, nonDefault: Boolean): Boolean = advanced || nonDefault

// What "already doing something" means for each tucked-away group; the page and
// the search below both ask these, so they cannot disagree.
private fun DesktopController.lyricsOrCoversChanged() =
    lyricsTerm != LyricsTerm.ARTIST_TITLE || lyricsProviders.any { !lyricsProviderOn(it) } ||
        coverProviders.any { !coverProviderOn(it) } || coverProvider.isNotBlank()

private fun DesktopController.coloursChanged() =
    accent == AccentChoice.CUSTOM || settings.lyricsActiveColor.isNotBlank() || settings.lyricsInactiveColor.isNotBlank()

private fun DesktopController.proxyChanged() = proxy != ProxyConfig()

/**
 * One setting as the search lists it: its name as the page shows it, a few
 * plain words it is also found by, the tab it is on, and whether it is tucked
 * away unless Show advanced options is on (or [inUse] says it is doing something).
 */
internal class SettingEntry(
    val label: String,
    val hint: String,
    val tab: SettingsTab,
    val advanced: Boolean = false,
    val inUse: ((DesktopController) -> Boolean)? = null
)

/** Every setting row, so the box above the tabs can find one without knowing which tab it is on. */
internal val settingsIndex: List<SettingEntry> = buildList {
    val library = SettingsTab.LIBRARY
    val playback = SettingsTab.PLAYBACK
    val look = SettingsTab.APPEARANCE
    val downloads = SettingsTab.DOWNLOADS
    val links = SettingsTab.INTEGRATIONS
    add(SettingEntry("Music folders", "where your songs are, add or remove a folder", library))
    add(SettingEntry("Lyrics search", "which words are used to look up lyrics", library, true) { it.lyricsOrCoversChanged() })
    add(SettingEntry("Lyrics services", "which sites are asked for lyrics", library, true) { it.lyricsOrCoversChanged() })
    add(SettingEntry("Cover art services", "where covers come from", library, true) { it.lyricsOrCoversChanged() })
    add(SettingEntry("Output sample rate", "44.1 kHz 48 kHz 96 kHz audio device", playback, true) { it.settings.outputRate != 0 })
    add(SettingEntry("Even out volumes", "same loudness for every song, normalise", playback))
    add(SettingEntry("Fade out on the sleep timer", "gentle ending", playback))
    add(SettingEntry("Crossfade", "overlap one song into the next", playback))
    add(SettingEntry("Media keys", "play pause next previous on the keyboard", playback))
    add(SettingEntry("Mouse side buttons", "back forward thumb buttons, previous next song, page history", playback))
    add(SettingEntry("Keep playing in the tray when the window is closed", "background system tray", playback))
    add(SettingEntry("Show the mini player while it's in the tray", "small window on top", playback))
    if (Platform.isWindows) {
        add(SettingEntry("Offer Lucense for music files in Explorer", "open with default app", playback))
        add(SettingEntry("Game ducking", "turn the music down with a hotkey while gaming", playback, true) { it.duckEnabled })
    }
    add(SettingEntry("Theme", "Dark or light, accent colour, swatches, light mode, dark mode", look))
    add(SettingEntry("Interface size", "zoom, scale, bigger text, smaller", look))
    add(SettingEntry("Liquid glass", "frosted panes, transparency", look))
    add(SettingEntry("Glass see-through", "how much of the background shows through the glass", look, true) {
        it.glassSeeThrough != Glass.SEE_THROUGH_DEFAULT
    })
    add(SettingEntry("Colourful glow", "the glass background drifts through several hues, rainbow, calm, liquid glass", look))
    add(SettingEntry("Take the accent from the song's cover", "colours from the artwork", look))
    add(SettingEntry("Show notes when hovering", "tooltips, help text", look))
    add(SettingEntry("Show advanced options", "expert extra controls", look))
    add(SettingEntry("Edit colours", "custom theme, lyrics colours", look, true) { it.coloursChanged() })
    add(SettingEntry("Background", "stars, effect behind the window", look))
    add(SettingEntry("Song cover in the ball", "round album art in the middle of the reactive ball", look))
    add(SettingEntry("Wallpaper", "a picture of your own behind everything", look))
    add(SettingEntry("Wallpaper dim", "darken the picture", look, true) {
        it.wallpaper != null && it.wallpaperDim != WALLPAPER_DIM_DEFAULT
    })
    add(SettingEntry("Downloads folder", "where downloaded songs go", downloads))
    add(SettingEntry("Write tags and cover art into files", "leave your files untouched", downloads))
    add(SettingEntry("Proxy", "connect through a proxy, socks, http, vpn", downloads, true) { it.proxyChanged() })
    if (Platform.isLinux) add(SettingEntry("Show each song as it starts", "desktop notification", links))
    add(SettingEntry("Show what you're playing on Discord", "rich presence, sharing", links))
    add(SettingEntry("Keyboard shortcuts", "keys, hotkeys", SettingsTab.KEYS))
    add(SettingEntry("Updates", "check for a new version", SettingsTab.ABOUT))
    add(SettingEntry("Tell me when an update is available", "update notice, new version at start, launch, notification, off", SettingsTab.ABOUT))
    add(SettingEntry("Backups", "back up favourites and playlists, restore", SettingsTab.ABOUT))
    add(SettingEntry("Data folder", "where Lucense keeps its files", SettingsTab.ABOUT))
    add(SettingEntry("Logs", "log files for bug reports", SettingsTab.ABOUT, true))
}

/**
 * The settings that match [query]: every word in the name, the hint or the tab
 * name, in any order and ignoring accents ([NameSearch], as the palette does).
 * Name matches come first, then the rest, each in page order.
 */
internal fun findSettings(query: String): List<SettingEntry> {
    if (query.isBlank()) return emptyList()
    return settingsIndex
        .filter { NameSearch.matches(query, it.label, it.hint, it.tab.label) }
        .sortedBy { if (NameSearch.matches(query, it.label)) 0 else 1 }
}

/**
 * Settings, a tab per kind of thing.
 *
 * Sound lives in the Effects panel and output in the Output panel, both a click
 * from the transport bar — this covers the things you set once: where the music
 * is, where downloads go (the weather place is on the Moods page).
 *
 * It used to be one page, a long way down from the music folders to About; a
 * setting was found by scrolling past all the others. Each tab scrolls on its
 * own and comes back where it was left, and the tab itself is remembered while
 * the app runs - the Settings page has its own slot in DesktopApp's page memory.
 */
@Composable
fun SettingsScreen(controller: DesktopController, onChooseFolder: () -> File?) {
    // On the controller, so the Ctrl+K palette can open Settings at a tab.
    var tab by controller::settingsTab
    val tabs = rememberSaveableStateHolder()
    var query by remember { mutableStateOf("") }
    // The advanced result clicked while advanced options are off, waiting on "Turn on".
    var pending by remember { mutableStateOf<SettingEntry?>(null) }
    Column(Modifier.fillMaxSize()) {
        TextInput(
            value = query,
            onValueChange = {
                query = it
                pending = null
            },
            placeholder = "Search settings",
            leading = Icons.Default.Search,
            search = true,
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 10.dp)
        )
        pending?.let { entry ->
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Turn on Show advanced options in Appearance to see this.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextDim,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(10.dp))
                GhostButton("Turn on") {
                    controller.advancedMode = true
                    tab = entry.tab
                    query = ""
                    pending = null
                }
            }
        }
        if (query.isNotBlank()) {
            SettingsResults(controller, query) { entry ->
                if (entry.advanced && !Advanced.on && entry.inUse?.invoke(controller) != true) {
                    pending = entry
                } else {
                    tab = entry.tab
                    query = ""
                }
            }
            return@Column
        }
        // The strip scrolls sideways (fading at the edges, the open tab kept in view) when the
        // pane is too narrow for seven tabs, rather than squeezing the last one into a letter per line.
        Box(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 12.dp)) {
            SegmentedRow(
                options = SettingsTab.entries,
                selected = tab,
                label = { it.label },
                onSelect = { tab = it },
                scrollWhenTight = true
            )
        }
        Crossfade(tab, Modifier.fillMaxSize(), Motion.change(), label = "settings tab") { shown ->
        tabs.SaveableStateProvider(shown.name) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                when (shown) {
                    SettingsTab.LIBRARY -> {
                        MusicFoldersSettings(controller, onChooseFolder)
                        LyricsAndArtworkSettings(controller)
                    }
                    SettingsTab.PLAYBACK -> {
                        PlaybackOptionsSettings(controller)
                        GameDuckingSettings(controller)
                    }
                    SettingsTab.APPEARANCE -> AppearanceSettings(controller)
                    SettingsTab.DOWNLOADS -> {
                        DownloadsSettings(controller, onChooseFolder)
                        ProxySettings(controller)
                    }
                    SettingsTab.INTEGRATIONS -> {
                        if (Platform.isLinux) DesktopNotificationSettings(controller)
                        SharingSettings(controller)
                    }
                    SettingsTab.KEYS -> KeysSettings()
                    SettingsTab.ABOUT -> AboutSettings(controller)
                }
                Spacer(Modifier.height(14.dp))
            }
        }
        }
    }
}

/** The flat list a search shows: each match with its tab in small type, and an Advanced tag where it applies. */
@Composable
private fun SettingsResults(controller: DesktopController, query: String, onPick: (SettingEntry) -> Unit) {
    val found = remember(query) { findSettings(query) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (found.isEmpty()) Hint("No setting matches that. Try another word.")
        found.forEach { entry ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Palette.Well)
                    .clickable { onPick(entry) }
                    .padding(horizontal = 11.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(entry.label, style = MaterialTheme.typography.bodyMedium, color = Palette.Text)
                    Hint(entry.hint)
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    if (entry.advanced) "${entry.tab.label} · Advanced" else entry.tab.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.TextFaint
                )
            }
        }
        Spacer(Modifier.height(14.dp))
    }
}

@Composable
private fun MusicFoldersSettings(controller: DesktopController, onChooseFolder: () -> File?) {
    Panel(Modifier.fillMaxWidth()) {
        SectionTitle("Music folders") {
            AccentButton("Add folder", icon = Icons.Default.FolderOpen) {
                onChooseFolder()?.let { controller.addFolder(it) }
            }
        }
        Spacer(Modifier.height(4.dp))
        Hint("Lucense reads these folders. Your files are never copied or moved.")
        Spacer(Modifier.height(12.dp))

        if (controller.folders.isEmpty()) {
            Hint("None yet.")
        } else {
            controller.folders.forEach { path ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Palette.Well)
                        .padding(horizontal = 11.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.FolderOpen, null,
                        Modifier.size(14.dp), tint = Palette.TextFaint
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        path,
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.TextDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        Modifier
                            .size(22.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .clickable { controller.removeFolder(path) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Close, "Remove",
                            Modifier.size(13.dp), tint = Palette.TextDim
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadsSettings(controller: DesktopController, onChooseFolder: () -> File?) {
    Panel(Modifier.fillMaxWidth()) {
        SectionTitle("Downloads")
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                controller.downloadDir,
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(10.dp))
            GhostButton("Open") { controller.openMusicFolder() }
            Spacer(Modifier.width(6.dp))
            GhostButton("Change") {
                onChooseFolder()?.let { controller.chooseDownloadDir(it) }
            }
        }
        Spacer(Modifier.height(12.dp))
        CheckRow(
            label = "Write tags and cover art into files",
            checked = controller.writeTags,
            note = "Off means identified names and covers stay inside Lucense " +
                "and your files aren't modified"
        ) { controller.writeTags = it }
    }
}

@Composable
private fun AppearanceSettings(controller: DesktopController) {
    Panel(Modifier.fillMaxWidth()) {
        SectionTitle("Appearance")
        Spacer(Modifier.height(4.dp))
        Hint(
            "Recolours the whole window, not just the buttons. Every theme comes dark and " +
                "light, and most are published schemes - the surfaces and the text tones come " +
                "from it together, which is what keeps every label readable on every layer."
        )
        Spacer(Modifier.height(14.dp))
        var editingColours by remember { mutableStateOf(false) }
        val current = controller.accent
        Text("Theme", style = MaterialTheme.typography.titleMedium, color = Palette.Text)
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Fixed-width cells, and the short last row padded out with empty
            // ones, so the columns line up whatever the names are.
            AccentChoice.families.chunked(SWATCHES_PER_ROW).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    row.forEach { family ->
                        Noted(family.name, if (family.hasModes) Notes.THEME else Notes.THEME_CUSTOM) {
                            FamilySwatch(family, family.name == current.family) {
                                // A family keeps the mode in use: Nord from a light theme is Snow Storm.
                                controller.accent = if (current.light) family.light else family.dark
                                if (!family.hasModes) editingColours = true
                            }
                        }
                    }
                    repeat(SWATCHES_PER_ROW - row.size) { Spacer(Modifier.width(SWATCH_CELL)) }
                }
            }
        }
        // Custom has only its own colours, so there is no other mode to switch to.
        if (current.themeFamily.hasModes) {
            Spacer(Modifier.height(10.dp))
            Noted(null, Notes.THEME_MODE) {
                SegmentedRow(
                    options = listOf(false, true),
                    selected = current.light,
                    label = { if (it) "Light" else "Dark" },
                    onSelect = { controller.accent = current.variant(light = it) }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Hint(current.credit)
        Spacer(Modifier.height(10.dp))
        Text("Interface size", style = MaterialTheme.typography.bodyMedium, color = Palette.Text)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UI_SCALES.forEach { (name, factor) ->
                Chip("$name ${(factor * 100).roundToInt()}%", selected = controller.uiScale == factor) {
                    controller.uiScale = factor
                }
            }
        }
        Hint("Makes everything bigger or smaller. The window still fits itself to its size.")
        Spacer(Modifier.height(10.dp))
        CheckRow(
            "Liquid glass",
            controller.glass,
            note = "The window as panes of frosted glass over a soft field of the theme's colours, " +
                "or over your wallpaper. Rounder controls, lit edges. Uses a little more of the graphics card."
        ) { controller.glass = it }
        if (controller.glass && rowShown(Advanced.on, controller.glassSeeThrough != Glass.SEE_THROUGH_DEFAULT)) {
            // Under the box it belongs to, in line with its label.
            Column(Modifier.padding(start = 25.dp, top = 4.dp)) {
                Text(
                    "See-through ${(controller.glassSeeThrough * 100).roundToInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextDim
                )
                Slider(
                    value = controller.glassSeeThrough,
                    onValueChange = { controller.glassSeeThrough = it },
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = Palette.Accent,
                        activeTrackColor = Palette.Accent,
                        inactiveTrackColor = Palette.Line
                    )
                )
                Hint(
                    "How much of what's behind the panes shows through them. 50% is the standard look; " +
                        "lower is more frosted, higher thins the frost until your wallpaper shows nearly as it is."
                )
            }
        }
        if (controller.glass) {
            // Under the box it belongs to, in line with Liquid glass's own options.
            Column(Modifier.padding(start = 25.dp)) {
                CheckRow(
                    "Colourful glow",
                    controller.glassRainbow,
                    note = "The glass background drifts through several hues instead of staying close to your theme's colour."
                ) { controller.glassRainbow = it }
                Hint("The glass background drifts through several hues instead of staying close to your theme's colour.")
            }
        }
        Spacer(Modifier.height(6.dp))
        CheckRow(
            "Take the accent from the song's cover",
            controller.coverColours,
            note = "The theme stays and its accent follows the artwork of what's playing. " +
                "Black-and-white covers keep the theme's own."
        ) { controller.coverColours = it }
        Spacer(Modifier.height(6.dp))
        CheckRow(
            "Show notes when hovering",
            controller.hoverNotes,
            note = "A short note on how to use a button or menu item when you rest the mouse on it. " +
                "Buttons that are only an icon still show their name."
        ) { controller.hoverNotes = it }
        Spacer(Modifier.height(6.dp))
        CheckRow(
            "Show advanced options",
            controller.advancedMode,
            note = "Shows the extra controls for people who want to tune everything: loops, output devices, " +
                "downloader details, fine sliders and more. Nothing is removed when this is off, it is only " +
                "tucked away. Ctrl+K finds everything either way."
        ) { controller.advancedMode = it }
        // Picking the Custom swatch opens the editor too, so this is only the way back in.
        if (rowShown(Advanced.on, controller.coloursChanged())) {
            Spacer(Modifier.height(12.dp))
            GhostButton("Edit colours", icon = Icons.Default.ColorLens) { editingColours = true }
            Spacer(Modifier.height(6.dp))
            Hint("Theme colours, the background effect's colour, and the lyrics' current and other lines.")
        }
        if (editingColours) ThemeEditorWindow(controller) { editingColours = false }

        Spacer(Modifier.height(18.dp))
        Text("Background", style = MaterialTheme.typography.titleMedium, color = Palette.Text)
        Spacer(Modifier.height(8.dp))
        SegmentedRow(
            options = BackdropStyle.entries,
            selected = controller.backdrop,
            label = { it.label },
            onSelect = { controller.backdrop = it }
        )
        if (controller.backdrop == BackdropStyle.REACTIVE) {
            Spacer(Modifier.height(8.dp))
            SegmentedRow(
                options = ReactiveMode.entries,
                selected = controller.reactiveMode,
                label = { it.label },
                onSelect = { controller.reactiveMode = it }
            )
            if (controller.reactiveMode == ReactiveMode.BALL) {
                Spacer(Modifier.height(6.dp))
                CheckRow(
                    "Song cover in the ball",
                    controller.ballCover,
                    note = "Shows the cover of the song playing, round, in the middle of the ball. " +
                        "It follows the music with the ball."
                ) { controller.ballCover = it }
            }
        }
        Spacer(Modifier.height(6.dp))
        Hint(
            if (controller.backdrop == BackdropStyle.NONE) {
                controller.backdrop.note
            } else {
                controller.backdrop.note +
                    (if (controller.backdrop == BackdropStyle.REACTIVE) " " + controller.reactiveMode.note else "") +
                    " Shown behind the sidebar, the library and the " +
                    "lyrics, and paused while the window isn't focused."
            }
        )

        Spacer(Modifier.height(18.dp))
        Text("Wallpaper", style = MaterialTheme.typography.titleMedium, color = Palette.Text)
        Spacer(Modifier.height(4.dp))
        Hint("A picture of your own behind everything, with the background effect drawn over it.")
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            GhostButton(
                if (controller.wallpaper == null) "Choose picture" else "Change picture",
                icon = Icons.Default.Image
            ) { chooseImage()?.let { controller.setWallpaper(it) } }
            if (controller.wallpaper != null) {
                Spacer(Modifier.width(8.dp))
                GhostButton("Remove") { controller.clearWallpaper() }
            }
        }
        controller.wallpaperNote?.let { note ->
            Spacer(Modifier.height(6.dp))
            Hint(note)
        }
        if (controller.wallpaper != null && rowShown(Advanced.on, controller.wallpaperDim != WALLPAPER_DIM_DEFAULT)) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Dim ${(controller.wallpaperDim * 100).roundToInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextDim
            )
            Slider(
                value = controller.wallpaperDim,
                onValueChange = { controller.wallpaperDim = it },
                valueRange = 0f..0.9f,
                colors = SliderDefaults.colors(
                    thumbColor = Palette.Accent,
                    activeTrackColor = Palette.Accent,
                    inactiveTrackColor = Palette.Line
                )
            )
            Hint("Darkens the picture so text stays easy to read.")
        }
    }
}

@Composable
private fun GameDuckingSettings(controller: DesktopController) {

    // A global hotkey is a Windows thing; Linux desktops keep the keyboard to themselves.
    if (Platform.isWindows && rowShown(Advanced.on, controller.duckEnabled)) {

        Panel(Modifier.fillMaxWidth()) {
            SectionTitle("Game ducking")
            Spacer(Modifier.height(4.dp))
            Hint(
                "Turns the music down system-wide at the press of a key, so you can hear " +
                    "what the game is doing without alt-tabbing. Works while the game has " +
                    "focus."
            )
            Spacer(Modifier.height(12.dp))

            CheckRow(
                label = "Duck the music with a hotkey",
                checked = controller.duckEnabled,
                note = "Press once to turn down, again to restore"
            ) { controller.duckEnabled = it }

            if (controller.duckEnabled) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Key",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.TextDim,
                        modifier = Modifier.width(64.dp)
                    )
                    SegmentedRow(
                        options = DuckKey.entries,
                        selected = controller.duckKey,
                        label = { it.label },
                        onSelect = { controller.duckKey = it }
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Only these keys are offered: a global hotkey is taken from every " +
                        "other program too, so binding a letter would break typing " +
                        "everywhere.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.TextFaint
                )

                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Turn down by",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.TextDim,
                        modifier = Modifier.width(94.dp)
                    )
                    Text(
                        "${controller.duckPercent}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.Accent,
                        modifier = Modifier.width(46.dp)
                    )
                    Slider(
                        value = controller.duckPercent.toFloat(),
                        onValueChange = { controller.duckPercent = it.toInt() },
                        valueRange = 10f..100f,
                        steps = 17,
                        colors = SliderDefaults.colors(
                            thumbColor = Palette.Accent,
                            activeTrackColor = Palette.Accent,
                            inactiveTrackColor = Palette.Line
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }

                controller.duckError?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.labelSmall, color = Palette.TextDim)
                }
            }
        }
    }
}

/** 44100 as "44.1 kHz", 48000 as "48 kHz". */
private fun kiloHertz(rate: Int): String = if (rate % 1000 == 0) "${rate / 1000} kHz" else "${rate / 1000.0} kHz"

@Composable
private fun PlaybackOptionsSettings(controller: DesktopController) {
    Panel(Modifier.fillMaxWidth()) {
        SectionTitle("Playback")
        Spacer(Modifier.height(10.dp))
        // Read once, as the engine starts: the whole chain - decoding, effects,
        // the device - is built for one rate.
        var outputRate by remember { mutableStateOf(controller.settings.outputRate) }
        if (rowShown(Advanced.on, outputRate != 0)) {
            Text("Output sample rate", style = MaterialTheme.typography.titleMedium, color = Palette.Text)
            Spacer(Modifier.height(6.dp))
            SegmentedRow(
                options = listOf(0, 44_100, 48_000, 96_000),
                selected = outputRate,
                label = { if (it == 0) "Automatic" else kiloHertz(it) },
                onSelect = {
                    outputRate = it
                    controller.settings.outputRate = it
                }
            )
            Spacer(Modifier.height(4.dp))
            Hint(
                "Now ${kiloHertz(controller.engine.rate)}" +
                    (if (outputRate == 0) ", the sound system's own rate, so it converts nothing." else ".") +
                    " A change takes effect when Lucense next starts."
            )
            Spacer(Modifier.height(14.dp))
        }
        CheckRow(
            label = "Even out volumes",
            checked = controller.levelling,
            note = "Plays every measured song at the same loudness. Run Level volumes in " +
                "Bulk tools to measure them."
        ) { controller.levelling = it }
        Spacer(Modifier.height(14.dp))
        CheckRow(
            label = "Fade out on the sleep timer",
            checked = controller.sleepFade,
            note = "The last half minute comes down gently instead of stopping mid-bar."
        ) { controller.sleepFade = it }
        Spacer(Modifier.height(18.dp))
        Text("Crossfade", style = MaterialTheme.typography.titleMedium, color = Palette.Text)
        Spacer(Modifier.height(4.dp))
        Hint(
            "How long one song overlaps the next. Off still runs straight on into it " +
                "without the silence there used to be between tracks."
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            SegmentedRow(
                options = listOf(0, 2, 4, 6, 8, 12),
                selected = controller.crossfadeSeconds,
                label = { if (it == 0) "Off" else "$it seconds" },
                onSelect = { controller.crossfadeSeconds = it }
            )
        }

        Spacer(Modifier.height(18.dp))
        CheckRow(
            label = "Media keys",
            checked = controller.mediaKeysEnabled,
            note = if (Platform.isWindows) {
                "Play, pause, next and previous on the keyboard, from anywhere. " +
                    "While this is on, other players won't see those keys."
            } else {
                "Play, pause, next and previous on the keyboard, and Lucense in the desktop's " +
                    "media controls with the song playing and its cover."
            }
        ) { controller.mediaKeysEnabled = it }
        Spacer(Modifier.height(18.dp))
        Text("Mouse side buttons", style = MaterialTheme.typography.titleMedium, color = Palette.Text)
        Spacer(Modifier.height(8.dp))
        Noted(null, MOUSE_BUTTONS_NOTE) {
            SegmentedRow(
                options = MouseButtonsMode.entries,
                selected = controller.mouseButtons,
                label = { it.label },
                onSelect = { controller.mouseButtons = it }
            )
        }
        Spacer(Modifier.height(14.dp))
        CheckRow(
            label = "Keep playing in the tray when the window is closed",
            checked = controller.keepInTray && controller.trayAvailable,
            enabled = controller.trayAvailable,
            note = if (controller.trayAvailable) {
                "Closing the window leaves Lucense playing, with its icon by the clock. " +
                    "Double-click the icon to bring the window back; quit from its menu."
            } else {
                "This desktop has no tray for Lucense to wait in, so closing the window quits."
            }
        ) { controller.keepInTray = it }
        Spacer(Modifier.height(10.dp))
        CheckRow(
            label = "Show the mini player while it's in the tray",
            checked = controller.trayMiniPlayer,
            enabled = controller.keepInTray && controller.trayAvailable,
            note = "The small window that stays on top, with the song, its cover and the buttons. " +
                "Also in the tray icon's menu, and the Mini button by the volume."
        ) { controller.trayMiniPlayer = it }
        // On Linux the package puts Lucense in the Open with list itself.
        if (Platform.isWindows) {
            Spacer(Modifier.height(14.dp))
            CheckRow(
                label = "Offer Lucense for music files in Explorer",
                checked = controller.fileTypes,
                enabled = controller.canAssociate,
                note = controller.fileTypesNote ?: when {
                    !controller.canAssociate ->
                        "Available once Lucense is running from its installed shortcut " +
                            "rather than from a development build."
                    else ->
                        "Puts Lucense in the Open with list for MP3, FLAC, M4A and the rest. " +
                            "Windows does not allow a program to make itself the default, so " +
                            "pick it from Open with once and tick \"Always use this app\"."
                }
            ) { controller.changeFileTypes(it) }
        }

    }
}

@Composable
private fun LyricsAndArtworkSettings(controller: DesktopController) {
    // The lookup defaults suit most libraries; the section is for tuning them,
    // and shows itself when one has been changed.
    if (!rowShown(Advanced.on, controller.lyricsOrCoversChanged())) return
    Panel(Modifier.fillMaxWidth()) {
        SectionTitle("Lyrics and artwork")
        Spacer(Modifier.height(10.dp))
        Text("Lyrics search", style = MaterialTheme.typography.titleMedium, color = Palette.Text)
        Spacer(Modifier.height(4.dp))
        Hint(controller.lyricsTerm.note)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            SegmentedRow(
                options = LyricsTerm.entries,
                selected = controller.lyricsTerm,
                label = { it.label },
                onSelect = { controller.lyricsTerm = it }
            )
        }
        if (controller.lyricsTerm == LyricsTerm.CUSTOM) {
            Spacer(Modifier.height(8.dp))
            TextInput(
                value = controller.lyricsTermCustom,
                onValueChange = { controller.lyricsTermCustom = it },
                placeholder = "{artist} {title}",
                modifier = Modifier.width(320.dp)
            )
        }
        Spacer(Modifier.height(18.dp))
        Text("Lyrics services", style = MaterialTheme.typography.titleMedium, color = Palette.Text)
        Spacer(Modifier.height(4.dp))
        Hint(
            "Asked in this order until one has the song's words. One that's unticked is never asked. " +
                "With all of them off, nothing is looked up online; lyrics can still be typed in."
        )
        Spacer(Modifier.height(4.dp))
        for (label in controller.lyricsProviders) {
            CheckRow(
                label = label,
                checked = controller.lyricsProviderOn(label),
                note = ProviderNotes.lyrics(label).ifEmpty { null }
            ) { controller.setLyricsProvider(label, it) }
        }

        Spacer(Modifier.height(18.dp))
        Text("Cover art services", style = MaterialTheme.typography.titleMedium, color = Palette.Text)
        Spacer(Modifier.height(4.dp))
        Hint(
            "Where covers come from: when they're fetched in bulk, by Fix, from a match in Identify, " +
                "and for Discord. One that's unticked never gives a song its cover. Tags still come " +
                "from all of them."
        )
        Spacer(Modifier.height(4.dp))
        for (label in controller.coverProviders) {
            CheckRow(
                label = label,
                checked = controller.coverProviderOn(label),
                note = ProviderNotes.cover(label).ifEmpty { null }
            ) { controller.setCoverProvider(label, it) }
        }
        val coversOn = controller.coverProviders.filter { controller.coverProviderOn(it) }
        if (coversOn.size > 1) {
            Spacer(Modifier.height(10.dp))
            Text("Asked first", style = MaterialTheme.typography.titleSmall, color = Palette.Text)
            Spacer(Modifier.height(4.dp))
            Hint("The others that are on are still asked when it has nothing, so a preference never costs you a cover.")
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                SegmentedRow(
                    options = listOf("") + coversOn,
                    selected = controller.coverProvider.takeIf { it in coversOn }.orEmpty(),
                    label = { if (it.isBlank()) "Automatic" else it },
                    onSelect = { controller.coverProvider = it }
                )
            }
        } else if (coversOn.isEmpty()) {
            Spacer(Modifier.height(6.dp))
            Hint("With every service off, songs keep the covers they have, and new ones come only from files and tags.")
        }

    }
}

@Composable
private fun DesktopNotificationSettings(controller: DesktopController) {
    Panel(Modifier.fillMaxWidth()) {
        SectionTitle("Desktop")
        Spacer(Modifier.height(10.dp))
        CheckRow(
            label = "Show each song as it starts",
            checked = controller.notifySongs,
            note = "A notification with the song and its cover, for when Lucense is behind other windows. " +
                "The next song replaces it rather than piling up."
        ) { controller.notifySongs = it }
    }
}

@Composable
private fun KeysSettings() {
    Panel(Modifier.fillMaxWidth()) {
        SectionTitle("Keys")
        Spacer(Modifier.height(4.dp))
        Hint("Live whenever the window has focus and you aren't typing in a box. / shows the same list from anywhere.")
        Spacer(Modifier.height(12.dp))
        KeyGuide()
    }
}

@Composable
private fun SharingSettings(controller: DesktopController) {
    Panel(Modifier.fillMaxWidth()) {
        SectionTitle("Sharing")
        Spacer(Modifier.height(10.dp))
        CheckRow(
            label = "Show what you're playing on Discord",
            checked = controller.discord,
            note = controller.discordNote
                ?: if (controller.discordConnected) {
                    "Connected. Your profile shows the song and artist while something is playing."
                } else {
                    "Needs the Discord desktop app running on this PC. Nothing leaves your machine."
                }
        ) { controller.discord = it }
        if (controller.discord) {
            Spacer(Modifier.height(12.dp))
            CheckRow(
                label = "Show the song's own cover",
                checked = controller.discordCover,
                note = "Discord fetches the picture itself, so the cover has to be one " +
                    "with a web address: the ones the cover tool found, or one looked up " +
                    "once per song. Off shows the Lucense icon instead."
            ) { controller.discordCover = it }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Application ID",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextDim,
                    modifier = Modifier.width(110.dp)
                )
                TextInput(
                    value = controller.discordId,
                    onValueChange = { controller.changeDiscordId(it) },
                    placeholder = "From discord.com/developers",
                    modifier = Modifier.width(260.dp)
                )
            }
        }

    }
}

@Composable
private fun AboutSettings(controller: DesktopController) {
    Panel(Modifier.fillMaxWidth()) {
        SectionTitle("About")
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Lucense for ${Platform.systemName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.Text
                )
                Hint("version ${AppVersion.name} · made by lucent")
            }
            GhostButton("Contact") { openLink("https://t.me/Eth4wn") }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Thanks to Riff", style = MaterialTheme.typography.bodyMedium, color = Palette.Text)
                Hint(
                    "Several features are adapted from Riff by rootscripts, a free desktop music " +
                        "player: distortion, download formats, playlist import, fullscreen lyrics, " +
                        "lyrics timing and translation, colours from the cover, the trimmer and " +
                        "streaming. Riff is MIT licensed."
                )
            }
            GhostButton("Riff on GitHub") { openLink("https://github.com/rootscripts/riff") }
        }
        Spacer(Modifier.height(12.dp))
        UpdateRow(controller, ::openLink)
        Spacer(Modifier.height(16.dp))
        Text("Backups", style = MaterialTheme.typography.bodyMedium, color = Palette.Text)
        Spacer(Modifier.height(4.dp))
        Hint(
            controller.backupNote
                ?: "Favourites, playlists and what you have played — everything the files " +
                "themselves don't hold. Settings are written down too, but restoring doesn't apply them."
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AccentButton("Back up now", icon = Icons.Default.Save) { controller.backUpNow() }
            Spacer(Modifier.width(8.dp))
            GhostButton("Show backups", icon = Icons.Default.FolderOpen) { controller.revealBackups() }
        }
        controller.backups.take(4).forEach { file ->
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    file.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                GhostButton("Restore") { controller.restoreBackup(file) }
            }
        }

        Spacer(Modifier.height(16.dp))
        GhostButton("Keyboard shortcuts", icon = Icons.Default.Keyboard) {
            controller.showShortcuts = true
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Data folder",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextDim
                )
                Text(
                    AppDirs.root.absolutePath,
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.TextFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            GhostButton("Open") { openFolder(AppDirs.root) }
        }
        if (Advanced.on) Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Logs",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextDim
                )
                Text(
                    (LogFile.folder ?: File(AppDirs.root, "logs")).absolutePath,
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.TextFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            GhostButton("Open") { openFolder(LogFile.folder ?: File(AppDirs.root, "logs")) }
        }
    }
}


/**
 * A family's swatch: its dark variant on the left and its light one on the
 * right, each its page colour with its accent, so both modes show at a glance.
 * Opaque colours inside the clip, so it reads the same with glass on.
 */
@Composable
private fun FamilySwatch(family: ThemeFamily, selected: Boolean, onClick: () -> Unit) {
    val custom = !family.hasModes
    val dark = if (custom) Palette.custom else family.dark.colors
    val light = if (custom) Palette.custom else family.light.colors
    Column(
        Modifier.width(SWATCH_CELL),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // The ring sits a little outside the swatch, on the page, so it shows
        // whatever the two halves are.
        Box(
            Modifier
                .border(2.dp, if (selected) Palette.Text else Color.Transparent, RoundedCornerShape(11.dp))
                .padding(3.dp)
                .size(width = 52.dp, height = 40.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, Palette.Line, RoundedCornerShape(8.dp))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Row(Modifier.fillMaxSize()) {
                listOf(dark, light).forEach { half ->
                    Box(
                        Modifier.weight(1f).fillMaxHeight().background(half.content),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(half.accent))
                    }
                }
            }
            if (selected) {
                val shown = Palette.themeColors
                Box(
                    Modifier.size(22.dp).clip(CircleShape).background(shown.accent),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Check, null, Modifier.size(15.dp), tint = shown.onAccent)
                }
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            family.name,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) Palette.Text else Palette.TextFaint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

/** Every swatch takes the same width, whatever its name is, so the rows line up. */
private val SWATCH_CELL = 78.dp
private const val SWATCHES_PER_ROW = 6

private fun openLink(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported() &&
            Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)
        ) {
            Desktop.getDesktop().browse(URI(url))
        }
    }
}

private fun openFolder(dir: File) {
    runCatching {
        if (Desktop.isDesktopSupported() &&
            Desktop.getDesktop().isSupported(Desktop.Action.OPEN)
        ) {
            Desktop.getDesktop().open(dir)
        }
    }
}

/**
 * The proxy for everything Lucense does online.
 *
 * One address box and one port box rather than a URL field: the kind of proxy
 * is already chosen above it, and a pasted "socks5://" would only be something
 * to strip back off.
 */
@Composable
private fun ProxySettings(controller: DesktopController) {
    val proxy = controller.proxy
    if (!rowShown(Advanced.on, controller.proxyChanged())) return
    Panel(Modifier.fillMaxWidth()) {
        SectionTitle("Proxy")
        Spacer(Modifier.height(4.dp))
        Hint(
            "Used for everything that goes online: yt-dlp and spotdl downloads, YouTube " +
                "search and previews, lyrics, cover art, identification and weather."
        )
        Spacer(Modifier.height(12.dp))
        SegmentedRow(
            options = ProxyMode.entries,
            selected = proxy.mode,
            label = { it.label },
            onSelect = { controller.proxy = controller.proxy.copy(mode = it) }
        )
        Spacer(Modifier.height(10.dp))
        when (proxy.mode) {
            ProxyMode.SYSTEM ->
                Hint(
                    if (Platform.isWindows) {
                        "Follows Windows: Settings, Network & internet, Proxy."
                    } else {
                        // Java's system proxy on Linux is GNOME's; it doesn't read http_proxy.
                        "Follows the desktop's proxy: Settings, Network, Network Proxy (GNOME)."
                    }
                )
            ProxyMode.DIRECT ->
                Hint("Connects straight to the internet, even if ${Platform.systemName} has a proxy set.")
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextInput(
                        value = proxy.host,
                        onValueChange = { controller.proxy = controller.proxy.copy(host = it.trim()) },
                        placeholder = "Address, like 127.0.0.1",
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    TextInput(
                        value = if (proxy.port == 0) "" else proxy.port.toString(),
                        onValueChange = { typed ->
                            val digits = typed.filter { it.isDigit() }.take(5)
                            controller.proxy = controller.proxy.copy(port = digits.toIntOrNull() ?: 0)
                        },
                        placeholder = "Port",
                        modifier = Modifier.width(110.dp)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Hint(
                    when {
                        proxy.port > 65535 ->
                            "Ports go up to 65535. Until then Lucense connects directly."
                        proxy.incomplete ->
                            "Fill in both the address and the port. Until then Lucense connects directly."
                        proxy.mode == ProxyMode.HTTPS ->
                            "Reaches the proxy the same way as HTTP and tunnels secure sites through it."
                        proxy.mode == ProxyMode.SOCKS5 ->
                            "Recommended: carries every kind of connection Lucense makes."
                        else ->
                            "Applied straight away to every new connection."
                    }
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            GhostButton("Test connection") { controller.testProxy() }
            controller.proxyTestNote?.let { note ->
                Spacer(Modifier.width(10.dp))
                Text(note, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
            }
        }
    }
}

/** Windows' own open dialog, filtered to pictures. */
private fun chooseImage(): File? {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Choose a wallpaper", java.awt.FileDialog.LOAD)
    dialog.file = "*.jpg;*.jpeg;*.png;*.webp;*.bmp"
    dialog.isVisible = true
    val name = dialog.file ?: return null
    return File(dialog.directory, name)
}
