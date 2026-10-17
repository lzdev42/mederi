package xyz.mederi.core.bridge

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.mederi.api.SessionApi
import xyz.mederi.core.contract.ConversationStateReducer
import xyz.mederi.core.contract.SnapshotReducer
import xyz.mederi.core.contract.dto.ConversationSnapshot
import xyz.mederi.core.contract.dto.ConversationState
import xyz.mederi.core.contract.dto.MessagesPage
import xyz.mederi.core.contract.models.ChatBlock
import xyz.mederi.core.contract.models.ChatMessage
import xyz.mederi.core.contract.models.ConversationStatus
import xyz.mederi.core.contract.models.CoreEventType
import xyz.mederi.core.contract.models.PlanApprovalRequest
import xyz.mederi.core.contract.models.QuestionRequest
import xyz.mederi.core.contract.models.ToolCallState
import xyz.mederi.debug.DebugLog
import xyz.mederi.domain.model.Message
import xyz.mederi.domain.model.MessagePart
import xyz.mederi.domain.model.MessageRole
import xyz.mederi.tools.contextUsedTokens
import xyz.mederi.tools.estimateTokens

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
     * @param planApproval 会话重开时的计划审批展示项 hydration（重启/翻历史恢复待批准卡片）。
     * 仅决定"填进快照的数据"，不决定卡片渲染位置（UI 层自决）。
     */
    fun observe(
        conversationId: String,
        sessions: SessionApi,
        modelToProvider: (String) -> String?,
        planApproval: suspend (String) -> PlanApprovalRequest? = { null },
        planApprovals: suspend (String) -> List<PlanApprovalRequest> = { emptyList() },
        lastError: (String) -> LastSessionError? = { null }
    ): Flow<ConversationSnapshot> = flow {
        DebugLog.section("Aggregator", "MederiEventAggregator.observe start")
        DebugLog.data("Aggregator", "conversationId", conversationId)

        val session = sessions.get(conversationId)
        val includeImages = session.aiModel?.supportsImages ?: true
        val messages = sessions.listMessages(conversationId)
        val toolResults = MederiModelMapper.buildToolResultsById(messages)
        DebugLog.data("Aggregator", "initial messages", messages.size)

        val backfill = MederiModelMapper.toUsageBackfill(messages)
        val mappedMessages = messages.map { MederiModelMapper.toChatMessage(it, toolResults) }
        // store 重建快照时 hydrate pendingQuestion：session 为 RUNNING 且有 ask_user Pending → 恢复挂起的提问
        val hydratedQuestion = if (session.status == xyz.mederi.domain.model.SessionStatus.RUNNING) {
            hydratePendingQuestion(mappedMessages, conversationId)
        } else null
        val base = ConversationSnapshot(
            conversation = MederiModelMapper.toConversation(session, session.aiModel?.id?.let(modelToProvider)).let { conv ->
                if (hydratedQuestion != null) conv.copy(status = ConversationStatus.WaitingUser) else conv
            },
            messages = mappedMessages,
            tokenUsage = MederiModelMapper.toTokenUsage(messages),
            contextUsedTokens = sessions.contextUsedTokens(conversationId).toLong(),
            cost = MederiModelMapper.toCostSummary(),
            todos = MederiModelMapper.toTodos(session.todos),
            pendingPlanApproval = planApproval(conversationId),
            planApprovals = planApprovals(conversationId),
            subagents = sessions.listSubagentsForSession(conversationId).map { bg ->
                xyz.mederi.core.contract.models.SubagentState(
                    agentId = bg.agentId,
                    parentSessionId = bg.parentSessionId,
                    role = bg.role.name,
                    modelId = bg.aiModel?.id ?: "",
                    modelName = bg.aiModel?.name ?: "",
                    reasoningLevel = bg.reasoningLevel?.name,
                    task = bg.task,
                    briefing = bg.briefing,
                    status = bg.status.name,
                    startedAt = bg.startedAt
                )
            },
            pendingQuestion = hydratedQuestion,
            requestCount = backfill.requestCount,
            lastRequestUsage = backfill.lastRequestUsage
        )
        var snapshot = hydrateLastError(base, lastError(conversationId))
        DebugLog.event("Aggregator", "initial snapshot built: status=${snapshot.conversation.status}, messages=${snapshot.messages.size}")
        emit(snapshot)

        /** core 落库数据回查：拉全量消息 + token 统计，转成契约层 [MessagesPage] */
        suspend fun refreshPage(): MessagesPage? {
            val refreshed = runCatching { sessions.listMessages(conversationId) }.getOrNull() ?: return null
            DebugLog.data("Aggregator", "refreshed messages", refreshed.size)
            val refreshedToolResults = MederiModelMapper.buildToolResultsById(refreshed)
            val refreshedBackfill = MederiModelMapper.toUsageBackfill(refreshed)
            // 从已加载的 domain messages 直接算 contextUsedTokens，避免第二次全量 DB 加载 + Koog 转换
            val contextTokens = computeContextUsedTokens(refreshed, includeImages)
            return MessagesPage(
                messages = refreshed.map { MederiModelMapper.toChatMessage(it, refreshedToolResults) },
                tokenUsage = MederiModelMapper.toTokenUsage(refreshed),
                contextUsedTokens = contextTokens.toLong(),
                requestCount = refreshedBackfill.requestCount,
                lastRequestUsage = refreshedBackfill.lastRequestUsage
            )
        }

        var lastBlockCount = -1
        sessions.events(conversationId).collect { coreEvent ->
            val event = MederiModelMapper.toCoreEvent(coreEvent)
            val isDelta = event.type == CoreEventType.MESSAGE_DELTA
            // MESSAGE_DELTA 是流式期间的高频事件：不打逐事件日志（噪音源），
            // 改为在快照里数 block——blocks 数远超消息数 = "一字母一行"的直接证据

            if (event.type == CoreEventType.PLAN_APPROVAL_REQUESTED) {
                val planContent = event.payload["planContent"] ?: ""
                DebugLog.data(
                    "Aggregator",
                    "PLAN_APPROVAL_REQUESTED",
                    "planId=${event.payload["planId"]}, summary='${event.payload["summary"]}', planPath='${event.payload["planPath"]}', planContentLen=${planContent.length}"
                )
            }

            val emissions = ConversationStateReducer.applyWithRefresh(snapshot, event) { refreshPage() }
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
                        "event applied: type=${event.type}, status=${it.conversation.status}, messages=${it.messages.size}, blocks=$blockCount"
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

    /**
     * 从已加载的 domain messages 直接算 contextUsedTokens（不重新加载 DB、不转 Koog）。
     *
     * 口径与 [xyz.mederi.infrastructure.koog.aiViewContextUsedTokens] 完全一致：
     * - 找最后一条 SUMMARY（压缩标记），取标记本身 + 之后的窗口
     * - 窗口首条是 SUMMARY → 加权估算（[estimateTokens]），需按 includeImages 剔图
     * - 否则 → 最后一条 Assistant 的 inputTokens（API 报告值），无则回退估算
     *
     * @param messages 已从 DB 加载的全量 domain Message 列表
     * @param includeImages 估算路径是否计入图片 token（与 session.aiModel.supportsImages 一致）
     */
    private fun computeContextUsedTokens(
        messages: List<Message>,
        includeImages: Boolean
    ): Int {
        if (messages.isEmpty()) return 0
        val markerIndex = messages.indexOfLast { it.role == MessageRole.SUMMARY }
        val window = if (markerIndex >= 0) messages.drop(markerIndex) else messages
        if (window.isEmpty()) return 0
        return if (window.first().role == MessageRole.SUMMARY) {
            // 压缩后：估算窗口 token，按 includeImages 决定是否剔图
            val windowForEstimate = if (includeImages) window else window.map { msg ->
                msg.copy(parts = msg.parts.filterNot { it is MessagePart.Image })
            }
            estimateTokens(windowForEstimate)
        } else {
            // 未压缩：取最后一条 Assistant 的 API 报告 inputTokens，无则估算
            contextUsedTokens(window)
        }
    }

    /**
     * 从 store 重建快照时 hydrate pendingQuestion：
     * 扫描已映射的消息列表，找最后一个 ask_user 工具调用处于 Pending（无 ToolResult），
     * 从工具入参解析 questions 构造 [QuestionRequest]。
     *
     * 调用方负责判断 session 状态：仅在 session 为 RUNNING（turn 仍在跑）时调用——
     * 重启后 session 被 cleanupStaleRunningSessions 复位为 IDLE → 不调用（工具行走"未回答"显示）；
     * 用户绕过提问发消息后 session 被 abortAndJoin 复位为 IDLE → 不调用；
     * 仅在"切走再切回"（app 仍运行、QuestionRequester 仍在内存）时恢复可交互卡片。
     */
    fun hydratePendingQuestion(
        messages: List<ChatMessage>,
        conversationId: String
    ): QuestionRequest? {
        val askUserPending = messages.asReversed()
            .flatMap { it.blocks }
            .filterIsInstance<ChatBlock.ToolCall>()
            .firstOrNull { it.name == "ask_user" && it.state is ToolCallState.Pending }
            ?: return null

        val input = (askUserPending.state as ToolCallState.Pending).input
        val questionsJson = input["questions"] ?: return null

        val questions = runCatching {
            val json = Json { ignoreUnknownKeys = true; isLenient = true }
            var elem = json.parseToJsonElement(questionsJson)
            if (elem is JsonPrimitive && elem.isString) {
                elem = json.parseToJsonElement(elem.content)
            }
            if (elem is JsonArray) {
                elem.mapNotNull { item ->
                    if (item is JsonObject) {
                        val id = (item["id"] as? JsonPrimitive)?.contentOrNull ?: "q_0"
                        val prompt = (item["prompt"] as? JsonPrimitive)?.contentOrNull
                            ?: (item["question"] as? JsonPrimitive)?.contentOrNull
                            ?: return@mapNotNull null
                        val options = (item["options"] as? JsonArray)?.mapNotNull {
                            (it as? JsonPrimitive)?.contentOrNull
                        } ?: emptyList()
                        val multiSelect = (item["multiSelect"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false
                        QuestionRequest.Question(id = id, prompt = prompt, options = options, multiSelect = multiSelect)
                    } else null
                }
            } else emptyList()
        }.getOrDefault(emptyList())

        if (questions.isEmpty()) return null

        return QuestionRequest(
            id = "pending_${askUserPending.id}",
            conversationId = conversationId,
            questions = questions
        )
    }
}
