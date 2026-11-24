package com.exo.musicplayer.desktop.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.exo.musicplayer.desktop.download.OwnSignIn
import com.exo.musicplayer.desktop.download.YouTubeSignIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The YouTube sign-in yt-dlp downloads with, for VPN addresses YouTube refuses:
 * which one is chosen, checking it, Lucense's own sign-in window, and renewing
 * a browser's copy.
 *
 * Part of [DesktopController], which makes it and hands the UI's calls through.
 * [scope] is the controller's own, so a failure here is caught and logged like
 * any of its other work.
 */
internal class YouTubeSignInSettings(
    private val scope: CoroutineScope,
    private val settings: DesktopSettings
) {
    private val signInState = mutableStateOf(YouTubeSignIn.Source.of(settings.youtubeSignIn))
    private val cookieFileState = mutableStateOf(settings.youtubeCookieFile)

    init {
        YouTubeSignIn.source = signInState.value
        YouTubeSignIn.cookieFile = cookieFileState.value.takeIf { it.isNotBlank() }?.let(::File)
    }

    /** Which sign-in yt-dlp downloads with. */
    var youtubeSignIn: YouTubeSignIn.Source
        get() = signInState.value
        set(value) {
            signInState.value = value
            settings.youtubeSignIn = value.name
            YouTubeSignIn.source = value
            signInNote = null
            signInKeptAt = keptAtFor(value)
        }

    /** The cookies.txt file, when that is the sign-in. */
    var youtubeCookieFile: String
        get() = cookieFileState.value
        set(value) {
            cookieFileState.value = value
            settings.youtubeCookieFile = value
            YouTubeSignIn.cookieFile = value.takeIf { it.isNotBlank() }?.let(::File)
            signInNote = null
        }

    /** What the last check of the sign-in found. */
    var signInNote by mutableStateOf<String?>(null)
        private set
    var checkingSignIn by mutableStateOf(false)
        private set

    /** When the kept sign-in was made or last renewed, for the panel; null when there is none for the chosen source. */
    var signInKeptAt by mutableStateOf(keptAtFor(signInState.value))
        private set

    private fun keptAtFor(chosen: YouTubeSignIn.Source): Long? =
        YouTubeSignIn.keptAt()?.takeIf { YouTubeSignIn.keptSource() == chosen }

    /** A sign-in window is open, waiting for the account. */
    var signingIn by mutableStateOf(false)
        private set

    /**
     * Asks YouTube for a page only a signed-in account sees, with the sign-in
     * as it is set. For a browser, the same run makes Lucense's copy of its
     * sign-in, so this is also how the copy is renewed by hand.
     */
    fun checkYouTubeSignIn() {
        if (checkingSignIn || signingIn) return
        val chosen = youtubeSignIn
        if (chosen == YouTubeSignIn.Source.OFF) {
            signInNote = "Choose where the sign-in comes from first."
            return
        }
        if (chosen == YouTubeSignIn.Source.FILE && !YouTubeSignIn.active) {
            signInNote = "Choose the cookies.txt file first."
            return
        }
        if (chosen == YouTubeSignIn.Source.OWN && !YouTubeSignIn.active) {
            signInNote = "Sign in first."
            return
        }
        if (!ToolPaths.ytDlp.isFile) {
            signInNote = "Install yt-dlp first."
            return
        }
        checkingSignIn = true
        signInNote = if (chosen.copied) "Copying ${chosen.label}'s sign-in and checking it with YouTube…" else "Checking with YouTube…"
        scope.launch {
            val error = if (chosen.copied) copySignIn(chosen) else runSignInCheck(YouTubeSignIn.ytDlpArgs())
            signInNote = YouTubeSignIn.explain(error, chosen)
            signInKeptAt = keptAtFor(chosen)
            checkingSignIn = false
        }
    }

    /** yt-dlp's errors from asking for the check page with [signIn], or blank when it worked. */
    private suspend fun runSignInCheck(signIn: List<String>): String = io {
        runCatching {
            val process = ProcessBuilder(
                listOf(ToolPaths.ytDlp.absolutePath) + YouTubeSignIn.CHECK_ARGUMENTS + NetworkProxy.ytDlpArgs() + signIn
            ).redirectErrorStream(false).also(NetworkProxy::configure).start()
            val drain = Thread { process.inputStream.use { it.readBytes() } }.apply { isDaemon = true; start() }
            val err = process.errorStream.bufferedReader().use { it.readText() }
            process.waitFor()
            drain.join(2_000)
            err.lineSequence().filter { it.trim().startsWith("ERROR") }.joinToString("\n")
        }.getOrElse { "ERROR: ${it.message}" }
    }

    /**
     * Copies [chosen]'s sign-in into Lucense's own file while checking it:
     * yt-dlp reads the browser and writes everything it read into a scratch
     * file, of which YouTube's and Google's cookies are kept and the rest
     * deleted straight away. Blank when it worked.
     */
    private suspend fun copySignIn(chosen: YouTubeSignIn.Source): String {
        val scratch = YouTubeSignIn.scratch()
        return try {
            val error = runSignInCheck(YouTubeSignIn.copyArguments(chosen, scratch))
            when {
                error.isNotBlank() -> error
                !io { YouTubeSignIn.keep(scratch, chosen) } -> "ERROR: Login details are needed"
                else -> ""
            }
        } finally {
            io { scratch.delete() }
        }
    }

    /**
     * Renews a browser's copy without a word when it is due, or when YouTube
     * has just refused a download - at most every half hour. If the browser
     * holds its cookies locked just then, the copy there is kept and used.
     */
    fun renewSignInQuietly(refused: Boolean = false) {
        val chosen = youtubeSignIn
        if (!chosen.copied || checkingSignIn || !ToolPaths.ytDlp.isFile) return
        if (!refused && !YouTubeSignIn.needsRenewal()) return
        val now = System.currentTimeMillis()
        if (now - lastQuietRenewal < QUIET_RENEWAL_GAP_MS) return
        lastQuietRenewal = now
        scope.launch {
            if (copySignIn(chosen).isBlank()) signInKeptAt = keptAtFor(chosen)
        }
    }

    private var lastQuietRenewal = 0L

    init {
        // A copy past its time is renewed once Lucense is up, out of the way.
        scope.launch {
            delay(20_000)
            renewSignInQuietly()
        }
    }

    /** Opens a browser window of Lucense's own to sign in to YouTube once ([OwnSignIn]). */
    fun signInHere() {
        if (signingIn || checkingSignIn) return
        signingIn = true
        signInNote = "Opening a browser window…"
        scope.launch {
            val outcome = OwnSignIn.signIn(NetworkProxy.urlFor()) { status -> scope.launch { signInNote = status } }
            signingIn = false
            when (outcome) {
                OwnSignIn.Outcome.Kept -> {
                    signInKeptAt = keptAtFor(YouTubeSignIn.Source.OWN)
                    signInNote = "Signed in. Checking with YouTube…"
                    checkingSignIn = true
                    val error = runSignInCheck(YouTubeSignIn.ytDlpArgs())
                    signInNote = YouTubeSignIn.explain(error, YouTubeSignIn.Source.OWN)
                    checkingSignIn = false
                }
                OwnSignIn.Outcome.Stopped -> signInNote = "Stopped; nothing was kept."
                OwnSignIn.Outcome.NotSignedIn -> signInNote =
                    "The window closed before YouTube was signed in to, so nothing was kept. Sign in again, and close " +
                        "the window (or press Done) once YouTube shows your account."
                is OwnSignIn.Outcome.Failed -> signInNote = outcome.why
            }
        }
    }

    /** Closes a sign-in window that is still waiting. */
    fun stopSigningIn() = OwnSignIn.stop()

    /** Signed in, in the window: it is closed and the sign-in read. */
    fun doneSigningIn() = OwnSignIn.done()

    /** Forgets the kept sign-in: Lucense's copy is deleted, and downloads go signed out. */
    fun forgetYouTubeSignIn() {
        YouTubeSignIn.forget()
        signInKeptAt = null
        signInNote = "Signed out: Lucense's copy of the sign-in is deleted."
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private companion object {
        /** A browser's sign-in is copied quietly at most this often. */
        const val QUIET_RENEWAL_GAP_MS = 30L * 60 * 1000
    }
}
