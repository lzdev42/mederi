package xyz.mederi.core.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.mederi.core.contract.models.ChatBlock
import xyz.mederi.domain.model.Message
import xyz.mederi.domain.model.MessagePart
import xyz.mederi.domain.model.MessageRole
import xyz.mederi.ui.ChatListItem
import xyz.mederi.ui.computeChatItems

/**
 * [MederiModelMapper.toUsageBackfill] 从历史 assistant 消息回填 requestCount / lastRequestUsage。
 */
class MederiModelMapperTest {

    private fun msg(role: MessageRole, input: Int? = null, output: Int? = null, cached: Int? = null) = Message(
        sessionId = "s",
        role = role,
        parts = listOf(MessagePart.Text("x")),
        createdAt = "2026-01-01T00:00:00Z",
        inputTokens = input,
        outputTokens = output,
        cachedTokens = cached,
    )

    @Test
    fun backfill_countsAllAssistantMessages() {
        val messages = listOf(
            msg(MessageRole.USER),
            msg(MessageRole.ASSISTANT, input = 10, output = 5),
            msg(MessageRole.USER),
            msg(MessageRole.ASSISTANT, input = 20, output = 8, cached = 3),
            msg(MessageRole.ASSISTANT), // 无 usage 的 assistant 仍计入 requestCount
        )
        val backfill = MederiModelMapper.toUsageBackfill(messages)
        assertEquals(3, backfill.requestCount, "requestCount = assistant 消息数（含无 usage 的）")
    }

    @Test
    fun backfill_lastUsageFromLastAssistantWithUsage() {
        val messages = listOf(
            msg(MessageRole.ASSISTANT, input = 100, output = 40),
            msg(MessageRole.ASSISTANT, input = 200, output = 60, cached = 50),
            msg(MessageRole.ASSISTANT), // 第 3 条无 usage
        )
        val backfill = MederiModelMapper.toUsageBackfill(messages)
        // lastRequestUsage 取"最后一条带 usage"的 assistant（第 2 条），inputTokens 对得上
        assertEquals(200L, backfill.lastRequestUsage?.inputTokens)
        assertEquals(60L, backfill.lastRequestUsage?.outputTokens)
        assertEquals(50L, backfill.lastRequestUsage?.cachedTokens)
        assertEquals(3, backfill.requestCount)
    }

    @Test
    fun backfill_noUsageNull() {
        val messages = listOf(
            msg(MessageRole.USER),
            msg(MessageRole.ASSISTANT),
            msg(MessageRole.ASSISTANT),
        )
        val backfill = MederiModelMapper.toUsageBackfill(messages)
        assertEquals(2, backfill.requestCount)
        assertNull(backfill.lastRequestUsage, "全无 usage → lastRequestUsage 为 null（不用 0 伪装）")
    }

    @Test
    fun sessionCacheRateWeightsReportedInputsAndExcludesMissingOrInvalidSamples() {
        val messages = listOf(
            msg(MessageRole.ASSISTANT, input = 1000, cached = 800).copy(cacheWriteTokens = 100),
            msg(MessageRole.ASSISTANT, input = 8000),
            msg(MessageRole.ASSISTANT, input = 100, cached = 200),
            msg(MessageRole.ASSISTANT, input = 100, cached = 0).copy(cacheWriteTokens = 0),
        )
        val usage = MederiModelMapper.toTokenUsage(messages)
        assertEquals(9200L, usage.input)
        assertEquals(800L, usage.cacheRead)
        assertEquals(100L, usage.cacheWrite)
        assertEquals(1100L, usage.cacheHitSample?.inputTokens)
        assertEquals(800L, usage.cacheHitSample?.readTokens)
        assertEquals(72.7, usage.cacheHitSample?.percent)
        val latest = MederiModelMapper.toUsageBackfill(messages).lastRequestUsage
        assertEquals(0.0, latest?.cacheHitPercent)
        assertEquals(0L, latest?.cacheWriteTokens)

        val unreported = MederiModelMapper.toTokenUsage(listOf(messages[1]))
        assertNull(unreported.cacheRead)
        assertNull(unreported.cacheWrite)
        assertNull(unreported.cacheHitSample)
    }

