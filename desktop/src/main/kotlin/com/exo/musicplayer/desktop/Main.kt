package com.exo.musicplayer.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.WindowPosition
import androidx.compose.runtime.CompositionLocalProvider
import com.exo.musicplayer.desktop.ui.LinuxFrame
import com.exo.musicplayer.desktop.ui.NativeFrame
import com.exo.musicplayer.desktop.ui.WindowEdges
import com.exo.musicplayer.desktop.ui.LocalWindowFrame
import com.exo.musicplayer.desktop.ui.WindowCorners
import com.exo.musicplayer.desktop.ui.WindowFrame
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.window.WindowPlacement
import com.exo.musicplayer.desktop.data.DesktopController
import com.exo.musicplayer.desktop.ui.DesktopApp
import com.exo.musicplayer.desktop.ui.LyricsWindow
import com.exo.musicplayer.desktop.ui.FullscreenLyrics
import com.exo.musicplayer.desktop.ui.TrimWindow
import com.exo.musicplayer.desktop.ui.MiniPlayer
import com.exo.musicplayer.desktop.ui.Palette
import com.exo.musicplayer.desktop.ui.LucenseDesktopTheme
import com.exo.musicplayer.desktop.ui.ScaledToWindow
import com.exo.musicplayer.desktop.ui.WindowShortcuts
import com.exo.musicplayer.desktop.ui.shortcutActions
import com.exo.musicplayer.desktop.system.SingleInstance
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import com.exo.musicplayer.desktop.ui.Motion
import com.exo.musicplayer.desktop.ui.WindowFade
import java.awt.GraphicsEnvironment
import java.awt.SystemTray
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlinx.coroutines.delay
import java.awt.Dimension
import java.io.File
import javax.swing.JFileChooser
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter
import com.exo.musicplayer.desktop.system.LogFile
import com.exo.musicplayer.desktop.data.AppDirs
import com.exo.musicplayer.desktop.ui.collectNowPlaying
import com.exo.musicplayer.desktop.system.Mpris
import com.exo.musicplayer.desktop.system.Platform

/**
 * Windows entry point.
 *
 * The main window draws its own title strip, and hands moving, sizing and
 * snapping back to Windows ([NativeFrame]), so it keeps Windows' snap layouts,
 * animations and shadow.
 */
fun main(args: Array<String>) {
    // Already running - perhaps out of sight in the tray: that one takes the
    // files and comes to the front, and this one goes.
    if (SingleInstance.handOver(args)) return
    nameTheWindowClass()
    // Before anything has anything to say: from here on it is kept in a file too.
    LogFile.install(File(AppDirs.root, "logs"), AppVersion.name)
    lucense(args)
}

/**
 * GNOME, KDE and the rest tie a window to its menu entry - for its icon in the dock
 * and in Alt+Tab, its name there, and whose notifications are whose - by the
 * window's X11 class. Java names that after the main class,
 * "com-exo-musicplayer-desktop-MainKt", which no menu entry is called, so Lucense
 * showed as that, with a blank icon. The package's entry is lucense-Lucense.desktop,
 * and a window class of the same name is matched to it. Needs
 * --add-opens java.desktop/sun.awt.X11 (build.gradle.kts adds it on Linux).
 */
private fun nameTheWindowClass() {
    if (!Platform.isLinux) return
    runCatching {
        val toolkit = java.awt.Toolkit.getDefaultToolkit()
        toolkit.javaClass.getDeclaredField("awtAppClassName").apply { isAccessible = true }.set(toolkit, Mpris.DESKTOP_ENTRY)
    }.onFailure { System.err.println("Lucense: the window keeps Java's class name (${it.message})") }
}

