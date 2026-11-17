package xyz.mederi.provider.infrastructure.koog.sanitize

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 验证流式路径 [MederiOpenAILLMClient.processStreamingFlow] 把 usage 的 cached_tokens
 * 写进 End 帧的 metaInfo.metadata（修复前流式走父类 createMetaInfo 不写 cached，导致
 * Message.cachedTokens 常态 null）。
 *
 * 直接驱动 processStreamingFlow（internal），不经 HTTP——但父类构造期 createConfiguredHttpClient
 * 会调 factory.create，故需一个返回 fake client 的 factory。
 */
class MederiOpenAILLMClientTest {

    @Test
    fun `stream error preserves original payload classification and retry eligibility`() {
        val raw = """{"id":"gen-failed","error":{"code":429,"message":"Rate limit exceeded","metadata":{"provider_name":"upstream","raw":"${"detail".repeat(200)}"}},"choices":[{"index":0,"delta":{},"finish_reason":"error"}]}"""
        val error = kotlin.test.assertFails { makeClient().checkChatResponseError(raw) }
        val record = xyz.mederi.debug.ErrorCollector.collect(error)
        assertEquals(xyz.mederi.debug.ErrorCategory.RATE_LIMIT, record.category)
        assertEquals(429, record.httpStatusCode)
        assertEquals(raw, record.errorBody)
        kotlin.test.assertTrue(record.fullDiagnostic.contains(raw))
        kotlin.test.assertTrue(record.toPayload()["errorBody"] == raw)
        kotlin.test.assertTrue(xyz.mederi.provider.infrastructure.koog.retry.RetryableLLMClient.isTransientError(error))
        val report = xyz.mederi.tools.subagent.subagentErrorReport(record.toPayload())
        kotlin.test.assertTrue(report.contains(raw))
    }

    @Test
    fun `responses failures retain raw nested and standalone errors`() {
        for (raw in listOf(
            """{"type":"response.failed","response":{"status":"failed","error":{"code":"server_error","message":"Upstream unavailable","metadata":{"trace":"original"}}}}""",
            """{"type":"error","code":"invalid_request","message":"Original standalone message"}""",
            """{"status":"failed","error":{"code":"server_error","message":"Non-streaming original error"}}"""
        )) {
            val error = kotlin.test.assertFails { makeClient().checkResponsesResponseError(raw) }
            val record = xyz.mederi.debug.ErrorCollector.collect(error)
            assertEquals(raw, record.errorBody)
            kotlin.test.assertTrue(record.fullDiagnostic.contains(raw))
            kotlin.test.assertTrue(xyz.mederi.tools.subagent.subagentErrorReport(record.toPayload()).contains(raw))
        }
    }

    @Test
    fun `error only frame without numeric code keeps original reason`() {
        val raw = """{"error":{"code":"provider_failed","message":"Upstream disconnected","metadata":{"trace_id":"trace-original"}}}"""
        val error = kotlin.test.assertFails { makeClient().checkChatResponseError(raw) }
        val record = xyz.mederi.debug.ErrorCollector.collect(error)
        assertEquals(xyz.mederi.debug.ErrorCategory.API, record.category)
        assertNull(record.httpStatusCode)
        assertEquals(raw, record.errorBody)
        assertEquals("Upstream disconnected", record.serverMessage)
        kotlin.test.assertTrue(record.fullDiagnostic.contains(raw))
    }

    @Test
    fun `choice error and missing error details both preserve response`() {
        for (raw in listOf(
            """{"choices":[{"finish_reason":"error","error":{"code":502,"message":"Upstream failed"}}]}""",
            """{"id":"gen-no-detail","choices":[{"finish_reason":"error"}]}"""
        )) {
            val error = kotlin.test.assertFails { makeClient().checkChatResponseError(raw) }
            assertEquals(raw, xyz.mederi.debug.ErrorCollector.extractDiagnostics(error).errorBody)
        }
    }

