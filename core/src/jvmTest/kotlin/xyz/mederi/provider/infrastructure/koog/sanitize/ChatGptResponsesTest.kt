package xyz.mederi.provider.infrastructure.koog.sanitize

import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.reflect.KClass
import kotlin.test.*
import xyz.mederi.provider.parseChatGptModelsResponse
import xyz.mederi.provider.parseOpenAIModelsResponse
import xyz.mederi.tools.FileSystemTools
import xyz.mederi.tools.patch.ApplyPatchProtocol

class ChatGptResponsesTest {
    private val model = LLModel(LLMProvider.OpenAI, "account-model")
    private val tool get() = FileSystemTools(listOf(".")).ApplyPatchTool().descriptor
    private var lines: List<String> = emptyList()
    private val requests = mutableListOf<Pair<String, Map<String, String>>>()
    private val fake = object : KoogHttpClient {
        override val clientName = "chatgpt-test"
        override suspend fun <R : Any> get(path: String, responseType: KClass<R>, parameters: Map<String, String>, headers: Map<String, String>): R = error("not used")
        override suspend fun <T : Any, R : Any> post(path: String, requestBody: T, requestBodyType: KClass<T>, responseType: KClass<R>, parameters: Map<String, String>, headers: Map<String, String>): R = error("SIWC must stream even execute()")
        override fun <T : Any, R : Any, O : Any> sse(path: String, requestBody: T, requestBodyType: KClass<T>, dataFilter: (String?) -> Boolean, decodeStreamingResponse: (String) -> R, processStreamingChunk: (R) -> O?, parameters: Map<String, String>, headers: Map<String, String>): Flow<O> = error("not used")
        override fun <T : Any> lines(path: String, requestBody: T, requestBodyType: KClass<T>, parameters: Map<String, String>, headers: Map<String, String>): Flow<String> = flow {
            requests += (requestBody as String) to headers
            lines.forEach { emit(it) }
        }
        override fun close() = Unit
    }
    private val factory = object : KoogHttpClient.Factory {
        override fun create(clientName: String, baseUrl: String, headers: Map<String, String>, queryParameters: Map<String, String>, requestTimeoutMillis: Long, connectTimeoutMillis: Long, socketTimeoutMillis: Long, json: Json): KoogHttpClient = fake
    }
    private fun client(oauth: Boolean = true, token: (suspend () -> String)? = null) = MederiOpenAILLMClient(
        apiKey = "initial-token", settings = OpenAIClientSettings(baseUrl = "https://api.openai.com"),
        httpClientFactory = factory, chatCompletionsPath = "v1/chat/completions",
        chatGptPlan = oauth, accessTokenProvider = token,
    )
    private fun request(client: MederiOpenAILLMClient, history: List<Message> = emptyList()) =
        Json.parseToJsonElement(client.buildResponsesRequestJson(Prompt(history, "chatgpt-test"), model, listOf(tool), false)).jsonObject

    @Test
    fun `account models preserve visible ordering and names without inventing metadata`() {
        val catalog = buildJsonObject {
            put("models", buildJsonArray {
                listOf(Triple("second", "Second model", "list"), Triple("hidden", "Hidden", "hide"), Triple("first", "First model", "list")).forEach { (slug, name, visibility) ->
                    add(buildJsonObject { put("slug", slug); put("display_name", name); put("visibility", visibility) })
                }
            })
        }
        val models = parseChatGptModelsResponse(catalog.toString())
        assertEquals(listOf("second", "first"), models.map { it.providerModelId })
        assertEquals(listOf("Second model", "First model"), models.map { it.name })
        assertTrue(models.all { it.contextWindow == null && it.maxTokens == null })
        assertFails { parseOpenAIModelsResponse(catalog.toString()) }
        assertFails { parseChatGptModelsResponse(buildJsonObject { put("data", buildJsonArray { }) }.toString()) }
    }

