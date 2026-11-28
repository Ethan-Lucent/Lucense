package com.exo.musicplayer.desktop.update

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdatePlannerTest {
    private val dir = UpdateTestKit.tempDir("planner")
    private val log = File(dir, "launch.log")
    private val installed = UpdateTestKit.linuxImage(File(dir, "install"), "1.0.0", log)

    @AfterTest
    fun tearDown() = UpdateTestKit.cleanUp(dir)

    private fun layout(root: File = installed) = InstallLayout(root, File(root, "bin/Lucense"), windows = false, version = "1.0.0")

    @Test
    fun `the same files need nothing`(): Unit = runBlocking {
        val plan = UpdatePlanner.plan(UpdateTestKit.manifestOf(installed, "1.0.0"), layout())
        assertTrue(plan.isEmpty)
        assertEquals(0, plan.bytes)
        assertEquals(installed.walkTopDown().count { it.isFile }, plan.unchanged)
    }

    @Test
    fun `changed, missing and stale files are found, and nothing else is removed`(): Unit = runBlocking {
        val next = UpdateTestKit.linuxImage(File(dir, "next"), "1.1.0", log, extraFolder = true)
        // Something the release doesn't list, outside the jar folder and not a jar: left alone.
        UpdateTestKit.write(File(installed, "share/doc/README"), "mine".toByteArray())
        UpdateTestKit.write(File(installed, "lib/app/notes.txt"), "mine".toByteArray())
        UpdateTestKit.write(File(installed, "lib/runtime/lib/old.jar"), "a jar elsewhere".toByteArray())
        var progressCalls = 0
        val plan = UpdatePlanner.plan(UpdateTestKit.manifestOf(next, "1.1.0"), layout()) { _, _ -> progressCalls++ }
        assertEquals(
            listOf(
                "lib/app/Lucense.cfg",
                "lib/app/desktop-1_1_0.jar",
                "lib/app/resources/new-folder/extra.dat",
                "lib/app/shared-1_1_0.jar",
                "lib/runtime/lib/server/classes.jsa"
            ),
            plan.toFetch.map { it.path }
        )
        assertEquals(listOf("lib/app/desktop-1_0_0.jar", "lib/app/shared-1_0_0.jar"), plan.stale)
        assertEquals(plan.toFetch.sumOf { it.size }, plan.bytes)
        assertTrue(progressCalls > 0)
    }

    @Test
    fun `a file of the same size but other bytes is fetched`(): Unit = runBlocking {
        val manifest = UpdateTestKit.manifestOf(installed, "1.0.0")
        val modules = File(installed, "lib/runtime/lib/modules")
        val bytes = modules.readBytes().also { it[12345] = (it[12345] + 1).toByte() }
        modules.writeBytes(bytes)
        assertEquals(listOf("lib/runtime/lib/modules"), UpdatePlanner.plan(manifest, layout()).toFetch.map { it.path })
    }

    @Test
    fun `a program that lost its exec bit is put right`(): Unit = runBlocking {
        assumeTrue(System.getProperty("os.name").startsWith("Linux"))
        val manifest = UpdateTestKit.manifestOf(installed, "1.0.0")
        File(installed, "lib/runtime/bin/java").setExecutable(false, false)
        assertEquals(listOf("lib/runtime/bin/java"), UpdatePlanner.plan(manifest, layout()).toFetch.map { it.path })
    }

    @Test
    fun `a release that would leave the launcher unrunnable, or is for another system, is refused`(): Unit = runBlocking {
        val manifest = UpdateTestKit.manifestOf(installed, "1.1.0")
        val broken = manifest.copy(files = manifest.files.map { if (it.path == "bin/Lucense") it.copy(exec = false) else it })
        assertFailsWith<ManifestException> { UpdatePlanner.plan(broken, layout()) }
        assertFailsWith<ManifestException> { UpdatePlanner.plan(manifest.copy(platform = "windows-x64"), layout()) }
    }

    @Test
    fun `the release list is read for this system's assets`() {
        val body = """[{"tag_name":"v1.58.0","html_url":"https://github.com/Ethan-Lucent/Lucense/releases/tag/v1.58.0","assets":[
            {"name":"Lucense-1.58.0-linux-x64-manifest.json","browser_download_url":"https://github.com/a/m.json","size":10},
            {"name":"Lucense-1.58.0-windows-x64-manifest.json","browser_download_url":"https://github.com/a/w.json","size":10},
            {"name":"Lucense-1.58.0-linux-x64.zip","browser_download_url":"https://github.com/a/z.zip","size":99}]},
            {"tag_name":"v1.57.1","assets":[]}]"""
        val release = assertNotNull(UpdateRelease.parse(body))
        assertEquals("1.58.0", release.version)
        assertEquals("https://github.com/a/m.json", release.manifestFor("linux-x64")?.url)
        assertEquals("https://github.com/a/w.json", release.manifestFor("windows-x64")?.url)
        assertNotNull(release.asset("Lucense-1.58.0-linux-x64.zip"))
        assertNull(UpdateRelease.parse("""{"message":"rate limited"}"""))
        assertNull(UpdateRelease.parse("""[{"tag_name":"nightly"}]"""))
        // The page link only ever goes to github.com.
        val page = UpdateRelease.parse("""[{"tag_name":"v2.0.0","html_url":"https://evil.example/x"}]""")?.page
        assertEquals("https://github.com/Ethan-Lucent/Lucense/releases", page)
        assertTrue(com.exo.musicplayer.data.update.Updates.RELEASES.contains("Ethan-Lucent/Lucense"))
    }
}
