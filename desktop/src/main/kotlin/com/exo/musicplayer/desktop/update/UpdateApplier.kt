package com.exo.musicplayer.desktop.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** How the helper gets the rights to write into the install. */
sealed interface Elevation {
    /** The user can write every file: the helper runs as them. */
    data object None : Elevation

    /** Linux: through pkexec, which shows the system's password prompt. */
    data class Pkexec(val program: File) : Elevation

    /** Windows: the helper starts an administrator copy of itself (the UAC prompt). */
    data object RunAs : Elevation

    /** No way to get the rights from here. */
    data class Impossible(val reason: String) : Elevation
}

/** What came of starting the helper, before the app exits. */
sealed interface HelperStart {
    /** Running, with the rights it needs: the app should exit now. */
    data object Ready : HelperStart

    /** The password or UAC prompt was dismissed: the app keeps running. */
    data object Cancelled : HelperStart

    data class Failed(val message: String) : HelperStart
}

/** What the helper left in result.json. */
data class ApplyResult(val ok: Boolean, val version: String, val error: String?)

/** Everything the helper is started with. Each value is checked by [UpdateApplier.checkArgument]. */
data class HelperRequest(
    val pid: Long,
    val root: File,
    val staging: File,
    val version: String,
    val plan: File,
    val result: File,
    /** The launcher relative to [root]: `bin/Lucense` or `Lucense.exe`. */
    val launcher: String,
    val windows: Boolean,
    val elevation: Elevation,
    val script: File
)

/**
 * Puts a staged update in place, through a helper script.
 *
 * The app can't replace its own jars while it runs (Windows locks them; the JVM
 * has them open), so it writes `apply-update.sh` / `apply-update.ps1` and a plan
 * into the staging folder, starts the script detached and exits; the script
 * does the swap, with a backup and a rollback, and starts Lucense again.
 */
object UpdateApplier {

    const val SH = "apply-update.sh"
    const val PS1 = "apply-update.ps1"
    const val PLAN = "apply-plan.tsv"
    const val STATE = "apply.state"
    const val LOG = "apply.log"

    /**
     * The plan the helper follows: `path<TAB>sha256<TAB>exec` for each file to
     * install, `stale<TAB>path` for each old jar to remove. The names come from
     * a checked manifest ([UpdateManifest.isSafePath]: no tab, newline or other
     * control character), and are checked again here, since a name with a tab or
     * a newline in it could add a line of its own.
     */
    fun planText(plan: UpdatePlan): String = buildString {
        for (file in plan.toFetch) {
            require(UpdateManifest.isSafePath(file.path)) { "unsafe path" }
            append(file.path).append('\t').append(file.sha256).append('\t').append(if (file.exec) '1' else '0').append('\n')
        }
        for (path in plan.stale) {
            require(UpdateManifest.isSafePath(path) && path.endsWith(".jar", ignoreCase = true)) { "unsafe stale path" }
            append("stale\t").append(path).append('\n')
        }
    }

    /** Every install path the plan writes or removes, for [InstallLayout.isWritable]. */
    fun touchedPaths(plan: UpdatePlan): List<String> = plan.toFetch.map { it.path } + plan.stale

    /**
     * Whether the helper needs administrator rights, and how it would get them.
     * [findProgram] looks a program up on the PATH (a parameter so tests can
     * say "there is no pkexec").
     */
    fun elevation(layout: InstallLayout, plan: UpdatePlan, findProgram: (String) -> File?): Elevation = when {
        layout.isWritable(touchedPaths(plan)) -> Elevation.None
        layout.windows -> Elevation.RunAs
        else -> findProgram("pkexec")?.let { Elevation.Pkexec(it) }
            ?: Elevation.Impossible("Updating needs administrator rights, and there is no way to ask for them here: use the installer.")
    }

    /**
     * A value about to become one of the helper's arguments. Only what the app
     * itself made goes there (its pid, a version number, its own folders), and
     * each is still checked: no control characters (a newline could end the
     * argument list a shell sees), no double quote (PowerShell's elevated copy
     * gets its arguments as one quoted line), no trailing backslash (which would
     * escape that closing quote). Anything else fails rather than being cleaned
     * up, since a cleaned-up path would be a different path.
     */
    fun checkArgument(value: String): String {
        require(value.isNotEmpty()) { "empty argument" }
        require(value.none { it < ' ' || it == '\u007f' || it == '"' }) { "unsafe character in an argument" }
        require(!value.endsWith("\\")) { "trailing backslash in an argument" }
        return value
    }

    /** The helper's own arguments, in the order both scripts take them. */
    fun arguments(request: HelperRequest): List<String> {
        val mode = when (request.elevation) {
            Elevation.None -> "run"
            is Elevation.Pkexec, Elevation.RunAs -> "run-elevated"
            is Elevation.Impossible -> throw IllegalArgumentException("no way to elevate")
        }
        require(UpdateManifest.isVersion(request.version)) { "bad version" }
        require(UpdateManifest.isSafePath(request.launcher)) { "bad launcher" }
        val base = listOf(
            mode,
            request.pid.toString(),
            request.root.absolutePath,
            request.staging.absolutePath,
            request.version,
            request.plan.absolutePath,
            request.result.absolutePath,
            request.launcher
        )
        val extra = if (request.elevation is Elevation.Pkexec) listOf(request.elevation.program.absolutePath) else emptyList()
        return (base + extra).map(::checkArgument)
    }