private fun lucense(args: Array<String>) = application {
    // The folder picker is Swing; matching the OS look keeps it from standing out.
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }

    // Held here rather than inside AppHost so the controller outlives
    // recomposition and the window's key handler can reach it.
    val scope = rememberCoroutineScope()
    val controller = remember { DesktopController(scope) }

    // The main window's fade (made inside the window, where the AWT window is). Closing goes
    // through it, so the window fades before it hides or the app exits; without it (not made yet,
    // or the window is already in the tray) there is nothing to fade and it happens at once.
    var mainFade by remember { mutableStateOf<WindowFade?>(null) }
    val closeMain: () -> Unit = {
        val leave = { if (!controller.closeToTray()) exitApplication() }
        val fade = mainFade
        if (fade != null && controller.windowShown) fade.fadeOut(onDone = leave) else leave()
    }
    val quitMain: () -> Unit = {
        val fade = mainFade
        if (fade != null && controller.windowShown) fade.fadeOut(onDone = ::exitApplication) else exitApplication()
    }

    // Lucense started again while this one runs: show this one, and play what it was opened with.
    remember {
        SingleInstance.listen { paths ->
            controller.showWindow()
            if (paths.isNotEmpty()) controller.openExternal(paths)
        }
    }

    // A tray icon, so the keys and the transport are there while the window is
    // behind something else - or closed, when Lucense is kept in the tray.
    // Not every desktop has a tray; Windows always does.
    if (controller.trayAvailable) {
        val playing by controller.engine.collectNowPlaying()
        Tray(
            icon = remember { trayIcon() },
            tooltip = playing.track?.let { "${it.title} — Lucense" } ?: "Lucense",
            // A double-click on the icon, as Windows has it.
            onAction = { controller.showWindow() },
            menu = {
                Item("Open Lucense") { controller.showWindow() }
                CheckboxItem("Mini player", checked = controller.miniPlayer) { controller.miniPlayer = it }
                Separator()
                Item(if (playing.playing) "Pause" else "Play") { controller.togglePlay() }
                Item("Next") { controller.next() }
                Item("Previous") { controller.previous() }
                Separator()
                Item("Quit Lucense") { quitMain() }
            }
        )
    }

    // -Dlucense.window=900x600 starts at another size: for checking how a small window looks.
    val startSize = System.getProperty("lucense.window")?.split('x')?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.size == 2 }
    // The main window itself, for the windows that belong to it (Trim).
    var mainAwt by remember { mutableStateOf<java.awt.Window?>(null) }
    val mainWindow = rememberWindowState(width = (startSize?.get(0) ?: 1280).dp, height = (startSize?.get(1) ?: 820).dp)
    val shortcuts = remember(controller) { WindowShortcuts(controller.shortcutActions()) }
    // The other windows - the mini player, the lyrics - get the playing keys only.
    val playbackKeys = remember(controller) { WindowShortcuts(controller.shortcutActions(), playbackOnly = true) }
    Window(
        // Kept in the tray when that is chosen; otherwise closing quits.
        onCloseRequest = closeMain,
        visible = controller.windowShown,
        title = "Lucense",
        icon = painterResource(FALLBACK_ICON),
        state = mainWindow,
        // Lucense draws its own title strip and edges (WindowFrame), and
        // Windows 11 rounds the corners.
        undecorated = true,
        // Window level, not view level: the keys have to work whether or not
        // the track table happens to hold focus. Which keys are taken before
        // the focus sees them, and which only after, is in WindowShortcuts.
        onPreviewKeyEvent = shortcuts::preview,
        onKeyEvent = shortcuts::bubble
    ) {
        EveryIconSize()
        LaunchedEffect(Unit) { mainAwt = window }
        // Before Compose has painted anything the window shows its own background, which was
        // white (or black): the theme's base colour instead, so there is no flash. Done at once
        // and again once the window's inner parts exist.
        remember { paintBase(window) }
        LaunchedEffect(Unit) { paintBase(window) }
        // How this window fades. Whether the desktop can fade a whole window is found out once.
        val fadeScope = rememberCoroutineScope()
        val fade = remember { WindowFade({ window.opacity = it }, WindowFade.canFadeWindow(window), fadeScope) }
        DisposableEffect(fade) {
            mainFade = fade
            // Never leave the window part-way if this goes away mid-fade.
            onDispose { mainFade = null; fade.cancel() }
        }
        // Back from the tray: it was faded out to nothing, so fade it in.
        LaunchedEffect(controller.windowShown) { if (controller.windowShown) fade.fadeIn() }
        // The smallest window the scaled layout still fits in.
        LaunchedEffect(Unit) { window.minimumSize = Dimension(760, 500) }
        // Asked to the front - from the tray, the mini player, or a second start.
        LaunchedEffect(controller.windowRaised) {
            if (controller.windowRaised == 0) return@LaunchedEffect
            // A minimised window is still at full opacity, so it starts over; one that was
            // mid-fade-out (closed, then opened again) turns round from where it is.
            fade.fadeIn(restart = mainWindow.isMinimized)
            mainWindow.isMinimized = false
            window.toFront()
            window.requestFocus()
        }
        // Anything Windows handed over: a double-clicked file, or "Open with".
        LaunchedEffect(Unit) { controller.openExternal(args.toList()) }
        // The title strip's close button, like the window's: into the tray when that is chosen.
        val frame = remember { WindowFrame(window) { closeMain() } }
        LaunchedEffect(Unit) {
            // Windows - on Linux, the window manager - moves, sizes and snaps it
            // from here on; Compose's own resize border along the edges would only
            // be in the way. On Linux, WindowEdges below takes its place.
            if (NativeFrame.install(window, frame) || LinuxFrame.install(window, frame)) {
                window.undecoratedResizerThickness = 0.dp
            }
            WindowCorners.round(window)
        }
        CompositionLocalProvider(LocalWindowFrame provides frame) {
            LucenseDesktopTheme {
                // Drawn, not laid out: when the window can't be faded as a whole, the picture is.
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = fade.contentAlpha }) {
                    // The layout adapts down to about 920 wide (a narrower
                    // sidebar and panel, buttons as icons); only below that is
                    // it drawn smaller, and never under 80 %.
                    ScaledToWindow(designWidth = 920.dp, designHeight = 600.dp, minScale = 0.8f) {
                        AppHost(controller)
                    }
                    // Over everything, but only along the window's edges.
                    WindowEdges(frame)
                }
            }
        }
    }

    if (controller.miniPlayer) {
        // Where it was left last time, or the bottom right corner of the screen.
        val miniState = rememberWindowState(
            width = 392.dp,
            height = 128.dp,
            position = controller.settings.miniPlayerAt.split(',').mapNotNull { it.trim().toFloatOrNull() }
                .takeIf { it.size == 2 }
                ?.let { (x, y) -> WindowPosition(x.dp, y.dp) }
                ?: WindowPosition(Alignment.BottomEnd)
        )
        Window(
            onCloseRequest = { controller.miniPlayer = false },
            title = "Lucense",
            icon = painterResource(FALLBACK_ICON),
            state = miniState,
            undecorated = true,
            transparent = true,
            resizable = false,
            alwaysOnTop = true,
            onPreviewKeyEvent = playbackKeys::preview,
            onKeyEvent = playbackKeys::bubble
        ) {
            EveryIconSize()
            LaunchedEffect(Unit) {
                snapshotFlow { miniState.position }.collect { at ->
                    if (at is WindowPosition.Absolute) controller.settings.miniPlayerAt = "${at.x.value},${at.y.value}"
                }
            }
            LucenseDesktopTheme {
                MiniPlayer(
                    controller,
                    onExpand = { controller.showWindow() },
                    onClose = { controller.miniPlayer = false }
                )
            }
        }
    }

    controller.trimming?.let { track ->
        // Owned by the main window, so it stays in front of it; another song's Trim is a new window.
        key(track.file) { TrimWindow(controller, track, mainAwt) { controller.trimming = null } }
    }

    if (controller.lyricsFullscreen) {
        Window(
            onCloseRequest = { controller.lyricsFullscreen = false },
            title = "Lyrics · Lucense",
            icon = painterResource(FALLBACK_ICON),
            // A borderless window the size of the screen rather than Windows'
            // exclusive full screen, which screenshots and recordings can't see.
            state = rememberWindowState(
                placement = WindowPlacement.Floating,
                position = WindowPosition(screenUnderPointer().x.dp, screenUnderPointer().y.dp),
                size = DpSize(screenUnderPointer().width.dp, screenUnderPointer().height.dp)
            ),
            undecorated = true,
            alwaysOnTop = true,
            onPreviewKeyEvent = playbackKeys::preview,
            onKeyEvent = { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    controller.lyricsFullscreen = false
                    true
                } else {
                    playbackKeys.bubble(event)
                }
            }
        ) {
            EveryIconSize()
            LucenseDesktopTheme { FullscreenLyrics(controller) }
        }
    }

    if (controller.lyricsDetached) {
        Window(
            onCloseRequest = { controller.lyricsDetached = false },
            title = "Lyrics · Lucense",
            icon = painterResource(FALLBACK_ICON),
            state = rememberWindowState(width = 460.dp, height = 700.dp),
            onPreviewKeyEvent = playbackKeys::preview,
            onKeyEvent = playbackKeys::bubble
        ) {
            EveryIconSize()
            LaunchedEffect(Unit) { window.minimumSize = Dimension(300, 360) }
            LucenseDesktopTheme {
                ScaledToWindow(designWidth = 420.dp, designHeight = 560.dp) { LyricsWindow(controller) }
            }
        }
    }
}

