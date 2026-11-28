package com.exo.musicplayer.desktop.update

import com.exo.musicplayer.desktop.system.Platform
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * apply-update.ps1 for real, with Windows PowerShell, against a fake install
 * in a temp folder with a `.cmd` launcher. Windows only: the repository's
 * Windows CI runs it. (The UAC prompt itself can't be driven by a test; the
 * elevated part can, run straight from its encoded command as this user.)
 */
class UpdateHelperPs1Test {
    private lateinit var dir: File
    private lateinit var log: File
    private lateinit var install: File
    private lateinit var next: File
    private lateinit var updates: File
    private lateinit var staging: File
    private lateinit var plan: UpdatePlan
    private lateinit var before: Map<String, String>
    private val result get() = File(updates, "result.json")

    @BeforeTest
    fun setUp() {
        assumeTrue(Platform.isWindows)
        dir = UpdateTestKit.tempDir("ps1").canonicalFile
        log = File(dir, "launch.log")
        install = UpdateTestKit.windowsImage(File(dir, "install"), "1.0.0", log)
        next = UpdateTestKit.windowsImage(File(dir, "next"), "1.1.0", log, extraFolder = true)
        updates = File(dir, "updates")
        staging = File(updates, "1.1.0")
        val layout = InstallLayout(install, File(install, "Lucense.cmd"), windows = true, version = "1.0.0")
        plan = runBlocking { UpdatePlanner.plan(UpdateTestKit.manifestOf(next, "1.1.0", platform = "windows-x64"), layout) }
        plan.toFetch.forEach { File(next, it.path).copyTo(File(staging, "files/${it.path}")) }
        before = UpdateTestKit.snapshot(install)
    }

    @AfterTest
    fun tearDown() {
        if (::dir.isInitialized) UpdateTestKit.cleanUp(dir)
    }

    private fun request() = HelperRequest(
        pid = UpdateTestKit.deadPid(),
        root = install,
        staging = staging,
        version = "1.1.0",
        plan = UpdateApplier.writePlan(plan, staging),
        result = result,
        launcher = "Lucense.cmd",
        windows = true,
        elevation = Elevation.None,
        script = UpdateApplier.writeScript(staging)
    )

    private fun run(command: List<String>): Int {
        val process = UpdateApplier.start(command, staging)
        if (!process.waitFor(90, TimeUnit.SECONDS)) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            fail("the helper didn't finish in 90 s")
        }
        return process.exitValue()
    }

    private fun launches(): List<String> = runBlocking {
        runCatching { UpdateTestKit.until(20_000) { log.isFile && log.readText().isNotBlank() } }
        if (log.isFile) log.readLines().map { it.trim() } else emptyList()
    }

    private fun helperLog() = staging.listFiles().orEmpty().filter { it.name.startsWith("apply") && it.name.endsWith(".log") }.joinToString("\n") { it.readText() }

    @Test
    fun `the new files are put in place, old jars removed, and the new version started`() {
        assertEquals(0, run(UpdateApplier.command(request())), helperLog())
        assertEquals(UpdateTestKit.snapshot(next), UpdateTestKit.snapshot(install), helperLog())
        assertEquals(ApplyResult(true, "1.1.0", null), UpdateApplier.readResult(result), helperLog())
        assertEquals(listOf("started 1.1.0"), launches())
    }

    @Test
    fun `a staged file damaged after it was checked is caught, and everything is put back`() {
        val staged = File(staging, "files/app/shared-1_1_0.jar")
        staged.writeBytes(staged.readBytes().also { it[100] = (it[100] + 1).toByte() })
        assertEquals(0, run(UpdateApplier.command(request())))
        assertEquals(before, UpdateTestKit.snapshot(install), helperLog())
        val outcome = assertNotNull(UpdateApplier.readResult(result))
        assertFalse(outcome.ok)
        assertTrue(outcome.error!!.contains("shared-1_1_0.jar"), outcome.error)
        assertEquals(listOf("started 1.0.0"), launches())
        assertFalse(File(install, "app/resources/new-folder").exists())
    }

    @Test
    fun `a file held open by another program fails the swap half-way, and it is rolled back`() {
        // Java opens files without delete sharing, so nothing can be renamed over this one.
        RandomAccessFile(File(install, "runtime/lib/server/classes.jsa"), "r").use {
            assertEquals(0, run(UpdateApplier.command(request())))
        }
        assertEquals(before, UpdateTestKit.snapshot(install), helperLog())
        assertFalse(UpdateApplier.readResult(result)!!.ok)
        assertEquals(listOf("started 1.0.0"), launches())
        assertTrue(install.walkTopDown().none { it.name.endsWith(".lucense-new") })
    }

    @Test
    fun `wrong arguments are refused without touching anything`() {
        val args = UpdateApplier.command(request()).toMutableList()
        // powershell.exe and its options, -File, the script, then MODE PID ROOT ...
        val mode = args.indexOf("run")
        args[mode + 2] = "relative\\root"
        assertEquals(2, run(args))
        assertEquals(before, UpdateTestKit.snapshot(install))
        assertNull(UpdateApplier.readResult(result))
        assertFalse(log.exists())
    }

    @Test
    fun `the elevated part runs from its encoded command and writes only into the install`() {
        val req = request().copy(elevation = Elevation.RunAs)
        val private = File(install, ".lucense-update")
        val encoded = UpdateApplier.elevatedEncoded(req)
        val command = listOf("powershell.exe") + UpdateApplier.POWERSHELL_OPTIONS + listOf("-EncodedCommand", encoded)
        assertTrue(command.joinToString(" ").length < UpdateApplier.MAX_WINDOWS_COMMAND_LINE)
        File(staging, UpdateApplier.PS1).delete()
        assertEquals(0, run(command), private.listFiles().orEmpty().joinToString("\n") { it.name })
        val installed = UpdateTestKit.snapshot(install).filterKeys { !it.startsWith(".lucense-update/") }
        assertEquals(UpdateTestKit.snapshot(next), installed, File(private, "apply-swap.log").takeIf { it.isFile }?.readText())
        assertEquals("ready ${req.token}", File(private, "apply.state").readText().trim())
        assertEquals(ApplyResult(true, "1.1.0", null), UpdateApplier.readResult(File(private, "result.json")))
        assertFalse(File(private, "backup").exists(), "the backup inside the install goes once the update is in")
        // Nothing was written to the user's folder, and the app is left to the elevation step to start.
        assertFalse(result.exists())
        assertFalse(File(staging, UpdateApplier.STATE).exists())
        assertFalse(File(staging, "backup").exists())
        assertFalse(File(staging, UpdateApplier.PS1).exists())
        assertEquals(emptyList(), launches())
    }
}
