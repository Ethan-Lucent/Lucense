package com.exo.musicplayer.data.net

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.asContextElement

/**
 * What the requests made under [Http.counting] came to.
 *
 * Every provider turns a failed request into "nothing found", so on their own a
 * song with no lyrics and a song looked up while offline look the same. This
 * tells them apart: a service that was [reached] answered, one that [failed]
 * could not be reached, timed out, or said "try later" (429, 5xx).
 */
class HttpTally {
    private val reachedCount = AtomicInteger()
    private val failedCount = AtomicInteger()
    private val slowDown = AtomicLong(-1L)
    private val open = ConcurrentHashMap.newKeySet<HttpURLConnection>()

    @Volatile
    private var abandoned = false

    val reached: Int get() = reachedCount.get()
    val failed: Int get() = failedCount.get()

    /**
     * How long a service that answered 429 (or 503 with Retry-After) asked to be
     * left alone, in ms; [Http.DEFAULT_SLOW_DOWN_MS] when it didn't say. -1 when none did.
     */
    val slowDownMs: Long get() = slowDown.get()

    internal fun answered(code: Int) {
        if (Http.isAnswer(code)) reachedCount.incrementAndGet() else failedCount.incrementAndGet()
    }

    internal fun unreachable() {
        failedCount.incrementAndGet()
    }

    internal fun throttled(ms: Long) {
        slowDown.accumulateAndGet(ms) { a, b -> maxOf(a, b) }
    }

    internal val isAbandoned: Boolean get() = abandoned

    internal fun opened(connection: HttpURLConnection) {
        open += connection
        // Abandoned while this was being opened: close it straight away.
        if (abandoned) closeQuietly(connection)
    }

    internal fun closed(connection: HttpURLConnection) {
        open -= connection
    }

    /**
     * Nobody wants these requests' answers any more (the lookup was stopped, or a
     * service ahead of this one already had the lyrics). Their sockets are closed,
     * which ends a blocking read at once, and no further request starts under this
     * tally. Failures from here on are not counted as network trouble.
     */
    fun abandon() {
        abandoned = true
        open.toList().forEach(::closeQuietly)
    }

    private fun closeQuietly(connection: HttpURLConnection) {
        runCatching { connection.disconnect() }
    }
}

/**
 * One-line GET helper shared by the metadata and lyrics providers.
 *
 * Connections are kept alive: a body that has been read to the end and closed
 * hands its socket back to the JDK's keep-alive cache, so the next request to
 * the same service skips the TCP connect, the TLS handshake and (behind
 * v2rayN and the like) the SOCKS handshake. Measured through a SOCKS5 proxy
 * that is most of the time a request takes. A connection that failed, timed
 * out or answered "in trouble" (5xx, 429, 408) is closed rather than reused.
 */
object Http {

    private const val TAG = "Http"
    const val USER_AGENT = "Lucense/2.0 (Android music player)"

    /**
     * How long to wait for a connection, and then for the answer.
     *
     * Connecting is short on purpose: a host that is down or unreachable through
     * the proxy should cost a few seconds, not the twelve it used to. Behind a
     * SOCKS proxy the connect time includes the proxy reaching the site.
     * Reading stays generous, because a slow answer is still an answer.
     */
    data class Limits(val connectMs: Int = CONNECT_TIMEOUT_MS, val readMs: Int = READ_TIMEOUT_MS)

    const val CONNECT_TIMEOUT_MS = 5_000
    const val READ_TIMEOUT_MS = 18_000

    /** What a 429 without Retry-After is taken to mean. */
    const val DEFAULT_SLOW_DOWN_MS = 10_000L

    /** The longest a Retry-After is honoured for, so one odd header can't park a service for an hour. */
    private const val MAX_SLOW_DOWN_MS = 120_000L

    /** Error bodies up to this size are read off so the connection can be reused; bigger ones close it. */
    private const val MAX_DRAIN_BYTES = 64 * 1024

    init {
        keepConnections()
    }

    /**
     * The JDK keeps at most `http.maxConnections` idle connections per site (5
     * unless told otherwise) and reads the property once, when the first
     * connection is made. The Lyrics tool has more songs than that in flight,
     * so more are kept; the app's startup sets this before anything goes online,
     * and this is the fallback when Http happens to be first.
     */
    fun keepConnections() {
        if (System.getProperty("http.keepAlive") == null) System.setProperty("http.keepAlive", "true")
        if (System.getProperty("http.maxConnections") == null) System.setProperty("http.maxConnections", "16")
    }

    private val trouble = AtomicInteger()

    /**
     * How many requests have failed for want of an answer - no connection, a
     * timeout, a server error or "slow down" - since the start.
     *
     * Every caller here turns a failure into null, which looks exactly like
     * "nothing found". Something that must tell the two apart (a cover lookup
     * that may only call a song "has no cover" when the services really
     * answered) reads this before and after: if it went up, a miss is not
     * to be trusted.
     */
    val troubleCount: Int get() = trouble.get()

    /** Records one request that got no usable answer from the network. */
    fun noteTrouble() {
        trouble.incrementAndGet()
    }

