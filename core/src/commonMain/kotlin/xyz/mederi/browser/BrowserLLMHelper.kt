package xyz.mederi.browser

import ai.koog.agents.core.tools.ToolBase
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import xyz.mederi.domain.model.AIModel
import xyz.mederi.infrastructure.koog.toAssistantMessageSafe
import xyz.mederi.provider.ApiKeyResolver
import xyz.mederi.provider.domain.model.Provider
import xyz.mederi.provider.domain.model.ReasoningLevel
import xyz.mederi.provider.infrastructure.koog.KoogClientFactory
import xyz.mederi.provider.infrastructure.koog.KoogModelBuilder
import xyz.mederi.provider.infrastructure.koog.KoogParamsBuilder
import xyz.mederi.provider.ProviderManager
import java.util.Base64

/**
 * 单次 LLM 工具调用返回的数据封装。
 */
data class BrowserToolCall(
    val id: String?,
    val name: String,
    val argsJson: String
)

/**
 * 浏览器模块 LLM 调用的结构化回复。
 */
data class BrowserLLMResponse(
    val text: String = "",
    val toolCalls: List<BrowserToolCall> = emptyList(),
    val reasoning: String? = null
)

/**
 * 浏览器模块流式 LLM 调用的事件帧。
 *
 * - [ReasoningDelta]：推理增量文本（UI 顶部推理框流式渲染）
 * - [TextDelta]：正文增量文本
 * - [Completed]：流结束，携带完整结构化回复
 */
sealed interface BrowserLLMStreamEvent {
    data class ReasoningDelta(val text: String) : BrowserLLMStreamEvent
    data class TextDelta(val text: String) : BrowserLLMStreamEvent
    data class Completed(val response: BrowserLLMResponse) : BrowserLLMStreamEvent
}

/**
 * 浏览器模块统一的 LLM 调用抽象。
 *
 * BrowserOperator / BrowserBrain 只依赖这个接口做 LLM 调用（decide / judge / report），
 * 不关心底层是真实 Koog 客户端还是测试 Fake——LLM 可注入，便于单元测试与多实现。
 *
 * @param systemPrompt 系统提示词
 * @param userPrompt 用户提示词
 * @param image 可选的页面截图字节（PNG）。实现方按自身策略决定是否真正发送
 *              （[BrowserLLMHelper] 按 aiModel.supportsImages 门控）。
 * @return assistant 回复的纯文本内容
 */
interface BrowserLLMCaller {
    suspend fun call(systemPrompt: String, userPrompt: String, image: ByteArray? = null): String

    /**
     * 携带工具描述的单次非流式 LLM 调用。
     * 默认实现转调 [call]，以兼顾仅实现 [call] 的测试 Mock（如 FakeLLMCaller）。
     */
    suspend fun callWithTools(
        systemPrompt: String,
        userPrompt: String,
        tools: List<ToolBase<*, *>>,
        image: ByteArray? = null
    ): BrowserLLMResponse {
        val text = call(systemPrompt, userPrompt, image)
        return BrowserLLMResponse(text = text, toolCalls = emptyList())
    }

    /**
     * 携带工具描述的流式 LLM 调用：逐帧推送 reasoning/text 增量，结束时发 [BrowserLLMStreamEvent.Completed]。
     * 默认实现委托非流式 [callWithTools]，发单帧 Completed——仅实现 [call] / [callWithTools]
     * 的测试 Mock（如 FakeLLMCaller / NativeToolLLMCaller）自动回退，无需改动。
     */
    suspend fun callWithToolsStreaming(
        systemPrompt: String,
        userPrompt: String,
        tools: List<ToolBase<*, *>>,
        image: ByteArray? = null
    ): Flow<BrowserLLMStreamEvent> = flow {
        emit(BrowserLLMStreamEvent.Completed(callWithTools(systemPrompt, userPrompt, tools, image)))
    }
}

/**
 * 浏览器模块统一的 LLM 调用辅助工具（默认实现，真实 Koog 客户端）。
 * 负责解析 provider、创建 LLMClient 并构建 Koog Prompt 发起调用。
 *
 * vision 门控：当且仅当 `image != null && aiModel.supportsImages` 时，把截图以
 * data URL 图片 part 拼进 user 消息；否则只发纯文本。
 *
 * 由 BrowserOperator 与 BrowserBrain 共用，消除重复代码。
 */
