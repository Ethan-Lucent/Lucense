package com.exo.musicplayer.desktop.update

import com.exo.musicplayer.desktop.system.Platform
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The helper's inputs and command lines; the helper itself runs in [UpdateHelperShTest]. */
class UpdateApplierTest {
    private val dir = UpdateTestKit.tempDir("applier")

    @AfterTest
    fun tearDown() = UpdateTestKit.cleanUp(dir)

    private val sha = "0123456789abcdef".repeat(4)

    private fun plan(vararg paths: String, stale: List<String> = emptyList()): UpdatePlan {
        val files = paths.map { ManifestFile(it, 10, sha, it.startsWith("bin/")) }
        return UpdatePlan(UpdateManifest("1.58.0", "linux-x64", "z.zip", "Lucense/", files), files, stale, 0)
    }

    private fun request(elevation: Elevation, windows: Boolean = false, root: String = "/opt/lucense") = HelperRequest(
        pid = 4242,
        root = File(root),
        staging = File("/home/u/.local/share/Lucense/update/1.58.0"),
        version = "1.58.0",
        plan = File("/home/u/.local/share/Lucense/update/1.58.0/apply-plan.tsv"),
        result = File("/home/u/.local/share/Lucense/update/result.json"),
        launcher = if (windows) "Lucense.exe" else "bin/Lucense",
        windows = windows,
        elevation = elevation,
        script = if (windows) File("C:\\Users\\u\\AppData\\Roaming\\Lucense\\update\\1.58.0\\apply-update.ps1") else null,
        token = "0123456789abcdef0123456789abcdef"
    )

    @Test
    fun `the plan is one line per file and per old jar`() {
        val text = UpdateApplier.planText(plan("bin/Lucense", "lib/app/desktop-ab.jar", stale = listOf("lib/app/desktop-aa.jar")))
        assertEquals(
            "bin/Lucense\t$sha\t1\nlib/app/desktop-ab.jar\t$sha\t0\nstale\tlib/app/desktop-aa.jar\n",
            text
        )
        // A name that could add a line of its own, or a stale entry that isn't a jar, never gets written.
        assertFailsWith<IllegalArgumentException> { UpdateApplier.planText(plan("a\nstale\tb.jar")) }
        assertFailsWith<IllegalArgumentException> { UpdateApplier.planText(plan(stale = listOf("lib/app/notes.txt"))) }
        assertFailsWith<IllegalArgumentException> { UpdateApplier.planText(plan(stale = listOf("../x.jar"))) }
    }

    @Test
    fun `helper arguments are checked, not cleaned up`() {
        assertEquals("/opt/lucense", UpdateApplier.checkArgument("/opt/lucense"))
        assertEquals("C:\\Program Files\\Lucense", UpdateApplier.checkArgument("C:\\Program Files\\Lucense"))
        for (bad in listOf("", "a\nb", "a\u0000b", "say \"hi\"", "C:\\Lucense\\")) {
            assertFailsWith<IllegalArgumentException>(bad) { UpdateApplier.checkArgument(bad) }
        }
        assertFailsWith<IllegalArgumentException> { UpdateApplier.arguments(request(Elevation.None, root = "/opt/luc\"ense")) }
        assertFailsWith<IllegalArgumentException> { UpdateApplier.arguments(request(Elevation.Impossible("no"))) }
        assertFailsWith<IllegalArgumentException> { UpdateApplier.arguments(request(Elevation.None).copy(version = "1.0; rm -rf /")) }
    }

    @Test
    fun `the command lines for each way of running`() {
        val sh = UpdateApplier.scriptText(UpdateApplier.SH)
        val args = listOf(
            "4242", "/opt/lucense", "/home/u/.local/share/Lucense/update/1.58.0", "1.58.0",
            "/home/u/.local/share/Lucense/update/1.58.0/apply-plan.tsv", "/home/u/.local/share/Lucense/update/result.json", "bin/Lucense"
        )
        // The helper's text itself is the argument of sh -c: no script file is run at all.
        val plain = UpdateApplier.command(request(Elevation.None), setsid = File("/usr/bin/setsid"))
        assertEquals(listOf("/usr/bin/setsid", "/bin/sh", "-c", sh, "apply-update", "run") + args, plain)

        val elevated = request(Elevation.Pkexec(File("/usr/bin/pkexec")))
        val viaPkexec = UpdateApplier.command(elevated, ids = { 1000L to 1001L })
        // What runs as root: the swap only, its code in the command itself, with the user's ids to act for.
        val asRoot = listOf("/usr/bin/pkexec", "/bin/sh", "-c", sh, "apply-update", "swap") + args + listOf("-", "1000", "1001")
        assertEquals(asRoot, UpdateApplier.pkexecCommand(elevated, 1000, 1001))
        // The user's part is given that command word for word after its own arguments.
        assertEquals(listOf("/bin/sh", "-c", sh, "apply-update", "run-elevated") + args + asRoot, viaPkexec)
        // Nothing in the user's folder is among what either part runs: the only paths there are data.
        assertTrue((viaPkexec + asRoot).none { it.endsWith(".sh") || it.startsWith("/home/u/") && it !in args })

        val windowsPlain = UpdateApplier.command(request(Elevation.None, windows = true, root = "C:\\Program Files\\Lucense"))
        assertEquals(listOf("powershell.exe") + UpdateApplier.POWERSHELL_OPTIONS + "-File", windowsPlain.take(8))
        assertEquals("run", windowsPlain[9])
        assertEquals("Lucense.exe", windowsPlain.last())
        assertEquals(emptyMap(), UpdateApplier.environment(request(Elevation.None, windows = true, root = "C:\\Program Files\\Lucense")))
    }

