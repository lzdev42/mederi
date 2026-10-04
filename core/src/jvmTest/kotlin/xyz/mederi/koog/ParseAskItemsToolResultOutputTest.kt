package xyz.mederi.infrastructure.koog

import ai.koog.serialization.JSONLiteral
import ai.koog.serialization.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * formatToolResultOutput 根因回归（ask_user 已回答显示修复的 core 侧一半）。
 *
 * 根因：Koog `JSONLiteral.toString()` 对 isString=true 的字符串字面量做 `"` + content + `"`
 * 且不转义内部引号/反斜杠——ask_user 工具返回 `Json.encodeToString(AskUserResult...)` 的
 * JSON 字符串被 Koog 包成字符串型 JSONLiteral 后，`.toString()` 产出 `"{"answers":[...]}"`
 * 非法 JSON，UI 端 parseAskItems 解析失败、整串塞进 Q1。
 *
 * 类名带 ParseAskItems 前缀：本函数正是 parseAskItems 上游 output 的格式化根因修复，
 * 与 app:shared 的 ParseAskItemsTest 同属一条回归链路（验证命令按该模式过滤两模块）。
 */
class ParseAskItemsToolResultOutputTest {

    private val askUserJson =
        """{"answers":[{"questionId":"q1","answers":["Kotlin"]},{"questionId":"q2","answers":["core","app"]}]}"""

    @Test
    fun stringLiteralReturnsRawContentWithoutOuterQuotes() {
        // Koog 把 ask_user 的 JSON 字符串返回包成 isString=true 的 JSONLiteral
        val koogResult = JSONLiteral(content = askUserJson, isString = true)

        val output = formatToolResultOutput(koogResult)

        // 必须取回工具真实返回的干净 JSON（无外层引号、非非法 JSON）
        assertEquals(askUserJson, output)
        assertFalse(output.startsWith("\""), "不允许再包一层引号：$output")
        // 产物必须是合法 JSON（旧 .toString() 产物无法解析）
        kotlinx.serialization.json.Json.parseToJsonElement(output)
    }

    @Test
    fun koogLegacyToStringWouldProduceInvalidJson() {
        // 反例固化：旧实现 eventContext.toolResult.toString() 的产物确实是非法 JSON
        val koogResult = JSONLiteral(content = askUserJson, isString = true)
        val legacy = koogResult.toString()
        assertTrue(legacy.startsWith("\"") && legacy.endsWith("\""))
        // 预期：非法 JSON，解析失败（证明根因存在，formatToolResultOutput 必须绕开 .toString()）
        var threw = false
        try {
            kotlinx.serialization.json.Json.parseToJsonElement(legacy)
        } catch (e: kotlinx.serialization.SerializationException) {
            threw = true
        }
        assertTrue(threw, "旧 toString() 产物应当是非法 JSON：$legacy")
    }

    @Test
    fun jsonObjectReturnsCanonicalJson() {
        val koogResult = JSONObject(
            entries = linkedMapOf(
                "tool" to JSONLiteral("ask_user", isString = true),
                "ok" to JSONLiteral("true", isString = false),
                "count" to JSONLiteral("42", isString = false),
            )
        )

        assertEquals("""{"tool":"ask_user","ok":true,"count":42}""", formatToolResultOutput(koogResult))
    }

    @Test
    fun nullReturnsEmptyString() {
        assertEquals("", formatToolResultOutput(null))
    }
}
