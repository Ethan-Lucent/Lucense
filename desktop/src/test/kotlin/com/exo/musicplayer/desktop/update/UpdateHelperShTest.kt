package com.exo.musicplayer.desktop.update

import com.exo.musicplayer.desktop.system.Platform
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Runs apply-update.sh for real with /bin/sh against a fake install in a temp
 * folder: the swap, the verification, and every way it can go wrong, each
 * ending in either all-new or all-old files. Every process is bounded.
 */
class UpdateHelperShTest {
    private lateinit var dir: File
    private lateinit var log: File
    private lateinit var install: File
    private lateinit var next: File
    private lateinit var updates: File
    private lateinit var staging: File
    private lateinit var plan: UpdatePlan
    private lateinit var before: Map<String, String>
    private val result get() = File(updates, "result.json")
    private val sleepers = mutableListOf<Process>()

    @BeforeTest
    fun setUp() {
        assumeTrue(Platform.isLinux && Platform.onPath("sha256sum") != null)
        dir = UpdateTestKit.tempDir("helper")
        log = File(dir, "launch.log")
        install = UpdateTestKit.linuxImage(File(dir, "install"), "1.0.0", log)
        next = UpdateTestKit.linuxImage(File(dir, "next"), "1.1.0", log, extraFolder = true)
        updates = File(dir, "updates")
        staging = File(updates, "1.1.0")
        val layout = InstallLayout(install, File(install, "bin/Lucense"), windows = false, version = "1.0.0")
        plan = runBlocking { UpdatePlanner.plan(UpdateTestKit.manifestOf(next, "1.1.0"), layout) }
        // Staged as the stager leaves things: each file under files/<path>.
        plan.toFetch.forEach { File(next, it.path).copyTo(File(staging, "files/${it.path}")) }
        before = UpdateTestKit.snapshot(install)
    }

    @AfterTest
    fun tearDown() {
        sleepers.forEach { it.destroyForcibly() }
        if (::dir.isInitialized) UpdateTestKit.cleanUp(dir)
    }

    private fun request(pid: Long = UpdateTestKit.deadPid(), elevation: Elevation = Elevation.None) = HelperRequest(
        pid = pid,
        root = install,
        staging = staging,
        version = "1.1.0",
        plan = UpdateApplier.writePlan(plan, staging),
        result = result,
        launcher = "bin/Lucense",
        windows = false,
        elevation = elevation,
        script = UpdateApplier.writeScript(staging, windows = false)
    )

