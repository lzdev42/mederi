package xyz.mederi.mcp.servers.infrastructure

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import xyz.mederi.mcp.servers.domain.McpConnection

/**
 * mcpServers JSON 解析与格式校验（纯函数，零状态）。
 *
 * 只负责"格式正确性"，不做任何值的语义校验（API key 对不对与本模块无关）。
 *
 * 接受的输入形态：
 * - 标准形态：`{"mcpServers": {"name": {...}, ...}}`
 * - 兼容形态：`{"name": {...}, ...}`（省略 mcpServers 包裹层，条目直接作为根对象成员）
 *
 * 条目格式规则：
 * - stdio：`command`（非空字符串）必需；`args`（字符串数组）、`env`（字符串 map）可选
 * - remote：`url`（http/https 字符串）必需；`headers`（字符串 map）、`type`（"sse" | "streamable-http" | "http"）可选
 * - command 与 url 同时出现 = 错误（歧义）；都不出现 = 错误
 * - 未知字段忽略（对客户端方言宽容：disabled / autoApprove 等）
 */
object McpServersParser {

    private val json = Json { ignoreUnknownKeys = true }

    /** 解析结果：[errors] 非空时 [entries] 为空（all-or-nothing，不部分成功）。 */
    data class Result(val entries: List<Entry>, val errors: List<String>) {
        val ok: Boolean get() = errors.isEmpty()
    }

    /** 解析出的单条条目。[raw] 是该条目对象本身（模型，保留 schema 外的全部原始字段）。 */
    data class Entry(val name: String, val connection: McpConnection, val raw: JsonElement)

    /**
     * 解析 mcpServers JSON。
     * 任何格式错误都收集进 [Result.errors]，不抛异常。
     */
    fun parse(mcpServersJson: String): Result {
        val root = try {
            json.parseToJsonElement(mcpServersJson).jsonObject
        } catch (e: Exception) {
            return Result(emptyList(), listOf("不是合法的 JSON 对象: ${e.message}"))
        }

        val servers: JsonObject = if (root.containsKey("mcpServers")) {
            val inner = root["mcpServers"]
            if (inner !is JsonObject) {
                return Result(emptyList(), listOf("\"mcpServers\" 必须是对象"))
            }
            inner
        } else {
            root
        }

        if (servers.isEmpty()) {
            return Result(emptyList(), listOf("mcpServers 为空，没有可安装的条目"))
        }

        val entries = mutableListOf<Entry>()
        val errors = mutableListOf<String>()

        for ((name, element) in servers) {
            when {
                name.isBlank() -> errors += "存在空白名称的条目"
                element !is JsonObject -> errors += "[$name] 条目必须是对象"
                else -> parseEntry(name, element).let { (entry, entryErrors) ->
                    entry?.let { entries += it }
                    errors += entryErrors
                }
            }
        }

        return if (errors.isEmpty()) Result(entries, emptyList()) else Result(emptyList(), errors)
    }

    /** 把单条条目（模型）与名称组装成标准 mcpServers 文档（getJson 用）。 */
    fun wrapDocument(name: String, raw: JsonElement): JsonElement =
        JsonObject(mapOf("mcpServers" to JsonObject(mapOf(name to raw))))

    // ==================== 内部实现 ====================

    /** 返回 (条目, 错误清单)；有错时条目为 null。错误消息自带 "[name]" 前缀。 */
    private fun parseEntry(name: String, entry: JsonObject): Pair<Entry?, List<String>> {
        val hasCommand = entry["command"] != null
        val hasUrl = entry["url"] != null
        return when {
            hasCommand && hasUrl ->
                null to listOf("[$name] 同时包含 \"command\" 与 \"url\"，无法判断是本地还是远程服务")
            hasCommand -> parseStdio(name, entry)
            hasUrl -> parseRemote(name, entry)
            else -> null to listOf("[$name] 缺少 \"command\"（本地）或 \"url\"（远程）")
        }
    }

    private fun parseStdio(name: String, entry: JsonObject): Pair<Entry?, List<String>> {
        val errors = mutableListOf<String>()

        val command = (entry["command"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (command.isNullOrBlank()) errors += "[$name] \"command\" 必须是非空字符串"

        val args = mutableListOf<String>()
        when (val argsEl = entry["args"]) {
            null -> {}
            is JsonArray -> argsEl.forEachIndexed { index, el ->
                val arg = (el as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (arg != null) args += arg else errors += "[$name] args[$index] 必须是字符串"
            }
            else -> errors += "[$name] \"args\" 必须是字符串数组"
        }

        val env = when (val envEl = entry["env"]) {
            null -> emptyMap()
            is JsonObject -> stringMap(envEl) { errors += "[$name] $it" }
            else -> { errors += "[$name] \"env\" 必须是对象"; null }
        }

        if (errors.isNotEmpty()) return null to errors
        return Entry(name, McpConnection.Stdio(command!!, args, env!!), entry) to emptyList()
    }

    private fun parseRemote(name: String, entry: JsonObject): Pair<Entry?, List<String>> {
        val errors = mutableListOf<String>()

        val url = (entry["url"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        when {
            url.isNullOrBlank() -> errors += "[$name] \"url\" 必须是非空字符串"
            !url.startsWith("http://") && !url.startsWith("https://") ->
                errors += "[$name] \"url\" 必须以 http:// 或 https:// 开头"
        }

        val headers = when (val headersEl = entry["headers"]) {
            null -> emptyMap()
            is JsonObject -> stringMap(headersEl) { errors += "[$name] $it" }
            else -> { errors += "[$name] \"headers\" 必须是对象"; null }
        }

        val type = when (val typeEl = entry["type"]) {
            null -> null
            is JsonPrimitive -> when (typeEl.content) {
                "sse" -> "sse"
                "streamable-http", "http" -> "streamable-http"
                else -> { errors += "[$name] \"type\" 只支持 sse / streamable-http（收到: ${typeEl.content}）"; null }
            }
            else -> { errors += "[$name] \"type\" 必须是字符串"; null }
        }

        if (errors.isNotEmpty()) return null to errors
        return Entry(name, McpConnection.Remote(url!!, headers!!, type), entry) to emptyList()
    }

    /** 校验并提取"全部值为字符串"的对象；值不是字符串时报告错误并跳过该键。 */
    private fun stringMap(obj: JsonObject, report: (String) -> Unit): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for ((key, el) in obj) {
            val value = (el as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (value == null) report("\"$key\" 的值必须是字符串") else result[key] = value
        }
        return result
    }
}
