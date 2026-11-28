package com.exo.musicplayer.desktop.update

import com.exo.musicplayer.data.net.Http
import com.exo.musicplayer.data.update.Updates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import kotlin.coroutines.coroutineContext

/** A failure worth telling the user about as it is. */
open class UpdateException(message: String) : IOException(message)

/** The server sent the whole file where a part was asked for; nothing more is downloaded. */
class RangesUnsupportedException : UpdateException("The download server doesn't support partial downloads (ranges unsupported).")

/**
 * Where update traffic may go.
 *
 * HTTPS only, every hop of every redirect included: a redirect to plain HTTP is
 * refused, not followed. The releases list and the assets must start on
 * [trustedHosts] (GitHub's API and github.com); redirects from there may lead
 * anywhere HTTPS, since GitHub hands assets out from storage hosts it changes
 * now and then. [plainHttpHosts] exists for the tests' local server only and is
 * empty in the app.
 */
data class UrlPolicy(
    val trustedHosts: Set<String> = setOf("api.github.com", "github.com"),
    val plainHttpHosts: Set<String> = emptySet(),
    /**
     * Where a download may start, beyond the host: this repository's API and
     * its release files only, so a release list that somehow named another
     * repository's (or another site's) files is not followed. Empty in tests.
     */
    val startPrefixes: List<String> = listOf(
        "https://api.github.com/repos/${Updates.REPOSITORY}/",
        "https://github.com/${Updates.REPOSITORY}/releases/download/"
    )
) {
    fun checkHop(url: String): URI {
        val uri = runCatching { URI(url) }.getOrNull() ?: throw UpdateException("Not a web address: ${url.take(120)}")
        val host = uri.host?.lowercase() ?: throw UpdateException("No host in ${url.take(120)}")
        when (uri.scheme?.lowercase()) {
            "https" -> Unit
            "http" -> if (host !in plainHttpHosts) throw UpdateException("Refusing an insecure (http) address: ${url.take(120)}")
            else -> throw UpdateException("Refusing ${url.take(120)}")
        }
        return uri
    }

    fun checkStart(url: String): URI {
        val uri = checkHop(url)
        if (uri.host.lowercase() !in trustedHosts) throw UpdateException("Refusing a download from ${uri.host}")
        if (startPrefixes.isNotEmpty() && startPrefixes.none { url.startsWith(it) }) {
            throw UpdateException("Refusing a download from outside Lucense's releases: ${url.take(120)}")
        }
        return uri
    }
}

/**
 * The few HTTP requests the updater makes, all through HttpURLConnection
 * opened without an explicit proxy, so they take the route NetworkProxy sets
 * for the whole JVM (SOCKS5 included) like every other request in the app.
 */
class UpdateHttp(private val policy: UrlPolicy = UrlPolicy()) {

    /** Milliseconds to wait before each retry of a failed range. */
    var backoffMs: List<Long> = listOf(500, 1500, 3000)

    /** GETs [url] as text, refusing anything over [limit] bytes; null for a 404. */
    suspend fun getText(url: String, limit: Int, accept: String? = null): String? {
        val connection = openFollowing(url, headers = listOfNotNull(accept?.let { "Accept" to it }))
        return closing(connection) {
            when (val code = connection.responseCode) {
                in 200..299 -> {
                    val length = connection.contentLengthLong
                    if (length > limit) throw UpdateException("The answer from ${connection.url.host} is too large.")
                    val bytes = connection.inputStream.use { readAtMost(it, limit) }
                    String(bytes, Charsets.UTF_8)
                }
                404 -> null
                else -> throw UpdateException("${connection.url.host} answered HTTP $code.")
            }
        }
    }

