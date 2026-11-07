package xyz.mederi.core.contract

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import xyz.mederi.core.bridge.ServerAiCore
import xyz.mederi.core.contract.models.*
import xyz.mederi.core.mock.MockAiCore
import xyz.mederi.core.contract.dto.ReadyInfo
import xyz.mederi.server.serverModule
import kotlin.test.*

class SessionSubagentSettingsContractTest {
    @Test
    fun serverRoutesKeepRoleAndConcurrencyResetsSeparate() = testApplication {
        val mock = MockAiCore()
        mock.initialize().getOrThrow()
        application { serverModule(aiCore = mock, ready = ReadyInfo(ready = true, configDir = "test")) }
        val codec = Json { ignoreUnknownKeys = true }
        val client = createClient { install(ContentNegotiation) { json(codec) } }
        val path = "/v1/sessions/conv_101/subagent-configs"
        assertEquals(HttpStatusCode.OK, client.put("$path/EXECUTOR") {
            contentType(ContentType.Application.Json)
            setBody(UpdateSubagentConfigInput("model", "HIGH", "key"))
        }.status)
        assertEquals(HttpStatusCode.OK, client.put("$path/global") {
            contentType(ContentType.Application.Json)
            setBody(UpdateSubagentGlobalSettingsInput(9))
        }.status)
        val roles = codec.decodeFromString<List<SubagentConfigItem>>(client.get(path).bodyAsText())
        assertEquals(setOf("EXECUTOR", "RESEARCHER"), roles.map { it.role }.toSet())
        assertEquals("model", roles.single { it.role == "EXECUTOR" }.modelId)
        assertFalse(roles.single { it.role == "EXECUTOR" }.usesDefault)
        assertEquals(SubagentGlobalSettings(9, false), codec.decodeFromString(client.get("$path/global").bodyAsText()))
        assertEquals(HttpStatusCode.OK, client.delete("$path/EXECUTOR").status)
        assertTrue(codec.decodeFromString<List<SubagentConfigItem>>(client.get(path).bodyAsText()).all { it.usesDefault })
        assertEquals(9, mock.getSessionSubagentGlobalSettings("conv_101").getOrThrow().maxConcurrentAgents)
        assertEquals(HttpStatusCode.OK, client.delete("$path/global").status)
        assertTrue(mock.getSessionSubagentGlobalSettings("conv_101").getOrThrow().usesDefault)
    }

    @Test
    fun defaultsOverridesAndResetsRemainIsolatedByConversation() = runBlocking {
        val core = MockAiCore()
        core.initialize().getOrThrow()
        val first = "conv_101"
        val second = "conv_102"
        fun config(model: String) = UpdateSubagentConfigInput(model, "HIGH", "key-id")
        core.updateSubagentConfig("RESEARCHER", config("default")).getOrThrow()
        assertEquals("default", core.listSessionSubagentConfigs(first).getOrThrow().single { it.role == "RESEARCHER" }.modelId)
        core.updateSessionSubagentConfig(first, "RESEARCHER", config("override")).getOrThrow()
        core.updateSubagentConfig("RESEARCHER", config("new-default")).getOrThrow()
        val custom = core.listSessionSubagentConfigs(first).getOrThrow().single { it.role == "RESEARCHER" }
        assertEquals("override", custom.modelId)
        assertEquals("key-id", custom.apiKeyId)
        assertFalse(custom.usesDefault)
        assertEquals("new-default", core.listSessionSubagentConfigs(second).getOrThrow().single { it.role == "RESEARCHER" }.modelId)
        core.updateSessionSubagentConfig(first, "RESEARCHER", UpdateSubagentConfigInput()).getOrThrow()
        val parent = core.listSessionSubagentConfigs(first).getOrThrow().single { it.role == "RESEARCHER" }
        assertTrue(parent.isInheriting)
        assertFalse(parent.usesDefault)
        core.updateSessionSubagentConfig(first, "RESEARCHER", null).getOrThrow()
        val restored = core.listSessionSubagentConfigs(first).getOrThrow().single { it.role == "RESEARCHER" }
        assertTrue(restored.usesDefault)
        assertEquals("new-default", restored.modelId)

        core.updateSubagentGlobalSettings(UpdateSubagentGlobalSettingsInput(4)).getOrThrow()
        core.updateSessionSubagentGlobalSettings(first, UpdateSubagentGlobalSettingsInput(7)).getOrThrow()
        core.updateSubagentGlobalSettings(UpdateSubagentGlobalSettingsInput(5)).getOrThrow()
        assertEquals(SubagentGlobalSettings(7, false), core.getSessionSubagentGlobalSettings(first).getOrThrow())
        assertEquals(SubagentGlobalSettings(5, true), core.getSessionSubagentGlobalSettings(second).getOrThrow())
        core.updateSessionSubagentGlobalSettings(first, null).getOrThrow()
        assertEquals(SubagentGlobalSettings(5, true), core.getSessionSubagentGlobalSettings(first).getOrThrow())
        assertTrue(core.updateSessionSubagentGlobalSettings(first, UpdateSubagentGlobalSettingsInput(0)).isFailure)
        assertTrue(core.listSessionSubagentConfigs("missing").isFailure)
        assertTrue(core.updateSessionSubagentConfig("missing", "EXECUTOR", config("x")).isFailure)
        assertTrue(core.updateSessionSubagentGlobalSettings("missing", null).isFailure)
        assertEquals(setOf("EXECUTOR", "RESEARCHER"), core.listSessionSubagentConfigs(first).getOrThrow().map { it.role }.toSet())
        assertTrue(core.updateSessionSubagentConfig(first, "BROWSER_OPERATOR", config("browser")).isFailure)
        assertTrue(core.updateSessionSubagentConfig(first, "BROWSER_BRAIN", null).isFailure)
    }

