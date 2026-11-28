package com.exo.musicplayer.desktop.update

import com.exo.musicplayer.desktop.system.Platform
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
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
        script = File("/home/u/.local/share/Lucense/update/1.58.0/apply-update.sh")
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
        val plain = UpdateApplier.command(request(Elevation.None), setsid = File("/usr/bin/setsid"))
        assertEquals(
            listOf(
                "/usr/bin/setsid", "/bin/sh", "/home/u/.local/share/Lucense/update/1.58.0/apply-update.sh",
                "run", "4242", "/opt/lucense", "/home/u/.local/share/Lucense/update/1.58.0", "1.58.0",
                "/home/u/.local/share/Lucense/update/1.58.0/apply-plan.tsv", "/home/u/.local/share/Lucense/update/result.json", "bin/Lucense"
            ),
            plain
        )
        val elevated = request(Elevation.Pkexec(File("/usr/bin/pkexec")))
        val viaPkexec = UpdateApplier.command(elevated)
        assertEquals(listOf("/bin/sh", "/home/u/.local/share/Lucense/update/1.58.0/apply-update.sh", "run-elevated"), viaPkexec.take(3))
        assertEquals("/usr/bin/pkexec", viaPkexec.last())
        // What the script itself then hands to pkexec: the swap only, as root, with the user's ids to give files back to.
        assertEquals(
            listOf(
                "/usr/bin/pkexec", "/bin/sh", "/home/u/.local/share/Lucense/update/1.58.0/apply-update.sh", "swap",
                "4242", "/opt/lucense", "/home/u/.local/share/Lucense/update/1.58.0", "1.58.0",
                "/home/u/.local/share/Lucense/update/1.58.0/apply-plan.tsv", "/home/u/.local/share/Lucense/update/result.json",
                "bin/Lucense", "-", "1000", "1000"
            ),
            UpdateApplier.pkexecCommand(elevated, 1000, 1000)
        )
        val windows = UpdateApplier.command(request(Elevation.RunAs, windows = true, root = "C:\\Program Files\\Lucense"))
        assertEquals(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File"), windows.take(8))
        assertEquals("run-elevated", windows[9])
        assertEquals("Lucense.exe", windows.last())
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
    fun `both helper scripts ship with the app and keep to their rules`() {
        val staging = File(dir, "staging").apply { mkdirs() }
        val sh = UpdateApplier.writeScript(staging, windows = false).readText()
        val ps1 = UpdateApplier.writeScript(staging, windows = true).readText()
        assertTrue(sh.startsWith("#!/bin/sh"))
        assertTrue(Regex("(?m)^set -eu$").containsMatchIn(sh))
        // The pkexec call in the script is the one pkexecCommand describes.
        assertTrue(sh.contains("\"\$ELEVATOR\" /bin/sh \"\$0\" swap \"\$APP_PID\" \"\$ROOT\" \"\$STAGING\" \"\$VERSION\" \"\$PLAN\" \"\$RESULT\" \"\$LAUNCHER\" - \"\$(id -u)\" \"\$(id -g)\""))
        assertTrue(sh.contains("setsid \"\$ROOT/\$LAUNCHER\""))
        assertTrue(!sh.contains("eval"), "no eval in the helper")
        assertTrue(ps1.contains("\$ErrorActionPreference = 'Stop'"))
        assertTrue(ps1.contains("-Verb RunAs"))
        // Windows PowerShell 5.1: none of PowerShell 7's operators, and no aliases.
        for (pwsh7 in listOf("??", "?.", " && ", " || ", "-Parallel", "ForEach-Object -Parallel")) {
            assertTrue(!ps1.contains(pwsh7), "PowerShell 7 only: $pwsh7")
        }
        for (alias in listOf(" % {", " ? {", "gci ", " ls ", " cp ", " mv ", " rm ", " del ", " echo ", " cat ")) {
            assertTrue(!ps1.contains(alias), "alias: $alias")
        }
        // The automatic $PID would shadow the app's.
        assertTrue(!Regex("\\\$Pid\\b", RegexOption.IGNORE_CASE).containsMatchIn(ps1.replace("\$AppPid", "")))
    }
}