/**
 * The sizes the icon is drawn at, the same set as the installer's .ico. Below
 * 128 pixels they show the cat alone, because the wordmark can't be read that
 * small.
 */
private val ICON_SIZES = listOf(16, 20, 24, 32, 40, 48, 64, 128, 256)

/** What a window shows for the moment before [EveryIconSize] takes over. */
private const val FALLBACK_ICON = "icons/lucense-64.png"

private val iconImages: List<BufferedImage> by lazy {
    ICON_SIZES.mapNotNull { size ->
        Thread.currentThread().contextClassLoader
            ?.getResourceAsStream("icons/lucense-$size.png")
            ?.use { ImageIO.read(it) }
    }
}

/**
 * Hands the window its icon at every size, so Windows picks one instead of
 * shrinking a single picture itself.
 *
 * Windows does that shrink in one step, and it is where the speckle came from.
 * Measured against a clean box-filtered shrink, one step is 13 levels out at
 * 16 pixels; these images, halved down to size, are under one level out.
 */
@Composable
private fun FrameWindowScope.EveryIconSize() {
    LaunchedEffect(Unit) {
        if (iconImages.isNotEmpty()) window.iconImages = iconImages
    }
}

/** The tray's picture, at the size the tray shows it on this screen. */
private fun trayIcon(): Painter {
    val scale = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
            .defaultConfiguration.defaultTransform.scaleX
    }.getOrDefault(1.0)
    val wanted = ceil(SystemTray.getSystemTray().trayIconSize.width * scale).toInt()
    val image = iconImages.firstOrNull { it.width >= wanted } ?: iconImages.last()
    return BitmapPainter(image.toComposeImageBitmap())
}

