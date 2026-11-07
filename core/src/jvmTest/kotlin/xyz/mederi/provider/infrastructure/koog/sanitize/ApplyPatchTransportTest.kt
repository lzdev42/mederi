package xyz.mederi.provider.infrastructure.koog.sanitize

import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.LLMClientException
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import kotlin.reflect.KClass
import kotlin.test.*
import xyz.mederi.tools.FileSystemTools
import xyz.mederi.tools.patch.ApplyPatchProtocol

class ApplyPatchTransportTest {
    private var responseBody: String = ""
    private var capturedRequest: String? = null
    private var responseLines: List<String> = emptyList()
    private val fakeHttpClient = object : KoogHttpClient {
        override val clientName: String = "fake"
        override suspend fun <R : Any> get(
            path: String, responseType: KClass<R>,
            parameters: Map<String, String>, headers: Map<String, String>
        ): R = error("not used")

        override suspend fun <T : Any, R : Any> post(
            path: String, requestBody: T, requestBodyType: KClass<T>, responseType: KClass<R>,
            parameters: Map<String, String>, headers: Map<String, String>
        ): R {
            capturedRequest = requestBody as String
            @Suppress("UNCHECKED_CAST")
            return responseBody as R
        }

        override fun <T : Any, R : Any, O : Any> sse(
            path: String, requestBody: T, requestBodyType: KClass<T>,
            dataFilter: (String?) -> Boolean,
            decodeStreamingResponse: (String) -> R,
            processStreamingChunk: (R) -> O?,
            parameters: Map<String, String>, headers: Map<String, String>
        ): Flow<O> = error("not used")

        override fun <T : Any> lines(
            path: String, requestBody: T, requestBodyType: KClass<T>,
            parameters: Map<String, String>, headers: Map<String, String>
        ): Flow<String> {
            capturedRequest = requestBody as String
            return flow { responseLines.forEach { emit(it) } }
        }

        override fun close() { }
    }

    private val fakeFactory = object : KoogHttpClient.Factory {
        override fun create(
            clientName: String,
            baseUrl: String,
            headers: Map<String, String>,
            queryParameters: Map<String, String>,
            requestTimeoutMillis: Long,
            connectTimeoutMillis: Long,
            socketTimeoutMillis: Long,
            json: Json
        ): KoogHttpClient = fakeHttpClient
    }


    private val model = LLModel(LLMProvider.OpenAI, "test-responses")
    private val patch = "*** Begin Patch\n*** Add File: quoted.txt\n+\"hello\" \\path\t中文😀\n*** End Patch\n"
    private fun client(base: String = "https://api.openai.com", path: String = "v1/chat/completions") = MederiOpenAILLMClient(
        apiKey = "test-key", settings = OpenAIClientSettings(baseUrl = base),
        httpClientFactory = fakeFactory, chatCompletionsPath = path
    )
    private val tool get() = FileSystemTools(listOf(".")).ApplyPatchTool().descriptor
    private fun prompt(messages: List<Message> = emptyList()) = Prompt(messages, "patch-test")

    @Test
    fun `official responses declares exact Codex grammar and compatible endpoint uses JSON function`() {
        val official = Json.parseToJsonElement(client().buildResponsesRequestJson(prompt(), model, listOf(tool), true)).jsonObject
        val declared = official.getValue("tools").jsonArray.single().jsonObject
        val upstreamGrammar = assertNotNull(javaClass.getResourceAsStream("/apply-patch-upstream/apply_patch.lark")).use { it.readBytes().decodeToString() }
        assertEquals(upstreamGrammar, ApplyPatchProtocol.LARK_GRAMMAR)
        assertEquals("custom", declared.getValue("type").jsonPrimitive.content)
        assertEquals(ApplyPatchProtocol.LARK_GRAMMAR, declared.getValue("format").jsonObject.getValue("definition").jsonPrimitive.content)
        assertFalse("parameters" in declared)
        listOf("https://compatible.example", "https://api.openai.com.evil.example", "http://api.openai.com").forEach { base ->
            val other = Json.parseToJsonElement(client(base).buildResponsesRequestJson(prompt(), model, listOf(tool), true)).jsonObject
                .getValue("tools").jsonArray.single().jsonObject
            assertEquals("function", other.getValue("type").jsonPrimitive.content)
            assertTrue("patch" in other.getValue("parameters").jsonObject.getValue("properties").jsonObject)
            assertTrue(other.getValue("parameters").jsonObject.getValue("required").jsonArray.any { it.jsonPrimitive.content == "patch" })
            assertFalse("format" in other)
        }
    }

