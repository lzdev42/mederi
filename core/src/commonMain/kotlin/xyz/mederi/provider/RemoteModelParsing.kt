package xyz.mederi.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.mederi.provider.domain.model.RemoteModelInfo

/**
 * 远端 /models 响应解析（纯函数，无网络依赖，可独立测试）。
 *
 * 网络层（ProviderManagerImpl.fetchRemoteModels）只负责 HTTP 与分页循环，
 * 解析、过滤、字段映射全部在此，用随机生成的响应样本做属性测试。
 */

internal val remoteModelJson = Json { ignoreUnknownKeys = true }

// ==================== OpenAI 风格 ====================

/** OpenAI 风格 /models 响应：{"data":[{"id":...}]} */
@Serializable
internal data class RemoteModelListResponse(val data: List<RemoteModelItemWithMetadata>)

/**
 * vLLM 风格 /models 响应中的模型项：OpenAI 基础字段 + 可选上下文窗口元数据。
 *
 * 实测来源：
 * - Hetzner：返回 `max_model_len`（vLLM 标准字段，如 262144）
 * - Agnes：只返回 OpenAI 基础字段，无任何窗口信息
 *
 * 兼容常见别名（不同推理框架命名不一），全部可选：
 * - max_model_len（vLLM）
 * - context_length / context_window / max_context_length / max_context_tokens
 * - max_tokens / max_output_tokens（注意：这俩是**输出**上限，映射到 maxTokens 而非 contextWindow）
 */
@Serializable
internal data class RemoteModelItemWithMetadata(
    val id: String,
    @SerialName("max_model_len") val maxModelLen: Int? = null,
    @SerialName("context_length") val contextLength: Int? = null,
    @SerialName("context_window") val contextWindow: Int? = null,
    @SerialName("max_context_length") val maxContextLength: Int? = null,
    @SerialName("max_context_tokens") val maxContextTokens: Int? = null,
    @SerialName("max_output_tokens") val maxOutputTokens: Int? = null
) {
    /** 各别名字段中第一个非空的上下文窗口值 */
    val effectiveContextWindow: Int?
        get() = maxModelLen ?: contextWindow ?: contextLength ?: maxContextLength ?: maxContextTokens
}

/**
 * 解析 OpenAI 风格 /models 响应。
 *
 * 严格 OpenAI 端点只返回 id；vLLM 兼容端点（如 Hetzner）额外带上下文窗口元数据
 * （max_model_len 等），有则解析进 RemoteModelInfo，没有则为 null（name 与 id 相同）。
 *
 * @throws RuntimeException 响应不是合法的 OpenAI 模型列表结构时。
 */
internal fun parseOpenAIModelsResponse(raw: String): List<RemoteModelInfo> {
    val parsed = try {
        remoteModelJson.decodeFromString<RemoteModelListResponse>(raw)
    } catch (e: Exception) {
        throw RuntimeException("响应格式异常：无法解析模型列表响应", e)
    }
    return parsed.data.map {
        RemoteModelInfo(
            providerModelId = it.id,
            name = it.id,
            contextWindow = it.effectiveContextWindow,
            maxTokens = it.maxOutputTokens
        )
    }
}

// ==================== Google 原生 ====================

/** Google 原生 models.list 响应：{"models":[...], "nextPageToken": "..."} */
@Serializable
internal data class GoogleModelsResponse(
    // 无默认值：缺失 models 字段说明响应结构不对（如错误页/网关响应），必须报错而不是当空列表
    val models: List<GoogleModel>,
    val nextPageToken: String? = null
)

/**
 * Google 原生 Model 资源（仅解析需要的字段）。
 * name 形如 "models/gemini-2.5-flash"；thinking 为可选布尔字段。
 */
@Serializable
internal data class GoogleModel(
    val name: String = "",
    val displayName: String? = null,
    val inputTokenLimit: Int? = null,
    val outputTokenLimit: Int? = null,
    val supportedGenerationMethods: List<String> = emptyList(),
    val thinking: Boolean? = null
)

/** 一页解析结果：模型列表 + 下一页 token（null = 最后一页） */
internal data class GoogleModelsPage(
    val models: List<RemoteModelInfo>,
    val nextPageToken: String?
)

/**
 * 解析 Google 原生 models.list 单页响应。
 *
 * 远端返回的**全部模型**都进列表（不做 generateContent 过滤，embedding/TTS 等也列出，
 * 用户可手动删除）：
 * - name 去掉 "models/" 前缀作为 providerModelId
 * - displayName 缺省回退为 id
 * - thinking 字段缺失时 supportsReasoning 为 null（合并时保留本地值，不覆盖）
 *
 * @throws RuntimeException 响应不是合法的 Google 模型列表结构时。
 */
internal fun parseGoogleModelsResponse(raw: String): GoogleModelsPage {
    val parsed = try {
        remoteModelJson.decodeFromString<GoogleModelsResponse>(raw)
    } catch (e: Exception) {
        throw RuntimeException("响应格式异常：无法解析 Google 模型列表响应", e)
    }
    val models = parsed.models.mapNotNull { model ->
        val id = model.name.removePrefix("models/")
        if (id.isBlank()) return@mapNotNull null
        RemoteModelInfo(
            providerModelId = id,
            name = model.displayName ?: id,
            contextWindow = model.inputTokenLimit,
            maxTokens = model.outputTokenLimit,
            supportsReasoning = model.thinking
        )
    }
    return GoogleModelsPage(models = models, nextPageToken = parsed.nextPageToken)
}

/**
 * 构建 Google models.list 的查询参数。
 * @param pageToken null 表示第一页。
 */
internal fun googleModelsQueryParameters(pageToken: String?): Map<String, String> =
    buildMap {
        put("pageSize", "1000")
        if (pageToken != null) put("pageToken", pageToken)
    }