    @Test
    fun `provider error after partial text does not emit successful end`() = runBlocking {
        val client = makeClient()
        val frames = mutableListOf<StreamFrame>()
        val partialDelivered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val error = kotlin.test.assertFails {
            client.processStreamingFlow(kotlinx.coroutines.flow.flow {
                emit(SanitizedStreamResponse(
                    choices = listOf(SanitizedStreamChoice(SanitizedStreamDelta(content = "partial"), index = 0)),
                    created = 0, id = "gen-partial", model = "test", objectType = "chat.completion.chunk"))
                // 等待真实交付一帧后再模拟断流，避免纯内存生产者抢先失败取消缓冲帧。
                kotlinx.coroutines.withTimeout(5_000) { partialDelivered.await() }
                client.checkChatResponseError("""{"error":{"code":502,"message":"Provider disconnected"},"choices":[{"finish_reason":"error"}]}""")
            }).collect {
                frames.add(it)
                if (it is StreamFrame.TextDelta) partialDelivered.complete(Unit)
            }
        }
        kotlin.test.assertTrue(frames.any { it is StreamFrame.TextDelta })
        kotlin.test.assertTrue(frames.none { it is StreamFrame.End })
        assertEquals(502, xyz.mederi.debug.ErrorCollector.extractDiagnostics(error).httpStatusCode)
    }

    /** 不会被实际调用（processStreamingFlow 不走 HTTP）；构造期需要一个非 null 的 KoogHttpClient */
    private val fakeHttpClient = object : KoogHttpClient {
        override val clientName: String = "fake"
        override suspend fun <R : Any> get(
            path: String, responseType: KClass<R>,
            parameters: Map<String, String>, headers: Map<String, String>
        ): R = error("not used")

        override suspend fun <T : Any, R : Any> post(
            path: String, requestBody: T, requestBodyType: KClass<T>, responseType: KClass<R>,
            parameters: Map<String, String>, headers: Map<String, String>
        ): R = error("not used")

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
        ): Flow<String> = error("not used")

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

    private fun makeClient(): MederiOpenAILLMClient =
        MederiOpenAILLMClient(
            apiKey = "test-key",
            settings = OpenAIClientSettings(),
            httpClientFactory = fakeFactory,
            chatCompletionsPath = "https://example.com/v1/chat/completions"
        )

    @Test
    fun `streaming end frame carries cached tokens from usage`() = runBlocking {
        val client = makeClient()
        // OpenAI 流式惯例：最后一帧带 usage、choices 可为空
        val response = flowOf(
            SanitizedStreamResponse(
                choices = listOf(
                    SanitizedStreamChoice(
                        delta = SanitizedStreamDelta(content = "hello"),
                        finishReason = null,
                        index = 0
                    )
                ),
                created = 0L,
                id = "1",
                model = "test",
                objectType = "chat.completion.chunk",
                usage = null
            ),
            SanitizedStreamResponse(
                choices = emptyList(),
                created = 0L,
                id = "1",
                model = "test",
                objectType = "chat.completion.chunk",
                usage = SanitizedChatUsage(
                    promptTokens = 100,
                    completionTokens = 50,
                    totalTokens = 150,
                    promptTokensDetails = SanitizedInputTokensDetails(cachedTokens = 80)
                )
            )
        )

        val frames = client.processStreamingFlow(response).toList()
        val end = frames.filterIsInstance<StreamFrame.End>().single()

        assertEquals(100, end.metaInfo.inputTokensCount)
        assertEquals(50, end.metaInfo.outputTokensCount)
        // 修复前（走父类 createMetaInfo）metadata 为 null → 此断言失败
        val cached = end.metaInfo.metadata?.get("cachedTokens")
        assertNotNull(cached, "cachedTokens metadata 必须存在（修复后走 createMetaInfoInternal）")
        assertEquals("80", cached.jsonPrimitive.content)
    }

    @Test
    fun `streaming without usage produces end frame with null token counts`() = runBlocking {
        val client = makeClient()
        val response = flowOf(
            SanitizedStreamResponse(
                choices = listOf(
                    SanitizedStreamChoice(
                        delta = SanitizedStreamDelta(content = "hi"),
                        finishReason = "stop",
                        index = 0
                    )
                ),
                created = 0L,
                id = "1",
                model = "test",
                objectType = "chat.completion.chunk",
                usage = null
            )
        )

        val frames = client.processStreamingFlow(response).toList()
        val end = frames.filterIsInstance<StreamFrame.End>().single()

        assertNull(end.metaInfo.inputTokensCount)
        assertNull(end.metaInfo.outputTokensCount)
        assertNull(end.metaInfo.metadata)
    }

