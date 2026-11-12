package xyz.mederi.ui

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import xyz.mederi.core.contract.AiCore
import xyz.mederi.core.contract.dto.ConversationState
import xyz.mederi.core.contract.models.Conversation
import xyz.mederi.core.contract.models.ConversationStatus
import xyz.mederi.core.contract.models.CostSummary
import xyz.mederi.core.contract.models.PlanApprovalRequest
import xyz.mederi.core.contract.models.PlanSubtaskItem
import xyz.mederi.core.contract.models.TokenUsage
import xyz.mederi.core.contract.preferences.InMemoryPreferencesStore
import xyz.mederi.core.mock.MockAiCore
import xyz.mederi.ui.appstate.AppState
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 子任务 4：Plan/Spec UI 按需读盘适配（getPlanDocument 异步读盘统一链路）。
 *
 * T5_PLAN_ON_DEMAND：openPlanFile → 统一入口调 getPlanDocument(planId) → 阅读器打开且正文 == 契约返回。
 * T5_SPEC_ON_DEMAND：planOverviewList 对 hasSpec 的映射正确；打开 Spec 走 getPlanDocument(subtaskIndex=N)
 *                    且阅读器展示拼装正文（按钮显隐本体 `if (step.hasSpec)` 是 Composable 编译级保证，此处不测组合）。
 * T5_FAILURE_NOTICE：getPlanDocument 失败 → 一次性轻提示置位、阅读器不打开、无异常。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlanOnDemandReadUiTest {

    /** 记录 getPlanDocument 调用、可注入返回值的 fake AiCore（照抄既有 LifecycleMockAiCore 的委托模式）。 */
    private class RecordingPlanDocCore(
        var planDocResult: Result<String> = Result.success("# Plan\n\nfull markdown body"),
    ) : AiCore by MockAiCore() {
        val planDocCalls = CopyOnWriteArrayList<Triple<String, String, Int?>>()

        override suspend fun getPlanDocument(
            conversationId: String,
            planId: String,
            subtaskIndex: Int?
        ): Result<String> {
            planDocCalls.add(Triple(conversationId, planId, subtaskIndex))
            return planDocResult
        }
    }

    private fun testVm(core: RecordingPlanDocCore, block: TestScope.(WorkspaceViewModel, RecordingPlanDocCore) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val app = AppState(aiCore = core, preferences = InMemoryPreferencesStore(), scope = backgroundScope)
        val store = ViewModelStore()
        val vm = WorkspaceViewModel(app)
        store.put("vm", vm)
        try {
            runCurrent()
            vm.attach("conv_1")
            runCurrent()
            // 会话快照使 conversationState 非空：activeDockPanel 读取 conversationState.activeDockPanel
            applySnapshot(
                vm,
                ConversationState(
                    conversation = Conversation(
                        id = "conv_1",
                        projectId = "p1",
                        title = "Plan On Demand Test",
                        status = ConversationStatus.Idle,
                        createdAt = 1000L,
                        updatedAt = 2000L
                    ),
                    messages = emptyList(),
                    tokenUsage = TokenUsage(),
                    cost = CostSummary()
                )
            )
            runCurrent()
            block(vm, core)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    private fun applySnapshot(vm: WorkspaceViewModel, snap: ConversationState) {
        val method = vm::class.java.getDeclaredMethod(
            "applySnapshot",
            String::class.java,
            ConversationState::class.java
        ).apply { isAccessible = true }
        method.invoke(vm, "conv_1", snap)
    }

    private fun snapWithPlan(plan: PlanApprovalRequest): ConversationState = ConversationState(
        conversation = Conversation(
            id = "conv_1",
            projectId = "p1",
            title = "Plan On Demand Test",
            status = ConversationStatus.Idle,
            createdAt = 1000L,
            updatedAt = 2000L
        ),
        messages = emptyList(),
        tokenUsage = TokenUsage(),
        cost = CostSummary(),
        planApprovals = listOf(plan)
    )

    @Test
    fun planOnDemandReadCallsContractAndOpensReaderWithContent() = testVm(RecordingPlanDocCore()) { vm, core ->
        val item = PlanOverviewItem(
            id = "plan_a",
            title = "Migration Plan",
            summary = "migration summary",
            planPath = "/tmp/plans/plan_a/plan.md",
            status = PlanOverviewStatus.InProgress,
            subtasks = emptyList()
        )
        vm.openPlanFile(item)
        runCurrent()

        val calls = core.planDocCalls.toList()
        assertEquals(1, calls.size, "openPlanFile 必须只发起一次按需读盘")
        assertEquals(Triple("conv_1", "plan_a", null), calls.single(), "getPlanDocument 参数应为 (conversationId, planId, subtaskIndex=null)")
        assertEquals(RightDockPanel.ARTIFACTS, vm.activeDockPanel, "读盘成功后应打开右侧阅读器")
        val active = vm.readerPanelState.activeItem
        assertIs<ArtifactItem.Text>(active, "阅读器应展示契约返回的文本正文")
        assertEquals("# Plan\n\nfull markdown body", active.content, "阅读器正文必须等于 getPlanDocument 返回内容，而非标题摘要占位")
        assertEquals("计划: Migration Plan", active.title)
        assertEquals("# Plan\n\nfull markdown body", vm.currentPlan?.content)
    }

    @Test
    fun planOverviewListMapsHasSpecAndSpecOpensViaOnDemandRead() = testVm(
        RecordingPlanDocCore(planDocResult = Result.success("spec body"))
    ) { vm, core ->
        val plan = PlanApprovalRequest(
            id = "plan_a",
            conversationId = "conv_1",
            planPath = "/tmp/plans/plan_a/plan.md",
            title = "Migration Plan",
            summary = "migration summary",
            status = "IN_PROGRESS",
            subtasks = listOf(
                PlanSubtaskItem(index = 0, name = "分析依赖", status = "PENDING", hasSpec = true),
                PlanSubtaskItem(index = 1, name = "迁移代码", status = "PENDING", hasSpec = false),
                PlanSubtaskItem(index = 2, name = "验证构建", status = "PENDING", hasSpec = true)
            )
        )
        applySnapshot(vm, snapWithPlan(plan))
        runCurrent()

        // a) planOverviewList 对 hasSpec 的映射正确性（按钮显隐本体 `if (step.hasSpec)` 为 Composable 编译级保证）
        val overview = vm.planOverviewList.single()
        assertEquals(listOf(true, false, true), overview.subtasks.map { it.hasSpec }, "概览子任务必须原样映射契约 hasSpec")

        // b) 打开 Spec 走 getPlanDocument(subtaskIndex=N) 且阅读器打开拼装正文
        vm.openPlanDocument(planId = "plan_a", title = "Migration Plan", subtaskIndex = 1, subtaskName = "迁移代码")
        runCurrent()

        assertEquals(Triple("conv_1", "plan_a", 1), core.planDocCalls.last(), "Spec 打开应携带 subtaskIndex 调 getPlanDocument")
        assertEquals(RightDockPanel.ARTIFACTS, vm.activeDockPanel)
        val active = vm.readerPanelState.activeItem
        assertIs<ArtifactItem.Text>(active)
        assertTrue(active.title.contains("步骤 2 Spec: 迁移代码"), "保留「步骤 N Spec」拼装格式: ${active.title}")
        assertTrue(active.content.contains("> 所属计划：Migration Plan (`plan_a`)"), "拼装正文应含所属计划头")
        assertTrue(active.content.contains("spec body"), "拼装正文应含契约返回的 Spec 原文")
    }

    @Test
    fun planReadFailureShowsTransientNoticeWithoutOpeningReaderOrCrashing() = testVm(
        RecordingPlanDocCore(planDocResult = Result.failure(IOException("disk gone")))
    ) { vm, core ->
        vm.openPlanDocument(planId = "plan_a", title = "Migration Plan")
        runCurrent()

        // 失败：一次性轻提示置位（值为计划标题），阅读器不打开、无异常抛出（测试跑完即证明未 crash）
        assertEquals("Migration Plan", vm.planReadErrorNotice, "失败后轻提示应置位为计划标题")
        assertEquals(listOf(Triple("conv_1", "plan_a", null)), core.planDocCalls.toList())
        assertTrue(vm.artifactItems.isEmpty(), "失败不得用降级内容打开阅读器")
        assertNull(vm.activeArtifactId)
        assertNull(vm.currentPlan, "失败不得写入 currentPlan 伪装成功")

        // 自动清除模式：5 秒后轻提示自动消失
        testScheduler.advanceTimeBy(5001)
        runCurrent()
        assertNull(vm.planReadErrorNotice, "轻提示应在 5 秒后自动清除")
    }
}
