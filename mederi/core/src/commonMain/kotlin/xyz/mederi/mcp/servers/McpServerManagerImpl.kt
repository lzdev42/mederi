package xyz.mederi.mcp.servers

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import xyz.mederi.debug.DebugLog
import xyz.mederi.mcp.engine.McpConnector
import xyz.mederi.mcp.servers.domain.McpDiscoveryResult
import xyz.mederi.mcp.servers.domain.McpInstallResult
import xyz.mederi.mcp.servers.domain.McpServerConfig
import xyz.mederi.mcp.servers.domain.McpServerInfo
import xyz.mederi.mcp.servers.domain.McpServerStatus
import xyz.mederi.mcp.servers.domain.McpVerifyResult
import xyz.mederi.mcp.servers.domain.kind
import xyz.mederi.mcp.servers.domain.summary
import xyz.mederi.mcp.servers.infrastructure.McpServersParser
import xyz.mederi.store.McpServersStore
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * [McpServerManager] 实现。
 *
 * 持有 [McpServersStore]（唯一真理源的存储侧）+ [McpConnector]（真连接：可用性检查 / 工具发现）。
 * 业务规则：
 * - install / update 都是 all-or-nothing：任何一条格式错误，全部不入库
 * - update 不允许改名（条目键必须与 name 一致），改名 = 删除 + 重装
 * - update 保留原 enabled 状态
 * - install / update / setEnabled(true) 后自动做一次可用性检查，结果进内存缓存；
 *   [list] 把缓存状态合并进 [McpServerInfo]，UI 直接读
 */
class McpServerManagerImpl(
    private val store: McpServersStore,
    private val connector: McpConnector = McpConnector(store)
) : McpServerManager {

    private val prettyJson = Json { prettyPrint = true }

    /** 运行时状态缓存（name → 最近一次检查结果）。非配置，不入库，重启归零。 */
    private val statusCache = ConcurrentHashMap<String, CachedStatus>()

    private data class CachedStatus(
        val status: McpServerStatus,
        val toolCount: Int?,
        val lastError: String?,
        val lastCheckedAt: String
    )

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
        // 新装/覆盖后立即探测，用户马上能看到能不能连
        refreshStatus(configs)
        return McpInstallResult(installed = installed, errors = emptyList())
    }

    override suspend fun list(): List<McpServerInfo> =
        store.list().map { config ->
            val cached = statusCache[config.name]
            McpServerInfo(
                name = config.name,
                enabled = config.enabled,
                kind = config.connection.kind,
                summary = config.connection.summary,
                status = cached?.status ?: McpServerStatus.UNCHECKED,
                toolCount = cached?.toolCount,
                lastError = cached?.lastError,
                lastCheckedAt = cached?.lastCheckedAt
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

        val updated = existing.copy(
            raw = entry.raw,
            connection = entry.connection
            // enabled 保留原值：编辑连接方式不应改变启停状态
        )
        store.save(updated)
        DebugLog.event("McpServerMgr", "update 完成: $name")
        // 连接方式变了，立即重新探测
        refreshStatus(listOf(updated))
        return McpInstallResult(installed = listOf(name), errors = emptyList())
    }

    override suspend fun setEnabled(name: String, enabled: Boolean) {
        val config = requireConfig(name)
        store.save(config.copy(enabled = enabled))
        DebugLog.event("McpServerMgr", "setEnabled: $name -> $enabled")
        if (enabled) {
            refreshStatus(listOf(config.copy(enabled = true)))
        } else {
            // 禁用即停止关心可用性：清掉缓存，避免列表还挂着旧的 FAILED
            statusCache.remove(name)
        }
    }

    override suspend fun delete(name: String) {
        requireConfig(name)
        store.delete(name)
        statusCache.remove(name)
        DebugLog.event("McpServerMgr", "delete: $name")
    }

    override suspend fun discover(name: String): McpDiscoveryResult {
        requireConfig(name)
        return connector.discover(name)
    }

    override suspend fun verify(name: String): McpVerifyResult {
        val config = requireConfig(name)
        val result = connector.verify(config.connection)
        cache(config.name, result)
        DebugLog.event("McpServerMgr", "verify $name: ok=${result.ok} (${result.latencyMs}ms, ${result.toolCount ?: "?"} tools)")
        return result
    }

    override suspend fun verifyAll(): Map<String, McpVerifyResult> {
        val configs = store.list().filter { it.enabled }
        if (configs.isEmpty()) return emptyMap()

        val results = coroutineScope {
            configs.map { config ->
                async { config.name to connector.verify(config.connection) }
            }.awaitAll()
        }
        results.forEach { (name, result) -> cache(name, result) }
        DebugLog.event("McpServerMgr", "verifyAll: ${results.size} 个 server")
        return results.toMap()
    }

    override suspend fun verifyConfig(mcpServersJson: String): McpVerifyResult {
        val parsed = McpServersParser.parse(mcpServersJson)
        if (!parsed.ok) {
            return McpVerifyResult.failure(0, "格式错误: ${parsed.errors.joinToString("; ")}")
        }

        // 多条目并行握手（互不依赖；结果按原顺序汇总）
        val results = coroutineScope {
            parsed.entries.map { entry ->
                async { entry to connector.verify(entry.connection) }
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

    // ==================== 内部 ====================

    /** 对指定配置逐个做可用性检查，结果写缓存。任一失败不抛异常（自动检查是尽力而为）。 */
    private suspend fun refreshStatus(configs: List<McpServerConfig>) {
        coroutineScope {
            configs.map { config ->
                async {
                    val result = try {
                        connector.verify(config.connection)
                    } catch (e: Exception) {
                        DebugLog.error("McpServerMgr", "自动检查 ${config.name} 失败: ${e.message}", e)
                        McpVerifyResult.failure(0, "自动检查异常: ${e.message}")
                    }
                    cache(config.name, result)
                }
            }.awaitAll()
        }
    }

    private fun cache(name: String, result: McpVerifyResult) {
        statusCache[name] = CachedStatus(
            status = if (result.ok) McpServerStatus.OK else McpServerStatus.FAILED,
            toolCount = result.toolCount,
            lastError = result.error,
            lastCheckedAt = Instant.now().toString()
        )
    }
}
