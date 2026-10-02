package xyz.mederi.koog

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import xyz.mederi.tools.estimateTokens
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CompressionPlanner 纯函数测试：覆盖切批 / 保留段预算 / 硬上限截断 /
 * 单条超批预算单独成批 / system 排除 / 多批小结合并 / 空输入 /
 * keepLast 按条数切分（手动压缩模式） / 手动压缩预检。
 *
 * 文本用 `"a".repeat(n)` 生成，ASCII → 估计约 n/4 token，便于精确设计预算边界。
 */
class CompressionPlannerTest {

    // ── 构造 helper：ASCII 文本长度 n → 估计约 n/4 token ────────────────────
    private fun user(n: Int) = Message.User("a".repeat(n), RequestMetaInfo.Empty)
    private fun assistant(n: Int) = Message.Assistant("a".repeat(n), ResponseMetaInfo.Empty)

    /** 含 Tool.Result part 的 User 消息（Koog 里 tool result 是 User 消息里的 part，不是独立消息类型）。 */
    private fun userWithToolResult(id: String, tool: String = "read_file") =
        Message.User(listOf(MessagePart.Tool.Result(id = id, tool = tool, output = "ok")), RequestMetaInfo.Empty)

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

    // ── compactionSkipReason：空转预检（与 MederiCompressionStrategy 的两条早退判定同源） ──

    @Test
    fun testCompactionSkipReasonLowTokens() {
        // 大窗口：保留预算 = 1_000_000 × 0.3 = 300_000 token，几条小消息（每条 ≈ 1_000 token）
        // 全部落在保留段里，没有任何可压缩的旧消息 → low_tokens
        val messages = (0 until 5).map { user(4_000) }
        val plan = planCompression(messages, 1_000_000)
        assertTrue(plan.olderBatches.isEmpty(), "前置条件：5 条小消息在大窗口下应全在保留段")

        assertEquals("low_tokens", compactionSkipReason(messages, 1_000_000))
    }

    @Test
    fun testCompactionSkipReasonTooFew() {
        // 窗口 100_000 → 保留预算 30_000；1 条 40_000 字符（≈ 10_000 token）+ 30 条 4_000 字符（≈ 1_000 token）
        // 保留段刚好吃掉后面 30 条（30 × 1_000 = 30_000），older 只剩最早那 1 条 → 不值得压一次 LLM
        val messages = buildList {
            add(user(40_000))
            repeat(30) { add(user(4_000)) }
        }
        val plan = planCompression(messages, 100_000)
        assertEquals(1, plan.olderBatches.sumOf { it.size }, "前置条件：older 应恰好只剩 1 条")

        assertEquals("too_few", compactionSkipReason(messages, 100_000))
    }

    @Test
    fun testCompactionSkipReasonNullWhenEnoughOlderMessages() {
        // 窗口 100_000 → 保留预算 30_000；10 条 20_000 字符（≈ 5_000 token）共 50_000 token，
        // 保留段吃掉最近 6 条，older 剩 4 条 ≥ 2 → 值得压，预检放行
        val messages = (0 until 10).map { user(20_000) }
        val plan = planCompression(messages, 100_000)
        assertTrue(plan.olderBatches.sumOf { it.size } >= 2, "前置条件：older 应 ≥ 2 条")

        assertNull(compactionSkipReason(messages, 100_000), "旧消息足量时不应判定为空转")
    }

    // ── keepLast 按条数切分（手动压缩模式）：recent 恒为尾部最后 K 条，其余全压 ──

    @Test
    fun testKeepLastModeKeepsExactTailCount() {
        // 每条长度不同（1_000..1_009 字符），保证按内容而非引用也能区分 take/takeLast
        val messages = (0 until 10).map { user(1_000 + it) }

        val plan = planCompression(messages, 100_000, keepLastMessages = 3)

        assertEquals(messages.takeLast(3), plan.recentMessages, "recent 应恰为原列表尾部最后 3 条（连续子序列）")
        assertEquals(messages.take(7), plan.olderBatches.flatten(), "older 应为前 7 条")
        assertEquals(0, plan.droppedCount)
    }

    @Test
    fun testKeepLastNotLessThanMessageCountKeepsEverything() {
        // keepLast ≥ 条数：coerceAtLeast(0) 后 start=0，recent = 全部，没有可压缩的旧消息
        val messages = (0 until 2).map { user(4_000) }

        val plan = planCompression(messages, 100_000, keepLastMessages = MANUAL_KEEP_LAST_MESSAGES)

        assertEquals(messages, plan.recentMessages, "keepLast 大于条数时 recent 应是全部消息")
        assertTrue(plan.olderBatches.isEmpty(), "没有 older 可压缩")
        assertEquals(0, plan.droppedCount)
    }

    @Test
    fun testKeepLastModeRespectsToolPairing() {
        // 4 条消息，keepLast=3 → 朴素切点 start=1；下标 1 那条恰带孤立 Tool.Result part，
        // 即"recent 首条是 tool result"的典型坏切法，守卫应把 start 再退 1。
        val messages = listOf(
            user(4_000),
            userWithToolResult("call-1"),
            assistant(4_000),
            user(4_000)
        )

        val plan = planCompression(messages, 100_000, keepLastMessages = 3)

        assertEquals(4, plan.recentMessages.size, "recent 首条是 tool result 时 start 应再退 1")
        assertEquals(messages, plan.recentMessages, "退 1 条后 recent = 全部 4 条")
        assertTrue(
            plan.recentMessages.first().parts.none { it is MessagePart.Tool.Result },
            "recent 首条不得是孤立的 Tool.Result"
        )
        assertTrue(plan.olderBatches.isEmpty(), "全部退进 recent 后没有可压缩的旧消息")
    }

