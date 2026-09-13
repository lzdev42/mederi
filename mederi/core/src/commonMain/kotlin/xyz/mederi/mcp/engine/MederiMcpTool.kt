package xyz.mederi.mcp.engine

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.mcp.metadata.McpMetadataKeys
import ai.koog.serialization.JSONElement
import ai.koog.serialization.JSONObject
import ai.koog.serialization.JSONSerializer
import ai.koog.serialization.kotlinx.toKoogJSONElement
import ai.koog.serialization.kotlinx.toKotlinxJsonElement
import ai.koog.serialization.kotlinx.toKotlinxJsonObject
import ai.koog.serialization.typeToken
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * [ai.koog.agents.mcp.McpTool] 的定制版（koog 原版是 final、无法子类化，这里复制其实现）。
 *
 * 差别只在 server 调用名：**注册给 LLM 的名字带 server 前缀**（descriptor.name =
 * `context7_resolve-library-id`），但真正调用 MCP server 时必须用**原始工具名**
 * （`resolve-library-id`）。
 *
 * 两个名字的唯一推导点 = [PrefixedMcpToolDescriptorParser] + `McpConnector.connect` 构造处
 * （都在同一处从 `sdkTool` 派生，见 McpConnector）；本类只接收构造参数，不自己回退推导，
 * 缺参即编译失败——避免"缺 ToolId 静默回退前缀名"这类无声 bug（真实事故：koog 原版用
 * descriptor.name 调 server，前缀后 server 报 "Tool not found"）。
 */
internal class MederiMcpTool(
    private val mcpClient: Client,
    /** MCP server 侧的真实工具名（原始名，不带前缀）——调用 server 的唯一真理源。 */
    private val serverToolName: String,
    descriptor: ToolDescriptor,
    metadata: Map<String, String>,
) : Tool<JSONObject, CallToolResult?>(
    argsType = typeToken<JSONObject>(),
    resultType = typeToken<CallToolResult?>(),
    descriptor = descriptor,
    metadata = metadata,
) {
    /** MCP SDK 用 kotlinx.serialization，独立实例避免受外部 Json 配置影响。 */
    private val json = Json.Default
    private val resultSerializer = CallToolResult.serializer().nullable

    override suspend fun execute(args: JSONObject): CallToolResult {
        return mcpClient.callTool(name = serverToolName, arguments = args.toKotlinxJsonObject())
    }

    override fun decodeResult(rawResult: JSONElement, serializer: JSONSerializer): CallToolResult? {
        return json.decodeFromJsonElement(resultSerializer, rawResult.toKotlinxJsonElement())
    }

    override fun encodeResult(result: CallToolResult?, serializer: JSONSerializer): JSONElement {
        return json.encodeToJsonElement(resultSerializer, result).toKoogJSONElement()
    }

    /**
     * 结果转字符串（发给 LLM 的展示文本）。
     *
     * - isError=true → 前缀 "Error: " 让 LLM 能识别失败；
     *   若没有 TextContent（或空文本），回退编码完整 CallToolResult JSON，避免图片/资源被静默丢弃。
     * - 正常结果 → 去掉 "type" / "_meta" 元字段，只留实际数据。
     */
    override fun encodeResultToString(result: CallToolResult?, serializer: JSONSerializer): String {
        if (result?.isError == true) {
            val errorText = result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
            if (errorText.isNotBlank()) {
                return "Error: $errorText"
            }
            val fallbackJson = json.encodeToJsonElement(resultSerializer, result).toKoogJSONElement()
            return "Error: ${serializer.encodeJSONElementToString(fallbackJson)}"
        }

        val preparedResultJson: JsonElement = result
            ?.let {
                JsonObject(
                    json.encodeToJsonElement(resultSerializer, result).jsonObject
                        .filter { (key, _) -> key !in listOf("type", "_meta") }
                )
            }
            ?: JsonNull

        return serializer.encodeJSONElementToString(preparedResultJson.toKoogJSONElement())
    }
}