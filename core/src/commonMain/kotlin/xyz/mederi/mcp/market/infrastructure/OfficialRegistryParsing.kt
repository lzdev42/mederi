package xyz.mederi.mcp.market.infrastructure

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import xyz.mederi.mcp.market.domain.McpArgument
import xyz.mederi.mcp.market.domain.McpKeyValue
import xyz.mederi.mcp.market.domain.McpInputSpec
import xyz.mederi.mcp.market.domain.McpPackage
import xyz.mederi.mcp.market.domain.McpRemote
import xyz.mederi.mcp.market.domain.McpSearchResult
import xyz.mederi.mcp.market.domain.McpServerDetail
import xyz.mederi.mcp.market.domain.McpServerSummary
import xyz.mederi.mcp.market.domain.McpTransport

/**
 * 官方 MCP registry API 响应解析（纯函数，零状态）。
 *
 * 用 JsonElement 手工遍历而不是严格 @Serializable 反序列化：
 * wild 数据里存在超 schema 的脏形态（argument.type 为空串、多余字段、缺字段），
 * 宽松遍历 + 逐字段兜底比严格模型更抗造，坏字段只影响该字段不影响整条数据。
 */
object OfficialRegistryParsing {

    private val json = Json { ignoreUnknownKeys = true }

    // 官方 registry 在 _meta 里挂状态信息的固定 key（反 DNS 命名）
    private const val META_OFFICIAL = "io.modelcontextprotocol.registry/official"

    /** 解析列表响应（GET /v0.1/servers）。 */
    fun parseSearchResponse(text: String): McpSearchResult {
        val root = json.parseToJsonElement(text).jsonObject
        val items = (root["servers"] as? JsonArray ?: JsonArray(emptyList()))
            .mapNotNull { el ->
                (el as? JsonObject)?.let { obj ->
                    (obj["server"] as? JsonObject)?.let { server ->
                        val meta = officialMeta(obj)
                        toSummary(toDetail(server, meta))
                    }
                }
            }
        val nextCursor = (root["metadata"] as? JsonObject)
            ?.get("nextCursor") as? JsonPrimitive
        return McpSearchResult(items = items, nextCursor = nextCursor?.takeIf { it.isString && it.content.isNotBlank() }?.content)
    }

    /** 解析详情响应（GET /v0.1/servers/{id}/versions/{version}）。 */
    fun parseDetailResponse(text: String): McpServerDetail {
        val root = json.parseToJsonElement(text).jsonObject
        val server = root["server"] as? JsonObject
            ?: throw IllegalArgumentException("registry 响应缺少 server 节点")
        return toDetail(server, officialMeta(root))
    }

    // ==================== server ====================

    private fun toDetail(server: JsonObject, meta: OfficialMeta?): McpServerDetail {
        val name = server.str("name") ?: throw IllegalArgumentException("registry 条目缺少 name")
        return McpServerDetail(
            id = name,
            title = server.str("title"),
            description = server.str("description") ?: "",
            version = server.str("version") ?: "",
            websiteUrl = server.str("websiteUrl"),
            repositoryUrl = (server["repository"] as? JsonObject)?.str("url"),
            iconUrl = (server["icons"] as? JsonArray)
                ?.firstNotNullOfOrNull { (it as? JsonObject)?.str("src") },
            packages = (server["packages"] as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.let(::toPackage) }
                ?: emptyList(),
            remotes = (server["remotes"] as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.let(::toRemote) }
                ?: emptyList(),
            status = meta?.status,
            publishedAt = meta?.publishedAt,
            updatedAt = meta?.updatedAt
        )
    }

    private fun toSummary(detail: McpServerDetail): McpServerSummary = McpServerSummary(
        id = detail.id,
        title = detail.title,
        description = detail.description,
        version = detail.version,
        transports = (detail.packages.map { it.transport.type } + detail.remotes.map { it.type }).distinct(),
        registryTypes = detail.packages.map { it.registryType }.distinct(),
        status = detail.status,
        updatedAt = detail.updatedAt
    )

