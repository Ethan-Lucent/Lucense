package com.exo.musicplayer.data.net

import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
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
    val reached: Int get() = reachedCount.get()
    val failed: Int get() = failedCount.get()

    internal fun answered(code: Int) {
        if (Http.isAnswer(code)) reachedCount.incrementAndGet() else failedCount.incrementAndGet()
    }

    internal fun unreachable() {
        failedCount.incrementAndGet()
    }
}

/** One-line GET helper shared by the metadata and lyrics providers. */
object Http {

    private const val TAG = "Http"
    const val USER_AGENT = "Lucense/2.0 (Android music player)"

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

    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        /** Null goes the usual way; a proxy sends just this request through it. */
        proxy: Proxy? = null
    ): String? = try {
        val opened = if (proxy != null) URL(url).openConnection(proxy) else URL(url).openConnection()
        val connection = (opened as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 18_000
            setRequestProperty("User-Agent", USER_AGENT)
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            val code = connection.responseCode
            tally.get()?.answered(code)
            if (code in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        } finally {
            connection.disconnect()
        }
    } catch (t: Throwable) {
        tally.get()?.unreachable()
        Log.warn(TAG, "GET failed: $url", t)
        null
    }

    /** POSTs [body] and returns the answer, or null on anything but success. */
    fun post(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        proxy: Proxy? = null
    ): String? = try {
        val opened = if (proxy != null) URL(url).openConnection(proxy) else URL(url).openConnection()
        val connection = (opened as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 12_000
            readTimeout = 18_000
            setRequestProperty("User-Agent", USER_AGENT)
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            tally.get()?.answered(code)
            if (code in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        } finally {
            connection.disconnect()
        }
    } catch (t: Throwable) {
        tally.get()?.unreachable()
        Log.warn(TAG, "POST failed: $url", t)
        null
    }
}