    @Test
    fun `authorization requests force store false stream true and namespace tools`() {
        val request = request(client())
        assertFalse(request.getValue("store").jsonPrimitive.boolean)
        assertTrue(request.getValue("stream").jsonPrimitive.boolean)
        val namespace = request.getValue("tools").jsonArray.single().jsonObject
        assertEquals("namespace", namespace.getValue("type").jsonPrimitive.content)
        assertEquals("mederi", namespace.getValue("name").jsonPrimitive.content)
        assertEquals("apply_patch", namespace.getValue("tools").jsonArray.single().jsonObject.getValue("name").jsonPrimitive.content)
        assertTrue(setOf("temperature", "max_output_tokens", "previous_response_id", "conversation").none { it in request })
        val keyRequest = request(client(oauth = false))
        assertFalse("store" in keyRequest)
        assertFalse(keyRequest.getValue("stream").jsonPrimitive.boolean)
        assertEquals("custom", keyRequest.getValue("tools").jsonArray.single().jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `history keeps namespace tool ids and results and maps system to developer`() {
        val patch = "*** Begin Patch\n*** Add File: greeting.txt\n+hello\n*** End Patch"
        val history = listOf(
            Message.System("Follow instructions", RequestMetaInfo.Empty),
            Message.Assistant(listOf(MessagePart.Tool.Call("call-patch", "apply_patch", ApplyPatchProtocol.encodeArguments(patch)), MessagePart.Tool.Call("call-read", "read_file", "{}")), ResponseMetaInfo.Empty),
            Message.User(listOf(MessagePart.Tool.Result("call-patch", "apply_patch", "Saved"), MessagePart.Tool.Result("call-read", "read_file", "hello")), RequestMetaInfo.Empty),
        )
        val input = request(client(), history).getValue("input").jsonArray.map { it.jsonObject }
        assertEquals("developer", input[0].getValue("role").jsonPrimitive.content)
        assertEquals(listOf("mederi", "mederi"), input.subList(1, 3).map { it.getValue("namespace").jsonPrimitive.content })
        assertEquals(patch, input[1].getValue("input").jsonPrimitive.content)
        assertEquals(listOf("call-patch", "call-read"), input.takeLast(2).map { it.getValue("call_id").jsonPrimitive.content })
        assertEquals(listOf("Saved", "hello"), input.takeLast(2).map { it.getValue("output").jsonPrimitive.content })
    }

    @Test
    fun `execute collects full SSE and renews authorization on each request`() = runBlocking {
        var tokenReads = 0
        val client = client { "token-${++tokenReads}" }
        fun event(type: String, body: JsonObjectBuilder.() -> Unit = {}) = "data: " + buildJsonObject { put("type", type); body() }
        lines = listOf(
            event("response.output_text.delta") { put("delta", "Hello "); put("output_index", 0) },
            event("response.output_text.delta") { put("delta", "world"); put("output_index", 0) },
            event("response.completed") { put("response", buildJsonObject { put("status", "completed") }) },
            "data: [DONE]",
        )
        repeat(2) {
            val response = client.execute(Prompt(emptyList(), "test"), model, emptyList())
            assertEquals("Hello world", response.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text })
        }
        assertEquals(2, tokenReads)
        assertEquals(listOf("Bearer token-1", "Bearer token-2"), requests.map { it.second["Authorization"] })
        assertTrue(requests.all { Json.parseToJsonElement(it.first).jsonObject.getValue("stream").jsonPrimitive.boolean })
    }

    @Test
    fun `execute preserves streamed tool name arguments and call id for next history replay`() = runBlocking {
        fun line(event: SanitizedResponsesStreamEvent) = "data: " + sanitizeJson.encodeToString(SanitizedResponsesStreamEvent.serializer(), event)
        lines = listOf(
            line(SanitizedResponsesStreamEvent("response.output_item.added", item = SanitizedResponsesOutputItem("function_call", id = "item-read", callId = "call-read", name = "read_file", arguments = ""), outputIndex = 0)),
            line(SanitizedResponsesStreamEvent("response.function_call_arguments.delta", outputIndex = 0, delta = "{}")),
            line(SanitizedResponsesStreamEvent("response.output_item.done", outputIndex = 0, item = SanitizedResponsesOutputItem("function_call", arguments = "{}"))),
            line(SanitizedResponsesStreamEvent("response.completed", response = SanitizedResponsesAPIResponse())),
            "data: [DONE]",
        )
        val response = client().execute(Prompt(emptyList(), "test"), model, emptyList())
        val call = response.parts.filterIsInstance<MessagePart.Tool.Call>().single()
        assertEquals("read_file", call.tool)
        assertEquals("call-read", call.id)
        assertEquals("{}", call.args)
        val replay = request(client(), listOf(response, Message.User(MessagePart.Tool.Result(call.id, call.tool, "file content"), RequestMetaInfo.Empty)))
            .getValue("input").jsonArray
        assertEquals("mederi", replay[0].jsonObject.getValue("namespace").jsonPrimitive.content)
        assertEquals("call-read", replay[1].jsonObject.getValue("call_id").jsonPrimitive.content)
    }

    @Test
    fun `truncated OAuth response is never accepted as a complete nonstream response`(): Unit = runBlocking {
        lines = listOf("data: " + buildJsonObject { put("type", "response.incomplete") })
        val failure = assertFailsWith<ai.koog.prompt.executor.clients.LLMClientException> {
            client().execute(Prompt(emptyList(), "test"), model, emptyList())
        }
        assertIs<IllegalArgumentException>(failure.cause)
    }
}