    private val tally = ThreadLocal<HttpTally?>()

    /**
     * A coroutine context under which every request counts into [into], across
     * the withContext(Dispatchers.IO) the providers do.
     */
    fun counting(into: HttpTally): CoroutineContext.Element = tally.asContextElement(into)

    /** For clients with a connection of their own: the service answered with [code]. */
    internal fun noteAnswer(code: Int) = tally.get()?.answered(code)

    /** For clients with a connection of their own: the service couldn't be reached. */
    internal fun noteUnreachable() = tally.get()?.unreachable()

    /**
     * Whether a status code is the service's answer about the song, rather than
     * the service not being able to answer right now: a 404 or 403 will be the
     * same tomorrow, a 429, 408, 407 (the proxy) or 5xx may not.
     */
    fun isAnswer(code: Int): Boolean = code in 200..499 && code != 407 && code != 408 && code != 429

    /** A status that says the service is in trouble: its connection is not worth keeping. */
    private fun inTrouble(code: Int) = code >= 500 || code == 429 || code == 408 || code == 407

    /** What one request came back with: the status, and the body when it was a success. */
    internal class Reply(val code: Int, val body: String?)

    /**
     * One request, counted into the current tally, on a connection that goes back
     * to the keep-alive cache when it ended well. Throws when there was no answer
     * (no connection, a timeout), having closed the connection.
     */
    internal fun exchange(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
        proxy: Proxy?,
        userAgent: String,
        limits: Limits
    ): Reply {
        val counted = tally.get()
        // A lookup nobody is waiting for any more starts nothing new.
        if (counted?.isAbandoned == true) throw IOException("abandoned")
        val opened = if (proxy != null) URL(url).openConnection(proxy) else URL(url).openConnection()
        val connection = opened as HttpURLConnection
        counted?.opened(connection)
        try {
            connection.requestMethod = method
            connection.connectTimeout = limits.connectMs
            connection.readTimeout = limits.readMs
            connection.setRequestProperty("User-Agent", userAgent)
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            counted?.answered(code)
            if (code == 429 || (code == 503 && connection.getHeaderField("Retry-After") != null)) {
                counted?.throttled(retryAfterMs(connection.getHeaderField("Retry-After")))
            }
            return when {
                // Read to the end and closed, not disconnected: closing is what returns the socket to the cache.
                code in 200..299 -> Reply(code, connection.inputStream.bufferedReader().use { it.readText() })
                inTrouble(code) -> {
                    connection.disconnect()
                    Reply(code, null)
                }
                else -> {
                    // A plain "no" (404, 403): read it off so the connection can serve the next request.
                    if (!drain(connection.errorStream)) connection.disconnect()
                    Reply(code, null)
                }
            }
        } catch (failure: Throwable) {
            // Timed out or broken: this socket is never handed to anyone else.
            runCatching { connection.disconnect() }
            throw failure
        } finally {
            counted?.closed(connection)
        }
    }

    /** Reads [stream] to its end and closes it; false when it was too long to be worth it or broke. */
    private fun drain(stream: InputStream?): Boolean {
        if (stream == null) return true
        return try {
            stream.use {
                val buffer = ByteArray(8 * 1024)
                var total = 0
                var n = it.read(buffer)
                while (n >= 0 && total <= MAX_DRAIN_BYTES) {
                    total += n
                    n = it.read(buffer)
                }
                n < 0
            }
        } catch (_: IOException) {
            false
        }
    }

    /** Retry-After as seconds or as an HTTP date, in ms from now; the default when absent or unreadable. */
    fun retryAfterMs(header: String?, nowMs: Long = System.currentTimeMillis()): Long {
        val text = header?.trim().orEmpty()
        if (text.isEmpty()) return DEFAULT_SLOW_DOWN_MS
        text.toLongOrNull()?.let { return (it * 1000).coerceIn(0, MAX_SLOW_DOWN_MS) }
        return runCatching {
            val at = ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            (at - nowMs).coerceIn(0, MAX_SLOW_DOWN_MS)
        }.getOrDefault(DEFAULT_SLOW_DOWN_MS)
    }

    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        /** Null goes the usual way; a proxy sends just this request through it. */
        proxy: Proxy? = null,
        limits: Limits = Limits()
    ): String? = request(url, "GET", headers, null, proxy, limits)

    /** POSTs [body] and returns the answer, or null on anything but success. */
    fun post(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        proxy: Proxy? = null,
        limits: Limits = Limits()
    ): String? = request(url, "POST", headers, body, proxy, limits)

    private fun request(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
        proxy: Proxy?,
        limits: Limits
    ): String? = try {
        val reply = exchange(url, method, headers, body, proxy, USER_AGENT, limits)
        if (inTrouble(reply.code) && reply.code != 407) noteTrouble()
        reply.body
    } catch (t: Throwable) {
        val counted = tally.get()
        // A request cut off because nobody wants it any more is no sign of trouble.
        if (counted?.isAbandoned != true) {
            if (t is IOException) noteTrouble()
            counted?.unreachable()
            Log.warn(TAG, "$method failed: $url", t)
        }
        null
    }
}
