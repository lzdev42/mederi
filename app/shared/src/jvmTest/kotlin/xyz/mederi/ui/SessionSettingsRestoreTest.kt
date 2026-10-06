package xyz.mederi.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import xyz.mederi.core.contract.dto.UpdateConversationSettingsInput
import xyz.mederi.core.contract.preferences.InMemoryPreferencesStore
import xyz.mederi.core.mock.MockAiCore
import xyz.mederi.ui.appstate.AppState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 锁住简化后的会话设置恢复语义（jvmTest，装配方式照搬 SharedLogicDesktopTest）：
 *
 * 会话行带四列（modelId/modelProvider/agent/thinkingLevel）→ attach 恢复该四列；
 * 空会话（modelId == null）→ 保留当前选择器值，绝不覆盖用户当前选择；
 * 切走再切回 → 恢复会话自身设置；
 * 冷启动（无选中模型）→ 默认取最近修改的已发送会话的模型。
 *
 * 注：app:shared jvmTest 依赖 kotlin-test（JUnit Platform），类路径无 org.junit(JUnit4)，
 * 故这里用 kotlin.test 断言（与模板 SharedLogicDesktopTest 及同目录 jvmTest 文件一致）。
 */
class SessionSettingsRestoreTest {

    private class Ctx(
        val mockAiCore: MockAiCore,
        val appState: AppState,
        val viewModel: WorkspaceViewModel,
    )

    /** 装配模板（照搬 SharedLogicDesktopTest testAgentModeSelectionState / testRollbackMessageSlicesAndRestoresText）： */
    private suspend fun newCtx(scope: CoroutineScope): Ctx {
        val mockAiCore = MockAiCore()
        mockAiCore.initialize()
        val prefs = InMemoryPreferencesStore()
        val appState = AppState(
            aiCore = mockAiCore,
            preferences = prefs,
            scope = scope
        )
        appState.hydrate()
        val viewModel = WorkspaceViewModel(appState)
        return Ctx(mockAiCore, appState, viewModel)
    }

    /** 等待 attach 完成：conversationId 同步置位，再等 getSnapshot+applySessionSettings 异步收尾。 */
    private suspend fun awaitAttached(ctx: Ctx, convId: String) {
        withTimeout(5_000) {
            while (ctx.viewModel.conversationId != convId) delay(50)
        }
        delay(150)
    }

    private fun claudeSonnet(ctx: Ctx) = ctx.appState.availableModels.value.first { it.id == "claude-sonnet-4" }

    /** 【Test A】会话行带四列 → attach 恢复模型 / Agent / 推理档位。 */
    @Test
    fun attachRestoresSettingsFromConversationRow() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val ctx = newCtx(testScope)
            val conv = ctx.mockAiCore.createConversation("proj_1", null).getOrThrow()
            ctx.mockAiCore.updateConversationSettings(
                conv.id,
                UpdateConversationSettingsInput(
                    model = claudeSonnet(ctx),
                    agent = ctx.appState.availableAgents.value.first { it.id == "approval" },
                    thinkingLevel = "HIGH",
                )
            )
            ctx.appState.selectConversation(conv.id)
            awaitAttached(ctx, conv.id)

