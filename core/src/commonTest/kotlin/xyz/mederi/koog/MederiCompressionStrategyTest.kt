package xyz.mederi.infrastructure.koog

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.mederi.koog.COMPRESS_BATCH_BUDGET_RATIO
import xyz.mederi.koog.RECENT_KEEP_BUDGET_RATIO
import xyz.mederi.tools.estimateTokens

/**
 * MederiCompressionStrategy 接线测试：验证 contextWindow 从构造函数正确流入规划器
 * （构造 → planFor → planCompression），并保证 null 兜底路径不崩。
 *
 * 文本用 `"a".repeat(n)` 生成，ASCII → 估计约 n/4 token，便于精确设计预算边界。
 */
class MederiCompressionStrategyTest {

    // ── 构造 helper：ASCII 文本长度 n → 估计约 n/4 token ────────────────────
    private fun user(n: Int) = Message.User("a".repeat(n), RequestMetaInfo.Empty)

    // ── contextWindow 正确流入规划：大消息按批预算切成多批，且每批不超批预算 ──
    @Test
    fun testContextWindowFlowsIntoPlanning() {
        val window = 100_000
        val strategy = MederiCompressionStrategy(contextWindow = window)
        // 每条 40_000 字符 ≈ 10_000 token
        val messages = (0 until 20).map { user(40_000) }

        val plan = strategy.planFor(messages)

        assertTrue(
            plan.olderBatches.size >= 2,
            "20 条大消息应按批预算切成多批，实际 ${plan.olderBatches.size}"
        )
        val batchBudget = (window * COMPRESS_BATCH_BUDGET_RATIO).toInt() // 50_000
        plan.olderBatches.forEach { batch ->
            assertTrue(
                estimateTokens(batch) <= batchBudget,
                "每批估计应 ≤ batchBudget=$batchBudget，实际 ${estimateTokens(batch)}"
            )
        }
    }

    // ── null 窗口兜底：不抛异常，无超限消息时 droppedCount 为 0 ──────────────
    @Test
    fun testNullContextWindowDoesNotCrash() {
        val strategy = MederiCompressionStrategy(contextWindow = null)
        val messages = (0 until 5).map { user(400) }

        val plan = strategy.planFor(messages)

        assertEquals(0, plan.droppedCount, "无超硬上限消息时不应丢弃任何消息")
    }

    // ── 默认构造（keepLastMessages = null）：自动压缩模式，recent 仍受 token 预算约束 ──
    @Test
    fun testDefaultKeepLastMessagesUsesTokenBudget() {
        val window = 100_000
        // 不传 keepLastMessages = 自动压缩模式（默认行为）
        val strategy = MederiCompressionStrategy(contextWindow = window)
        // 每条 40_000 字符 = 10_000 token，20 条共 200_000 token，远超窗口
        val messages = (0 until 20).map { user(40_000) }

        val plan = strategy.planFor(messages)

        val recentBudget = (window * RECENT_KEEP_BUDGET_RATIO).toInt() // 30_000
        assertTrue(plan.recentMessages.isNotEmpty(), "自动模式至少保留最后一条原文")
        assertTrue(
            estimateTokens(plan.recentMessages) <= recentBudget,
            "自动模式 recent 估计应 ≤ recentBudget=$recentBudget，实际 ${estimateTokens(plan.recentMessages)}"
        )
        assertTrue(plan.olderBatches.isNotEmpty(), "超预算时应存在待压缩的旧消息批")
    }

    // ── keepLastMessages = 3：手动压缩模式，recent 恰为最后 3 条非 system 原文 ──
    @Test
    fun testKeepLastMessagesSlicesByCount() {
        val strategy = MederiCompressionStrategy(contextWindow = 100_000, keepLastMessages = 3)
        val nonSystem = (0 until 6).map { user(40_000) }
        val messages = listOf(Message.System("system prompt", RequestMetaInfo.Empty)) + nonSystem

        val plan = strategy.planFor(messages)

        // 手动模式按条数切分，忽略 token 保留预算；system 由调用方单独保留，不参与切分
        assertEquals(nonSystem.takeLast(3), plan.recentMessages, "recent 应恰为最后 3 条非 system 原文")
        assertEquals(
            nonSystem.dropLast(3),
            plan.olderBatches.flatten(),
            "其余消息应全部进 olderBatches 走压缩"
        )
    }
}
