package xyz.mederi.koog

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import xyz.mederi.tools.estimateTokens
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CompressionPlanner 纯函数测试：覆盖切批 / 保留段预算 / 硬上限截断 /
 * 单条超批预算单独成批 / system 排除 / 多批小结合并 / 空输入。
 *
 * 文本用 `"a".repeat(n)` 生成，ASCII → 估计约 n/4 token，便于精确设计预算边界。
 */
class CompressionPlannerTest {

    // ── 构造 helper：ASCII 文本长度 n → 估计约 n/4 token ────────────────────
    private fun user(n: Int) = Message.User("a".repeat(n), RequestMetaInfo.Empty)
    private fun assistant(n: Int) = Message.Assistant("a".repeat(n), ResponseMetaInfo.Empty)

    // ── 切批：10 条大消息按批预算（窗口一半）切成多批 ──────────────────────
    @Test
    fun testSplitsOlderIntoBatches() {
        val window = 100_000
        val batchBudget = (window * COMPRESS_BATCH_BUDGET_RATIO).toInt() // 50_000
        // 每条 40_000 字符 ≈ 10_000 token
        val messages = (0 until 10).map { user(40_000) }

        val plan = planCompression(messages, window)

        assertTrue(plan.olderBatches.size >= 2, "10 条大消息应被切成多批，实际 ${plan.olderBatches.size}")
        plan.olderBatches.forEach { batch ->
            assertTrue(batch.isNotEmpty(), "批不得为空")
            assertTrue(
                estimateTokens(batch) <= batchBudget,
                "每批估计应 ≤ batchBudget=$batchBudget，实际 ${estimateTokens(batch)}"
            )
        }
    }

    // ── 保留段按预算：recent 估计之和 ≤ recentBudget，且为原列表尾部连续子序列 ──
    @Test
    fun testRecentKeptWithinBudgetAsTail() {
        val window = 100_000
        val recentBudget = (window * RECENT_KEEP_BUDGET_RATIO).toInt() // 30_000
        // 每条 8_000 字符 ≈ 2_000 token
        val messages = (0 until 20).map { user(8_000) }

        val plan = planCompression(messages, window)

        assertTrue(plan.recentMessages.isNotEmpty(), "至少保留 1 条")
        assertTrue(
            estimateTokens(plan.recentMessages) <= recentBudget,
            "保留段估计应 ≤ recentBudget=$recentBudget，实际 ${estimateTokens(plan.recentMessages)}"
        )
        // 尾部连续子序列：与取末尾 size 条完全相同
        assertEquals(
            messages.takeLast(plan.recentMessages.size),
            plan.recentMessages,
            "recentMessages 应是原列表的尾部连续子序列"
        )
    }

    // ── 硬上限截断：单条 > hardCap 的最旧消息被丢弃，且不出现在任何 batch ──
    @Test
    fun testDropsMessageOverHardCap() {
        val window = 100_000
        val hardCap = (window * COMPRESS_HARD_CAP_RATIO).toInt() // 90_000
        val normals = (0 until 40).map { user(4_000) } // 每条 ≈ 1_000 token
        val huge = user(400_000) // ≈ 100_000 token > hardCap
        // 超大消息放在靠前位置（会落入 older），后面 40 条正常消息把 recent 撑满
        val messages = buildList {
            add(normals[0])
            add(huge)
            addAll(normals.drop(1))
        }

        val plan = planCompression(messages, window)

        assertEquals(1, plan.droppedCount, "恰好 1 条超硬上限消息被丢弃")
        assertTrue(
            plan.olderBatches.flatten().none { it === huge },
            "超过 hardCap=$hardCap 的最旧消息不得出现在任何 batch 中"
        )
        assertTrue(plan.olderBatches.isNotEmpty(), "其余正常旧消息仍应成批")
    }

    // ── 单条 > batchBudget 但 < hardCap：不丢弃，单独成一批 ────────────────
    @Test
    fun testOversizedButBelowHardCapBecomesOwnBatch() {
        val window = 100_000
        val batchBudget = (window * COMPRESS_BATCH_BUDGET_RATIO).toInt() // 50_000
        val hardCap = (window * COMPRESS_HARD_CAP_RATIO).toInt()         // 90_000
        val mid = user(240_000) // ≈ 60_000 token：> batchBudget 且 < hardCap
        val smalls = (0 until 40).map { user(4_000) }
        val messages = buildList {
            add(mid)
            addAll(smalls)
        }

        val plan = planCompression(messages, window)

        assertEquals(0, plan.droppedCount, "60_000 落在 ($batchBudget, $hardCap]，不得丢弃")
        val midBatch = plan.olderBatches.firstOrNull { batch -> batch.any { it === mid } }
        assertTrue(midBatch != null, "超批预算但未超硬上限的消息应出现在某个 batch 中")
        assertEquals(1, midBatch.size, "它无法与别的消息同批，应单独成批")
    }

    // ── system 排除：system 不出现在 recentMessages，也不出现在任何 batch ──
    @Test
    fun testSystemExcluded() {
        val messages = listOf(
            user(4_000),
            Message.System("system prompt", RequestMetaInfo.Empty),
            assistant(4_000),
            user(4_000)
        )

        val plan = planCompression(messages, 100_000)

        assertTrue(
            plan.recentMessages.none { it is Message.System },
            "system 不应进入 recentMessages"
        )
        assertTrue(
            plan.olderBatches.flatten().none { it is Message.System },
            "system 不应进入任何 batch"
        )
    }

    // ── combineBatchSummaries：单条 ────────────────────────────────────────
    @Test
    fun testCombineSingleSummary() {
        assertTrue(
            combineBatchSummaries(listOf("TLDR: hello")).startsWith("TLDR:"),
            "已带前缀的单条小结应保持以 TLDR: 开头"
        )
        assertTrue(
            combineBatchSummaries(listOf("no prefix")).startsWith("TLDR:"),
            "未带前缀的单条小结应补上 TLDR: 首行"
        )
    }

    // ── combineBatchSummaries：多条（用 --- 分隔，正文内 TLDR: 前缀被剥掉） ──
    @Test
    fun testCombineMultipleSummaries() {
        val combined = combineBatchSummaries(listOf("TLDR: A", "TLDR: B"))

        assertTrue(combined.startsWith("TLDR:"), "合并结果首行必须是 TLDR:")
        assertTrue(combined.contains("---"), "各批小结应用 --- 分隔")
        // 结果里 TLDR: 只能出现一次（仅首行），正文内的前缀已被剥掉
        val occurrences = Regex(Regex.escape("TLDR:")).findAll(combined).count()
        assertEquals(1, occurrences, "结果中 TLDR: 应只出现一次，实际 $occurrences")
    }

    // ── 空输入 ─────────────────────────────────────────────────────────────
    @Test
    fun testEmptyInput() {
        val plan = planCompression(emptyList(), 100_000)

        assertTrue(plan.recentMessages.isEmpty(), "空输入 recent 应为空")
        assertTrue(plan.olderBatches.isEmpty(), "空输入 batches 应为空")
        assertEquals(0, plan.droppedCount, "空输入 dropped 应为 0")
        assertEquals("", combineBatchSummaries(emptyList()), "空小结列表应返回空串")
    }
}