    @Test
    fun `history replays raw custom input and retains both custom and function results in order`() {
        val history = listOf(
            Message.Assistant(listOf(MessagePart.Text("I will apply the patch."), MessagePart.Tool.Call("call-patch", "apply_patch", ApplyPatchProtocol.encodeArguments(patch))), ResponseMetaInfo.Empty),
            Message.User(listOf(MessagePart.Tool.Result("call-patch", "apply_patch", "Success."), MessagePart.Text("continue"),
                MessagePart.Tool.Result("call-read", "read_file", "file contents")), RequestMetaInfo.Empty)
        )
        val withText = Json.parseToJsonElement(client().buildResponsesRequestJson(prompt(history), model, listOf(tool), false)).jsonObject.getValue("input").jsonArray
        assertEquals("output_text", withText[0].jsonObject.getValue("content").jsonArray.single().jsonObject.getValue("type").jsonPrimitive.content)
        val input = withText.drop(1)
        assertEquals("custom_tool_call", input[0].jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(patch, input[0].jsonObject.getValue("input").jsonPrimitive.content)
        assertEquals("custom_tool_call_output", input[1].jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("Success.", input[1].jsonObject.getValue("output").jsonPrimitive.content)
        assertEquals("user", input[2].jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals("function_call_output", input[3].jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("call-read", input[3].jsonObject.getValue("call_id").jsonPrimitive.content)
        val compatible = Json.parseToJsonElement(client("https://compatible.example").buildResponsesRequestJson(prompt(history), model, listOf(tool), false)).jsonObject.getValue("input").jsonArray.drop(1)
        assertEquals("function_call", compatible[0].jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(ApplyPatchProtocol.encodeArguments(patch), compatible[0].jsonObject.getValue("arguments").jsonPrimitive.content)
    }

    @Test
    fun `custom input deltas are escaped and delivered before completion and missing output index uses item id`(): Unit = runBlocking {
        val deliveredBeforeDone = CompletableDeferred<Unit>()
        val events = flow {
            emit(SanitizedResponsesStreamEvent("response.output_item.added", item = SanitizedResponsesOutputItem("custom_tool_call", id = "item-patch", callId = "call-patch", name = "apply_patch", input = ""), outputIndex = 0))
            patch.chunked(3).forEach { emit(SanitizedResponsesStreamEvent("response.custom_tool_call_input.delta", itemId = "item-patch", delta = it)) }
            withTimeout(5_000) { deliveredBeforeDone.await() }
            emit(SanitizedResponsesStreamEvent("response.custom_tool_call_input.done", itemId = "item-patch", input = patch))
            emit(SanitizedResponsesStreamEvent("response.output_item.done", outputIndex = 0, item = SanitizedResponsesOutputItem("custom_tool_call", input = patch)))
            emit(SanitizedResponsesStreamEvent("response.completed", response = SanitizedResponsesAPIResponse()))
        }
        val frames = mutableListOf<StreamFrame>()
        client().processResponsesStreamingFlow(events).collect { frame ->
            if (frame is StreamFrame.ToolCallDelta && frame.content?.isNotEmpty() == true) deliveredBeforeDone.complete(Unit)
            if (frame is StreamFrame.ToolCallDelta) {
                assertEquals(frame.content, frame.content?.encodeToByteArray()?.decodeToString(), "Every emitted fragment must survive UTF-8 transport")
            }
            frames.add(frame)
        }
        val complete = frames.filterIsInstance<StreamFrame.ToolCallComplete>().single()
        assertEquals("call-patch", complete.id)
        assertEquals("apply_patch", complete.name)
        assertEquals(patch, ApplyPatchProtocol.decodeArguments(complete.content))
        assertEquals(ApplyPatchProtocol.encodeArguments(patch), frames.filterIsInstance<StreamFrame.ToolCallDelta>().joinToString("") { it.content.orEmpty() })
    }

    @Test
    fun `terminal input without deltas works and conflicting terminal metadata fails safely`(): Unit = runBlocking {
        fun events(final: String) = flowOf(
            SanitizedResponsesStreamEvent("response.output_item.added", item = SanitizedResponsesOutputItem("custom_tool_call", id = "item", callId = "call", name = "apply_patch", input = ""), outputIndex = 0),
            SanitizedResponsesStreamEvent("response.custom_tool_call_input.done", outputIndex = 0, input = final),
            SanitizedResponsesStreamEvent("response.completed", response = SanitizedResponsesAPIResponse())
        )
        val result = client().processResponsesStreamingFlow(events(patch)).toList().filterIsInstance<StreamFrame.ToolCallComplete>().single()
        assertEquals(patch, ApplyPatchProtocol.decodeArguments(result.content))
        assertFailsWith<IllegalArgumentException> {
            client().processResponsesStreamingFlow(flowOf(
                SanitizedResponsesStreamEvent("response.output_item.added", item = SanitizedResponsesOutputItem("custom_tool_call", id = "item", callId = "call", name = "apply_patch", input = "prefix"), outputIndex = 0),
                SanitizedResponsesStreamEvent("response.custom_tool_call_input.done", outputIndex = 0, input = "different")
            )).toList()
        }
    }

    @Test
    fun `incomplete stream never finalizes executable patch arguments`(): Unit = runBlocking {
        assertFailsWith<IllegalArgumentException> {
            client().processResponsesStreamingFlow(flowOf(
                SanitizedResponsesStreamEvent("response.output_item.added", item = SanitizedResponsesOutputItem("custom_tool_call", id = "item", callId = "call", name = "apply_patch", input = patch), outputIndex = 0),
                SanitizedResponsesStreamEvent("response.incomplete", response = SanitizedResponsesAPIResponse())
            )).toList()
        }
    }

    @Test
    fun `nonstream HTTP request declares custom tool and response converts freeform to DTO arguments`(): Unit = runBlocking {
        responseBody = buildJsonObject {
            put("status", "completed")
            put("output", buildJsonArray { add(buildJsonObject {
                put("type", "custom_tool_call")
                put("call_id", "call-http")
                put("name", "apply_patch")
                put("input", patch)
            }) })
        }.toString()
        val response = client().execute(prompt(), model, listOf(tool))
        val call = response.parts.filterIsInstance<MessagePart.Tool.Call>().single()
        assertEquals("call-http", call.id)
        assertEquals(patch, ApplyPatchProtocol.decodeArguments(call.args))
        val request = Json.parseToJsonElement(assertNotNull(capturedRequest)).jsonObject
        assertEquals(false, request.getValue("stream").jsonPrimitive.boolean)
        assertEquals("custom", request.getValue("tools").jsonArray.single().jsonObject.getValue("type").jsonPrimitive.content)
    }


    @Test
    fun `public streaming HTTP path keeps function tools working beside custom patches`(): Unit = runBlocking {
        val events = listOf(
            SanitizedResponsesStreamEvent("response.output_item.added", item = SanitizedResponsesOutputItem("custom_tool_call", id = "item-patch", callId = "call-patch", name = "apply_patch", input = ""), outputIndex = 0),
            SanitizedResponsesStreamEvent("response.custom_tool_call_input.delta", itemId = "item-patch", delta = patch),
            SanitizedResponsesStreamEvent("response.custom_tool_call_input.done", itemId = "item-patch", input = patch),
            SanitizedResponsesStreamEvent("response.output_item.added", item = SanitizedResponsesOutputItem("function_call", id = "item-read", callId = "call-read", name = "read_file", arguments = ""), outputIndex = 1),
            SanitizedResponsesStreamEvent("response.function_call_arguments.delta", itemId = "item-read", delta = buildJsonObject { put("path", "file.txt") }.toString()),
            SanitizedResponsesStreamEvent("response.completed", response = SanitizedResponsesAPIResponse())
        )
        responseLines = events.map { "data: " + Json.encodeToString(SanitizedResponsesStreamEvent.serializer(), it) } + "data: [DONE]"
        val frames = client().executeStreaming(prompt(), model, listOf(tool)).toList()
        val calls = frames.filterIsInstance<StreamFrame.ToolCallComplete>()
        assertEquals(listOf("apply_patch", "read_file"), calls.map { it.name })
        assertEquals(patch, ApplyPatchProtocol.decodeArguments(calls[0].content))
        assertEquals("file.txt", Json.parseToJsonElement(calls[1].content).jsonObject.getValue("path").jsonPrimitive.content)
        assertEquals(true, Json.parseToJsonElement(assertNotNull(capturedRequest)).jsonObject.getValue("stream").jsonPrimitive.boolean)
    }


    @Test
    fun `absolute compatible path cannot inherit official custom tool support`() {
        val request = Json.parseToJsonElement(client(path = "https://compatible.example/v1/chat/completions")
            .buildResponsesRequestJson(prompt(), model, listOf(tool), false)).jsonObject
        assertEquals("function", request.getValue("tools").jsonArray.single().jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `nonstream incomplete response or incomplete custom item fails before tool execution`(): Unit = runBlocking {
        listOf("incomplete" to "completed", "failed" to "completed", "completed" to "in_progress").forEach { (status, itemStatus) ->
            responseBody = buildJsonObject {
                put("status", status)
                put("output", buildJsonArray { add(buildJsonObject {
                    put("type", "custom_tool_call")
                    put("status", itemStatus)
                    put("call_id", "call-http")
                    put("name", "apply_patch")
                    put("input", patch)
                }) })
            }.toString()
            if (status == "failed") {
                val error = assertFailsWith<LLMClientException> { client().execute(prompt(), model, listOf(tool)) }
                assertIs<ai.koog.http.client.KoogHttpClientException>(error.cause)
            } else {
                assertFailsWith<IllegalArgumentException> { client().execute(prompt(), model, listOf(tool)) }
            }
        }
    }


    @Test
    fun `interleaved custom and function inputs with commentary produce exactly one complete call each`(): Unit = runBlocking {
        val args = buildJsonObject { put("path", "file.txt") }.toString()
        val halfway = patch.length / 2
        val events = flowOf(
            SanitizedResponsesStreamEvent("response.output_item.added", item = SanitizedResponsesOutputItem("custom_tool_call", id = "item-patch", callId = "call-patch", name = "apply_patch", input = ""), outputIndex = 0),
            SanitizedResponsesStreamEvent("response.custom_tool_call_input.delta", itemId = "item-patch", delta = patch.take(halfway)),
            SanitizedResponsesStreamEvent("response.output_item.added", item = SanitizedResponsesOutputItem("function_call", id = "item-read", callId = "call-read", name = "read_file", arguments = ""), outputIndex = 1),
            SanitizedResponsesStreamEvent("response.function_call_arguments.delta", itemId = "item-read", delta = args.take(5)),
            SanitizedResponsesStreamEvent("response.output_text.delta", outputIndex = 2, delta = "Working..."),
            SanitizedResponsesStreamEvent("response.custom_tool_call_input.delta", itemId = "item-patch", delta = patch.drop(halfway)),
            SanitizedResponsesStreamEvent("response.function_call_arguments.delta", itemId = "item-read", delta = args.drop(5)),
            SanitizedResponsesStreamEvent("response.custom_tool_call_input.done", itemId = "item-patch", input = patch),
            SanitizedResponsesStreamEvent("response.output_item.done", outputIndex = 1, item = SanitizedResponsesOutputItem("function_call", id = "item-read", arguments = args)),
            SanitizedResponsesStreamEvent("response.completed", response = SanitizedResponsesAPIResponse())
        )
        val frames = client().processResponsesStreamingFlow(events).toList()
        val calls = frames.filterIsInstance<StreamFrame.ToolCallComplete>()
        assertEquals(2, calls.size)
        assertEquals(patch, ApplyPatchProtocol.decodeArguments(calls.single { it.name == "apply_patch" }.content))
        assertEquals(args, calls.single { it.name == "read_file" }.content)
        assertEquals("Working...", frames.filterIsInstance<StreamFrame.TextComplete>().single().text)
    }

    @Test
    fun `surrogate pairs split across deltas survive UTF8 transport and dangling pair fails`() {
        val encoder = ApplyPatchProtocol.StreamingArguments()
        val fragments = listOf(encoder.open(""), encoder.append("\uD83D"), encoder.append("\uDE00"), encoder.close("😀"))
        fragments.forEach { assertEquals(it, it.encodeToByteArray().decodeToString()) }
        assertEquals("😀", ApplyPatchProtocol.decodeArguments(fragments.joinToString("")))
        val broken = ApplyPatchProtocol.StreamingArguments()
        broken.open("\uD83D")
        assertFailsWith<IllegalArgumentException> { broken.close(null) }
    }


    @Test
    fun `final incomplete custom metadata rejects an already received input done`(): Unit = runBlocking {
        val start = listOf(
            SanitizedResponsesStreamEvent("response.output_item.added", item = SanitizedResponsesOutputItem("custom_tool_call", id = "item", callId = "call", name = "apply_patch", input = ""), outputIndex = 0),
            SanitizedResponsesStreamEvent("response.custom_tool_call_input.done", outputIndex = 0, input = patch)
        )
        val invalidTerminals = listOf(
            SanitizedResponsesStreamEvent("response.output_item.done", outputIndex = 0,
                item = SanitizedResponsesOutputItem("custom_tool_call", status = "incomplete", input = patch)),
            SanitizedResponsesStreamEvent("response.completed", response = SanitizedResponsesAPIResponse(status = "incomplete")),
            SanitizedResponsesStreamEvent("response.completed", response = SanitizedResponsesAPIResponse(status = "completed",
                output = listOf(SanitizedResponsesOutputItem("custom_tool_call", status = "incomplete", input = patch))))
        )
        invalidTerminals.forEach { terminal ->
            assertFailsWith<IllegalArgumentException> {
                client().processResponsesStreamingFlow(flow { (start + terminal).forEach { emit(it) } }).toList()
            }
        }
    }


    @Test
    fun `conflicting final raw input after input done fails for item and response metadata`(): Unit = runBlocking {
        val start = listOf(
            SanitizedResponsesStreamEvent("response.output_item.added", item = SanitizedResponsesOutputItem("custom_tool_call", id = "item", callId = "call", name = "apply_patch", input = ""), outputIndex = 0),
            SanitizedResponsesStreamEvent("response.custom_tool_call_input.delta", outputIndex = 0, delta = patch),
            SanitizedResponsesStreamEvent("response.custom_tool_call_input.done", outputIndex = 0, input = patch)
        )
        val conflictingItem = SanitizedResponsesOutputItem("custom_tool_call", id = "item", callId = "call",
            name = "apply_patch", status = "completed", input = patch + "different")
        val terminals = listOf(
            SanitizedResponsesStreamEvent("response.output_item.done", outputIndex = 0, item = conflictingItem),
            SanitizedResponsesStreamEvent("response.completed", response = SanitizedResponsesAPIResponse(status = "completed", output = listOf(conflictingItem)))
        )
        terminals.forEach { terminal ->
            assertFailsWith<IllegalArgumentException> {
                client().processResponsesStreamingFlow(flow { (start + terminal).forEach { emit(it) } }).toList()
            }
        }
    }

}
