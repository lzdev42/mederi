package xyz.mederi.koog

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.mederi.infrastructure.koog.HistoryStoreChatHistoryProvider
import xyz.mederi.infrastructure.koog.MederiCompressionStrategy

class CompactionInterruptionTest {

    private fun user(n: Int) = Message.User("a".repeat(n), RequestMetaInfo.Empty)

    @Test
    fun verifyCompactionSkipsWhenFewMessagesEvenIfOverBudget() {
        // 假设 contextWindow = 100_000，阈值 70% = 70_000
        val window = 100_000
        // 3 条大消息，每条 30_000 token（共 90_000 token，超 70% 预算）
        val messages = listOf(
            user(120_000), // ~30k tokens
            user(120_000), // ~30k tokens
            user(120_000)  // ~30k tokens
        )

        // 1. 在手动 / compressOnce 模式下（keepLastMessages = 3）
        val manualPlan = planCompression(messages, window, keepLastMessages = MANUAL_KEEP_LAST_MESSAGES)
        println("[LOG-VERIFY] manualPlan: recent=${manualPlan.recentMessages.size}, olderBatches=${manualPlan.olderBatches.size}")
        
        // 验证：因为 keepLastMessages=3 把所有 3 条消息都当成 recent 保留，olderBatches 为空！
        assertEquals(0, manualPlan.olderBatches.size, "3 条大消息在 keepLastMessages=3 下 olderBatches 为空")
        
        // 2. 在自动压缩模式下（keepLastMessages = null）
        val autoPlan = planCompression(messages, window, keepLastMessages = null)
        println("[LOG-VERIFY] autoPlan: recent=${autoPlan.recentMessages.size}, olderBatches=${autoPlan.olderBatches.size}")
        val olderCount = autoPlan.olderBatches.sumOf { it.size }
        println("[LOG-VERIFY] autoPlan olderCount=$olderCount")
    }

    @Test
    fun verifyFewLargeMessagesEarlyReturnLoop() {
        val window = 100_000
        // 2 条大消息，每条 40_000 token（共 80_000 token，超 70% 阈值）
        val messages = listOf(
            user(160_000), // ~40k tokens
            user(160_000)  // ~40k tokens
        )

        val autoPlan = planCompression(messages, window, keepLastMessages = null)
        val olderCount = autoPlan.olderBatches.sumOf { it.size }
        println("[LOG-VERIFY] 2 large messages: olderCount=$olderCount, recentCount=${autoPlan.recentMessages.size}")
        
        // 验证：olderCount 恰好是 1（< 2），导致 MederiCompressionStrategy.compress 始终早期 return！
        assertEquals(1, olderCount)
        assertTrue(olderCount < 2, "因为 olderCount < 2，压缩策略直接 return，完全无法压缩！")
        println("[LOG-VERIFY] Result: MederiCompressionStrategy early-returns, nothing is compressed, tokens remain > 70%!")
        println("[LOG-VERIFY] This causes compression to trigger on EVERY tool step, fail to compress, and conversation continues!")
    }

    @Test
    fun verifyPreflightFailureBehavior() {
        // 验证现状：preflightCompressionIfNeeded 抛异常被 fold 捕获，
        // 只返回字符串，runTurn 把字符串放入 streamWarning 后继续往下走，
        // 根本没有暂停或阻断 AI 发起下一个 agent.run()！
        val failureMessage = "自动压缩失败：Connection timed out。上下文可能过长，请尝试手动压缩或切换更大窗口的模型。"
        println("[LOG-VERIFY] Currently, when preflight fails, warning is: $failureMessage")
        println("[LOG-VERIFY] But runTurn ignores it and continues directly to buildTurnAgent and agent.run()!")
    }
}

