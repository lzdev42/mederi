package xyz.mederi.mcp.market

import xyz.mederi.debug.DebugLog
import xyz.mederi.mcp.market.domain.McpInstallOption
import xyz.mederi.mcp.market.domain.McpSearchResult
import xyz.mederi.mcp.market.domain.McpServerDetail
import xyz.mederi.mcp.market.infrastructure.McpMarketSource

/**
 * [McpMarketManager] 实现：source 路由 + 纯函数构建器，自身零状态。
 */
class McpMarketManagerImpl(
    private val source: McpMarketSource,
    private val builder: McpClientConfigBuilder = McpClientConfigBuilder
) : McpMarketManager {

    override suspend fun search(query: String?, cursor: String?, pageSize: Int): McpSearchResult {
        val result = source.search(query, cursor, pageSize)
        DebugLog.event(
            "McpMarketMgr",
            "search: query=$query cursor=$cursor -> ${result.items.size} 条, nextCursor=${result.nextCursor}"
        )
        return result
    }

    override suspend fun detail(id: String): McpServerDetail {
        val detail = source.detail(id)
        DebugLog.event("McpMarketMgr", "detail: $id -> packages=${detail.packages.size}, remotes=${detail.remotes.size}")
        return detail
    }

    override fun installOptions(detail: McpServerDetail): List<McpInstallOption> =
        builder.installOptions(detail)

    override fun buildInstallConfig(
        detail: McpServerDetail,
        optionId: String?,
        inputs: Map<String, String>
    ): String = builder.build(detail, optionId, inputs)

    override suspend fun installConfig(id: String, optionId: String?, inputs: Map<String, String>): String =
        buildInstallConfig(detail(id), optionId, inputs)
}
