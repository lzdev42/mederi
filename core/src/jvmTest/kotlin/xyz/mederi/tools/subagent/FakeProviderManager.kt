package xyz.mederi.tools.subagent

import xyz.mederi.domain.model.AIModel
import xyz.mederi.metadata.ModelMetadata
import xyz.mederi.provider.ProviderManager
import xyz.mederi.provider.domain.model.Provider
import xyz.mederi.provider.domain.model.ProviderApiKey
import xyz.mederi.provider.domain.model.ProviderType
import xyz.mederi.provider.domain.model.ReasoningLevel
import xyz.mederi.provider.domain.model.ReasoningParameter
import xyz.mederi.provider.domain.model.RemoteModelInfo

/**
 * 共享测试夹具：内存版 [ProviderManager]。
 *
 * 从 SubagentConfigManagerTest 抽取为公开类，供 SubagentConfigManagerTest /
 * BrowserToolTest 等复用（单一来源，避免 28 个方法重复实现）。
 * 模型按 id 查表；其余方法为占位实现（未实现的方法抛 NotImplementedError）。
 */
class FakeProviderManager(
    private val models: Map<String, AIModel>
) : ProviderManager {
    override suspend fun getModel(modelId: String): AIModel? = models[modelId]
    override suspend fun list(): List<Provider> = emptyList()
    override suspend fun listWithoutKeys(): List<Provider> = emptyList()
    override suspend fun get(id: String): Provider? = null
    override suspend fun require(id: String): Provider = throw NotImplementedError()
    override suspend fun create(name: String, type: ProviderType, baseUrl: String, reasoningParameter: ReasoningParameter?, responseSanitization: Boolean, modelsDevKey: String?): Provider = throw NotImplementedError()
    override suspend fun update(id: String, name: String?, baseUrl: String?, reasoningParameter: ReasoningParameter?, modelsDevKey: String?): Provider = throw NotImplementedError()
    override suspend fun delete(id: String) {}
    override suspend fun listKeys(providerId: String): List<ProviderApiKey> = emptyList()
    override suspend fun addKey(providerId: String, name: String, value: String, isDefault: Boolean): ProviderApiKey = throw NotImplementedError()
    override suspend fun deleteKey(providerId: String, keyId: String) {}
    override suspend fun setDefaultKey(providerId: String, keyId: String) {}
    override suspend fun getDefaultKeyValue(providerId: String): String? = null
    override suspend fun getKeyValue(providerId: String, keyId: String): String? = null
    override suspend fun listModels(providerId: String): List<AIModel> = models.values.toList()
    override suspend fun addModel(providerId: String, providerModelId: String, name: String, supportsReasoning: Boolean, reasoningLevel: ReasoningLevel, contextWindow: Int?, maxTokens: Int?, supportsImages: Boolean, reasoningLevels: List<ReasoningLevel>, isEnabled: Boolean, inputPricePerMillion: Double?, outputPricePerMillion: Double?): AIModel = throw NotImplementedError()
    override suspend fun addFetchedModel(providerId: String, merged: AIModel, isEnabled: Boolean): AIModel = throw NotImplementedError()
    override suspend fun applyRemoteMetadata(providerId: String, modelId: String, endpoint: RemoteModelInfo?, catalog: ModelMetadata?): AIModel = throw NotImplementedError()
    override suspend fun updateUserModel(providerId: String, modelId: String, name: String?, supportsReasoning: Boolean?, reasoningLevel: ReasoningLevel?, contextWindow: Int?, maxTokens: Int?, supportsImages: Boolean?, reasoningLevels: List<ReasoningLevel>?, isEnabled: Boolean?): AIModel = throw NotImplementedError()
    override suspend fun deleteModel(providerId: String, modelId: String) {}
    override suspend fun listAllModels(): List<AIModel> = models.values.toList()
    override suspend fun fetchRemoteModels(providerId: String): List<RemoteModelInfo> = emptyList()
}
