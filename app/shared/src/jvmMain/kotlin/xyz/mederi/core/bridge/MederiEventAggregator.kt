package xyz.mederi.core.bridge

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import xyz.mederi.api.SessionApi
import xyz.mederi.core.contract.SnapshotReducer
import xyz.mederi.core.contract.dto.ConversationSnapshot
import xyz.mederi.core.contract.dto.MessagesPage
import xyz.mederi.core.contract.models.ConversationStatus
import xyz.mederi.core.contract.models.CoreEventType
import xyz.mederi.core.contract.models.PlanApprovalRequest
import xyz.mederi.core.contract.models.TodoItem
import xyz.mederi.debug.DebugLog

/**
 * 把 core 的会话事件流聚合成 UI 需要的 [ConversationSnapshot]。
 *
 * 职责仅剩两件 JVM 特有的事：
 * - 首次订阅时拉取 Session + 历史 Message 构建初始快照（core 领域模型 → contract 模型转换）
 * - 提供 core 落库数据的回查（MESSAGE_COMPLETED / MESSAGE_ERROR 对齐）
 *
 * 事件 → 快照的应用逻辑已抽取到 commonMain 的 [SnapshotReducer]，
 * 与 wasmJs（ServerAiCore）共享同一份状态机。
 */
object MederiEventAggregator {

    /**
     * @param planTodos 会话重开时的 Plan 子任务投影 hydration（MederiAiCore 提供，
     * 内部走 PlanStore 同一投影函数）；优先级：Plan 投影 > session.todos（与提示词挂载规则同构）。
     * @param planApproval 会话重开时的计划审批展示项 hydration（重启/翻历史恢复待批准卡片）。
     * 仅决定"填进快照的数据"，不决定卡片渲染位置（UI 层自决）。
     */
    fun observe(
        conversationId: String,
        sessions: SessionApi,
        modelToProvider: (String) -> String?,
        planTodos: suspend (String) -> List<TodoItem> = { emptyList() },
        planApproval: suspend (String) -> PlanApprovalRequest? = { null },
        planApprovals: suspend (String) -> List<PlanApprovalRequest> = { emptyList() },
        lastError: (String) -> LastSessionError? = { null }
    ): Flow<ConversationSnapshot> = flow {
        DebugLog.section("Aggregator", "MederiEventAggregator.observe start")
        DebugLog.data("Aggregator", "conversationId", conversationId)

        val session = sessions.get(conversationId)
        val messages = sessions.listMessages(conversationId)
        val toolResults = MederiModelMapper.buildToolResultsById(messages)
        DebugLog.data("Aggregator", "initial messages", messages.size)

        val base = ConversationSnapshot(
            conversation = MederiModelMapper.toConversation(session, session.aiModel?.id?.let(modelToProvider)),
            messages = messages.map { MederiModelMapper.toChatMessage(it, toolResults) },
            tokenUsage = MederiModelMapper.toTokenUsage(messages),
            contextUsedTokens = MederiModelMapper.toContextUsedTokens(messages),
            cost = MederiModelMapper.toCostSummary(),
            todos = planTodos(conversationId).ifEmpty { MederiModelMapper.toTodos(session.todos) },
            pendingPlanApproval = planApproval(conversationId),
            planApprovals = planApprovals(conversationId)
        )
        var snapshot = hydrateLastError(base, lastError(conversationId))
        DebugLog.event("Aggregator", "initial snapshot built: status=${snapshot.conversation.status}, messages=${snapshot.messages.size}")
        emit(snapshot)

        /** core 落库数据回查：拉全量消息 + token 统计，转成契约层 [MessagesPage] */
        suspend fun refreshPage(): MessagesPage? {
            val refreshed = runCatching { sessions.listMessages(conversationId) }.getOrNull() ?: return null
            DebugLog.data("Aggregator", "refreshed messages", refreshed.size)
            val refreshedToolResults = MederiModelMapper.buildToolResultsById(refreshed)
            return MessagesPage(
                messages = refreshed.map { MederiModelMapper.toChatMessage(it, refreshedToolResults) },
                tokenUsage = MederiModelMapper.toTokenUsage(refreshed),
                contextUsedTokens = MederiModelMapper.toContextUsedTokens(refreshed)
            )
        }

        var lastBlockCount = -1
        sessions.events(conversationId).collect { coreEvent ->
            val event = MederiModelMapper.toCoreEvent(coreEvent)
            val isDelta = event.type == CoreEventType.MESSAGE_DELTA
            // MESSAGE_DELTA 是流式期间的高频事件：不打逐事件日志（噪音源），
            // 改为在快照里数 block——blocks 数远超消息数 = "一字母一行"的直接证据

            val enriched = if (event.type == CoreEventType.PLAN_APPROVAL_REQUESTED) {
                val planPath = event.payload["planPath"] ?: ""
                val existingContent = event.payload["planContent"]
                val content = if (!existingContent.isNullOrBlank()) {
                    existingContent
                } else {
                    runCatching { java.io.File(planPath).readText() }.getOrNull()
                }
                DebugLog.data(
                    "Aggregator",
                    "PLAN_APPROVAL_REQUESTED enriched",
                    "planId=${event.payload["planId"]}, summary='${event.payload["summary"]}', planPath='$planPath', contentFound=${content != null}, contentLen=${content?.length ?: 0}"
                )
                if (content != null) event.copy(payload = event.payload + ("planContent" to content)) else event
            } else {
                event
            }

            val emissions = SnapshotReducer.applyWithRefresh(snapshot, enriched) { refreshPage() }
            emissions.forEach {
                val blockCount = it.messages.sumOf { m -> m.blocks.size }
                if (isDelta) {
                    // 流式期间：只在 block 计数跳变时打点（正常 = 每轮 +2：1 Reasoning + 1 Text；
                    // 跳变密集 = 块被切碎，直接指认"一字母一行"）
                    if (blockCount != lastBlockCount) {
                        DebugLog.debug(
                            "Aggregator",
                            "delta blocks: $lastBlockCount -> $blockCount (messages=${it.messages.size})"
                        )
                        lastBlockCount = blockCount
                    }
                } else {
                    DebugLog.debug(
                        "Aggregator",
                        "event applied: type=${enriched.type}, status=${it.conversation.status}, messages=${it.messages.size}, blocks=$blockCount"
                    )
                }
                if (it.conversation.status == ConversationStatus.Error) {
                    DebugLog.event(
                        "Aggregator",
                        "session errored: id=$conversationId, isCurrent=false(observe-flow), error='${it.errorMessage}', errorId=${it.errorId}, failureMode=${it.conversation.status}"
                    )
                }
                emit(it)
            }
            snapshot = emissions.last()
        }
    }
}
