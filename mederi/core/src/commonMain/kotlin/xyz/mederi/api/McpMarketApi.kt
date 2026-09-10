package xyz.mederi.api

import xyz.mederi.mcp.market.domain.McpInstallOption
import xyz.mederi.mcp.market.domain.McpSearchResult
import xyz.mederi.mcp.market.domain.McpServerDetail

/**
 * MCP 市场 API。
 *
 * 查询全部无状态实时直连官方 registry（免认证）；安装配置生成为纯函数，
 * 产出标准 mcpServers JSON 字符串，可直接交给 [McpServerApi.install]。
 *
 * 数据源唯一标识 = registry name（反 DNS，如 "io.github.upstash/context7"）。
 * 只保证产出格式正确；值的有效性（API key 对不对）是用户的事，
 * 必填项拦截是 UI 层的产品规则（依据 [McpInstallOption.inputs] 的 isRequired/isSecret）。
 */
interface McpMarketApi {

    /**
     * 搜索 / 列表（一页，默认 10 条）。
     *
     * @param query 关键词（子串匹配，空串 = 直接列全量分页）
     * @param cursor 翻页书签：上次结果的 [McpSearchResult.nextCursor] 原样传回，首页不传
     */
    suspend fun search(query: String = "", cursor: String? = null, pageSize: Int = 10): McpSearchResult

    /** 按 id 取详情（registry 有什么输出什么）。 */
    suspend fun detail(id: String): McpServerDetail

    /** 列出可安装形态及表单输入（纯函数，不发网络请求；UI 据此渲染形态选择 + 输入表单）。 */
    fun installOptions(detail: McpServerDetail): List<McpInstallOption>

    /**
     * 生成安装配置（纯函数，UI 已持有 detail 时用，免二次网络请求）。
     *
     * @param optionId [McpInstallOption.id]；null = 自动选择（远程优先，零安装）
     * @param inputs   用户输入（键 = [xyz.mederi.mcp.market.domain.McpInputField.key]）；
     *                 缺失按空串代入，不拦截
     * @return 完整 mcpServers JSON 文本，可直接交给 [McpServerApi.install]
     */
    fun installConfig(detail: McpServerDetail, optionId: String? = null, inputs: Map<String, String> = emptyMap()): String

    /** 一步到位：按 id 取详情并生成安装配置（UI 只持有 id 时的便捷入口）。 */
    suspend fun installConfig(id: String, optionId: String? = null, inputs: Map<String, String> = emptyMap()): String
}
