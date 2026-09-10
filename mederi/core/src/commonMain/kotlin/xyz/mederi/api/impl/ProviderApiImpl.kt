package xyz.mederi.api.impl

import xyz.mederi.api.CreateApiKeyRequest
import xyz.mederi.api.CreateModelRequest
import xyz.mederi.api.CreateProviderRequest
import xyz.mederi.api.ProviderApi
import xyz.mederi.api.UpdateModelRequest
import xyz.mederi.api.UpdateProviderRequest
import xyz.mederi.api.exception.MederiValidationException
import xyz.mederi.api.exception.mederiCall
import xyz.mederi.debug.DebugLog
import xyz.mederi.domain.model.AIModel
import xyz.mederi.metadata.ModelCatalog
import xyz.mederi.provider.ProviderManager
import xyz.mederi.provider.domain.model.Provider
import xyz.mederi.provider.domain.model.ProviderApiKey
import xyz.mederi.provider.domain.model.ProviderType
import xyz.mederi.provider.domain.model.RemoteModelInfo

/**
 * ProviderApi 实现。
 *
 * 所有方法都通过 [mederiCall] 把底层异常转换为 Mederi 统一异常，
 * 确保调用方不需要关心 Kotlin 标准库异常的具体类型。
 */
internal const val DEFAULT_VISIBLE_MODEL_LIMIT = 10