    private fun run(command: List<String>, env: Map<String, String> = emptyMap()): Int {
        val process = UpdateApplier.start(command, staging, env)
        if (!process.waitFor(90, TimeUnit.SECONDS)) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            fail("the helper didn't finish in 90 s:\n" + File(staging, UpdateApplier.LOG).readText())
        }
        return process.exitValue()
    }

    /** The launcher's log once it has a line (the helper starts it in the background). */
    private fun launches(): List<String> = runBlocking {
        runCatching { UpdateTestKit.until(15_000) { log.isFile && log.readText().isNotBlank() } }
        if (log.isFile) log.readLines() else emptyList()
    }

    private fun helperLog() = File(staging, UpdateApplier.LOG).takeIf { it.isFile }?.readText().orEmpty()

    private fun assertRolledBack(why: String) {
        assertEquals(before, UpdateTestKit.snapshot(install), "the install is exactly as before\n${helperLog()}")
        val outcome = assertNotNull(UpdateApplier.readResult(result))
        assertFalse(outcome.ok)
        assertEquals("1.1.0", outcome.version)
        assertTrue(outcome.error!!.contains(why), "error: ${outcome.error}")
        assertEquals(listOf("started 1.0.0 "), launches(), "the old version is started again")
        assertFalse(File(install, "lib/app/resources/new-folder").exists(), "a folder the update made is gone again")
        assertTrue(install.walkTopDown().none { it.name.endsWith(".lucense-new") })
    }

    @Test
    fun `the new files are put in place, old jars removed, exec bits set, and the new version started`() {
        assertEquals(0, run(UpdateApplier.command(request())), helperLog())
        assertEquals(UpdateTestKit.snapshot(next), UpdateTestKit.snapshot(install), helperLog())
        assertTrue(File(install, "lib/app/resources/new-folder/extra.dat").isFile, "made the folder that was missing")
        assertTrue(File(install, "bin/Lucense").canExecute())
        assertTrue(File(install, "lib/runtime/bin/java").canExecute())
        assertFalse(File(install, "lib/app/desktop-1_0_0.jar").exists())
        assertEquals(ApplyResult(true, "1.1.0", null), UpdateApplier.readResult(result))
        assertEquals(listOf("started 1.1.0 "), launches())
        assertEquals("ready", File(staging, UpdateApplier.STATE).readText().trim())
        // Everything replaced or removed has its copy in the backup.
        assertEquals(before["lib/app/desktop-1_0_0.jar"], UpdatePlanner.sha256(File(staging, "backup/lib/app/desktop-1_0_0.jar")))
        assertEquals(before["lib/app/Lucense.cfg"], UpdatePlanner.sha256(File(staging, "backup/lib/app/Lucense.cfg")))
    }

    @Test
    fun `a staged file damaged after it was checked fails the final check, and everything is put back`() {
        val staged = File(staging, "files/lib/app/shared-1_1_0.jar")
        staged.writeBytes(staged.readBytes().also { it[100] = (it[100] + 1).toByte() })
        assertEquals(0, run(UpdateApplier.command(request())))
        // The stale jars had already been removed when the check failed: they are back too.
        assertTrue(File(install, "lib/app/desktop-1_0_0.jar").isFile)
        assertRolledBack("shared-1_1_0.jar doesn't match")
    }

    @Test
    fun `a copy failing half-way is rolled back`() {
        assumeTrue(System.getProperty("user.name") != "root")
        // The last file of the plan goes into a folder that can't be written.
        val server = File(install, "lib/runtime/lib/server")
        server.setWritable(false, false)
        try {
            assertEquals(0, run(UpdateApplier.command(request())))
        } finally {
            server.setWritable(true, true)
        }
        assertRolledBack("classes.jsa")
    }

    @Test
    fun `an app that doesn't close in time changes nothing`() {
        val sleeper = ProcessBuilder("sleep", "60").redirectInput(ProcessBuilder.Redirect.from(File("/dev/null"))).start()
        sleepers += sleeper
        assertEquals(0, run(UpdateApplier.command(request(pid = sleeper.pid())), mapOf("LUCENSE_UPDATE_WAIT_SECONDS" to "2")))
        assertEquals(before, UpdateTestKit.snapshot(install))
        val outcome = assertNotNull(UpdateApplier.readResult(result))
        assertFalse(outcome.ok)
        assertTrue(outcome.error!!.contains("didn't close"))
        assertEquals(listOf("started 1.0.0 "), launches())
    }

    @Test
    fun `an unsafe plan is refused before anything changes`() {
        val req = request()
        req.plan.writeText("../outside\t${"a".repeat(64)}\t0\n")
        assertEquals(0, run(UpdateApplier.command(req)))
        assertEquals(before, UpdateTestKit.snapshot(install))
        assertTrue(UpdateApplier.readResult(result)!!.error!!.contains("unsafe"))
        assertFalse(File(dir, "outside").exists())
        // A stale entry that isn't a jar is refused the same way.
        result.delete()
        log.delete()
        req.plan.writeText("stale\tlib/app/Lucense.cfg\n")
        run(UpdateApplier.command(req))
        assertEquals(before, UpdateTestKit.snapshot(install))
        assertTrue(UpdateApplier.readResult(result)!!.error!!.contains("isn't an old jar"))
    }

    @Test
    fun `wrong arguments are refused without touching anything`() {
        val req = request()
        val args = UpdateApplier.command(req).toMutableList()
        // /bin/sh, the script, then MODE PID ROOT ...
        args[4] = "relative/root"
        assertEquals(2, run(args))
        args[4] = install.absolutePath
        args[2] = "explode"
        assertEquals(2, run(args))
        args[2] = "run"
        args[3] = "12; rm -rf /"
        assertEquals(2, run(args))
        assertEquals(before, UpdateTestKit.snapshot(install))
        assertNull(UpdateApplier.readResult(result))
        assertFalse(log.exists(), "nothing was started")
    }

    /** A stand-in for pkexec that records what it was asked to run and runs it as this same user. */
    private fun fakePkexec(body: String): File =
        UpdateTestKit.write(File(dir, "bin/pkexec"), "#!/bin/sh\nprintf '%s\\n' \"\$@\" > '${File(dir, "pkexec-argv").absolutePath}'\n$body\n".toByteArray(), exec = true)

    private fun id(flag: String): Long {
        val p = ProcessBuilder("id", flag).redirectInput(ProcessBuilder.Redirect.from(File("/dev/null"))).start()
        p.waitFor(30, TimeUnit.SECONDS)
        return p.inputStream.bufferedReader().readText().trim().toLong()
    }

    @Test
    fun `through pkexec the swap runs with the command line it should, and Lucense starts as the user`() {
        val pkexec = fakePkexec("exec \"\$@\"")
        val req = request(elevation = Elevation.Pkexec(pkexec))
        assertEquals(0, run(UpdateApplier.command(req)), helperLog())
        val expected = UpdateApplier.pkexecCommand(req, id("-u"), id("-g")).drop(1)
        assertEquals(expected, File(dir, "pkexec-argv").readLines())
        assertEquals(UpdateTestKit.snapshot(next), UpdateTestKit.snapshot(install), helperLog())
        assertEquals(ApplyResult(true, "1.1.0", null), UpdateApplier.readResult(result))
        assertEquals(listOf("started 1.1.0 "), launches())
    }

    @Test
    fun `a dismissed password prompt keeps the app running and changes nothing`(): Unit = runBlocking {
        val req = request(elevation = Elevation.Pkexec(fakePkexec("exit 126")))
        val process = UpdateApplier.start(UpdateApplier.command(req), staging)
        assertEquals(HelperStart.Cancelled, UpdateApplier.awaitStart(process, staging, 60_000))
        assertTrue(process.waitFor(60, TimeUnit.SECONDS))
        assertEquals(before, UpdateTestKit.snapshot(install))
        assertFalse(result.exists())
        assertFalse(log.exists(), "nothing was started")

        val refused = request(elevation = Elevation.Pkexec(fakePkexec("exit 127")))
        val again = UpdateApplier.start(UpdateApplier.command(refused), staging)
        val failed = assertIs<HelperStart.Failed>(UpdateApplier.awaitStart(again, staging, 60_000))
        assertTrue(failed.message.contains("administrator rights"))
        assertTrue(again.waitFor(60, TimeUnit.SECONDS))
        assertEquals(before, UpdateTestKit.snapshot(install))
    }
}
