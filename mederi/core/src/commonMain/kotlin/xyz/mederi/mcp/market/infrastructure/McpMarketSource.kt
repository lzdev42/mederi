package xyz.mederi.mcp.market.infrastructure

import xyz.mederi.mcp.market.domain.McpSearchResult
import xyz.mederi.mcp.market.domain.McpServerDetail

/**
 * MCP 市场数据源抽象。
 *
 * 一个数据源一个实现；查询语义（cursor 翻页 / 详情按 id）统一，UI 与上层不感知源差异。
 * 未来接入其他市场（Smithery / PulseMCP / 自托管 registry）时新增实现即可。
 */
interface McpMarketSource {

    /**
     * 搜索 / 列表（一页）。
     *
     * @param query    关键词（子串匹配，空串 = 不筛，直接列全量分页）
     * @param cursor   翻页书签：来自上一次结果的 [McpSearchResult.nextCursor]，首页传 null
     * @param pageSize 每页条数
     */
    suspend fun search(query: String?, cursor: String?, pageSize: Int): McpSearchResult

    /** 按 id（registry name）取详情。 */
    suspend fun detail(id: String): McpServerDetail
}
