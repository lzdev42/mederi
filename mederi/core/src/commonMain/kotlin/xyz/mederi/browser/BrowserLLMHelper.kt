package xyz.mederi.browser

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import xyz.mederi.domain.model.AIModel
import xyz.mederi.provider.domain.model.Provider
import xyz.mederi.provider.domain.model.ReasoningLevel
import xyz.mederi.provider.infrastructure.koog.KoogClientFactory
import xyz.mederi.provider.infrastructure.koog.KoogModelBuilder
import xyz.mederi.provider.infrastructure.koog.KoogParamsBuilder
import xyz.mederi.provider.ProviderManager
import java.util.Base64

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

    /**
     * 根据当前 [aiModel] 动态解析归属的 Provider 和 API Key 并实例化 LLMClient。
     */
    suspend fun resolveClient(): LLMClient? {
        val p = providerManager.listWithoutKeys()
            .firstOrNull { it.models.any { m -> m.id == aiModel.id } }
            ?: return null
        val apiKey = apiKeyId?.let { providerManager.getKeyValue(p.id, it) }
            ?: providerManager.getDefaultKeyValue(p.id)
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