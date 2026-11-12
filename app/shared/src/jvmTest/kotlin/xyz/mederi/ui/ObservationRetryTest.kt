package xyz.mederi.ui

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import xyz.mederi.core.contract.AiCore
import xyz.mederi.core.contract.dto.ConversationState
import xyz.mederi.core.contract.models.*
import xyz.mederi.core.contract.preferences.InMemoryPreferencesStore
import xyz.mederi.core.mock.MockAiCore
import xyz.mederi.ui.appstate.AppState
import kotlin.test.*

/**
 * 观察流（ensureObserving）失败自动重试行为：
 * - T6_AUTO_RETRY：首次 collect 抛异常 → 无限退避重试重订阅上游 → 快照最终到达。
 * - T6_CACHE_KEPT：持续抛异常期间观察 Job 保持活跃（重试进行中），重复 attach 不重建 Job。
 *   （缓存条目不删语义由实现保证：ensureObserving catch 分支不再调用
 *   sessionCache.conversationStates.remove —— 本测试无法访问 private cache，
 *   以"Job 存活 + 不重建 + 不退化为空白"间接断言。）
 *
 * Fake 说明：retryWhen 的重试是对**同一个 flow 对象重新 collect**（不重新调用
 * observeConversation），因此失败/成功必须按 collect 次数（而非 observeConversation
 * 调用次数）在 flow builder 内决定；observeCalls 只用于断言"未重建 Job"。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObservationRetryTest {
    private fun snapshot(id: String) = ConversationState(
        Conversation(id, "proj_1", "title", ConversationStatus.Idle, 1, 1), emptyList(), TokenUsage(), cost = CostSummary()
    )

    private fun snapshotWithOneMessage(id: String) = snapshot(id).copy(
        messages = listOf(
            ChatMessage(
                id = "msg-$id-1",
                conversationId = id,
                role = ChatRole.Assistant,
                blocks = listOf(ChatBlock.Text("b-1", "retried snapshot")),
                createdAt = 1000L,
                completedAt = null,
                parentMessageId = null,
                model = null,
                agent = null
            )
        )
    )

    private inner class Fake(val backend: MockAiCore) : AiCore by backend {
        val flows = mutableMapOf<String, MutableSharedFlow<ConversationState>>()
        /** observeConversation 被调用次数（一次 observe job 只调用一次；重试是重新 collect 同一 flow） */
        var observeCalls = 0
        /** flow 被 collect 的次数（重试每次重订阅都会 +1） */
        var collectAttempts = 0
        /** 前 N 次 collect 抛 RuntimeException（模拟会话被删 NotFound、DB busy、SSE 抖动等瞬时失败） */
        var failFirstCollects = 0
        /** true = 每次 collect 都抛异常（模拟持续失败） */
        var alwaysFail = false

        override fun observeConversation(conversationId: String): Flow<ConversationState> {
            observeCalls++
            return flow {
                collectAttempts++
                if (alwaysFail || collectAttempts <= failFirstCollects) {
                    throw RuntimeException("simulated observe failure (collect attempt #$collectAttempts)")
                }
                // 正常路径：转发共享流（replay=1，支持测试在任意时刻 emit 快照）
                flows.getOrPut(conversationId) { MutableSharedFlow(replay = 1) }.collect { emit(it) }
            }
        }
    }

    private fun exercise(block: suspend TestScope.(WorkspaceViewModel, AppState, Fake) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val core = Fake(MockAiCore()); core.initialize()
        val app = AppState(core, InMemoryPreferencesStore(), backgroundScope); app.hydrate()
        val store = ViewModelStore(); val vm = WorkspaceViewModel(app); store.put("retry", vm)
        try { runCurrent(); block(vm, app, core) } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test
    fun T6_AUTO_RETRY_snapshot_received_after_retry() = exercise { vm, app, core ->
        core.failFirstCollects = 1
        app.selectConversation("a"); runCurrent()
        assertEquals(1, core.observeCalls, "first observeConversation must be subscribed")
        assertEquals(1, core.collectAttempts, "first collect must throw")
        // 第一次 collect 抛 RuntimeException → retryWhen 进入首次 1s 退避（虚拟时间，无需真实等待）
        advanceTimeBy(1100); runCurrent()
        assertTrue(core.collectAttempts >= 2, "retry must re-collect upstream (collectAttempts=${core.collectAttempts})")
        // 重订阅成功后 emit 含一条消息的快照 → applySnapshot 应把状态送达当前会话渲染
        core.flows.getValue("a").emit(snapshotWithOneMessage("a")); runCurrent()
        val state = assertNotNull(vm.conversationState, "snapshot must arrive after auto retry")
        assertEquals(1, state.messages.size)
        assertEquals("msg-a-1", state.messages.single().id)
    }

    @Test
    fun T6_CACHE_KEPT_pool_entry_retained() = exercise { vm, _, core ->
        core.alwaysFail = true
        vm.attach("a"); runCurrent()
        val callsAfterAttach = core.observeCalls
        assertTrue(callsAfterAttach >= 1, "observe must have started on attach")
        assertTrue(core.collectAttempts >= 1, "first collect must have thrown")
        // 幂等：观察 Job 仍活跃 → 再次 attach 同一会话不得重建/重启观察流（否则 observeConversation 会再次被调用）
        vm.attach("a"); runCurrent()
        assertEquals(callsAfterAttach, core.observeCalls, "active observe job must not be rebuilt by re-attach")
        // 退避重试进行中：推进虚拟时间后上游被重新 collect（job 未死、一直在重试）
        advanceTimeBy(1100); runCurrent()
        assertTrue(core.collectAttempts > 1, "retry must keep re-collecting (collectAttempts=${core.collectAttempts})")
        // 持续失败期间无快照可渲染（UI 停在等待恢复状态），但绝不落到"观察流已死"的空态。
        // 缓存不删语义由实现保证：ensureObserving catch 分支不再调用 conversationStates.remove。
        assertNull(vm.conversationState)
    }
}