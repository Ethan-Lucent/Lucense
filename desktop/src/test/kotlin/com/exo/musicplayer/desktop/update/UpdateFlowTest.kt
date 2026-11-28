package com.exo.musicplayer.desktop.update

import com.exo.musicplayer.desktop.system.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The whole updater against a fake GitHub on a local server: the release list,
 * the manifest and a ranged zip behind a GitHub-like redirect, then check,
 * download, staging, the helper for real (Linux), and the next start's notice.
 */
class UpdateFlowTest {
    private lateinit var dir: File
    private lateinit var log: File
    private lateinit var install: File
    private lateinit var next: File
    private lateinit var updates: File
    private lateinit var server: TestServer
    private lateinit var zipBytes: ByteArray
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val exits = AtomicInteger()

    @BeforeTest
    fun setUp() {
        assumeTrue(Platform.isLinux)
        dir = UpdateTestKit.tempDir("flow")
        log = File(dir, "launch.log")
        install = UpdateTestKit.linuxImage(File(dir, "install"), "1.0.0", log)
        next = UpdateTestKit.linuxImage(File(dir, "next"), "1.1.0", log, extraFolder = true)
        updates = File(dir, "updates")
        server = TestServer()
        publish("1.1.0", next)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        if (::server.isInitialized) server.close()
        if (::dir.isInitialized) UpdateTestKit.cleanUp(dir)
    }

    /** Puts a release of [image] on the fake GitHub, as the release side lays it out. */
    private fun publish(version: String, image: File, withDelta: Boolean = true, manifestVersion: String = version) {
        val manifest = UpdateTestKit.manifestOf(image, manifestVersion)
        val zipName = "Lucense-$manifestVersion-linux-x64.zip"
        zipBytes = UpdateTestKit.zipOf(image, File(dir, zipName)).readBytes()
        val download = "/releases/download/v$version"
        server.put("$download/Lucense-$version-linux-x64-manifest.json", manifest.toJson().toByteArray())
        server.put("/storage/$zipName", zipBytes)
        // GitHub answers an asset's address with a redirect to signed storage.
        server.redirects["$download/$zipName"] = server.url("/storage/$zipName") + "?X-Amz-Signature=abc"
        val assets = JSONArray()
        if (withDelta) {
            assets.put(JSONObject().put("name", "Lucense-$version-linux-x64-manifest.json").put("browser_download_url", server.url("$download/Lucense-$version-linux-x64-manifest.json")))
            assets.put(JSONObject().put("name", zipName).put("browser_download_url", server.url("$download/$zipName")).put("size", zipBytes.size))
        }
        // Another system's files are there too, and ignored.
        assets.put(JSONObject().put("name", "Lucense-$version-windows-x64-manifest.json").put("browser_download_url", server.url("/nope")))
        assets.put(JSONObject().put("name", "lucense_${version}_amd64.deb").put("browser_download_url", server.url("/deb")))
        val releases = JSONArray().put(
            JSONObject().put("tag_name", "v$version").put("html_url", "https://github.com/Ethan-Lucent/Lucense/releases/tag/v$version").put("assets", assets)
        )
        server.put("/repos/Ethan-Lucent/Lucense/releases", releases.toString().toByteArray())
    }

    private fun layout(version: String = "1.0.0") =
        InstallLayout.detect(File(install, "lib/app/resources").path, version, "Linux", "amd64")

    private fun controller(
        version: String = "1.0.0",
        layout: InstallLayout? = layout(version),
        pkexec: File? = null
    ) = UpdateController(
        scope = scope,
        currentVersion = version,
        layout = layout,
        updatesRoot = updates,
        http = UpdateTestKit.http(),
        releasesUrl = server.url("/repos/Ethan-Lucent/Lucense/releases"),
        findProgram = { name -> if (name == "pkexec") pkexec else Platform.onPath(name) },
        exitApp = { exits.incrementAndGet() },
        helperEnvironment = mapOf("LUCENSE_UPDATE_WAIT_SECONDS" to "5"),
        pid = UpdateTestKit.deadPid()
    )

    private suspend fun UpdateController.settle(timeoutMs: Long = 30_000, until: (UpdateState) -> Boolean): UpdateState {
        UpdateTestKit.until(timeoutMs) { until(state) }
        return state
    }

