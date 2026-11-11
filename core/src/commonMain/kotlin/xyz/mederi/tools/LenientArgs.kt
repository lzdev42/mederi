package xyz.mederi.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 工具入参「复合对象列表字段」形状矫正共享 helper（plan 工具与 ask_user 复用）。
 *
 * 模型（实测 glm-5.3，sess_f01ec14e / sess_be789501 seq95 / sess_e5a60397 seq119）对
 * 复合列表字段有两种稳定错形：
 * - 把列表序列化成 JSON 字符串（`"questions": "[{...}]"`，双层编码）
   → kotlinx 报 "Expected JsonArray, but had JsonLiteral as the serialized body of kotlin.collections.ArrayList"
 * - 把单个对象直接当列表（`"decisions": {...}`）→ "Expected JsonArray, but had JsonObject"
 *
 * 触发条件常见于文本内嵌 ASCII 引号——模型在嵌套转义上滑倒后把整个数组包成字符串。
 * 解析失败会作为工具结果回喂、模型一轮可自纠，但每复发一次浪费一轮 LLM 往返 + UI 红错，
 * 因此在解码侧做无歧义矫正。
 *
 * 严格语义（不兜底原则）：只修复无歧义形态，修不了就原样放行，让原始解码错误浮出——
 * 静默兜底成空列表会吞掉模型的真实意图，比报错更糟：
 * - JsonArray → 原样；
 * - JsonObject → 包成单元素数组；
 * - 字符串，内容解析成 JsonArray → 用解析结果；解析成 JsonObject → 包单元素数组；
 * - 其余（字符串解析失败 / 解析成标量 / 非字符串字面量 / null）→ 原样返回。
 */
internal fun coerceObjectListField(element: JsonElement, fieldName: String): JsonElement {
    if (element !is JsonObject) return element
    val value = element[fieldName] ?: return element
    val fixed: JsonElement = when (value) {
        is JsonArray -> value
        is JsonObject -> JsonArray(listOf(value))
        is JsonPrimitive -> if (value.isString) {
            when (val parsed = runCatching { Json.parseToJsonElement(value.content) }.getOrNull()) {
                is JsonArray -> parsed
                is JsonObject -> JsonArray(listOf(parsed))
                // 解析失败 / 解析成标量：保留原值，让原始解码错误浮出（不兜底空数组）
                else -> value
            }
        } else {
            // 非字符串字面量（数字/布尔/null）：保留原值
            value
        }
        else -> value
    }
    return JsonObject(element.toMutableMap().apply { put(fieldName, fixed) })
}
