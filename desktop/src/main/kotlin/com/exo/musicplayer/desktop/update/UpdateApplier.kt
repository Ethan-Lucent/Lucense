package com.exo.musicplayer.desktop.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** How the helper gets the rights to write into the install. */
sealed interface Elevation {
    /** The user can write every file: the helper runs as them. */
    data object None : Elevation

    /** Linux: through pkexec, which shows the system's password prompt. */
    data class Pkexec(val program: File) : Elevation

    /** Windows: through the UAC prompt (Start-Process -Verb RunAs, in apply-update-elevate.ps1). */
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
    /**
     * The staged copy of apply-update.ps1, for Windows without elevation only:
     * it runs as the user, with the user's own rights. Everything else runs
     * the helper's text straight from the app (see [UpdateApplier.command]).
     */
    val script: File? = null,
    /** Ties the Windows elevated helper's answers to this request (see [UpdateApplier.command]). */
    val token: String = UpdateApplier.newToken()
)

/**
 * Puts a staged update in place, through a helper script.
 *
 * The app can't replace its own jars while it runs (Windows locks them; the JVM
 * has them open), so it writes a plan into the staging folder, starts the
 * helper detached and exits; the helper does the swap, with a backup and a
 * rollback, and starts Lucense again.
 *
 * The one rule for the part that runs as administrator: its code comes from
 * this app's own copy of the helper (a resource in the jar, which sits in the
 * install only an administrator can change), handed over in memory, and never
 * from a file in the staging folder. That folder is the user's, so any program
 * running as the user could replace a file there while the password prompt is
 * open, and the administrator would then run that program's code.
 */
object UpdateApplier {

    const val SH = "apply-update.sh"
    const val PS1 = "apply-update.ps1"
    const val ELEVATE_PS1 = "apply-update-elevate.ps1"
    const val PLAN = "apply-plan.tsv"
    const val STATE = "apply.state"
    const val LOG = "apply.log"

    /** The name `sh -c` gives the helper as its `$0`. */
    const val SH_NAME = "apply-update"

    /**
     * Ceiling for the Linux helper's text. It is passed as a single argument,
     * and Linux refuses any single argument over 128 KB; this leaves room, and
     * stops the build's tests (rather than a user's update) when the script
     * outgrows it.
     */
    const val MAX_SH_BYTES = 100 * 1024

    /**
     * Windows: the longest command line CreateProcess takes is 32,767
     * characters, program name included. Both PowerShell command lines are
     * held under this by the tests.
     */
    const val MAX_WINDOWS_COMMAND_LINE = 32_000

    /** The environment variable the Windows elevation step gets the elevated command from. */
    const val ELEVATED_ENV = "LUCENSE_UPDATE_ELEVATED"

