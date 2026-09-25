package xyz.mederi.core.contract

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * 工具参数 JSON 的宽容解析：把扁平对象解析为 `Map<String, String>`。
 *
 * 与 `Json.decodeFromString<Map<String, String>>` 的区别：LLM 经常在参数里混入数字/布尔值
 * （如 read_file 的 `max_lines`、execute_command 的 `timeout_seconds`），严格反序列化遇到
 * 任一非字符串值就整体抛错，导致 `path` / `command` 等关键信息全部丢失——UI 工具行只剩一个勾，
 * 路径、命令全不显示（历史事故根因）。这里逐值降级：
 * - 字符串 → 原值（不带引号）；
 * - 数字 / 布尔 → 字面量文本（"200" / "true"）；
 * - 嵌套对象 / 数组 → 保留 JSON 文本。
 *
 * 非对象结构（数组、标量）或解析失败返回空 map，不崩溃。
 */
object ToolArgParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(args: String): Map<String, String> {
        if (args.isBlank()) return emptyMap()
        return try {
            val obj = json.parseToJsonElement(args).jsonObject
            obj.mapValues { (_, element) ->
                if (element is JsonPrimitive) element.content else element.toString()
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
