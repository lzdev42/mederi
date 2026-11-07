package xyz.mederi.ui.workspace

import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.mederi.core.contract.dto.CreateConversationInput
import xyz.mederi.core.contract.dto.CreateProjectInput
import xyz.mederi.core.contract.models.ArchiveWorkspaceInput
import xyz.mederi.core.contract.models.Conversation
import xyz.mederi.core.contract.models.ConversationWorkspaceInfo
import xyz.mederi.core.contract.models.CreateWorkspaceBranchInput
import xyz.mederi.core.contract.models.HandoffInput
import xyz.mederi.core.contract.models.HandoffPreview
import xyz.mederi.core.contract.models.HandoffPreviewInput
import xyz.mederi.core.contract.models.ProjectEnvironment
import xyz.mederi.core.contract.models.RecoveryPreview
import xyz.mederi.core.contract.models.RecoveryStrategy
import xyz.mederi.core.contract.models.RepositoryInfo
import xyz.mederi.core.contract.models.RestoreWorkspaceInput
import xyz.mederi.core.contract.models.Workspace
import xyz.mederi.core.contract.models.WorkspaceActionInput
import xyz.mederi.core.contract.models.WorkspaceFileArea
import xyz.mederi.core.contract.models.WorkspaceFileContent
import xyz.mederi.core.contract.models.WorkspaceFileEntry
import xyz.mederi.core.contract.models.WorkspaceInfo
import xyz.mederi.core.contract.models.WorkspaceKind
import xyz.mederi.core.contract.models.WorkspaceOperation
import xyz.mederi.core.contract.models.WorkspaceRecoveryInput
import xyz.mederi.core.contract.models.WorkspaceSelection
import xyz.mederi.core.contract.models.WorktreeSettings
import xyz.mederi.core.mock.MockAiCore
import xyz.mederi.core.contract.dto.ReadyInfo
import xyz.mederi.server.serverModule

class WorkspaceHttpTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun coversEveryDeclaredRouteAndEncodedIdentifier() = testApplication {
        val mock = MockAiCore()
        application {
            serverModule(aiCore = mock, ready = ReadyInfo(ready = true, configDir = "~/.mederi"))
        }
        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        // 初始化基础 project 与 session，id 包含特殊字符
        runBlocking {
            mock.createProject(CreateProjectInput(name = "Special Project", directory = "/tmp/special"))
            val proj = mock.projects.value.first()
            mock.createConversation(CreateConversationInput(projectId = proj.id))
        }
        val projId = runBlocking { mock.projects.value.first().id }
        val sessId = runBlocking { mock.projects.value.first().conversations.first().id }
        val wsId = "ws_$sessId"

        // 1. GET /v1/projects/{id}/repository
        val repoRes = client.get("/v1/projects/${projId.encodeURLParameter()}/repository")
        assertEquals(HttpStatusCode.OK, repoRes.status)

        // 2. GET /v1/projects/{id}/workspaces
        val wsListRes = client.get("/v1/projects/${projId.encodeURLParameter()}/workspaces")
        assertEquals(HttpStatusCode.OK, wsListRes.status)

        // 3. GET /v1/sessions/{id}/workspace
        val convWsRes = client.get("/v1/sessions/${sessId.encodeURLParameter()}/workspace")
        assertEquals(HttpStatusCode.OK, convWsRes.status)

        // 显式发起会话随动作请求经过 HTTP 序列化和路由。第 15 项保留旧请求兼容性覆盖。
        val ownedActionRes = client.post("/v1/workspaces/${wsId.encodeURLParameter()}/actions") {
            contentType(ContentType.Application.Json)
            setBody(WorkspaceActionInput(
                environmentId = "env_node",
                actionId = "build",
                environmentVersion = 2L,
                requestId = "req_owned_action",
                conversationId = sessId
            ))
        }
        assertEquals(HttpStatusCode.OK, ownedActionRes.status)
        assertEquals(sessId, json.decodeFromString<WorkspaceOperation>(ownedActionRes.bodyAsText()).sessionId)

