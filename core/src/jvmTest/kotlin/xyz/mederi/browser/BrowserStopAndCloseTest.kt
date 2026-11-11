package xyz.mederi.browser

import ai.koog.agents.core.tools.ToolBase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import xyz.mederi.domain.model.AIModel
import xyz.mederi.domain.model.EventType
import xyz.mederi.domain.model.MederiEvent
import xyz.mederi.provider.domain.model.ReasoningLevel
import xyz.mederi.tools.subagent.FakeProviderManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 浏览器任务停止/关闭生命周期锁定：
 * - stopTask 返回真实状态名（String?）：运行中 → STOPPED，终态 → 真实状态（COMPLETED），未知 taskId → null；
 * - 停止运行中任务后，即使 close() 带真实挂起点（取消上下文），浏览器也必须在 NonCancellable 下真正关闭；
 * - 重复 stopTask 幂等（终态不改写状态）。
 */
class BrowserStopAndCloseTest {

    private val fakeModel = AIModel(id = "stop-model", providerModelId = "stop-model", name = "Stop Model")

    /** 挂起不返回的 caller：flow 吐一帧增量后永久挂起，让任务稳定停在 RUNNING 供 stopTask 取消。 */
    private class HangingLLMCaller : BrowserLLMCaller {
        override suspend fun call(systemPrompt: String, userPrompt: String, image: ByteArray?): String = ""
        override suspend fun callWithToolsStreaming(
            systemPrompt: String,
            userPrompt: String,
            tools: List<ai.koog.agents.core.tools.ToolBase<*, *>>,
            image: ByteArray?
        ): kotlinx.coroutines.flow.Flow<BrowserLLMStreamEvent> = kotlinx.coroutines.flow.flow {
            emit(BrowserLLMStreamEvent.TextDelta("thinking"))
            kotlinx.coroutines.awaitCancellation()
        }
    }

    /** 立即 done 的 caller：第一轮就返回 done tool call，任务随即 COMPLETED。 */
    private class DoneLLMCaller(private val doneMessage: String = "操作完成") : BrowserLLMCaller {
        override suspend fun call(systemPrompt: String, userPrompt: String, image: ByteArray?): String = "{}"
        override suspend fun callWithTools(
            systemPrompt: String,
            userPrompt: String,
            tools: List<ToolBase<*, *>>,
            image: ByteArray?
        ): BrowserLLMResponse {
            return BrowserLLMResponse(
                text = "任务完成",
                toolCalls = listOf(
                    BrowserToolCall(
                        id = "done-1",
                        name = BrowserConstants.ToolNames.DONE,
                        argsJson = """{"message":"$doneMessage"}"""
                    )
                )
            )
        }
    }

    @Test
    fun stopTaskReallyClosesBrowserEvenWhenCloseSuspends() = runBlocking {
        val fakeControl = FakeBrowserControl(closeDelayMs = 100)
        BrowserRegistry.register("fake-stop-close", BrowserKind.OBSCURA, factory = { _, _ -> fakeControl })
        val eventBus = MutableSharedFlow<MederiEvent>(replay = 10)
        val manager = BrowserTaskManager(
            providerManager = FakeProviderManager(emptyMap()),
            scope = this,
            eventBus = eventBus,
            llmCallerProvider = { _, _, _ -> HangingLLMCaller() }
        )

        val taskId = manager.runTask(
            task = "挂起任务",
            aiModel = fakeModel,
            reasoningLevel = ReasoningLevel.NONE,
            projectId = "p1",
            parentSessionId = "sess_1",
            browser = "fake-stop-close",
            recipe = null
        )

        // 等待任务进入 RUNNING（最多 3 秒，20ms 一次）
        withTimeout(3000) {
            while (!manager.taskStatus(taskId).contains("\"RUNNING\"")) {
                delay(20)
            }
        }
        assertEquals("STOPPED", manager.stopTask(taskId))

        // close 带 100ms 真实挂起点：任务被取消后，NonCancellable 也必须把它执行完
        withTimeout(3000) {
            while (!fakeControl.log.contains("close-finished")) {
                delay(20)
            }
        }
        // 幂等：终态任务不改写状态，仍返回真实状态 STOPPED
        assertEquals("STOPPED", manager.stopTask(taskId))
    }

    @Test
    fun stopTaskOnCompletedTaskReturnsRealStatus() = runBlocking {
        val fakeControl = FakeBrowserControl()
        BrowserRegistry.register("fake-stop-done", BrowserKind.OBSCURA, factory = { _, _ -> fakeControl })
        val eventBus = MutableSharedFlow<MederiEvent>(replay = 10)
        val manager = BrowserTaskManager(
            providerManager = FakeProviderManager(emptyMap()),
            scope = this,
            eventBus = eventBus,
            llmCallerProvider = { _, _, _ -> DoneLLMCaller() }
        )

        val completed = asyncEvent(eventBus, EventType.BROWSER_TASK_COMPLETED)
        val taskId = manager.runTask(
            task = "立即完成",
            aiModel = fakeModel,
            reasoningLevel = ReasoningLevel.NONE,
            projectId = "p1",
            parentSessionId = "sess_1",
            browser = "fake-stop-done",
            recipe = null
        )
        completed.await()
        // 终态任务 stopTask 返回真实状态 COMPLETED，不伪装成 STOPPED
        assertEquals("COMPLETED", manager.stopTask(taskId))
    }

    @Test
    fun stopTaskUnknownIdReturnsNull() = runBlocking {
        val eventBus = MutableSharedFlow<MederiEvent>(replay = 10)
        val manager = BrowserTaskManager(
            providerManager = FakeProviderManager(emptyMap()),
            scope = this,
            eventBus = eventBus,
            llmCallerProvider = { _, _, _ -> HangingLLMCaller() }
        )
        assertNull(manager.stopTask("bt_missing"))
    }

    private fun CoroutineScope.asyncEvent(bus: MutableSharedFlow<MederiEvent>, type: EventType) =
        async {
            bus.filter { it.type == type }.first()
        }
}