    private fun readAtMost(input: InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > limit) throw UpdateException("The answer is too large.")
        }
        return out.toByteArray()
    }

    /**
     * One file read by parts. The first request follows the redirects (a
     * GitHub asset URL answers 302 with a signed storage URL), re-sending the
     * Range header on each hop, and the address it ends on is reused for every
     * later part. Should that address stop working (signed URLs expire) it is
     * looked up again from the original once.
     */
    inner class Ranged(private val url: String) {
        private var resolved: String? = null

        /** The file's full length, known after the first part has been read. */
        var length: Long = -1
            private set

        /** Bytes received, over all parts: what the tests count. */
        var received: Long = 0
            private set

        /**
         * Reads bytes [start]..[endInclusive] and hands them to [block] as a
         * stream; a failed attempt is retried up to three times with a short
         * wait. Without [resumable] a retry starts the part over; with it,
         * [block] is called again with resumeFrom = how many bytes of the part
         * it already took, and the stream starts there, so a consumer that
         * streams (the inflater) carries on where it was. [block] must read
         * the stream to its end.
         */
        suspend fun <T> read(
            start: Long,
            endInclusive: Long,
            resumable: Boolean = false,
            block: suspend (input: InputStream, resumeFrom: Long) -> T
        ): T = readParts(start, endInclusive, resumable, block)

        /** A small part, whole, as bytes. */
        suspend fun bytes(start: Long, endInclusive: Long): ByteArray =
            read(start, endInclusive) { input, _ -> input.readBytes() }

        private suspend fun <T> readParts(
            start: Long,
            endInclusive: Long,
            resumable: Boolean,
            block: suspend (InputStream, Long) -> T
        ): T {
            var taken = 0L
            var failures = 0
            var lookedUpAgain = false
            var takenAtFailure = 0L
            while (true) {
                coroutineContext.ensureActive()
                val from = start + taken
                try {
                    val connection = openPart(from, endInclusive)
                    return closing(connection) {
                        val already = taken
                        val counting = CountingStream(connection.inputStream, endInclusive - from + 1) { received += it; taken += it }
                        coroutineScope {
                            // When the download is cancelled, closing the connection
                            // unblocks a read stuck waiting on the network.
                            val watcher = launch(Dispatchers.IO) {
                                try { awaitCancellation() } finally { connection.disconnect() }
                            }
                            try {
                                block(counting, already).also {
                                    if (counting.count != endInclusive - from + 1) {
                                        throw RetryableException("The download ended early.")
                                    }
                                }
                            } finally {
                                watcher.cancel()
                            }
                        }
                    }
                } catch (e: RangesUnsupportedException) {
                    throw e
                } catch (e: ExpiredException) {
                    if (lookedUpAgain) throw UpdateException("The download address stopped working (HTTP ${e.code}).")
                    lookedUpAgain = true
                    resolved = null
                } catch (e: IOException) {
                    coroutineContext.ensureActive()
                    if (e is UpdateException && e !is RetryableException) throw e
                    // Three tries for each stretch: an attempt that got somewhere starts the count again.
                    if (resumable && taken > takenAtFailure) failures = 0
                    if (!resumable) taken = 0
                    takenAtFailure = taken
                    if (failures >= backoffMs.size) throw UpdateException("The download kept failing: ${e.message}")
                    delay(backoffMs[failures])
                    failures++
                }
            }
        }

        private suspend fun openPart(from: Long, to: Long): HttpURLConnection {
            val range = "Range" to "bytes=$from-$to"
            val known = resolved
            val connection = if (known != null) open(known, listOf(range)) else openFollowing(url, listOf(range))
            val code = try {
                connection.responseCode
            } catch (e: IOException) {
                connection.disconnect()
                throw RetryableException("No answer: ${e.message}")
            }
            when {
                code == 206 -> Unit
                // A 200 is the whole file coming: hang up rather than download it.
                code == 200 -> { connection.disconnect(); throw RangesUnsupportedException() }
                known != null && (code == 401 || code == 403 || code == 404 || code == 410) -> {
                    connection.disconnect(); throw ExpiredException(code)
                }
                code == 408 || code == 429 || code >= 500 -> { connection.disconnect(); throw RetryableException("HTTP $code") }
                else -> { connection.disconnect(); throw UpdateException("The download server answered HTTP $code.") }
            }
            // "bytes 100-199/5000": the part asked for, of a file this long.
            val header = connection.getHeaderField("Content-Range").orEmpty()
            val match = CONTENT_RANGE.matchEntire(header.trim())
            if (match == null) { connection.disconnect(); throw UpdateException("The download server sent no proper Content-Range.") }
            val (a, b, total) = match.destructured
            if (a.toLong() != from || b.toLong() != to) {
                connection.disconnect(); throw UpdateException("The download server sent the wrong part ($header).")
            }
            if (total != "*") {
                val all = total.toLong()
                if (length >= 0 && all != length) { connection.disconnect(); throw UpdateException("The file changed while it was being downloaded.") }
                length = all
            }
            resolved = connection.url.toString()
            return connection
        }
    }

    private class ExpiredException(val code: Int) : IOException("HTTP $code")
    private class RetryableException(message: String) : UpdateException(message)

    /** Opens [url], following up to five redirects by hand so each hop is checked and keeps [headers]. */
    private fun openFollowing(url: String, headers: List<Pair<String, String>>): HttpURLConnection {
        policy.checkStart(url)
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            val connection = open(current, headers)
            val code = try {
                connection.responseCode
            } catch (e: IOException) {
                connection.disconnect()
                throw RetryableException("Couldn't reach ${URI(current).host}: ${e.message}")
            }
            if (code !in REDIRECTS) return connection
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            if (location.isNullOrBlank()) throw UpdateException("A redirect without an address.")
            current = runCatching { URI(current).resolve(location.trim()).toString() }
                .getOrElse { throw UpdateException("A redirect to an address that can't be read.") }
        }
        throw UpdateException("Too many redirects.")
    }

    private fun open(url: String, headers: List<Pair<String, String>>): HttpURLConnection {
        policy.checkHop(url)
        return (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 30_000
            useCaches = false
            setRequestProperty("User-Agent", Http.USER_AGENT)
            // Ranges must arrive as stored, not re-compressed by the server.
            setRequestProperty("Accept-Encoding", "identity")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
    }

    private inline fun <T> closing(connection: HttpURLConnection, block: () -> T): T =
        try { block() } finally { connection.disconnect() }

    /** Counts what passes, and never reads past [limit]: a server sending more than asked is cut off. */
    private class CountingStream(
        private val input: InputStream,
        private val limit: Long,
        private val onRead: (Int) -> Unit
    ) : InputStream() {
        var count = 0L
            private set

        override fun read(): Int {
            if (count >= limit) return -1
            val b = input.read()
            if (b >= 0) { count++; onRead(1) }
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (count >= limit) return -1
            val read = input.read(b, off, minOf(len.toLong(), limit - count).toInt())
            if (read > 0) { count += read; onRead(read) }
            return read
        }

        override fun close() = input.close()
    }

    companion object {
        private const val MAX_REDIRECTS = 5
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        private val CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)")
    }
}
