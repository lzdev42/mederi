package xyz.mederi.core.contract.models

import kotlinx.serialization.Serializable

/**
 * 供应商协议类型。枚举名与 core 的 ProviderType 一一对应，通过 name 互转。
 */
enum class ProtocolType(val displayName: String, val placeholderUrl: String) {
    OPENAI_CHAT("OpenAI 兼容", "https://api.openai.com/v1"),
    OPENAI_RESPONSES("OpenAI Responses", "https://api.openai.com/v1"),
    GOOGLE("Google Gemini", "https://generativelanguage.googleapis.com");

    companion object {
        val DEFAULT get() = OPENAI_CHAT
        val ALL get() = entries.toList()
        val CREATABLE get() = listOf(OPENAI_CHAT, OPENAI_RESPONSES)
    }
}

/**
 * 推理级别常量。名称与 core 的 ReasoningLevel 枚举一一对应。
 */
object ReasoningLevels {
    /** 全序列唯一真理源（含 NONE 关闭档，按显示顺序） */
    val ORDER = listOf("NONE", "MINIMAL", "LOW", "MEDIUM", "HIGH", "XHIGH", "MAX")

    /** 可选档位（去 NONE），派生自 [ORDER] */
    val SELECTABLE = ORDER.filter { it != "NONE" }

    /** 是否为激活档位（非 NONE）。NONE 是"关闭"语义，不算有效档位。 */
    fun isActiveLevel(level: String): Boolean = !level.equals("NONE", ignoreCase = true)
}

enum class ProviderType { Builtin, Custom }

@Serializable
data class CustomModelEntry(
    val id: String,
    val name: String,
    val supportsThinking: Boolean = false,
    val supportsImages: Boolean = false,
    val contextWindow: Int? = null,
    val maxTokens: Int? = null,
    val reasoningLevels: List<String> = emptyList(),
    val isEnabled: Boolean = true,
)

@Serializable
data class ApiKeyOption(
    val id: String,
    val name: String,
    val maskedValue: String,
    val isDefault: Boolean,
)

@Serializable
data class ProviderConfig(
    val id: String,
    val name: String,
    val type: ProviderType,
    val baseUrl: String?,
    val isConnected: Boolean,
    val models: List<ModelOption>,
    val customModels: List<CustomModelEntry> = emptyList(),
    val supportsApiKey: Boolean,
    val supportsBaseUrl: Boolean,
    val protocolType: ProtocolType = ProtocolType.DEFAULT,
    val apiKeys: List<ApiKeyOption> = emptyList(),
    /** 推理参数配置（v6）：级别名（NONE/LOW/MEDIUM/HIGH/MAX）→ 请求体 JSON 片段/null，空 map 表示未配置 */
    val reasoningLevels: Map<String, String?> = emptyMap(),
    val responseSanitization: Boolean = false,
)