    /** The fixed options of every PowerShell the helper starts, before the code. */
    val POWERSHELL_OPTIONS = listOf("-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden")

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
     * argument list a shell sees), no double quote and no trailing backslash
     * (either would break the quoting of a Windows command line). Anything
     * else fails rather than being cleaned up, since a cleaned-up path would
     * be a different path.
     */
    fun checkArgument(value: String): String {
        require(value.isNotEmpty()) { "empty argument" }
        require(value.none { it < ' ' || it == '\u007f' || it == '"' }) { "unsafe character in an argument" }
        require(!value.endsWith("\\")) { "trailing backslash in an argument" }
        return value
    }

    /** The helper's own arguments, in the order both scripts take them: MODE PID ROOT STAGING VERSION PLAN RESULT LAUNCHER. */
    fun arguments(request: HelperRequest): List<String> {
        val mode = when (request.elevation) {
            Elevation.None -> "run"
            is Elevation.Pkexec, Elevation.RunAs -> "run-elevated"
            is Elevation.Impossible -> throw IllegalArgumentException("no way to elevate")
        }
        require(UpdateManifest.isVersion(request.version)) { "bad version" }
        require(UpdateManifest.isSafePath(request.launcher)) { "bad launcher" }
        return listOf(
            mode,
            request.pid.toString(),
            request.root.absolutePath,
            request.staging.absolutePath,
            request.version,
            request.plan.absolutePath,
            request.result.absolutePath,
            request.launcher
        ).map(::checkArgument)
    }

    /**
     * The command line that starts the helper.
     *
     * Linux: `/bin/sh -c "<the helper's text>" apply-update MODE ...` (in a
     * session of its own through `setsid` when there is one, so it is not tied
     * to the app's). No file is run. With pkexec, the whole elevated command
     * ([pkexecCommand], with the text in it again) follows the arguments, and
     * the helper runs it word for word, then starts Lucense as the user.
     *
     * Windows without elevation: Windows PowerShell with the staged script,
     * as the user. With elevation: Windows PowerShell with the elevation step
     * as an encoded command (no file), and the elevated command in
     * [environment]; see apply-update-elevate.ps1.
     */
    fun command(
        request: HelperRequest,
        setsid: File? = null,
        powershell: String = "powershell.exe",
        ids: () -> Pair<Long, Long> = ::userIds
    ): List<String> {
        val arguments = arguments(request)
        if (request.windows) {
            if (request.elevation == Elevation.RunAs) {
                val step = invocation(scriptText(ELEVATE_PS1), elevateArguments(request))
                return listOf(powershell) + POWERSHELL_OPTIONS + listOf("-EncodedCommand", encodeCommand(step))
            }
            val script = checkArgument(requireNotNull(request.script) { "no staged script" }.absolutePath)
            return listOf(powershell) + POWERSHELL_OPTIONS + listOf("-File", script) + arguments
        }
        val elevated = if (request.elevation is Elevation.Pkexec) {
            val (uid, gid) = ids()
            pkexecCommand(request, uid, gid)
        } else {
            emptyList()
        }
        return listOfNotNull(setsid?.absolutePath, "/bin/sh", "-c", scriptText(SH), SH_NAME) + arguments + elevated
    }

    /**
     * The command run through pkexec: `pkexec /bin/sh -c "<the helper's text>"
     * apply-update swap ...` with the same arguments, then `-` and the user's
     * uid and gid (whom root acts for in the user's folders). The code root
     * runs is in the command itself, read from this app's jar a moment ago:
     * there is no file between the prompt and the code. The single place this
     * command is made; the helper runs it as given.
     */
    fun pkexecCommand(request: HelperRequest, uid: Long, gid: Long): List<String> {
        val elevation = request.elevation as? Elevation.Pkexec ?: throw IllegalArgumentException("not a pkexec request")
        val arguments = arguments(request)
        return listOf(checkArgument(elevation.program.absolutePath), "/bin/sh", "-c", scriptText(SH), SH_NAME, "swap") +
            arguments.subList(1, 8) + listOf("-", uid.toString(), gid.toString())
    }

    /** Extra environment for the helper: on Windows with elevation, the elevated command (see [command]). */
    fun environment(request: HelperRequest): Map<String, String> =
        if (request.windows && request.elevation == Elevation.RunAs) mapOf(ELEVATED_ENV to elevatedEncoded(request)) else emptyMap()

    /**
     * What the elevated Windows PowerShell runs: apply-update.ps1's text in
     * swap mode, with its arguments written into the command, encoded
     * ([packedInvocation]).
     */
    fun elevatedEncoded(request: HelperRequest): String {
        val arguments = arguments(request)
        return encodeCommand(packedInvocation(scriptText(PS1), listOf("swap") + arguments.subList(1, 8) + checkToken(request.token)))
    }

    /** The command line apply-update-elevate.ps1 gives the elevated PowerShell (it builds the same from [ELEVATED_ENV]). */
    fun elevatedArgumentLine(request: HelperRequest): String =
        (POWERSHELL_OPTIONS + listOf("-EncodedCommand", elevatedEncoded(request))).joinToString(" ")

    private fun elevateArguments(request: HelperRequest): List<String> {
        val arguments = arguments(request)
        // ROOT STAGING VERSION RESULT LAUNCHER TOKEN
        return listOf(arguments[2], arguments[3], arguments[4], arguments[6], arguments[7], checkToken(request.token))
    }

    /** A fresh random token: 32 hex digits. */
    fun newToken(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun checkToken(token: String): String {
        require(Regex("[0-9a-f]{32}").matches(token)) { "bad token" }
        return token
    }

    /**
     * PowerShell's `-EncodedCommand`: the code as UTF-16LE, in base64. Only
     * letters, digits, `+`, `/` and `=`, so nothing in it can be taken for
     * another argument or a quote on the way.
     */
    fun encodeCommand(code: String): String = Base64.getEncoder().encodeToString(code.toByteArray(Charsets.UTF_16LE))

    fun decodeCommand(encoded: String): String = String(Base64.getDecoder().decode(encoded), Charsets.UTF_16LE)

    /**
     * Runs [script] as a script block with [arguments] as its positional
     * parameters: `& { <script> } 'a' 'b' ...`. Each argument is a
     * single-quoted literal, in which PowerShell expands nothing; a single
     * quote inside is doubled, and the typographic single quotes PowerShell
     * also treats as quotes are refused rather than guessed at.
     */
    fun invocation(script: String, arguments: List<String>): String =
        "& {\n" + compact(script) + "\n} " + arguments.joinToString(" ") { powershellLiteral(checkArgument(it)) }

    /**
     * [invocation] for a script too long to fit: -EncodedCommand takes UTF-16,
     * which with base64 makes 2.7 characters of command line of every
     * character of code, and apply-update.ps1 would come to more than the
     * 32,767 Windows allows. So the script goes in gzipped (still the app's own
     * text: [unpack] gives it back, and the tests check that it does), and two
     * lines of PowerShell unpack it in memory and run it with the arguments.
     */
    fun packedInvocation(script: String, arguments: List<String>): String {
        val packed = ByteArrayOutputStream()
        GZIPOutputStream(packed).use { it.write(compact(script).toByteArray(Charsets.UTF_8)) }
        val blob = Base64.getEncoder().encodeToString(packed.toByteArray())
        return "\$z = New-Object System.IO.Compression.GZipStream((New-Object System.IO.MemoryStream(, [System.Convert]::FromBase64String('$blob'))), " +
            "[System.IO.Compression.CompressionMode]::Decompress)\n" +
            "\$t = (New-Object System.IO.StreamReader(\$z, (New-Object System.Text.UTF8Encoding(\$false)))).ReadToEnd()\n" +
            "& ([scriptblock]::Create(\$t)) " + arguments.joinToString(" ") { powershellLiteral(checkArgument(it)) }
    }

    /** The script inside a [packedInvocation], for the tests. */
    fun unpack(invocation: String): String {
        val blob = Regex("FromBase64String\\('([A-Za-z0-9+/=]+)'\\)").find(invocation)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("not a packed invocation")
        return GZIPInputStream(Base64.getDecoder().decode(blob).inputStream()).use { String(it.readBytes(), Charsets.UTF_8) }
    }

    fun powershellLiteral(value: String): String {
        require(value.none { it in "\u2018\u2019\u201a\u201b" }) { "unsafe quote in an argument" }
        return "'" + value.replace("'", "''") + "'"
    }

    /**
     * The script without its comment lines, blank lines and indentation, to
     * keep the encoded command well under Windows' limit. Safe because the
     * Windows scripts have no here-strings and no block comments (a test
     * holds them to that), so no line that starts with `#` is part of a string.
     */
    fun compact(script: String): String =
        script.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.joinToString("\n")

    /**
     * One of the helper scripts, as this app's jar has it, read into memory
     * each time it is needed (it is never kept in a file the user can write).
     */
    fun scriptText(name: String): String {
        val bytes = UpdateApplier::class.java.getResourceAsStream("/update/$name")?.use { it.readBytes() }
            ?: throw UpdateException("The update helper is missing from this build.")
        if (name == SH) check(bytes.size <= MAX_SH_BYTES) { "the update helper is too long to pass as one argument" }
        return String(bytes, Charsets.UTF_8)
    }

    /** This process's real user and group ids, as the kernel has them in /proc/self/status. */
    fun userIds(): Pair<Long, Long> {
        val status = File("/proc/self/status").readLines()
        fun id(key: String) = status.first { it.startsWith("$key:") }.substringAfter(':').trim().split(Regex("\\s+")).first().toLong()
        return id("Uid") to id("Gid")
    }

    /**
     * Copies apply-update.ps1 into [staging] for Windows without elevation
     * (see [HelperRequest.script]), and returns it.
     */
    fun writeScript(staging: File): File {
        val target = File(staging, PS1)
        val temp = File(staging, "$PS1.tmp")
        temp.writeText(scriptText(PS1), Charsets.UTF_8)
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

    /** The helper's last word: from its output, or from the log the PowerShell one keeps per mode. */
    private fun lastLogLine(staging: File): String =
        runCatching {
            staging.listFiles().orEmpty()
                .filter { it.name.startsWith("apply") && it.name.endsWith(".log") && it.length() > 0 }
                .maxByOrNull { it.lastModified() }
                ?.readLines()?.lastOrNull { it.isNotBlank() }
        }.getOrNull()
            ?.substringAfter("apply-update: ")?.take(300) ?: "no reason given"

    /** result.json as the helper wrote it, or null if there is none or it can't be read. */
    fun readResult(file: File): ApplyResult? = runCatching {
        if (!file.isFile || file.length() > 64 * 1024) return null
        val json = JSONObject(file.readText())
        ApplyResult(json.getBoolean("ok"), json.optString("version"), json.optString("error").ifBlank { null })
    }.getOrNull()
}
