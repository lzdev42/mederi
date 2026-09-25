package xyz.mederi.koog

import ai.koog.serialization.JSONLiteral
import ai.koog.serialization.JSONObject
import ai.koog.serialization.kotlinx.toKotlinxJsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * 回归保护：TOOL_CALLED 事件 args 必须走标准 JSON 序列化（TurnExecutor.kt onToolCallStarting）。
 *
 * 历史事故根因：Koog `JSONObject.toString()` 是手拼的——key 用引号包，字符串值原样包引号但不转义
 * 内部双引号/反斜杠（见 ai.koog.serialization JSONElement.kt 源码）。命令里带 `"`（如
 * `grep -n "SUMMARY" ...`）或 `\|` 时产出非法 JSON，UI 端 ToolArgParser 严格解析失败返回空 map，
 * 工具行目标（command/path）显示丢失，降级为纯"运行命令"。
 *
 * 修复 = `Json.encodeToString(toolArgs.toKotlinxJsonElement())`，本测试锁定两条不变量：
 * 1. 旧路径（toString）对含引号命令必须仍是非法 JSON（若未来有人改回 toString，这里立即红）；
 * 2. 新路径输出合法 JSON 且 round-trip 后参数值逐字符精确（引号、反斜杠原样保留）。
 */
class KoogToolArgsSerializationTest {

    private val command = "grep -n \"SUMMARY\\|Summary\\|ChatMessage(\" mederi/app/shared/src/jvmMain/... | head -30"

    private fun sampleArgs(): JSONObject = JSONObject(
        mapOf(
            "command" to JSONLiteral(content = command, isString = true),
            "timeout_seconds" to JSONLiteral(content = "30", isString = false),
        )
    )

    @Test
    fun koogJsonObjectToStringIsInvalidJsonForQuotedCommand() {
        // 旧路径：Koog 手拼 toString 不转义内部引号/反斜杠 → 非法 JSON（bug 复现）
        val legacy = sampleArgs().toString()
        assertTrue(runCatching { Json.parseToJsonElement(legacy) }.isFailure, "legacy 输出应当非法：$legacy")
    }

    @Test
    fun kotlinxSerializedArgsIsValidJsonAndRoundTripsExactly() {
        val obj = sampleArgs()
        val json = Json.Default.encodeToString(obj.toKotlinxJsonElement())

        // 合法 JSON（不抛异常即通过）
        val parsed = Json.parseToJsonElement(json).jsonObject

        // 值逐字符精确：引号、反斜杠、管道符全部原样
        assertEquals(command, parsed["command"]!!.jsonPrimitive.content)
        assertEquals("30", parsed["timeout_seconds"]!!.jsonPrimitive.content)

        // 明确断言修复路径：反序列化回 Koog 后与源对象一致（双向往返）
        assertEquals(obj.entries.keys, parsed.keys)
    }
}
