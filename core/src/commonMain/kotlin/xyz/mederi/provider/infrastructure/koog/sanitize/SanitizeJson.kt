package xyz.mederi.provider.infrastructure.koog.sanitize

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.serializer
import xyz.mederi.infrastructure.koog.JsonRecordKind
import xyz.mederi.infrastructure.koog.jsonRecordException

/**
 * Sanitized 模型专用的 Json 实例。
 *
 * 与 Koog [AbstractOpenAILLMClient] 内部的 `defaultJson` 配置一致：
 * - `ignoreUnknownKeys = true`：忽略 vLLM 内部字段（prompt_logprobs / token_ids / routed_experts 等）
 * - `namingStrategy = SnakeCase`：Kotlin 驼峰属性映射到 JSON snake_case（reasoningContent → reasoning_content）
 * - `explicitNulls = false`：不序列化 null 字段
 * - `coerceInputValues = true`：显式 null（如 Responses `output_item.added` 的 `"content": null`）回退到默认值
 *
 * [SanitizedStreamDelta.reasoningAlias] / [SanitizedAssistantMessage.reasoningAlias] 用 `@SerialName("reasoning")`
 * 显式指定 JSON 字段名，覆盖 SnakeCase 策略，用于兜底解析 vLLM 的非标准 `reasoning` 字段。
 */
@OptIn(ExperimentalSerializationApi::class)
val sanitizeJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    explicitNulls = false
    coerceInputValues = true
    namingStrategy = JsonNamingStrategy.SnakeCase
}

internal inline fun <reified T> decodeReceivedJson(
    data: String,
    kind: JsonRecordKind,
    checkError: (String) -> Unit,
): T = try {
    checkError(data)
    sanitizeJson.decodeFromString<T>(data)
} catch (_: SerializationException) {
    throw jsonRecordException("Invalid provider JSON", kind, data, responseFieldNames(serializer<T>().descriptor))
}

@OptIn(ExperimentalSerializationApi::class)
internal fun responseFieldNames(root: SerialDescriptor): Set<String> = buildSet {
    // The failure envelope carries its error object as an opaque JSON object.
    addAll(listOf("error", "message", "code", "param"))
    val visited = mutableSetOf<SerialDescriptor>()
    fun collect(descriptor: SerialDescriptor) {
        if (!visited.add(descriptor)) return
        when (descriptor.kind) {
            StructureKind.CLASS, StructureKind.OBJECT -> repeat(descriptor.elementsCount) { index ->
                val name = descriptor.getElementName(index)
                add(JsonNamingStrategy.SnakeCase.serialNameForJson(descriptor, index, name))
                collect(descriptor.getElementDescriptor(index))
            }
            StructureKind.LIST -> collect(descriptor.getElementDescriptor(0))
            else -> Unit
        }
    }
    collect(root)
}