    @Test
    fun cacheUsageGroupsHistoricalRequestsByEndpointModelAndVariant() {
        val request = msg(MessageRole.ASSISTANT, input = 100, cached = 75).copy(
            providerId = "gateway",
            providerEndpoint = "https://one.example/v1",
            modelId = "local-1",
            providerModelId = "upstream",
            reasoningLevel = "HIGH",
            cacheWriteTokens = 10,
        )
        val usage = MederiModelMapper.toTokenUsage(listOf(
            request,
            request.copy(modelId = "local-2", cachedTokens = 0),
            request.copy(providerEndpoint = "https://two.example/v1"),
            request.copy(providerModelId = "another-model"),
            request.copy(reasoningLevel = "LOW"),
            request.copy(cachedTokens = null),
            request.copy(modelRequestCompleted = false, cachedTokens = null),
        ))
        assertEquals(listOf(2, 1, 1, 1), usage.modelCacheUsage.map { it.reportedRequests })
        assertEquals(listOf(3, 1, 1, 1), usage.modelCacheUsage.map { it.totalRequests })
        assertEquals(37.5, usage.modelCacheUsage.first().sample?.percent)
        assertEquals(75.0, usage.modelCacheUsage[1].sample?.percent)
    }

    @Test
    fun compactionResetsLatestRequestButPreservesSessionTotals() {
        val request = msg(MessageRole.ASSISTANT, input = 100, cached = 75)
        val history = listOf(request, msg(MessageRole.SUMMARY))
        assertNull(MederiModelMapper.toUsageBackfill(history).lastRequestUsage)
        assertEquals(75L, MederiModelMapper.toTokenUsage(history).cacheRead)
    }

    @Test
    fun completedRequestWithoutUsageDoesNotDisplayPreviousModelsCacheHit() {
        val previous = msg(MessageRole.ASSISTANT, input = 100, cached = 75)
        val latest = msg(MessageRole.ASSISTANT).copy(
            providerId = "gateway", providerModelId = "new-model", modelRequestCompleted = true,
        )
        val usage = MederiModelMapper.toUsageBackfill(listOf(previous, latest)).lastRequestUsage
        assertEquals("new-model", usage?.cacheModel?.modelId)
        assertNull(usage?.inputTokens)
        assertNull(usage?.cacheHitPercent)
    }

    @Test
    fun estimatePricesEachRecordedModelAndPreservesCoverage() {
        val first = msg(MessageRole.ASSISTANT, input = 1_000_000, output = 1_000_000, cached = 500_000)
            .copy(providerId = "gateway", modelId = "local-a", providerModelId = "remote-a", cacheWriteTokens = 100_000)
        val second = first.copy(modelId = "local-b", providerModelId = "remote-b", cachedTokens = 0, cacheWriteTokens = 0)
        val unpriced = first.copy(modelId = "local-c", providerModelId = "remote-c")
        val projection = MederiModelMapper.toUsageProjection(listOf(first, second, unpriced)) { message ->
            when (message.providerModelId) {
                "remote-a" -> xyz.mederi.metadata.ModelReferencePricing(2.0, 4.0, 1.0, 7.0)
                "remote-b" -> xyz.mederi.metadata.ModelReferencePricing(4.0, 6.0, null, null)
                else -> null
            }
        }
        assertEquals(3_000_000L, projection.tokens.input)
        assertEquals(6.0, projection.tokens.latestByModel.first().estimatedCost)
        assertEquals(10.0, projection.tokens.latestByModel[1].estimatedCost)
        assertNull(projection.tokens.latestByModel[2].estimatedCost)
        assertEquals(16.0, projection.cost.total)
        assertEquals(2, projection.cost.pricedRequests)
        assertEquals(3, projection.cost.totalRequests)
        assertEquals(listOf(6.0, 10.0, null), projection.cost.models.map { it.estimate })
    }

    @Test
    fun projectsSuccessCountsSeparatelyFromTokenCoverageForModelDetails() {
        val response = msg(MessageRole.ASSISTANT).copy(
            id = "success", providerId = "provider", modelId = "model",
            modelRequestCompleted = true, finishReason = "stop",
        )
        val failed = response.copy(id = "failed", finishReason = "error", inputTokens = 12)
        val records = listOf(response, failed).map { message ->
            xyz.mederi.domain.model.ModelRequestRecord(
                requireNotNull(message.id), message.sessionId, message.sessionId, message.createdAt, message,
            )
        }
        val projection = MederiModelMapper.toUsageProjection(emptyList(), records)
        val row = projection.cost.models.single()
        assertEquals(1, projection.requestCount)
        assertEquals(1, row.successfulRequests)
        assertEquals(2, row.totalRequests)
        assertEquals(12L, row.inputTokens)
        assertNull(row.estimate)
    }

    // ── 跨会话累计统计（供应商 + API Key + 上游模型 分组）──

