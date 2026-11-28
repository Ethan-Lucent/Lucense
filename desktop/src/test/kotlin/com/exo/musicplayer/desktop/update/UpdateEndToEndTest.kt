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
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The whole update on a copy of a real installed Lucense, headless, through
 * the non-UI classes only. Opt in with LUCENSE_UPDATER_E2E=/opt/lucense (the
 * install to copy; it is only read). The copy's launcher is swapped for a
 * stand-in before anything else, so the real app is never started, and the
 * user's own data folder is never used: staging lives in the temp folder.
 *
 * "New" is the copy with this worktree's freshly built desktop and shared
 * jars, its launcher settings pointing at them with the next version number,
 * and (as every Linux build makes its own) a changed class archive.
 * The numbers go to /tmp/updater-e2e.txt.
 */
class UpdateEndToEndTest {

    @Test
    fun `update a copy of the real install with this build's jars`(): Unit = runBlocking {
        val source = System.getenv("LUCENSE_UPDATER_E2E")?.let(::File)
        assumeTrue(source != null && Platform.isLinux && File(source, "bin/Lucense").isFile)
        source!!
        val desktopJar = File("build/libs/desktop.jar")
        val sharedJar = File("../shared/build/libs/shared.jar")
        assumeTrue(desktopJar.isFile && sharedJar.isFile, "build :desktop:jar :shared:jar first")
        val work = UpdateTestKit.tempDir("e2e")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val report = StringBuilder()
        try {
            val installed = File(work, "lucense")
            exec(listOf("cp", "-a", source.absolutePath, installed.absolutePath), 600)
            val log = File(work, "launch.log")
            // Never the real app: a stand-in that only notes the version it would have started.
            UpdateTestKit.write(
                File(installed, "bin/Lucense"),
                (
                    "#!/bin/sh\nhere=\$(dirname \"\$0\")\n" +
                        "v=\$(sed -n 's/^java-options=-Dlucense.version=//p' \"\$here/../lib/app/Lucense.cfg\")\n" +
                        "printf 'started %s\\n' \"\$v\" >> '${log.absolutePath}'\n"
                    ).toByteArray(),
                exec = true
            )
            val cfg = File(installed, "lib/app/Lucense.cfg").readText()
            val oldVersion = Regex("-Dlucense.version=([0-9.]+)").find(cfg)!!.groupValues[1]
            val parts = oldVersion.split('.').map { it.toInt() }
            val newVersion = (parts.dropLast(1) + (parts.last() + 1)).joinToString(".")

            // The new app image: what the release build would make from this worktree.
            val image = File(work, "image/Lucense")
            image.parentFile.mkdirs()
            exec(listOf("cp", "-a", installed.absolutePath, image.absolutePath), 600)
            File(image, "share").deleteRecursively() // the package's menu entry and icons, not part of the app image
            val appDir = File(image, "lib/app")
            val oldDesktop = appDir.listFiles()!!.single { Regex("desktop-[0-9a-f]+\\.jar").matches(it.name) }
            val oldShared = appDir.listFiles()!!.single { Regex("shared-[0-9a-f]+\\.jar").matches(it.name) }
            val newDesktop = "desktop-${md5(desktopJar).take(31)}.jar"
            val newShared = "shared-${md5(sharedJar).take(30)}.jar"
            oldDesktop.delete()
            oldShared.delete()
            desktopJar.copyTo(File(appDir, newDesktop))
            sharedJar.copyTo(File(appDir, newShared))
            File(appDir, "Lucense.cfg").writeText(
                cfg.replace(oldDesktop.name, newDesktop).replace(oldShared.name, newShared).replace("=$oldVersion", "=$newVersion")
            )
            val jsa = File(image, "lib/runtime/lib/server/classes.jsa")
            if (jsa.isFile) {
                java.io.RandomAccessFile(jsa, "rw").use { raf ->
                    raf.seek(raf.length() / 2)
                    raf.write(ByteArray(4096) { (it * 31).toByte() })
                }
            }

            val manifest = UpdateTestKit.manifestOf(image, newVersion)
            val zip = UpdateTestKit.zipOf(image, File(work, manifest.zip))
            val imageBytes = manifest.files.sumOf { it.size }

            TestServer().use { server ->
                val download = "/releases/download/v$newVersion"
                server.put("$download/Lucense-$newVersion-linux-x64-manifest.json", manifest.toJson().toByteArray())
                server.onDisk["/storage/${manifest.zip}"] = zip
                server.redirects["$download/${manifest.zip}"] = server.url("/storage/${manifest.zip}") + "?sig=e2e"
                val assets = JSONArray()
                    .put(JSONObject().put("name", "Lucense-$newVersion-linux-x64-manifest.json").put("browser_download_url", server.url("$download/Lucense-$newVersion-linux-x64-manifest.json")))
                    .put(JSONObject().put("name", manifest.zip).put("browser_download_url", server.url("$download/${manifest.zip}")).put("size", zip.length()))
                server.put(
                    "/repos/Ethan-Lucent/Lucense/releases",
                    JSONArray().put(JSONObject().put("tag_name", "v$newVersion").put("html_url", "https://github.com/Ethan-Lucent/Lucense/releases/tag/v$newVersion").put("assets", assets)).toString().toByteArray()
                )

                val exits = AtomicInteger()
                val updates = File(work, "update")
                val updater = UpdateController(
                    scope = scope,
                    currentVersion = oldVersion,
                    layout = InstallLayout.detect(File(installed, "lib/app/resources").path, oldVersion, "Linux", "amd64"),
                    updatesRoot = updates,
                    http = UpdateTestKit.http(),
                    releasesUrl = server.url("/repos/Ethan-Lucent/Lucense/releases"),
                    findProgram = { Platform.onPath(it).takeIf { _ -> it != "pkexec" } },
                    exitApp = { exits.incrementAndGet() },
                    pid = UpdateTestKit.deadPid()
                )
                var t = System.nanoTime()
                updater.check()
                UpdateTestKit.until(300_000) { (updater.state as? UpdateState.Available)?.summary != null || updater.state is UpdateState.Ready }
                val checkMs = (System.nanoTime() - t) / 1_000_000
                val offer = assertIs<UpdateState.Available>(updater.state)
                val askedBefore = server.rangeBytesAsked.get()

                t = System.nanoTime()
                updater.download()
                UpdateTestKit.until(300_000) { updater.state is UpdateState.Ready || updater.state is UpdateState.Failed }
                val downloadMs = (System.nanoTime() - t) / 1_000_000
                assertIs<UpdateState.Ready>(updater.state, "state: ${updater.state}")
                val fetched = server.rangeBytesAsked.get() - askedBefore
                val requests = server.requests.count { it.startsWith("/storage/") }

                t = System.nanoTime()
                updater.applyAndRestart()
                UpdateTestKit.until(60_000) { exits.get() == 1 }
                UpdateTestKit.until(120_000) { File(updates, "result.json").isFile }
                val applyMs = (System.nanoTime() - t) / 1_000_000
                val outcome = UpdateApplier.readResult(File(updates, "result.json"))
                assertEquals(ApplyResult(true, newVersion, null), outcome, File(updates, "$newVersion/apply.log").readText())
                UpdateTestKit.until { log.isFile && log.readText().isNotBlank() }
                assertEquals(listOf("started $newVersion"), log.readLines())

                // The copy now holds exactly the new image (and its untouched share/ folder).
                val after = UpdateTestKit.snapshot(installed).filterKeys { !it.startsWith("share/") }
                assertEquals(UpdateTestKit.snapshot(image), after)
                assertTrue(!File(installed, "lib/app/${oldDesktop.name}").exists())

                report.appendLine("Lucense in-app update, end to end on a copy of ${source.absolutePath} ($oldVersion -> $newVersion), ${java.time.LocalDateTime.now()}")
                report.appendLine("App image: ${manifest.files.size} files, ${UpdateText.size(imageBytes)} ($imageBytes bytes); release zip ${UpdateText.size(zip.length())} (${zip.length()} bytes)")
                report.appendLine("To fetch: ${offer.summary!!.files} files, ${UpdateText.size(offer.summary!!.bytes)} uncompressed; old jars to remove: ${offer.summary!!.stale}")
                val packed = java.util.zip.ZipFile(zip).use { z ->
                    fetchedPaths(updates, newVersion).associateWith { z.getEntry(manifest.prefix + it).compressedSize }
                }
                packed.keys.sorted().forEach { path ->
                    report.appendLine("  $path  ${UpdateText.size(File(image, path).length())} (${UpdateText.size(packed.getValue(path))} in the zip)")
                }
                val withoutArchive = packed.filterKeys { !it.endsWith("classes.jsa") }.values.sum()
                report.appendLine("Of that, without the class archive: ${UpdateText.size(withoutArchive)} compressed")
                report.appendLine("Downloaded over HTTP (ranges, compressed): ${UpdateText.size(fetched)} ($fetched bytes) in $requests range requests")
                report.appendLine("  = ${"%.2f".format(100.0 * fetched / zip.length())}% of the zip, ${"%.2f".format(100.0 * fetched / imageBytes)}% of the image")
                report.appendLine("Times: check+compare ${checkMs} ms, download ${downloadMs} ms, swap+verify ${applyMs} ms")
                report.appendLine("Result: ${File(updates, "result.json").readText().trim()}; launcher stand-in started: ${log.readText().trim()}")
                report.appendLine("(classes.jsa was changed on purpose in the new image, since each Linux build makes its own; the launcher is a stand-in in both, so the real app never ran.)")
            }
            File("/tmp/updater-e2e.txt").writeText(report.toString())
            println(report)
        } finally {
            scope.cancel()
            UpdateTestKit.cleanUp(work)
        }
    }

    private fun fetchedPaths(updates: File, version: String): Set<String> {
        val files = File(updates, "$version/files")
        return files.walkTopDown().filter { it.isFile }.map { it.relativeTo(files).invariantSeparatorsPath }.toSet()
    }

    private fun md5(file: File): String =
        MessageDigest.getInstance("MD5").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun exec(command: List<String>, seconds: Long) {
        val process = ProcessBuilder(command).redirectInput(ProcessBuilder.Redirect.from(File("/dev/null"))).redirectErrorStream(true).start()
        if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("timed out: $command")
        }
        check(process.exitValue() == 0) { "$command: " + process.inputStream.bufferedReader().readText() }
    }
}
