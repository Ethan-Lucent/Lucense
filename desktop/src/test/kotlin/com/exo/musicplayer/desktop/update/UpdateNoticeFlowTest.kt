package com.exo.musicplayer.desktop.update

import com.exo.musicplayer.desktop.data.DesktopSettings
import com.exo.musicplayer.desktop.system.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.prefs.Preferences
import java.util.zip.ZipFile
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
 * The launch notice and the one-click update against the fake GitHub: what
 * the look-up at launch does with the setting on and off, the size it
 * announces, and "Update now" going all the way (and stopping where it fails).
 */
class UpdateNoticeFlowTest {
    private lateinit var dir: File
    private lateinit var log: File
    private lateinit var install: File
    private lateinit var next: File
    private lateinit var updates: File
    private lateinit var server: TestServer
    private lateinit var node: Preferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val exits = AtomicInteger()

    @BeforeTest
    fun setUp() {
        assumeTrue(Platform.isLinux)
        dir = UpdateTestKit.tempDir("notice")
        log = File(dir, "launch.log")
        install = UpdateTestKit.linuxImage(File(dir, "install"), "1.0.0", log)
        next = UpdateTestKit.linuxImage(File(dir, "next"), "1.1.0", log, extraFolder = true)
        updates = File(dir, "updates")
        server = TestServer()
        node = Preferences.userRoot().node("com/exo/musicplayer-noticeflow-${System.nanoTime()}")
        publish("1.1.0", next)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        if (::server.isInitialized) server.close()
        if (::node.isInitialized) node.removeNode()
        if (::dir.isInitialized) UpdateTestKit.cleanUp(dir)
    }

    private fun publish(version: String, image: File) {
        val manifest = UpdateTestKit.manifestOf(image, version)
        val zipName = "Lucense-$version-linux-x64.zip"
        val zipBytes = UpdateTestKit.zipOf(image, File(dir, zipName)).readBytes()
        val download = "/releases/download/v$version"
        server.put("$download/Lucense-$version-linux-x64-manifest.json", manifest.toJson().toByteArray())
        server.put("/storage/$zipName", zipBytes)
        server.redirects["$download/$zipName"] = server.url("/storage/$zipName") + "?X-Amz-Signature=abc"
        val assets = JSONArray()
        assets.put(JSONObject().put("name", "Lucense-$version-linux-x64-manifest.json").put("browser_download_url", server.url("$download/Lucense-$version-linux-x64-manifest.json")))
        assets.put(JSONObject().put("name", zipName).put("browser_download_url", server.url("$download/$zipName")).put("size", zipBytes.size))
        val releases = JSONArray().put(
            JSONObject().put("tag_name", "v$version").put("html_url", "https://github.com/Ethan-Lucent/Lucense/releases/tag/v$version").put("assets", assets)
        )
        server.put("/repos/Ethan-Lucent/Lucense/releases", releases.toString().toByteArray())
    }

    private fun layout() = InstallLayout.detect(File(install, "lib/app/resources").path, "1.0.0", "Linux", "amd64")

    private fun controller(pkexec: File? = null) = UpdateController(
        scope = scope,
        currentVersion = "1.0.0",
        layout = layout(),
        updatesRoot = updates,
        http = UpdateTestKit.http(),
        releasesUrl = server.url("/repos/Ethan-Lucent/Lucense/releases"),
        findProgram = { name -> if (name == "pkexec") pkexec else Platform.onPath(name) },
        exitApp = { exits.incrementAndGet() },
        helperEnvironment = mapOf("LUCENSE_UPDATE_WAIT_SECONDS" to "5"),
        pid = UpdateTestKit.deadPid()
    )

    private fun notice(updater: UpdateController, on: Boolean = true) =
        UpdateNotice(updater, { on }, { DesktopSettings(node).updateSkipped }, { DesktopSettings(node).updateSkipped = it })

    /** Looks up as the app starts and waits for the card to be announced. */
    private suspend fun UpdateNotice.launchAndAnnounce(updater: UpdateController): NoticeView {
        assertTrue(launchLookup())
        UpdateTestKit.until { evaluate(modalOpen = false); announced != null }
        return view()
    }

    private suspend fun UpdateController.settle(until: (UpdateState) -> Boolean) = UpdateTestKit.until(30_000) { until(state) }

    private fun stagedFiles() = File(updates, "1.1.0/files").walkTopDown().filter { it.isFile && !it.name.endsWith(".part") }.toList()

