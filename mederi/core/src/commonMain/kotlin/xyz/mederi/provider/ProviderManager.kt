package xyz.mederi.provider

import xyz.mederi.domain.model.AIModel
import xyz.mederi.provider.domain.model.Provider
import xyz.mederi.provider.domain.model.ProviderApiKey
import xyz.mederi.provider.domain.model.ProviderType
import xyz.mederi.provider.domain.model.ReasoningLevel
import xyz.mederi.provider.domain.model.ReasoningParameter
import xyz.mederi.provider.domain.model.RemoteModelInfo

/**
 * 供应商管理器。
 *
 * Provider 和 Model 的唯一真理源。所有供应商配置、API Key、Model 的读写都必须通过 ProviderManager。
 *
 * 核心职责：
 * - Provider CRUD（读写时自动处理 API Key 的合并/剥离）
 * - API Key 管理（增删改查、设置默认）
 * - Model 管理（Provider 内嵌模型的增删改查、跨供应商聚合查询）
 * - 级联删除：删除 Provider 时级联删除其下所有 API Key
 */
interface ProviderManager {

    // === Provider ===

    suspend fun list(): List<Provider>
    suspend fun listWithoutKeys(): List<Provider>
    suspend fun get(id: String): Provider?
    suspend fun require(id: String): Provider

    suspend fun create(
        name: String,
        type: ProviderType,
        baseUrl: String,
        reasoningParameter: ReasoningParameter?,
        responseSanitization: Boolean,
        modelsDevKey: String? = null
    ): Provider

    suspend fun update(
        id: String,
        name: String?,
        baseUrl: String?,
        reasoningParameter: ReasoningParameter?,
        modelsDevKey: String? = null
    ): Provider

    suspend fun delete(id: String)

    // === API Key ===

    suspend fun listKeys(providerId: String): List<ProviderApiKey>
    suspend fun addKey(providerId: String, name: String, value: String, isDefault: Boolean): ProviderApiKey
    suspend fun deleteKey(providerId: String, keyId: String)
    suspend fun setDefaultKey(providerId: String, keyId: String)
    suspend fun getDefaultKeyValue(providerId: String): String?

    // === Model ===

    suspend fun listModels(providerId: String): List<AIModel>
    suspend fun addModel(
        providerId: String,
        providerModelId: String,
        name: String,
        supportsReasoning: Boolean,
        reasoningLevel: ReasoningLevel,
        contextWindow: Int? = null,
        maxTokens: Int? = null,
        supportsImages: Boolean = false,
        reasoningLevels: List<ReasoningLevel> = emptyList(),
        isEnabled: Boolean = true,
        inputPricePerMillion: Double? = null,
        outputPricePerMillion: Double? = null
    ): AIModel
    suspend fun updateModel(
        providerId: String,
        modelId: String,
        name: String?,
        supportsReasoning: Boolean?,
        reasoningLevel: ReasoningLevel?,
        contextWindow: Int? = null,
        maxTokens: Int? = null,
        supportsImages: Boolean? = null,
        reasoningLevels: List<ReasoningLevel>? = null,
        isEnabled: Boolean? = null,
        inputPricePerMillion: Double? = null,
        outputPricePerMillion: Double? = null
    ): AIModel
    suspend fun deleteModel(providerId: String, modelId: String)

    // === 跨供应商 Model 查询 ===

    suspend fun getModel(modelId: String): AIModel?
    suspend fun listAllModels(): List<AIModel>

    // === 远端模型拉取 ===

    /**
     * 从供应商的 /models 端点拉取可用模型及元数据。
     * 不写入存储，只返回远端列表。
     *
     * 按供应商类型分协议：
     * - OPENAI_CHAT / OPENAI_RESPONSES：GET {base}/{versionPath}models（Bearer），仅 id
     * - GOOGLE：GET {base}/{versionPath}models（x-goog-api-key），原生 Model 资源，
     *   解析 displayName / inputTokenLimit / outputTokenLimit / thinking
     */
    suspend fun fetchRemoteModels(providerId: String): List<RemoteModelInfo>

    /**
     * 从供应商的 /models 端点拉取可用模型 ID 列表（便捷方法）。
     */
    suspend fun fetchRemoteModelIds(providerId: String): List<String> =
        fetchRemoteModels(providerId).map { it.providerModelId }
}
