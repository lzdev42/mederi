package xyz.mederi.provider.infrastructure.koog.google

import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.mederi.debug.ErrorCollector
import xyz.mederi.infrastructure.koog.toAssistantMessageSafe
import xyz.mederi.provider.domain.model.Provider
import xyz.mederi.provider.domain.model.ProviderType
import xyz.mederi.provider.domain.model.ReasoningLevel
import xyz.mederi.provider.domain.model.ReasoningParameter
import xyz.mederi.provider.infrastructure.koog.KoogClientFactory
import xyz.mederi.provider.infrastructure.koog.KoogParamsBuilder
import xyz.mederi.tools.BatchTool
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoogleNativeTransportTest {
    private val model = LLModel(LLMProvider.Google, "gemma-4-31b-it",
        capabilities = listOf(LLMCapability.Completion, LLMCapability.Thinking, LLMCapability.Tools))
    private val tools = listOf(BatchTool(emptyMap()).descriptor)
    private val prompt = Prompt(
        listOf(Message.System("System rules", RequestMetaInfo.Empty), Message.User("Inspect", RequestMetaInfo.Empty)),
        "google-native",
        KoogParamsBuilder.build(ProviderType.GOOGLE, ReasoningLevel.NONE,
            ReasoningParameter(levels = mapOf(ReasoningLevel.NONE to "minimal")), maxTokens = 300),
    )

    private data class Reply(val body: String, val streaming: Boolean = true)
    private data class Request(val path: String, val query: String?, val body: JsonObject)
    private val complete = """{"candidates":[{"content":{"role":"model","parts":[{"text":"Done"}]},"finishReason":"STOP"}]}"""

    private suspend fun withClient(
        vararg replies: Reply,
        block: suspend (GoogleLLMClient, LinkedBlockingQueue<Request>) -> Unit,
    ) {
        val pending = LinkedBlockingQueue(replies.toList())
        val requests = LinkedBlockingQueue<Request>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            try {
                val request = exchange.requestBody.bufferedReader().use { it.readText() }
                requests.add(Request(exchange.requestURI.path, exchange.requestURI.query, Json.parseToJsonElement(request).jsonObject))
                val reply = pending.remove()
                val body = if (reply.streaming) {
                    exchange.responseHeaders.set("Content-Type", "text/event-stream")
                    reply.body.lineSequence().joinToString("") { "data: $it\n\n" }
                } else {
                    exchange.responseHeaders.set("Content-Type", "application/json")
                    reply.body
                }
                val bytes = body.encodeToByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.write(bytes)
            } finally {
                exchange.close()
            }
        }
        server.start()
        val provider = Provider("google", "Test Google", ProviderType.GOOGLE, "http://127.0.0.1:${server.address.port}/v1beta")
        val client = KoogClientFactory.createGoogleClient(provider, "test-key")
        try { withTimeout(10_000) { block(client, requests) } }
        finally { client.close(); server.stop(0) }
    }

    @Test
    fun nativeGenerationSendsMinimalAndRoundTripsStreamedToolSignatures() = runBlocking {
        val calls = """
            {"candidates":[{"content":{"role":"model","parts":[{"text":"Checking","thought":true} ]}}]}
            {"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"id":"call_a","name":"batch","args":{"calls":[]}},"thoughtSignature":"c2lnbmF0dXJl"},{"functionCall":{"name":"batch","args":{"calls":[]}},"thoughtSignature":"c2Vjb25k"}]},"finishReason":"STOP"}]}
        """.trimIndent()
        withClient(Reply(calls), Reply(complete), Reply(complete, streaming = false)) { client, requests ->
            val frames = client.executeStreaming(prompt, model, tools).toList()
            assertEquals("STOP", frames.filterIsInstance<StreamFrame.End>().single().finishReason)
            val assistant = frames.toAssistantMessageSafe()
            val signatures = assistant.parts.filterIsInstance<MessagePart.Reasoning>().mapNotNull { it.encrypted }
            assertEquals(listOf("c2lnbmF0dXJl", "c2Vjb25k"), signatures)
            val toolCalls = assistant.parts.filterIsInstance<MessagePart.Tool.Call>()
            assertEquals(2, toolCalls.size)
            val results = toolCalls.map { MessagePart.Tool.Result(it.id, it.tool, "fixture result") }
            val replay = Prompt(prompt.messages + assistant + Message.User(results, RequestMetaInfo.Empty), prompt.id, prompt.params)
            client.executeStreaming(replay, model, tools).toList()
            assertEquals("Done", client.execute(prompt, model, tools).parts.filterIsInstance<MessagePart.Text>().single().text)

            val streamRequest = requests.remove()
            assertEquals("/v1beta/models/gemma-4-31b-it:streamGenerateContent", streamRequest.path)
            assertTrue(streamRequest.query.orEmpty().contains("alt=sse"))
            assertTrue(streamRequest.query.orEmpty().contains("key=test-key"))
            assertNotNull(streamRequest.body["systemInstruction"])
            assertNotNull(streamRequest.body["tools"])
            val replayRequest = requests.remove()
            val modelParts = replayRequest.body.getValue("contents").jsonArray
                .first { it.jsonObject["role"]?.jsonPrimitive?.content == "model" }.jsonObject.getValue("parts").jsonArray
            val transmittedCalls = modelParts.map { it.jsonObject }.filter { it.containsKey("functionCall") }
            assertEquals(signatures, transmittedCalls.map { it.getValue("thoughtSignature").jsonPrimitive.content })
            assertEquals("call_a", transmittedCalls.first().getValue("functionCall").jsonObject.getValue("id").jsonPrimitive.content)
            val responses = replayRequest.body.getValue("contents").jsonArray.last().jsonObject.getValue("parts").jsonArray
            assertEquals("call_a", responses.first().jsonObject.getValue("functionResponse").jsonObject.getValue("id").jsonPrimitive.content)
            val singleRequest = requests.remove()
            assertEquals("/v1beta/models/gemma-4-31b-it:generateContent", singleRequest.path)
            for (request in listOf(streamRequest, singleRequest)) {
                val config = request.body.getValue("generationConfig").jsonObject
                val thinking = config.getValue("thinkingConfig").jsonObject
                assertEquals("minimal", thinking.getValue("thinkingLevel").jsonPrimitive.content)
                assertEquals("false", thinking.getValue("includeThoughts").jsonPrimitive.content)
                assertEquals("300", config.getValue("maxOutputTokens").jsonPrimitive.content)
                assertNull(config["additionalProperties"])
            }
        }
    }

    @Test
    fun nativeErrorFeedbackSurvivesStreamingAndNonStreamingDecoders() = runBlocking {
        val blocked = """{"promptFeedback":{"blockReason":"SAFETY","safetyRatings":[]}}"""
        val error = """{"error":{"code":429,"message":"Quota exceeded in fixture","status":"RESOURCE_EXHAUSTED"}}"""
        withClient(Reply(blocked), Reply(error), Reply(blocked, streaming = false)) { client, _ ->
            suspend fun assertFailure(raw: String, status: Int?, expected: String, run: suspend () -> Unit) {
                val failure = assertFailsWith<Exception> { run() }
                val diagnostic = ErrorCollector.extractDiagnostics(failure)
                assertEquals(ErrorCollector.FailureMode.HTTP_ERROR, diagnostic.failureMode, diagnostic.causeChain.joinToString("\n"))
                assertEquals(status, diagnostic.httpStatusCode)
                assertEquals(raw, diagnostic.errorBody)
                assertEquals(expected, ErrorCollector.extractServerMessage(failure))
            }

            assertFailure(blocked, null, "Google blocked the prompt (SAFETY)") { client.executeStreaming(prompt, model, tools).toList() }
            assertFailure(error, 429, "Quota exceeded in fixture") { client.executeStreaming(prompt, model, tools).toList() }
            assertFailure(blocked, null, "Google blocked the prompt (SAFETY)") { client.execute(prompt, model, tools) }
        }
    }

    @Test
    fun missingEndReportsObservedStreamMetadataWithoutAttributingTheCause() = runBlocking {
        val truncated = """{"responseId":"fixture-response","candidates":[{"content":{"role":"model","parts":[{"text":"Partial"}]}}]}"""
        withClient(Reply(truncated)) { client, _ ->
            val failure = assertFailsWith<Exception> { client.executeStreaming(prompt, model, tools).toList() }
            val diagnostic = ErrorCollector.extractDiagnostics(failure)
            assertEquals(ErrorCollector.FailureMode.PREMATURE_CLOSE, diagnostic.failureMode)
            assertEquals("IncompleteStreamException", diagnostic.networkErrorType)
            assertEquals("UNKNOWN", diagnostic.responsibleParty)
            assertTrue(diagnostic.networkErrorMessage.orEmpty().contains("chunks=1, responseId=fixture-response"))
        }
    }
}