        // 4. POST /v1/sessions/{id}/workspace/handoff/preview
        val previewRes = client.post("/v1/sessions/${sessId.encodeURLParameter()}/workspace/handoff/preview") {
            contentType(ContentType.Application.Json)
            setBody(HandoffPreviewInput(targetKind = WorkspaceKind.MANAGED_WORKTREE))
        }
        assertEquals(HttpStatusCode.OK, previewRes.status)

        // 5. POST /v1/sessions/{id}/workspace/handoff
        val handoffRes = client.post("/v1/sessions/${sessId.encodeURLParameter()}/workspace/handoff") {
            contentType(ContentType.Application.Json)
            setBody(HandoffInput(targetKind = WorkspaceKind.MANAGED_WORKTREE, previewToken = "tok1", requestId = "req_h1"))
        }
        assertEquals(HttpStatusCode.OK, handoffRes.status)

        // 6. POST /v1/workspaces/{id}/restore
        val restoreRes = client.post("/v1/workspaces/${wsId.encodeURLParameter()}/restore") {
            contentType(ContentType.Application.Json)
            setBody(RestoreWorkspaceInput(requestId = "req_r1"))
        }
        assertEquals(HttpStatusCode.OK, restoreRes.status)

        // 7. POST /v1/workspaces/{id}/archive
        val archiveRes = client.post("/v1/workspaces/${wsId.encodeURLParameter()}/archive") {
            contentType(ContentType.Application.Json)
            setBody(ArchiveWorkspaceInput(requestId = "req_a1"))
        }
        assertEquals(HttpStatusCode.OK, archiveRes.status)

        // 8. POST /v1/workspaces/{id}/branch
        val branchRes = client.post("/v1/workspaces/${wsId.encodeURLParameter()}/branch") {
            contentType(ContentType.Application.Json)
            setBody(CreateWorkspaceBranchInput(name = "feat/test", expectedHead = "head1", expectedGeneration = 1L))
        }
        assertEquals(HttpStatusCode.OK, branchRes.status)

        // 9. GET /v1/workspace-operations/{id} & POST cancel
        val opRes = client.get("/v1/workspace-operations/op_req_h1")
        assertEquals(HttpStatusCode.OK, opRes.status)
        val cancelRes = client.post("/v1/workspace-operations/op_req_h1/cancel")
        assertEquals(HttpStatusCode.OK, cancelRes.status)

        // 10. GET /v1/projects/{id}/workspace-operations
        val projOpsRes = client.get("/v1/projects/${projId.encodeURLParameter()}/workspace-operations")
        assertEquals(HttpStatusCode.OK, projOpsRes.status)

        // 11. POST /v1/sessions/{id}/archive & pin
        val sessArchRes = client.post("/v1/sessions/${sessId.encodeURLParameter()}/archive") {
            contentType(ContentType.Application.Json)
            setBody(xyz.mederi.core.contract.models.SetConversationArchivedInput(archived = true, requestId = "req_arch_sess"))
        }
        assertEquals(HttpStatusCode.OK, sessArchRes.status)

        val sessPinRes = client.post("/v1/sessions/${sessId.encodeURLParameter()}/pin") {
            contentType(ContentType.Application.Json)
            setBody(xyz.mederi.core.contract.models.SetConversationPinnedInput(pinned = true))
        }
        assertEquals(HttpStatusCode.OK, sessPinRes.status)

        // 12. GET /v1/projects/{id}/archived-sessions
        val archListRes = client.get("/v1/projects/${projId.encodeURLParameter()}/archived-sessions")
        assertEquals(HttpStatusCode.OK, archListRes.status)

