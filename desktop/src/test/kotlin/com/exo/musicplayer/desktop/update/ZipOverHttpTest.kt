package com.exo.musicplayer.desktop.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ZipOverHttpTest {
    private lateinit var dir: File
    private lateinit var server: TestServer
    private lateinit var image: File
    private lateinit var zipBytes: ByteArray

    @BeforeTest
    fun setUp() {
        dir = UpdateTestKit.tempDir("zip")
        server = TestServer()
        image = UpdateTestKit.linuxImage(File(dir, "image"), "1.1.0", File(dir, "launch.log"))
        zipBytes = UpdateTestKit.zipOf(image, File(dir, "image.zip"), stored = setOf("lib/app/common-1.0.jar")).readBytes()
    }

    @AfterTest
    fun tearDown() {
        server.close()
        UpdateTestKit.cleanUp(dir)
    }

    private fun zip(url: String, http: UpdateHttp = UpdateTestKit.http()) = ZipOverHttp(http.Ranged(url))

    private suspend fun ZipOverHttp.bytesOf(name: String, sha: String? = null): ByteArray {
        val out = ByteArrayOutputStream()
        read(entries.getValue(name), out, sha)
        return out.toByteArray()
    }

    @Test
    fun `reads one entry with a few small ranges, not the whole zip`(): Unit = runBlocking {
        val url = server.put("/a.zip", zipBytes)
        val zip = zip(url)
        zip.open()
        // Folder entries are skipped; every file is there.
        assertFalse(zip.entries.keys.any { it.endsWith("/") })
        assertEquals(image.walkTopDown().count { it.isFile }, zip.entries.size)
        val cfg = File(image, "lib/app/Lucense.cfg")
        val got = zip.bytesOf("Lucense/lib/app/Lucense.cfg", UpdatePlanner.sha256(cfg))
        assertContentEquals(cfg.readBytes(), got)
        // A stored (uncompressed) entry reads too.
        val common = File(image, "lib/app/common-1.0.jar")
        assertContentEquals(common.readBytes(), zip.bytesOf("Lucense/lib/app/common-1.0.jar"))
        assertTrue(zip.received < zipBytes.size / 4, "received ${zip.received} of ${zipBytes.size}")
        assertEquals(zip.received, server.rangeBytesAsked.get())
        assertTrue(server.requests.all { !it.endsWith(" -") }, "every request asked for a range: ${server.requests}")
    }

    @Test
    fun `follows a GitHub-like redirect once, re-sending Range, and reuses the final address`(): Unit = runBlocking {
        server.put("/storage/blob.zip", zipBytes)
        server.redirects["/releases/download/v1.1.0/Lucense.zip"] = server.url("/storage/blob.zip") + "?sig=abc&expires=1"
        val zip = zip(server.url("/releases/download/v1.1.0/Lucense.zip"))
        zip.open()
        zip.bytesOf("Lucense/bin/Lucense")
        zip.bytesOf("Lucense/lib/app/Lucense.cfg")
        val toAsset = server.requests.count { it.startsWith("/releases/") }
        assertEquals(1, toAsset, "the asset address is asked once: ${server.requests}")
        assertTrue(server.requests.filter { it.startsWith("/storage/") }.all { it.contains("bytes=") })
        assertTrue(server.requests.first().contains("bytes="), "Range is sent on the first hop too")
    }

    @Test
    fun `a server that ignores Range stops the download at once`(): Unit = runBlocking {
        server.honourRanges = false
        val zip = zip(server.put("/a.zip", zipBytes))
        val error = assertFailsWith<RangesUnsupportedException> { zip.open() }
        assertTrue(error.message!!.contains("ranges unsupported"))
        assertEquals(0, zip.received)
        assertEquals(1, server.requests.size, "no second try: ${server.requests}")
    }

    @Test
    fun `a truncated zip is refused cleanly`(): Unit = runBlocking {
        val zip = zip(server.put("/cut.zip", zipBytes.copyOf(zipBytes.size - 300)))
        val error = assertFailsWith<UpdateException> { zip.open() }
        assertTrue(error.message!!.contains("central directory"), error.message)
    }

    @Test
    fun `a damaged entry fails its CRC check`(): Unit = runBlocking {
        // The stored entry's bytes sit as they are in the zip: change one.
        val common = File(image, "lib/app/common-1.0.jar").readBytes()
        val at = indexOf(zipBytes, common.copyOfRange(0, 64))
        assertTrue(at > 0)
        val damaged = zipBytes.copyOf().also { it[at + 1000] = (it[at + 1000] + 1).toByte() }
        val zip = zip(server.put("/bad.zip", damaged))
        zip.open()
        val error = assertFailsWith<UpdateException> { zip.bytesOf("Lucense/lib/app/common-1.0.jar") }
        assertTrue(error.message!!.contains("CRC"), error.message)
    }

    @Test
    fun `a file that doesn't match the release's checksum is refused`(): Unit = runBlocking {
        val zip = zip(server.put("/a.zip", zipBytes))
        zip.open()
        val error = assertFailsWith<UpdateException> { zip.bytesOf("Lucense/bin/Lucense", "0".repeat(64)) }
        assertTrue(error.message!!.contains("checksum"), error.message)
    }

    @Test
    fun `a failed or cut-short range is retried and carries on`(): Unit = runBlocking {
        val zip = zip(server.put("/a.zip", zipBytes))
        zip.open()
        server.failRanges.set(2)
        server.cutShort.set(1)
        val modules = File(image, "lib/runtime/lib/modules")
        assertContentEquals(modules.readBytes(), zip.bytesOf("Lucense/lib/runtime/lib/modules", UpdatePlanner.sha256(modules)))
    }

    @Test
    fun `a server that keeps failing gives up after three retries`(): Unit = runBlocking {
        val zip = zip(server.put("/a.zip", zipBytes))
        zip.open()
        server.failRanges.set(100)
        val before = server.requests.size
        assertFailsWith<UpdateException> { zip.bytesOf("Lucense/bin/Lucense") }
        assertEquals(4, server.requests.size - before)
    }

    @Test
    fun `plain http and redirects to it are refused`(): Unit = runBlocking {
        val strict = UpdateHttp(UrlPolicy(trustedHosts = setOf("127.0.0.1")))
        assertFailsWith<UpdateException> { zip(server.put("/a.zip", zipBytes), strict).open() }
        // Allowed to start on 127.0.0.1 over http, but the redirect goes to another plain-http host.
        server.redirects["/r.zip"] = "http://localhost:${server.port}/a.zip"
        val error = assertFailsWith<UpdateException> { zip(server.url("/r.zip")).open() }
        assertTrue(error.message!!.contains("insecure"), error.message)
        assertFailsWith<UpdateException> { UrlPolicy().checkStart("https://example.com/x.zip") }
        assertFailsWith<UpdateException> { UrlPolicy().checkStart("http://github.com/x.zip") }
        UrlPolicy().checkStart("https://github.com/Ethan-Lucent/Lucense/releases/download/v1/x.zip")
        UrlPolicy().checkHop("https://objects.githubusercontent.com/x?sig=1")
    }

    @Test
    fun `a download can be cancelled while it runs`(): Unit = runBlocking {
        val zip = zip(server.put("/a.zip", zipBytes))
        zip.open()
        server.slowMs = 200
        val reading = async(Dispatchers.IO) {
            zip.read(zip.entries.getValue("Lucense/lib/runtime/lib/modules"), OutputStream.nullOutputStream())
        }
        kotlinx.coroutines.delay(400)
        withTimeout(5_000) { reading.cancelAndJoin() }
        assertTrue(reading.isCancelled)
    }

    @Test
    fun `a ZIP64 zip is read`(): Unit = runBlocking {
        // More entries than the plain format can count makes Java write the ZIP64 records.
        val big = File(dir, "zip64.zip")
        ZipOutputStream(big.outputStream().buffered()).use { out ->
            for (i in 0 until 65_600) {
                out.putNextEntry(ZipEntry("Lucense/f/$i"))
                out.write("file $i".toByteArray())
                out.closeEntry()
            }
        }
        val zip = zip(server.put("/64.zip", big.readBytes()))
        zip.open()
        assertEquals(65_600, zip.entries.size)
        assertEquals("file 65599", String(zip.bytesOf("Lucense/f/65599")))
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