    @Test
    fun restBridgeUsesSessionEndpointsAndDeleteForReset() = runBlocking {
        val json = Json { encodeDefaults = true }
        data class Request(val method: String, val path: String, val body: String, val auth: String?)
        val requests = java.util.concurrent.CopyOnWriteArrayList<Request>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/") { exchange ->
            val request = Request(exchange.requestMethod, exchange.requestURI.path,
                exchange.requestBody.bufferedReader().use { it.readText() }, exchange.requestHeaders.getFirst("Authorization"))
            requests += request
            val body = when {
                request.method != "GET" -> "{}"
                request.path.endsWith("/global") -> json.encodeToString(SubagentGlobalSettings.serializer(), SubagentGlobalSettings(6, false))
                else -> json.encodeToString(kotlinx.serialization.builtins.ListSerializer(SubagentConfigItem.serializer()),
                    listOf(SubagentConfigItem("EXECUTOR", "Executor", "", modelId = "model", usesDefault = false)))
            }.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val core = ServerAiCore("http://127.0.0.1:${server.address.port}", passwordProvider = { "secret" })
            assertFalse(core.listSessionSubagentConfigs("first").getOrThrow().single().usesDefault)
            val input = UpdateSubagentConfigInput("model", "HIGH", API_KEY_ROTATION_ID)
            core.updateSessionSubagentConfig("first", "EXECUTOR", input).getOrThrow()
            core.updateSessionSubagentConfig("first", "EXECUTOR", null).getOrThrow()
            assertEquals(SubagentGlobalSettings(6, false), core.getSessionSubagentGlobalSettings("second").getOrThrow())
            core.updateSessionSubagentGlobalSettings("second", UpdateSubagentGlobalSettingsInput(8)).getOrThrow()
            core.updateSessionSubagentGlobalSettings("second", null).getOrThrow()
            assertTrue(requests.all { it.auth == "Bearer secret" })
            assertEquals(listOf("GET", "PUT", "DELETE", "GET", "PUT", "DELETE"), requests.map { it.method })
            assertEquals(List(3) { "/v1/sessions/first/subagent-configs" }.mapIndexed { index, path ->
                if (index == 0) path else "$path/EXECUTOR"
            } + List(3) { "/v1/sessions/second/subagent-configs/global" }, requests.map { it.path })
            assertEquals(input, json.decodeFromString<UpdateSubagentConfigInput>(requests[1].body))
            assertEquals(UpdateSubagentGlobalSettingsInput(8), json.decodeFromString<UpdateSubagentGlobalSettingsInput>(requests[4].body))
            assertTrue(requests.filter { it.method == "DELETE" }.all { it.body.isEmpty() })
        } finally {
            server.stop(0)
        }
    }
}