    private fun record(message: Message, owner: String = message.sessionId): xyz.mederi.domain.model.ModelRequestRecord =
        xyz.mederi.domain.model.ModelRequestRecord(
            requestId = requireNotNull(message.id),
            ownerSessionId = owner,
            executionSessionId = message.sessionId,
            startedAt = message.createdAt,
            usage = message,
        )

    /** input 1.0 / output 2.0 / cacheRead 0.1 / cacheWrite 1.5（美元每百万）。 */
    private fun keyPricing(message: Message): xyz.mederi.metadata.ModelReferencePricing? =
        when (message.providerModelId) {
            "remote-a" -> xyz.mederi.metadata.ModelReferencePricing(1.0, 2.0, 0.1, 1.5)
            else -> null
        }

    @Test
    fun cumulativeUsageSplitsSameModelByApiKeyAndKeepsCoverageForUnpricedGroup() {
        val base = msg(MessageRole.ASSISTANT, input = 1000, output = 200, cached = 800).copy(
            providerId = "gateway", providerName = "Gateway", providerModelId = "remote-a", modelName = "Model A",
            modelId = "local-a", cacheWriteTokens = 100, modelRequestCompleted = true, finishReason = "stop",
        )
        // 同供应商同模型、不同 API Key → 必须分成两组（跨会话累计按 Key 计价）
        val keyA = base.copy(id = "req_a", sessionId = "s1", apiKeyId = "key_a")
        val keyB = base.copy(id = "req_b", sessionId = "s2", apiKeyId = "key_b")
        // 另一上游模型，无参考价 → estimate 为 null 但仍计入请求覆盖
        val unpriced = base.copy(id = "req_c", sessionId = "s2", apiKeyId = "key_a", providerModelId = "remote-b")

        val stats = MederiModelMapper.toCumulativeUsageStatistics(
            listOf(record(keyA), record(keyB), record(unpriced)), ::keyPricing,
        )

        assertEquals(3, stats.groups.size, "key 维度必须分开，模型维度也必须分开")
        val first = stats.groups[0]
        assertEquals("gateway", first.providerId)
        assertEquals("key_a", first.apiKeyId)
        assertEquals("remote-a", first.modelId)
        assertEquals("Gateway", first.providerName)
        assertEquals("Model A", first.modelName)
        assertEquals(1000L, first.inputTokens)
        assertEquals(200L, first.outputTokens)
        assertEquals(800L, first.cacheReadTokens)
        assertEquals(100L, first.cacheWriteTokens)
        assertEquals(1, first.successfulRequests)
        assertEquals(1, first.totalRequests)
        assertEquals(1, first.pricedRequests)
        // 手算：(1000-800-100)*1 + 800*0.1 + 100*1.5 + 200*2 = 730 → 730/1e6
        assertEquals(730.0 / 1_000_000, requireNotNull(first.estimate), 1e-12)
        assertEquals("key_b", stats.groups[1].apiKeyId, "分组顺序 = 记录首次出现顺序")
        assertNull(stats.groups[1].apiKeyName, "apiKeyName 由桥层 join 供应商配置，mapper 内保持 null")
        val noPrice = stats.groups[2]
        assertEquals("remote-b", noPrice.modelId)
        assertNull(noPrice.estimate, "无参考价不造 0.0")
        assertEquals(0, noPrice.pricedRequests)
        assertEquals(1, noPrice.totalRequests, "无价模型仍计入请求覆盖")
        assertEquals(2, stats.pricedRequests)
        assertEquals(3, stats.totalRequests)
        assertEquals(2 * 730.0 / 1_000_000, requireNotNull(stats.totalEstimatedCost), 1e-12)
        assertEquals("USD", stats.currency)
    }

    @Test
    fun cumulativeUsageCountsUnfinishedRequestsWithoutInventingTokens() {
        val pending = xyz.mederi.domain.model.ModelRequestRecord(
            requestId = "req_pending",
            ownerSessionId = "s1",
            executionSessionId = "s1",
            startedAt = "2026-01-01T00:00:00Z",
            usage = null,
        )
        val recorded = msg(MessageRole.ASSISTANT, input = 1000, cached = 800).copy(
            id = "req_done", providerId = "gateway", providerModelId = "remote-a",
            modelRequestCompleted = true, finishReason = "stop",
        )
        val stats = MederiModelMapper.toCumulativeUsageStatistics(listOf(pending, record(recorded)), ::keyPricing)

        assertEquals(2, stats.groups.size, "无维度旧记录归独立「未知」桶")
        val unknown = stats.groups[0]
        assertNull(unknown.providerId)
        assertNull(unknown.apiKeyId)
        assertNull(unknown.modelId)
        assertEquals(1, unknown.totalRequests)
        assertEquals(0, unknown.successfulRequests)
        assertNull(unknown.inputTokens, "未完成请求不得产生 0 值 token 累计")
        assertNull(unknown.estimate)
        assertEquals(2, stats.totalRequests)
        assertEquals(1, stats.groups[1].successfulRequests)
    }

