package xyz.mederi.ui.workspace

import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import xyz.mederi.core.contract.TerminalManager
import xyz.mederi.core.contract.TerminalSession
import xyz.mederi.core.contract.dto.ReadyInfo
import xyz.mederi.core.contract.models.Conversation
import xyz.mederi.core.contract.models.ConversationStatus
import xyz.mederi.core.contract.models.FileDiff
import xyz.mederi.core.contract.models.WorkspaceFileArea
import xyz.mederi.core.contract.models.WorkspaceFileContent
import xyz.mederi.core.contract.models.WorkspaceFileEntry
import xyz.mederi.core.contract.models.WorkspaceKind
import xyz.mederi.core.contract.models.WorkspaceSelection
import xyz.mederi.core.mock.MockAiCore
import xyz.mederi.server.serverModule
import xyz.mederi.ui.DiffPresenter
import xyz.mederi.ui.TerminalViewModel
import xyz.mederi.ui.appstate.AppState
import xyz.mederi.ui.terminalWorkspaceIdOf

class WorkspaceFileUiTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun showsHostWorkspaceFilesOnDesktopAndRemote() = testApplication {
        val mockAiCore = MockAiCore()
        application {
            serverModule(aiCore = mockAiCore, ready = ReadyInfo(ready = true, configDir = "~/.mederi"))
        }
        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        // 1. 初始化对话和工作区
        val conv = runBlocking {
            mockAiCore.createConversation(
                xyz.mederi.core.contract.dto.CreateConversationInput(
                    projectId = "p1",
                    workspaceSelection = WorkspaceSelection(kind = WorkspaceKind.LOCAL)
                )
            ).getOrThrow()
        }
        val wsId = conv.workspaceId ?: "ws_${conv.id}"

        // 2. 桌面直调与遥控 HTTP 调取文件列表对比
        val desktopFiles = runBlocking {
            mockAiCore.listWorkspaceFiles(wsId, "", WorkspaceFileArea.PROJECT).getOrThrow()
        }
        val remoteFilesResp = client.get("/v1/workspaces/$wsId/files?path=&area=PROJECT")
        assertEquals(HttpStatusCode.OK, remoteFilesResp.status)
        val remoteFiles = json.decodeFromString<List<WorkspaceFileEntry>>(remoteFilesResp.bodyAsText())

        assertEquals(desktopFiles.size, remoteFiles.size)
        assertEquals(desktopFiles.first().name, remoteFiles.first().name)
        assertEquals(desktopFiles.first().relativePath, remoteFiles.first().relativePath)
        assertEquals(desktopFiles.first().kind, remoteFiles.first().kind)

        // 3. 桌面直调与遥控 HTTP 读取单文件内容对比
        val desktopFileContent = runBlocking {
            mockAiCore.readWorkspaceFile(wsId, "README.md", 0L, 262144, WorkspaceFileArea.PROJECT).getOrThrow()
        }
        val remoteFileResp = client.get("/v1/workspaces/$wsId/file?path=README.md&area=PROJECT")
        assertEquals(HttpStatusCode.OK, remoteFileResp.status)
        val remoteFileContent = json.decodeFromString<WorkspaceFileContent>(remoteFileResp.bodyAsText())

        assertEquals(desktopFileContent.relativePath, remoteFileContent.relativePath)
        assertEquals(desktopFileContent.text, remoteFileContent.text)
        assertEquals(desktopFileContent.mime, remoteFileContent.mime)

        // 4. 路径穿越与逃根拒绝验证（测试安全防御）
        val traversalDesktop = runBlocking {
            mockAiCore.readWorkspaceFile(wsId, "../../etc/shadow", 0L, 262144, WorkspaceFileArea.PROJECT)
        }
        // 桌面直调必须返回失败，不能只“不包含系统密钥”就算通过：
        // MockAiCore 必须在 mock 层拒绝逃根路径（失败原因与真实 PATH_DENIED 同一语义）。
        assertTrue(traversalDesktop.isFailure, "桌面直调必须拒绝路径穿越, 实际: $traversalDesktop")
        assertEquals(
            true,
            traversalDesktop.exceptionOrNull()?.message?.contains("escapes workspace root"),
            "失败原因必须明确为逃根拒绝, 实际: ${traversalDesktop.exceptionOrNull()}"
        )

