package xyz.mederi.core.contract

import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.mederi.core.bridge.ServerAiCore
import xyz.mederi.core.contract.dto.ReadyInfo
import xyz.mederi.core.contract.models.UsageStatGroup
import xyz.mederi.core.contract.models.UsageStatistics
import xyz.mederi.core.mock.MockAiCore
import xyz.mederi.server.serverModule
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * getUsageStatistics 四处同步的 REST 面验证：
 * - Server 路由 `GET /v1/usage/statistics` 存在并把契约 DTO 序列化成 JSON（不是手拼）；
 * - ServerAiCore（遥控端）经同一路由取回等价数据。
 */
class UsageStatisticsHttpTest {

    private val sample = UsageStatistics(
        groups = listOf(
            UsageStatGroup(
                providerId = "gateway",
                providerName = "Gateway",
                apiKeyId = "key_a",
                apiKeyName = "Main Key",
                modelId = "remote-a",
                modelName = "Model A",
                inputTokens = 1000,
                outputTokens = 200,
                cacheReadTokens = 800,
                cacheWriteTokens = 100,
                successfulRequests = 1,
                totalRequests = 2,
                pricedRequests = 1,
                estimate = 730.0 / 1_000_000,
            ),
            UsageStatGroup(
                providerId = null,
                providerName = null,
                apiKeyId = null,
                apiKeyName = null,
                modelId = null,
                modelName = null,
                inputTokens = null,
                outputTokens = null,
                cacheReadTokens = null,
                cacheWriteTokens = null,
                successfulRequests = 0,
                totalRequests = 1,
                pricedRequests = 0,
                estimate = null,
            ),
        ),
        totalEstimatedCost = 730.0 / 1_000_000,
        pricedRequests = 1,
        totalRequests = 3,
        currency = "USD",
    )

    @Test
    fun routeServesSerializedUsageStatisticsAndRemoteBridgeReadsIt() = testApplication {
        val core = object : AiCore by MockAiCore() {
            override suspend fun getUsageStatistics(): Result<UsageStatistics> = Result.success(sample)
        }
        application { serverModule(aiCore = core, ready = ReadyInfo(ready = true, configDir = "test")) }

        val response = client.get("/v1/usage/statistics")
        assertEquals(HttpStatusCode.OK, response.status)

        // 序列化形态由 kotlinx 生成：key 维度与 token 维度都必须在线
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(3, body["totalRequests"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, body["pricedRequests"]!!.jsonPrimitive.content.toInt())
        assertEquals("USD", body["currency"]!!.jsonPrimitive.content)
        val groups = body["groups"]!!.jsonArray
        assertEquals(2, groups.size)
        val first = groups[0].jsonObject
        assertEquals("key_a", first["apiKeyId"]!!.jsonPrimitive.content)
        assertEquals("Main Key", first["apiKeyName"]!!.jsonPrimitive.content)
        assertEquals("remote-a", first["modelId"]!!.jsonPrimitive.content)
        assertEquals(1000L, first["inputTokens"]!!.jsonPrimitive.content.toLong())
        assertEquals(800L, first["cacheReadTokens"]!!.jsonPrimitive.content.toLong())
        assertEquals(100L, first["cacheWriteTokens"]!!.jsonPrimitive.content.toLong())
        assertEquals(1, first["successfulRequests"]!!.jsonPrimitive.content.toInt())
        assertEquals(2, first["totalRequests"]!!.jsonPrimitive.content.toInt())
        // 未知桶：null 维度必须保留成 null（不伪装成 0 或空串）
        assertNull(groups[1].jsonObject["apiKeyId"])
        assertNull(groups[1].jsonObject["inputTokens"])
    }

    @Test
    fun remoteBridgeDecodesUsageStatisticsOverREST() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        // 用契约同款 kotlinx 编码构造 wire 载荷，避免手拼 JSON
        val payload = Json.encodeToString(UsageStatistics.serializer(), sample)
        server.createContext("/v1/usage/statistics") { exchange ->
            val authenticated = exchange.requestHeaders.getFirst("Authorization") == "Bearer secret"
            if (authenticated && exchange.requestMethod == "GET") {
                val bytes = payload.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } else {
                exchange.sendResponseHeaders(401, -1)
                exchange.close()
            }
        }
        server.start()
        try {
            val client = ServerAiCore("http://127.0.0.1:${server.address.port}", passwordProvider = { "secret" })
            val decoded = client.getUsageStatistics().getOrThrow()
            assertEquals(sample, decoded, "遥控端拿到的跨会话累计统计必须与被遥控端一致")
            assertTrue(decoded.groups.first().estimate!! > 0)
        } finally {
            server.stop(0)
        }
    }
}
