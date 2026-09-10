package xyz.mederi.mcp.market

import xyz.mederi.mcp.market.domain.McpInstallOption
import xyz.mederi.mcp.market.domain.McpSearchResult
import xyz.mederi.mcp.market.domain.McpServerDetail

/**
 * MCP 市场管理（唯一真理源）。
 *
 * 查询全部无状态实时直连（registry 自带 cursor 翻页，每页默认 10 条）；
 * 安装配置生成是纯函数管线，产出标准 mcpServers JSON，交给 MCP 配置模块（安装）消费。
 */
interface McpMarketManager {

    /** 搜索 / 列表（一页）。[cursor] 来自上次结果的 [McpSearchResult.nextCursor]，首页传 null。 */
    suspend fun search(query: String?, cursor: String?, pageSize: Int): McpSearchResult

    /** 按 id 取详情。 */
    suspend fun detail(id: String): McpServerDetail

    /** 列出可安装形态及表单输入（纯函数，不发网络请求）。 */
    fun installOptions(detail: McpServerDetail): List<McpInstallOption>

    /** 生成安装配置（纯函数）。同 [McpClientConfigBuilder.build]。 */
    fun buildInstallConfig(detail: McpServerDetail, optionId: String?, inputs: Map<String, String>): String

    /** 一步到位：按 id 取详情并生成安装配置（UI 只持有 id 时的便捷入口）。 */
    suspend fun installConfig(id: String, optionId: String?, inputs: Map<String, String>): String
}