class ProviderApiImpl(
    private val providerManager: ProviderManager,
    private val modelCatalog: ModelCatalog
) : ProviderApi {

    override suspend fun list(): List<Provider> = mederiCall {
        providerManager.list()
    }

    override suspend fun create(request: CreateProviderRequest): Provider = mederiCall {
        val type = try {
            ProviderType.valueOf(request.type)
        } catch (e: IllegalArgumentException) {
            throw MederiValidationException(
                "Invalid provider type '${request.type}'. " +
                    "Expected one of: ${ProviderType.entries.joinToString()}",
                e
            )
        }
        val created = providerManager.create(
            name = request.name,
            type = type,
            baseUrl = request.baseUrl,
            reasoningParameter = request.reasoningParameter,
            responseSanitization = request.responseSanitization,
            modelsDevKey = request.modelsDevKey
        )
        // 创建时同时添加 API Key
        request.apiKeys.forEach { keyReq ->
            providerManager.addKey(created.id, keyReq.name, keyReq.value, keyReq.isDefault)
        }
        // 创建时同时添加模型
        request.models.forEach { modelReq ->
            providerManager.addModel(
                providerId = created.id,
                providerModelId = modelReq.providerModelId,
                name = modelReq.name,
                supportsReasoning = modelReq.supportsReasoning,
                reasoningLevel = modelReq.reasoningLevel,
                contextWindow = modelReq.contextWindow,
                maxTokens = modelReq.maxTokens,
                supportsImages = modelReq.supportsImages,
                reasoningLevels = modelReq.reasoningLevels
            )
        }
        // fetchModels=true 时从远端拉取模型列表并合并（与 refreshModels 共用同一合并逻辑）
        if (request.fetchModels && request.models.isEmpty()) {
            try {
                val remote = providerManager.fetchRemoteModels(created.id)
                mergeRemoteModels(created.id, remote)
                DebugLog.event("ProviderApi", "fetchModels: fetched ${remote.size} models from /models endpoint")
            } catch (e: Exception) {
                DebugLog.error("ProviderApi", "fetchModels failed: ${e.message}", e)
            }
        }
        // 返回添加 keys/models 后重新获取的完整 Provider（含合并后的 apiKeys 和 models）
        providerManager.get(created.id)!!
    }

    override suspend fun get(id: String): Provider = mederiCall {
        providerManager.require(id)
    }

    override suspend fun update(id: String, request: UpdateProviderRequest): Provider = mederiCall {
        providerManager.update(
            id = id,
            name = request.name,
            baseUrl = request.baseUrl,
            reasoningParameter = request.reasoningParameter,
            modelsDevKey = request.modelsDevKey
        )
    }

    override suspend fun delete(id: String) {
        mederiCall { providerManager.delete(id) }
    }

    override suspend fun addKey(providerId: String, request: CreateApiKeyRequest): ProviderApiKey = mederiCall {
        providerManager.addKey(providerId, request.name, request.value, request.isDefault)
    }

    override suspend fun listKeys(providerId: String): List<ProviderApiKey> = mederiCall {
        providerManager.listKeys(providerId)
    }

    override suspend fun deleteKey(providerId: String, keyId: String) {
        mederiCall { providerManager.deleteKey(providerId, keyId) }
    }

    override suspend fun setDefaultKey(providerId: String, keyId: String) {
        mederiCall { providerManager.setDefaultKey(providerId, keyId) }
    }

    override suspend fun listModels(providerId: String): List<AIModel> = mederiCall {
        providerManager.listModels(providerId)
    }

    override suspend fun addModel(providerId: String, request: CreateModelRequest): AIModel = mederiCall {
        providerManager.addModel(
            providerId = providerId,
            providerModelId = request.providerModelId,
            name = request.name,
            supportsReasoning = request.supportsReasoning,
            reasoningLevel = request.reasoningLevel,
            contextWindow = request.contextWindow,
            maxTokens = request.maxTokens,
            supportsImages = request.supportsImages,
            reasoningLevels = request.reasoningLevels,
            isEnabled = request.isEnabled
        )
    }

    override suspend fun updateModel(
        providerId: String,
        modelId: String,
        request: UpdateModelRequest
    ): AIModel = mederiCall {
        providerManager.updateModel(
            providerId = providerId,
            modelId = modelId,
            name = request.name,
            supportsReasoning = request.supportsReasoning,
            reasoningLevel = request.reasoningLevel,
            contextWindow = request.contextWindow,
            maxTokens = request.maxTokens,
            supportsImages = request.supportsImages,
            reasoningLevels = request.reasoningLevels,
            isEnabled = request.isEnabled
        )
    }

    override suspend fun deleteModel(providerId: String, modelId: String) {
        mederiCall { providerManager.deleteModel(providerId, modelId) }
    }

    override suspend fun refreshModels(providerId: String): List<String> = mederiCall {
        val remote = providerManager.fetchRemoteModels(providerId)

        mergeRemoteModels(providerId, remote)

        remote.map { it.providerModelId }
    }

    /**
     * 远端模型合并进存储（create(fetchModels) 与 refreshModels 共用）。
     *
     * 合并前用 models.dev 元数据目录补充缺口：端点给的值优先（如 Hetzner 的
     * max_model_len），目录只填端点没提供的字段（价格/图片/推理档位等）。
     * 目录匹配不上就保持端点原样，不兜底。
     *
     * - 新增模型：带元数据（displayName / contextWindow / maxTokens / supportsReasoning /
     *   价格 / 图片 / 推理档位），默认可见规则 = 补足启用至 [DEFAULT_VISIBLE_MODEL_LIMIT] 个
     * - 已存在模型：刷新远端元数据；supportsReasoning 为 null（远端未提供）保留本地值；
     *   reasoningLevel / isEnabled 是用户设置，不动
     */
    private suspend fun mergeRemoteModels(providerId: String, remote: List<RemoteModelInfo>) {
        val provider = providerManager.require(providerId)
        val existingByProviderId = provider.models.associateBy { it.providerModelId }
        var enabledCount = provider.models.count { it.isEnabled }
        var added = 0
        var updated = 0
        for (info in remote) {
            val enriched = enrichFromCatalog(provider, info)
            val existing = existingByProviderId[info.providerModelId]
            if (existing == null) {
                val enabled = enabledCount < DEFAULT_VISIBLE_MODEL_LIMIT
                if (enabled) enabledCount++
                added++
                providerManager.addModel(
                    providerId = providerId,
                    providerModelId = enriched.providerModelId,
                    name = enriched.name,
                    supportsReasoning = enriched.supportsReasoning ?: false,
                    reasoningLevel = xyz.mederi.provider.domain.model.ReasoningLevel.NONE,
                    contextWindow = enriched.contextWindow,
                    maxTokens = enriched.maxTokens,
                    supportsImages = enriched.supportsImages ?: false,
                    reasoningLevels = enriched.reasoningLevels,
                    isEnabled = enabled,
                    inputPricePerMillion = enriched.inputPricePerMillion,
                    outputPricePerMillion = enriched.outputPricePerMillion
                )
            } else {
                updated++
                providerManager.updateModel(
                    providerId = providerId,
                    modelId = existing.id,
                    name = enriched.name,
                    supportsReasoning = enriched.supportsReasoning,
                    reasoningLevel = null,
                    contextWindow = enriched.contextWindow,
                    maxTokens = enriched.maxTokens,
                    supportsImages = enriched.supportsImages,
                    reasoningLevels = enriched.reasoningLevels.takeIf { it.isNotEmpty() },
                    isEnabled = null,
                    inputPricePerMillion = enriched.inputPricePerMillion,
                    outputPricePerMillion = enriched.outputPricePerMillion
                )
            }
        }
        DebugLog.event("ProviderApi", "mergeRemoteModels: $added added / $updated updated (provider=$providerId)")
    }

    /**
     * 用 models.dev 目录补充端点未提供的模型元数据。
     *
     * 匹配优先级：Provider.modelsDevKey（显式指定，覆盖同一家多端点场景）
     * → baseUrl 对应目录 api 字段（自定义供应商零注册自动命中）。
     * 端点已给的值不动，目录只填空。
     */
    private fun enrichFromCatalog(provider: Provider, info: RemoteModelInfo): RemoteModelInfo {
        val meta = modelCatalog.getFor(provider.modelsDevKey, provider.baseUrl, info.providerModelId)
            ?: return info
        return info.copy(
            contextWindow = info.contextWindow ?: meta.contextWindow,
            maxTokens = info.maxTokens ?: meta.maxOutputTokens,
            supportsReasoning = info.supportsReasoning ?: meta.supportsReasoning,
            inputPricePerMillion = info.inputPricePerMillion ?: meta.inputPricePerMillion,
            outputPricePerMillion = info.outputPricePerMillion ?: meta.outputPricePerMillion,
            supportsImages = meta.supportsImages, // 端点不提供图片信息，目录是唯一来源
            reasoningLevels = meta.reasoningLevels // 目录档位为权威；空列表由调用方按"无值保留"处理
        )
    }
}
