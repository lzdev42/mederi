package xyz.mederi.mcp.servers.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 单个 MCP server 的配置 = 标准 mcpServers JSON 中一个条目的结构化表示。
 *
 * 是 MCP 配置模块的领域模型：
 * - [name] 是 mcpServers 里的键（如 "context7"），在配置集合内唯一。
 * - [raw] 保存该条目的原始 JSON 元素（模型，非字符串拼装），getJson 用它与 name 组装标准文档，
 *   保证编辑场景的精确往返（结构化提取只保留 schema 内字段，raw 保留原文全部字段）。
 * - [connection] 是提取后的结构化连接方式，将来的内核 MCP 引擎直接消费它建立连接。
 *
 * 注意：本模型只关心"格式是否正确"，不校验值的有效性（API key 填错是用户的事）。
 */
@Serializable
data class McpServerConfig(
    val name: String,
    val enabled: Boolean = true,
    val raw: JsonElement,
    val connection: McpConnection
)

/**
 * MCP 连接方式。对应 mcpServers 条目的两种形态：
 * - stdio：本地进程（command + args + env）
 * - remote：远程 HTTP 服务（url + headers）
 */
@Serializable
sealed class McpConnection {

    /**
     * 本地 stdio 进程。
     *
     * @param command 可执行命令（含 runner，如 "npx"/"docker"，或直接二进制名）
     * @param args    命令参数
     * @param env     进程环境变量
     */
    @Serializable
    @SerialName("stdio")
    data class Stdio(
        val command: String,
        val args: List<String> = emptyList(),
        val env: Map<String, String> = emptyMap()
    ) : McpConnection()

    /**
     * 远程 HTTP 服务。
     *
     * @param url           服务端点
     * @param headers       HTTP 请求头
     * @param transportType 原始 JSON 中标注的传输类型（"sse" / "streamable-http"），未标注为 null，
     *                      探活时自动探测。
     */
    @Serializable
    @SerialName("remote")
    data class Remote(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        val transportType: String? = null
    ) : McpConnection()
}

/** 连接方式种类："stdio" 或 "remote"。 */
val McpConnection.kind: String
    get() = when (this) {
        is McpConnection.Stdio -> "stdio"
        is McpConnection.Remote -> "remote"
    }

/** 人类可读的单行摘要（列表展示用）：命令行或 URL。 */
val McpConnection.summary: String
    get() = when (this) {
        is McpConnection.Stdio -> (listOf(command) + args).joinToString(" ")
        is McpConnection.Remote -> url
    }
