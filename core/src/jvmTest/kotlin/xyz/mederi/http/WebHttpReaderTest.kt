package xyz.mederi.http

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 真正走独立 HTTP 客户端；只连接本机测试 fixture，不依赖 shell 或公网。 */
class WebHttpReaderTest {
    private suspend fun withServer(handler: (HttpExchange) -> Unit, block: suspend (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        val executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "mederi-test-web-server").apply { isDaemon = true }
        }
        server.executor = executor
        server.createContext("/") { exchange ->
            try { handler(exchange) }
            catch (_: IOException) { /* 超时/字节截断时客户端可主动关闭连接。 */ }
            catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            finally { exchange.close() }
        }
        server.start()
        try { block("http://127.0.0.1:${server.address.port}") }
        finally {
            server.stop(0)
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS), "test HTTP fixture did not stop")
        }
    }

    private fun HttpExchange.respond(text: String, status: Int = 200) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.write(bytes)
    }

    @Test
    fun getPreservesQuotedPathAndQueryAsDataAndSendsUserAgent() = runBlocking {
        data class Received(val method: String, val path: String, val query: String?, val userAgent: String?)
        val received = AtomicReference<Received>()
        withServer(handler = { exchange ->
            received.set(Received(exchange.requestMethod, exchange.requestURI.rawPath,
                exchange.requestURI.rawQuery, exchange.requestHeaders.getFirst("User-Agent")))
            exchange.respond("真实响应 'quoted' ✓")
        }) { origin ->
            val body = WebHttpReader.get("$origin/quote/'literal?q=%24%28echo%20bad%29", 5, "Mederi/TestAgent")
            assertEquals("真实响应 'quoted' ✓", body)
            assertEquals(Received("GET", "/quote/'literal", "q=%24%28echo%20bad%29", "Mederi/TestAgent"), received.get())
        }
    }

    @Test
    fun followsRelativeHttpRedirectsAndAcceptsExactlyFiveRedirects() = runBlocking {
        val requests = AtomicInteger()
        withServer(handler = { exchange ->
            requests.incrementAndGet()
            val remaining = exchange.requestURI.path.substringAfterLast('/').toInt()
            if (remaining == 0) exchange.respond("arrived") else {
                exchange.responseHeaders.set("Location", "./${remaining - 1}")
                exchange.sendResponseHeaders(302, -1)
            }
        }) { origin ->
            assertEquals("arrived", WebHttpReader.get("$origin/redirect/5", 5, "test"))
            assertEquals(6, requests.get(), "five redirects should issue six GETs")
        }
    }

    @Test
    fun sixthRedirectFailsBeforeFollowingItsTarget() = runBlocking {
        val requests = AtomicInteger()
        withServer(handler = { exchange ->
            requests.incrementAndGet()
            val remaining = exchange.requestURI.path.substringAfterLast('/').toInt()
            exchange.responseHeaders.set("Location", "/redirect/${remaining - 1}")
            exchange.sendResponseHeaders(307, -1)
        }) { origin ->
            val failure = assertFailsWith<IllegalArgumentException> {
                WebHttpReader.get("$origin/redirect/6", 5, "test")
            }
            assertEquals("Too many HTTP redirects", failure.message)
            assertEquals(6, requests.get(), "the seventh request must never be sent")
        }
    }

    @Test
    fun redirectsCannotSwitchToNonHttpSchemes() = runBlocking {
        val requests = AtomicInteger()
        withServer(handler = { exchange ->
            requests.incrementAndGet()
            exchange.responseHeaders.set("Location", "file:///must-not-be-opened-by-web-reader")
            exchange.sendResponseHeaders(302, -1)
        }) { origin ->
            val failure = assertFailsWith<IllegalArgumentException> { WebHttpReader.get("$origin/redirect", 5, "test") }
            assertEquals("Only http/https URLs are allowed", failure.message)
            assertEquals(1, requests.get())
        }
    }

    @Test
    fun timeoutBoundsSlowResponseBodyAndDoesNotReturnPartialSuccess() = runBlocking {
        withServer(handler = { exchange ->
            exchange.sendResponseHeaders(200, 2)
            exchange.responseBody.write('O'.code)
            exchange.responseBody.flush()
            Thread.sleep(1800)
            exchange.responseBody.write('K'.code)
        }) { origin ->
            val started = System.nanoTime()
            assertFailsWith<SocketTimeoutException> { WebHttpReader.get("$origin/slow", 1, "test") }
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue(elapsed < 4000, "one-second request exceeded its bounded deadline: ${elapsed}ms")
        }
    }

    @Test
    fun responseStopsAtTwoMebibytesInsteadOfReadingUnboundedBody() = runBlocking {
        val limit = 2 * 1024 * 1024
        val payload = "a".repeat(limit) + "must-not-be-read".repeat(1000)
        withServer(handler = { it.respond(payload) }) { origin ->
            val body = WebHttpReader.get("$origin/large", 5, "test")
            assertEquals(limit, body.length)
            assertTrue(body.all { it == 'a' })
        }
    }

    @Test
    fun rejectsHttpErrorsMissingLocationAndInvalidTimeouts() = runBlocking {
        withServer(handler = { exchange ->
            if (exchange.requestURI.path == "/missing-location") exchange.sendResponseHeaders(303, -1)
            else exchange.respond("not successful", 503)
        }) { origin ->
            assertEquals("HTTP 503", assertFailsWith<IllegalArgumentException> {
                WebHttpReader.get("$origin/error", 5, "test")
            }.message)
            assertEquals("Redirect has no Location", assertFailsWith<IllegalStateException> {
                WebHttpReader.get("$origin/missing-location", 5, "test")
            }.message)
            for (timeout in listOf(0, -1, 301)) assertFailsWith<IllegalArgumentException> {
                WebHttpReader.get("$origin/unused", timeout, "test")
            }
        }
    }
}
