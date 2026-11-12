package xyz.mederi.core.bridge

import kotlinx.coroutines.CancellationException
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
import xyz.mederi.core.contract.withPendingInteractionStatus
import xyz.mederi.core.contract.models.ExecutionApprovalRequest
import xyz.mederi.core.contract.dto.ConversationSnapshot
import xyz.mederi.core.contract.dto.ConversationState
import xyz.mederi.core.contract.dto.MessagesPage
import xyz.mederi.core.contract.models.ChatBlock
import xyz.mederi.core.contract.models.ChatMessage
import xyz.mederi.core.contract.models.ConversationStatus
import xyz.mederi.core.contract.models.ConversationWorkspaceInfo
import xyz.mederi.core.contract.models.CoreEventType
import xyz.mederi.core.contract.models.PlanApprovalRequest
import xyz.mederi.core.contract.models.QuestionRequest
import xyz.mederi.core.contract.models.SubagentState
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
 * - 首次订阅时由 [initialSnapshot] 构建首帧（生产装配 = [buildInitialSnapshot]，
 *   jvmTest 可注入固定/计数 fake 快照验证时序与归约）
 * - 提供 core 落库数据的回查（MESSAGE_COMPLETED / MESSAGE_ERROR 对齐）
 *
 * 事件 → 快照的应用逻辑已抽取到 commonMain 的 [SnapshotReducer]，
 * 与 wasmJs（ServerAiCore）共享同一份状态机。
 */
object MederiEventAggregator {

