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
import xyz.mederi.debug.DebugLog
import xyz.mederi.http.MederiHttpClientFactory
import xyz.mederi.store.ApiKeyStore
import xyz.mederi.store.ProviderStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

private val lenientJson = Json { ignoreUnknownKeys = true }

/**
 * ProviderManager 默认实现。
 *
 * 持有 [ProviderStore] 和 [ApiKeyStore]，负责：
 * - 读时合并：list/get 返回的 Provider 自动合并 ApiKeyStore 中的密钥
 * - 写时剥离：保存 Provider 时剥离 apiKeys（由 ApiKeyStore 独立管理）
 * - 级联删除：删除 Provider 时级联删除其下所有 API Key
 * - Model 管理：Model 作为 Provider 内嵌列表，增删改通过整体保存 Provider 实现
 *
 * @param providerStore 供应商配置存储。
 * @param apiKeyStore API 密钥存储。
 */
class ProviderManagerImpl(
    private val providerStore: ProviderStore,
    private val apiKeyStore: ApiKeyStore
) : ProviderManager {

    @Serializable
    data class RemoteModelListResponse(val data: List<RemoteModelItem>)

    @Serializable
    data class RemoteModelItem(val id: String)

    // ==================== Provider ====================

    override suspend fun list(): List<Provider> =
        providerStore.list().map { it.copy(apiKeys = apiKeyStore.listByProvider(it.id)) }

    override suspend fun listWithoutKeys(): List<Provider> = providerStore.list()

    override suspend fun get(id: String): Provider? =
        providerStore.get(id)?.copy(apiKeys = apiKeyStore.listByProvider(id))

    override suspend fun require(id: String): Provider =
        get(id) ?: throw NoSuchElementException("Provider not found: $id")

    override suspend fun create(
        name: String,
        type: ProviderType,
        baseUrl: String,
        reasoningParameter: ReasoningParameter?,
        responseSanitization: Boolean,
        modelsDevKey: String?
    ): Provider {
        val effectiveReasoningParameter = reasoningParameter ?: ReasoningParameter.forType(type)
        validateReasoningParameter(effectiveReasoningParameter)
        val provider = Provider(
            id = "prov_${UUID.randomUUID().toString().take(8)}",
            name = name,
            type = type,
            baseUrl = baseUrl,
            reasoningParameter = effectiveReasoningParameter,
            models = emptyList(),
            responseSanitization = responseSanitization,
            modelsDevKey = modelsDevKey,
            apiKeys = emptyList()
        )
        saveProviderInternal(provider)
        return get(provider.id)!!
    }

    override suspend fun update(
        id: String,
        name: String?,
        baseUrl: String?,
        reasoningParameter: ReasoningParameter?,
        modelsDevKey: String?
    ): Provider {
        val existing = require(id)
        reasoningParameter?.let { validateReasoningParameter(it) }
        val updated = existing.copy(
            name = name ?: existing.name,
            baseUrl = baseUrl ?: existing.baseUrl,
            reasoningParameter = reasoningParameter ?: existing.reasoningParameter,
            modelsDevKey = modelsDevKey ?: existing.modelsDevKey
        )
        saveProviderInternal(updated)
        return updated
    }

    /**
     * 校验推理参数配置（写入前拦截）。
     *
     * 规则：所有以 "{" 开头的级别值必须是合法 JSON 对象（用户填什么发什么，
     * 只挡会发出畸形请求体的配置）。非法抛 IllegalArgumentException，
     * 经统一异常转换映射为 MederiValidationException。
     */
    private fun validateReasoningParameter(parameter: ReasoningParameter) {
        val error = parameter.validate()
        if (error != null) {
            throw IllegalArgumentException(error)
        }
    }

    override suspend fun delete(id: String) {
        providerStore.delete(id)
        apiKeyStore.listByProvider(id).forEach { apiKeyStore.delete(id, it.id) }
    }

    // ==================== API Key ====================

    override suspend fun listKeys(providerId: String): List<ProviderApiKey> =
        apiKeyStore.listByProvider(providerId)

    override suspend fun addKey(
        providerId: String,
        name: String,
        value: String,
        isDefault: Boolean
    ): ProviderApiKey {
        require(providerId) // 校验 Provider 存在
        return apiKeyStore.add(providerId, name, value, isDefault)
    }

    override suspend fun deleteKey(providerId: String, keyId: String) {
        apiKeyStore.delete(providerId, keyId)
    }

    override suspend fun setDefaultKey(providerId: String, keyId: String) {
        apiKeyStore.setDefault(providerId, keyId)
    }

    override suspend fun getDefaultKeyValue(providerId: String): String? =
        apiKeyStore.getDefaultValue(providerId)

    override suspend fun getKeyValue(providerId: String, keyId: String): String? =
        apiKeyStore.getValue(providerId, keyId)

    // ==================== Model ====================

    override suspend fun listModels(providerId: String): List<AIModel> =
        require(providerId).models

    override suspend fun addModel(
        providerId: String,
        providerModelId: String,
        name: String,
        supportsReasoning: Boolean,
        reasoningLevel: ReasoningLevel,
        contextWindow: Int?,
        maxTokens: Int?,
        supportsImages: Boolean,
        reasoningLevels: List<ReasoningLevel>,
        isEnabled: Boolean,
        inputPricePerMillion: Double?,
        outputPricePerMillion: Double?
    ): AIModel {
        val provider = providerStore.get(providerId)
            ?: throw NoSuchElementException("Provider not found: $providerId")
        val model = AIModel(
            id = "mdl_${UUID.randomUUID().toString().take(8)}",
            providerModelId = providerModelId,
            name = name,
            supportsReasoning = supportsReasoning,
            reasoningLevel = reasoningLevel,
            contextWindow = contextWindow,
            maxTokens = maxTokens,
            supportsImages = supportsImages,
            reasoningLevels = reasoningLevels,
            origin = ModelOrigin.MANUAL,
            isEnabled = isEnabled,
            inputPricePerMillion = inputPricePerMillion,
            outputPricePerMillion = outputPricePerMillion
        )
        saveProviderInternal(provider.copy(models = provider.models + model))
        return model
    }

    override suspend fun addFetchedModel(providerId: String, merged: AIModel, isEnabled: Boolean): AIModel {
        val provider = providerStore.get(providerId)
            ?: throw NoSuchElementException("Provider not found: $providerId")
        val model = merged.copy(
            id = "mdl_${UUID.randomUUID().toString().take(8)}",
            origin = ModelOrigin.FETCHED,
            isEnabled = isEnabled
        )
        saveProviderInternal(provider.copy(models = provider.models + model))
        return model
    }

    override suspend fun applyRemoteMetadata(
        providerId: String,
        modelId: String,
        endpoint: RemoteModelInfo?,
        catalog: ModelMetadata?
    ): AIModel {
        val provider = providerStore.get(providerId)
            ?: throw NoSuchElementException("Provider not found: $providerId")
        val existing = provider.getModel(modelId)
            ?: throw NoSuchElementException("Model not found: $modelId")
        val merged = ModelMerge.mergeFetched(existing, endpoint, catalog)
        // 无变化不落库（回填路径每模型都会调，省一次写放大）
        if (merged == existing) return existing
        saveProviderInternal(provider.copy(models = provider.models.map { if (it.id == modelId) merged else it }))
        return merged
    }

    override suspend fun updateUserModel(
        providerId: String,
        modelId: String,
        name: String?,
        supportsReasoning: Boolean?,
        reasoningLevel: ReasoningLevel?,
        contextWindow: Int?,
        maxTokens: Int?,
        supportsImages: Boolean?,
        reasoningLevels: List<ReasoningLevel>?,
        isEnabled: Boolean?
    ): AIModel {
        val provider = providerStore.get(providerId)
            ?: throw NoSuchElementException("Provider not found: $providerId")
        val existing = provider.getModel(modelId)
            ?: throw NoSuchElementException("Model not found: $modelId")
        val updated = existing.copy(
            name = name ?: existing.name,
            supportsReasoning = supportsReasoning ?: existing.supportsReasoning,
            reasoningLevel = reasoningLevel ?: existing.reasoningLevel,
            contextWindow = contextWindow ?: existing.contextWindow,
            maxTokens = maxTokens ?: existing.maxTokens,
            supportsImages = supportsImages ?: existing.supportsImages,
            // 用户覆盖（用户权威）：FETCHED 模型经 UI 显式设置的图片能力，同步永不洗掉
            //（目录对长尾/私有模型经常缺数据或标错，用户显式设置必须压过目录）
            supportsImagesOverride = when {
                existing.origin == ModelOrigin.FETCHED && supportsImages != null -> supportsImages
                else -> existing.supportsImagesOverride
            },
            reasoningLevels = reasoningLevels ?: existing.reasoningLevels,
            isEnabled = isEnabled ?: existing.isEnabled
        )
        saveProviderInternal(provider.copy(models = provider.models.map { if (it.id == modelId) updated else it }))
        return updated
    }

    override suspend fun deleteModel(providerId: String, modelId: String) {
        val provider = providerStore.get(providerId)
            ?: throw NoSuchElementException("Provider not found: $providerId")
        saveProviderInternal(provider.copy(models = provider.models.filter { it.id != modelId }))
    }

    // ==================== 跨供应商 Model 查询 ====================

    override suspend fun getModel(modelId: String): AIModel? =
        providerStore.list().firstNotNullOfOrNull { it.getModel(modelId) }

    override suspend fun listAllModels(): List<AIModel> =
        providerStore.list().flatMap { it.models }

    // ==================== 远端模型拉取 ====================
    // 解析逻辑抽在 RemoteModelParsing.kt（纯函数），网络层只负责 HTTP 与分页循环

    override suspend fun fetchRemoteModels(providerId: String): List<RemoteModelInfo> {
        val provider = require(providerId)
        val apiKey = apiKeyStore.getDefaultValue(providerId)
            ?: throw IllegalStateException("未配置 API Key，无法拉取模型列表")

        val normalized = xyz.mederi.provider.infrastructure.UrlNormalizer.normalize(provider.baseUrl)
        val modelsPath = "${normalized.pathPrefix}models"

        return when (provider.type) {
            ProviderType.GOOGLE -> {
                val httpClient = MederiHttpClientFactory.create(
                    clientName = "mederi-models-fetch-google",
                    baseUrl = normalized.base,
                    headers = mapOf("x-goog-api-key" to apiKey)
                )
                try {
                    fetchGoogleModels(httpClient, modelsPath)
                } finally {
                    httpClient.close()
                }
            }
            else -> {
                val httpClient = MederiHttpClientFactory.create(
                    clientName = "mederi-models-fetch",
                    baseUrl = normalized.base,
                    headers = mapOf(
                        "Authorization" to "Bearer $apiKey",
                        "Content-Type" to "application/json"
                    )
                )
                try {
                    fetchOpenAIModels(httpClient, modelsPath)
                } finally {
                    httpClient.close()
                }
            }
        }
    }

    /** OpenAI 风格拉取（OPENAI_CHAT / OPENAI_RESPONSES）：单页，无分页 */
    private suspend fun fetchOpenAIModels(
        httpClient: ai.koog.http.client.KoogHttpClient,
        modelsPath: String
    ): List<RemoteModelInfo> {
        DebugLog.event("ProviderMgr", "fetchRemoteModels: path=$modelsPath")
        val response = httpClient.get<String>(
            path = modelsPath,
            responseType = String::class
        )
        DebugLog.data("ProviderMgr", "response (len=${response.length})", response)
        return parseOpenAIModelsResponse(response)
    }

    /**
     * Google 原生拉取（GOOGLE）。
     *
     * GET {base}/{versionPath}models，认证用 x-goog-api-key header。
     * 分页循环 nextToken（pageSize 上限 1000）；解析/过滤在 parseGoogleModelsResponse。
     */
    private suspend fun fetchGoogleModels(
        httpClient: ai.koog.http.client.KoogHttpClient,
        modelsPath: String
    ): List<RemoteModelInfo> {
        DebugLog.event("ProviderMgr", "fetchRemoteModels(GOOGLE): path=$modelsPath")

        val result = mutableListOf<RemoteModelInfo>()
        var pageToken: String? = null
        do {
            val response = httpClient.get<String>(
                path = modelsPath,
                responseType = String::class,
                parameters = googleModelsQueryParameters(pageToken)
            )
            DebugLog.data("ProviderMgr", "response page (len=${response.length})", response)

            val page = parseGoogleModelsResponse(response)
            result += page.models
            pageToken = page.nextPageToken
        } while (pageToken != null)

        return result
    }

    // ==================== 内部方法 ====================

    /**
     * 保存 Provider 时剥离 apiKeys（由 ApiKeyStore 独立管理）。
     */
    private suspend fun saveProviderInternal(provider: Provider) {
        providerStore.save(provider.copy(apiKeys = emptyList()))
    }
}