    private suspend fun UpdateController.available(): UpdateState.Available {
        check()
        val s = settle { it is UpdateState.Available && (it.summary != null || !it.canDelta) || it is UpdateState.Ready || it is UpdateState.Idle }
        return assertIs<UpdateState.Available>(s, "state: $s")
    }

    @Test
    fun `check, download only the changed files, apply, and hear about it on the next start`(): Unit = runBlocking {
        val updater = controller()
        val found = updater.available()
        assertTrue(found.canDelta)
        val summary = assertNotNull(found.summary)
        assertEquals(5, summary.files)
        assertEquals(2, summary.stale)
        assertEquals("Version 1.1.0 is out.", updater.note)
        // The check reads the zip's directory (to know the real download size), not its files.
        val atCheck = server.rangeBytesAsked.get()
        assertTrue(atCheck in 1..100_000, "only the zip's end was read before the button: $atCheck of ${zipBytes.size}")

        updater.download()
        assertIs<UpdateState.Ready>(updater.settle { it is UpdateState.Ready || it is UpdateState.Failed }, "state: ${updater.state}")
        assertTrue(File(updates, "1.1.0/files/lib/app/desktop-1_1_0.jar").isFile)
        assertTrue(File(updates, "1.1.0/files").walkTopDown().none { it.name.endsWith(".part") })
        val fetched = server.rangeBytesAsked.get()
        assertTrue(fetched < zipBytes.size / 2, "fetched $fetched of ${zipBytes.size}")
        // Once to learn the download size at the check, once for the download.
        assertEquals(2, server.requests.count { it.startsWith("/releases/download/v1.1.0/Lucense-1.1.0-linux-x64.zip") })

        updater.applyAndRestart()
        UpdateTestKit.until { exits.get() == 1 }
        UpdateTestKit.until(60_000) { File(updates, "result.json").isFile }
        UpdateTestKit.until { log.isFile && log.readText().isNotBlank() }
        assertEquals(UpdateTestKit.snapshot(next), UpdateTestKit.snapshot(install))
        assertEquals(listOf("started 1.1.0 "), log.readLines())

        // The next start, now 1.1.0.
        val restarted = controller(version = "1.1.0")
        assertEquals("Updated to 1.1.0.", restarted.startup())
        assertEquals("Updated to 1.1.0.", restarted.note)
        assertFalse(File(updates, "result.json").exists(), "said once")
        assertFalse(File(updates, "1.1.0").exists(), "the staging folder, with its backup, is cleared")
        assertTrue(File(updates, UpdateController.LAST_LOG).isFile)
        assertNull(restarted.startup())
        // And the release list now says it is current.
        restarted.check()
        restarted.settle { it == UpdateState.Idle && restarted.note == "Up to date." }
    }

    @Test
    fun `a download already made is picked up again instead of fetched twice`(): Unit = runBlocking {
        val first = controller()
        first.available()
        first.download()
        first.settle { it is UpdateState.Ready }
        val asked = server.requests.count { it.startsWith("/storage/") }
        val second = controller()
        second.check()
        assertIs<UpdateState.Ready>(second.settle { it is UpdateState.Ready || it is UpdateState.Failed })
        assertEquals(asked, server.requests.count { it.startsWith("/storage/") })
        second.later()
        assertEquals(UpdateState.Ready("1.1.0", deferred = true), second.state)
    }

    @Test
    fun `a cancelled download goes back to the offer and can be finished later`(): Unit = runBlocking {
        val updater = controller()
        updater.available()
        server.slowMs = 30
        updater.download()
        updater.settle { it is UpdateState.Downloading && it.bytesDone > 0 }
        updater.cancel()
        assertIs<UpdateState.Available>(updater.settle { it !is UpdateState.Downloading })
        assertTrue(File(updates, "1.1.0/files").walkTopDown().none { it.name.endsWith(".part") }, "no half file is left")
        server.slowMs = 0
        updater.download()
        assertIs<UpdateState.Ready>(updater.settle { it is UpdateState.Ready || it is UpdateState.Failed })
    }

    @Test
    fun `without update files, or not installed by the installer, the release page is offered`(): Unit = runBlocking {
        publish("1.1.0", next, withDelta = false)
        val noDelta = controller().available()
        assertFalse(noDelta.canDelta)
        assertTrue(noDelta.whyNot!!.contains("no update files"))
        assertEquals("https://github.com/Ethan-Lucent/Lucense/releases/tag/v1.1.0", noDelta.release.page)

        publish("1.1.0", next)
        val devRun = controller(layout = null).available()
        assertFalse(devRun.canDelta)
        assertTrue(devRun.whyNot!!.contains("installer"))
    }