    @Test
    fun `the size announced is the zip's compressed size of the files fetched, smaller than the installed one`(): Unit = runBlocking {
        val updater = controller()
        val notice = notice(updater)
        val offer = assertIs<NoticeView.Offer>(notice.launchAndAnnounce(updater))
        val summary = assertNotNull(assertIs<UpdateState.Available>(updater.state).summary)

        // Worked out here from the zip itself: the compressed bytes of just the files that differ.
        val plan = UpdatePlanner.plan(UpdateTestKit.manifestOf(next, "1.1.0"), assertNotNull(layout()))
        val compressed = ZipFile(File(dir, "Lucense-1.1.0-linux-x64.zip")).use { zip ->
            plan.toFetch.sumOf { assertNotNull(zip.getEntry("Lucense/${it.path}"), it.path).compressedSize }
        }
        assertEquals(compressed, summary.transferBytes)
        assertTrue(summary.transferExact)
        assertTrue(summary.transferBytes < summary.bytes, "${summary.transferBytes} < ${summary.bytes}: the text-like jars deflate")
        assertEquals(plan.bytes, summary.bytes, "the installed total is kept for the free-space check")
        assertEquals(UpdateText.size(compressed), offer.size)
        assertEquals("Update to 1.1.0  (${UpdateText.size(compressed)})", UpdateText.updateButton("1.1.0", summary.transferBytes, summary.transferExact))
        assertEquals("1.1.0", offer.version)
        assertFalse(offer.needsAdmin)
    }

    @Test
    fun `when the zip can't be opened at the check the offer says up to the installed total`(): Unit = runBlocking {
        server.honourRanges = false
        val updater = controller()
        updater.check()
        updater.settle { it is UpdateState.Available && it.summary != null }
        val summary = assertNotNull(assertIs<UpdateState.Available>(updater.state).summary)
        assertFalse(summary.transferExact)
        assertEquals(summary.bytes, summary.transferBytes)
        assertEquals("up to ${UpdateText.size(summary.bytes)}", UpdateText.transfer(summary.transferBytes, summary.transferExact))
    }

    @Test
    fun `launch with the setting on announces once, waits for a modal, and Later keeps it quiet`(): Unit = runBlocking {
        val updater = controller()
        val notice = notice(updater)
        assertTrue(notice.launchLookup())
        updater.settle { it is UpdateState.Available && it.summary != null }
        // A dialog or Ctrl+K is open: it waits.
        repeat(3) { notice.evaluate(modalOpen = true) }
        assertNull(notice.announced)
        assertEquals(NoticeView.Hidden, notice.view())
        notice.evaluate(modalOpen = false)
        assertEquals("1.1.0", notice.announced)
        assertIs<NoticeView.Offer>(notice.view())
        // Later: gone until the next launch, and not announced a second time.
        notice.later()
        assertEquals(NoticeView.Hidden, notice.view())
        notice.evaluate(modalOpen = false)
        assertEquals(NoticeView.Hidden, notice.view())
    }

    @Test
    fun `a later manual check does not pop the card up when the launch lookup found nothing`(): Unit = runBlocking {
        server.put("/repos/Ethan-Lucent/Lucense/releases", JSONArray().put(JSONObject().put("tag_name", "v1.0.0").put("assets", JSONArray())).toString().toByteArray())
        val updater = controller()
        val notice = notice(updater)
        assertTrue(notice.launchLookup())
        UpdateTestKit.until { updater.note == "Up to date." && !notice.armed }
        publish("1.1.0", next)
        updater.check()
        updater.settle { it is UpdateState.Available && it.summary != null }
        notice.evaluate(modalOpen = false)
        assertNull(notice.announced)
    }

    @Test
    fun `with the setting off nothing is asked of the network and nothing is announced`(): Unit = runBlocking {
        val updater = controller()
        val notice = notice(updater, on = false)
        assertFalse(notice.launchLookup())
        assertFalse(updater.startupCheck(false))
        delay(400)
        assertEquals(emptyList(), server.requests.toList(), "no request at all")
        assertEquals(UpdateState.Idle, updater.state)
        notice.evaluate(modalOpen = false)
        assertNull(notice.announced)
        // "Check now" still works.
        updater.check()
        updater.settle { it is UpdateState.Available && it.summary != null }
        assertTrue(server.requests.isNotEmpty())
        notice.evaluate(modalOpen = false)
        assertNull(notice.announced, "and still no card")
    }

