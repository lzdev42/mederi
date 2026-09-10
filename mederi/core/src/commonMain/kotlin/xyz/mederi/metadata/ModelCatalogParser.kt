package xyz.mederi.metadata

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import xyz.mederi.provider.domain.model.ReasoningLevel

/**
 * models.dev 目录响应解析（纯函数，无网络依赖，可独立测试）。
 *
 * 目录结构（一次 GET 返回全部 207 家供应商）：
 * ```
 * { "providerId": { "api": "https://...", "name": "...",
 *     "models": { "modelId": { "limit": {...}, "cost": {...}, "modalities": {...},
 *                               "reasoning": true, "reasoning_options": [...] } } } }
 * ```
 *
 * 解析原则：字段全部可空可缺失（各家目录标注完整度不一），缺什么就是 null，
 * 由调用方决定"无值保留本地"还是兜底。
 */

internal val modelsDevJson = Json { ignoreUnknownKeys = true }

/**
 * 整个目录的内存索引。
 *
 * @param byApi baseUrl（归一化）→ modelId → 元数据。自定义供应商只要 baseUrl
 *              撞上目录里任何一家的 api 字段就自动命中。
 * @param byKey 供应商 key → modelId → 元数据。用于 api 字段缺失的目录条目
 *              （如 google），由内置预设显式指定 modelsDevKey。
 */
class CatalogIndex(
    val byApi: Map<String, Map<String, ModelMetadata>>,
    val byKey: Map<String, Map<String, ModelMetadata>>
) {
    fun size(): Int = byKey.values.sumOf { it.size }

    companion object {
        val EMPTY = CatalogIndex(emptyMap(), emptyMap())
    }
}

/** baseUrl 归一化：去尾部斜杠 + 小写。 */
internal fun normalizeBaseUrl(url: String): String = url.trimEnd('/').lowercase()

// ==================== models.dev 响应模型 ====================

@Serializable
internal data class CatalogProvider(
    val id: String? = null,
    val api: String? = null,
    val name: String? = null,
    val models: Map<String, CatalogModel> = emptyMap()
)

@Serializable
internal data class CatalogModel(
    val id: String? = null,
    val name: String? = null,
    val reasoning: Boolean? = null,
    @SerialName("reasoning_options") val reasoningOptions: List<CatalogReasoningOption>? = null,
    val modalities: CatalogModalities? = null,
    val limit: CatalogLimit? = null,
    val cost: CatalogCost? = null
)

@Serializable
internal data class CatalogReasoningOption(
    val type: String? = null,
    // 目录里个别条目的 values 数组内含 null 元素（如 sarvam-105b），声明为可空再过滤
    val values: List<String?> = emptyList()
) {
    val nonNullValues: List<String> get() = values.filterNotNull()
}

@Serializable
internal data class CatalogModalities(
    val input: List<String> = emptyList()
)

@Serializable
internal data class CatalogLimit(
    val context: Int? = null,
    val output: Int? = null
)

/**
 * 价格字段用 JsonElement 宽松解析：目录里绝大多数是数字，但个别条目可能是字符串，
 * 一个字段解析失败不能拖垮整个目录。
 */
@Serializable
internal data class CatalogCost(
    val input: kotlinx.serialization.json.JsonElement? = null,
    val output: kotlinx.serialization.json.JsonElement? = null
) {
    val inputPrice: Double? get() = input?.lenientDouble()
    val outputPrice: Double? get() = output?.lenientDouble()
}

private fun kotlinx.serialization.json.JsonElement.lenientDouble(): Double? = when (this) {
    is JsonPrimitive ->
        if (isString) content.toDoubleOrNull() else doubleOrNull
    else -> null
}

// ==================== 解析入口 ====================

/**
 * 解析 models.dev 全量目录 JSON → [CatalogIndex]。
 *
 * @throws RuntimeException 响应不是合法 JSON 对象时（整体结构错误必须报错，
 *         单个模型字段缺失不算错，目录标注完整度本来就参差）。
 */
