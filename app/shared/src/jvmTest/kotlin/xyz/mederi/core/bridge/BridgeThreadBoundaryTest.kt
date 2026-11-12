package xyz.mederi.core.bridge

import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import xyz.mederi.api.SessionApi
import xyz.mederi.core.contract.dto.ConversationSnapshot
import xyz.mederi.domain.model.AgentMode
import xyz.mederi.domain.model.EventType
import xyz.mederi.domain.model.MederiEvent
import xyz.mederi.domain.model.Message
import xyz.mederi.domain.model.Session
import xyz.mederi.domain.model.SessionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 线程边界回归（子任务 0）：桥层对外返回的 Flow body 必须与收集者解耦。
 *
 * 生产 wiring = MederiEventAggregator.observe(...).flowOn(Dispatchers.IO)（MederiAiCore）/
 * Dispatchers.Default（ServerAiCore，commonMain）。本测试直调 internal
 * [MederiAiCore.conversationFlow]（依赖注入式装配 = 生产同一条路径），注入记录线程名的
 * fake SessionApi；收集协程跑在专用单线程调度器上，模拟 viewModelScope = Main.immediate
 * 的收集者上下文（UI 主线程只收快照，不做数据加工）。
 * 若有人移除 conversationFlow 的 flowOn，本文件用例必然失败。
 */
class BridgeThreadBoundaryTest {

    private val timestamp = "2026-11-05T00:00:00Z"

    /** 收集者线程名（模拟 UI 主线程）。 */
    private val collectorThreadName = "bridge-test-collector"

    private val core = MederiAiCore(
        configDir = File(System.getProperty("java.io.tmpdir"), "mederi-bridge-thread-boundary").path
    )

    private fun session(id: String) = Session(
        id = id,
        projectId = "proj_1",
        title = "test",
        status = SessionStatus.IDLE,
        agentMode = AgentMode.AUTONOMOUS,
        aiModel = null,
        reasoningLevel = null,
        createdAt = timestamp,
        updatedAt = timestamp,
    )

    /** 记录被调线程名的 SessionApi fake；仅实现 observe 触及的成员。 */
    private inner class TracingSessionApi(private val sessionId: String) {
        val events = MutableSharedFlow<MederiEvent>() // replay=0，与宿主总线一致
        val bodyThreads = ConcurrentLinkedQueue<String>()

        val api: SessionApi =
            Proxy.newProxyInstance(SessionApi::class.java.classLoader, arrayOf(SessionApi::class.java)) { _, method, _ ->
                when (method.name) {
                    // observe 首帧 body：sessions.get + sessions.listMessages（refreshPage 复用 listMessages）
                    "get" -> {
                        bodyThreads.add(Thread.currentThread().name)
                        session(sessionId)
                    }
                    "listMessages" -> {
                        bodyThreads.add(Thread.currentThread().name)
                        emptyList<Message>()
                    }
                    "contextUsedTokens" -> 0
                    "listSubagentsForSession" -> emptyList<Any>()
                    "events" -> events
                    else -> error("Unexpected SessionApi.${method.name}")
                }
            } as SessionApi
    }

    private fun flowFor(tracing: TracingSessionApi, conversationId: String) = core.conversationFlow(
        conversationId = conversationId,
        sessions = tracing.api,
        modelToProvider = { null },
        initialSnapshot = {
            // 生产等价装配：首帧经 MederiAiCore.buildInitialSnapshot 的同一共享构建
            // （MederiEventAggregator.buildInitialSnapshot）加载数据并记录 body 线程名。
            MederiEventAggregator.buildInitialSnapshot(
                conversationId = conversationId,
                sessions = tracing.api,
                modelToProvider = { null },
                planHydration = { MederiEventAggregator.PlanApprovalHydration(null, emptyList()) },
                executionApprovals = { emptyList() },
                subagents = { emptyList() },
                lastError = { null },
                workspaceInfo = { null },
            )
        },
    )

    /** 在专用单线程调度器（模拟 UI 主线程）上执行测试体；测试后关闭执行器。 */
    private fun <T> withCollector(block: suspend CoroutineScope.(CoroutineDispatcher) -> T): T {
        val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, collectorThreadName).apply { isDaemon = true }
        }
        val dispatcher = executor.asCoroutineDispatcher()
        return try {
            runBlocking { block(dispatcher) }
        } finally {
            executor.shutdown()
        }
    }

    private fun assertRanOffCollector(tracing: TracingSessionApi, what: String) {
        assertTrue(tracing.bodyThreads.isNotEmpty(), "$what 应至少记录一次 body 调用")
        // 收集线程用 asCoroutineDispatcher() 跑协程时会被临时改名成 "<name> @coroutine#N"，
        // 因此用前缀匹配判断 body 是否落在收集者线程上。
        assertTrue(
            tracing.bodyThreads.all { !it.startsWith(collectorThreadName) },
            "$what 必须在非收集者线程执行，记录线程: ${tracing.bodyThreads}"
        )
    }

    @Test
    fun `initial snapshot body runs off collector thread`() = withCollector { dispatcher ->
        val tracing = TracingSessionApi("sess_x")
        val first = CompletableDeferred<ConversationSnapshot>()
        val job = launch(dispatcher) {
            flowFor(tracing, "sess_x").collect { first.complete(it) }
        }
        try {
            val snapshot = withTimeout(5_000) { first.await() }
            assertNotNull(snapshot)
            assertRanOffCollector(tracing, "首帧 body（sessions.get / listMessages）")
            assertTrue(tracing.bodyThreads.size >= 2, "首帧应至少触发 get + listMessages: ${tracing.bodyThreads}")
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun `refreshPage fallback runs off collector thread`() = withCollector { dispatcher ->
        val tracing = TracingSessionApi("sess_x")
        val first = CompletableDeferred<ConversationSnapshot>()
        val job = launch(dispatcher) {
            flowFor(tracing, "sess_x").collect { first.complete(it) }
        }
        try {
            withTimeout(5_000) { first.await() }
            val initialCalls = tracing.bodyThreads.size
            // 等事件订阅就位（replay=0 总线，事件在订阅前发出会被丢）
            withTimeout(5_000) { while (tracing.events.subscriptionCount.value == 0) delay(10) }
            // SESSION_UPDATED → ConversationStateReducer 走 refreshPage 落库回查 → listMessages
            tracing.events.emit(MederiEvent(EventType.SESSION_UPDATED, "sess_x", timestamp = timestamp))
            withTimeout(5_000) { while (tracing.bodyThreads.size <= initialCalls) delay(10) }
            assertRanOffCollector(tracing, "refreshPage 回查（listMessages）")
            assertEquals(initialCalls + 1, tracing.bodyThreads.size, "事件应恰好触发一次 refreshPage 回查")
        } finally {
            job.cancelAndJoin()
        }
    }
}
