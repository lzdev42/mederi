package xyz.mederi.provider.infrastructure.koog.sanitize

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.KoogHttpClientException
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.LLMClientException
import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIUsage
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.StreamFrameFlowBuilder
import ai.koog.prompt.streaming.buildStreamFrameFlow
import ai.koog.prompt.streaming.emitEnd
import ai.koog.prompt.streaming.emitReasoningDelta
import ai.koog.prompt.streaming.emitTextDelta
import ai.koog.prompt.streaming.emitToolCallDelta
import ai.koog.prompt.streaming.requireEndFrame
import ai.koog.utils.time.KoogClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.timeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ai.koog.agents.core.tools.ToolDescriptor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import xyz.mederi.config.MederiIdentity
import xyz.mederi.debug.DebugLog
import xyz.mederi.debug.StreamCloseDiagnostics
import xyz.mederi.debug.StreamTimingLog
import xyz.mederi.debug.StreamTrace
import xyz.mederi.http.SseIdleTimeoutException
import xyz.mederi.tools.patch.ApplyPatchProtocol
import java.net.URI

class MederiOpenAILLMClient(
    apiKey: String,
    settings: OpenAIClientSettings,
    httpClientFactory: KoogHttpClient.Factory,
    private val chatCompletionsPath: String,
    clock: KoogClock = KoogClock.System,
    private val chatGptPlan: Boolean = false,
    private val accessTokenProvider: (suspend () -> String)? = null,
) : OpenAILLMClient(
    apiKey = apiKey,
    settings = settings,
    httpClientFactory = httpClientFactory,
    clock = clock,
) {

    private val responsesAPIPath: String = chatCompletionsPath.replace("chat/completions", "responses")

    // Custom tools are an OpenAI Responses feature, not part of the compatible-server contract.
    private val supportsCustomApplyPatch = runCatching {
        fun isOfficialOrigin(uri: URI): Boolean = uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals("api.openai.com", ignoreCase = true) && uri.userInfo == null &&
            (uri.port == -1 || uri.port == 443)
        val endpoint = URI(chatCompletionsPath)
        isOfficialOrigin(URI(settings.baseUrl)) &&
            (!endpoint.isAbsolute && endpoint.rawAuthority == null || isOfficialOrigin(endpoint))
    }.getOrDefault(false)

    override val clientName: String = "MederiOpenAILLMClient"

    /**
     * 从 Prompt.id 提取 prompt_cache_key（OpenAI 请求级前缀缓存键）。
     *
     * Prompt.id 由 TurnExecutor 设为 Session.id（Mederi 会话 ID），两者是同一个值——
     * Prompt.id 是 Koog 层的载体，Session.id 是 Mederi 层的载体，值一致。
     * 为空时不发送（返回 null → Koog explicitNulls=false 自动省略该字段）。
     */
    private fun promptCacheKey(prompt: Prompt): String? =
        prompt.id.takeIf { it.isNotBlank() }

    private fun withChatPromptCacheKey(prompt: Prompt): LLMParams =
        (prompt.params as? OpenAIChatParams)
            ?.let { p -> promptCacheKey(prompt)?.let { p.copy(promptCacheKey = it) } }
            ?: prompt.params

    companion object {
        /**
         * SSE 空闲超时：连续这么久没收到**任何一行**（含 keep-alive 注释行 `: ping` /
         * `: OPENROUTER PROCESSING` 等）才判定连接已死。
         *
         * 语义：只要 SSE 还在吐数据（哪怕两帧之间卡几十秒、哪怕一次推理跑一两个小时），
         * 就永远不超时；只有连接真正没数据到达才计时。Koog/Ktor 的 requestTimeout 和
         * socketTimeout 已在 [xyz.mederi.http.MederiHttpClientFactory] 禁用（设为 0），
         * 这里是我们自己的唯一超时判定。
         */
        val SSE_IDLE_TIMEOUT: Duration = 90.seconds
    }

    /**
     * 最近一次流式请求的关闭诊断——`onCompletion` 写入，[xyz.mederi.koog.TurnExecutor] 断流时读取。
     * 包含流结束模式（premature-close = 服务端关连接 / exception = 客户端网络异常）、
     * 最后 N 条原始 SSE 行、非 data: 错误信号行、时间统计。
     */
    @Volatile
    var lastStreamDiagnostics: StreamCloseDiagnostics? = null

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

        if (chatGptPlan) {
            // SIWC 不接受非流式 HTTP。压缩/改名等 execute 调用仍走同一 SSE 协议，
            // 只在内部收集完整帧，不把 delta 当作正式完整响应。
            return collectChatGptResponse(executeStreamingResponsesAPI(prompt, model, tools))
        }
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
            params = withChatPromptCacheKey(prompt),
            stream = false
        )
        DebugLog.event("SSE", "POST $chatCompletionsPath (stream=false)")

        val rawResponse = postJson(chatCompletionsPath, requestJson, "HTTP POST failed")
        DebugLog.data("SSE", "response length", rawResponse.length)

        checkChatResponseError(rawResponse)
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
            metaInfo = chatMetaInfo(response.usage),
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
        if (chatGptPlan || !model.supports(LLMCapability.OpenAIEndpoint.Completions)) {
            DebugLog.event("SSE", "routing to Responses API (model lacks Completions capability)")
            return executeStreamingResponsesAPI(prompt, model, tools)
        }

        // Chat Completions - SanitizedModels 解析 + 共享 SSE 管线
        val messages = convertPromptToMessages(prompt, model)
        val llmTools = tools.map { it.toOpenAIChatTool() }
        val toolChoice = prompt.params.toolChoice?.toOpenAIToolChoice()
        val requestJson = super.serializeProviderChatRequest(
            messages = messages,
            model = model,
            tools = llmTools.takeIf { it.isNotEmpty() },
            toolChoice = toolChoice,
            params = withChatPromptCacheKey(prompt),
            stream = true
        )
        DebugLog.event("SSE", "POST $chatCompletionsPath (stream=true)")

        return streamSseFrames(
            path = chatCompletionsPath,
            requestBody = requestJson,
            timingLabel = "chat lines",
            logPrefix = "chat",
            stopOnEmptyData = false,
        ) { dataLines ->
            dataLines
                .map { data ->
                    checkChatResponseError(data)
                    sanitizeJson.decodeFromString<SanitizedStreamResponse>(data)
                }
                .let { processStreamingFlow(it) }
                .requireEndFrame()
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

        val rawResponse = postJson(responsesAPIPath, requestJson, "Responses API POST failed")
        DebugLog.data("SSE", "response length", rawResponse.length)

        checkResponsesResponseError(rawResponse)
        val response = sanitizeJson.decodeFromString<SanitizedResponsesAPIResponse>(rawResponse)
        if (response.output.any { it.type == "custom_tool_call" }) {
            require(response.status == null || response.status == "completed") { "Incomplete apply_patch response: ${response.status}" }
            require(response.output.none { it.type == "custom_tool_call" && it.status != null && it.status != "completed" }) {
                "Incomplete apply_patch output item"
            }
        }

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
                "function_call", "custom_tool_call" -> {
                    val isCustom = item.type == "custom_tool_call"
                    require(!isCustom || item.name == ApplyPatchProtocol.TOOL_NAME) { "Unsupported custom tool: ${item.name}" }
                    parts.add(
                        MessagePart.Tool.Call(
                            id = item.callId ?: "",
                            tool = item.name ?: "",
                            args = if (isCustom) ApplyPatchProtocol.encodeArguments(requireNotNull(item.input) { "Missing apply_patch input" })
                                else item.arguments ?: "{}"
                        )
                    )
                }
            }
        }
        if (response.status == "incomplete") {
            finishReason = "incomplete"
        }

        val metaInfo = responsesMetaInfo(response.usage) ?: ResponseMetaInfo.Empty

        return Message.Assistant(
            parts = parts,
            finishReason = finishReason,
            metaInfo = metaInfo
        )
    }

    private suspend fun collectChatGptResponse(stream: Flow<StreamFrame>): Message.Assistant {
        val parts = mutableListOf<MessagePart.ResponsePart>()
        var completed: StreamFrame.End? = null
        stream.requireEndFrame().collect { frame ->
            when (frame) {
                is StreamFrame.TextComplete -> parts.add(MessagePart.Text(frame.text))
                is StreamFrame.ReasoningComplete -> parts.add(MessagePart.Reasoning(
                    id = frame.id, content = frame.content, summary = frame.summary.orEmpty(),
                ))
                is StreamFrame.ToolCallComplete -> parts.add(MessagePart.Tool.Call(
                    id = frame.id, tool = frame.name, args = frame.content,
                ))
                is StreamFrame.End -> completed = frame
                else -> Unit
            }
        }
        val end = requireNotNull(completed) { "ChatGPT response did not complete" }
        return Message.Assistant(parts = parts, metaInfo = end.metaInfo, finishReason = end.finishReason)
    }

    internal fun buildResponsesRequestJson(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
        stream: Boolean
    ): String = buildJsonObject {
        put("model", model.id)
        promptCacheKey(prompt)?.let { put("prompt_cache_key", it) }
        if (tools.isNotEmpty()) {
            // Responses API 工具是扁平结构（type/name/description/parameters 在根级），
            // 与 Chat 的 {type:function, function:{...}} 嵌套不同；参数 schema 复用 Chat 的转换
            val declaredTools = buildJsonArray {
                tools.forEach { tool ->
                    val chatTool = tool.toOpenAIChatTool()
                    add(buildJsonObject {
                        val isCustom = supportsCustomApplyPatch && tool.name == ApplyPatchProtocol.TOOL_NAME
                        put("type", if (isCustom) "custom" else "function")
                        put("name", chatTool.function.name)
                        if (isCustom) {
                            put("description", ApplyPatchProtocol.FREEFORM_DESCRIPTION)
                            put("format", buildJsonObject {
                                put("type", "grammar")
                                put("syntax", "lark")
                                put("definition", ApplyPatchProtocol.LARK_GRAMMAR)
                            })
                        } else {
                            chatTool.function.description?.let { put("description", it) }
                            chatTool.function.parameters?.let { put("parameters", it) }
                        }
                    })
                }
            }
            put("tools", if (chatGptPlan) buildJsonArray {
                add(buildJsonObject {
                    put("type", "namespace")
                    put("name", MederiIdentity.PRODUCT_NAME)
                    put("description", "${MederiIdentity.displayName} tools")
                    put("tools", declaredTools)
                })
            } else declaredTools)
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
                        // Tool results are user parts in Koog. Preserve their causal position;
                        // treating them as ordinary text silently drops the result on the next request.
                        val texts = mutableListOf<MessagePart.Text>()
                        fun flushText() {
                            if (texts.isEmpty()) return
                            add(buildJsonObject {
                                put("role", "user")
                                put("content", buildJsonArray {
                                    texts.forEach { text -> add(buildJsonObject {
                                        put("type", "input_text")
                                        put("text", text.text)
                                    }) }
                                })
                            })
                            texts.clear()
                        }
                        message.parts.forEach { part ->
                            when (part) {
                                is MessagePart.Text -> texts.add(part)
                                is MessagePart.Tool.Result -> {
                                    flushText()
                                    add(buildJsonObject {
                                        val custom = supportsCustomApplyPatch && part.tool == ApplyPatchProtocol.TOOL_NAME
                                        put("type", if (custom) "custom_tool_call_output" else "function_call_output")
                                        put("call_id", part.id ?: "")
                                        put("output", part.output)
                                    })
                                }
                                else -> {}
                            }
                        }
                        flushText()
                    }
                    is Message.Assistant -> {
                        message.parts.forEach { part ->
                            when (part) {
                                is MessagePart.Text -> {
                                    add(buildJsonObject {
                                        put("role", "assistant")
                                        put("content", buildJsonArray {
                                            add(buildJsonObject {
                                                put("type", "output_text")
                                                put("text", part.text)
                                            })
                                        })
                                    })
                                }
                                is MessagePart.Tool.Call -> {
                                    add(buildJsonObject {
                                        val custom = supportsCustomApplyPatch && part.tool == ApplyPatchProtocol.TOOL_NAME
                                        put("type", if (custom) "custom_tool_call" else "function_call")
                                        put("call_id", part.id ?: "")
                                        put("name", part.tool)
                                        if (chatGptPlan) put("namespace", MederiIdentity.PRODUCT_NAME)
                                        if (custom) put("input", ApplyPatchProtocol.decodeArguments(part.args))
                                        else put("arguments", part.args)
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
        if (chatGptPlan) put("store", false)
        put("stream", stream || chatGptPlan)
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
     * text/reasoning 由 Koog builder 生成 Complete；工具按 output_index 独立累计，
     * 在 done/completed 事件确认后生成唯一 ToolCallComplete，避免并行工具或正文交错时
     * 单 pending 槽把未完成补丁提前冲成非法 JSON。所有输入 delta 仍在到达时立刻外显。
     */
    private fun executeStreamingResponsesAPI(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): Flow<StreamFrame> {
        val requestJson = buildResponsesRequestJson(prompt, model, tools, stream = true)
        DebugLog.event("SSE", "POST $responsesAPIPath (Responses API, stream=true)")

        return streamSseFrames(
            path = responsesAPIPath,
            requestBody = requestJson,
            timingLabel = "responses events",
            logPrefix = "responses",
            extraHeaders = {
                // 每次实际收集（包括重试）重新获取凭据；不能把首次 access token
                // 固定在 client 的默认头里让长任务过期后仍反复发送旧值。
                if (chatGptPlan) accessTokenProvider?.invoke()?.let {
                    mapOf("Authorization" to "Bearer $it")
                }.orEmpty() else emptyMap()
            },
            stopOnEmptyData = true,
        ) { dataLines ->
            dataLines
                .map { data ->
                    checkResponsesResponseError(data)
                    sanitizeJson.decodeFromString<SanitizedResponsesStreamEvent>(data)
                }
                .let { processResponsesStreamingFlow(it) }
                .requireEndFrame()
        }
    }

    internal fun processResponsesStreamingFlow(
        events: Flow<SanitizedResponsesStreamEvent>
    ): Flow<StreamFrame> = flow {
        val output = this
        // The Koog builder has one pending tool slot. Responses may interleave tool
        // indices and commentary, so tools are accumulated independently below. The
        // builder still owns text/reasoning completions and flushes at tool boundaries.
        val frames = StreamFrameFlowBuilder(output)
        val toolMetaByIndex = HashMap<Int, SanitizedResponsesOutputItem>()
        val indexByItemId = HashMap<String, Int>()
        val customInputs = HashMap<Int, ApplyPatchProtocol.StreamingArguments>()
        val functionInputs = HashMap<Int, StringBuilder>()
        val completedTools = mutableSetOf<Int>()

        suspend fun emitToolFrame(frame: StreamFrame) {
            frames.tryEmitPendingText()
            frames.tryEmitPendingReasoning()
            output.emit(frame)
        }

        fun indexOf(event: SanitizedResponsesStreamEvent): Int? =
            event.outputIndex ?: event.itemId?.let { indexByItemId[it] }
                ?: event.item?.id?.let { indexByItemId[it] }

        suspend fun completeTool(index: Int, finalItem: SanitizedResponsesOutputItem? = null, finalInput: String? = null) {
            // input.done can precede output_item.done; validate final item status even
            // when its arguments were already completed by the earlier event.
            if (index in customInputs) {
                require(finalItem?.status == null || finalItem.status == "completed") { "Incomplete apply_patch output item" }
            }
            if (index in completedTools) {
                customInputs[index]?.completeArguments(finalInput ?: finalItem?.input)
                return
            }
            val meta = requireNotNull(toolMetaByIndex[index]) { "Tool completion without an added item" }
            val encoder = customInputs[index]
            val arguments = if (encoder != null) {
                val suffix = encoder.close(finalInput ?: finalItem?.input)
                emitToolFrame(StreamFrame.ToolCallDelta(meta.callId, meta.name, suffix, index))
                encoder.completeArguments()
            } else {
                val received = requireNotNull(functionInputs[index])
                finalItem?.arguments?.let { final ->
                    require(final.startsWith(received.toString())) { "Function arguments disagree with streamed deltas" }
                    val suffix = final.substring(received.length)
                    if (suffix.isNotEmpty()) {
                        received.append(suffix)
                        emitToolFrame(StreamFrame.ToolCallDelta(meta.callId, meta.name, suffix, index))
                    }
                }
                received.toString().ifEmpty { "{}" }
            }
            emitToolFrame(StreamFrame.ToolCallComplete(meta.callId, requireNotNull(meta.name), arguments, index))
            completedTools.add(index)
        }

        events.collect { event ->
            when (event.type) {
                "response.output_item.added" -> {
                    val item = event.item ?: return@collect
                    if (item.type == "function_call" || item.type == "custom_tool_call") {
                        val index = event.outputIndex ?: (toolMetaByIndex.keys.maxOrNull()?.plus(1) ?: 0)
                        require(index !in toolMetaByIndex) { "Duplicate tool output index: $index" }
                        item.id?.let { indexByItemId[it] = index }
                        toolMetaByIndex[index] = item
                        val args = if (item.type == "custom_tool_call") {
                            require(item.name == ApplyPatchProtocol.TOOL_NAME) { "Unsupported custom tool: ${item.name}" }
                            ApplyPatchProtocol.StreamingArguments().also { customInputs[index] = it }.open(item.input.orEmpty())
                        } else {
                            item.arguments.orEmpty().also { functionInputs[index] = StringBuilder(it) }
                        }
                        emitToolFrame(StreamFrame.ToolCallDelta(item.callId, item.name, args, index))
                    }
                }
                "response.reasoning_text.delta" -> event.delta?.takeIf { it.isNotEmpty() }?.let {
                    frames.emitReasoningDelta(id = event.itemId, text = it, index = event.outputIndex)
                }
                "response.reasoning_summary_text.delta" -> event.delta?.takeIf { it.isNotEmpty() }?.let {
                    frames.emitReasoningDelta(id = event.itemId, summary = it, index = event.outputIndex)
                }
                "response.output_text.delta", "response.refusal.delta" -> event.delta?.takeIf { it.isNotEmpty() }?.let {
                    frames.emitTextDelta(it, event.outputIndex)
                }
                "response.custom_tool_call_input.delta", "response.function_call_arguments.delta" -> {
                    val index = requireNotNull(indexOf(event)) { "Tool input delta without an added item" }
                    require(index !in completedTools) { "Tool input delta after completion" }
                    val meta = requireNotNull(toolMetaByIndex[index]) { "Tool delta without metadata" }
                    event.delta?.takeIf { it.isNotEmpty() }?.let { delta ->
                        val args = if (event.type == "response.custom_tool_call_input.delta") {
                            requireNotNull(customInputs[index]) { "Custom input for a function tool" }.append(delta)
                        } else {
                            requireNotNull(functionInputs[index]) { "Function arguments for a custom tool" }.append(delta)
                            delta
                        }
                        emitToolFrame(StreamFrame.ToolCallDelta(meta.callId, meta.name, args, index))
                    }
                }
                "response.custom_tool_call_input.done" -> {
                    completeTool(requireNotNull(indexOf(event)), finalInput = event.input)
                }
                "response.output_item.done" -> {
                    val index = indexOf(event)
                    if (index != null && index in toolMetaByIndex) completeTool(index, finalItem = event.item)
                }
                "response.completed", "response.incomplete" -> {
                    require(!chatGptPlan || event.type == "response.completed") { "Incomplete ChatGPT response" }
                    require(event.type != "response.incomplete" || customInputs.isEmpty()) { "Incomplete apply_patch response" }
                    val response = event.response
                    if (customInputs.isNotEmpty()) {
                        require(response?.status == null || response.status == "completed") { "Incomplete apply_patch response: ${response?.status}" }
                        require(response?.output.orEmpty().none {
                            it.type == "custom_tool_call" && it.status != null && it.status != "completed"
                        }) { "Incomplete apply_patch output item" }
                    }
                    toolMetaByIndex.keys.sorted().forEach { index ->
                        val meta = toolMetaByIndex.getValue(index)
                        val finalItem = response?.output?.firstOrNull { it.callId == meta.callId }
                        if (index in completedTools) {
                            customInputs[index]?.completeArguments(finalItem?.input)
                        } else {
                            if (index in customInputs) {
                                require(event.type == "response.completed" && finalItem?.input != null) { "Incomplete apply_patch input stream" }
                            }
                            completeTool(index, finalItem)
                        }
                    }
                    val finishReason = if (response?.status == "incomplete") {
                        "incomplete"
                    } else {
                        response?.output?.lastOrNull { it.type == "message" }?.status
                            ?: if (event.type == "response.completed") "completed" else "incomplete"
                    }
                    frames.emitEnd(finishReason, responsesMetaInfo(response?.usage))
                }
                "response.failed" -> {
                    val error = response_error(event)
                    throw LLMClientException(clientName, "Responses stream failed: ${error.first} ${error.second}".trim())
                }
                "error" -> throw LLMClientException(clientName,
                    "Responses stream error: ${event.code.orEmpty()} ${event.message.orEmpty()}".trim())
            }
        }
    }

    /** response.failed 事件：错误明细在 response.error 里（standalone error 事件才在顶层） */
    private fun response_error(event: SanitizedResponsesStreamEvent): Pair<String?, String?> =
        event.response?.error?.let { it.code to it.message } ?: (event.code to event.message)

    private fun responsesMetaInfo(usage: SanitizedResponsesUsage?): ResponseMetaInfo? =
        usage?.let {
            buildUsageMetaInfo(
                totalTokens = it.totalTokens,
                inputTokens = it.effectiveInputTokens,
                outputTokens = it.effectiveOutputTokens,
                cachedTokens = it.effectiveCachedTokens,
            )
        }

    // ------------------------------------------------------------------
    // 流式帧处理
    // ------------------------------------------------------------------

    /** 在成功 DTO 忽略扩展字段之前捕获原始错误；同时覆盖无 choices 的错误帧。 */
    internal fun checkChatResponseError(rawResponse: String) {
        val envelope = sanitizeJson.decodeFromString<SanitizedChatErrorEnvelope>(rawResponse)
        val error = envelope.error ?: envelope.choices.firstNotNullOfOrNull { it.error }
        if (error == null && envelope.choices.none { it.finishReason == "error" }) return
        throwProviderResponseError(rawResponse, error)
    }

    internal fun checkResponsesResponseError(rawResponse: String) {
        val envelope = sanitizeJson.decodeFromString<SanitizedResponsesErrorEnvelope>(rawResponse)
        val error = envelope.error ?: envelope.response?.error
        if (error == null && envelope.type !in setOf("error", "response.failed") &&
            envelope.status != "failed" && envelope.response?.status != "failed") return
        val details = error ?: sanitizeJson.parseToJsonElement(rawResponse) as kotlinx.serialization.json.JsonObject
        throwProviderResponseError(rawResponse, details)
    }

    private fun throwProviderResponseError(rawResponse: String, error: kotlinx.serialization.json.JsonObject?): Nothing {
        val code = (error?.get("code") as? JsonPrimitive)?.intOrNull?.takeIf { it in 400..599 }
        val message = (error?.get("message") as? JsonPrimitive)?.contentOrNull
            ?: "Provider reported an error without an error message."
        // 原始响应保存在 cause，统一分类/重试/子代理事件均可取回，不伪装成本地状态错误。
        throw LLMClientException(clientName, message,
            KoogHttpClientException(statusCode = code, errorBody = rawResponse))
    }

    // internal 以便 MederiOpenAILLMClientTest 直接驱动流式管线
    internal fun processStreamingFlow(response: Flow<SanitizedStreamResponse>): Flow<StreamFrame> =
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
                    choice.delta.content?.takeIf { it.isNotEmpty() }?.let {
                        DebugLog.debug("SSE-Frame", "chat textDelta len=${it.length}")
                        emitTextDelta(it, choice.index)
                    }
                    choice.delta.effectiveReasoning?.takeIf { it.isNotEmpty() }?.let { reasoning ->
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
                    choice.finishReason?.takeIf { it.isNotBlank() }?.let {
                        finishReason = it
                        DebugLog.event("SSE-Frame", "chat finishReason=$it")
                    }
                }
                chunk.usage?.let { metaInfo = chatMetaInfo(it) }
            }

            DebugLog.event("SSE-Frame", "chat emitEnd: finishReason=$finishReason, toolCallDeltas=$toolCallDeltaCount, usage=${metaInfo != null}")
            emitEnd(finishReason, metaInfo)
        }

    // ------------------------------------------------------------------
    // 辅助（Chat / Responses 共享传输与用量清洗）
    // ------------------------------------------------------------------

    private suspend fun postJson(
        path: String,
        requestJson: String,
        failureLabel: String,
    ): String = try {
        httpClient.post(
            path = path,
            requestBody = requestJson,
            requestBodyType = String::class,
            responseType = String::class
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DebugLog.error("SSE", "$failureLabel: ${e.message}", e)
        throw LLMClientException(clientName, e.message, e)
    }

    private fun streamSseFrames(
        path: String,
        requestBody: String,
        timingLabel: String,
        logPrefix: String,
        extraHeaders: suspend () -> Map<String, String> = { emptyMap() },
        stopOnEmptyData: Boolean,
        transformDataLines: (Flow<String>) -> Flow<StreamFrame>,
    ): Flow<StreamFrame> = flow {
        val timing = StreamTimingLog("SSE-Timing", timingLabel)
        var sawDone = false
        var bytesReceived = 0L
        var linesReceived = 0
        // 原始 SSE 行 ring buffer（data: 过滤前）+ 非 data: 错误信号行——供断流诊断
        val lastRawLines = ArrayDeque<String>()
        val errorSseLines = mutableListOf<String>()
        try {
            val authHeaders = extraHeaders()
            emitAll(
                httpClient.lines(
                    path = path,
                    requestBody = requestBody,
                    requestBodyType = String::class,
                    headers = authHeaders + mapOf(
                        "Accept" to "text/event-stream",
                        "Cache-Control" to "no-cache",
                        "Content-Type" to "application/json"
                    )
                )
                    .timeout(SSE_IDLE_TIMEOUT)
                    .catch { e ->
                        if (e is TimeoutCancellationException) {
                            DebugLog.error(
                                "SSE",
                                "SSE $logPrefix idle timeout triggered: no lines received for ${SSE_IDLE_TIMEOUT.inWholeSeconds}s (lines=$linesReceived, bytes=$bytesReceived)"
                            )
                            throw SseIdleTimeoutException(
                                "SSE idle timeout: no data received for ${SSE_IDLE_TIMEOUT.inWholeSeconds}s"
                            )
                        } else {
                            throw e
                        }
                    }
                    .onEach {
                        timing.sample(it.length)
                        bytesReceived += it.length
                        linesReceived++
                        lastRawLines.addLast(it)
                        while (lastRawLines.size > 5) lastRawLines.removeFirst()
                        if (!it.startsWith("data:") && it.isNotBlank()) {
                            val lower = it.lowercase()
                            if (lower.contains("error") || it.startsWith("event:")) {
                                if (errorSseLines.size < 10) errorSseLines.add(it)
                            }
                        }
                    }
                    // SSE 注释行（`: ping` / `: OPENROUTER PROCESSING` 等 keep-alive）与
                    // event:/id:/retry: 行不是 JSON——不过滤直接解码会抛异常炸断整条流。
                    .filter { it.startsWith("data:") }
                    .map { line -> line.removePrefix("data:").trim() }
                    .onEach { data ->
                        if (data == "[DONE]" || (stopOnEmptyData && data.isEmpty())) sawDone = true
                    }
                    .takeWhile { data -> data != "[DONE]" && (!stopOnEmptyData || data.isNotEmpty()) }
                    .onCompletion { error ->
                        val mode = when {
                            error != null -> "exception"
                            sawDone -> "done"
                            else -> "premature-close"
                        }
                        DebugLog.event(
                            "SSE",
                            "$logPrefix lines flow closed: mode=$mode, lines=$linesReceived, bytes=$bytesReceived, " +
                                "sawDone=$sawDone, cause=${error?.message?.take(120) ?: "none"}"
                        )
                        StreamTrace.record("Stream.Summary", mapOf(
                            "event" to "flow-completion",
                            "mode" to mode,
                            "lines" to linesReceived.toString(),
                            "bytes" to bytesReceived.toString(),
                            "cause" to (error?.message?.take(120) ?: "none")
                        ))
                        val ts = timing.snapshot()
                        lastStreamDiagnostics = StreamCloseDiagnostics(
                            mode = mode,
                            linesReceived = linesReceived,
                            bytesReceived = bytesReceived,
                            durationMs = ts.durationMs,
                            maxGapMs = ts.maxGapMs,
                            ttfbMs = ts.ttfbMs,
                            lastRawLines = lastRawLines.toList(),
                            errorSseLines = errorSseLines.toList(),
                            errorType = error?.let { it::class.simpleName },
                            errorMessage = error?.message?.lineSequence()?.firstOrNull()?.take(120)
                        )
                    }
                    .let(transformDataLines)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.error(
                "SSE",
                "$logPrefix streaming failed: lines=$linesReceived, bytes=$bytesReceived, sawDone=$sawDone, " +
                    "cause=${e.message?.take(120)}",
                e
            )
            throw LLMClientException(clientName, e.message, e)
        } finally {
            timing.close()
        }
    }

    private fun chatMetaInfo(usage: OpenAIUsage?): ResponseMetaInfo =
        buildUsageMetaInfo(
            totalTokens = usage?.totalTokens,
            inputTokens = usage?.promptTokens,
            outputTokens = usage?.completionTokens,
            cachedTokens = usage?.promptTokensDetails?.cachedTokens,
        )

    private fun buildUsageMetaInfo(
        totalTokens: Int?,
        inputTokens: Int?,
        outputTokens: Int?,
        cachedTokens: Int?,
    ): ResponseMetaInfo = ResponseMetaInfo.create(
        clock,
        totalTokensCount = totalTokens,
        inputTokensCount = inputTokens,
        outputTokensCount = outputTokens,
        metadata = cachedTokens?.let {
            buildJsonObject { put("cachedTokens", it) }
        }
    )
}