    @Test
    fun `blank finish reason in intermediate chunks stays null on premature close`() = runBlocking {
        val client = makeClient()
        val response = flowOf(
            SanitizedStreamResponse(
                choices = listOf(
                    SanitizedStreamChoice(
                        delta = SanitizedStreamDelta(reasoningContent = "thinking..."),
                        finishReason = "",
                        index = 0
                    )
                ),
                created = 0L,
                id = "1",
                model = "test",
                objectType = "chat.completion.chunk",
                usage = null
            )
        )

        val frames = client.processStreamingFlow(response).toList()
        val end = frames.filterIsInstance<StreamFrame.End>().single()

        assertNull(end.finishReason, "空字符串 finishReason 应被忽略，提前结束时 End.finishReason 必须为 null")
    }

    @Test
    fun `trailing blank finish reason does not overwrite prior valid finish reason`() = runBlocking {
        val client = makeClient()
        val response = flowOf(
            SanitizedStreamResponse(
                choices = listOf(
                    SanitizedStreamChoice(
                        delta = SanitizedStreamDelta(content = "done"),
                        finishReason = "stop",
                        index = 0
                    )
                ),
                created = 0L,
                id = "1",
                model = "test",
                objectType = "chat.completion.chunk",
                usage = null
            ),
            SanitizedStreamResponse(
                choices = listOf(
                    SanitizedStreamChoice(
                        delta = SanitizedStreamDelta(),
                        finishReason = "",
                        index = 0
                    )
                ),
                created = 0L,
                id = "1",
                model = "test",
                objectType = "chat.completion.chunk",
                usage = SanitizedChatUsage(promptTokens = 10, completionTokens = 5, totalTokens = 15)
            )
        )

        val frames = client.processStreamingFlow(response).toList()
        val end = frames.filterIsInstance<StreamFrame.End>().single()

        assertEquals("stop", end.finishReason, "末尾帧的空串 finishReason 不得覆盖已收到的有效 finishReason")
    }

    @Test
    fun `chat streaming decodes hetzner non-standard chunks and preserves trailing cached tokens`() = runBlocking {
        val rawChunks = listOf(
            """{"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,"model":"Qwen/Qwen3.6-35B-A3B","choices":[{"index":0,"delta":{"role":"assistant","content":""},"logprobs":null,"finish_reason":null}],"usage":{"prompt_tokens":2540,"total_tokens":2540,"completion_tokens":0,"completion_tokens_details":{"reasoning_tokens":0}},"prompt_token_ids":null,"prompt_text":null}""",
            """{"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,"model":"Qwen/Qwen3.6-35B-A3B","choices":[{"index":0,"delta":{"reasoning":"thinking"},"logprobs":null,"finish_reason":null,"token_ids":null}],"usage":{"prompt_tokens":2540,"total_tokens":2545,"completion_tokens":5,"completion_tokens_details":{"reasoning_tokens":5}}}""",
            """{"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,"model":"Qwen/Qwen3.6-35B-A3B","choices":[{"index":0,"delta":{"content":"391"},"logprobs":null,"finish_reason":"stop","stop_reason":null,"token_ids":null}],"usage":{"prompt_tokens":2540,"total_tokens":2556,"completion_tokens":16,"completion_tokens_details":{"reasoning_tokens":5}}}""",
            """{"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,"model":"Qwen/Qwen3.6-35B-A3B","choices":[],"usage":{"prompt_tokens":2540,"total_tokens":2556,"completion_tokens":16,"prompt_tokens_details":{"cached_tokens":2096,"created_cache_tokens":0},"completion_tokens_details":{"reasoning_tokens":5}}}"""
        )
        val decoded = kotlinx.coroutines.flow.flow {
            rawChunks.forEach { emit(sanitizeJson.decodeFromString<SanitizedStreamResponse>(it)) }
        }
        val frames = makeClient().processStreamingFlow(decoded).toList()
        val end = frames.filterIsInstance<StreamFrame.End>().single()

        assertEquals("stop", end.finishReason)
        assertEquals(2540, end.metaInfo.inputTokensCount)
        assertEquals(16, end.metaInfo.outputTokensCount)
        assertEquals("2096", end.metaInfo.metadata?.get("cachedTokens")?.jsonPrimitive?.content)
        assertEquals("0", end.metaInfo.metadata?.get("cacheWriteTokens")?.jsonPrimitive?.content)
        assertEquals("thinking", frames.filterIsInstance<StreamFrame.ReasoningDelta>().single().text)
        assertEquals("391", frames.filterIsInstance<StreamFrame.TextDelta>().single().text)
    }

