package xyz.mederi.api

import xyz.mederi.domain.model.AIModel
import xyz.mederi.provider.domain.model.Provider
import xyz.mederi.provider.domain.model.ProviderApiKey
import xyz.mederi.provider.domain.model.ReasoningLevel

/**
 * Provider API。
 */
interface ProviderApi {

    suspend fun list(): List<Provider>
    suspend fun create(request: CreateProviderRequest): Provider
    suspend fun get(id: String): Provider
    suspend fun update(id: String, request: UpdateProviderRequest): Provider
    suspend fun delete(id: String)

    suspend fun addKey(providerId: String, request: CreateApiKeyRequest): ProviderApiKey
    suspend fun listKeys(providerId: String): List<ProviderApiKey>
    suspend fun deleteKey(providerId: String, keyId: String)
    suspend fun setDefaultKey(providerId: String, keyId: String)

    suspend fun listModels(providerId: String): List<AIModel>
    suspend fun addModel(providerId: String, request: CreateModelRequest): AIModel
    suspend fun updateModel(providerId: String, modelId: String, request: UpdateModelRequest): AIModel
    suspend fun deleteModel(providerId: String, modelId: String)
    suspend fun refreshModels(providerId: String): List<String>
}

/**
 * 创建供应商请求。
 */
data class CreateProviderRequest(
    val name: String,
    val type: ProviderTypeRequest,
    val baseUrl: String,
    val apiKeys: List<CreateApiKeyRequest> = emptyList(),
    val models: List<CreateModelRequest> = emptyList(),
    val reasoningParameter: ReasoningParameterRequest? = null,
    val responseSanitization: Boolean = false,
    val fetchModels: Boolean = true,
    /**
     * models.dev 目录的供应商 key（如 "agnes"、"opencode"）。null = 目录按端点 baseUrl 匹配。
     * 内置供应商创建时由预设写入；自定义供应商一般留空。
     */
    val modelsDevKey: String? = null
)

/**
 * 更新供应商请求。
 */
data class UpdateProviderRequest(
    val name: String? = null,
    val baseUrl: String? = null,
    val reasoningParameter: ReasoningParameterRequest? = null,
    val modelsDevKey: String? = null
)

/**
 * 创建 API Key 请求。
 */
data class CreateApiKeyRequest(
    val name: String,
    val value: String,
    val isDefault: Boolean = false
)

/**
 * 创建模型请求。
 */
data class CreateModelRequest(
    val providerModelId: String,
    val name: String,
    val supportsReasoning: Boolean = false,
    val reasoningLevel: ReasoningLevel = ReasoningLevel.NONE,
    val contextWindow: Int? = null,
    val maxTokens: Int? = null,
    val supportsImages: Boolean = false,
    val reasoningLevels: List<ReasoningLevel> = emptyList(),
    val isEnabled: Boolean = true
)

/**
 * 更新模型请求。
 */
data class UpdateModelRequest(
    val name: String? = null,
    val supportsReasoning: Boolean? = null,
    val reasoningLevel: ReasoningLevel? = null,
    val contextWindow: Int? = null,
    val maxTokens: Int? = null,
    val supportsImages: Boolean? = null,
    val reasoningLevels: List<ReasoningLevel>? = null,
    val isEnabled: Boolean? = null
)

/**
 * 供应商类型请求（简化字符串，如 "OPENAI_CHAT"）。
 */
typealias ProviderTypeRequest = String

/**
 * Reasoning 参数请求。
 */
typealias ReasoningParameterRequest = xyz.mederi.provider.domain.model.ReasoningParameter