@Composable
private fun AppHost(controller: DesktopController) {
    val status by controller.engine.collectNowPlaying()

    // Shown once per launch, not on every navigation — a splash that reappears
    // when you switch tabs is an interruption, not an introduction.
    var booting by remember { mutableStateOf(true) }

    DisposableEffect(Unit) {
        controller.start()
        onDispose { controller.release() }
    }

    // The start-up screen leaves once the interface has really drawn a frame (not at a fixed
    // time from the start, which a slow first frame used up), and then it fades rather than goes.
    LaunchedEffect(Unit) {
        withFrameNanos { }
        withFrameNanos { }
        delay(Motion.BOOT_HOLD_MS)
        booting = false
    }
    val bootAlpha by animateFloatAsState(if (booting) 1f else 0f, Motion.firstFrame())

    // Roll on to the next track when one runs out. The engine says when that
    // happened; working it out from the position meant that pausing a track
    // whose length was unknown counted as finishing it, and skipped ahead.
    LaunchedEffect(status.ended, status.track) {
        if (status.ended) controller.advance()
    }

    Box(Modifier.fillMaxSize()) {
        DesktopApp(
            controller,
            onChooseFolder = ::chooseFolder,
            onChooseMedia = ::chooseMediaFile,
            onPickPlaylistFile = ::choosePlaylistFile
        )
        // The interface is complete underneath, so fading this out is the interface fading in.
        if (bootAlpha > 0f) BootScreen(Modifier.graphicsLayer { alpha = bootAlpha })
    }
}