    @Test
    fun `chat usage preserves compatible cache aliases when later chunks omit them`() = runBlocking {
        val chunks = flowOf(
            """{"id":"1","object":"chat.completion.chunk","created":1,"model":"test","choices":[],"usage":{"prompt_tokens":100,"prompt_cache_hit_tokens":80,"cache_created_input_tokens":12}}""",
            """{"id":"1","object":"chat.completion.chunk","created":1,"model":"test","choices":[],"usage":{"completion_tokens":5,"total_tokens":105}}""",
        )
        val frames = makeClient().processStreamingFlow(kotlinx.coroutines.flow.flow {
            chunks.collect { emit(sanitizeJson.decodeFromString<SanitizedStreamResponse>(it)) }
        }).toList()
        val end = frames.filterIsInstance<StreamFrame.End>().single()
        assertEquals(100, end.metaInfo.inputTokensCount)
        assertEquals(5, end.metaInfo.outputTokensCount)
        assertEquals("80", end.metaInfo.metadata?.get("cachedTokens")?.jsonPrimitive?.content)
        assertEquals("12", end.metaInfo.metadata?.get("cacheWriteTokens")?.jsonPrimitive?.content)
    }

    @Test
    fun `responses streaming handles null content array, cached tokens, and incomplete status`() = runBlocking {
        val rawEvents = listOf(
            """{"type":"response.output_item.added","output_index":0,"item":{"id":"rs_1","summary":[],"type":"reasoning","content":null,"encrypted_content":null,"status":"in_progress"}}""",
            """{"type":"response.reasoning_text.delta","output_index":0,"item_id":"rs_1","delta":"step 1"}""",
            """{"type":"response.completed","response":{"id":"resp_1","status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[{"id":"rs_1","summary":[],"type":"reasoning","content":[{"type":"reasoning_text","text":"step 1"}]}],"usage":{"input_tokens":2540,"output_tokens":16,"total_tokens":2556,"input_tokens_details":{"cached_tokens":2096,"cache_write_tokens":0}}}}"""
        )
        val events = kotlinx.coroutines.flow.flow {
            rawEvents.forEach { emit(sanitizeJson.decodeFromString<SanitizedResponsesStreamEvent>(it)) }
        }
        val frames = makeClient().processResponsesStreamingFlow(events).toList()
        val end = frames.filterIsInstance<StreamFrame.End>().single()

        assertEquals("incomplete", end.finishReason)
        assertEquals(2540, end.metaInfo.inputTokensCount)
        assertEquals(16, end.metaInfo.outputTokensCount)
        assertEquals("2096", end.metaInfo.metadata?.get("cachedTokens")?.jsonPrimitive?.content)
        assertEquals("0", end.metaInfo.metadata?.get("cacheWriteTokens")?.jsonPrimitive?.content)
        assertEquals("step 1", frames.filterIsInstance<StreamFrame.ReasoningDelta>().single().text)
    }

    @Test
    fun `responses uses finalized function arguments and rejects conflicting final metadata`() = runBlocking {
        val args = """{"path":"file.txt"}"""
        val start = listOf(
            SanitizedResponsesStreamEvent("response.output_item.added", outputIndex = 0,
                item = SanitizedResponsesOutputItem("function_call", id = "item-read", callId = "call-read", name = "read_file", arguments = "")),
            SanitizedResponsesStreamEvent("response.function_call_arguments.delta", itemId = "item-read", delta = args.take(5)),
            SanitizedResponsesStreamEvent("response.function_call_arguments.done", itemId = "item-read", arguments = args)
        )
        val finalItem = SanitizedResponsesOutputItem("function_call", id = "item-read", callId = "call-read",
            name = "read_file", status = "completed", arguments = args)
        val itemDone = SanitizedResponsesStreamEvent("response.output_item.done", item = finalItem.copy(arguments = null))
        fun terminal(item: SanitizedResponsesOutputItem) = SanitizedResponsesStreamEvent("response.completed",
            response = SanitizedResponsesAPIResponse(status = "completed", output = listOf(item)))
        val client = makeClient()
        val frames = client.processResponsesStreamingFlow(flowOf(*(start + itemDone + terminal(finalItem)).toTypedArray())).toList()
        assertEquals(args, frames.filterIsInstance<StreamFrame.ToolCallComplete>().single().content)
        assertEquals(args, frames.filterIsInstance<StreamFrame.ToolCallDelta>().joinToString("") { it.content.orEmpty() })

        kotlin.test.assertFailsWith<IllegalArgumentException> {
            val conflicting = terminal(finalItem.copy(arguments = """{"path":"different.txt"}"""))
            client.processResponsesStreamingFlow(flowOf(*(start + itemDone + conflicting).toTypedArray())).toList()
        }
        Unit
    }