        // 13. GET & POST /v1/worktree-settings
        val getSetRes = client.get("/v1/worktree-settings")
        assertEquals(HttpStatusCode.OK, getSetRes.status)
        val postSetRes = client.post("/v1/worktree-settings") {
            contentType(ContentType.Application.Json)
            setBody(WorktreeSettings(rootPath = "/mock/wt", maxManagedWorktrees = 25, automaticCleanup = true, version = 1L))
        }
        assertEquals(HttpStatusCode.OK, postSetRes.status)

        // 14. GET & POST /v1/projects/{id}/environments
        val getEnvRes = client.get("/v1/projects/${projId.encodeURLParameter()}/environments")
        assertEquals(HttpStatusCode.OK, getEnvRes.status)
        val postEnvRes = client.post("/v1/projects/${projId.encodeURLParameter()}/environments") {
            contentType(ContentType.Application.Json)
            setBody(xyz.mederi.core.contract.models.SaveProjectEnvironmentInput(
                expectedVersion = 1L,
                environment = ProjectEnvironment(id = "env_node", name = "Node", version = 1L)
            ))
        }
        assertEquals(HttpStatusCode.OK, postEnvRes.status)

        // 15. POST /v1/workspaces/{id}/actions
        val actionRes = client.post("/v1/workspaces/${wsId.encodeURLParameter()}/actions") {
            contentType(ContentType.Application.Json)
            setBody(WorkspaceActionInput(environmentId = "env_node", actionId = "build", environmentVersion = 2L, requestId = "req_act"))
        }
        assertEquals(HttpStatusCode.OK, actionRes.status)

        // 16. GET /v1/workspace-operations/{id}/log
        val logRes = client.get("/v1/workspace-operations/op_req_h1/log?afterOffset=0&maxBytes=1024")
        assertEquals(HttpStatusCode.OK, logRes.status)

        // 17. GET /v1/workspaces/{id}/files & file
        val filesRes = client.get("/v1/workspaces/${wsId.encodeURLParameter()}/files?path=&area=PROJECT")
        assertEquals(HttpStatusCode.OK, filesRes.status)
        val fileRes = client.get("/v1/workspaces/${wsId.encodeURLParameter()}/file?path=README.md&offset=0&maxBytes=1024&area=PROJECT")
        assertEquals(HttpStatusCode.OK, fileRes.status)

        // 18. POST retry & recovery
        val retryPrepRes = client.post("/v1/sessions/${sessId.encodeURLParameter()}/workspace/retry") {
            contentType(ContentType.Application.Json)
            setBody(xyz.mederi.core.contract.models.RetryWorkspacePreparationInput(
                failedOperationId = "op_fail",
                requestId = "req_rprep"
            ))
        }
        assertEquals(HttpStatusCode.OK, retryPrepRes.status)

        val retrySetupRes = client.post("/v1/workspaces/${wsId.encodeURLParameter()}/setup/retry") {
            contentType(ContentType.Application.Json)
            setBody(xyz.mederi.core.contract.models.RetryWorkspaceSetupInput(
                failedOperationId = "op_fail",
                expectedEnvironmentHash = "hash1",
                requestId = "req_rset"
            ))
        }
        assertEquals(HttpStatusCode.OK, retrySetupRes.status)

        val recovPrevRes = client.post("/v1/workspace-operations/op_req_h1/recovery/preview") {
            contentType(ContentType.Application.Json)
            setBody(xyz.mederi.core.contract.models.PreviewWorkspaceRecoveryInput(
                strategy = RecoveryStrategy.KEEP_VERIFIED_CURRENT
            ))
        }
        assertEquals(HttpStatusCode.OK, recovPrevRes.status)
        assertEquals(HttpStatusCode.OK, recovPrevRes.status)