internal fun parseModelsDevResponse(raw: String): CatalogIndex {
    val root = try {
        modelsDevJson.decodeFromString<Map<String, CatalogProvider>>(raw)
    } catch (e: Exception) {
        throw RuntimeException("models.dev 目录响应格式异常", e)
    }

    val byApi = mutableMapOf<String, MutableMap<String, ModelMetadata>>()
    val byKey = mutableMapOf<String, MutableMap<String, ModelMetadata>>()

    for ((key, provider) in root) {
        val models = mutableMapOf<String, ModelMetadata>()
        for ((modelId, model) in provider.models) {
            models[modelId] = toModelMetadata(model)
        }
        byKey[key] = models
        provider.api?.takeIf { it.isNotBlank() }?.let { api ->
            byApi[normalizeBaseUrl(api)] = models
        }
    }

    return CatalogIndex(byApi = byApi, byKey = byKey)
}

/**
 * 目录模型条目 → [ModelMetadata]。
 *
 * 推理档位映射规则：
 * - reasoning=false → supportsReasoning=false，无档位
 * - reasoning=true + effort values → 逐值映射 NONE/LOW/MEDIUM/HIGH/MAX（去重保序）：
 *   none→NONE、minimal→LOW、low→LOW、medium→MEDIUM、high→HIGH、xhigh/max→MAX
 * - reasoning=true + toggle → [NONE, HIGH]（开关型：NONE=关，HIGH=开，与 Agnes 内置语义一致）
 * - reasoning=true + budget_tokens → 支持推理但无命名档位（菜单由供应商参数兜底）
 */
internal fun toModelMetadata(model: CatalogModel): ModelMetadata {
    val images = when {
        model.modalities == null -> null
        else -> model.modalities.input.any { it.equals("image", ignoreCase = true) }
    }

    var supportsReasoning: Boolean? = model.reasoning
    var levels: List<ReasoningLevel> = emptyList()
    var optionType: ReasoningOptionType? = null

    if (model.reasoning == true) {
        val options = model.reasoningOptions.orEmpty()
        when {
            // effort 型：{"type":"effort","values":["low","medium","high"]}
            options.any { it.type == "effort" } -> {
                optionType = ReasoningOptionType.EFFORT
                levels = options.filter { it.type == "effort" }
                    .flatMap { it.nonNullValues }
                    .mapNotNull { it.toReasoningLevelOrNull() }
                    .distinct()
            }
            // toggle 型：{"type":"toggle"}
            options.any { it.type == "toggle" } -> {
                optionType = ReasoningOptionType.TOGGLE
                levels = listOf(ReasoningLevel.NONE, ReasoningLevel.HIGH)
            }
            // budget 型：{"type":"budget_tokens"}
            options.any { it.type == "budget_tokens" } -> {
                optionType = ReasoningOptionType.BUDGET
            }
        }
    }

    return ModelMetadata(
        contextWindow = model.limit?.context,
        maxOutputTokens = model.limit?.output,
        inputPricePerMillion = model.cost?.inputPrice,
        outputPricePerMillion = model.cost?.outputPrice,
        supportsImages = images,
        supportsReasoning = supportsReasoning,
        reasoningLevels = levels,
        reasoningOptionType = optionType
    )
}

/**
 * 目录档位名 → Mederi [ReasoningLevel]。
 * 未知档位名返回 null（不猜）。
 */
internal fun String.toReasoningLevelOrNull(): ReasoningLevel? = when (lowercase()) {
    "none" -> ReasoningLevel.NONE
    "minimal", "low" -> ReasoningLevel.LOW
    "medium" -> ReasoningLevel.MEDIUM
    "high" -> ReasoningLevel.HIGH
    "xhigh", "max" -> ReasoningLevel.MAX
    else -> null
}