    @Test
    fun `responses streams interleaved text parts and reads the distinct refusal done field`() = runBlocking {
        val start = listOf(
            SanitizedResponsesStreamEvent("response.output_text.delta", outputIndex = 0, contentIndex = 0, delta = "first"),
            SanitizedResponsesStreamEvent("response.output_text.delta", outputIndex = 1, contentIndex = 0, delta = "second"),
            SanitizedResponsesStreamEvent("response.output_text.done", outputIndex = 1, contentIndex = 0, text = "second"),
        )
        val refusal = sanitizeJson.decodeFromString<SanitizedResponsesStreamEvent>(
            """{"type":"response.refusal.done","output_index":1,"content_index":1,"refusal":"refused"}"""
        )
        val terminal = SanitizedResponsesStreamEvent("response.completed", response = SanitizedResponsesAPIResponse())
        val frames = makeClient().processResponsesStreamingFlow(flowOf(*(start + refusal + terminal).toTypedArray())).toList()
        assertEquals(listOf("first", "second", "refused"), frames.filterIsInstance<StreamFrame.TextComplete>().map { it.text })
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            val inconsistent = start.last().copy(text = "conflict")
            makeClient().processResponsesStreamingFlow(flowOf(*(start.dropLast(1) + inconsistent + terminal).toTypedArray())).toList()
        }
        Unit
    }

    @Test
    fun `responses terminal event cannot turn incomplete into completed via message status`() = runBlocking {
        val frames = makeClient().processResponsesStreamingFlow(flowOf(
            SanitizedResponsesStreamEvent("response.output_text.delta", outputIndex = 0, delta = "Partial"),
            SanitizedResponsesStreamEvent("response.incomplete", response = SanitizedResponsesAPIResponse(
                output = listOf(SanitizedResponsesOutputItem("message", status = "completed")),
            )),
        )).toList()
        assertEquals("Partial", frames.filterIsInstance<StreamFrame.TextComplete>().single().text)
        assertEquals("incomplete", frames.filterIsInstance<StreamFrame.End>().single().finishReason)
    }

    @Test
    fun `responses tool events require valid and consistent provider identities`() = runBlocking {
        val client = makeClient()
        val tool = SanitizedResponsesOutputItem("function_call", id = "item", callId = "call", name = "read_file",
            arguments = "{}")
        fun added(item: SanitizedResponsesOutputItem, index: Int? = 0) =
            SanitizedResponsesStreamEvent("response.output_item.added", item = item, outputIndex = index)
        fun done(item: SanitizedResponsesOutputItem) =
            SanitizedResponsesStreamEvent("response.output_item.done", item = item, outputIndex = 0)
        fun completed(vararg items: SanitizedResponsesOutputItem) = SanitizedResponsesStreamEvent(
            "response.completed", response = SanitizedResponsesAPIResponse(output = items.toList()),
        )

        val invalidSequences = listOf(
            listOf(added(tool.copy(callId = null)), completed()),
            listOf(added(tool.copy(name = " ")), completed()),
            listOf(added(tool, index = null), completed()),
            listOf(added(tool), added(tool.copy(id = "other-item"), index = 1), completed()),
            listOf(added(tool), added(tool.copy(callId = "other-call"), index = 1), completed()),
            listOf(added(tool), done(tool.copy(type = "message")), completed()),
            listOf(added(tool), done(tool.copy(callId = "other-call")), completed()),
            listOf(added(tool), done(tool.copy(id = "other-item")), completed()),
            listOf(added(tool), done(tool.copy(name = "list_directory")), completed()),
            listOf(added(tool), SanitizedResponsesStreamEvent(
                "response.function_call_arguments.done", itemId = "item", outputIndex = 1, arguments = "{}",
            ), completed()),
            listOf(added(tool), SanitizedResponsesStreamEvent("response.custom_tool_call_input.done",
                outputIndex = 0, input = "{}"), completed()),
            listOf(completed(tool)),
            listOf(added(tool), completed(tool.copy(id = "other-item"))),
            listOf(added(tool), completed(tool.copy(name = "other-tool"))),
            listOf(added(tool), completed(tool.copy(type = "message"))),
            listOf(added(tool), completed(tool, tool)),
            listOf(added(tool), completed(tool.copy(status = "incomplete"))),
        )
        for (events in invalidSequences) {
            val observed = mutableListOf<StreamFrame>()
            assertFailsWith<IllegalArgumentException>(events.toString()) {
                client.processResponsesStreamingFlow(flowOf(*events.toTypedArray())).toList(observed)
            }
            assertTrue(observed.none { it is StreamFrame.End }, events.toString())
        }
    }

    @Test
    fun `responses non-stream preserves cached tokens and incomplete status`() = runBlocking {
        val body = """{"id":"resp_1","status":"incomplete","output":[{"type":"reasoning","id":"rs_1","summary":null,"content":[{"type":"reasoning_text","text":"partial"}]}],"usage":{"input_tokens":2540,"output_tokens":16,"total_tokens":2556,"input_tokens_details":{"cached_tokens":2096,"cache_write_tokens":0}}}"""
        val response = nonStreamingResponsesClient { body }.execute(Prompt(emptyList(), "sess-1"), responsesTestModel, emptyList())

        assertEquals("incomplete", response.finishReason)
        assertEquals(2540, response.metaInfo.inputTokensCount)
        assertEquals(16, response.metaInfo.outputTokensCount)
        assertEquals("2096", response.metaInfo.metadata?.get("cachedTokens")?.jsonPrimitive?.content)
        assertEquals("0", response.metaInfo.metadata?.get("cacheWriteTokens")?.jsonPrimitive?.content)
        assertEquals(listOf("partial"), response.parts.filterIsInstance<MessagePart.Reasoning>().single().content)
    }

    @Test
    fun `responses non-stream never synthesizes tool names or call IDs`() = runBlocking {
        val tool = SanitizedResponsesOutputItem("function_call", callId = "call", name = "read_file", arguments = "{}")
        for (items in listOf(listOf(tool.copy(callId = null)), listOf(tool.copy(name = "")), listOf(tool, tool))) {
            val body = sanitizeJson.encodeToString(SanitizedResponsesAPIResponse(status = "completed", output = items))
            assertFailsWith<IllegalArgumentException> {
                nonStreamingResponsesClient { body }.execute(Prompt(emptyList(), "sess-1"), responsesTestModel, emptyList())
            }
        }
    }

    @Test
    fun `file attachment in chat completions degrades to text without throwing`() = runBlocking {
        var capturedJson = ""
        val http = object : KoogHttpClient {
            override val clientName: String = "fake"
            override suspend fun <R : Any> get(path: String, responseType: KClass<R>, parameters: Map<String, String>, headers: Map<String, String>): R = error("not used")
            @Suppress("UNCHECKED_CAST")
            override suspend fun <T : Any, R : Any> post(path: String, requestBody: T, requestBodyType: KClass<T>, responseType: KClass<R>, parameters: Map<String, String>, headers: Map<String, String>): R {
                capturedJson = requestBody.toString()
                return """{"id":"1","created":123456789,"model":"gpt-4o","object":"chat.completion","choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}""" as R
            }
            override fun <T : Any, R : Any, O : Any> sse(path: String, requestBody: T, requestBodyType: KClass<T>, dataFilter: (String?) -> Boolean, decodeStreamingResponse: (String) -> R, processStreamingChunk: (R) -> O?, parameters: Map<String, String>, headers: Map<String, String>): Flow<O> = error("not used")
            override fun <T : Any> lines(path: String, requestBody: T, requestBodyType: KClass<T>, parameters: Map<String, String>, headers: Map<String, String>): Flow<String> = error("not used")
            override fun close() = Unit
        }
        val factory = object : KoogHttpClient.Factory {
            override fun create(clientName: String, baseUrl: String, headers: Map<String, String>, queryParameters: Map<String, String>, requestTimeoutMillis: Long, connectTimeoutMillis: Long, socketTimeoutMillis: Long, json: Json): KoogHttpClient = http
        }
        val client = MederiOpenAILLMClient(
            apiKey = "test-key",
            settings = OpenAIClientSettings(),
            httpClientFactory = factory,
            chatCompletionsPath = "https://example.com/v1/chat/completions"
        )
        val filePart = ai.koog.prompt.message.MessagePart.Attachment(
            source = ai.koog.prompt.message.AttachmentSource.File(
                content = ai.koog.prompt.message.AttachmentContent.URL("https://example.com/test.pdf"),
                format = "pdf",
                fileName = "test.pdf",
                mimeType = "application/pdf"
            )
        )
        val prompt = Prompt(
            messages = listOf(ai.koog.prompt.message.Message.User(listOf(filePart), ai.koog.prompt.message.RequestMetaInfo.create(ai.koog.utils.time.KoogClock.System))),
            id = "sess-1"
        )
        val model = LLModel(LLMProvider.OpenAI, "gpt-4o", capabilities = listOf(ai.koog.prompt.llm.LLMCapability.OpenAIEndpoint.Completions))
        val response = client.execute(prompt, model, emptyList())
        assertEquals("ok", response.parts.filterIsInstance<MessagePart.Text>().single().text)
        assertTrue(capturedJson.contains("[File: test.pdf]"), "Chat completions should include degraded text [File: test.pdf]")
    }

    @Test
    fun `file attachment in responses api serializes to input_file`() {
        val client = nonStreamingResponsesClient { "{}" }
        val filePart = ai.koog.prompt.message.MessagePart.Attachment(
            source = ai.koog.prompt.message.AttachmentSource.File(
                content = ai.koog.prompt.message.AttachmentContent.URL("https://example.com/doc.pdf"),
                format = "pdf",
                fileName = "doc.pdf",
                mimeType = "application/pdf"
            )
        )
        val prompt = Prompt(
            messages = listOf(ai.koog.prompt.message.Message.User(listOf(filePart), ai.koog.prompt.message.RequestMetaInfo.create(ai.koog.utils.time.KoogClock.System))),
            id = "sess-2"
        )
        val json = client.buildResponsesRequestJson(prompt, responsesTestModel, emptyList(), stream = false)
        assertTrue(json.contains("\"type\":\"input_file\""), "Responses API should serialize file to input_file")
        assertTrue(json.contains("\"filename\":\"doc.pdf\""), "Responses API should include filename")
        assertTrue(json.contains("\"file_url\":\"https://example.com/doc.pdf\""), "Responses API should include file_url")
    }

    private val responsesTestModel = LLModel(LLMProvider.OpenAI, "Qwen/Qwen3.6-35B-A3B")

    private fun nonStreamingResponsesClient(body: () -> String): MederiOpenAILLMClient {
        val http = object : KoogHttpClient {
            override val clientName: String = "fake-responses"
            override suspend fun <R : Any> get(path: String, responseType: KClass<R>, parameters: Map<String, String>, headers: Map<String, String>): R = error("not used")
            @Suppress("UNCHECKED_CAST")
            override suspend fun <T : Any, R : Any> post(path: String, requestBody: T, requestBodyType: KClass<T>, responseType: KClass<R>, parameters: Map<String, String>, headers: Map<String, String>): R = body() as R
            override fun <T : Any, R : Any, O : Any> sse(path: String, requestBody: T, requestBodyType: KClass<T>, dataFilter: (String?) -> Boolean, decodeStreamingResponse: (String) -> R, processStreamingChunk: (R) -> O?, parameters: Map<String, String>, headers: Map<String, String>): Flow<O> = error("not used")
            override fun <T : Any> lines(path: String, requestBody: T, requestBodyType: KClass<T>, parameters: Map<String, String>, headers: Map<String, String>): Flow<String> = error("not used")
            override fun close() = Unit
        }
        val factory = object : KoogHttpClient.Factory {
            override fun create(clientName: String, baseUrl: String, headers: Map<String, String>, queryParameters: Map<String, String>, requestTimeoutMillis: Long, connectTimeoutMillis: Long, socketTimeoutMillis: Long, json: Json): KoogHttpClient = http
        }
        return MederiOpenAILLMClient(
            apiKey = "test-key",
            settings = OpenAIClientSettings(),
            httpClientFactory = factory,
            chatCompletionsPath = "https://example.com/v1/chat/completions"
        )
    }
}
