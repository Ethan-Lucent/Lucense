package com.exo.musicplayer.desktop

import com.exo.musicplayer.data.lyrics.GeniusLyricsProvider
import com.exo.musicplayer.data.lyrics.LrcLibProvider
import com.exo.musicplayer.data.lyrics.LyricsFetch
import com.exo.musicplayer.data.lyrics.LyricsOvhProvider
import com.exo.musicplayer.data.lyrics.LyricsProvider
import com.exo.musicplayer.data.lyrics.LyricsProviderChain
import com.exo.musicplayer.data.lyrics.NeteaseLyricsProvider
import com.exo.musicplayer.data.net.Http
import com.exo.musicplayer.desktop.data.NetworkProxy
import com.exo.musicplayer.desktop.data.ProxyConfig
import com.exo.musicplayer.desktop.data.ProxyMode
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * A real-network benchmark of the lyrics lookup, skipped unless
 * LUCENSE_LYRICS_BENCH=1. It reads only the FILE NAMES of the library folder
 * ("0001 Artist - Title.ext") and touches no app data.
 *
 * Polite by construction: [SONGS] songs, 3 in flight, a pause between one
 * worker's songs, and a hard cap on requests per host (counted at the proxy
 * selector, which every HttpURLConnection request asks).
 *
 * LUCENSE_LYRICS_BENCH_TAG names the run (before/after), _SONGS and _RAW make
 * it shorter; results are appended to /tmp/lyrics-bench-raw.txt. Run it with
 * --no-daemon: a Gradle daemon keeps the environment it was started with.
 */
@Timeout(value = 20, unit = TimeUnit.MINUTES)
class LyricsBenchTest {

    private val perHost = ConcurrentHashMap<String, AtomicInteger>()

