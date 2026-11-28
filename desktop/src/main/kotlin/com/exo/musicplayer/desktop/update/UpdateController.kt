package com.exo.musicplayer.desktop.update

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.exo.musicplayer.data.update.Updates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** What an update would download. */
data class UpdateSummary(
    val files: Int,
    /** The files as installed: what the free-space check needs. */
    val bytes: Long,
    val stale: Int,
    /** What is actually downloaded (the zip's compressed sizes); [bytes] when the zip couldn't be read. */
    val transferBytes: Long = bytes,
    /** False when [transferBytes] is only the installed total, an upper bound: shown as "up to". */
    val transferExact: Boolean = true
)

/** Where the in-app update stands; Compose state on [UpdateController.state]. */
sealed interface UpdateState {
    data object Idle : UpdateState

    data object Checking : UpdateState

    /**
     * A newer release is out. [canDelta] says whether it can be installed in
     * place; when not, [whyNot] says why and the UI offers the release page.
     * [summary] is null while the installed files are still being compared.
     */
    data class Available(
        val release: UpdateRelease,
        val summary: UpdateSummary?,
        val canDelta: Boolean,
        val whyNot: String?
    ) : UpdateState

    data class Downloading(
        val version: String,
        val done: Int,
        val total: Int,
        val bytesDone: Long,
        val bytesTotal: Long
    ) : UpdateState

    /** Downloaded and checked; applied only when the user says so. [note]: e.g. "Update cancelled." */
    data class Ready(val version: String, val note: String? = null, val deferred: Boolean = false) : UpdateState

    data object Applying : UpdateState

    data class Failed(val message: String, val canRetry: Boolean) : UpdateState
}

/**
 * The in-app updater: check, download only what changed, then hand over to the
 * helper script and exit.
 *
 * Nothing is downloaded until [download] is called (the user pressed the
 * button); [check] only reads the release list and the manifest and compares
 * the installed files.
 *
 * Integrity rests on GitHub's HTTPS and the SHA-256s in the release's
 * manifest; nothing is signed yet (see [UpdateManifest]).
 */