    @Test
    fun `on Windows the elevated code is an encoded command made from the app's own scripts, and fits a command line`() {
        val req = request(Elevation.RunAs, windows = true, root = "C:\\Program Files\\Lucense").copy(
            staging = File("C:\\Users\\O'Brien\\AppData\\Roaming\\Lucense\\update\\1.58.0"),
            plan = File("C:\\Users\\O'Brien\\AppData\\Roaming\\Lucense\\update\\1.58.0\\apply-plan.tsv"),
            result = File("C:\\Users\\O'Brien\\AppData\\Roaming\\Lucense\\update\\result.json")
        )
        val command = UpdateApplier.command(req, powershell = "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe")
        assertEquals(UpdateApplier.POWERSHELL_OPTIONS + "-EncodedCommand", command.subList(1, 8))
        assertEquals(8, command.size - 1, "nothing after the encoded command")
        // (On Linux these paths aren't absolute, so they are taken as the request has them.)
        val a = UpdateApplier.arguments(req)
        fun literals(vararg values: String) = values.joinToString(" ") { UpdateApplier.powershellLiteral(it) }
        val token = "0123456789abcdef0123456789abcdef"
        // The user-mode step: apply-update-elevate.ps1's own text, then its arguments as literals.
        val step = UpdateApplier.decodeCommand(command.last())
        assertEquals(
            "& {\n" + UpdateApplier.compact(UpdateApplier.scriptText(UpdateApplier.ELEVATE_PS1)) + "\n} " + literals(a[2], a[3], a[4], a[6], a[7], token),
            step
        )
        assertTrue(step.contains("O''Brien") && !step.contains("O'Brien"), "a single quote is doubled")
        // The administrator's part: apply-update.ps1 in swap mode, handed over in the environment.
        val encoded = UpdateApplier.environment(req).getValue(UpdateApplier.ELEVATED_ENV)
        assertTrue(Regex("[A-Za-z0-9+/]+={0,2}").matches(encoded))
        val elevatedCode = UpdateApplier.decodeCommand(encoded)
        // Packed, it unpacks to the app's own script, and the arguments follow as literals.
        assertEquals(UpdateApplier.compact(UpdateApplier.scriptText(UpdateApplier.PS1)), UpdateApplier.unpack(elevatedCode))
        assertEquals(3, elevatedCode.lines().size)
        assertTrue(
            elevatedCode.endsWith("\n& ([scriptblock]::Create(\$t)) " + literals("swap", "4242", a[2], a[3], "1.58.0", a[5], a[6], "Lucense.exe", token)),
            elevatedCode.lines().last()
        )
        // The elevation step builds the same line the app describes.
        val elevatedLine = UpdateApplier.elevatedArgumentLine(req)
        assertTrue(UpdateApplier.scriptText(UpdateApplier.ELEVATE_PS1).contains("'" + elevatedLine.removeSuffix(encoded) + "' + \$encoded"))
        // Both command lines fit Windows' limit, with the longest program path.
        assertTrue(command.joinToString(" ").length < UpdateApplier.MAX_WINDOWS_COMMAND_LINE, "user step: ${command.joinToString(" ").length}")
        val elevated = "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe $elevatedLine"
        assertTrue(elevated.length < UpdateApplier.MAX_WINDOWS_COMMAND_LINE, "elevated: ${elevated.length}")
        // A quote PowerShell would also take as one is refused, not guessed at.
        assertEquals("'O''Brien'", UpdateApplier.powershellLiteral("O'Brien"))
        assertFailsWith<IllegalArgumentException> { UpdateApplier.powershellLiteral("O\u2019Brien") }
        assertFailsWith<IllegalArgumentException> { UpdateApplier.environment(req.copy(token = "not a token")) }
    }

    @Test
    fun `the Linux helper fits in one argument`() {
        val size = UpdateApplier.scriptText(UpdateApplier.SH).toByteArray().size
        assertTrue(size < UpdateApplier.MAX_SH_BYTES, "apply-update.sh is $size bytes")
        assertTrue(UpdateApplier.MAX_SH_BYTES < 128 * 1024)
    }

    @Test
    fun `this process's ids are read the way id reports them`() {
        assumeTrue(Platform.isLinux)
        fun id(flag: String): Long {
            val p = ProcessBuilder("id", flag).redirectInput(ProcessBuilder.Redirect.from(File("/dev/null"))).start()
            assertTrue(p.waitFor(30, TimeUnit.SECONDS))
            return p.inputStream.bufferedReader().readText().trim().toLong()
        }
        assertEquals(id("-u") to id("-g"), UpdateApplier.userIds())
    }

