package com.exo.musicplayer.data.net

import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/** One-line GET helper shared by the metadata and lyrics providers. */
object Http {

    private const val TAG = "Http"
    const val USER_AGENT = "Lucense/2.0 (Android music player)"

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

    private fun HttpURLConnection.answered(): Boolean {
        val code = responseCode
        if (code >= 500 || code == 429 || code == 408) noteTrouble()
        return code in 200..299
    }

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
            if (connection.answered()) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        } finally {
            connection.disconnect()
        }
    } catch (t: Throwable) {
        if (t is java.io.IOException) noteTrouble()
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
            if (connection.answered()) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        } finally {
            connection.disconnect()
        }
    } catch (t: Throwable) {
        if (t is java.io.IOException) noteTrouble()
        Log.warn(TAG, "POST failed: $url", t)
        null
    }
}
