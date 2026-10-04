package xyz.mederi.infrastructure.koog

import ai.koog.serialization.JSONElement
import ai.koog.serialization.kotlinx.toKotlinxJsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * 把 Koog 工具执行结果（`eventContext.toolResult: JSONElement?`）格式化为落盘/展示的字符串。
 *
 * 根因（与 toolArgs 修复同源）：Koog 的 `JSONLiteral.toString()` 对字符串字面量做
 * `"` + content + `"` 且不转义内部引号/反斜杠——工具返回 `Json.encodeToString(...)` 的
 * JSON 字符串会被 Koog 包成字符串型 JSONLiteral，`.toString()` 产出 `"{"answers":[...]}"`
 * 这种非法 JSON，UI 端 parseAskItems 解析失败后回退把整串塞进 Q1（ask_user 已回答显示错乱）。
 *
 * 格式化规则（与 toolArgs 的 `Json.Default.encodeToString(toolArgs.toKotlinxJsonElement())` 对称）：
 * - 字符串型 primitive（isString=true）→ 取原始 content 原文（即工具真实返回的字符串，
 *   如 AskUserResult 的 JSON），不再包一层引号；
 * - 对象/数组/数字/布尔 → `Json.Default.encodeToString` 规范 JSON；
 * - null → 空串。
 */
internal fun formatToolResultOutput(toolResult: JSONElement?): String {
    if (toolResult == null) return ""
    val ke = toolResult.toKotlinxJsonElement()
    return (ke as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: Json.Default.encodeToString(ke)
}