    @Test
    fun `Skip this version is kept, and a newer release is announced again`(): Unit = runBlocking {
        val first = controller()
        val firstNotice = notice(first)
        firstNotice.launchAndAnnounce(first)
        firstNotice.skip()
        assertEquals(NoticeView.Hidden, firstNotice.view())
        assertEquals("1.1.0", DesktopSettings(node).updateSkipped)

        // The next launch, same release: quiet.
        val second = controller()
        val secondNotice = notice(second)
        assertTrue(secondNotice.launchLookup())
        second.settle { it is UpdateState.Available && it.summary != null }
        secondNotice.evaluate(modalOpen = false)
        assertNull(secondNotice.announced)

        // A newer one than the skipped: announced.
        DesktopSettings(node).updateSkipped = "1.0.5"
        secondNotice.launchLookup()
        UpdateTestKit.until { secondNotice.evaluate(false); secondNotice.announced != null }
    }

    @Test
    fun `Update now downloads, verifies, stages, applies and exits without a second click`(): Unit = runBlocking {
        val updater = controller()
        val notice = notice(updater)
        notice.launchAndAnnounce(updater)
        notice.updateNow()
        UpdateTestKit.until(60_000) { exits.get() == 1 }
        assertEquals(1, exits.get())
        // The staged files are the release's, byte for byte.
        val staged = stagedFiles()
        assertEquals(5, staged.size)
        val expected = UpdateTestKit.snapshot(next)
        staged.forEach { file ->
            val path = file.relativeTo(File(updates, "1.1.0/files")).invariantSeparatorsPath
            assertEquals(expected.getValue(path).substringBefore(' '), UpdatePlanner.sha256(file), path)
        }
        UpdateTestKit.until(60_000) { File(updates, "result.json").isFile }
        UpdateTestKit.until { log.isFile && log.readText().isNotBlank() }
        assertEquals(UpdateTestKit.snapshot(next), UpdateTestKit.snapshot(install))
    }

    @Test
    fun `a failure half way stops the chain, keeps what is staged, and Try again carries on to the restart`(): Unit = runBlocking {
        val updater = controller()
        val notice = notice(updater)
        notice.launchAndAnnounce(updater)
        server.slowMs = 60
        notice.updateNow()
        UpdateTestKit.until { stagedFiles().isNotEmpty() }
        server.failRanges.set(1000)
        updater.settle { it is UpdateState.Failed }
        server.slowMs = 0
        val failed = assertIs<NoticeView.Failed>(notice.view())
        assertTrue(failed.canRetry)
        delay(300)
        assertEquals(0, exits.get(), "nothing was applied")
        assertFalse(File(updates, "result.json").exists())
        val kept = stagedFiles().size
        assertTrue(kept in 1..4, "what was verified stays staged: $kept")
        assertTrue(File(updates, "1.1.0/files").walkTopDown().none { it.name.endsWith(".part") })

        // Try again: the same one click, from where it stopped.
        server.failRanges.set(0)
        notice.updateNow()
        UpdateTestKit.until(60_000) { exits.get() == 1 }
        assertEquals(5, stagedFiles().size)
    }

    @Test
    fun `a dismissed password prompt leaves the app up with Update cancelled, Try again and Not now`(): Unit = runBlocking {
        assumeTrue(System.getProperty("user.name") != "root")
        val pkexec = UpdateTestKit.write(File(dir, "bin/pkexec"), "#!/bin/sh\nexit 126\n".toByteArray(), exec = true)
        // A system install: the user can't write the jar folder, so a password will be asked.
        File(install, "lib/app").setWritable(false, false)
        try {
            val updater = controller(pkexec = pkexec)
            val notice = notice(updater)
            val offer = assertIs<NoticeView.Offer>(notice.launchAndAnnounce(updater))
            assertTrue(offer.needsAdmin)
            assertTrue(offerLine(offer).endsWith("You'll be asked for your password once."))
            notice.updateNow()
            UpdateTestKit.until(60_000) { notice.view() == NoticeView.Cancelled }
            assertEquals(0, exits.get(), "the app stays up")
            notice.notNow()
            assertEquals(NoticeView.Hidden, notice.view())
            assertEquals(UpdateState.Ready("1.1.0", deferred = true), updater.state)
        } finally {
            File(install, "lib/app").setWritable(true, true)
        }
    }

    @Test
    fun `the result after a restart is shown in the same card and can be dismissed`() {
        val updater = controller()
        val notice = notice(updater)
        notice.showResult("Updated to 1.1.0.", ok = true)
        assertEquals(NoticeView.Result("Updated to 1.1.0.", true), notice.view())
        notice.dismissResult()
        assertEquals(NoticeView.Hidden, notice.view())
        // What startup() returns on the next start, with its outcome.
        updates.mkdirs()
        File(updates, UpdateController.RESULT).writeText("""{"ok":false,"version":"1.1.0","error":"disk full"}""")
        val message = updater.startup()
        assertEquals(UpdateText.rolledBack("1.1.0", "disk full"), message)
        assertEquals(false, updater.lastResultOk)
    }
}