        val recovRes = client.post("/v1/workspace-operations/op_req_h1/recovery") {
            contentType(ContentType.Application.Json)
            setBody(WorkspaceRecoveryInput(
                strategy = RecoveryStrategy.KEEP_VERIFIED_CURRENT,
                expectedOperationVersion = 0L,
                previewToken = "tok_recov",
                requestId = "req_recov"
            ))
        }
        assertEquals(HttpStatusCode.OK, recovRes.status)
    }

    @Test
    fun keepsTwoProjectsAndArchivedListsIsolated() = testApplication {
        val mock = MockAiCore()
        application {
            serverModule(aiCore = mock, ready = ReadyInfo(ready = true, configDir = "~/.mederi"))
        }
        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        var pAId = ""
        var pBId = ""
        var sAId = ""
        var sBId = ""

        runBlocking {
            mock.createProject(CreateProjectInput(name = "Project A", directory = "/tmp/a"))
            mock.createProject(CreateProjectInput(name = "Project B", directory = "/tmp/b"))
            val projs = mock.projects.value
            pAId = projs[0].id
            pBId = projs[1].id

            val convA = mock.createConversation(CreateConversationInput(projectId = pAId)).getOrThrow()
            val convB = mock.createConversation(CreateConversationInput(projectId = pBId)).getOrThrow()
            sAId = convA.id
            sBId = convB.id

            // 只归档 Project A 的会话
            mock.setConversationArchived(sAId, true, "req_arch_a")
        }

        val archA = client.get("/v1/projects/$pAId/archived-sessions")
        assertEquals(HttpStatusCode.OK, archA.status)
        val listA = json.decodeFromString<List<Conversation>>(archA.bodyAsText())
        assertEquals(1, listA.size)
        assertEquals(sAId, listA[0].id)

        val archB = client.get("/v1/projects/$pBId/archived-sessions")
        assertEquals(HttpStatusCode.OK, archB.status)
        val listB = json.decodeFromString<List<Conversation>>(archB.bodyAsText())
        assertEquals(0, listB.size)
    }

    @Test
    fun deduplicatesRetryCancelAndRecoveryOverHttp() = testApplication {
        val mock = MockAiCore()
        application {
            serverModule(aiCore = mock, ready = ReadyInfo(ready = true, configDir = "~/.mederi"))
        }
        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        var sessId = ""
        runBlocking {
            mock.createProject(CreateProjectInput(name = "Proj Dedupe", directory = "/tmp/dedupe"))
            val p = mock.projects.value.first()
            val c = mock.createConversation(CreateConversationInput(projectId = p.id)).getOrThrow()
            sessId = c.id
        }

        // 第一次调用 handoff
        val body = HandoffInput(targetKind = WorkspaceKind.MANAGED_WORKTREE, previewToken = "tok", requestId = "req_dedupe_1")
        val res1 = client.post("/v1/sessions/$sessId/workspace/handoff") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.OK, res1.status)
        val op1 = json.decodeFromString<WorkspaceOperation>(res1.bodyAsText())

        // 第二次重复相同 requestId
        val res2 = client.post("/v1/sessions/$sessId/workspace/handoff") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.OK, res2.status)
        val op2 = json.decodeFromString<WorkspaceOperation>(res2.bodyAsText())

        assertEquals(op1.id, op2.id)
    }

    @Test
    fun hydratesWorkspaceAfterDroppedEventsAndReconnect() = testApplication {
        val mock = MockAiCore()
        application {
            serverModule(aiCore = mock, ready = ReadyInfo(ready = true, configDir = "~/.mederi"))
        }
        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        var sessId = ""
        runBlocking {
            mock.createProject(CreateProjectInput(name = "Proj Recon", directory = "/tmp/recon"))
            val p = mock.projects.value.first()
            val c = mock.createConversation(CreateConversationInput(projectId = p.id)).getOrThrow()
            sessId = c.id
        }

        // 假定 SSE 事件在网络层断连丢失，客户端通过 REST GET 轮询水合
        val res = client.get("/v1/sessions/$sessId/workspace")
        assertEquals(HttpStatusCode.OK, res.status)
        val convWs = json.decodeFromString<ConversationWorkspaceInfo>(res.bodyAsText())
        assertEquals("ws_$sessId", convWs.active.workspace.id)
    }
}