            assertEquals("claude-sonnet-4", ctx.appState.selectedModel.value?.id, "attach 应恢复会话绑定的模型")
            assertEquals("approval", ctx.appState.selectedAgentId.value, "attach 应恢复会话绑定的 Agent")
            // 派生流（combine）异步更新：用 first{} 等 effectiveThinkingLevel 发射到 HIGH 再断言，避免竞态
            withTimeout(5_000) {
                ctx.viewModel.effectiveThinkingLevel.first { it == "HIGH" }
            }
            assertEquals("HIGH", ctx.viewModel.effectiveThinkingLevel.value, "attach 应恢复会话绑定的推理档位")
        } finally {
            testScope.cancel()
        }
    }

    /** 【Test B】空会话（modelId == null）→ 保留当前选择（bug 回归保护：空会话不得覆盖用户当前选择）。 */
    @Test
    fun emptyConversationPreservesCurrentSelection() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val ctx = newCtx(testScope)
            // 先选一个模型进选择器
            ctx.appState.selectModel(ctx.appState.availableModels.value.first { it.id == "gpt-5" })
            assertEquals("gpt-5", ctx.appState.selectedModel.value?.id, "前置：用户已选择 gpt-5")

            // 空会话（modelId == null）
            val conv = ctx.mockAiCore.createConversation("proj_1", null).getOrThrow()
            ctx.appState.selectConversation(conv.id)
            awaitAttached(ctx, conv.id)

            assertEquals(
                "gpt-5",
                ctx.appState.selectedModel.value?.id,
                "空会话不得覆盖用户当前选择（这是本轮修复的 bug）"
            )
        } finally {
            testScope.cancel()
        }
    }

    /** 【Test C】切走切回：A（带设置）→ B（空）→ A，各自恢复。 */
    @Test
    fun switchAwayAndBackRestoresSettings() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val ctx = newCtx(testScope)
            val convA = ctx.mockAiCore.createConversation("proj_1", null).getOrThrow()
            ctx.mockAiCore.updateConversationSettings(
                convA.id,
                UpdateConversationSettingsInput(
                    model = claudeSonnet(ctx),
                    thinkingLevel = "HIGH",
                )
            )
            val convB = ctx.mockAiCore.createConversation("proj_1", null).getOrThrow()

            // 1. 选中 A → 恢复 A 的设置
            ctx.appState.selectConversation(convA.id)
            awaitAttached(ctx, convA.id)
            assertEquals("claude-sonnet-4", ctx.appState.selectedModel.value?.id, "切到 A 应恢复 A 的模型")

            // 2. 切到空会话 B → 保留当前选择（不清空）
            ctx.appState.selectConversation(convB.id)
            awaitAttached(ctx, convB.id)
            assertEquals("claude-sonnet-4", ctx.appState.selectedModel.value?.id, "切到空会话 B 应保留当前选择")

            // 3. 切回 A → 恢复 A 的设置（含推理档位）
            ctx.appState.selectConversation(convA.id)
            awaitAttached(ctx, convA.id)
            assertEquals("claude-sonnet-4", ctx.appState.selectedModel.value?.id, "切回 A 应恢复 A 的模型")
            withTimeout(5_000) {
                ctx.viewModel.effectiveThinkingLevel.first { it == "HIGH" }
            }
            assertEquals("HIGH", ctx.viewModel.effectiveThinkingLevel.value, "切回 A 应恢复 A 的推理档位")
        } finally {
            testScope.cancel()
        }
    }

    /**
     * 【Test D】冷启动 → 最近会话默认（best-effort，方案 1）：
     * 全新 AppState+ViewModel（_selectedModel 初始为 null），等待 init 冷启动兜底
     * 从"最近修改的已发送会话"恢复模型。数据推导 gets `maxByOrNull { updatedAt }`，
     * 若 init 时序 flaky，可降级为只断言数据推导（见 spec 方案 2）。
     */
    @Test
    fun coldStartSelectsRecentConversationModel() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // 制造一个有 modelId 的会话（updatedAt = now，晚于种子会话）
            val mockAiCore = MockAiCore()
            mockAiCore.initialize()
            val conv = mockAiCore.createConversation("proj_1", null).getOrThrow()
            mockAiCore.updateConversationSettings(
                conv.id,
                UpdateConversationSettingsInput(model = mockAiCore.availableModels.value.first { it.id == "claude-sonnet-4" })
            )

            // 全新 AppState + ViewModel（_selectedModel 初始为 null），冷启动兜底应读到最近会话
            val prefs = InMemoryPreferencesStore()
            val appState = AppState(
                aiCore = mockAiCore,
                preferences = prefs,
                scope = testScope
            )
            appState.hydrate()
            val viewModel = WorkspaceViewModel(appState)

            // 模型已就绪（initialize 已填充）；等 init 冷启动兜底异步执行完
            withTimeout(5_000) { appState.availableModels.first { it.isNotEmpty() } }
            withTimeout(5_000) { appState.selectedModel.first { it != null } }

            assertEquals(
                "claude-sonnet-4",
                appState.selectedModel.value?.id,
                "冷启动（无选择）应默认取最近修改的已发送会话的模型"
            )

            // 数据推导复核（方案 2 的断言，兼作冷启动读取源的验证）：
            // 冷启动所读 = projects 中 modelId != null 且 updatedAt 最大的会话
            val recentModelId = appState.projects.value
                .flatMap { it.conversations }
                .filter { it.modelId != null }
                .maxByOrNull { it.updatedAt }
                ?.modelId
            assertEquals("claude-sonnet-4", recentModelId, "冷启动读取的最近会话数据应正确")
            assertEquals(conv.id, appState.projects.value.flatMap { it.conversations }.filter { it.modelId != null }.maxByOrNull { it.updatedAt }?.id)
            assertEquals(true, viewModel.conversationId == null, "冷启动兜底不改变会话选择")
        } finally {
            testScope.cancel()
        }
    }
}