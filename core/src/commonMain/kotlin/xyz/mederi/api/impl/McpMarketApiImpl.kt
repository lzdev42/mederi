package xyz.mederi.api.impl

import xyz.mederi.api.McpMarketApi
import xyz.mederi.api.exception.mederiCall
import xyz.mederi.mcp.market.McpMarketManager
import xyz.mederi.mcp.market.domain.McpInstallOption
import xyz.mederi.mcp.market.domain.McpSearchResult
import xyz.mederi.mcp.market.domain.McpServerDetail

/**
 * McpMarketApi 实现（薄转调 + 异常转换）。
 */
class McpMarketApiImpl(private val manager: McpMarketManager) : McpMarketApi {

    override suspend fun search(query: String, cursor: String?, pageSize: Int): McpSearchResult = mederiCall {
        manager.search(query, cursor, pageSize)
    }

    override suspend fun detail(id: String): McpServerDetail = mederiCall { manager.detail(id) }

    override fun installOptions(detail: McpServerDetail): List<McpInstallOption> = mederiCall {
        manager.installOptions(detail)
    }

    override fun installConfig(
        detail: McpServerDetail,
        optionId: String?,
        inputs: Map<String, String>
    ): String = mederiCall { manager.buildInstallConfig(detail, optionId, inputs) }

    override suspend fun installConfig(
        id: String,
        optionId: String?,
        inputs: Map<String, String>
    ): String = mederiCall { manager.installConfig(id, optionId, inputs) }
}