open class BrowserLLMHelper(
    private val providerManager: ProviderManager,
    private val aiModel: AIModel,
    private val reasoningLevel: ReasoningLevel,
    private val apiKeyId: String? = null
) : BrowserLLMCaller {

    var provider: Provider? = null
        private set

    private var client: LLMClient? = null

    // API Key 唯一真理源解析器（记忆为进程级静态共享，与 TurnExecutor 的实例共享同一份记忆）
    private val apiKeyResolver = ApiKeyResolver(providerManager)

    /**
     * 根据当前 [aiModel] 动态解析归属的 Provider 和 API Key 并实例化 LLMClient。
     */
    suspend fun resolveClient(): LLMClient? {
        val p = providerManager.listWithoutKeys()
            .firstOrNull { it.models.any { m -> m.id == aiModel.id } }
            ?: return null
        // resolve 无 key 时抛 IllegalStateException，runCatching 兜住 → 返回 null，
        // 保留 call() 的 "Failed to resolve LLM client" 降级语义。
        val apiKey = runCatching { apiKeyResolver.resolve(p.id, apiKeyId) }.getOrNull()?.value
            ?: return null
        provider = p
        return KoogClientFactory.create(p, apiKey)
    }

    private suspend fun getOrCreateClient(): LLMClient? {
        if (client == null) {
            client = resolveClient()
        }
        return client
    }

    /**
     * 单次非流式 LLM 调用；client 解析失败不抛异常，返回约定的错误字符串。
     */
    override suspend fun call(systemPrompt: String, userPrompt: String, image: ByteArray?): String {
        val activeClient = getOrCreateClient() ?: return "Failed to resolve LLM client"

        val p = provider ?: error("provider not resolved")
        val koogModel = KoogModelBuilder.build(aiModel, p.type)
        val params = KoogParamsBuilder.build(
            type = p.type,
            reasoningLevel = reasoningLevel,
            reasoningParameter = p.reasoningParameter,
            maxTokens = aiModel.maxTokens
        )

        // vision 门控：模型支持图片且拿到截图时才拼 image part
        val sendImage = image != null && aiModel.supportsImages
        val koogPrompt = prompt("browser_llm", params = params) {
            system(systemPrompt)
            if (sendImage) {
                user {
                    text(userPrompt)
                    // 注意：koog DSL 的 image(url: String) 便捷重载只接受 http(s):// URL
                    // （内部 urlFileData() 正则校验），data URL 必须走 AttachmentSource.Image 直构。
                    image(
                        AttachmentSource.Image(
                            content = AttachmentContent.URL(
                                "data:image/png;base64,${Base64.getEncoder().encodeToString(image!!)}"
                            ),
                            format = "png"
                        )
                    )
                }
            } else {
                user(userPrompt)
            }
        }
        return extractText(activeClient.execute(koogPrompt, koogModel))
    }

    /**
     * 携带工具描述的单次非流式 LLM 调用。
     */
    override suspend fun callWithTools(
        systemPrompt: String,
        userPrompt: String,
        tools: List<ToolBase<*, *>>,
        image: ByteArray?
    ): BrowserLLMResponse {
        val activeClient = getOrCreateClient() ?: return BrowserLLMResponse(text = "Failed to resolve LLM client")

        val p = provider ?: error("provider not resolved")
        val koogModel = KoogModelBuilder.build(aiModel, p.type)
        val params = KoogParamsBuilder.build(
            type = p.type,
            reasoningLevel = reasoningLevel,
            reasoningParameter = p.reasoningParameter,
            maxTokens = aiModel.maxTokens
        )

        val sendImage = image != null && aiModel.supportsImages
        val koogPrompt = prompt("browser_llm", params = params) {
            system(systemPrompt)
            if (sendImage) {
                user {
                    text(userPrompt)
                    image(
                        AttachmentSource.Image(
                            content = AttachmentContent.URL(
                                "data:image/png;base64,${Base64.getEncoder().encodeToString(image!!)}"
                            ),
                            format = "png"
                        )
                    )
                }
            } else {
                user(userPrompt)
            }
        }

        val assistant = activeClient.execute(koogPrompt, koogModel, tools = tools.map { it.descriptor })
        val text = extractText(assistant)
        val toolCalls = assistant.parts.filterIsInstance<MessagePart.Tool.Call>().map {
            BrowserToolCall(
                id = it.id,
                name = it.tool,
                argsJson = it.args
            )
        }
        val reasoning = assistant.parts.filterIsInstance<MessagePart.Reasoning>().firstOrNull()?.content?.joinToString("\n")
        return BrowserLLMResponse(
            text = text,
            toolCalls = toolCalls,
            reasoning = reasoning
        )
    }

    /**
     * 携带工具描述的流式 LLM 调用（真实 Koog 流式路径）。
     *
     * 解析 client / prompt / model / params 与 [callWithTools] 完全一致；随后
     * `executeStreaming` 逐帧收集：reasoning/text 增量经 120ms 窗口聚合实时推出，
     * End 帧时用 [toAssistantMessageSafe] 重建最终结构化回复并发 [BrowserLLMStreamEvent.Completed]。
     */
    override suspend fun callWithToolsStreaming(
        systemPrompt: String,
        userPrompt: String,
        tools: List<ToolBase<*, *>>,
        image: ByteArray?
    ): Flow<BrowserLLMStreamEvent> = flow {
        val activeClient = getOrCreateClient()
        if (activeClient == null) {
            emit(BrowserLLMStreamEvent.Completed(BrowserLLMResponse(text = "Failed to resolve LLM client")))
            return@flow
        }

        val p = provider ?: error("provider not resolved")
        val koogModel = KoogModelBuilder.build(aiModel, p.type)
        val params = KoogParamsBuilder.build(
            type = p.type,
            reasoningLevel = reasoningLevel,
            reasoningParameter = p.reasoningParameter,
            maxTokens = aiModel.maxTokens
        )

        // vision 门控：模型支持图片且拿到截图时才拼 image part（与 callWithTools 一致）
        val sendImage = image != null && aiModel.supportsImages
        val koogPrompt = prompt("browser_llm", params = params) {
            system(systemPrompt)
            if (sendImage) {
                user {
                    text(userPrompt)
                    image(
                        AttachmentSource.Image(
                            content = AttachmentContent.URL(
                                "data:image/png;base64,${Base64.getEncoder().encodeToString(image!!)}"
                            ),
                            format = "png"
                        )
                    )
                }
            } else {
                user(userPrompt)
            }
        }

        val frames = mutableListOf<StreamFrame>()
        val batcher = StreamingDeltaBatcher()
        activeClient.executeStreaming(koogPrompt, koogModel, tools = tools.map { it.descriptor }).collect { frame ->
            frames.add(frame)
            when (frame) {
                is StreamFrame.TextDelta -> batcher.add("text", frame.text)?.let { emit(it) }
                is StreamFrame.ReasoningDelta -> {
                    // summary-only delta（Responses API 的 reasoning summary）与 text 一样作为推理内容转发
                    val reasoning = frame.text?.takeIf { it.isNotEmpty() }
                        ?: frame.summary?.takeIf { it.isNotEmpty() }
                    if (reasoning != null) batcher.add("reasoning", reasoning)?.let { emit(it) }
                }
                is StreamFrame.ToolCallDelta, is StreamFrame.ToolCallComplete, is StreamFrame.End -> {
                    // 工具调用帧不产生 text/reasoning 增量：冲刷残留（参数 JSON 碎片不进 UI 流）
                    batcher.flushNow()?.let { emit(it) }
                }
                // Complete 帧（ReasoningComplete/TextComplete）只进 frames 供 End 时重建，不产生增量
                else -> {}
            }
            batcher.flushIfDue()?.let { emit(it) }
        }

        // 用完整帧列表重建最终响应（提取逻辑与 callWithTools 一致）
        val assistant = frames.toAssistantMessageSafe()
        val text = extractText(assistant)
        val toolCalls = assistant.parts.filterIsInstance<MessagePart.Tool.Call>().map {
            BrowserToolCall(
                id = it.id,
                name = it.tool,
                argsJson = it.args
            )
        }
        val reasoning = assistant.parts.filterIsInstance<MessagePart.Reasoning>().firstOrNull()?.content?.joinToString("\n")
        emit(BrowserLLMStreamEvent.Completed(BrowserLLMResponse(text = text, toolCalls = toolCalls, reasoning = reasoning)))
    }

    companion object {
        /**
         * 提取 Assistant 回复的所有文本内容。
         */
        fun extractText(assistant: Message.Assistant): String {
            return assistant.parts
                .filterIsInstance<MessagePart.Text>()
                .joinToString("") { it.text }
                .trim()
        }
    }
}

