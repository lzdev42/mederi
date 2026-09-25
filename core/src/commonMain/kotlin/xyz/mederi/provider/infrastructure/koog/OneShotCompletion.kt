package xyz.mederi.provider.infrastructure.koog

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.MessagePart

/**
 * 一次性补全（one-shot completion）。
 *
 * 轻量场景专用（会话自动命名等）：单条 user 消息、非流式、无工具、小 maxTokens。
 * 复用 [KoogClientFactory] / [KoogModelBuilder] / [KoogParamsBuilder]，与对话 turn 同一条请求链路。
 *
 * **不注入任何推理参数**（reasoningParameter = null，服务器默认是什么就是什么）：
 * 推理型模型很多对"显式关闭推理"报错，选模型/关思考把失败风险推到调用方不可控的配置上；
 * 不发参数让服务器端默认行为决定，是各类模型最稳的请求形态。
 *
 * 这是个无状态函数对象，线程安全。调用方负责异常处理（本层不吞错）。
 */
object OneShotCompletion {

    /**
     * 发送一条 user 消息，返回 assistant 文本（trim 后）；空文本返回 null。
     *
     * @param tag 请求标识（Kooh prompt id + 日志用），如 "autotitle"。
     * @param maxTokens 最大输出 token 数。
     * @param model 必须传 ProviderManager 现取值（`provider.getModel(id)`），**禁止传 Session.aiModel 快照**——
     *   能力标记（supportsImages/supportsReasoning）经 buildCapabilities 传导到引擎，快照值过期会导致
     *   §7 同款"设置已改、执行仍旧"事故。
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
            val params = KoogParamsBuilder.build(
                type = provider.type,
                reasoningLevel = xyz.mederi.provider.domain.model.ReasoningLevel.NONE,
                reasoningParameter = null,
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
