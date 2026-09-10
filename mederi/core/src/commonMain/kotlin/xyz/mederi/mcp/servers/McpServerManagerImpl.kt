package xyz.mederi.mcp.servers

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import xyz.mederi.debug.DebugLog
import xyz.mederi.mcp.servers.domain.McpInstallResult
import xyz.mederi.mcp.servers.domain.McpServerConfig
import xyz.mederi.mcp.servers.domain.McpServerInfo
import xyz.mederi.mcp.servers.domain.McpVerifyResult
import xyz.mederi.mcp.servers.domain.kind
import xyz.mederi.mcp.servers.domain.summary
import xyz.mederi.mcp.servers.infrastructure.McpServersParser
import xyz.mederi.mcp.servers.infrastructure.McpVerifier
import xyz.mederi.store.McpServersStore

/**
 * [McpServerManager] 实现。
 *
 * 持有 [McpServersStore]（唯一真理源的存储侧）+ [McpVerifier]（可用性探测）。
 * 业务规则：
 * - install / update 都是 all-or-nothing：任何一条格式错误，全部不入库
 * - update 不允许改名（条目键必须与 name 一致），改名 = 删除 + 重装
 * - update 保留原 enabled 状态
 */
class McpServerManagerImpl(
    private val store: McpServersStore,
    private val verifier: McpVerifier = McpVerifier()
) : McpServerManager {

    private val prettyJson = Json { prettyPrint = true }

    override suspend fun install(mcpServersJson: String): McpInstallResult {
        val parsed = McpServersParser.parse(mcpServersJson)
        if (!parsed.ok) {
            DebugLog.event("McpServerMgr", "install 格式校验失败: ${parsed.errors.size} 个错误")
            return McpInstallResult(installed = emptyList(), errors = parsed.errors)
        }

        val configs = parsed.entries.map { entry ->
            McpServerConfig(
                name = entry.name,
                enabled = true,
                raw = entry.raw,
                connection = entry.connection
            )
        }
        store.saveAll(configs)
        val installed = configs.map { it.name }
        DebugLog.event("McpServerMgr", "install 完成: $installed")
        return McpInstallResult(installed = installed, errors = emptyList())
    }

    override suspend fun list(): List<McpServerInfo> =
        store.list().map { config ->
            McpServerInfo(
                name = config.name,
                enabled = config.enabled,
                kind = config.connection.kind,
                summary = config.connection.summary
            )
        }

    override suspend fun requireConfig(name: String): McpServerConfig =
        store.get(name) ?: throw NoSuchElementException("MCP server not found: $name")

    override suspend fun getJson(name: String): String {
        val config = requireConfig(name)
        return prettyJson.encodeToString(
            JsonElement.serializer(),
            McpServersParser.wrapDocument(config.name, config.raw)
        )
    }

    override suspend fun update(name: String, mcpServersJson: String): McpInstallResult {
        val existing = store.get(name)
            ?: return McpInstallResult(emptyList(), listOf("[$name] 未安装，无法编辑"))

        val parsed = McpServersParser.parse(mcpServersJson)
        if (!parsed.ok) return McpInstallResult(emptyList(), parsed.errors)
        if (parsed.entries.size != 1) {
            return McpInstallResult(
                emptyList(),
                listOf("编辑时 JSON 必须只包含一条条目（收到 ${parsed.entries.size} 条）")
            )
        }
        val entry = parsed.entries.first()
        if (entry.name != name) {
            return McpInstallResult(
                emptyList(),
                listOf("条目名 \"${entry.name}\" 与编辑目标 \"$name\" 不一致（改名请删除后重新安装）")
            )
        }

        store.save(
            existing.copy(
                raw = entry.raw,
                connection = entry.connection
                // enabled 保留原值：编辑连接方式不应改变启停状态
            )
        )
        DebugLog.event("McpServerMgr", "update 完成: $name")
        return McpInstallResult(installed = listOf(name), errors = emptyList())
    }

    override suspend fun setEnabled(name: String, enabled: Boolean) {
        val config = requireConfig(name)
        store.save(config.copy(enabled = enabled))
        DebugLog.event("McpServerMgr", "setEnabled: $name -> $enabled")
    }

    override suspend fun delete(name: String) {
        requireConfig(name)
        store.delete(name)
        DebugLog.event("McpServerMgr", "delete: $name")
    }

    override suspend fun verify(name: String): McpVerifyResult {
        val config = requireConfig(name)
        val result = verifier.verify(config.connection)
        DebugLog.event("McpServerMgr", "verify $name: ok=${result.ok} (${result.latencyMs}ms)")
        return result
    }

    override suspend fun verifyConfig(mcpServersJson: String): McpVerifyResult {
        val parsed = McpServersParser.parse(mcpServersJson)
        if (!parsed.ok) {
            return McpVerifyResult.failure(0, "格式错误: ${parsed.errors.joinToString("; ")}")
        }

        // 多条目并行握手（互不依赖；结果按原顺序汇总）
        val results = coroutineScope {
            parsed.entries.map { entry ->
                async {
                    entry to verifier.verify(entry.connection)
                }
            }.awaitAll()
        }

        var totalLatency = 0L
        val serverInfos = mutableListOf<String>()
        for ((entry, result) in results) {
            totalLatency += result.latencyMs
            if (!result.ok) {
                return McpVerifyResult.failure(totalLatency, "[${entry.name}] ${result.error}")
            }
            result.serverInfo?.let { serverInfos += "[${entry.name}] $it" }
        }
        return McpVerifyResult.success(totalLatency, serverInfos.joinToString("; ").ifBlank { null })
    }
}