    @Test
    fun `whether to ask for administrator rights, and how`() {
        assumeTrue(Platform.isLinux && System.getProperty("user.name") != "root")
        val root = UpdateTestKit.linuxImage(File(dir, "install"), "1.0.0", File(dir, "log"))
        val layout = InstallLayout(root, File(root, "bin/Lucense"), windows = false, version = "1.0.0")
        val work = plan("lib/app/Lucense.cfg", stale = listOf("lib/app/desktop-1_0_0.jar"))
        assertEquals(Elevation.None, UpdateApplier.elevation(layout, work) { null })
        File(root, "lib/app").setWritable(false, false)
        try {
            val pkexec = File("/usr/bin/pkexec")
            assertEquals(Elevation.Pkexec(pkexec), UpdateApplier.elevation(layout, work) { if (it == "pkexec") pkexec else null })
            val none = assertIs<Elevation.Impossible>(UpdateApplier.elevation(layout, work) { null })
            assertTrue(none.reason.contains("administrator rights"))
            assertTrue(none.reason.contains("installer"))
            val windowsLayout = InstallLayout(root, File(root, "bin/Lucense"), windows = true, version = "1.0.0")
            assertEquals(Elevation.RunAs, UpdateApplier.elevation(windowsLayout, work) { null })
        } finally {
            File(root, "lib/app").setWritable(true, true)
        }
    }

    @Test
    fun `result json is read, and anything else is ignored`() {
        val file = File(dir, "result.json")
        file.writeText("""{"ok":false,"version":"1.58.0","error":"bin/Lucense doesn't match"}""")
        assertEquals(ApplyResult(false, "1.58.0", "bin/Lucense doesn't match"), UpdateApplier.readResult(file))
        file.writeText("""{"ok":true,"version":"1.58.0"}""")
        assertEquals(ApplyResult(true, "1.58.0", null), UpdateApplier.readResult(file))
        file.writeText("garbage")
        assertNull(UpdateApplier.readResult(file))
        assertNull(UpdateApplier.readResult(File(dir, "missing.json")))
    }

    @Test
    fun `the helper scripts ship with the app and keep to their rules`() {
        val sh = UpdateApplier.scriptText(UpdateApplier.SH)
        val ps1 = UpdateApplier.scriptText(UpdateApplier.PS1)
        val elevate = UpdateApplier.scriptText(UpdateApplier.ELEVATE_PS1)
        assertTrue(sh.startsWith("#!/bin/sh"))
        assertTrue(Regex("(?m)^set -eu$").containsMatchIn(sh))
        // The user's part runs the app's elevated command as given, and never re-runs a file of its own.
        assertTrue(sh.contains("shift 8\n        set +e\n        \"\$@\" </dev/null"))
        val code = sh.lines().filterNot { it.trimStart().startsWith("#") }
        assertTrue(code.none { it.contains("\$0") }, "no \$0 outside comments")
        assertTrue(code.none { it.contains("ELEVATOR\" /bin/sh") })
        assertTrue(sh.contains("setsid \"\$ROOT/\$LAUNCHER\""))
        assertTrue(!sh.contains("eval"), "no eval in the helper")
        assertTrue(ps1.contains("\$ErrorActionPreference = 'Stop'"))
        // Only the elevation step asks for rights, and only with the encoded command from the app.
        assertTrue(!ps1.contains("-Verb RunAs") && !ps1.contains("MyInvocation"))
        assertTrue(elevate.contains("-Verb RunAs"))
        assertTrue(!elevate.contains("-File "))
        for (script in listOf(ps1, elevate)) {
            // UpdateApplier.compact relies on this: no line starting with # is inside a string.
            for (multiLine in listOf("@'", "@\"", "<#")) assertTrue(!script.contains(multiLine), "multi-line: $multiLine")
            assertTrue(script.all { it.code < 128 }, "ASCII only: Windows PowerShell reads a file without a BOM as ANSI")
            // Windows PowerShell 5.1: none of PowerShell 7's operators, and no aliases.
            for (pwsh7 in listOf("??", "?.", " && ", " || ", "-Parallel", "ForEach-Object -Parallel")) {
                assertTrue(!script.contains(pwsh7), "PowerShell 7 only: $pwsh7")
            }
            for (alias in listOf(" % {", " ? {", "gci ", " ls ", " cp ", " mv ", " rm ", " del ", " echo ", " cat ")) {
                assertTrue(!script.contains(alias), "alias: $alias")
            }
            // The automatic $PID would shadow the app's.
            assertTrue(!Regex("\\\$Pid\\b", RegexOption.IGNORE_CASE).containsMatchIn(script.replace("\$AppPid", "")))
        }
        // Copied for Windows without elevation, byte for byte.
        val staging = File(dir, "staging").apply { mkdirs() }
        assertEquals(ps1, UpdateApplier.writeScript(staging).readText())
    }
}
