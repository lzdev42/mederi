package xyz.mederi.provider.infrastructure.koog

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.MessagePart

/**
 * 一次性补全（one-shot completion）。
 *
 * 轻量场景专用（会话自动命名等）：单条 user 消息、非流式、无工具、关推理、小 maxTokens。
 * 复用 [KoogClientFactory] / [KoogModelBuilder] / [KoogParamsBuilder]，与对话 turn 同一条请求链路。
 *
 * 这是个无状态函数对象，线程安全。调用方负责异常处理（本层不吞错）。
 */
object OneShotCompletion {

    /**
     * 发送一条 user 消息，返回 assistant 文本（trim 后）；空文本返回 null。
     *
     * @param tag 请求标识（Kooh prompt id + 日志用），如 "autotitle"。
     * @param maxTokens 最大输出 token 数。
     */
    suspend fun execute(
        provider: xyz.mederi.provider.domain.model.Provider,
        model: xyz.mederi.domain.model.AIModel,
        apiKey: String,
        tag: String,
        prompt: String,
        maxTokens: Int
    ): String? {
        val client = KoogClientFactory.create(provider, apiKey)
        try {
            // 关推理（NONE 档）：推理型模型默认输出全在 reasoning_content，content 为空；
            // 空文本由调用方按失败处理
            val params = KoogParamsBuilder.build(
                type = provider.type,
                reasoningLevel = xyz.mederi.provider.domain.model.ReasoningLevel.NONE,
                reasoningParameter = provider.reasoningParameter,
                maxTokens = maxTokens
            )
            val koogPrompt = prompt(tag, params = params) {
                user(prompt)
            }
            val assistant = client.execute(koogPrompt, KoogModelBuilder.build(model, provider.type), emptyList())
            return assistant.parts
                .filterIsInstance<MessagePart.Text>()
                .joinToString("") { it.text }
                .trim()
                .takeIf { it.isNotEmpty() }
        } finally {
            runCatching { (client as? AutoCloseable)?.close() }
        }
    }
}