    @Test
    fun `a manifest for another version than the release is refused`(): Unit = runBlocking {
        publish("1.1.0", next, manifestVersion = "1.2.0")
        val offered = controller().available()
        assertFalse(offered.canDelta)
        assertTrue(offered.whyNot!!.contains("not 1.1.0"), offered.whyNot)
        // An older release than the one running is never offered at all.
        publish("0.9.0", next)
        val updater = controller()
        updater.check()
        updater.settle { it == UpdateState.Idle && updater.note == "Up to date." }
    }

    @Test
    fun `GitHub out of reach is said as before`(): Unit = runBlocking {
        server.files.remove("/repos/Ethan-Lucent/Lucense/releases")
        val updater = controller()
        updater.check()
        updater.settle { it == UpdateState.Idle && updater.note == "Could not reach GitHub." }
    }

    @Test
    fun `a server without ranges fails for good and offers the installer`(): Unit = runBlocking {
        val updater = controller()
        updater.available()
        server.honourRanges = false
        updater.download()
        val failed = assertIs<UpdateState.Failed>(updater.settle { it is UpdateState.Failed || it is UpdateState.Ready })
        assertFalse(failed.canRetry)
        assertTrue(failed.message.contains("ranges unsupported"))
    }

    @Test
    fun `a dismissed password prompt keeps the app running, and with no way to ask it says so`(): Unit = runBlocking {
        assumeTrue(System.getProperty("user.name") != "root")
        val pkexec = UpdateTestKit.write(File(dir, "bin/pkexec"), "#!/bin/sh\nexit 126\n".toByteArray(), exec = true)
        val updater = controller(pkexec = pkexec)
        updater.available()
        updater.download()
        updater.settle { it is UpdateState.Ready }
        val before = UpdateTestKit.snapshot(install)
        // A system install: the user can't write the jar folder.
        File(install, "lib/app").setWritable(false, false)
        try {
            updater.applyAndRestart()
            val ready = assertIs<UpdateState.Ready>(updater.settle { it !is UpdateState.Applying })
            assertEquals("Update cancelled.", ready.note)
            assertEquals(0, exits.get(), "the app doesn't exit")

            val noPkexec = controller(pkexec = null)
            noPkexec.check()
            noPkexec.settle { it is UpdateState.Ready }
            noPkexec.applyAndRestart()
            val failed = assertIs<UpdateState.Failed>(noPkexec.settle { it !is UpdateState.Applying })
            assertTrue(failed.message.contains("administrator rights"))
            assertTrue(failed.message.contains("installer"))
            assertEquals(0, exits.get())
        } finally {
            File(install, "lib/app").setWritable(true, true)
        }
        assertEquals(before, UpdateTestKit.snapshot(install))
    }

    @Test
    fun `a failed update is told once on the next start`() {
        updates.mkdirs()
        File(updates, "result.json").writeText("""{"ok":false,"version":"1.1.0","error":"lib/app/x.jar doesn't match the release after installing."}""")
        val updater = controller()
        assertEquals(
            "The update to 1.1.0 didn't work, and your old version was put back: lib/app/x.jar doesn't match the release after installing.",
            updater.startup()
        )
        assertNull(updater.startup())
    }

    @Test
    fun `old staging folders are cleared, links inside them are not followed`(): Unit = runBlocking {
        val outside = UpdateTestKit.write(File(dir, "music/song.flac"), "precious".toByteArray())
        val old = File(updates, "0.9.0/files").apply { mkdirs() }
        Files.createSymbolicLink(File(old, "link").toPath(), outside.parentFile.toPath())
        File(updates, "1.1.0/files").mkdirs()
        val stale = File(updates, "1.2.0").apply { mkdirs() }
        stale.setLastModified(System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000)
        val updater = controller()
        updater.startup()
        assertFalse(File(updates, "0.9.0").exists())
        assertFalse(stale.exists())
        assertTrue(File(updates, "1.1.0").exists(), "a newer one, still fresh, is kept")
        assertTrue(outside.isFile, "the folder a link pointed to is untouched")
        File(updates, "1.3.0").mkdirs()
        updater.cleanStaging(keep = "1.1.0")
        assertTrue(File(updates, "1.1.0").exists())
        assertFalse(File(updates, "1.3.0").exists())
    }
}
