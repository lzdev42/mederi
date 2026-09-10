package xyz.mederi.provider.infrastructure.koog

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.google.GoogleClientSettings
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.http.client.HttpClientFactoryResolver
import xyz.mederi.debug.DebugLog
import xyz.mederi.provider.domain.model.Provider
import xyz.mederi.provider.domain.model.ProviderType
import xyz.mederi.provider.infrastructure.UrlNormalizer
import xyz.mederi.provider.infrastructure.koog.sanitize.MederiOpenAILLMClient

/**
 * Koog LLM 客户端工厂。
 *
 * 根据供应商配置创建对应的 Koog LLM 客户端实例。
 * 这是 Mederi 领域模型与 Koog 客户端之间的适配器。
 *
 * 支持创建以下类型的客户端：
 * - [OpenAILLMClient]: 用于 OpenAI Chat Completions 和 Responses API（及所有 OpenAI 兼容端点）
 * - [GoogleLLMClient]: 用于 Google Gemini API
 *
 * 用户在 [Provider.baseUrl] 中输入带版本路径的完整端点（如 "https://api.openai.com/v1"），
 * 本工厂通过 [UrlNormalizer] 将其拆解为 base 和 versionPath，
 * 再用 versionPath 拼接 Koog 的各个 path 字段（如 "v1/chat/completions"）。
 * 用户填错端点不会被纠正，请求失败时错误信息会反馈给用户。
 *
 * 使用 Koog 的 JVM 便捷顶层函数创建客户端，自动解析默认的 KoogHttpClient.Factory。
 *
 * 这是一个无状态的工厂对象，线程安全。
 */
object KoogClientFactory {

    /**
     * 根据供应商配置和 API 密钥创建 Koog LLM 客户端。
     *
     * 根据供应商类型（[Provider.type]）自动选择创建哪种客户端：
     * - [ProviderType.OPENAI_CHAT] / [ProviderType.OPENAI_RESPONSES] -> [OpenAILLMClient]
     * - [ProviderType.GOOGLE] -> [GoogleLLMClient]
     *
     * 对于 OpenAI 类型的供应商，使用哪种 API 模式（Chat Completions vs Responses）
     * 不是在创建客户端时决定的，而是在发送请求时由传入的参数类型决定：
     * - OpenAIChatParams -> Chat Completions API (如 v1/chat/completions)
     * - OpenAIResponsesParams -> Responses API (如 v1/responses)
     * OpenAILLMClient 同时支持两种 API 模式。
     *
     * @param provider 供应商配置。
     * @param apiKey API 密钥明文。
     * @return Koog LLM 客户端实例（OpenAILLMClient 或 GoogleLLMClient）。
     * @throws IllegalArgumentException 如果供应商类型不支持。
     */
    fun create(provider: Provider, apiKey: String): LLMClient {
        DebugLog.section("ClientFactory", "KoogClientFactory.create")
        DebugLog.data("ClientFactory", "provider", "${provider.id} (${provider.name}), type=${provider.type}")
        DebugLog.data("ClientFactory", "baseUrl", provider.baseUrl)
        DebugLog.data("ClientFactory", "responseSanitization", provider.responseSanitization)
        return when (provider.type) {
            ProviderType.OPENAI_CHAT, ProviderType.OPENAI_RESPONSES -> createOpenAIClient(provider, apiKey)
            ProviderType.GOOGLE -> createGoogleClient(provider, apiKey)
        }
    }

    /**
     * 创建 OpenAI 兼容客户端。
     *
     * 选择 [MederiOpenAILLMClient] 的条件（满足任一即可）：
     * - [Provider.responseSanitization] = true：供应商返回字段不标准（如 vLLM 兼容端点）
     * - [Provider.reasoningParameter] != null：供应商配了推理参数。
     *   Koog 原版 [OpenAILLMClient] 的 Chat Completions 流式 delta 没有 reasoningContent 字段，
     *   会静默丢弃 SSE 中的推理内容。[MederiOpenAILLMClient] 正确解析 reasoning_content / reasoning。
     *
     * 两者都不满足时直通 Koog 原版 [OpenAILLMClient]，零开销。
     */
    fun createOpenAIClient(provider: Provider, apiKey: String): LLMClient {
        val normalized = UrlNormalizer.normalize(provider.baseUrl)
        val prefix = normalized.pathPrefix
        val settings = OpenAIClientSettings(
            baseUrl = normalized.base,
            chatCompletionsPath = "${prefix}chat/completions",
            responsesAPIPath = "${prefix}responses",
            embeddingsPath = "${prefix}embeddings",
            moderationsPath = "${prefix}moderations",
            modelsPath = "${prefix}models"
        )

        val needsCustomClient = provider.responseSanitization || provider.reasoningParameter != null

        if (!needsCustomClient) {
            DebugLog.event("ClientFactory", "created OpenAILLMClient (standard)")
            return OpenAILLMClient(apiKey = apiKey, settings = settings)
        }

        DebugLog.event("ClientFactory", "created MederiOpenAILLMClient (reasoning=${provider.reasoningParameter != null}, sanitization=${provider.responseSanitization})")
        return MederiOpenAILLMClient(
            apiKey = apiKey,
            settings = settings,
            httpClientFactory = HttpClientFactoryResolver.resolve(),
            chatCompletionsPath = "${prefix}chat/completions",
        )
    }

    /**
     * 创建 Google Gemini 客户端。
     *
     * 将用户输入的端点 URL 拆解后，用 versionPath 拼接 Koog 的 defaultPath：
     * - 用户输入 "https://generativelanguage.googleapis.com/v1beta" ->
     *   baseUrl = "https://generativelanguage.googleapis.com", defaultPath = "v1beta/models"
     *
     * 注意：走的是 Google 原生 Generative Language API（v1beta 是原生 API 的版本路径，
     * 不是 OpenAI 兼容模式 /v1beta/openai/），协议为 models/{id}:generateContent。
     *
     * Google Interactions API（server-side state / Deep Research / background execution）
     * 暂不使用——generateContent 已满足当前需求；等 Koog 支持或需要其独有能力时再切。
     *
     * @param provider 供应商配置。
     * @param apiKey API 密钥明文。
     * @return GoogleLLMClient 实例。
     */
    fun createGoogleClient(provider: Provider, apiKey: String): GoogleLLMClient {
        val normalized = UrlNormalizer.normalize(provider.baseUrl)
        val settings = GoogleClientSettings(
            baseUrl = normalized.base,
            defaultPath = "${normalized.versionPath}/models"
        )
        return GoogleLLMClient(apiKey = apiKey, settings = settings)
    }
}
