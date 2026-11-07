package xyz.mederi.core.contract

import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import xyz.mederi.api.exception.MederiNotFoundException
import xyz.mederi.core.bridge.MederiAiCore
import xyz.mederi.core.bridge.ServerAiCore
import xyz.mederi.core.contract.dto.CreateProjectInput
import xyz.mederi.core.contract.dto.ConversationState
import xyz.mederi.core.contract.dto.ReadyInfo
import xyz.mederi.core.contract.models.Conversation
import xyz.mederi.core.contract.models.ManualPlanAcceptanceInput
import xyz.mederi.core.contract.models.ConversationStatus
import xyz.mederi.core.contract.models.CoreEvent
import xyz.mederi.core.contract.models.CoreEventType
import xyz.mederi.core.contract.models.CostSummary
import xyz.mederi.core.contract.models.TokenUsage
import xyz.mederi.core.mock.MockAiCore
import xyz.mederi.server.serverModule
import java.net.InetSocketAddress
import java.io.File
import kotlin.io.path.createTempDirectory
import xyz.mederi.domain.model.AgentMode
import xyz.mederi.plan.AcceptanceCase
import xyz.mederi.plan.Plan
import xyz.mederi.plan.PlanStatus
import xyz.mederi.plan.PlanStore
import xyz.mederi.plan.Subtask
import xyz.mederi.plan.VerificationSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanAcceptanceReportHttpTest {
    private val markdown = "# 验收证据报告\n\n- V-01: 实测 1，预期 1。\n"

    @Test
    fun autonomousPlanAppearsImmediatelyAndRemainsAddressableAfterCompletion() {
        val initial = ConversationState(
            conversation = Conversation("conversation-a", "project-a", "Plan", ConversationStatus.Working, 0, 0),
            messages = emptyList(),
            tokenUsage = TokenUsage(),
            cost = CostSummary(),
        )
        val payload = mapOf("planId" to "plan-a", "title" to "Plan A", "planContent" to markdown)
        val created = ConversationStateReducer.apply(
            initial, CoreEvent(CoreEventType.PLAN_PROGRESS, "conversation-a", payload = payload + ("action" to "created"))
        )
        assertEquals("APPROVED", created.planApprovals.single().status)
        val completed = ConversationStateReducer.apply(
            created, CoreEvent(CoreEventType.PLAN_PROGRESS, "conversation-a", payload = payload + ("action" to "completed"))
        )
        assertEquals("plan-a", completed.planApprovals.single().id)
        assertEquals("COMPLETED", completed.planApprovals.single().status)
        assertEquals(markdown, completed.planApprovals.single().planContent)
    }

    @Test
    fun manualAcceptanceRouteForwardsObservedValueAndReviewer() = testApplication {
        var received: Triple<String, String, ManualPlanAcceptanceInput>? = null
        val core = object : AiCore by MockAiCore() {
            override suspend fun recordPlanManualAcceptance(
                conversationId: String, planId: String, input: ManualPlanAcceptanceInput
            ): Result<Unit> {
                received = Triple(conversationId, planId, input)
                return Result.success(Unit)
            }
        }
        application { serverModule(aiCore = core, ready = ReadyInfo(ready = true, configDir = "test")) }
        val response = client.post("/v1/sessions/conversation-a/plans/plan-a/manual-acceptance") {
            contentType(ContentType.Application.Json)
            setBody("""{"caseId":"V-01","observed":"1","evidence":"database row count","confirmedBy":"Reviewer"}""")
        }
        assertTrue(response.status.value in 200..299, response.bodyAsText())
        assertEquals(Triple("conversation-a", "plan-a",
            ManualPlanAcceptanceInput("V-01", "1", "database row count", "Reviewer")), received)
    }

    @Test
    fun headlessServerAndDesktopBridgeReadTheSameCoreReport() = testApplication {
        val root = createTempDirectory("mederi-acceptance-route").toFile()
        try {
            val config = File(root, "config")
            val projectDir = File(root, "project").apply { mkdir() }
            val core = MederiAiCore(config.path)
            core.initialize().getOrThrow()
            val project = core.createProject(CreateProjectInput("Acceptance", projectDir.path)).getOrThrow()
            val conversation = core.createConversation(project.id).getOrThrow()
            val store = PlanStore(listOf(projectDir.path), authorityRoot = config.path)
            val manual = AcceptanceCase("V-01", "One row after retry", "Empty database",
                "messageId=m-001", "Submit twice and query the row count", "Database", "1", "Duplicate row", manual = true)
            store.save(Plan(id = "plan_a", sessionId = conversation.id, title = "Acceptance", summary = "",
                subtasks = listOf(Subtask(0, "Overall", planDetail = "Inspect stored data",
                    verification = VerificationSpec(""), acceptanceCases = listOf(manual), overallAcceptance = true)),
                status = PlanStatus.APPROVED, createdAt = "2026-11-06T00:00:00Z", agentMode = AgentMode.APPROVAL))
            application { serverModule(aiCore = core, ready = ReadyInfo(ready = true, configDir = config.path)) }

            val base = "/v1/sessions/${conversation.id}/plans/plan_a"
            val submitted = client.post("$base/manual-acceptance") {
                contentType(ContentType.Application.Json)
                setBody("""{"caseId":"V-01","observed":"1","evidence":"query returned one row","confirmedBy":"Reviewer"}""")
            }
            assertTrue(submitted.status.value in 200..299, submitted.bodyAsText())
            val response = client.get("$base/acceptance-report")
            assertEquals(HttpStatusCode.OK, response.status)
            val report = response.bodyAsText()
            assertEquals(core.getPlanAcceptanceReport(conversation.id, "plan_a").getOrThrow(), report)
            assertTrue(report.contains("实测值与批准的预期值相等"))
            assertTrue(report.contains("query returned one row"))
            assertTrue(report.contains("Reviewer"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun routeReturnsMarkdownForTheRequestedConversationAndPlan() = testApplication {
        val core = object : AiCore by MockAiCore() {
            override suspend fun getPlanAcceptanceReport(conversationId: String, planId: String): Result<String> =
                if (conversationId == "conversation-a" && planId == "plan-a") Result.success(markdown)
                else Result.failure(MederiNotFoundException("Plan not found"))
        }
        application { serverModule(aiCore = core, ready = ReadyInfo(ready = true, configDir = "test")) }
        val response = client.get("/v1/sessions/conversation-a/plans/plan-a/acceptance-report")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.parse("text/markdown"), response.contentType()?.withoutParameters())
        assertEquals(markdown, response.bodyAsText())
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/v1/sessions/conversation-b/plans/plan-a/acceptance-report").status
        )
    }

    @Test
    fun remoteBridgeReadsReportWithoutAHostFilePath() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/sessions/conversation-a/plans/plan-a/acceptance-report") { exchange ->
            val authenticated = exchange.requestHeaders.getFirst("Authorization") == "Bearer secret"
            if (authenticated && exchange.requestMethod == "GET") {
                val bytes = markdown.toByteArray()
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
            assertEquals(markdown, client.getPlanAcceptanceReport("conversation-a", "plan-a").getOrThrow())
            assertTrue(client.getPlanAcceptanceReport("conversation-b", "plan-a").isFailure)
        } finally {
            server.stop(0)
        }
    }
}
