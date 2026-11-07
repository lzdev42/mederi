package xyz.mederi.http

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.KoogHttpClientException
import ai.koog.http.client.get
import ai.koog.http.client.lines
import ai.koog.http.client.post
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.google.GoogleClientSettings
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.google.GoogleParams
import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAIResponsesParams
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import xyz.mederi.provider.infrastructure.koog.sanitize.MederiOpenAILLMClient
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 三种 LLM API 使用同一 HTTP 工厂；本地服务器覆盖非流式、lines 和 Google SSE。 */
class ProviderRetryMetadataTest {
    private suspend fun withServer(handler: (HttpExchange) -> Unit, block: suspend (KoogHttpClient, String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        val executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "mederi-retry-http-test").apply { isDaemon = true }
        }
        server.executor = executor
        server.createContext("/") { exchange ->
            try { handler(exchange) }
            catch (_: IOException) { /* 取消测试会主动断开连接。 */ }
            catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            finally { exchange.close() }
        }
        server.start()
        val client = MederiHttpClientFactory.create("retry-metadata-test", baseUrl = "http://127.0.0.1:${server.address.port}/")
        try { block(client, "http://127.0.0.1:${server.address.port}") }
        finally {
            client.close()
            server.stop(0)
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS), "test HTTP fixture did not stop")
        }
    }

    private fun HttpExchange.respond(body: String, status: Int = 200, contentType: String = "text/plain") {
        requestBody.close()
        val bytes = body.toByteArray(Charsets.UTF_8)
        responseHeaders.set("Content-Type", "$contentType; charset=utf-8")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.write(bytes)
    }

    private fun KoogHttpClient.events(path: String): Flow<String> = sse(
        path = path, requestBody = "request", requestBodyType = String::class,
        dataFilter = { it != null }, decodeStreamingResponse = { it }, processStreamingChunk = { it },
    )

    @Test
    fun everyHttpEntryPreservesFailureMetadataWithoutSensitiveHeaders() = runBlocking {
        withServer(handler = { exchange ->
            exchange.responseHeaders.set("ReTrY-AfTeR", "12.5")
            exchange.responseHeaders.set("retry-after-ms", "12500")
            exchange.responseHeaders.set("x-ms-retry-after-ms", "13000")
            exchange.responseHeaders.set("x-should-retry", "true")
            exchange.responseHeaders.set("Set-Cookie", "secret=must-not-leak")
            exchange.responseHeaders.set("Authorization", "Bearer must-not-leak")
            exchange.responseHeaders.set("x-ratelimit-reset-tokens", "6m0s")
            exchange.respond("provider failure body", 429)
        }) { client, _ ->
            val requests: List<suspend () -> Unit> = listOf(
                { client.get<String>("get"); Unit },
                { client.post<String, String>("chat/completions", "request"); Unit },
                { client.post<String, String>("responses", "request"); Unit },
                { client.lines("chat/completions", "request").toList(); Unit },
                { client.lines("responses", "request").toList(); Unit },
                { client.events("models/gemini:streamGenerateContent").toList(); Unit },
            )
            for ((index, request) in requests.withIndex()) {
                val error = assertFailsWith<Exception> { request() }
                val causes = generateSequence(error as Throwable) { it.cause }.toList()
                val metadata = assertNotNull(causes.filterIsInstance<ProviderHttpException>().firstOrNull(), "HTTP entry $index lost metadata: ${causes.map { it.toString() }}")
                assertEquals(429, metadata.statusCode)
                assertEquals("provider failure body", metadata.responseBody)
                assertEquals("12.5", metadata.responseHeaders["retry-after"])
                assertEquals("12500", metadata.responseHeaders["retry-after-ms"])
                assertEquals("13000", metadata.responseHeaders["x-ms-retry-after-ms"])
                assertEquals("true", metadata.responseHeaders["x-should-retry"])
                assertTrue(metadata.responseHeaders.keys.all {
                    it in setOf("retry-after", "retry-after-ms", "x-ms-retry-after-ms", "x-should-retry", "date")
                })
                assertTrue(metadata.responseHeaders.values.none { it.contains("must-not-leak") })
                val diagnostic = assertNotNull(causes.filterIsInstance<KoogHttpClientException>().firstOrNull { it.statusCode == 429 })
                assertEquals("provider failure body", diagnostic.errorBody)
            }
        }
    }

    @Test
    fun llmWrappersPreserveMetadataAcrossAllThreeProtocols() = runBlocking {
        val paths = java.util.concurrent.ConcurrentLinkedQueue<String>()
        withServer(handler = { exchange ->
            paths.add(exchange.requestURI.path)
            exchange.responseHeaders.set("retry-after", "2.5")
            exchange.respond("structured error remains available", 429, "application/json")
        }) { _, origin ->
            val openai = MederiOpenAILLMClient(
                apiKey = "dummy-test-key", settings = OpenAIClientSettings(baseUrl = origin),
                httpClientFactory = MederiHttpClientFactory, chatCompletionsPath = "v1/chat/completions",
            )
            val google = GoogleLLMClient(
                apiKey = "dummy-test-key", settings = GoogleClientSettings(baseUrl = origin),
                httpClientFactory = MederiHttpClientFactory,
            )
            val chatModel = LLModel(LLMProvider.OpenAI, "chat-test",
                capabilities = listOf(LLMCapability.Completion, LLMCapability.OpenAIEndpoint.Completions))
            val responsesModel = LLModel(LLMProvider.OpenAI, "responses-test",
                capabilities = listOf(LLMCapability.Completion, LLMCapability.OpenAIEndpoint.Responses))
            val googleModel = LLModel(LLMProvider.Google, "gemini-test", capabilities = listOf(LLMCapability.Completion))
            val chatPrompt = Prompt(emptyList(), "metadata-chat", OpenAIChatParams())
            val responsesPrompt = Prompt(emptyList(), "metadata-responses", OpenAIResponsesParams())
            val googlePrompt = Prompt(emptyList(), "metadata-google", GoogleParams())
            val requests: List<Pair<String, suspend () -> Unit>> = listOf(
                "chat.execute" to { openai.execute(chatPrompt, chatModel, emptyList()); Unit },
                "chat.streaming" to { openai.executeStreaming(chatPrompt, chatModel, emptyList()).toList(); Unit },
                "responses.execute" to { openai.execute(responsesPrompt, responsesModel, emptyList()); Unit },
                "responses.streaming" to { openai.executeStreaming(responsesPrompt, responsesModel, emptyList()).toList(); Unit },
                "google.execute" to { google.execute(googlePrompt, googleModel, emptyList()); Unit },
                "google.streaming" to { google.executeStreaming(googlePrompt, googleModel, emptyList()).toList(); Unit },
            )
            try {
                for ((name, request) in requests) {
                    val error = assertFailsWith<Exception> { withTimeout(5000) { request() } }
                    val causes = generateSequence(error as Throwable) { it.cause }.toList()
                    val metadata = assertNotNull(causes.filterIsInstance<ProviderHttpException>().firstOrNull(),
                        "$name lost metadata: ${causes.map { it.toString() }}")
                    assertEquals(429, metadata.statusCode, name)
                    assertEquals("2.5", metadata.responseHeaders["retry-after"], name)
                    assertEquals("structured error remains available", metadata.responseBody, name)
                }
                assertEquals(2, paths.count { it == "/v1/chat/completions" })
                assertEquals(2, paths.count { it == "/v1/responses" })
                assertEquals(1, paths.count { it == "/v1beta/models/gemini-test:generateContent" })
                assertEquals(1, paths.count { it == "/v1beta/models/gemini-test:streamGenerateContent" })
            } finally {
                openai.close()
                google.close()
            }
        }
    }

    @Test
    fun successfulResponsesAndStreamsKeepTheirOriginalContent() = runBlocking {
        withServer(handler = { exchange ->
            when (exchange.requestURI.path) {
                "/lines" -> exchange.respond("first\n\nsecond\n")
                "/sse" -> exchange.respond("data: first\n\ndata: second\n\n", contentType = "text/event-stream")
                else -> exchange.respond("successful response")
            }
        }) { client, _ ->
            assertEquals("successful response", client.get<String>("get"))
            assertEquals("successful response", client.post<String, String>("post", "request"))
            assertEquals(listOf("first", "second"), client.lines("lines", "request").toList())
            assertEquals(listOf("first", "second"), client.events("sse").toList())
        }
    }

    @Test
    fun cancellingSuccessfulStreamRemainsCancellation() = runBlocking {
        for (sse in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>()
            withServer(handler = { exchange ->
                exchange.requestBody.close()
                exchange.responseHeaders.set("Content-Type", if (sse) "text/event-stream" else "text/plain")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.flush()
                entered.complete(Unit)
                Thread.sleep(10_000)
            }) { client, _ ->
                val job = async { if (sse) client.events("stream").toList() else client.lines("stream", "request").toList() }
                withTimeout(3000) { entered.await() }
                withTimeout(1000) { job.cancelAndJoin() }
                assertTrue(job.isCancelled)
            }
        }
    }
}