    @Test
    fun cumulativeUsageTreatsErrorFinishAsFailedButKeepsReportedTokens() {
        val ok = msg(MessageRole.ASSISTANT, input = 500, output = 100).copy(
            id = "req_ok", providerId = "gateway", providerModelId = "remote-a",
            modelRequestCompleted = true, finishReason = "stop",
        )
        val failed = msg(MessageRole.ASSISTANT, input = 500, output = 100).copy(
            id = "req_failed", providerId = "gateway", providerModelId = "remote-a",
            modelRequestCompleted = true, finishReason = "error",
        )
        val group = MederiModelMapper.toCumulativeUsageStatistics(listOf(record(ok), record(failed)), ::keyPricing)
            .groups.single()

        assertEquals(1, group.successfulRequests, "error finishReason 不计成功")
        assertEquals(2, group.totalRequests)
        assertEquals(1000L, group.inputTokens, "失败请求已上报的 token 仍计入用量")
        assertEquals(200L, group.outputTokens)
    }

    @Test
    fun toChatMessage_preservesCoreDeliveryAndPendingInputIdentity() {
        val message = msg(MessageRole.ASSISTANT).copy(
            id = "response_1", turnId = "turn_1", isFinalAnswer = true, workStartedAt = 1000L, workCompletedAt = 9000L,
        )
        val chat = MederiModelMapper.toChatMessage(message)
        assertEquals("response_1", chat.id)
        assertEquals("turn_1", chat.turnId)
        assertEquals(1000L, chat.workStartedAt)
        assertEquals(9000L, chat.workCompletedAt)
        assertEquals(true, chat.isFinalAnswer)
        val pending = MederiModelMapper.toChatMessage(msg(MessageRole.USER).copy(
            id = "user_1", turnId = "turn_1", pendingSteering = true,
        ))
        assertEquals("user_1", pending.id)
        assertEquals(true, pending.pendingSteering)
    }

    // ── UI 剥离作用域与内部触发消息隐藏（ASSISTANT 不被剥离；hiddenFromUi 不落渲染块）──

    @Test
    fun toChatMessage_assistantTextIsNotStripped() {
        val body = "你看一下 `<user_intervention>example</user_intervention>` 这个标签"
        val message = Message(
            sessionId = "s",
            role = MessageRole.ASSISTANT,
            parts = listOf(MessagePart.Text(body)),
            createdAt = "2026-01-01T00:00:00Z",
        )
        val chat = MederiModelMapper.toChatMessage(message)
        val textBlock = chat.blocks.filterIsInstance<ChatBlock.Text>().single()
        assertEquals(body, textBlock.text, "ASSISTANT 原文不得被 USER 视图净化规则篡改")
    }

    @Test
    fun toChatMessage_hiddenFromUiClearsBlocksAndSeparatesTurnsWithoutUserBubble() {
        val internalTrigger = Message(
            id = "msg_plan_exec",
            sessionId = "s",
            role = MessageRole.USER,
            parts = listOf(MessagePart.Text("Plan 'x' (id=plan_1) has been approved by the user.")),
            createdAt = "2026-01-01T00:01:00Z",
            hiddenFromUi = true,
        )
        val mappedHidden = MederiModelMapper.toChatMessage(internalTrigger)
        assertTrue(mappedHidden.hiddenFromUi)
        assertTrue(mappedHidden.blocks.isEmpty(), "hiddenFromUi=true 投影不得携带可见渲染块")

        val planTurn = MederiModelMapper.toChatMessage(msg(MessageRole.ASSISTANT).copy(id = "a1"))
        val execTurn = MederiModelMapper.toChatMessage(msg(MessageRole.ASSISTANT).copy(id = "a2"))
        val items = computeChatItems(listOf(planTurn, mappedHidden, execTurn), isWorking = false, snapshot = null)
        assertEquals(0, items.count { it is ChatListItem.TextMessage && it.isUser }, "隐藏内部消息不得渲染为用户气泡")
        assertEquals(2, items.count { it is ChatListItem.Footer }, "隐藏触发消息仍应隔离前后两个 Assistant 轮次")
    }
}