/**
 * The window's own background, in the theme's base colour, on it and everything inside it.
 * Before the first frame that is all there is to see - and the default is white.
 */
private fun paintBase(window: java.awt.Window) {
    val base = java.awt.Color(Palette.Base.toArgb() or 0xFF000000.toInt(), false)
    fun paint(part: java.awt.Component) {
        runCatching { part.background = base }
        (part as? java.awt.Container)?.components?.forEach { paint(it) }
    }
    paint(window)
}

@Composable
private fun BootScreen(modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(500))
    LaunchedEffect(Unit) { visible = true }

    Box(
        modifier.fillMaxSize().background(Palette.Base),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.alpha(alpha)
        ) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(Palette.Accent))
            Spacer(Modifier.height(20.dp))
            Text(
                "Lucense",
                style = MaterialTheme.typography.headlineMedium,
                color = Palette.Text
            )
        }
    }
}

/**
 * File picker for identification.
 *
 * Deliberately unfiltered beyond a convenience filter: the fingerprinter takes
 * whatever ffmpeg can open, and a hard whitelist would refuse files that would
 * in fact have worked.
 */
private fun chooseMediaFile(): File? {
    val chooser = JFileChooser().apply {
        dialogTitle = "Choose audio or video to identify"
        fileSelectionMode = JFileChooser.FILES_ONLY
        isAcceptAllFileFilterUsed = true
        fileFilter = FileNameExtensionFilter(
            "Audio and video",
            "mp3", "flac", "wav", "ogg", "opus", "m4a", "aac", "wma", "aiff", "alac",
            "mp4", "mkv", "webm", "mov", "avi", "m4v", "3gp", "flv", "wmv", "mpg", "mpeg"
        )
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile
    } else {
        null
    }
}

/**
 * Open/save picker for shared playlists.
 *
 * Defaults to .txt because the whole point is a file that can be emailed or
 * pasted into a chat without anyone wondering what it is.
 */
private fun choosePlaylistFile(save: Boolean, suggested: String): File? {
    val chooser = JFileChooser().apply {
        dialogTitle = if (save) "Export playlist" else "Import a playlist"
        fileSelectionMode = JFileChooser.FILES_ONLY
        fileFilter = FileNameExtensionFilter("Playlist text file", "txt", "m3u", "csv")
        if (save && suggested.isNotBlank()) {
            selectedFile = File(suggested.replace(Regex("""[\/:*?"<>|]"""), "_") + ".txt")
        }
    }
    val approved = if (save) {
        chooser.showSaveDialog(null)
    } else {
        chooser.showOpenDialog(null)
    } == JFileChooser.APPROVE_OPTION
    if (!approved) return null

    val chosen = chooser.selectedFile ?: return null
    return if (save && chosen.extension.isBlank()) File(chosen.absolutePath + ".txt") else chosen
}

private fun chooseFolder(): File? {
    val chooser = JFileChooser().apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "Choose a folder"
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile
    } else {
        null
    }
}

/** The bounds of the screen the pointer is on: where the button for fullscreen lyrics was pressed. */
private fun screenUnderPointer(): java.awt.Rectangle {
    val at = java.awt.MouseInfo.getPointerInfo()?.location
    val screens = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration.bounds }
    return screens.firstOrNull { at != null && it.contains(at) } ?: screens.first()
}