    private class OfficialMeta(val status: String?, val publishedAt: String?, val updatedAt: String?)

    private fun officialMeta(container: JsonObject): OfficialMeta? {
        val official = ((container["_meta"] as? JsonObject)?.get(META_OFFICIAL) as? JsonObject) ?: return null
        return OfficialMeta(
            status = official.str("status"),
            publishedAt = official.str("publishedAt"),
            updatedAt = official.str("updatedAt")
        )
    }

    // ==================== package / remote / transport ====================

    private fun toPackage(obj: JsonObject): McpPackage? {
        val registryType = obj.str("registryType") ?: return null
        val identifier = obj.str("identifier") ?: return null
        return McpPackage(
            registryType = registryType,
            identifier = identifier,
            version = obj.str("version"),
            registryBaseUrl = obj.str("registryBaseUrl"),
            runtimeHint = obj.str("runtimeHint"),
            transport = (obj["transport"] as? JsonObject)?.let(::toTransport)
                ?: McpTransport(type = "stdio"),
            runtimeArguments = obj.arguments("runtimeArguments"),
            packageArguments = obj.arguments("packageArguments"),
            environmentVariables = (obj["environmentVariables"] as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.let(::toKeyValue) }
                ?: emptyList(),
            fileSha256 = obj.str("fileSha256")
        )
    }

    private fun toRemote(obj: JsonObject): McpRemote? {
        val type = obj.str("type") ?: return null
        val url = obj.str("url") ?: return null
        return McpRemote(
            type = type,
            url = url,
            headers = obj.headers(),
            variables = obj.variables()
        )
    }

    private fun toTransport(obj: JsonObject): McpTransport = McpTransport(
        type = obj.str("type") ?: "stdio",
        url = obj.str("url"),
        headers = obj.headers(),
        variables = obj.variables()
    )

    // ==================== 输入元数据 ====================

    private fun JsonObject.headers(): List<McpKeyValue> =
        (this["headers"] as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.let(::toKeyValue) }
            ?: emptyList()

    private fun toKeyValue(obj: JsonObject): McpKeyValue? {
        val name = obj.str("name") ?: return null
        return McpKeyValue(
            name = name,
            value = obj.str("value"),
            description = obj.str("description"),
            isSecret = obj.bool("isSecret"),
            isRequired = obj.bool("isRequired"),
            default = obj.str("default"),
            placeholder = obj.str("placeholder"),
            format = obj.str("format"),
            choices = obj.strList("choices"),
            variables = obj.variables()
        )
    }

    private fun JsonObject.arguments(field: String): List<McpArgument> =
        (this[field] as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.let(::toArgument) }
            ?: emptyList()

    private fun toArgument(obj: JsonObject): McpArgument = McpArgument(
        type = obj.str("type"),
        name = obj.str("name"),
        valueHint = obj.str("valueHint"),
        value = obj.str("value"),
        isRepeated = obj.bool("isRepeated"),
        description = obj.str("description"),
        isSecret = obj.bool("isSecret"),
        isRequired = obj.bool("isRequired"),
        default = obj.str("default"),
        placeholder = obj.str("placeholder"),
        format = obj.str("format"),
        choices = obj.strList("choices"),
        variables = obj.variables()
    )

    private fun JsonObject.variables(): Map<String, McpInputSpec> =
        (this["variables"] as? JsonObject)
            ?.mapValues { (_, spec) ->
                (spec as? JsonObject)?.let {
                    McpInputSpec(
                        description = it.str("description"),
                        value = it.str("value"),
                        isRequired = it.bool("isRequired"),
                        isSecret = it.bool("isSecret"),
                        default = it.str("default"),
                        placeholder = it.str("placeholder"),
                        format = it.str("format"),
                        choices = it.strList("choices")
                    )
                } ?: McpInputSpec()
            } ?: emptyMap()

    // ==================== 基础取值 ====================

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.bool(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.content == "true"

    private fun JsonObject.strList(key: String): List<String> =
        (this[key] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            ?: emptyList()
}
