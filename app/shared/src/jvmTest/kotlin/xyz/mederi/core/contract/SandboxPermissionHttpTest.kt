package xyz.mederi.core.contract

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import xyz.mederi.core.bridge.ServerAiCore
import xyz.mederi.core.contract.models.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 验证 REST 桥实际发出的路径、权限载荷及鉴权；不启动产品 APP/server。 */
class SandboxPermissionHttpTest {
    private data class Request(val method: String, val path: String, val body: String, val authorization: String?)

    @Test
    fun projectSettingsStatusAndExactApprovalUseIsolatedProjectEndpoints() = runBlocking {
        val json = Json { encodeDefaults = true }
        val requests = java.util.concurrent.CopyOnWriteArrayList<Request>()
        val settings = mutableMapOf("project-a" to SandboxSettings(), "project-b" to SandboxSettings())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/") { exchange ->
            val request = Request(exchange.requestMethod, exchange.requestURI.path,
                exchange.requestBody.bufferedReader().use { it.readText() }, exchange.requestHeaders.getFirst("Authorization"))
            requests.add(request)
            val projectId = request.path.split("/").getOrNull(3)
            val current = settings[projectId]
            var status = 200
            val response = when {
                request.path == "/v1/projects/$projectId/sandbox/settings" && current != null -> {
                    if (request.method == "POST") {
                        val input = json.decodeFromString<SandboxSettings>(request.body)
                        if (input.version != current.version) {
                            status = 409
                            json.encodeToString(xyz.mederi.core.contract.dto.ApiError.serializer(),
                                xyz.mederi.core.contract.dto.ApiError("Project permissions changed"))
                        } else {
                            val saved = input.copy(version = current.version + 1)
                            settings[projectId!!] = saved
                            json.encodeToString(SandboxSettings.serializer(), saved)
                        }
                    } else json.encodeToString(SandboxSettings.serializer(), current)
                }
                request.path == "/v1/projects/$projectId/sandbox/status" && current != null ->
                    json.encodeToString(SandboxStatusInfo.serializer(), SandboxStatusInfo("Seatbelt", true, "bash", "status:$projectId"))
                request.path == "/v1/sessions/conversation/execution-approvals/request" && request.method == "POST" ->
                    json.encodeToString(kotlinx.serialization.serializer<Unit>(), Unit)
                else -> {
                    status = 404
                    json.encodeToString(xyz.mederi.core.contract.dto.ApiError.serializer(),
                        xyz.mederi.core.contract.dto.ApiError("Project or endpoint not found"))
                }
            }.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val client = ServerAiCore("http://127.0.0.1:${server.address.port}", passwordProvider = { "secret" })
            assertEquals(SandboxSettings(), client.getSandboxSettings("project-a").getOrThrow())
            val full = SandboxSettings(ExecutionPermissionMode.FULL_ACCESS, listOf("/host/extra"),
                deniedPaths = listOf("/host/private"), allowedCommands = listOf("git status"), deniedCommands = listOf("git push"))
            val saved = client.updateSandboxSettings("project-a", full).getOrThrow()
            assertEquals(full.copy(version = 1), saved)
            assertEquals(saved, client.getSandboxSettings("project-a").getOrThrow())
            assertEquals(SandboxSettings(), client.getSandboxSettings("project-b").getOrThrow())
            assertEquals("status:project-a", client.getSandboxStatus("project-a").getOrThrow().detail)
            assertEquals("status:project-b", client.getSandboxStatus("project-b").getOrThrow().detail)
            assertTrue(client.updateSandboxSettings("project-a", full.copy(deniedCommands = emptyList())).isFailure)
            assertEquals(saved, client.getSandboxSettings("project-a").getOrThrow())
            client.resolveExecutionApproval("conversation", "request", true).getOrThrow()
            assertTrue(client.getSandboxSettings("missing").isFailure)
            assertTrue(requests.all { it.authorization == "Bearer secret" })
            assertTrue(requests.any { it.method == "POST" && it.path == "/v1/projects/project-a/sandbox/settings" })
            assertTrue(requests.any { it.path == "/v1/projects/project-b/sandbox/settings" })
            assertTrue(requests.none { it.path.startsWith("/v1/sandbox/") })
            val approval = requests.single { it.path == "/v1/sessions/conversation/execution-approvals/request" }
            assertEquals(ResolveExecutionApprovalInput(true), json.decodeFromString(approval.body))
        } finally {
            server.stop(0)
        }
    }
}