/**
 * 流式增量窗口聚合器（纯逻辑，参考 SubagentProgressBatch 的 120ms throttle 思路）。
 *
 * 规则：
 * - 同 kind（"text" / "reasoning"）增量先落入 [buf]，距上次 flush ≥ [throttleMillis] 时
 *   [flushIfDue] 才真正产出一帧（窗口吸收高频增量）；
 * - kind 切换（reasoning → text 或反之）时立即冲刷残留（[add] 返回切换冲刷帧）；
 * - 尾部不丢：流结束（End 帧）前调用 [flushNow] 冲刷残留。
 */
private class StreamingDeltaBatcher(
    private val throttleMillis: Long = 120L
) {

    private val buf = StringBuilder()
    private var kind: String? = null
    private var lastFlush = 0L

    /**
     * 喂入一段增量。返回 kind 切换时冲刷出的残留帧（无则 null）。
     */
    fun add(nextKind: String, delta: String): BrowserLLMStreamEvent? {
        if (delta.isEmpty()) return null
        val switched = if (kind != null && kind != nextKind) flushNow() else null
        kind = nextKind
        buf.append(delta)
        return switched
    }

    /**
     * 窗口到期时产出一帧；未到期或无残留返回 null。调用方在每个事件处理后调用一次即可。
     */
    fun flushIfDue(): BrowserLLMStreamEvent? {
        if (kind == null) return null
        val now = System.currentTimeMillis()
        if (now - lastFlush < throttleMillis) return null
        return flush(now)
    }

    /** 无条件冲刷（尾部兜底）：有未 flush 内容时产出一帧，否则 null。 */
    fun flushNow(): BrowserLLMStreamEvent? {
        if (kind == null) return null
        return flush(System.currentTimeMillis())
    }

    private fun flush(now: Long): BrowserLLMStreamEvent {
        val event = if (kind == "reasoning") {
            BrowserLLMStreamEvent.ReasoningDelta(buf.toString())
        } else {
            BrowserLLMStreamEvent.TextDelta(buf.toString())
        }
        lastFlush = now
        buf.setLength(0)
        kind = null
        return event
    }
}