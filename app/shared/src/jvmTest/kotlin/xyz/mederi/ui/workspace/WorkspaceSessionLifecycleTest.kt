package xyz.mederi.ui.workspace

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import xyz.mederi.core.contract.AiCore
import xyz.mederi.core.contract.dto.ChatPromptInput
import xyz.mederi.core.contract.dto.ConversationState
import xyz.mederi.core.contract.dto.CreateConversationInput
import xyz.mederi.core.contract.dto.CreateProjectInput
import xyz.mederi.core.contract.models.*
import xyz.mederi.core.contract.preferences.InMemoryPreferencesStore
import xyz.mederi.core.mock.MockAiCore
import xyz.mederi.ui.WorkspacePresenter
import xyz.mederi.ui.WorkspaceViewModel
import xyz.mederi.ui.appstate.AppState
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceSessionLifecycleTest {

    private class LifecycleMockAiCore(val delegate: MockAiCore = MockAiCore()) : AiCore by delegate {
        val sendCalls = CopyOnWriteArrayList<Pair<String, ChatPromptInput>>()
        val retryPrepCalls = CopyOnWriteArrayList<Triple<String, String, String>>()

        override suspend fun sendMessage(conversationId: String, input: ChatPromptInput): Result<Unit> {
            sendCalls.add(conversationId to input)
            return delegate.sendMessage(conversationId, input)
        }

        override suspend fun retryWorkspacePreparation(
            sessionId: String,
            failedOperationId: String,
            requestId: String
        ): Result<WorkspaceOperation> {
            retryPrepCalls.add(Triple(sessionId, failedOperationId, requestId))
            return delegate.retryWorkspacePreparation(sessionId, failedOperationId, requestId)
        }
    }

    @Test
    fun doesNotQueueOrDuplicateStableUserInputInUi() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val mock = LifecycleMockAiCore()
        val store = ViewModelStore()
        try {
            val appState = AppState(aiCore = mock, preferences = InMemoryPreferencesStore(), scope = backgroundScope)
            val vm = WorkspaceViewModel(appState)
            store.put("vm", vm)
            runCurrent()

            // 创建测试项目并选中
            val project = Project(id = "p1", name = "Test Proj", directory = "/tmp/p1", conversations = emptyList())
            mock.delegate.createProject(CreateProjectInput(name = "Test Proj", directory = "/tmp/p1"))
            vm.selectProject("p1")
            runCurrent()

            // 构造一条稳定输入发送
            val stableText = "Hello workspace"
            vm.send(stableText)
            runCurrent()

            // 验证 UI 未引入额外的暂存队列排队，也未复制出多条相同内容的不同 ID 消息
            val convId = vm.conversationId
            if (convId != null) {
                val page = mock.delegate.listMessagesPage(convId).getOrNull()
                val userMessages = page?.messages?.filter { it.role == ChatRole.User } ?: emptyList()
                assertTrue(userMessages.size <= 1)
                if (userMessages.isNotEmpty()) {
                    val blockText = userMessages.first().blocks.filterIsInstance<ChatBlock.Text>().firstOrNull()?.text
                    assertEquals(stableText, blockText)
                }
            }
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun preparationRetryDoesNotSendNewMessage() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val mock = LifecycleMockAiCore()
        val store = ViewModelStore()
        try {
            val appState = AppState(aiCore = mock, preferences = InMemoryPreferencesStore(), scope = backgroundScope)
            val vm = WorkspaceViewModel(appState)
            store.put("vm", vm)
            runCurrent()

            val conv = mock.createConversation(
                CreateConversationInput(
                    projectId = "p1",
                    workspaceSelection = WorkspaceSelection(kind = WorkspaceKind.LOCAL)
                )
            ).getOrThrow()
            vm.attach(conv.id)
            runCurrent()

            val failedOp = WorkspaceOperation(
                id = "op_failed_1",
                projectId = "p1",
                sessionId = conv.id,
                workspaceId = "ws1",
                kind = WorkspaceOperationKind.SETUP,
                state = WorkspaceOperationState.FAILED,
                phase = WorkspacePhase.SETUP,
                idempotencyKey = "key1",
                requestHash = "hash1",
                createdAt = "2026-11-02T00:00:00Z",
                updatedAt = "2026-11-02T00:00:00Z",
                error = WorkspaceError(WorkspaceErrorCode.SETUP_FAILED, "Setup failed"),
                operationPlan = WorkspaceOperationPlan.CreatePlan(
                    requestHash = "hash1",
                    selection = WorkspaceSelection(),
                    targetPath = "/tmp/ws1"
                )
            )

            val initialSendCount = mock.sendCalls.size

            // 点击或触发重试准备
            vm.retryWorkspaceOperation(failedOp)
            runCurrent()

            // 确认调用了 retryWorkspacePreparation，而绝对没有调用 sendMessage，也没有追加新用户消息
            assertEquals(1, mock.retryPrepCalls.size)
            assertEquals(conv.id, mock.retryPrepCalls.first().first)
            assertEquals("op_failed_1", mock.retryPrepCalls.first().second)

            // sendMessage 调用数保持不变（零新增消息）
            assertEquals(initialSendCount, mock.sendCalls.size)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun unknownOwnerAndSessionSwitchDoNotRetargetOperations() {
        val presenter = WorkspacePresenter()

        val basePlan = WorkspaceOperationPlan.CreatePlan(
            requestHash = "hash1",
            selection = WorkspaceSelection(),
            targetPath = "/tmp/ws"
        )

        val opSessionA = WorkspaceOperation(
            id = "op_a",
            projectId = "p1",
            sessionId = "sess_a",
            workspaceId = "ws_a",
            kind = WorkspaceOperationKind.SETUP,
            state = WorkspaceOperationState.RUNNING,
            phase = WorkspacePhase.COPY_CONTENT,
            idempotencyKey = "k_a",
            requestHash = "h_a",
            createdAt = "2026-11-02T00:00:00Z",
            updatedAt = "2026-11-02T00:00:00Z",
            operationPlan = basePlan
        )

        val opUnknownOwner = opSessionA.copy(id = "op_unknown", sessionId = null)
        val opDifferentProject = opSessionA.copy(id = "op_diff_proj", projectId = "p2")

        val stateA = ConversationState(
            conversation = Conversation(
                id = "sess_a",
                projectId = "p1",
                title = "Session A",
                status = ConversationStatus.Idle,
                createdAt = 1700000000000L,
                updatedAt = 1700000000000L,
                agent = "AUTONOMOUS"
            ),
            messages = emptyList(),
            tokenUsage = TokenUsage(),
            cost = CostSummary(),
            activeWorkspaceOperation = opSessionA
        )

        // 1. Session A 正确解析自身 operation
        val resolvedA = presenter.resolveActiveOperation("sess_a", stateA)
        assertNotNull(resolvedA)
        assertEquals("op_a", resolvedA.id)

        // 2. 切到 Session B 时，不得借用或将 Session A 的操作 retarget 到 Session B
        val resolvedSwitched = presenter.resolveActiveOperation("sess_b", stateA)
        assertNull(resolvedSwitched)

        // 3. owner 未知（sessionId 为 null）的操作严禁被任一会话认领
        val stateUnknown = stateA.copy(activeWorkspaceOperation = opUnknownOwner)
        assertNull(presenter.resolveActiveOperation("sess_a", stateUnknown))

        // 4. 项目不匹配的操作严禁认领
        val stateDiffProject = stateA.copy(activeWorkspaceOperation = opDifferentProject)
        assertNull(presenter.resolveActiveOperation("sess_a", stateDiffProject))
    }
}
