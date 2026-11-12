package xyz.mederi.core.contract

import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import xyz.mederi.core.bridge.MederiAiCore
import xyz.mederi.core.bridge.ServerAiCore
import xyz.mederi.core.contract.dto.CreateProjectInput
import xyz.mederi.core.contract.dto.ReadyInfo
import xyz.mederi.core.mock.MockAiCore
import xyz.mederi.domain.model.AgentMode
import xyz.mederi.plan.Plan
import xyz.mederi.plan.PlanStatus
import xyz.mederi.plan.PlanStore
import xyz.mederi.plan.Subtask
import xyz.mederi.plan.VerificationSpec
import xyz.mederi.server.serverModule
import java.io.File
import java.net.InetSocketAddress
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * getPlanDocument 按需读盘契约：subtaskIndex=null → planMarkdown 全文；非 null → 该子任务 Spec 原文。
 * 基建完全照抄 PlanAcceptanceReportHttpTest（MederiAiCore + serverModule + PlanStore 落盘）。
 */
class PlanDocumentHttpTest {

    @Test
    fun documentRouteReturnsPlanMarkdownAndSubtaskSpecOnDemand() = testApplication {
        val root = createTempDirectory("mederi-plan-doc").toFile()
        try {
            val config = File(root, "config")
            val projectDir = File(root, "project").apply { mkdir() }
            val core = MederiAiCore(config.path)
            core.initialize().getOrThrow()
            val project = core.createProject(CreateProjectInput("Document", projectDir.path)).getOrThrow()
            val conversation = core.createConversation(project.id).getOrThrow()
            val store = PlanStore(listOf(projectDir.path), authorityRoot = config.path)
            store.save(Plan(
                id = "plan_doc",
                sessionId = conversation.id,
                title = "按需读盘",
                summary = "plan summary",
                subtasks = listOf(
                    Subtask(0, "Task 0", planDetail = "Brief 0", verification = VerificationSpec("")),
                    Subtask(1, "Task 1", planDetail = "Brief 1", spec = "1. compile\n2. run checks",
                        verification = VerificationSpec("")),
                ),
                status = PlanStatus.APPROVED,
                createdAt = "2026-11-06T00:00:00Z",
                agentMode = AgentMode.APPROVAL,
            ))
            application { serverModule(aiCore = core, ready = ReadyInfo(ready = true, configDir = config.path)) }

            val base = "/v1/sessions/${conversation.id}/plans/plan_doc"

            // subtask 省略 → 计划全文（planMarkdown）
            val full = client.get("$base/document")
            assertEquals(HttpStatusCode.OK, full.status)
            assertEquals(ContentType.parse("text/markdown"), full.contentType()?.withoutParameters())
            val markdown = full.bodyAsText()
            assertEquals(core.getPlanDocument(conversation.id, "plan_doc", null).getOrThrow(), markdown)
            assertTrue(markdown.contains("按需读盘"), markdown.take(200))

            // ?subtask=N → 该子任务 Spec 原文
            val spec = client.get("$base/document?subtask=1")
            assertEquals(HttpStatusCode.OK, spec.status)
            val specBody = spec.bodyAsText()
            assertEquals("1. compile\n2. run checks", specBody)
            assertEquals(core.getPlanDocument(conversation.id, "plan_doc", 1).getOrThrow(), specBody)

            // 越界子任务 / 不存在计划 → NotFound
            assertEquals(HttpStatusCode.NotFound, client.get("$base/document?subtask=99").status)
            assertEquals(
                HttpStatusCode.NotFound,
                client.get("/v1/sessions/${conversation.id}/plans/plan_nope/document").status
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun invalidSubtaskQueryIsBadRequest() = testApplication {
        val core = object : AiCore by MockAiCore() {
            override suspend fun getPlanDocument(
                conversationId: String, planId: String, subtaskIndex: Int?
            ): Result<String> = Result.success("markdown")
        }
        application { serverModule(aiCore = core, ready = ReadyInfo(ready = true, configDir = "test")) }
        assertEquals(
            HttpStatusCode.BadRequest,
            client.get("/v1/sessions/conversation-a/plans/plan-a/document?subtask=abc").status
        )
    }

    @Test
    fun remoteBridgeRequestsPlanDocumentWithoutAHostFilePath() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/sessions/conversation-a/plans/plan-a/document") { exchange ->
            val authenticated = exchange.requestHeaders.getFirst("Authorization") == "Bearer secret"
            if (authenticated && exchange.requestMethod == "GET") {
                val body = if (exchange.requestURI.query == "subtask=1") "subtask-spec" else "plan-markdown"
                val bytes = body.toByteArray()
                exchange.responseHeaders.add("Content-Type", "text/markdown; charset=UTF-8")
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
            assertEquals("subtask-spec", client.getPlanDocument("conversation-a", "plan-a", 1).getOrThrow())
            assertEquals("plan-markdown", client.getPlanDocument("conversation-a", "plan-a", null).getOrThrow())
        } finally {
            server.stop(0)
        }
    }
}