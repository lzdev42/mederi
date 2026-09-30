package xyz.mederi.infrastructure.koog

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.mederi.koog.COMPRESS_BATCH_BUDGET_RATIO
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
}