class UpdateController(
    private val scope: CoroutineScope,
    private val currentVersion: String,
    /** Null: not an installed copy this can update (a run from Gradle, an unknown layout). */
    private val layout: InstallLayout?,
    /** `<AppDirs.root>/update`: staging folders and result.json. */
    private val updatesRoot: File,
    private val http: UpdateHttp = UpdateHttp(),
    private val releasesUrl: String = Updates.RELEASES,
    private val findProgram: (String) -> File? = { null },
    /** Asks the app to quit; called once the helper is running. */
    private val exitApp: () -> Unit = {},
    /** Extra environment for the helper (the tests shorten its wait). */
    private val helperEnvironment: Map<String, String> = emptyMap(),
    private val pid: Long = ProcessHandle.current().pid(),
    private val now: () -> Long = System::currentTimeMillis
) {
    var state by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set

    /** The newest release found, newer or not. */
    var release by mutableStateOf<UpdateRelease?>(null)
        private set

    /** A line for the Updates row: "Up to date.", "Could not reach GitHub.", the last update's outcome. */
    var note by mutableStateOf<String?>(null)
        private set

    /** Whether installing needs administrator rights (a system install): the notice says a password will be asked. */
    var needsAdmin by mutableStateOf(false)
        private set

    /** Whether the last update's result (from [startup]) was a success; null when there was none. */
    var lastResultOk by mutableStateOf<Boolean?>(null)
        private set

    private val stager = UpdateStager(updatesRoot)
    private var plan: UpdatePlan? = null
    private var job: Job? = null

    /** The compressed size of each file in the plan's toFetch, or null when the zip couldn't be read at check time. */
    private var transfer: List<Long>? = null

    /** Set by [updateNow]: once the download is staged, go straight on to applying. */
    private var applyWhenReady = false

    val resultFile: File get() = File(updatesRoot, RESULT)

    /** Whether this copy could ever update in place; when not, the UI keeps the release-page link. */
    val canUpdateInPlace: Boolean get() = layout != null

    /** Whether the newest release found is newer than this one. */
    val newer: Boolean get() = release?.let { Updates.isNewer(it.version, currentVersion) } == true

    /**
     * Reads the release list (and, when there is a newer release, its manifest)
     * and compares the installed files. [onDone] gets the release found, or null.
     */
    fun check(onDone: (UpdateRelease?) -> Unit = {}) {
        if (state is UpdateState.Checking || state is UpdateState.Downloading || state is UpdateState.Applying) return
        state = UpdateState.Checking
        job = scope.launch {
            val found = try {
                withContext(Dispatchers.IO) {
                    http.getText(releasesUrl, MAX_RELEASES_BYTES, "application/vnd.github+json")?.let(UpdateRelease::parse)
                }
            } catch (e: CancellationException) {
                state = UpdateState.Idle
                throw e
            } catch (e: IOException) {
                null
            }
            if (found == null) {
                note = "Could not reach GitHub."
                state = UpdateState.Idle
                onDone(null)
                return@launch
            }
            release = found
            if (!Updates.isNewer(found.version, currentVersion)) {
                note = "Up to date."
                plan = null
                state = UpdateState.Idle
                withContext(Dispatchers.IO) { cleanStaging(keep = null) }
                onDone(found)
                return@launch
            }
            note = "Version ${found.version} is out."
            onDone(found)
            try {
                prepare(found)
            } catch (e: CancellationException) {
                plan = null
                state = UpdateState.Idle
                throw e
            }
        }
    }

    /** Works out what [found] would change here; ends in Available or (already downloaded) Ready. */
    private suspend fun prepare(found: UpdateRelease) {
        val layout = layout
        if (layout == null) {
            state = UpdateState.Available(found, null, false, "This copy of Lucense wasn't installed by its installer, so it can't update itself.")
            return
        }
        val manifestAsset = found.manifestFor(layout.platform)
        if (manifestAsset == null) {
            state = UpdateState.Available(found, null, false, "This release has no update files for ${layout.platform}.")
            return
        }
        state = UpdateState.Available(found, null, true, null)
        transfer = null
        try {
            val made = withContext(Dispatchers.IO) {
                val text = http.getText(manifestAsset.url, UpdateManifest.MAX_BYTES)
                    ?: throw UpdateException("The release's update list couldn't be downloaded.")
                val manifest = UpdateManifest.fromJson(text)
                checkManifest(manifest, found, layout)
                if (found.asset(manifest.zip) == null) throw UpdateException("The release has no ${manifest.zip}.")
                UpdatePlanner.plan(manifest, layout)
            }
            if (made.isEmpty) {
                plan = null
                state = UpdateState.Available(found, null, false, "The installed files already match ${found.version}.")
                return
            }
            plan = made
            val complete = withContext(Dispatchers.IO) {
                cleanStaging(keep = made.version)
                stager.isComplete(made)
            }
            needsAdmin = withContext(Dispatchers.IO) { UpdateApplier.elevation(layout, made, findProgram) !is Elevation.None }
            if (!complete) transfer = compressedSizesOf(found, made)
            state = if (complete) UpdateState.Ready(made.version) else UpdateState.Available(found, summaryOf(made), true, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            plan = null
            state = UpdateState.Available(found, null, false, e.message ?: "The release's update list couldn't be read.")
        }
    }

    /**
     * Reads the zip's central directory (a couple of small ranged requests) for
     * the size that will really be downloaded; null if it can't be read, and
     * the offer then says "up to" the installed total.
     */
    private suspend fun compressedSizesOf(found: UpdateRelease, made: UpdatePlan): List<Long>? {
        val asset = found.asset(made.manifest.zip) ?: return null
        return try {
            withContext(Dispatchers.IO) {
                val zip = ZipOverHttp(http.Ranged(asset.url))
                zip.open()
                UpdateTransfer.compressedSizes(made, zip)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The manifest must be for this release and this system, and newer than
     * what runs: a manifest that would "update" to an older or the same
     * version (a replayed old release, a mixed-up asset) is refused.
     */
    private fun checkManifest(manifest: UpdateManifest, found: UpdateRelease, layout: InstallLayout) {
        if (manifest.version != found.version) throw ManifestException("The update list is for ${manifest.version}, not ${found.version}.")
        if (!Updates.isNewer(manifest.version, currentVersion)) throw ManifestException("The update list isn't newer than this version.")
        if (manifest.platform != layout.platform) throw ManifestException("The update list is for ${manifest.platform}.")
    }

    /** Downloads the changed files. Only ever called from the user's button. */
    fun download() {
        val available = state as? UpdateState.Available ?: return
        val made = plan ?: return
        val layout = layout ?: return
        if (!available.canDelta) return
        val zipAsset = available.release.asset(made.manifest.zip) ?: return
        // Progress is told in downloaded (compressed) bytes, as the size offered was.
        val sizes = transfer
        val shownTotal = sizes?.sum() ?: made.bytes
        state = UpdateState.Downloading(made.version, 0, made.toFetch.size, 0, shownTotal)
        job = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val zip = ZipOverHttp(http.Ranged(zipAsset.url))
                    zip.open()
                    var shown = 0L
                    stager.stage(made, zip, layout.root) { p ->
                        // A few updates a second are plenty for a progress bar.
                        val at = now()
                        if (at - shown >= 100 || p.filesDone == p.filesTotal) {
                            shown = at
                            val shown = if (sizes == null) p.bytesDone else UpdateTransfer.shown(made, sizes, p.bytesDone)
                            state = UpdateState.Downloading(made.version, p.filesDone, p.filesTotal, shown, shownTotal)
                        }
                    }
                    if (!stager.isComplete(made)) throw UpdateException("The downloaded files didn't check out.")
                }
                state = UpdateState.Ready(made.version)
                // "Update now": the go-ahead covers the restart too.
                if (applyWhenReady) {
                    applyWhenReady = false
                    applyAndRestart()
                }
            } catch (e: CancellationException) {
                applyWhenReady = false
                state = UpdateState.Available(available.release, summaryOf(made), true, null)
                throw e
            } catch (e: RangesUnsupportedException) {
                applyWhenReady = false
                state = UpdateState.Failed(e.message ?: "ranges unsupported", canRetry = false)
            } catch (e: Exception) {
                // The chain stops at the first failure; what is staged stays.
                applyWhenReady = false
                state = UpdateState.Failed(e.message ?: "The download failed.", canRetry = true)
            }
        }
    }

    /** Stops a download (what is already verified stays staged) or a check. */
    fun cancel() {
        job?.cancel()
    }

    /** "Not now": the update stays staged and is applied only when asked. */
    fun later() {
        val ready = state as? UpdateState.Ready ?: return
        state = ready.copy(deferred = true, note = null)
    }

    /** "Try again" after a failure: back to Ready if the files are all staged, otherwise a fresh check. */
    fun retry() {
        val made = plan
        val found = release
        if (made == null || found == null) {
            state = UpdateState.Idle
            check()
            return
        }
        job = scope.launch {
            val complete = withContext(Dispatchers.IO) { stager.isComplete(made) }
            state = if (complete) UpdateState.Ready(made.version) else UpdateState.Available(found, summaryOf(made), true, null)
            if (!complete) {
                download()
            } else if (applyWhenReady) {
                applyWhenReady = false
                applyAndRestart()
            }
        }
    }

    /**
     * The one click of the launch notice: download (verified and staged), then
     * apply and restart without a second "Restart to update". Stops at the
     * first failure, leaving the staged files and the state for the UI to show;
     * a dismissed password prompt ends as Ready with "Update cancelled.".
     */
    fun updateNow() {
        when (val current = state) {
            is UpdateState.Ready -> applyAndRestart()
            is UpdateState.Available -> {
                applyWhenReady = true
                download()
                if (state !is UpdateState.Downloading) applyWhenReady = false
            }
            is UpdateState.Failed -> if (current.canRetry && plan != null) {
                applyWhenReady = true
                retry()
            }
            else -> Unit
        }
    }

    /**
     * The look-up made as the app starts. With [enabled] off nothing is asked
     * of the network at all; returns whether it looked.
     */
    fun startupCheck(enabled: Boolean, onDone: (UpdateRelease?) -> Unit = {}): Boolean {
        if (!enabled) return false
        check(onDone)
        return true
    }

    /**
     * Starts the helper and, once it is running with the rights it needs,
     * exits the app. A dismissed password or UAC prompt leaves the app running
     * and the update staged.
     */
    fun applyAndRestart() {
        val ready = state as? UpdateState.Ready ?: return
        val made = plan ?: return
        val layout = layout ?: return
        state = UpdateState.Applying
        job = scope.launch {
            val outcome = try {
                withContext(Dispatchers.IO) { startHelper(made, layout) }
            } catch (e: CancellationException) {
                state = ready
                throw e
            } catch (e: Exception) {
                HelperStart.Failed(e.message ?: "The update helper couldn't be started.")
            }
            when (outcome) {
                HelperStart.Ready -> exitApp()
                HelperStart.Cancelled -> state = UpdateState.Ready(made.version, note = "Update cancelled.")
                is HelperStart.Failed -> state = UpdateState.Failed(outcome.message, canRetry = true)
            }
        }
    }

    private suspend fun startHelper(made: UpdatePlan, layout: InstallLayout): HelperStart {
        // Checked again: the staging folder is the user's, and minutes may have passed.
        if (!stager.isComplete(made)) throw UpdateException("The downloaded files changed since they were checked; download them again.")
        val elevation = UpdateApplier.elevation(layout, made, findProgram)
        if (elevation is Elevation.Impossible) return HelperStart.Failed(elevation.reason)
        val staging = stager.folder(made.version)
        // Only the Windows helper without elevation runs from a staged file, as the
        // user; anything that gets administrator rights is handed its code in memory.
        val script = if (layout.windows && elevation == Elevation.None) UpdateApplier.writeScript(staging) else null
        val planFile = UpdateApplier.writePlan(made, staging)
        resultFile.delete()
        val request = HelperRequest(
            pid = pid,
            root = layout.root,
            staging = staging,
            version = made.version,
            plan = planFile,
            result = resultFile,
            launcher = layout.launcherPath,
            windows = layout.windows,
            elevation = elevation,
            script = script
        )
        val command = UpdateApplier.command(request, setsid = if (layout.windows) null else findProgram("setsid"))
        val process = UpdateApplier.start(command, staging, helperEnvironment + UpdateApplier.environment(request))
        // Someone may need a while to type a password; without a prompt it is instant.
        val timeout = if (elevation == Elevation.None) 30_000L else 5 * 60_000L
        return UpdateApplier.awaitStart(process, staging, timeout)
    }

    /**
     * Once, as the app starts: what the last update came to (the message is
     * returned for a one-time notice, and kept as [note]), then the staging
     * folders that are no longer wanted are cleared.
     */
    fun startup(): String? {
        val result = UpdateApplier.readResult(resultFile)
        val message = result?.let {
            if (it.ok) UpdateText.updated(it.version) else UpdateText.rolledBack(it.version, it.error ?: "no reason given")
        }
        if (resultFile.exists()) {
            result?.version?.takeIf(UpdateManifest::isVersion)?.let { version ->
                // The helper's log is kept, the staging folder (with its backup) goes.
                File(updatesRoot, "$version/${UpdateApplier.LOG}").takeIf { it.isFile }?.let { log ->
                    runCatching { log.copyTo(File(updatesRoot, LAST_LOG), overwrite = true) }
                }
            }
            resultFile.delete()
        }
        if (message != null) note = message
        lastResultOk = result?.ok
        cleanStaging(keep = null, onlyOld = true)
        return message
    }

    /**
     * Clears staging folders. With [onlyOld], those for this version or an
     * older one (applied, or overtaken) and any untouched for a week; without,
     * every one but [keep]'s, and that one too once it is a week old.
     */
    fun cleanStaging(keep: String?, onlyOld: Boolean = false) {
        val week = 7L * 24 * 60 * 60 * 1000
        updatesRoot.listFiles().orEmpty().filter { it.isDirectory }.forEach { folder ->
            val name = folder.name
            val stale = now() - folder.lastModified() > week
            val overtaken = !UpdateManifest.isVersion(name) || !Updates.isNewer(name, currentVersion)
            val remove = when {
                stale -> true
                onlyOld -> overtaken
                else -> name != keep
            }
            if (remove) deleteTree(folder.toPath())
        }
    }

    private fun summaryOf(made: UpdatePlan): UpdateSummary {
        val sizes = transfer
        return UpdateSummary(made.toFetch.size, made.bytes, made.stale.size, sizes?.sum() ?: made.bytes, sizes != null)
    }

    companion object {
        const val RESULT = "result.json"
        const val LAST_LOG = "last-update.log"
        private const val MAX_RELEASES_BYTES = 4 * 1024 * 1024

        /**
         * Deletes [root] and what is in it without following links: a link in
         * a staging folder is removed as a link, never the folder it points to.
         */
        fun deleteTree(root: Path) {
            if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
            runCatching {
                Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        Files.deleteIfExists(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        runCatching { Files.deleteIfExists(dir) }
                        return FileVisitResult.CONTINUE
                    }
                })
            }
        }
    }
}
