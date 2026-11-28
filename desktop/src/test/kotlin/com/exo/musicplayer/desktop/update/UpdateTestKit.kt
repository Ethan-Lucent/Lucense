package com.exo.musicplayer.desktop.update

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/** Builds manifests, zips and fake installs for the updater's tests; nothing here touches the network. */
object UpdateTestKit {

    /** A manifest for every regular file under [image], as the release build makes it. */
    fun manifestOf(image: File, version: String, platform: String = "linux-x64", prefix: String = "Lucense/"): UpdateManifest {
        val files = image.walkTopDown().filter { it.isFile }.map { file ->
            val path = file.relativeTo(image).invariantSeparatorsPath
            ManifestFile(path, file.length(), UpdatePlanner.sha256(file), platform == "linux-x64" && file.canExecute())
        }.sortedBy { it.path }.toList()
        return UpdateManifest(version, platform, "Lucense-$version-$platform.zip", prefix, files)
    }

    /**
     * Zips [image] the way the release does: each file its own entry under
     * [prefix], plus folder entries (which readers must skip). [stored] keeps
     * the named paths uncompressed, for tests that change their bytes.
     */
    fun zipOf(image: File, target: File, prefix: String = "Lucense/", stored: Set<String> = emptySet()): File {
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(prefix))
            zip.closeEntry()
            image.walkTopDown().filter { it != image }.sortedBy { it.relativeTo(image).invariantSeparatorsPath }.forEach { file ->
                val path = file.relativeTo(image).invariantSeparatorsPath
                if (file.isDirectory) {
                    zip.putNextEntry(ZipEntry("$prefix$path/"))
                    zip.closeEntry()
                    return@forEach
                }
                val bytes = file.readBytes()
                val entry = ZipEntry(prefix + path)
                if (path in stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = bytes.size.toLong()
                    entry.crc = CRC32().also { it.update(bytes) }.value
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return target
    }

    fun write(file: File, bytes: ByteArray, exec: Boolean = false): File {
        file.parentFile.mkdirs()
        file.writeBytes(bytes)
        file.setExecutable(exec, false)
        return file
    }

    fun random(size: Int, seed: Int): ByteArray = Random(seed).nextBytes(size)

    /** Bytes that compress well, like most of a jar's. */
    fun text(size: Int, seed: Int): ByteArray {
        val words = listOf("lucense", "music", "player", "album", "artist", "kotlin", "compose", "update", "$seed")
        val random = Random(seed)
        val out = StringBuilder()
        while (out.length < size) out.append(words[random.nextInt(words.size)]).append(' ')
        return out.substring(0, size).toByteArray()
    }

    /**
     * A Linux app image of [version]: a launcher that notes which version it
     * started into [launchLog], the launcher's settings, the app's jars (named
     * by version, as the real ones carry a content hash), a jar that never
     * changes, runtime files, executables, and the class archive.
     */
    fun linuxImage(root: File, version: String, launchLog: File, extraFolder: Boolean = false): File {
        root.mkdirs()
        write(
            File(root, "bin/Lucense"),
            (
                "#!/bin/sh\n" +
                    "here=\$(dirname \"\$0\")\n" +
                    "v=\$(sed -n 's/^java-options=-Dlucense.version=//p' \"\$here/../lib/app/Lucense.cfg\")\n" +
                    "printf 'started %s %s\\n' \"\$v\" \"\$*\" >> '${launchLog.absolutePath}'\n"
                ).toByteArray(),
            exec = true
        )
        val v = version.replace('.', '_')
        write(
            File(root, "lib/app/Lucense.cfg"),
            "[Application]\napp.classpath=\$APPDIR/desktop-$v.jar\napp.classpath=\$APPDIR/shared-$v.jar\n[JavaOptions]\njava-options=-Dlucense.version=$version\n".toByteArray()
        )
        write(File(root, "lib/app/desktop-$v.jar"), text(300_000, version.hashCode()))
        write(File(root, "lib/app/shared-$v.jar"), text(60_000, version.hashCode() + 1))
        write(File(root, "lib/app/common-1.0.jar"), text(200_000, 7))
        write(File(root, "lib/app/resources/ffmpeg"), random(150_000, 8), exec = true)
        write(File(root, "lib/runtime/bin/java"), random(20_000, 9), exec = true)
        write(File(root, "lib/runtime/lib/modules"), random(2_000_000, 10))
        write(File(root, "lib/runtime/lib/server/libjvm.so"), random(400_000, 11))
        write(File(root, "lib/runtime/lib/server/classes.jsa"), random(500_000, version.hashCode() + 2))
        if (extraFolder) write(File(root, "lib/app/resources/new-folder/extra.dat"), text(5_000, 12))
        return root
    }

    /** Every file under [root] with its SHA-256 and exec bit: to compare an install before and after. */
    fun snapshot(root: File): Map<String, String> = root.walkTopDown().filter { it.isFile }.associate { file ->
        file.relativeTo(root).invariantSeparatorsPath to UpdatePlanner.sha256(file) + if (file.canExecute()) " x" else ""
    }

    fun policy(): UrlPolicy = UrlPolicy(trustedHosts = setOf("127.0.0.1"), plainHttpHosts = setOf("127.0.0.1"))

    fun http(): UpdateHttp = UpdateHttp(policy()).apply { backoffMs = listOf(10, 20, 40) }

    /** A process id that is certainly not running: one that has just ended. */
    fun deadPid(): Long {
        val process = ProcessBuilder("true").redirectInput(ProcessBuilder.Redirect.from(File("/dev/null"))).start()
        process.waitFor(30, TimeUnit.SECONDS)
        return process.pid()
    }

    /** Waits (bounded) for [condition]. */
    suspend fun until(timeoutMs: Long = 30_000, condition: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!condition()) delay(25)
        }
    }

    fun tempDir(name: String): File = Files.createTempDirectory("lucense-$name-").toFile()

    /** Removes [dir] even where a test made folders read-only. */
    fun cleanUp(dir: File) {
        dir.walkTopDown().filter { it.isDirectory }.forEach { it.setWritable(true, true) }
        dir.deleteRecursively()
    }
}