    @Test
    fun bench() = runBlocking {
        assumeTrue(System.getenv("LUCENSE_LYRICS_BENCH") == "1", "benchmark only on request")
        val tag = System.getenv("LUCENSE_LYRICS_BENCH_TAG") ?: "run"
        val songs = System.getenv("LUCENSE_LYRICS_BENCH_SONGS")?.toIntOrNull() ?: SONGS
        val out = StringBuilder()
        val viaProxy = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", 10808), 500) } }.isSuccess
        NetworkProxy.apply(if (viaProxy) ProxyConfig(ProxyMode.SOCKS5, "127.0.0.1", 10808) else ProxyConfig(ProxyMode.DIRECT))
        val routed = ProxySelector.getDefault()
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> {
                val host = uri.host ?: "?"
                val n = perHost.getOrPut(host) { AtomicInteger() }.incrementAndGet()
                if (n > HOST_CAP) throw IOException("benchmark cap reached for $host")
                return routed.select(uri)
            }
            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
        })
        out.appendLine("=== $tag  ${java.time.LocalDateTime.now()}  route=${if (viaProxy) "SOCKS5 127.0.0.1:10808" else "direct"}")

        // 1. Raw requests per service host, one after another: the first is cold, the rest
        //    show whether a connection is reused.
        val names = libraryNames().let { all -> all.filterIndexed { i, _ -> i % (all.size / 40).coerceAtLeast(1) == 0 } }
        val hosts = listOf<Pair<String, (Pair<String, String>) -> String>>(
            "LRCLIB" to { (a, t) -> "https://lrclib.net/api/search?q=${enc("$a $t")}" },
            "NetEase" to { (a, t) -> "https://music.163.com/api/search/get?s=${enc("$a $t")}&type=1&limit=1" },
            "lyrics.ovh" to { (a, t) -> "https://api.lyrics.ovh/suggest/${enc("$a $t")}" },
            "Genius" to { (a, t) -> "https://genius.com/api/search/song?q=${enc("$a $t")}" },
            // LRCLIB's exact match missing: a 404, the commonest answer in a lookup.
            "LRCLIB-404" to { (a, t) -> "https://lrclib.net/api/get?track_name=${enc("zz $t zz")}&artist_name=${enc(a)}" }
        )
        val raw = System.getenv("LUCENSE_LYRICS_BENCH_RAW")?.toIntOrNull() ?: RAW_PER_HOST
        withContext(Dispatchers.IO) {
            for ((label, url) in hosts) {
                val times = names.take(raw).map { name ->
                    val started = System.nanoTime()
                    val ok = Http.get(url(name), mapOf("Referer" to "https://music.163.com/")) != null
                    val ms = (System.nanoTime() - started) / 1_000_000
                    Thread.sleep(300)
                    if (ok) ms else -ms
                }
                out.appendLine("raw $label ms (negative = failed): ${times.joinToString()}")
                out.appendLine(
                    "raw $label cold=${times.first()}  warm median=${median(times.drop(1).map { kotlin.math.abs(it) })}"
                )
            }
        }

        // 2. Whole songs through the real chain, 3 at a time.
        val calls = Collections.synchronizedList(mutableListOf<Triple<String, Long, String>>())
        fun timed(inner: LyricsProvider) = object : LyricsProvider {
            override val label = inner.label
            override suspend fun fetch(title: String, artist: String?, album: String?, durationMs: Long): LyricsFetch {
                val started = System.nanoTime()
                var kind = "threw"
                try {
                    return inner.fetch(title, artist, album, durationMs).also { kind = it.javaClass.simpleName }
                } catch (c: CancellationException) {
                    kind = "cancelled"; throw c
                } finally {
                    calls += Triple(label, (System.nanoTime() - started) / 1_000_000, kind)
                }
            }
        }
        val chain = LyricsProviderChain(
            listOf(LrcLibProvider(), NeteaseLyricsProvider(), LyricsOvhProvider(), GeniusLyricsProvider()).map(::timed)
        )
        // The same songs every run (a fixed stride of SONGS); a shorter run takes the first of them.
        val picked = names.drop(RAW_PER_HOST).let { rest -> rest.filterIndexed { i, _ -> i % (rest.size / SONGS).coerceAtLeast(1) == 0 } }
            .take(SONGS).take(songs)
        val results = Collections.synchronizedList(mutableListOf<String>())
        val perSong = Collections.synchronizedList(mutableListOf<Long>())
        val work = Channel<Pair<String, String>>(Channel.UNLIMITED)
        picked.forEach { work.trySend(it) }
        work.close()
        val started = System.nanoTime()
        coroutineScope {
            repeat(3) {
                launch {
                    for ((artist, title) in work) {
                        val t0 = System.nanoTime()
                        val (result, source) = chain.fetch(title, artist, null, 0)
                        val ms = (System.nanoTime() - t0) / 1_000_000
                        perSong += ms
                        val what = when (result) {
                            is LyricsFetch.Found -> "Found(${source}, ${if (result.synced != null) "timed" else "plain"}, ${(result.plain ?: result.synced)?.length})"
                            is LyricsFetch.Error -> "Error(${result.message.take(70)})"
                            else -> result.toString()
                        }
                        results += "song $ms ms  $artist - $title  ->  $what"
                        delay(400)
                    }
                }
            }
        }
        val total = (System.nanoTime() - started) / 1_000_000
        results.sorted().forEach { out.appendLine(it) }
        for (label in chain.labels) {
            val mine = calls.filter { it.first == label }
            out.appendLine(
                "provider $label calls=${mine.size} median=${median(mine.map { it.second })} p90=${p90(mine.map { it.second })} " +
                    mine.groupingBy { it.third }.eachCount()
            )
        }
        out.appendLine("songs=${picked.size} per-song median=${median(perSong)} p90=${p90(perSong)} max=${perSong.maxOrNull()} end-to-end=$total ms")
        out.appendLine("requests per host: ${perHost.mapValues { it.value.get() }}")
        File("/tmp/lyrics-bench-raw.txt").appendText(out.toString() + "\n")
        println(out)
    }

    /** "Artist - Title" from "0001 Artist - Title.ext"; only names are read. */
    private fun libraryNames(): List<Pair<String, String>> {
        val dir = File(System.getProperty("user.home"), "Music/Lucense Library 2026-11-23")
        return (dir.list()?.sorted() ?: emptyList()).mapNotNull { name ->
            val bare = name.substringBeforeLast('.').replaceFirst(Regex("""^\d+\s+"""), "")
            val parts = bare.split(" - ", limit = 2)
            if (parts.size == 2 && parts.all { it.isNotBlank() }) parts[0].trim() to parts[1].trim() else null
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun median(v: List<Long>) = v.sorted().let { if (it.isEmpty()) 0 else it[it.size / 2] }
    private fun p90(v: List<Long>) = v.sorted().let { if (it.isEmpty()) 0 else it[((it.size - 1) * 9) / 10] }

    private companion object {
        const val SONGS = 15
        const val RAW_PER_HOST = 5
        const val HOST_CAP = 90
    }
}
