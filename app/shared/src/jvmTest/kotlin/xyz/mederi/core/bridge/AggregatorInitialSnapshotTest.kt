package xyz.mederi.core.bridge

import java.lang.reflect.Proxy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import xyz.mederi.api.SessionApi
import xyz.mederi.core.contract.dto.ConversationSnapshot
import xyz.mederi.core.contract.models.ConversationStatus
import xyz.mederi.core.contract.models.PlanApprovalRequest
import xyz.mederi.domain.model.AgentMode
import xyz.mederi.domain.model.EventType
import xyz.mederi.domain.model.MederiEvent
import xyz.mederi.domain.model.Message
import xyz.mederi.domain.model.MessagePart
import xyz.mederi.domain.model.MessageRole
import xyz.mederi.domain.model.Session
import xyz.mederi.domain.model.SessionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 子任务 1 首帧快照构建单一化的回归测试（专测 T2 硬约束）：
 * - T2_INITIAL_FIRST：observe 首帧 == 注入的 initialSnapshot lambda 原样结果，事件帧在其后归约。
 * - T2_TOKEN_REUSE：buildInitialSnapshot 复用已加载消息算 contextUsedTokens，
 *   绝不回调 sessions.contextUsedTokens（红/绿自检点）。
 * - T2_PLAN_SINGLE_SCAN：planHydration 恰好调用一次，pending 严格等于该次返回
 *   （firstOrNull 非终态 且 PENDING 才有值，不可退化成"列表里第一个 PENDING"红/绿自检点）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AggregatorInitialSnapshotTest {

    private val timestamp = "2026-11-05T00:00:00Z"

    private fun session(id: String = "s") = Session(
        id = id,
        projectId = "p",
        title = "test",
        status = SessionStatus.IDLE,
        agentMode = AgentMode.AUTONOMOUS,
        aiModel = null,
        reasoningLevel = null,
        createdAt = timestamp,
        updatedAt = timestamp,
    )

    /** 与既有 fake SessionApi 样例一致：只实现 observe / buildInitialSnapshot 触及的成员。 */
    private fun fakeApi(
        session: Session,
        messages: List<Message>,
        events: MutableSharedFlow<MederiEvent>,
        contextUsedTokens: () -> Int = { 0 },
    ): SessionApi = Proxy.newProxyInstance(
        SessionApi::class.java.classLoader,
        arrayOf(SessionApi::class.java)
    ) { _, method, _ ->
        when (method.name) {
            "get" -> session
            "listMessages" -> messages
            "contextUsedTokens" -> contextUsedTokens()
            "listSubagentsForSession" -> emptyList<Any>()
            "events" -> events
            else -> error("Unexpected API ${method.name}")
        }
    } as SessionApi

    // ------------------------------------------------------------------
    // T2_INITIAL_FIRST：首帧 == lambda 返回的快照；事件在其后归约
    // ------------------------------------------------------------------

    @Test
    fun initialFrameIsLambdaResultThenEventReduced() = runTest {
        val events = MutableSharedFlow<MederiEvent>()
        val input = Message(
            id = "input", sessionId = "s", role = MessageRole.USER,
            parts = listOf(MessagePart.Text("request")),
            createdAt = timestamp, turnId = "turn",
        )
        val api = fakeApi(session(), messages = listOf(input), events = events)

        // 固定快照：与任何 session 回查无关，专门证明首帧是 lambda 的原样结果
        val fixed = ConversationSnapshot(
            conversation = MederiModelMapper.toConversation(session(), null),
            messages = emptyList(),
            tokenUsage = MederiModelMapper.toTokenUsage(emptyList()),
            cost = MederiModelMapper.toCostSummary(),
        )

        val frames = mutableListOf<ConversationSnapshot>()
        val observer = backgroundScope.launch {
            MederiEventAggregator.observe(
                conversationId = "s",
                sessions = api,
                initialSnapshot = { fixed },
                modelToProvider = { null },
            ).collect { frames.add(it) }
        }
        // 等事件订阅就位（replay=0 总线，先 emit 首帧再 collect events 的时序不变）
        repeat(10) {
            if (events.subscriptionCount.value == 1) return@repeat
            runCurrent()
        }
        assertEquals(1, events.subscriptionCount.value, "首帧 emit 之后才订阅事件流")
        events.emit(MederiEvent(EventType.SESSION_UPDATED, "s", timestamp = timestamp))
        runCurrent()

        observer.cancel()
        assertEquals(2, frames.size, "首帧 + 事件归约帧")
        assertEquals(fixed, frames[0], "第一帧必须 == initialSnapshot lambda 返回的快照")
        assertEquals(ConversationStatus.Working, frames[1].conversation.status, "第二帧 = SESSION_UPDATED 归约结果")
        assertEquals(listOf("input"), frames[1].messages.map { it.id }, "事件帧经 refreshPage 对齐落库消息")
    }

    // ------------------------------------------------------------------
    // T2_TOKEN_REUSE：contextUsedTokens 复用已加载消息，不回调 sessions
    // ------------------------------------------------------------------

    @Test
    fun contextUsedTokensReusesLoadedMessages() = runTest {
        // SUMMARY 标记 + 后续消息：computeContextUsedTokens 走 estimateTokens 分支，
        // 与末条 Assistant 的 API 报告 inputTokens 无关，期望值完全确定。
        val messages = listOf(
            Message(sessionId = "s", role = MessageRole.SUMMARY, parts = listOf(MessagePart.Text("summary marker")), createdAt = timestamp),
            Message(sessionId = "s", role = MessageRole.ASSISTANT, parts = listOf(MessagePart.Text("assistant reply")), createdAt = timestamp, inputTokens = 4321),
        )
        var contextUsedTokensCalls = 0
        val api = fakeApi(session(), messages = messages, events = MutableSharedFlow()) {
            contextUsedTokensCalls++
            999 // fake 故意返回与计算值不同的"二次加载"结果，任何回退都会让断言变红
        }

        val snapshot = MederiEventAggregator.buildInitialSnapshot(
            conversationId = "s",
            sessions = api,
            modelToProvider = { null },
            planHydration = { MederiEventAggregator.PlanApprovalHydration(null, emptyList()) },
            executionApprovals = { emptyList() },
            subagents = { emptyList() },
            lastError = { null },
            workspaceInfo = { null },
        )

        assertEquals(0, contextUsedTokensCalls, "首帧 token 必须复用已加载消息，不再调 sessions.contextUsedTokens 二次 DB 加载")
        assertEquals(
            MederiEventAggregator.computeContextUsedTokens(messages, includeImages = true).toLong(),
            snapshot.contextUsedTokens,
            "口径与 computeContextUsedTokens（同一消息列表）完全一致",
        )
    }

    // ------------------------------------------------------------------
    // T2_PLAN_SINGLE_SCAN：planHydration 只调用一次；pending 严格取该次返回
    // ------------------------------------------------------------------

    @Test
    fun planHydrationCalledOnceAndPendingFollowsStrictSemantics() = runTest {
        val plans = listOf(
            PlanApprovalRequest(id = "approved", conversationId = "s", planPath = "a.md", title = "approved", status = "APPROVED"),
            PlanApprovalRequest(id = "pending", conversationId = "s", planPath = "b.md", title = "pending", status = "PENDING"),
        )
        var planHydrationCalls = 0
        // 注入与生产 initialPlanHydration 相同的严格推导：firstOrNull 非终态 且 PENDING 才有值
        val api = fakeApi(session(), messages = emptyList(), events = MutableSharedFlow())
        val snapshot = MederiEventAggregator.buildInitialSnapshot(
            conversationId = "s",
            sessions = api,
            modelToProvider = { null },
            planHydration = {
                planHydrationCalls++
                MederiEventAggregator.PlanApprovalHydration(
                    pending = plans.firstOrNull { it.status !in setOf("COMPLETED", "VOIDED") }
                        ?.takeIf { it.status == "PENDING" },
                    all = plans,
                )
            },
            executionApprovals = { emptyList() },
            subagents = { emptyList() },
            lastError = { null },
            workspaceInfo = { null },
        )

        assertEquals(1, planHydrationCalls, "计划目录只能扫描一次（pending + 全量同源），不得 planApprovals/planApproval 各扫一遍")
        assertNull(snapshot.pendingPlanApproval, "[APPROVED, PENDING]：第一条非终态是 APPROVED → pending 必须为 null（不能取列表里第一个 PENDING）")
        assertEquals(plans, snapshot.planApprovals, "全量计划列表取自同一次扫描")
    }

    @Test
    fun planHydrationPendingIsFirstNonTerminalPending() = runTest {
        val plans = listOf(
            PlanApprovalRequest(id = "pending", conversationId = "s", planPath = "b.md", title = "pending", status = "PENDING"),
            PlanApprovalRequest(id = "approved", conversationId = "s", planPath = "a.md", title = "approved", status = "APPROVED"),
        )
        var planHydrationCalls = 0
        val api = fakeApi(session(), messages = emptyList(), events = MutableSharedFlow())
        val snapshot = MederiEventAggregator.buildInitialSnapshot(
            conversationId = "s",
            sessions = api,
            modelToProvider = { null },
            planHydration = {
                planHydrationCalls++
                MederiEventAggregator.PlanApprovalHydration(
                    pending = plans.firstOrNull { it.status !in setOf("COMPLETED", "VOIDED") }
                        ?.takeIf { it.status == "PENDING" },
                    all = plans,
                )
            },
            executionApprovals = { emptyList() },
            subagents = { emptyList() },
            lastError = { null },
            workspaceInfo = { null },
        )

        assertEquals(1, planHydrationCalls, "计划目录只能扫描一次")
        assertEquals("pending", snapshot.pendingPlanApproval?.id, "[PENDING, APPROVED]：第一条非终态是 PENDING → pending == 第一个")
        assertEquals(plans, snapshot.planApprovals, "全量计划列表取自同一次扫描")
    }
}