    /**
     * @param initialSnapshot 首帧快照（observe 启动时恰好计算一次并先于任何事件发射）。
     * 生产装配见 [buildInitialSnapshot]（getSnapshot 共用同一实现）。
     */
    fun observe(
        conversationId: String,
        sessions: SessionApi,
        initialSnapshot: suspend () -> ConversationState,
        modelToProvider: (String) -> String?,
    ): Flow<ConversationSnapshot> = flow {
        DebugLog.section("Aggregator", "MederiEventAggregator.observe start")
        DebugLog.data("Aggregator", "conversationId", conversationId)

        var snapshot = initialSnapshot()
        DebugLog.event("Aggregator", "initial snapshot built: status=${snapshot.conversation.status}, messages=${snapshot.messages.size}")
        emit(snapshot)

        // refreshPage 的估算路径按 session.aiModel.supportsImages 决定是否计入图片 token；
        // 首帧构建（initialSnapshot）已读一次 session，此处再读同主键 session 仅为取该标志
        //（SQLite 主键读，开销可忽略；与 MESSAGE_COMPLETED 处理中的 re-read 同一模式）。
        val includeImages = runCatching { sessions.get(conversationId) }.getOrNull()?.aiModel?.supportsImages ?: true

        // 消息映射缓存（per-observation）：refreshPage 每次回查都对全量历史重跑 toChatMessage，
        // 本缓存让未变化消息的映射只算一次（IdentityHashMap + 工具结果版本号失效，见类注释）。
        // 注意：initialSnapshot lambda 在 observe 之外构建首帧（不改 observe 签名），无法共享本实例，
        // 首帧映射为一次性；首次 refreshPage 会做一次全量映射后进入缓存复用。
        val mappingCache = ChatMessageMappingCache()

        /** core 落库数据回查：拉全量消息 + token 统计，转成契约层 [MessagesPage] */
        suspend fun refreshPage(): MessagesPage? {
            val refreshed = runCatching { sessions.listMessages(conversationId) }.getOrNull() ?: return null
            DebugLog.data("Aggregator", "refreshed messages", refreshed.size)
            val refreshedToolResults = MederiModelMapper.buildToolResultsById(refreshed)
            val refreshedBackfill = MederiModelMapper.toUsageBackfill(refreshed)
            // 从已加载的 domain messages 直接算 contextUsedTokens，避免第二次全量 DB 加载 + Koog 转换
            val contextTokens = computeContextUsedTokens(refreshed, includeImages)
            return MessagesPage(
                messages = mappingCache.mapAll(refreshed, refreshedToolResults),
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
                DebugLog.data(
                    "Aggregator",
                    "PLAN_APPROVAL_REQUESTED",
                    "planId=${event.payload["planId"]}, summary='${event.payload["summary"]}', planPath='${event.payload["planPath"]}'"
                )
            }
            if (event.type == CoreEventType.MESSAGE_COMPLETED || event.type == CoreEventType.MESSAGE_ERROR) {
                runCatching { sessions.get(conversationId) }.getOrNull()?.let { latestSession ->
                    val latestConv = MederiModelMapper.toConversation(latestSession, latestSession.aiModel?.id?.let(modelToProvider))
                    snapshot = snapshot.copy(conversation = latestConv.copy(status = snapshot.conversation.status))
                }
            }

            // per-event 异常隔离：一个坏事件不杀整条 UI 订阅（log + 跳过、保留上一好快照）。
            // 注意：不做 live 水合 pendingQuestion——原始设计里水合只发生在 observe 启动时
            // （切走再切回重建快照）；live 水合会在问题已回答、ToolResult 尚未落库的窗口期
            // 重建出幽灵 pendingQuestion（强制 WaitingUser、挡住发送→停止转换），弊大于利。
            val emissions = try {
                ConversationStateReducer.applyWithRefresh(snapshot, event) { refreshPage() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLog.error("Aggregator", "event processing failed: type=${event.type}, error=${e.message}", e)
                listOf(snapshot)
            }
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
     * 计划水合单次扫描的产物：pending（审批卡片）+ 全量列表（活跃/历史/作废同源）。
     * 生产装配（[xyz.mederi.core.bridge.MederiAiCore.initialPlanHydration]）在一次
     * [xyz.mederi.plan.PlanStore.listBySession] 扫描内同时推导两者。
     */
    internal data class PlanApprovalHydration(
        val pending: PlanApprovalRequest?,
        val all: List<PlanApprovalRequest>,
    )

    /**
     * 构建会话当前完整快照（不订阅事件流）。observe 首帧与 getSnapshot 的唯一实现
     * （此前两份 ~70 行重复代码已漂移，本函数取较全版本统一之）。
     *
     * 内存访问顺序（有意为之，避免重复扫描/二次加载）：
     * - [sessions.get] + [sessions.listMessages] 各一次；contextUsedTokens 复用已加载消息
     *   计算（[computeContextUsedTokens]），不再走 sessions.contextUsedTokens 二次 DB 加载；
     * - [planHydration] 恰好调用一次：pending + 全量列表来自同一次计划目录扫描；
     * - [subagents] 直接取注入的最终列表（生产装配含 progressEvents 回放）。
     *
     * @param workspaceInfo 工作区投影（active/associated + 活跃操作），与 getSnapshot 同源。
     * @param mappingCache 可选消息映射缓存：非 null 时首帧映射走缓存（与 refreshPage 共享实例
     *   时，首帧映射结果可被第一次 refreshPage 复用）。生产 observe 经 initialSnapshot lambda
     *   调用本函数、无法传入 flow 内实例（observe 签名不改），因此默认为 null（首帧一次性映射）。
     */
    internal suspend fun buildInitialSnapshot(
        conversationId: String,
        sessions: SessionApi,
        modelToProvider: (String) -> String?,
        planHydration: suspend () -> PlanApprovalHydration,
        executionApprovals: () -> List<ExecutionApprovalRequest>,
        subagents: () -> List<SubagentState>,
        lastError: () -> LastSessionError?,
        workspaceInfo: suspend () -> ConversationWorkspaceInfo?,
        mappingCache: ChatMessageMappingCache? = null,
    ): ConversationSnapshot {
        val session = sessions.get(conversationId)
        val includeImages = session.aiModel?.supportsImages ?: true
        val messages = sessions.listMessages(conversationId)
        val toolResults = MederiModelMapper.buildToolResultsById(messages)
        DebugLog.data("Aggregator", "initial messages", messages.size)

        val backfill = MederiModelMapper.toUsageBackfill(messages)
        val mappedMessages = if (mappingCache != null) {
            mappingCache.mapAll(messages, toolResults)
        } else {
            messages.map { MederiModelMapper.toChatMessage(it, toolResults) }
        }
        // store 重建快照时 hydrate pendingQuestion：session 为 RUNNING 且有 ask_user Pending → 恢复挂起的提问
        val hydratedQuestion = if (session.status == xyz.mederi.domain.model.SessionStatus.RUNNING) {
            hydratePendingQuestion(mappedMessages, conversationId)
        } else null
        // T09：初始快照水合工作区投影（active/associated + 活跃操作），与 getSnapshot 保持一致。
        val hydratedWsInfo = workspaceInfo()
        // 单次计划扫描：pending + 全量列表同源（避免 planApproval/planApprovals 各自扫一遍计划目录）
        val hydratedPlans = planHydration()
        val base = ConversationSnapshot(
            conversation = MederiModelMapper.toConversation(session, session.aiModel?.id?.let(modelToProvider)).let { conv ->
                if (hydratedQuestion != null) conv.copy(status = ConversationStatus.WaitingUser) else conv
            },
            messages = mappedMessages,
            tokenUsage = MederiModelMapper.toTokenUsage(messages),
            // 复用已加载消息直接计算，不再调 sessions.contextUsedTokens 做二次 DB 加载 + Koog 转换
            contextUsedTokens = computeContextUsedTokens(messages, includeImages).toLong(),
            cost = MederiModelMapper.toCostSummary(),
            todos = MederiModelMapper.toTodos(session.todos),
            pendingPlanApproval = hydratedPlans.pending,
            pendingExecutionApprovals = executionApprovals(),
            planApprovals = hydratedPlans.all,
            subagents = subagents(),
            pendingQuestion = hydratedQuestion,
            requestCount = backfill.requestCount,
            lastRequestUsage = backfill.lastRequestUsage,
            workspaceInfo = hydratedWsInfo,
            activeWorkspaceOperation = hydratedWsInfo?.operation
        )
        return hydrateLastError(base, lastError()).withPendingInteractionStatus()
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
    internal fun computeContextUsedTokens(
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
            val json = Json { ignoreUnknownKeys = true }
            val elem = json.parseToJsonElement(questionsJson)
            if (elem is JsonArray) {
                elem.mapNotNull { item ->
                    if (item is JsonObject) {
                        val id = (item["id"] as? JsonPrimitive)?.contentOrNull ?: "q_0"
                        val prompt = (item["prompt"] as? JsonPrimitive)?.contentOrNull
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
