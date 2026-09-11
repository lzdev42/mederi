package xyz.mederi.provider.infrastructure.koog.sanitize

import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.LLMClientException
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.buildStreamFrameFlow
import ai.koog.prompt.streaming.emitEnd
import ai.koog.prompt.streaming.emitReasoningDelta
import ai.koog.prompt.streaming.emitTextDelta
import ai.koog.prompt.streaming.emitToolCallDelta
import ai.koog.prompt.streaming.requireEndFrame
import ai.koog.utils.time.KoogClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ai.koog.agents.core.tools.ToolDescriptor
import xyz.mederi.debug.DebugLog
import xyz.mederi.debug.SseDiagnostics
import xyz.mederi.debug.StreamTimingLog
import xyz.mederi.debug.StreamTrace

class MederiOpenAILLMClient(
    apiKey: String,
    settings: OpenAIClientSettings,
    httpClientFactory: KoogHttpClient.Factory,
    private val chatCompletionsPath: String,
    clock: KoogClock = KoogClock.System,
) : OpenAILLMClient(
    apiKey = apiKey,
    settings = settings,
    httpClientFactory = httpClientFactory,
    clock = clock,
) {

    private val responsesAPIPath: String = chatCompletionsPath.replace("chat/completions", "responses")

    override val clientName: String = "MederiOpenAILLMClient"

    override fun llmProvider(): LLMProvider = LLMProvider.OpenAI

    // ------------------------------------------------------------------
    // 非流式 execute
    // ------------------------------------------------------------------

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): Message.Assistant {
        DebugLog.section("SSE", "MederiOpenAILLMClient.execute (non-streaming)")
        DebugLog.data("SSE", "model", model.id)
        DebugLog.data("SSE", "messages", prompt.messages.size)
        DebugLog.data("SSE", "tools", tools.size)

        if (!model.supports(LLMCapability.OpenAIEndpoint.Completions)) {
            DebugLog.event("SSE", "routing to Responses API (model lacks Completions capability)")
            return executeResponsesAPI(prompt, model, tools)
        }

        val messages = convertPromptToMessages(prompt, model)
        val llmTools = tools.map { it.toOpenAIChatTool() }
        val toolChoice = prompt.params.toolChoice?.toOpenAIToolChoice()
        val requestJson = super.serializeProviderChatRequest(
            messages = messages,
            model = model,
            tools = llmTools.takeIf { it.isNotEmpty() },
            toolChoice = toolChoice,
            params = prompt.params,
            stream = false
        )
        DebugLog.event("SSE", "POST $chatCompletionsPath (stream=false)")

        val rawResponse = try {
            httpClient.post<String, String>(
                path = chatCompletionsPath,
                requestBody = requestJson,
                requestBodyType = String::class,
                responseType = String::class
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.error("SSE", "HTTP POST failed: ${e.message}", e)
            throw LLMClientException(clientName, e.message, e)
        }
        DebugLog.data("SSE", "response length", rawResponse.length)

        val response = sanitizeJson.decodeFromString<SanitizedChatCompletionResponse>(rawResponse)
        require(response.choices.isNotEmpty()) { "Empty choices in response" }

        val choice = response.choices[0]
        val parts = buildList {
            choice.message.effectiveReasoning
                ?.takeIf { it.isNotBlank() }
                ?.let { add(MessagePart.Reasoning(content = listOf(it))) }
            choice.message.content
                ?.takeIf { it.isNotBlank() }
                ?.let { add(MessagePart.Text(it)) }
            choice.message.toolCalls?.forEach { toolCall ->
                add(
                    MessagePart.Tool.Call(
                        id = toolCall.id,
                        tool = toolCall.function.name,
                        args = toolCall.function.arguments
                    )
                )
            }
        }
        DebugLog.event("SSE", "response parsed: parts=${parts.size}, finishReason=${choice.finishReason}")

        return Message.Assistant(
            parts = parts,
            metaInfo = createMetaInfoInternal(response.usage),
            finishReason = choice.finishReason
        )
    }

    // ------------------------------------------------------------------
    // 流式 executeStreaming
    // ------------------------------------------------------------------

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): Flow<StreamFrame> {
        DebugLog.section("SSE", "MederiOpenAILLMClient.executeStreaming")
        DebugLog.data("SSE", "model", model.id)
        DebugLog.data("SSE", "messages", prompt.messages.size)
        DebugLog.data("SSE", "tools", tools.size)
        DebugLog.data("SSE", "capabilities", model.capabilities)

        // Responses API：Ktor SSE 插件在部分端点挂起（父类 executeStreaming 用 httpClient.sse()，
        // incoming.collect 不关闭），且父类 Responses 响应模型 text 字段非空会反序列化失败。
        // 统一走自建 SSE 解析（httpClient.lines()），真流式逐事件发帧，绝不等待全量响应。
        if (!model.supports(LLMCapability.OpenAIEndpoint.Completions)) {
            DebugLog.event("SSE", "routing to Responses API (model lacks Completions capability)")
            return executeStreamingResponsesAPI(prompt, model, tools)
        }

        // Chat Completions - SanitizedModels 解析 + lines()/takeWhile 修复 SSE 挂起
        val messages = convertPromptToMessages(prompt, model)
        val llmTools = tools.map { it.toOpenAIChatTool() }
        val toolChoice = prompt.params.toolChoice?.toOpenAIToolChoice()
        val requestJson = super.serializeProviderChatRequest(
            messages = messages,
            model = model,
            tools = llmTools.takeIf { it.isNotEmpty() },
            toolChoice = toolChoice,
            params = prompt.params,
            stream = true
        )
        DebugLog.event("SSE", "POST $chatCompletionsPath (stream=true)")

        return flow {
            val timing = StreamTimingLog("SSE-Timing", "chat lines")
            var sawDone = false
            var bytesReceived = 0L
            var linesReceived = 0
            try {
                emitAll(
                    httpClient.lines(
                        path = chatCompletionsPath,
                        requestBody = requestJson,
                        requestBodyType = String::class,
                        headers = mapOf(
                            "Accept" to "text/event-stream",
                            "Cache-Control" to "no-cache",
                            "Content-Type" to "application/json"
                        )
                    )
                        .onEach {
                            timing.sample(it.length)
                            bytesReceived += it.length
                            linesReceived++
                        }
                        // SSE 注释行（`: ping` / `: OPENROUTER PROCESSING` 等 keep-alive）与
                        // event:/id:/retry: 行不是 JSON——不过滤直接解码会抛异常炸断整条流。
                        // 长推理的静默期正是端点发注释行的窗口，症状 = 推理中途断流。
                        // 与 Responses 路径（executeStreamingResponsesAPI）对齐。
                        .filter { it.startsWith("data:") }
                        .map { line -> line.removePrefix("data:").trim() }
                        .onEach { if (it == "[DONE]") sawDone = true }
                        .takeWhile { data -> data != "[DONE]" }
                        .onCompletion { error ->
                            // 区分三种结束模式：正常([DONE]) / 提前断连(无[DONE]无异常) / 异常断连
                            val mode = when {
                                error != null -> "exception"
                                sawDone -> "done"
                                else -> "premature-close"
                            }
                            DebugLog.event(
                                "SSE",
                                "chat lines flow closed: mode=$mode, lines=$linesReceived, bytes=$bytesReceived, " +
                                    "sawDone=$sawDone, cause=${error?.message?.take(120) ?: "none"}"
                            )
                            StreamTrace.record("Stream.Summary", mapOf(
                                "event" to "flow-completion",
                                "mode" to mode,
                                "lines" to linesReceived.toString(),
                                "bytes" to bytesReceived.toString(),
                                "cause" to (error?.message?.take(120) ?: "none")
                            ))
                        }
                        .map { data -> sanitizeJson.decodeFromString<SanitizedStreamResponse>(data) }
                        .let { processStreamingFlow(it) }
                        .requireEndFrame()
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val diag = SseDiagnostics.extract(e)
                DebugLog.error(
                    "SSE",
                    "streaming failed: ${diag.summary()}, " +
                        "lines=$linesReceived, bytes=$bytesReceived, sawDone=$sawDone, " +
                        "party=${diag.responsibleParty}",
                    e
                )
                StreamTrace.record("Stream.Summary", mapOf(
                    "event" to "stream-exception",
                    "mode" to diag.failureMode.name,
                    "httpStatus" to (diag.httpStatusCode?.toString() ?: "-"),
                    "errorBody" to (diag.errorBody?.take(200) ?: "-"),
                    "exceptionType" to (diag.exceptionType ?: "-"),
                    "lines" to linesReceived.toString(),
                    "bytes" to bytesReceived.toString(),
                    "sawDone" to sawDone.toString(),
                    "party" to diag.responsibleParty
                ))
                throw LLMClientException(clientName, e.message, e)
            } finally {
                timing.close()
            }
        }
    }

    // ------------------------------------------------------------------
    // Responses API 实现（Koog internal 模型不可用，用 SanitizedResponsesAPIResponse 替代）
    // ------------------------------------------------------------------

    private suspend fun executeResponsesAPI(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): Message.Assistant {
        val requestJson = buildResponsesRequestJson(prompt, model, tools, stream = false)
        DebugLog.event("SSE", "POST $responsesAPIPath (Responses API, stream=false)")
        DebugLog.data("SSE", "request size", requestJson.length)

        val rawResponse = try {
            httpClient.post<String, String>(
                path = responsesAPIPath,
                requestBody = requestJson,
                requestBodyType = String::class,
                responseType = String::class
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.error("SSE", "Responses API POST failed: ${e.message}", e)
            throw LLMClientException(clientName, e.message, e)
        }
        DebugLog.data("SSE", "response length", rawResponse.length)

        val response = sanitizeJson.decodeFromString<SanitizedResponsesAPIResponse>(rawResponse)

        val parts = mutableListOf<MessagePart.ResponsePart>()
        var finishReason: String? = null

        for (item in response.output) {
            when (item.type) {
                "reasoning" -> {
                    parts.add(
                        MessagePart.Reasoning(
                            id = item.id,
                            content = item.content.map { it.text ?: "" },
                            summary = item.summary.map { it.text ?: "" }
                        )
                    )
                }
                "message" -> {
                    finishReason = item.status
                    for (contentPart in item.content) {
                        when (contentPart.type) {
                            "output_text" -> parts.add(MessagePart.Text(contentPart.text ?: ""))
                            "refusal" -> parts.add(MessagePart.Text(contentPart.refusal ?: ""))
                        }
                    }
                }
                "function_call" -> {
                    parts.add(
                        MessagePart.Tool.Call(
                            id = item.callId ?: "",
                            tool = item.name ?: "",
                            args = item.arguments ?: "{}"
                        )
                    )
                }
            }
        }

        val metaInfo = responsesMetaInfo(response.usage) ?: ResponseMetaInfo.Empty

        return Message.Assistant(
            parts = parts,
            finishReason = finishReason,
            metaInfo = metaInfo
        )
    }

    private fun buildResponsesRequestJson(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
        stream: Boolean
    ): String = buildJsonObject {
        put("model", model.id)
        if (tools.isNotEmpty()) {
            // Responses API 工具是扁平结构（type/name/description/parameters 在根级），
            // 与 Chat 的 {type:function, function:{...}} 嵌套不同；参数 schema 复用 Chat 的转换
            put("tools", buildJsonArray {
                tools.forEach { tool ->
                    val chatTool = tool.toOpenAIChatTool()
                    add(buildJsonObject {
                        put("type", "function")
                        put("name", chatTool.function.name)
                        chatTool.function.description?.let { put("description", it) }
                        chatTool.function.parameters?.let { put("parameters", it) }
                    })
                }
            })
        }
        put("input", buildJsonArray {
            prompt.messages.forEach { message ->
                when (message) {
                    is Message.System -> {
                        val text = message.parts.filterIsInstance<MessagePart.Text>().joinToString("\n") { it.text }
                        if (text.isNotBlank()) {
                            add(buildJsonObject {
                                put("role", "developer")
                                put("content", text)
                            })
                        }
                    }
                    is Message.User -> {
                        add(buildJsonObject {
                            put("role", "user")
                            put("content", buildJsonArray {
                                message.parts.filterIsInstance<MessagePart.Text>().forEach {
                                    add(buildJsonObject {
                                        put("type", "input_text")
                                        put("text", it.text)
                                    })
                                }
                            })
                        })
                    }
                    is Message.Assistant -> {
                        message.parts.forEach { part ->
                            when (part) {
                                is MessagePart.Text -> {
                                    add(buildJsonObject {
                                        put("role", "assistant")
                                        put("content", buildJsonArray {
                                            add(buildJsonObject {
                                                put("type", "input_text")
                                                put("text", part.text)
                                            })
                                        })
                                    })
                                }
                                is MessagePart.Tool.Call -> {
                                    add(buildJsonObject {
                                        put("type", "function_call")
                                        put("call_id", part.id ?: "")
                                        put("name", part.tool)
                                        put("arguments", part.args)
                                    })
                                }
                                is MessagePart.Reasoning -> {}
                                is MessagePart.Attachment -> {}
                            }
                        }
                    }
                }
            }
        })
        put("stream", stream)
    }.toString()

    // ------------------------------------------------------------------
    // Responses API 流式（真流式：SSE 逐事件解析，推理/正文/工具参数按增量发帧）
    // ------------------------------------------------------------------

    /**
     * Responses API 真流式。
     *
     * 请求 stream=true，SSE data 行逐条解析为 [SanitizedResponsesStreamEvent]：
     * - reasoning_text.delta / reasoning_summary_text.delta → [StreamFrame.ReasoningDelta]
     * - output_text.delta / refusal.delta → [StreamFrame.TextDelta]
     * - function_call_arguments.delta → [StreamFrame.ToolCallDelta]（call_id/name 取自 output_item.added）
     * - response.completed → usage 记账 + End 帧
     *
     * Complete 帧不手工发：Koog 的 StreamFrameFlowBuilder 在帧类型切换和 End 时
     * 自动把累计的 delta 冲刷成对应 Complete 帧，[toMessageResponse] 据此重建完整消息。
     */
    private fun executeStreamingResponsesAPI(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): Flow<StreamFrame> {
        val requestJson = buildResponsesRequestJson(prompt, model, tools, stream = true)
        DebugLog.event("SSE", "POST $responsesAPIPath (Responses API, stream=true)")

        return flow {
            val timing = StreamTimingLog("SSE-Timing", "responses events")
            var sawDone = false
            var bytesReceived = 0L
            var linesReceived = 0
            try {
                emitAll(
                    httpClient.lines(
                        path = responsesAPIPath,
                        requestBody = requestJson,
                        requestBodyType = String::class,
                        headers = mapOf(
                            "Accept" to "text/event-stream",
                            "Cache-Control" to "no-cache",
                            "Content-Type" to "application/json"
                        )
                    )
                        .onEach {
                            timing.sample(it.length)
                            bytesReceived += it.length
                            linesReceived++
                        }
                        // SSE 行有两类：`event: xxx`（事件名，JSON 里也有 type，忽略）和 `data: {...}`
                        .filter { it.startsWith("data:") }
                        .map { line -> line.removePrefix("data:").trim() }
                        .onEach { if (it == "[DONE]" || it.isEmpty()) sawDone = true }
                        .takeWhile { data -> data.isNotEmpty() && data != "[DONE]" }
                        .onCompletion { error ->
                            val mode = when {
                                error != null -> "exception"
                                sawDone -> "done"
                                else -> "premature-close"
                            }
                            DebugLog.event(
                                "SSE",
                                "responses lines flow closed: mode=$mode, lines=$linesReceived, bytes=$bytesReceived, " +
                                    "sawDone=$sawDone, cause=${error?.message?.take(120) ?: "none"}"
                            )
                            StreamTrace.record("Stream.Summary", mapOf(
                                "event" to "flow-completion",
                                "mode" to mode,
                                "lines" to linesReceived.toString(),
                                "bytes" to bytesReceived.toString(),
                                "cause" to (error?.message?.take(120) ?: "none")
                            ))
                        }
                        .map { data -> sanitizeJson.decodeFromString<SanitizedResponsesStreamEvent>(data) }
                        .let { processResponsesStreamingFlow(it) }
                        .requireEndFrame()
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val diag = SseDiagnostics.extract(e)
                DebugLog.error(
                    "SSE",
                    "Responses streaming failed: ${diag.summary()}, " +
                        "lines=$linesReceived, bytes=$bytesReceived, sawDone=$sawDone, " +
                        "party=${diag.responsibleParty}",
                    e
                )
                StreamTrace.record("Stream.Summary", mapOf(
                    "event" to "stream-exception",
                    "mode" to diag.failureMode.name,
                    "httpStatus" to (diag.httpStatusCode?.toString() ?: "-"),
                    "errorBody" to (diag.errorBody?.take(200) ?: "-"),
                    "exceptionType" to (diag.exceptionType ?: "-"),
                    "lines" to linesReceived.toString(),
                    "bytes" to bytesReceived.toString(),
                    "sawDone" to sawDone.toString(),
                    "party" to diag.responsibleParty
                ))
                throw LLMClientException(clientName, e.message, e)
            } finally {
                timing.close()
            }
        }
    }

    private fun processResponsesStreamingFlow(
        events: Flow<SanitizedResponsesStreamEvent>
    ): Flow<StreamFrame> = buildStreamFrameFlow {
        // output_index → function_call 元数据（call_id/name 在 added 事件里，后续 delta 只有 item_id）
        val toolMetaByIndex = HashMap<Int, SanitizedResponsesOutputItem>()
        // item_id → output_index：部分端点的 *.delta 事件不带 output_index，
        // 而 Koog 的 StreamFrameFlowBuilder 对工具调用的 index 序列不一致直接抛
        // UnexpectedPartialToolCallIndex——用 added 事件建立的映射兜底，保证自洽
        val indexByItemId = HashMap<String, Int>()

        events.collect { event ->
            DebugLog.debug("SSE-Frame", "responses event: type=${event.type}, outputIndex=${event.outputIndex}, itemId=${event.itemId}")
            when (event.type) {
                "response.output_item.added" -> {
                    val item = event.item ?: return@collect
                    DebugLog.event(
                        "SSE-Frame",
                        "responses added: type=${item.type}, id=${item.id}, outputIndex=${event.outputIndex}, " +
                            "callId=${item.callId}, name=${item.name}, argsLen=${item.arguments?.length ?: 0}"
                    )
                    val index = event.outputIndex
                    if (index != null && item.id != null) indexByItemId[item.id] = index
                    if (item.type == "function_call") {
                        if (index != null) toolMetaByIndex[index] = item
                        emitToolCallDelta(
                            id = item.callId,
                            name = item.name,
                            args = item.arguments?.takeIf { it.isNotEmpty() },
                            index = index
                        )
                    }
                }
                "response.reasoning_text.delta" -> {
                    event.delta?.let { delta ->
                        emitReasoningDelta(id = event.itemId, text = delta, index = event.outputIndex)
                    }
                }
                "response.reasoning_summary_text.delta" -> {
                    event.delta?.let { delta ->
                        emitReasoningDelta(id = event.itemId, summary = delta, index = event.outputIndex)
                    }
                }
                "response.output_text.delta", "response.refusal.delta" -> {
                    event.delta?.let { delta ->
                        emitTextDelta(delta, event.outputIndex)
                    }
                }
                "response.function_call_arguments.delta" -> {
                    event.delta?.let { delta ->
                        val resolvedIndex = event.outputIndex ?: event.itemId?.let { indexByItemId[it] }
                        val meta = resolvedIndex?.let { toolMetaByIndex[it] }
                        DebugLog.event(
                            "SSE-Frame",
                            "responses argsDelta: wireOutputIndex=${event.outputIndex}, resolvedIndex=$resolvedIndex " +
                                "(itemId=${event.itemId}), metaFound=${meta != null}, deltaLen=${delta.length}"
                        )
                        emitToolCallDelta(
                            id = meta?.callId ?: event.itemId,
                            name = meta?.name,
                            args = delta,
                            index = resolvedIndex
                        )
                    }
                }
                "response.completed", "response.incomplete" -> {
                    val response = event.response
                    val metaInfo = responsesMetaInfo(response?.usage)
                    val finishReason = response?.output
                        ?.lastOrNull { it.type == "message" }?.status
                        ?: if (event.type == "response.completed") "completed" else "incomplete"
                    DebugLog.event(
                        "SSE-Frame",
                        "responses ${event.type}: finishReason=$finishReason, " +
                            "outputTypes=${response?.output?.joinToString { it.type }}, pendingToolMeta=${toolMetaByIndex.size}"
                    )
                    emitEnd(finishReason, metaInfo)
                }
                "response.failed" -> {
                    val error = response_error(event)
                    throw LLMClientException(
                        clientName,
                        "Responses stream failed: ${error.first} ${error.second}".trim()
                    )
                }
                "error" -> {
                    throw LLMClientException(
                        clientName,
                        "Responses stream error: ${event.code.orEmpty()} ${event.message.orEmpty()}".trim()
                    )
                }
            }
        }
    }

    /** response.failed 事件：错误明细在 response.error 里（standalone error 事件才在顶层） */
    private fun response_error(event: SanitizedResponsesStreamEvent): Pair<String?, String?> =
        event.response?.error?.let { it.code to it.message } ?: (event.code to event.message)

    private fun responsesMetaInfo(usage: SanitizedResponsesUsage?): ResponseMetaInfo? =
        usage?.let {
            ResponseMetaInfo.create(
                clock,
                totalTokensCount = it.totalTokens,
                inputTokensCount = it.inputTokens ?: it.promptTokens,
                outputTokensCount = it.outputTokens ?: it.completionTokens
            )
        }

    // ------------------------------------------------------------------
    // 流式帧处理
    // ------------------------------------------------------------------

    private fun processStreamingFlow(response: Flow<SanitizedStreamResponse>): Flow<StreamFrame> =
        buildStreamFrameFlow {
            var finishReason: String? = null
            var metaInfo: ResponseMetaInfo? = null
            var toolCallDeltaCount = 0
            // 工具调用 index 归一化：部分 OpenAI 兼容端点（deepseek）只在首个 delta 带 index、
            // 后续续片省略 id 和 index。Koog 的 StreamFrameFlowBuilder 自己会合并续片
            // （id 相同 or id 空 → appendArgumentsDelta），这里只需要把 index 兜底补上，
            // 保证 index 序列自洽不抛 UnexpectedPartialToolCallIndex。切不可自己缓冲拆帧——
            // 之前尝试缓冲后续片再 flush，反而把一次调用拆成 N 个空壳 Complete 帧污染消息历史。
            var lastToolCallIndex: Int? = null

            response.collect { chunk ->
                chunk.choices.firstOrNull()?.let { choice ->
                    choice.delta.content?.let {
                        DebugLog.debug("SSE-Frame", "chat textDelta len=${it.length}")
                        emitTextDelta(it, choice.index)
                    }
                    choice.delta.effectiveReasoning?.let { reasoning ->
                        DebugLog.debug("SSE-Frame", "chat reasoningDelta len=${reasoning.length}")
                        emitReasoningDelta(text = reasoning, index = choice.index)
                    }
                    choice.delta.toolCalls?.forEach { toolCall ->
                        if (toolCall.index != null) lastToolCallIndex = toolCall.index
                        toolCallDeltaCount++
                        DebugLog.event(
                            "SSE-Frame",
                            "chat toolCallDelta #$toolCallDeltaCount: id=${toolCall.id}, " +
                                "wireIndex=${toolCall.index}, resolvedIndex=${toolCall.index ?: lastToolCallIndex}, " +
                                "name=${toolCall.function?.name}, argsLen=${toolCall.function?.arguments?.length ?: 0}"
                        )
                        emitToolCallDelta(
                            id = toolCall.id,
                            name = toolCall.function?.name,
                            args = toolCall.function?.arguments,
                            index = toolCall.index ?: lastToolCallIndex
                        )
                    }
                    choice.finishReason?.let {
                        finishReason = it
                        DebugLog.event("SSE-Frame", "chat finishReason=$it")
                    }
                }
                chunk.usage?.let { metaInfo = createMetaInfo(it) }
            }

            DebugLog.event("SSE-Frame", "chat emitEnd: finishReason=$finishReason, toolCallDeltas=$toolCallDeltaCount, usage=${metaInfo != null}")
            emitEnd(finishReason, metaInfo)
        }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private fun createMetaInfoInternal(usage: ai.koog.prompt.executor.clients.openai.base.models.OpenAIUsage?): ResponseMetaInfo =
        ResponseMetaInfo.create(
            clock,
            totalTokensCount = usage?.totalTokens,
            inputTokensCount = usage?.promptTokens,
            outputTokensCount = usage?.completionTokens,
            metadata = usage?.promptTokensDetails?.cachedTokens?.let {
                buildJsonObject { put("cachedTokens", it) }
            }
        )
}