package xyz.mederi.provider.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import xyz.mederi.domain.model.AIModel

/**
 * 供应商聚合根（可序列化，用于 JSON 配置）。
 *
 * 注意：API 密钥（[apiKeys]）不写入 JSON，存在 SQLite 中。
 * 加载 Provider 时由 ProviderManager 从 ApiKeyStore 合并。
 *
 * @param id 系统生成的供应商唯一 ID。
 * @param name 供应商显示名称。
 * @param type 供应商类型。
 * @param baseUrl 用户输入的完整端点，如 "https://api.openai.com/v1"。
 * @param apiKeys API 密钥列表（运行时从 SQLite 合并，不序列化）。
 * @param reasoningParameter 推理参数配置，null 表示不支持 reasoning。
 * @param models 模型列表（AIModel）。
 * @param responseSanitization 是否需要响应清洗（供应商返回字段不标准，如 vLLM 兼容端点）。
 *   注意：配了 [reasoningParameter] 的供应商会自动使用 [MederiOpenAILLMClient]（能解析推理内容），
 *   无需额外开启此标志。此标志主要用于无推理但有非标准字段名的供应商。
 * @param modelsDevKey models.dev 目录的供应商 key（如 "agnes"、"opencode"）。
 *   用于模型元数据同步时的精确匹配；null = 按端点 baseUrl 自动匹配目录。
 *   内置供应商创建时写入，自定义供应商通常为 null。
 */
@Serializable
data class Provider(
    val id: String,
    val name: String,
    val type: ProviderType,
    val baseUrl: String,
    val reasoningParameter: ReasoningParameter? = null,
    val models: List<AIModel> = emptyList(),
    val responseSanitization: Boolean = false,
    val modelsDevKey: String? = null,
    @Transient
    val apiKeys: List<ProviderApiKey> = emptyList()
) {
    /**
     * 获取默认 API 密钥。
     */
    val defaultApiKey: ProviderApiKey?
        get() = apiKeys.find { it.isDefault } ?: apiKeys.firstOrNull()

    /**
     * 是否支持 reasoning。
     */
    val supportsReasoning: Boolean
        get() = reasoningParameter != null && models.any { it.supportsReasoning }

    /**
     * 根据模型 ID 获取模型。
     */
    fun getModel(modelId: String): AIModel? = models.find { it.id == modelId }

    /**
     * 根据供应商模型 ID 获取模型。
     */
    fun getModelByProviderId(providerModelId: String): AIModel? =
        models.find { it.providerModelId == providerModelId }
}