/**
 * A local web server for the updater's tests: serves byte arrays with or
 * without Range support, redirects like GitHub's asset URLs do, can fail
 * requests on purpose, and counts what it is asked for and what it sends.
 */
class TestServer : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val port: Int get() = server.address.port
    val base: String get() = "http://127.0.0.1:$port"

    val files = ConcurrentHashMap<String, ByteArray>()
    val redirects = ConcurrentHashMap<String, String>()

    @Volatile var honourRanges = true

    /** Answer this many range requests with a 503 before serving again. */
    val failRanges = AtomicInteger(0)

    /** Send part of each answer and then hang up, this many times. */
    val cutShort = AtomicInteger(0)

    /** Wait this long between 16 KB pieces of a body. */
    @Volatile var slowMs = 0L

    /** "path range" for each request, in order. */
    val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** The sum of the lengths of every range asked for. */
    val rangeBytesAsked = AtomicLong()

    /** Body bytes written (or attempted) by the server. */
    val bytesSent = AtomicLong()

    init {
        server.executor = Executors.newCachedThreadPool { r -> Thread(r, "test-server").apply { isDaemon = true } }
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
    }

    fun url(path: String) = base + path

    fun put(path: String, bytes: ByteArray): String {
        files[path] = bytes
        return url(path)
    }

    private fun handle(exchange: HttpExchange) {
        try {
            val path = exchange.requestURI.path
            val range = exchange.requestHeaders.getFirst("Range")
            requests += "$path ${range ?: "-"}"
            redirects[path]?.let { location ->
                exchange.responseHeaders.add("Location", location)
                exchange.sendResponseHeaders(302, -1)
                return
            }
            val body = files[path]
            if (body == null) {
                exchange.sendResponseHeaders(404, -1)
                return
            }
            val match = range?.let { Regex("bytes=(\\d+)-(\\d+)").matchEntire(it) }
            if (match != null && honourRanges) {
                if (failRanges.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) {
                    exchange.sendResponseHeaders(503, -1)
                    return
                }
                val from = match.groupValues[1].toLong()
                val to = minOf(match.groupValues[2].toLong(), body.size - 1L)
                if (from >= body.size) {
                    exchange.responseHeaders.add("Content-Range", "bytes */${body.size}")
                    exchange.sendResponseHeaders(416, -1)
                    return
                }
                rangeBytesAsked.addAndGet(to - from + 1)
                exchange.responseHeaders.add("Content-Range", "bytes $from-$to/${body.size}")
                exchange.sendResponseHeaders(206, to - from + 1)
                send(exchange, body, from.toInt(), (to - from + 1).toInt())
            } else {
                exchange.sendResponseHeaders(200, body.size.toLong())
                send(exchange, body, 0, body.size)
            }
        } catch (_: IOException) {
            // The client hung up: expected when it refuses a whole-file answer.
        } finally {
            exchange.close()
        }
    }

    private fun send(exchange: HttpExchange, body: ByteArray, from: Int, length: Int) {
        val out = exchange.responseBody
        val cut = cutShort.getAndUpdate { if (it > 0) it - 1 else 0 } > 0
        val limit = if (cut) length / 2 else length
        var at = 0
        while (at < limit) {
            val n = minOf(16 * 1024, limit - at)
            out.write(body, from + at, n)
            bytesSent.addAndGet(n.toLong())
            at += n
            if (slowMs > 0) {
                out.flush()
                Thread.sleep(slowMs)
            }
        }
        if (cut) {
            out.flush()
            // Ends the exchange without the rest of the body: the client sees the stream end early.
            throw IOException("cut short on purpose")
        }
        out.close()
    }

    override fun close() = server.stop(0)
}