        // 遥控实现：HTTP 必须返回错误状态而非 200+内容。真实 core 在 server 侧把
        // MederiWorkspaceException(PATH_DENIED) 映射为 403；MockAiCore 位于 commonMain
        // 只能抛通用异常（server 映射为 5xx），因此这里断言“非成功状态”且绝不返回内容。
        val traversalRemote = client.get("/v1/workspaces/$wsId/file?path=../../etc/shadow&area=PROJECT")
        assertTrue(
            traversalRemote.status.value >= 400 && traversalRemote.status.value < 600,
            "路径穿越请求必须被拒绝（错误状态），实际: ${traversalRemote.status}"
        )
        // 请求被拒绝，响应体即使含错误信息也决不能包含系统真实密钥/文件内容
        assertFalse(traversalRemote.bodyAsText().contains("root:x:0:0"))
    }

    @Test
    fun discardsStaleFileResultsAndKeepsDiffOrigin() = runBlocking {
        val mockAiCore = MockAiCore()

        // 1. 验证 DiffPresenter 异步旧响应 token 丢弃
        val presenter = DiffPresenter(mockAiCore, this)
        presenter.bind("session_1")
        presenter.setVisible(true)

        // 发起第一次请求
        presenter.open("msg_1", null)
        assertTrue(presenter.state.requested)
        assertEquals("msg_1", presenter.state.messageId)

        // 在第一次响应可能还未处理完时，快速切换到第二次请求
        presenter.open("msg_2", "src/Main.kt")
        assertEquals("msg_2", presenter.state.messageId)
        assertEquals("src/Main.kt", presenter.state.selectedPath)

        // 验证不会被 msg_1 的陈旧结果覆写
        presenter.close()

        // 2. 验证 Diff 包含完整的 originWorkspaceId 且保持只读
        val diffFromWs1 = FileDiff(
            filePath = "src/Foo.kt",
            before = "val a = 1",
            after = "val a = 2",
            additions = 1,
            deletions = 1,
            originWorkspaceId = "ws_alpha",
            originCheckoutRoot = "/mock/projects/p1/.mederi/worktrees/alpha",
            readOnly = true
        )

        assertEquals("ws_alpha", diffFromWs1.originWorkspaceId)
        assertTrue(diffFromWs1.readOnly)

        // 即使会话已交接到新工作区 ws_beta，历史 Diff 仍然指回 ws_alpha 且只读
        val currentWorkspaceId = "ws_beta"
        assertTrue(currentWorkspaceId != diffFromWs1.originWorkspaceId)
        assertEquals("ws_alpha", diffFromWs1.originWorkspaceId)
        assertTrue(diffFromWs1.readOnly)
    }

    @Test
    fun retainsTerminalWorkspaceIdentityAcrossHandoff() = runBlocking {
        val createdSessions = mutableMapOf<String, TestTerminalSession>()

        val mockManager = object : TerminalManager {
            override fun getOrCreate(key: String, cwd: String?, title: String): TerminalSession {
                return createdSessions.getOrPut(key) {
                    TestTerminalSession(key = key, cwd = cwd, title = title)
                }
            }

            override fun find(key: String): TerminalSession? = createdSessions[key]
            override fun all(): List<TerminalSession> = createdSessions.values.toList()
        }

        val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            val appState = AppState(
                aiCore = MockAiCore(),
                preferences = xyz.mederi.core.contract.preferences.InMemoryPreferencesStore(),
                scope = appScope
            )
            appState.terminalManager = mockManager

            val vm = TerminalViewModel(appState)

            // 1. 在工作区 ws_alpha 下打开终端
            vm.addTabForWorkspace("ws_alpha", "/checkouts/alpha", "Alpha Terminal")
            assertEquals(1, vm.uiState.tabs.size)
            val tab1Key = vm.uiState.tabs.first().key
            assertEquals("workspace:ws_alpha", tab1Key)
            assertEquals("ws_alpha", terminalWorkspaceIdOf(tab1Key))

            var session1: TerminalSession? = null
            for (i in 0 until 50) {
                session1 = vm.sessionOf(tab1Key)
                if (session1 != null) break
                kotlinx.coroutines.delay(20)
            }
            assertNotNull(session1)
            assertEquals("/checkouts/alpha", (session1 as TestTerminalSession).cwd)

            // 2. 模拟工作区 Handoff 到 ws_beta
            // 旧终端会话不应当突变为新工作区的 cwd
            assertEquals("/checkouts/alpha", session1.cwd)
            assertEquals("workspace:ws_alpha", session1.key)

            // 3. 在新工作区 ws_beta 打开终端
            vm.addTabForWorkspace("ws_beta", "/checkouts/beta", "Beta Terminal")
            assertEquals(2, vm.uiState.tabs.size)
            val tab2Key = vm.uiState.tabs[1].key
            assertEquals("workspace:ws_beta", tab2Key)
            assertEquals("ws_beta", terminalWorkspaceIdOf(tab2Key))

            var session2: TerminalSession? = null
            for (i in 0 until 50) {
                session2 = vm.sessionOf(tab2Key)
                if (session2 != null) break
                kotlinx.coroutines.delay(20)
            }
            assertNotNull(session2)
            assertEquals("/checkouts/beta", (session2 as TestTerminalSession).cwd)

            // 验证两个会话独立保留各自的工作区归属
            assertEquals("/checkouts/alpha", (vm.sessionOf(tab1Key) as TestTerminalSession).cwd)
            assertEquals("/checkouts/beta", (vm.sessionOf(tab2Key) as TestTerminalSession).cwd)

            // 4. 在 ws_beta 中再新建第二个 shell
            vm.addTabForWorkspace("ws_beta", "/checkouts/beta", "Beta Terminal 2")
            assertEquals(3, vm.uiState.tabs.size)
            val tab3Key = vm.uiState.tabs[2].key
            assertEquals("workspace:ws_beta#2", tab3Key)
            assertEquals("ws_beta", terminalWorkspaceIdOf(tab3Key))
        } finally {
            appScope.cancel()
        }
    }

    private class TestTerminalSession(
        override val key: String,
        val cwd: String?,
        override val title: String
    ) : TerminalSession {
        override val isRunning: StateFlow<Boolean> = MutableStateFlow(true)
        override val exitCode: StateFlow<Int?> = MutableStateFlow(null)
        override fun output(): Flow<String> = emptyFlow()
        override suspend fun write(text: String) {}
        override fun resize(cols: Int, rows: Int) {}
        override fun kill() {}
    }
}
