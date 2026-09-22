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
import xyz.mederi.domain.model.ModelOrigin
import xyz.mederi.metadata.ModelCatalog
import xyz.mederi.provider.ModelMerge
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
        providerManager.updateUserModel(
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

    override suspend fun autoSetupModels(providerId: String): Int = mederiCall {
        val provider = providerManager.require(providerId)
        var updated = 0
        for (model in provider.models) {
            val meta = modelCatalog.getFor(provider.modelsDevKey, provider.baseUrl, model.providerModelId) ?: continue
            val after = providerManager.applyRemoteMetadata(providerId, model.id, endpoint = null, catalog = meta)
            if (after != model) updated++
        }
        updated
    }

    /**
     * 远端模型列表同步进存储（create(fetchModels) 与 refreshModels 共用）。
     *
     * **只同步列表，不碰已有模型的元数据**（2026-09 机制翻转：系统永不自动纠正存量数据——
     * 启动回填已移除、刷新只新增模型；目录元数据进入存量模型的唯一通道 = [autoSetupModels] 显式触发）：
     * - 远端新模型：[ModelMerge.createFetched] 构建后落库（origin=FETCHED，带端点+目录元数据），
     *   默认可见规则 = 补足启用至 [DEFAULT_VISIBLE_MODEL_LIMIT] 个
     * - 已存在模型：一律跳过（存量元数据只属于用户，或经显式「自动设置」更新）
     */
    private suspend fun mergeRemoteModels(providerId: String, remote: List<RemoteModelInfo>) {
        val provider = providerManager.require(providerId)
        val existingByProviderId = provider.models.associateBy { it.providerModelId }
        var enabledCount = provider.models.count { it.isEnabled }
        var added = 0
        var skippedExisting = 0
        for (info in remote) {
            if (existingByProviderId.containsKey(info.providerModelId)) {
                skippedExisting++
                continue
            }
            val meta = modelCatalog.getFor(provider.modelsDevKey, provider.baseUrl, info.providerModelId)
            val enabled = enabledCount < DEFAULT_VISIBLE_MODEL_LIMIT
            if (enabled) enabledCount++
            added++
            providerManager.addFetchedModel(
                providerId = providerId,
                merged = ModelMerge.createFetched(info.providerModelId, info, meta),
                isEnabled = enabled
            )
        }
        DebugLog.event(
            "ProviderApi",
            "mergeRemoteModels: $added added / $skippedExisting skipped(existing) (provider=$providerId)"
        )
    }
}