    @Test
    fun testKeepLastModeSplitsOlderIntoBatches() {
        val window = 100_000
        val batchBudget = (window * COMPRESS_BATCH_BUDGET_RATIO).toInt() // 50_000
        // 每条 40_000 字符 ≈ 10_000 token
        val messages = (0 until 10).map { user(40_000) }

        val plan = planCompression(messages, window, keepLastMessages = 3)

        assertEquals(messages.takeLast(3), plan.recentMessages, "recent = 最后 3 条原文")
        assertEquals(7, plan.olderBatches.sumOf { it.size }, "older = 前 7 条")
        assertTrue(plan.olderBatches.size >= 2, "7 条大消息应被切成多批，实际 ${plan.olderBatches.size}")
        plan.olderBatches.forEach { batch ->
            assertTrue(batch.isNotEmpty(), "批不得为空")
            assertTrue(
                estimateTokens(batch) <= batchBudget,
                "每批估计应 ≤ batchBudget=$batchBudget，实际 ${estimateTokens(batch)}"
            )
        }
    }

    @Test
    fun testKeepLastModeDropsMessageOverHardCap() {
        val window = 100_000
        val hardCap = (window * COMPRESS_HARD_CAP_RATIO).toInt() // 90_000
        val huge = user(400_000) // ≈ 100_000 token > hardCap
        val normals = (0 until 10).map { user(4_000) } // 每条 ≈ 1_000 token
        // 超大消息放在最旧位置（keepLast=3 时必然落进 older）
        val messages = buildList {
            add(huge)
            addAll(normals)
        }

        val plan = planCompression(messages, window, keepLastMessages = 3)

        assertEquals(1, plan.droppedCount, "恰好 1 条超硬上限消息被丢弃")
        assertTrue(
            plan.olderBatches.flatten().none { it === huge },
            "超过 hardCap=$hardCap 的最旧消息不得出现在任何 batch 中"
        )
        assertTrue(
            plan.recentMessages.none { it === huge },
            "超过 hardCap 的最旧消息也不得出现在 recent 中"
        )
        assertEquals(normals.takeLast(3), plan.recentMessages, "recent 仍是尾部最后 3 条")
        // 共 11 条：recent 3 条 + older 8 条（huge + 7 正常），huge 被丢弃 → batch 里剩 7 条
        assertEquals(7, plan.olderBatches.sumOf { it.size }, "其余 7 条正常旧消息仍应成批")
    }

    // ── manualCompactionSkipReason：手动压缩预检（16k 绝对门槛 + 条数守卫） ──

    @Test
    fun testManualCompactionSkipReasonLowTokensBelowThreshold() {
        // 3 条 × 10_000 字符 ≈ 7_500 token < MIN_COMPRESSIBLE_TOKENS(16_000) → 绝对门槛先拦下
        val messages = (0 until 3).map { user(10_000) }
        assertTrue(
            estimateTokens(messages) < MIN_COMPRESSIBLE_TOKENS,
            "前置条件：总量应低于绝对门槛，实际 ${estimateTokens(messages)}"
        )

        assertEquals("low_tokens", manualCompactionSkipReason(messages, 100_000))
    }

    @Test
    fun testManualCompactionSkipReasonNullWhenEnoughOlderMessages() {
        // 7 条 × 20_000 字符 ≈ 35_000 token ≥ 16_000；keepLast=3 → older 4 条 ≥ 2 → 值得压
        val messages = (0 until 7).map { user(20_000) }
        assertTrue(
            estimateTokens(messages) >= MIN_COMPRESSIBLE_TOKENS,
            "前置条件：总量应高于绝对门槛，实际 ${estimateTokens(messages)}"
        )
        val plan = planCompression(messages, 100_000, keepLastMessages = MANUAL_KEEP_LAST_MESSAGES)
        assertEquals(4, plan.olderBatches.sumOf { it.size }, "前置条件：older 应为 4 条")

        assertNull(manualCompactionSkipReason(messages, 100_000), "旧消息足量时不应判定为空转")
    }

    @Test
    fun testManualCompactionSkipReasonTooFew() {
        // 4 条 × 20_000 字符 ≈ 20_000 token ≥ 16_000；但 keepLast=3 → older 只剩 1 条
        // → 压完可能反而变大，不值得一次 LLM
        val messages = (0 until 4).map { user(20_000) }
        assertTrue(
            estimateTokens(messages) >= MIN_COMPRESSIBLE_TOKENS,
            "前置条件：总量应高于绝对门槛，实际 ${estimateTokens(messages)}"
        )
        val plan = planCompression(messages, 100_000, keepLastMessages = MANUAL_KEEP_LAST_MESSAGES)
        assertEquals(1, plan.olderBatches.sumOf { it.size }, "前置条件：older 应恰好只剩 1 条")

        assertEquals("too_few", manualCompactionSkipReason(messages, 100_000))
    }

    @Test
    fun testManualCompactionSkipReasonEmptyInput() {
        assertEquals("low_tokens", manualCompactionSkipReason(emptyList(), 100_000), "空输入应判为 low_tokens")
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
