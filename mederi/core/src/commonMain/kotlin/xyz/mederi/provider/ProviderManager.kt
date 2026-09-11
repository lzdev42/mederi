package xyz.mederi.provider

import xyz.mederi.domain.model.AIModel
import xyz.mederi.domain.model.ModelOrigin
import xyz.mederi.metadata.ModelMetadata
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

    /**
     * 添加用户手动模型（[ModelOrigin.MANUAL]，用户权威，同步永不触碰）。
     */
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

    /**
     * 添加远端拉取的新模型（[ModelOrigin.FETCHED]）。
     * 元数据必须先经 [ModelMerge] 合并产生——本方法是落库通道，不解释字段。
     */
    suspend fun addFetchedModel(providerId: String, merged: AIModel, isEnabled: Boolean): AIModel

    /**
     * FETCHED 模型元数据的唯一写入路径（用户意图 API 之外的全部模型写入都走这里）。
     *
     * - endpoint != null = refresh：端点 + 目录全量权威重同步
     * - endpoint == null = 启动回填：只补空、绝不覆盖
     *
     * 合并规则唯一实现见 [ModelMerge]；本方法只做模型存在性/来源校验 + 无变化跳过落库。
     *
     * @throws NoSuchElementException 模型不存在。
     * @throws IllegalStateException 目标是 MANUAL 模型（用户权威，同步不许触碰）。
     */
    suspend fun applyRemoteMetadata(
        providerId: String,
        modelId: String,
        endpoint: RemoteModelInfo?,
        catalog: ModelMetadata?
    ): AIModel

    /**
     * 用户编辑模型（设置页对话框唯一通道）。
     *
     * MANUAL 模型：用户权威，全部字段可改。
     * FETCHED 模型：元数据是端点/目录权威，**只允许改 isEnabled 和图片能力**——
     * 图片能力走用户覆盖（[AIModel.supportsImagesOverride]，用户权威，后续同步永不覆盖），
     * 因为目录对长尾/私有模型经常缺数据或标错；其余元数据字段传入即抛错。
     *
     * @throws NoSuchElementException 模型不存在。
     * @throws IllegalStateException FETCHED 模型携带了 isEnabled/supportsImages 之外的修改。
     */
    suspend fun updateUserModel(
        providerId: String,
        modelId: String,
        name: String? = null,
        supportsReasoning: Boolean? = null,
        reasoningLevel: ReasoningLevel? = null,
        contextWindow: Int? = null,
        maxTokens: Int? = null,
        supportsImages: Boolean? = null,
        reasoningLevels: List<ReasoningLevel>? = null,
        isEnabled: Boolean? = null
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
