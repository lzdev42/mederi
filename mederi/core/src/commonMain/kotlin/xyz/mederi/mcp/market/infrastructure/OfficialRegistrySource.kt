package xyz.mederi.mcp.market.infrastructure

import ai.koog.http.client.HttpClientFactoryResolver
import ai.koog.http.client.KoogHttpClient
import xyz.mederi.mcp.market.domain.McpSearchResult
import xyz.mederi.mcp.market.domain.McpServerDetail
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * 官方 MCP Registry（registry.modelcontextprotocol.io）数据源。
 *
 * 策略：无状态直连（实测定案）——registry 数据量大（4000+ 且持续增长）且自带 cursor 翻页，
 * 不做全量缓存；每次请求实时取一页（默认 10 条），cursor 由调用方透传。
 *
 * 免认证（只读查询）；单页上限 100，默认 10。
 *
 * @param baseUrl registry 地址（生产），测试可指向 staging 或 mock。
 */
class OfficialRegistrySource(
    private val baseUrl: String = "https://registry.modelcontextprotocol.io"
) : McpMarketSource {

    companion object {
        private const val LIST_PATH = "v0.1/servers"
        private const val MAX_PAGE_SIZE = 100
        private const val DEFAULT_PAGE_SIZE = 10
    }

    override suspend fun search(query: String?, cursor: String?, pageSize: Int): McpSearchResult =
        withClient("mederi-mcp-market") { client ->
            val parameters = buildMap<String, String> {
                put("limit", pageSize.coerceIn(1, MAX_PAGE_SIZE).toString())
                put("version", "latest")
                if (!query.isNullOrBlank()) put("search", query.trim())
                if (!cursor.isNullOrBlank()) put("cursor", cursor)
            }
            OfficialRegistryParsing.parseSearchResponse(
                client.get<String>(path = LIST_PATH, responseType = String::class, parameters = parameters)
            )
        }

    override suspend fun detail(id: String): McpServerDetail =
        withClient("mederi-mcp-market-detail") { client ->
            // name 含 "/"（io.github.user/xxx），路径参数需整体 URL 编码
            val encoded = URLEncoder.encode(id, StandardCharsets.UTF_8)
            OfficialRegistryParsing.parseDetailResponse(
                client.get<String>(path = "$LIST_PATH/$encoded/versions/latest", responseType = String::class)
            )
        }

    /** 每请求创建即用即毁客户端（与 ModelCatalog / fetchRemoteModels 同款模式），统一生命周期。 */
    private suspend fun <T> withClient(name: String, block: suspend (KoogHttpClient) -> T): T {
        val client = HttpClientFactoryResolver.resolve().create(
            clientName = name,
            baseUrl = baseUrl,
            headers = mapOf("Accept" to "application/json")
        )
        try {
            return block(client)
        } finally {
            client.close()
        }
    }
}
