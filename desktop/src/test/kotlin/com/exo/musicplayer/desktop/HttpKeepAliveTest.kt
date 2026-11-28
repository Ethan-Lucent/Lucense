package com.exo.musicplayer.desktop

import com.exo.musicplayer.data.net.Http
import com.exo.musicplayer.data.net.HttpTally
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * Http against a server on this machine: connections are reused when they
 * ended well and dropped when they didn't, and the caps hold. No network.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class HttpKeepAliveTest {

    private lateinit var server: HttpServer
    private val ports = Collections.synchronizedSet(mutableSetOf<Int>())
    private val requests = AtomicInteger()
    private val pool = Executors.newFixedThreadPool(4)

    private fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16)
        server.createContext("/") { exchange ->
            requests.incrementAndGet()
            ports += exchange.remoteAddress.port
            val path = exchange.requestURI.path
            val (code, body) = when {
                path.startsWith("/404") -> 404 to "no such song"
                path.startsWith("/500") -> 500 to "broken"
                path.startsWith("/429") -> {
                    exchange.responseHeaders.add("Retry-After", "2")
                    429 to "slow down"
                }
                path.startsWith("/slow") -> {
                    Thread.sleep(1_500)
                    200 to "slow"
                }
                else -> 200 to "hello ${path.removePrefix("/")}"
            }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.executor = pool
        server.start()
    }

    @AfterEach
    fun stop() {
        server.stop(0)
        pool.shutdownNow()
    }

    private val direct = Proxy.NO_PROXY

    @Test
    fun requestsOneAfterAnotherShareOneConnection() {
        val bodies = (1..5).map { Http.get(url("/ok$it"), proxy = direct) }
        assertEquals((1..5).map { "hello ok$it" }, bodies)
        assertEquals(1, ports.size, "connections opened: $ports")
    }

    @Test
    fun aNotFoundAnswerKeepsTheConnection() {
        val bodies = listOf("/ok1", "/404a", "/ok2", "/404b", "/ok3").map { Http.get(url(it), proxy = direct) }
        assertEquals(listOf("hello ok1", null, "hello ok2", null, "hello ok3"), bodies)
        assertEquals(1, ports.size, "connections opened: $ports")
    }

    @Test
    fun aServerInTroubleGetsAFreshConnectionNextTime() {
        assertEquals("hello ok1", Http.get(url("/ok1"), proxy = direct))
        assertNull(Http.get(url("/500"), proxy = direct))
        assertEquals("hello ok2", Http.get(url("/ok2"), proxy = direct))
        assertEquals(2, ports.size, "the connection that said 500 was not reused")
    }

    @Test
    fun aTimedOutRequestDoesNotPoisonThePool() {
        assertEquals("hello ok1", Http.get(url("/ok1"), proxy = direct))
        val started = System.nanoTime()
        assertNull(Http.get(url("/slow"), proxy = direct, limits = Http.Limits(connectMs = 1_000, readMs = 300)))
        assertTrue(System.nanoTime() - started < 1_300_000_000L, "waited past the read cap")
        // The late answer to /slow must never be read as the answer to the next request.
        assertEquals("hello ok2", Http.get(url("/ok2"), proxy = direct))
        assertEquals("hello ok3", Http.get(url("/ok3"), proxy = direct))
        assertEquals(2, ports.size, "timed-out connection dropped, the fresh one reused: $ports")
    }

    @Test
    fun aServerThatNeverAnswersTimesOutAtTheReadCap() {
        ServerSocket(0, 4, InetAddress.getLoopbackAddress()).use { silent ->
            val held = mutableListOf<Socket>()
            val accepter = Thread { runCatching { while (true) held += silent.accept() } }.apply { isDaemon = true; start() }
            val started = System.nanoTime()
            assertNull(Http.get("http://127.0.0.1:${silent.localPort}/x", proxy = direct, limits = Http.Limits(1_000, 500)))
            val ms = (System.nanoTime() - started) / 1_000_000
            assertTrue(ms in 400..3_000, "took $ms ms")
            accepter.interrupt()
            held.forEach { runCatching { it.close() } }
        }
    }

    /** A listener whose queue is full drops new connections unanswered, as a dead host does. */
    @Test
    fun aHostThatDoesNotAcceptTimesOutAtTheConnectCap() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { full ->
            val fillers = mutableListOf<Socket>()
            var saturated = false
            repeat(16) {
                if (saturated) return@repeat
                val socket = Socket()
                try {
                    socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), full.localPort), 200)
                    fillers += socket
                } catch (_: SocketTimeoutException) {
                    socket.close()
                    saturated = true
                }
            }
            try {
                assumeTrue(saturated, "this system's listen queue never filled")
                val started = System.nanoTime()
                assertNull(Http.get("http://127.0.0.1:${full.localPort}/x", proxy = direct, limits = Http.Limits(700, 5_000)))
                val ms = (System.nanoTime() - started) / 1_000_000
                assertTrue(ms in 600..2_500, "took $ms ms")
            } finally {
                fillers.forEach { runCatching { it.close() } }
            }
        }
    }

    @Test
    fun theDefaultsAreAShortConnectAndALongRead() {
        assertEquals(Http.Limits(5_000, 18_000), Http.Limits())
    }

    @Test
    fun theTallyCountsAnswersAndFailuresAsBefore() = runBlocking {
        val tally = HttpTally()
        val troubleBefore = Http.troubleCount
        withContext(Dispatchers.IO + Http.counting(tally)) {
            Http.get(url("/ok"), proxy = direct)
            Http.get(url("/404"), proxy = direct)
            assertEquals(2, tally.reached)
            assertEquals(0, tally.failed)
            assertEquals(-1L, tally.slowDownMs)
            Http.get(url("/429"), proxy = direct)
            assertEquals(1, tally.failed)
            assertEquals(2_000L, tally.slowDownMs)
            Http.get(url("/500"), proxy = direct)
            // Port 1 refuses at once: unreachable.
            Http.get("http://127.0.0.1:1/x", proxy = direct)
            Http.post(url("/ok"), "body", proxy = direct)
        }
        assertEquals(3, tally.reached)
        assertEquals(3, tally.failed)
        assertTrue(Http.troubleCount - troubleBefore >= 3, "429, 500 and the refusal are trouble")
        // Outside the tally's context nothing is counted into it.
        Http.get(url("/ok"), proxy = direct)
        assertEquals(3, tally.reached)
    }

    @Test
    fun abandoningATallyClosesItsHungRequestAtOnce() = runBlocking {
        ServerSocket(0, 4, InetAddress.getLoopbackAddress()).use { silent ->
            val held = mutableListOf<Socket>()
            val accepter = Thread { runCatching { while (true) held += silent.accept() } }.apply { isDaemon = true; start() }
            val tally = HttpTally()
            val troubleBefore = Http.troubleCount
            val started = System.nanoTime()
            val request = async(Dispatchers.IO + Http.counting(tally)) {
                Http.get("http://127.0.0.1:${silent.localPort}/x", proxy = direct, limits = Http.Limits(1_000, 20_000))
            }
            delay(300)
            tally.abandon()
            assertNull(request.await())
            val ms = (System.nanoTime() - started) / 1_000_000
            assertTrue(ms < 3_000, "the read was not cut off: $ms ms")
            assertEquals(troubleBefore, Http.troubleCount, "an abandoned request is no network trouble")
            // And nothing new starts under it.
            val seen = requests.get()
            withContext(Dispatchers.IO + Http.counting(tally)) { assertNull(Http.get(url("/ok"), proxy = direct)) }
            assertEquals(seen, requests.get())
            accepter.interrupt()
            held.forEach { runCatching { it.close() } }
        }
    }
}