    /**
     * The command line that starts the helper. Linux: `/bin/sh` with the script
     * (in a session of its own through `setsid` when there is one, so it is not
     * tied to the app's); with pkexec the script itself then runs
     * `pkexec /bin/sh <script> swap ...` for the part that needs root and starts
     * Lucense as the user afterwards. Windows: Windows PowerShell with the script.
     */
    fun command(request: HelperRequest, setsid: File? = null, powershell: String = "powershell.exe"): List<String> {
        val arguments = arguments(request)
        val script = checkArgument(request.script.absolutePath)
        return if (request.windows) {
            listOf(powershell, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", script) + arguments
        } else {
            listOfNotNull(setsid?.absolutePath, "/bin/sh", script) + arguments
        }
    }

    /**
     * The command the script runs through pkexec, as `apply-update.sh` builds
     * it: the same arguments with the mode `swap`, no elevation program, and the
     * user's uid and gid to hand the files made as root back to. Kept here so a
     * test can hold the script to it.
     */
    fun pkexecCommand(request: HelperRequest, uid: Long, gid: Long): List<String> {
        val elevation = request.elevation as? Elevation.Pkexec ?: throw IllegalArgumentException("not a pkexec request")
        val arguments = arguments(request)
        return listOf(elevation.program.absolutePath, "/bin/sh", request.script.absolutePath, "swap") +
            arguments.subList(1, 8) + listOf("-", uid.toString(), gid.toString())
    }

    /** Copies the helper script for this system into [staging] and returns it. */
    fun writeScript(staging: File, windows: Boolean): File {
        val name = if (windows) PS1 else SH
        val target = File(staging, name)
        val source = UpdateApplier::class.java.getResourceAsStream("/update/$name")
            ?: throw UpdateException("The update helper is missing from this build.")
        val temp = File(staging, "$name.tmp")
        source.use { input -> temp.outputStream().use { input.copyTo(it) } }
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return target
    }

    fun writePlan(plan: UpdatePlan, staging: File): File {
        val target = File(staging, PLAN)
        target.writeText(planText(plan), Charsets.UTF_8)
        return target
    }

    /**
     * Starts the helper with nothing on its input and its output in the
     * staging folder's log. The process outlives the app: neither system ends
     * a child when its parent exits.
     */
    fun start(command: List<String>, staging: File, environment: Map<String, String> = emptyMap()): Process {
        File(staging, STATE).delete()
        val log = File(staging, LOG)
        return ProcessBuilder(command)
            .directory(staging)
            .redirectInput(ProcessBuilder.Redirect.from(File(if (File.separatorChar == '\\') "NUL" else "/dev/null")))
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
            .redirectErrorStream(true)
            .also { it.environment().putAll(environment) }
            .start()
    }

    /**
     * Waits until the helper says it is running with the rights it needs
     * (`apply.state` = ready), or that the prompt was dismissed or refused.
     * [timeoutMs] bounds the wait: long enough for someone to type a password,
     * and on expiry the helper is stopped, so nothing happens behind the
     * user's back later.
     */
    suspend fun awaitStart(process: Process, staging: File, timeoutMs: Long): HelperStart = withContext(Dispatchers.IO) {
        watch(process, staging, timeoutMs)
    }

    private suspend fun watch(process: Process, staging: File, timeoutMs: Long): HelperStart {
        val state = File(staging, STATE)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            when (state.takeIf { it.isFile }?.readText()?.trim()) {
                "ready" -> return HelperStart.Ready
                "cancelled" -> return HelperStart.Cancelled
                "denied" -> return HelperStart.Failed("Lucense didn't get administrator rights to update: use the installer.")
                "failed" -> return HelperStart.Failed("The update helper stopped: ${lastLogLine(staging)}")
            }
            if (!process.isAlive) {
                // Gone without a word: a refused argument, or a shell that wouldn't start.
                if (state.isFile) continue
                return HelperStart.Failed("The update helper stopped: ${lastLogLine(staging)}")
            }
            if (System.currentTimeMillis() > deadline) {
                process.descendants().forEach { it.destroy() }
                process.destroy()
                return HelperStart.Failed("The update helper didn't start in time.")
            }
            delay(150)
        }
    }

    private fun lastLogLine(staging: File): String =
        runCatching { File(staging, LOG).readLines().lastOrNull { it.isNotBlank() } }.getOrNull()
            ?.removePrefix("apply-update: ")?.take(300) ?: "no reason given"

    /** result.json as the helper wrote it, or null if there is none or it can't be read. */
    fun readResult(file: File): ApplyResult? = runCatching {
        if (!file.isFile || file.length() > 64 * 1024) return null
        val json = JSONObject(file.readText())
        ApplyResult(json.getBoolean("ok"), json.optString("version"), json.optString("error").ifBlank { null })
    }.getOrNull()
}
