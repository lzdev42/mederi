package xyz.mederi.core.contract

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.mederi.core.contract.dto.ConversationState
import xyz.mederi.core.contract.dto.MessagesPage
import xyz.mederi.core.contract.models.ChatBlock
import xyz.mederi.core.contract.models.ChatMessage
import xyz.mederi.core.contract.models.ChatRole
import xyz.mederi.core.contract.models.ConversationStatus
import xyz.mederi.core.contract.models.CoreEvent
import xyz.mederi.core.contract.models.CoreEventType
import xyz.mederi.core.contract.models.ExecutionApprovalRequest
import xyz.mederi.core.contract.models.LastRequestUsage
import xyz.mederi.core.contract.models.QuestionRequest
import xyz.mederi.core.contract.models.PlanApprovalRequest
import xyz.mederi.core.contract.models.PlanSubtaskItem
import xyz.mederi.core.contract.models.TodoItem
import xyz.mederi.core.contract.models.TodoWireItem
import xyz.mederi.core.contract.models.ToolCallState
import xyz.mederi.currentTimeMillis
import xyz.mederi.ui.RightDockPanel
import xyz.mederi.ui.browser.BrowserActionKind
import xyz.mederi.ui.browser.BrowserAutomationUiState
import xyz.mederi.ui.browser.BrowserFlowStep
import xyz.mederi.ui.browser.BrowserStepStatus

/**
 * 把契约层 [CoreEvent] 应用到 [ConversationState] 上的纯逻辑状态机（跨平台）。
 *
 * 同一份聚合逻辑服务两端：
 * - JVM（desktop 进程内 / server 进程内）：MederiEventAggregator 提供 core 数据源
 * - wasmJs（浏览器）：ServerAiCore 提供 HTTP/SSE 数据源
 *
 * 聚合规则：
 * - [CoreEventType.MESSAGE_DELTA] 按响应消息 ID 聚合；response_started/completed 保留请求边界
 * - [CoreEventType.MESSAGE_COMPLETED] 标记占位消息完成，再回查落库数据对齐
 * - [CoreEventType.MESSAGE_ERROR] 设置 errorMessage 并回查落库数据
 * - [CoreEventType.TOOL_CALLED] / [CoreEventType.TOOL_RESULT] 维护 ToolCall block 状态机
 * - APPROVAL / QUESTION / PLAN_APPROVAL 系列事件维护 pending 状态与全生命周期投影
 * - [CoreEventType.LLM_REQUEST_COMPLETED] 累加 requestCount、更新 lastRequestUsage
 * - [CoreEventType.SUBAGENT_*] 事件由 [SubagentTracker] 原子更新 [ConversationState.subagents]
 * - [CoreEventType.BROWSER_TASK_*] 事件更新 [ConversationState.browserAutomation] 与 [ConversationState.activeDockPanel]
 */
object ConversationStateReducer {

    /**
     * 应用单个事件，返回**要发出的状态序列**（1 或 2 个）。
     *
     * MESSAGE_COMPLETED 先发出"完成状态"（按钮立即变化），再回查 [refreshPage]
     * 落库数据发出对齐后的最终状态；MESSAGE_ERROR 同理回查但只发最终一个。
     * 其余事件只发一个。回查失败（[refreshPage] 返回 null 或抛异常）时跳过对齐。
     */
    suspend fun applyWithRefresh(
        snapshot: ConversationState,
        event: CoreEvent,
        refreshPage: suspend () -> MessagesPage?,
    ): List<ConversationState> = when (event.type) {
        CoreEventType.MESSAGE_DELTA, CoreEventType.TOOL_CALLED, CoreEventType.TOOL_RESULT -> {
            // 初始快照读取与事件订阅之间可能错过 SESSION_UPDATED。新响应首次出现前
            // 回查 durable-first 输入，避免流式响应先于仍在 UI 中的乐观用户气泡外显。
            // 只补用户输入，不导入落库响应正文：回查可能领先于事件队列，导入正文再叠加
            // delta 会重复输出；也不能用快照覆盖已收到的实时响应。
            val isNewResponse = !event.messageId.isNullOrBlank() &&
                snapshot.messages.none { it.id == event.messageId }
            val page = if (isNewResponse) runCatching { refreshPage() }.getOrNull() else null
            val aligned = if (page != null) snapshot.copy(
                messages = restoreInputOrder(snapshot.messages, page.messages)
            ) else snapshot
            listOf(apply(aligned, event))
        }

        CoreEventType.MESSAGE_COMPLETED -> {
            // 1. 先发出完成状态（按钮立即变化），保留流式累积的内容，标记 streaming 结束
            val idle = apply(snapshot, event)
            listOf(idle) + listOfNotNull(
                runCatching { refreshPage() }.getOrNull()?.let { page ->
                    idle.copy(
                        messages = page.messages,
                        tokenUsage = page.tokenUsage,
                        contextUsedTokens = page.contextUsedTokens,
                        requestCount = page.requestCount,
                        lastRequestUsage = page.lastRequestUsage,
                    )
                }
            )
        }

        CoreEventType.SESSION_UPDATED -> {
            val updated = apply(snapshot, event)
            // 新 turn 开始（包含 durable-first 用户消息刚落库）：
            // 立即回查对齐落库消息，使状态立刻包含刚发送的用户消息
            val page = runCatching { refreshPage() }.getOrNull()
            listOf(page?.let { p ->
                updated.copy(
                    messages = if (event.payload["inputAccepted"] == "true") mergeLiveMessages(p.messages, snapshot.messages) else p.messages,
                    tokenUsage = p.tokenUsage,
                    contextUsedTokens = p.contextUsedTokens,
                    requestCount = p.requestCount,
                    lastRequestUsage = p.lastRequestUsage,
                )
            } ?: updated)
        }

        CoreEventType.MESSAGE_ERROR -> {
            val errored = apply(snapshot, event)
            // 回查落库数据对齐（失败保持错误状态），只发最终一个
            val page = runCatching { refreshPage() }.getOrNull()
            listOf(page?.let { p ->
                errored.copy(
                    messages = p.messages,
                    tokenUsage = p.tokenUsage,
                    contextUsedTokens = p.contextUsedTokens,
                    requestCount = p.requestCount,
                    lastRequestUsage = p.lastRequestUsage,
                )
            } ?: errored)
        }

        else -> listOf(apply(snapshot, event))
    }

    /**
     * 应用单个事件到状态（不含落库回查）。
     */
    fun apply(snapshot: ConversationState, event: CoreEvent): ConversationState =
        reduce(snapshot, event).withPendingInteractionStatus()

    private fun reduce(snapshot: ConversationState, event: CoreEvent): ConversationState = when (event.type) {
        CoreEventType.SESSION_CREATED -> snapshot

        CoreEventType.SESSION_UPDATED -> if (event.payload["inputAccepted"] == "true") {
            // 输入已被 Core 接收：仅刷新历史，不结束当前响应，也不清除等待用户的交互。
            snapshot
        } else snapshot.copy(
            conversation = snapshot.conversation.copy(status = ConversationStatus.Working),
            // 新 turn 开始 = 上一轮的错误已过时（如"用户中止了对话"来自被回滚杀掉的旧 turn），
            // 不清除会让错误横幅挂在重发的新 turn 上；同时清空上一轮未决的提问与审批，
            // 并且强制复位上一轮遗留的 isStreaming 状态
            messages = snapshot.messages.map { msg ->
                if (msg.isStreaming) msg.copy(isStreaming = false) else msg
            },
            errorMessage = null,
            pendingQuestion = null,
            pendingPlanApproval = null,
            errorId = null,
            errorDiagnostic = null,
            errorIsStreamInterrupted = false,
            statusHint = null
        )

        CoreEventType.MESSAGE_DELTA -> snapshot.withDelta(event).copy(statusHint = null, errorIsStreamInterrupted = false)

        CoreEventType.MESSAGE_COMPLETED -> {
            val now = currentTimeMillis()
            val streamInterrupted = event.payload["failureMode"] == "PREMATURE_CLOSE"
            val turnDiffJson = event.payload["turnDiffSummary"]
            val diffSummary = turnDiffJson?.let {
                runCatching {
                    kotlinx.serialization.json.Json.Default.decodeFromString<xyz.mederi.core.contract.models.TurnDiffSummaryUi>(it)
                }.getOrNull()
            }
            val diffMessageId = event.payload["diffMessageId"]
            val finalMessageId = event.payload["finalMessageId"]?.takeIf { it.isNotBlank() }
            val turnId = event.payload["turnId"]

            val completed = snapshot.copy(
                pendingQuestion = null,
                pendingPlanApproval = null,
                conversation = snapshot.conversation.copy(status = ConversationStatus.Idle),
                messages = snapshot.messages.map { msg ->
                    val isTarget = if (diffMessageId != null) msg.id == diffMessageId else msg.isStreaming
                    val updatedDiff = if (isTarget && diffSummary != null) diffSummary else msg.turnDiffSummary
                    val belongsToTurn = turnId != null && msg.turnId == turnId
                    val finalAnswer = when {
                        msg.role != ChatRole.Assistant -> msg.isFinalAnswer
                        streamInterrupted && msg.isStreaming -> false
                        msg.id == finalMessageId -> !streamInterrupted
                        else -> msg.isFinalAnswer
                    }
                    msg.copy(
                        isStreaming = false,
                        completedAt = if (msg.isStreaming) now else msg.completedAt,
                        turnDiffSummary = updatedDiff,
                        isFinalAnswer = finalAnswer,
                        workStartedAt = if (belongsToTurn) event.payload["workStartedAt"]?.toLongOrNull() ?: msg.workStartedAt else msg.workStartedAt,
                        workCompletedAt = if (belongsToTurn) event.payload["workCompletedAt"]?.toLongOrNull() ?: msg.workCompletedAt else msg.workCompletedAt,
                    )
                },
                errorMessage = event.payload["error"] ?: snapshot.errorMessage,
                errorId = event.payload["errorId"] ?: snapshot.errorId,
                errorDiagnostic = event.payload["fullDiagnostic"] ?: snapshot.errorDiagnostic,
                errorIsStreamInterrupted = streamInterrupted,
                statusHint = null
            )
            completed
        }

        CoreEventType.MESSAGE_ERROR -> {
            // 读 payload["finalStatus"]（TurnExecutor.emit() 注入的权威会话终态）：
            // ERROR→红点，IDLE（transient 可恢复）→蓝点；payload 缺失时保守回退 Error。
            // 主动取消是正常停止，结束流式/计时即可，不投影为错误或诊断入口。
            val cancelled = event.payload["errorCategory"] == "CANCELLED"
            val fs = event.payload["finalStatus"]
            val status = if (cancelled) ConversationStatus.Idle else when (fs) {
                "IDLE" -> ConversationStatus.Idle
                else -> ConversationStatus.Error
            }
            snapshot.copy(
                conversation = snapshot.conversation.copy(status = status),
                pendingQuestion = null,
                pendingPlanApproval = null,
                messages = snapshot.messages.map { msg ->
                    val belongsToWork = event.payload["turnId"] != null && msg.turnId == event.payload["turnId"]
                    msg.copy(isStreaming = false,
                        workStartedAt = if (belongsToWork) event.payload["workStartedAt"]?.toLongOrNull() ?: msg.workStartedAt else msg.workStartedAt,
                        workCompletedAt = if (belongsToWork) event.payload["workCompletedAt"]?.toLongOrNull() ?: msg.workCompletedAt else msg.workCompletedAt)
                },
                errorMessage = if (cancelled) null else event.payload["error"],
                errorId = if (cancelled) null else event.payload["errorId"],
                errorDiagnostic = if (cancelled) null else event.payload["fullDiagnostic"],
                errorIsStreamInterrupted = !cancelled && event.payload["failureMode"] == "PREMATURE_CLOSE",
                statusHint = null
            )
        }

        CoreEventType.STATUS -> {
            // 环境态状态事件：过程状态提示，不碰会话状态机。
            // 其他 scope 的 STATUS 事件忽略（当前只有 provider 一个 scope）
            if (event.payload["scope"] == "provider" && event.payload["code"] == "RETRYING") {
                val attempt = event.payload["attempt"] ?: "?"
                val max = event.payload["maxAttempts"] ?: "?"
                val serverMsg = event.payload["message"]?.takeIf { it.isNotBlank() }
                val delayMs = event.payload["delayMs"]?.toLongOrNull()
                val retryAt = delayMs?.let { currentTimeMillis() + it }
                snapshot.copy(
                    statusHint = buildString {
                        append("$attempt/$max")
                        append("|${serverMsg ?: ""}")
                        if (retryAt != null) append("|$retryAt")
                    }
                )
            } else if (event.payload["scope"] == "provider" && event.payload["code"] == "RETRY_FINISHED") {
                snapshot.copy(statusHint = null)
            } else snapshot
        }

        CoreEventType.TOOL_CALLED -> snapshot.withToolCalled(
            toolCallId = event.payload["toolCallId"] ?: "",
            name = event.payload["tool"] ?: "tool",
            args = event.payload["args"] ?: "",
            event = event,
        ).copy(statusHint = null)

        CoreEventType.TOOL_RESULT -> snapshot.withToolResult(
            toolCallId = event.payload["toolCallId"] ?: "",
            name = event.payload["tool"] ?: "tool",
            output = event.payload["output"] ?: "",
            isError = event.payload["isError"] == "true",
            event = event,
        )

        // 单次 LLM HTTP 请求完成：实时累加 requestCount、更新 lastRequestUsage（不走 refreshPage）
        CoreEventType.LLM_REQUEST_COMPLETED -> snapshot.copy(
            requestCount = snapshot.requestCount + 1,
            lastRequestUsage = event.toLastRequestUsage()
        )

        CoreEventType.QUESTION_REQUESTED -> {
            val rawQuestions = event.payload["questions"] ?: "[]"
            val questions = runCatching {
                questionWireJson.decodeFromString(questionListSerializer, rawQuestions)
            }.getOrElse { ex ->
                emptyList()
            }
            snapshot.copy(
                conversation = snapshot.conversation.copy(status = ConversationStatus.WaitingUser),
                messages = snapshot.messages.map { msg ->
                    if (msg.isStreaming) msg.copy(isStreaming = false) else msg
                },
                pendingQuestion = QuestionRequest(
                    id = event.payload["questionId"] ?: "",
                    conversationId = snapshot.conversation.id,
                    questions = questions
                )
            )
        }

        CoreEventType.EXECUTION_APPROVAL_REQUESTED -> {
            val request = event.payload["request"]?.let { raw ->
                runCatching { questionWireJson.decodeFromString<ExecutionApprovalRequest>(raw) }.getOrNull()
            }
            if (request == null || request.conversationId != snapshot.conversation.id) snapshot else snapshot.copy(
                pendingExecutionApprovals = snapshot.pendingExecutionApprovals.filterNot { it.id == request.id } + request
            )
        }

        CoreEventType.EXECUTION_APPROVAL_RESOLVED -> {
            val requestId = event.payload["requestId"]
            if (snapshot.pendingExecutionApprovals.none { it.id == requestId }) snapshot else snapshot.copy(
                conversation = snapshot.conversation.copy(status = ConversationStatus.Working),
                pendingExecutionApprovals = snapshot.pendingExecutionApprovals.filterNot { it.id == requestId }
            )
        }

        CoreEventType.SANDBOX_SETTINGS_CHANGED -> snapshot

        CoreEventType.QUESTION_RESOLVED -> {
            val lastMsg = snapshot.messages.lastOrNull()
            val updatedMessages = if (lastMsg != null && lastMsg.role == ChatRole.Assistant && !lastMsg.isStreaming) {
                val msgs = snapshot.messages.toMutableList()
                msgs[msgs.lastIndex] = lastMsg.copy(isStreaming = true)
                msgs
            } else {
                snapshot.messages
            }
            snapshot.copy(
                conversation = snapshot.conversation.copy(status = ConversationStatus.Working),
                messages = updatedMessages,
                pendingQuestion = null
            )
        }

        CoreEventType.PLAN_APPROVAL_REQUESTED -> {
            val subtasksJson = event.payload["subtasks"]
            val subtasks = if (!subtasksJson.isNullOrBlank()) {
                runCatching {
                    todoWireJson.decodeFromString(planSubtasksSerializer, subtasksJson)
                }.getOrDefault(emptyList())
            } else emptyList()

            val req = PlanApprovalRequest(
                id = event.payload["planId"] ?: event.messageId ?: "",
                conversationId = snapshot.conversation.id,
                planPath = event.payload["planPath"] ?: "",
                title = event.payload["title"] ?: "",
                summary = event.payload["summary"].orEmpty(),
                subtaskCount = event.payload["subtaskCount"]?.toIntOrNull() ?: subtasks.size,
                status = "PENDING",
                subtasks = subtasks
            )
            val updatedList = (snapshot.planApprovals.filter { it.id != req.id } + req)
            snapshot.copy(
                conversation = snapshot.conversation.copy(status = ConversationStatus.WaitingUser),
                messages = snapshot.messages.map { msg ->
                    if (msg.isStreaming) msg.copy(isStreaming = false) else msg
                },
                pendingPlanApproval = req,
                planApprovals = updatedList
            )
        }

        CoreEventType.TODO_UPDATED -> {
            val todos = event.payload["todos"]?.let(::decodeTodoProjection)
            if (todos != null) snapshot.copy(todos = todos) else snapshot
        }

        CoreEventType.PLAN_PROGRESS -> {
            val planId = event.payload["planId"]
            val action = event.payload["action"]
            val subtasksJson = event.payload["subtasks"]
            val newSubtasks = if (!subtasksJson.isNullOrBlank()) {
                runCatching {
                    todoWireJson.decodeFromString(planSubtasksSerializer, subtasksJson)
                }.getOrNull()
            } else null

            val updatedApprovals = if (!planId.isNullOrBlank()) {
                val newStatus = when (action) {
                    "voided" -> "VOIDED"
                    "completed" -> "COMPLETED"
                    "approved", "created" -> "APPROVED"
                    "subtask-started", "spec-generated" -> "IN_PROGRESS"
                    else -> null
                }
                val existing = snapshot.planApprovals.find { it.id == planId }
                if (existing != null) {
                    snapshot.planApprovals.map { plan ->
                        if (plan.id == planId) plan.copy(
                            status = newStatus ?: plan.status,
                            subtasks = newSubtasks ?: plan.subtasks
                        ) else plan
                    }
                } else if (action == "created") {
                    snapshot.planApprovals + PlanApprovalRequest(
                        id = planId,
                        conversationId = snapshot.conversation.id,
                        planPath = event.payload["planPath"].orEmpty(),
                        title = event.payload["title"].orEmpty(),
                        summary = event.payload["summary"].orEmpty(),
                        subtaskCount = newSubtasks?.size ?: 0,
                        status = "APPROVED",
                        subtasks = newSubtasks.orEmpty()
                    )
                } else snapshot.planApprovals
            } else snapshot.planApprovals

            snapshot.copy(planApprovals = updatedApprovals)
        }

        CoreEventType.PLAN_APPROVAL_RESOLVED -> {
            val planId = event.payload["planId"]
            val approved = event.payload["approved"]?.toBooleanStrictOrNull() ?: true
            val updatedList = snapshot.planApprovals.map {
                if (it.id == planId) it.copy(status = if (approved) "APPROVED" else "REJECTED") else it
            }
            val lastMsg = snapshot.messages.lastOrNull()
            val updatedMessages = if (approved && lastMsg != null && lastMsg.role == ChatRole.Assistant && !lastMsg.isStreaming) {
                val msgs = snapshot.messages.toMutableList()
                msgs[msgs.lastIndex] = lastMsg.copy(isStreaming = true)
                msgs
            } else {
                snapshot.messages
            }
            snapshot.copy(
                conversation = snapshot.conversation.copy(status = ConversationStatus.Working),
                messages = updatedMessages,
                pendingPlanApproval = null,
                planApprovals = updatedList
            )
        }

        // 浏览器任务生命周期与步骤事件（收敛入状态机）
        CoreEventType.BROWSER_TASK_STARTED -> {
            val browser = event.payload["browser"]
            val current = snapshot.browserAutomation ?: BrowserAutomationUiState()
            val updated = current.copy(
                isAutomating = true,
                isPanelOpen = true,
                startedAtMillis = currentTimeMillis(),
                completedAtMillis = null,
                taskSummary = event.payload["thought"] ?: event.payload["message"],
                taskId = event.payload["taskId"],
                steps = emptyList(),
                liveReasoning = "",
                liveText = ""
            )
            snapshot.copy(
                browserAutomation = updated,
                activeDockPanel = if (browser == "jcef") RightDockPanel.BROWSER else snapshot.activeDockPanel
            )
        }

        CoreEventType.BROWSER_TASK_STEP -> {
            val current = snapshot.browserAutomation ?: BrowserAutomationUiState(isAutomating = true, isPanelOpen = true)
            val updatedSteps = parseBrowserStep(event, current.steps)
            snapshot.copy(browserAutomation = current.copy(steps = updatedSteps, liveReasoning = "", liveText = ""))
        }

        // 浏览器任务 LLM 流式增量（decide 阶段 reasoning/text 逐 token 推送）：瞬态流式事件，
        // 累加进 browserAutomation 的 liveReasoning/liveText（不入 steps 时间线），
        // UI 浮动条推理框从这两个字段流式渲染；新步（BROWSER_TASK_STEP）与终态复位为空
        CoreEventType.BROWSER_TASK_REASONING_DELTA -> {
            val current = snapshot.browserAutomation ?: BrowserAutomationUiState()
            val kind = event.payload["kind"]
            val content = event.payload["content"].orEmpty()
            val updated = when (kind) {
                "reasoning" -> current.copy(liveReasoning = current.liveReasoning + content)
                "text" -> current.copy(liveText = current.liveText + content)
                else -> current
            }
            snapshot.copy(browserAutomation = updated)
        }

        CoreEventType.BROWSER_TASK_COMPLETED,
        CoreEventType.BROWSER_TASK_ERROR,
        CoreEventType.BROWSER_TASK_STOPPED -> {
            // 终态一律收起浏览器 Dock 并清空 taskId（避免停止按钮用旧 taskId），不再区分测试任务
            val current = snapshot.browserAutomation
            val updatedDockPanel = if (snapshot.activeDockPanel == RightDockPanel.BROWSER) {
                null
            } else {
                snapshot.activeDockPanel
            }
            if (current != null) {
                val updatedSteps = current.steps.map { step ->
                    if (step is BrowserFlowStep.Action && step.status == BrowserStepStatus.RUNNING) {
                        step.copy(
                            status = if (event.type == CoreEventType.BROWSER_TASK_COMPLETED) {
                                BrowserStepStatus.COMPLETED
                            } else {
                                BrowserStepStatus.FAILED
                            }
                        )
                    } else step
                }
                snapshot.copy(
                    browserAutomation = current.copy(
                        isAutomating = false,
                        isPanelOpen = false,
                        completedAtMillis = currentTimeMillis(),
                        taskId = null,
                        steps = updatedSteps,
                        liveReasoning = "",
                        liveText = ""
                    ),
                    activeDockPanel = updatedDockPanel
                )
            } else {
                snapshot.copy(activeDockPanel = updatedDockPanel)
            }
        }

        // 子代理生命周期事件（收敛入状态机，彻底消除 ViewModel 旁路 Map）
        CoreEventType.SUBAGENT_STARTED,
        CoreEventType.SUBAGENT_PROGRESS,
        CoreEventType.SUBAGENT_COMPLETED,
        CoreEventType.SUBAGENT_ERROR,
        CoreEventType.SUBAGENT_STOPPED,
        CoreEventType.SUBAGENT_DISCARDED -> {
            val currentMap = snapshot.subagents.associateBy { it.agentId }
            val updatedMap = SubagentTracker.apply(currentMap, event)
            snapshot.copy(subagents = updatedMap.values.sortedBy { it.startedAt })
        }

        // 后台任务生命周期事件（暂时保持不变）
        CoreEventType.BACKGROUND_TASK_STARTED,
        CoreEventType.BACKGROUND_TASK_COMPLETED,
        CoreEventType.BACKGROUND_TASK_ERROR,
        CoreEventType.BACKGROUND_TASK_STOPPED -> snapshot

        // 会话 skill 激活/卸载：事件只是投影通知 UI 刷新激活列表（UI 按需读 session.activeSkills），
        // 快照不直接处理。
        CoreEventType.SKILL_ACTIVATED,
        CoreEventType.SKILL_DEACTIVATED -> snapshot

        // ------------------------------------------------------------------
        // 工作区与会话元数据事件
        // ------------------------------------------------------------------

        CoreEventType.WORKSPACE_CHANGED -> {
            val eventProjectId = event.payload["projectId"]
            val eventSessionId = event.payload["sessionId"]
            // 归属未知或跨项目事件绝不借用/更新
            if (eventProjectId == null || eventProjectId != snapshot.conversation.projectId) {
                return snapshot
            }
            if (eventSessionId != null && eventSessionId != snapshot.conversation.id) {
                return snapshot
            }
            val wsInfoJson = event.payload["workspaceInfo"]
            val newWsInfo = wsInfoJson?.let { jsonStr ->
                runCatching {
                    todoWireJson.decodeFromString(xyz.mederi.core.contract.models.ConversationWorkspaceInfo.serializer(), jsonStr)
                }.getOrNull() ?: runCatching {
                    val active = todoWireJson.decodeFromString(xyz.mederi.core.contract.models.WorkspaceInfo.serializer(), jsonStr)
                    xyz.mederi.core.contract.models.ConversationWorkspaceInfo(active = active)
                }.getOrNull()
            }
            val updatedConv = event.payload["workspaceId"]?.let {
                snapshot.conversation.copy(workspaceId = it)
            } ?: snapshot.conversation
            snapshot.copy(
                conversation = updatedConv,
                workspaceInfo = newWsInfo ?: snapshot.workspaceInfo
            )
        }

        CoreEventType.WORKSPACE_OPERATION_UPDATED -> {
            val eventProjectId = event.payload["projectId"]
            val eventSessionId = event.payload["sessionId"]
            // 归属未知或跨项目事件绝不借用/更新
            if (eventProjectId == null || eventProjectId != snapshot.conversation.projectId) {
                return snapshot
            }
            if (eventSessionId != null && eventSessionId != snapshot.conversation.id) {
                return snapshot
            }
            val opJson = event.payload["operation"] ?: return snapshot
            val op = runCatching {
                todoWireJson.decodeFromString(xyz.mederi.core.contract.models.WorkspaceOperation.serializer(), opJson)
            }.getOrNull() ?: return snapshot

            // 忽略更老版本
            val curOp = snapshot.activeWorkspaceOperation
            if (curOp?.id == op.id && op.version < curOp.version) {
                return snapshot
            }

            snapshot.copy(activeWorkspaceOperation = op)
        }

        CoreEventType.CONVERSATION_ARCHIVE_CHANGED -> {
            val eventSessionId = event.payload["sessionId"]
            if (eventSessionId != null && eventSessionId != snapshot.conversation.id) {
                return snapshot
            }
            val archivedAt = event.payload["archivedAt"]
            snapshot.copy(
                conversation = snapshot.conversation.copy(
                    archivedAt = archivedAt,
                    pinned = if (archivedAt != null) false else snapshot.conversation.pinned
                )
            )
        }

        CoreEventType.CONVERSATION_PIN_CHANGED -> {
            val eventSessionId = event.payload["sessionId"]
            if (eventSessionId != null && eventSessionId != snapshot.conversation.id) {
                return snapshot
            }
            val pinned = event.payload["pinned"]?.toBooleanStrictOrNull() ?: false
            snapshot.copy(
                conversation = snapshot.conversation.copy(pinned = pinned)
            )
        }
    }

    // ------------------------------------------------------------------
    // 私有：Todo 投影解码（TODO_UPDATED 使用，无状态整体替换）
    // ------------------------------------------------------------------

    private val todoWireJson = Json { ignoreUnknownKeys = true }
    private val todoWireSerializer = ListSerializer(TodoWireItem.serializer())
    private val planSubtasksSerializer = ListSerializer(PlanSubtaskItem.serializer())

    private val questionWireJson = Json {
        ignoreUnknownKeys = true
    }
    private val questionListSerializer = ListSerializer(QuestionRequest.Question.serializer())

    private fun decodeTodoProjection(raw: String): List<TodoItem>? = runCatching {
        todoWireJson.decodeFromString(todoWireSerializer, raw)
            .mapIndexed { i, w -> TodoItem(id = "t$i", content = w.content, status = w.status) }
    }.getOrNull()

    // ------------------------------------------------------------------
    // 私有：浏览器步骤解析（将 BROWSER_TASK_STEP 映射为时间线节点）
    // ------------------------------------------------------------------

    private fun parseBrowserStep(event: CoreEvent, existingSteps: List<BrowserFlowStep>): List<BrowserFlowStep> {
        val taskId = event.payload["taskId"] ?: return existingSteps
        val stepNum = event.payload["step"]?.toIntOrNull() ?: 0
        val thought = event.payload["thought"]
        val memory = event.payload["memory"]
        val resultsJson = event.payload["resultsJson"]
        val message = event.payload["message"]
        val now = currentTimeMillis()

        val newSteps = mutableListOf<BrowserFlowStep>()

        // 1. AI 旁白
        if (!thought.isNullOrBlank()) {
            newSteps.add(
                BrowserFlowStep.Narration(
                    id = "${taskId}_s${stepNum}_narr",
                    text = thought,
                    timestampMillis = now,
                )
            )
        }

        // 1.5 AI 记忆
        if (!memory.isNullOrBlank()) {
            newSteps.add(
                BrowserFlowStep.Memory(
                    id = "${taskId}_s${stepNum}_mem",
                    text = memory,
                    timestampMillis = now,
                )
            )
        }

        // 2. 动作结果卡片
        if (!resultsJson.isNullOrBlank()) {
            runCatching {
                val arr = Json.parseToJsonElement(resultsJson).jsonArray
                arr.forEachIndexed { i, el ->
                    val obj = el.jsonObject
                    val action = obj["action"]?.jsonPrimitive?.contentOrNull ?: "unknown"
                    val success = obj["success"]?.jsonPrimitive?.booleanOrNull ?: false
                    val detail = obj["detail"]?.jsonPrimitive?.contentOrNull ?: ""
                    newSteps.add(
                        BrowserFlowStep.Action(
                            id = "${taskId}_s${stepNum}_a${i}",
                            kind = mapBrowserActionKind(action),
                            title = mapBrowserActionLabel(action),
                            target = detail.takeIf { it.isNotBlank() },
                            status = if (success) BrowserStepStatus.COMPLETED else BrowserStepStatus.FAILED,
                            timestampMillis = now,
                        )
                    )
                }
            }
        } else if (thought.isNullOrBlank() && !message.isNullOrBlank()) {
            newSteps.add(
                BrowserFlowStep.Narration(
                    id = "${taskId}_s${stepNum}_narr",
                    text = message,
                    timestampMillis = now,
                )
            )
        }

        return existingSteps + newSteps
    }

    private fun mapBrowserActionKind(action: String): BrowserActionKind = when (action) {
        "navigate" -> BrowserActionKind.NAVIGATE
        "click" -> BrowserActionKind.CLICK
        "type" -> BrowserActionKind.INPUT
        "scroll" -> BrowserActionKind.SCROLL
        "screenshot" -> BrowserActionKind.SNAPSHOT
        "wait_for" -> BrowserActionKind.WAIT
        else -> BrowserActionKind.OTHER
    }

    private fun mapBrowserActionLabel(action: String): String = when (action) {
        "navigate" -> "导航"
        "click" -> "点击"
        "type" -> "输入"
        "scroll" -> "滚动"
        "screenshot" -> "截图"
        "wait_for" -> "等待"
        "judge" -> "判定"
        "drill" -> "脚本执行"
        "done" -> "完成"
        "sleep" -> "休眠"
        "close" -> "关闭"
        "back" -> "后退"
        "tabs" -> "标签操作"
        "noop" -> "无操作"
        "llm" -> "决策"
        else -> action
    }

    // ------------------------------------------------------------------
    // 私有：消息/block 状态机
    // ------------------------------------------------------------------

    private fun ConversationState.withDelta(event: CoreEvent): ConversationState {
        val payload = event.payload
        val type = payload["type"] ?: "text"
        val content = payload["content"] ?: ""
        val currentMessages = this.messages.toMutableList()

        val placeholder = ensureStreamingPlaceholder(currentMessages, event)
        val updatedBlocks = placeholder.blocks.toMutableList()

        when (type) {
            "response_started", "response_completed" -> Unit
            "text" -> {
                val lastBlock = updatedBlocks.lastOrNull()
                if (lastBlock is ChatBlock.Text) {
                    updatedBlocks[updatedBlocks.lastIndexOf(lastBlock)] = lastBlock.copy(
                        text = lastBlock.text + content
                    )
                } else {
                    updatedBlocks.add(
                        ChatBlock.Text(
                            id = "${placeholder.id}_text_${updatedBlocks.size}",
                            text = content
                        )
                    )
                }
            }

            "reasoning" -> {
                val lastBlock = updatedBlocks.lastOrNull()
                if (lastBlock is ChatBlock.Reasoning) {
                    updatedBlocks[updatedBlocks.lastIndexOf(lastBlock)] = lastBlock.copy(
                        text = lastBlock.text + content
                    )
                } else {
                    updatedBlocks.add(
                        ChatBlock.Reasoning(
                            id = "${placeholder.id}_reasoning_${updatedBlocks.size}",
                            text = content
                        )
                    )
                }
            }

            "tool_call" -> {
                val name = payload["name"] ?: "tool"
                val input = if (content.isBlank()) emptyMap() else parseToolArgs(content)
                val existing = (if (name != "tool") {
                    updatedBlocks.filterIsInstance<ChatBlock.ToolCall>()
                        .lastOrNull { (it.name == name || it.name == "tool") && it.state is ToolCallState.Running }
                } else null)
                    ?: (if (updatedBlocks.lastOrNull() is ChatBlock.ToolCall && (updatedBlocks.last() as ChatBlock.ToolCall).state is ToolCallState.Running) {
                        updatedBlocks.last() as ChatBlock.ToolCall
                    } else null)

                if (existing != null) {
                    val prevInput = (existing.state as? ToolCallState.Running)?.input
                    val effectiveInput = if (input.isEmpty() && !prevInput.isNullOrEmpty()) prevInput else input
                    updatedBlocks[updatedBlocks.lastIndexOf(existing)] = existing.copy(
                        name = if (name != "tool") name else existing.name,
                        state = ToolCallState.Running(input = effectiveInput)
                    )
                } else if (this.conversation.status != ConversationStatus.WaitingUser) {
                    updatedBlocks.add(
                        ChatBlock.ToolCall(
                            id = "${placeholder.id}_tool_${updatedBlocks.size}",
                            name = name,
                            state = ToolCallState.Running(input = input)
                        )
                    )
                }
            }

            "image" -> {
                val url = payload["url"] ?: content
                if (url.isNotBlank()) {
                    val mimeType = payload["mimeType"]
                    val name = payload["name"]?.ifBlank { null }
                        ?: url.substringAfterLast('/').substringAfterLast('\\').ifBlank { "image.png" }
                    updatedBlocks.add(
                        ChatBlock.File(
                            id = "${placeholder.id}_image_${updatedBlocks.size}",
                            name = name,
                            url = url,
                            mimeType = mimeType ?: "image/png"
                        )
                    )
                }
            }
        }

        currentMessages.putMessage(placeholder.copy(
            blocks = updatedBlocks,
            isStreaming = type != "response_completed",
            completedAt = if (type == "response_completed") currentTimeMillis() else null,
            workStartedAt = event.payload["workStartedAt"]?.toLongOrNull() ?: placeholder.workStartedAt,
            workCompletedAt = event.payload["workCompletedAt"]?.toLongOrNull() ?: placeholder.workCompletedAt,
        ))

        val targetStatus = if (this.conversation.status == ConversationStatus.WaitingUser) {
            ConversationStatus.WaitingUser
        } else {
            ConversationStatus.Working
        }
        return this.copy(
            conversation = this.conversation.copy(status = targetStatus),
            messages = currentMessages
        )
    }

    /** 回查已落库历史时保留尚未落库的响应，不让接收插话的刷新冲掉正在流式的内容。 */
    private fun mergeLiveMessages(persisted: List<ChatMessage>, live: List<ChatMessage>): List<ChatMessage> {
        val merged = persisted.toMutableList()
        for ((index, msg) in live.withIndex()) {
            val isUnconfirmedResponse = msg.role == ChatRole.Assistant && msg.turnId != null && msg.isFinalAnswer == false
            if ((!msg.isStreaming && !isUnconfirmedResponse) || merged.any { it.id == msg.id }) continue
            val previousId = live.take(index).lastOrNull { previous -> merged.any { it.id == previous.id } }?.id
            val previousIndex = merged.indexOfLast { it.id == previousId }
            merged.add(if (previousIndex >= 0) previousIndex + 1 else merged.size, msg)
        }
        return merged
    }

    /** 只补已消费的真实输入，按持久化的相邻消息定位，不按时间戳重排流式响应或插话。 */
    private fun restoreInputOrder(live: List<ChatMessage>, persisted: List<ChatMessage>): List<ChatMessage> {
        val merged = live.toMutableList()
        val knownIds = live.mapTo(mutableSetOf()) { it.id }
        for ((index, input) in persisted.withIndex()) {
            if (input.role != ChatRole.User || input.pendingSteering || input.hiddenFromUi ||
                input.blocks.none { it is ChatBlock.Text || it is ChatBlock.File } ||
                input.id in knownIds) continue
            val previousId = persisted.subList(0, index).lastOrNull { it.id in knownIds }?.id
            val previousIndex = merged.indexOfLast { it.id == previousId }
            val nextId = persisted.subList(index + 1, persisted.size).firstOrNull { it.id in knownIds }?.id
            val nextIndex = merged.indexOfFirst { it.id == nextId }
            merged.add(when {
                previousIndex >= 0 -> previousIndex + 1
                nextIndex >= 0 -> nextIndex
                else -> merged.size
            }, input)
            knownIds.add(input.id)
        }
        return merged
    }

    private fun MutableList<ChatMessage>.putMessage(message: ChatMessage) {
        val index = indexOfFirst { it.id == message.id }
        if (index >= 0) this[index] = message else add(message)
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun ConversationState.ensureStreamingPlaceholder(
        currentMessages: MutableList<ChatMessage>,
        event: CoreEvent,
    ): ChatMessage {
        val responseId = event.messageId?.takeIf { it.isNotBlank() }
        if (responseId != null) {
            currentMessages.firstOrNull { it.id == responseId && it.role == ChatRole.Assistant }?.let { return it }
            // 响应边界来自 Core；新请求不能继续写入上一个请求的占位消息。
            currentMessages.indices.forEach { index ->
                val message = currentMessages[index]
                if (message.isStreaming) currentMessages[index] = message.copy(isStreaming = false)
            }
        } else {
            // 兼容旧事件协议，只有无响应 ID 的事件才使用原来的尾部占位逻辑。
            val last = currentMessages.lastOrNull()
            if (last != null && last.role == ChatRole.Assistant &&
                (last.isStreaming || this.conversation.status == ConversationStatus.WaitingUser)) return last
        }
        return ChatMessage(
            id = responseId ?: "streaming_${Uuid.random()}",
            conversationId = this.conversation.id,
            role = ChatRole.Assistant,
            blocks = emptyList(),
            createdAt = currentTimeMillis(),
            completedAt = null,
            parentMessageId = null,
            model = this.conversation.modelId,
            agent = this.conversation.agent,
            isStreaming = true,
            error = null,
            turnId = event.payload["turnId"],
            isFinalAnswer = if (responseId != null) false else null,
            workStartedAt = event.payload["workStartedAt"]?.toLongOrNull(),
            workCompletedAt = event.payload["workCompletedAt"]?.toLongOrNull(),
        )
    }

    private fun ConversationState.withToolCalled(
        toolCallId: String,
        name: String,
        args: String,
        event: CoreEvent,
    ): ConversationState {
        val input = parseToolArgs(args)
        val currentMessages = this.messages.toMutableList()
        val placeholder = ensureStreamingPlaceholder(currentMessages, event)
        val updatedBlocks = placeholder.blocks.toMutableList()

        val calls = updatedBlocks.filterIsInstance<ChatBlock.ToolCall>()
        // 已知调用 ID 只能匹配自身或尚未绑定 ID 的流式占位；同名并行调用不得互相覆盖。
        val existing = calls.firstOrNull { toolCallId.isNotBlank() && it.id == "tool_$toolCallId" }
            ?: calls.lastOrNull {
                (toolCallId.isBlank() || it.id.startsWith("${placeholder.id}_tool_")) &&
                    it.name == name && (it.state is ToolCallState.Running || it.state is ToolCallState.Pending)
            }
            ?: calls.lastOrNull {
                it.id.startsWith("${placeholder.id}_tool_") && it.name == "tool" &&
                    (it.state is ToolCallState.Running || it.state is ToolCallState.Pending)
            }

        val prevInput = (existing?.state as? ToolCallState.Running)?.input
        val effectiveInput = if (input.isEmpty() && !prevInput.isNullOrEmpty()) prevInput else input
        if (existing != null) {
            val blockId = if (toolCallId.isNotBlank()) "tool_$toolCallId" else existing.id
            updatedBlocks[updatedBlocks.lastIndexOf(existing)] = existing.copy(
                id = blockId,
                name = name,
                state = ToolCallState.Running(input = effectiveInput)
            )
        } else {
            val blockId = "tool_${toolCallId.ifBlank { Uuid.random().toString() }}"
            updatedBlocks.add(
                ChatBlock.ToolCall(id = blockId, name = name, state = ToolCallState.Running(input = effectiveInput))
            )
        }

        currentMessages.putMessage(placeholder.copy(blocks = updatedBlocks))
        val targetStatus = if (this.conversation.status == ConversationStatus.WaitingUser) {
            ConversationStatus.WaitingUser
        } else {
            ConversationStatus.Working
        }
        return this.copy(
            conversation = this.conversation.copy(status = targetStatus),
            messages = currentMessages
        )
    }

    private fun ConversationState.withToolResult(
        toolCallId: String,
        name: String,
        output: String,
        isError: Boolean,
        event: CoreEvent,
    ): ConversationState {
        val currentMessages = this.messages.toMutableList()

        var found = false
        for (i in currentMessages.indices.reversed()) {
            val msg = currentMessages[i]
            if (msg.role != ChatRole.Assistant || (event.messageId != null && msg.id != event.messageId)) continue
            val blocks = msg.blocks.toMutableList()
            val target = (if (toolCallId.isNotBlank()) {
                blocks.filterIsInstance<ChatBlock.ToolCall>()
                    .firstOrNull { it.id == "tool_$toolCallId" }
            } else null)
                ?: blocks.filterIsInstance<ChatBlock.ToolCall>()
                    .lastOrNull { toolCallId.isBlank() && it.name == name && (it.state is ToolCallState.Running || it.state is ToolCallState.Pending) }

            if (target != null) {
                val prevInput = when (val s = target.state) {
                    is ToolCallState.Running -> s.input
                    is ToolCallState.Pending -> s.input
                    is ToolCallState.Completed -> s.input
                    is ToolCallState.Failed -> s.input
                }
                val state = if (isError) {
                    ToolCallState.Failed(input = prevInput, error = output)
                } else {
                    ToolCallState.Completed(input = prevInput, output = output)
                }
                blocks[blocks.lastIndexOf(target)] = target.copy(state = state)
                currentMessages[i] = msg.copy(blocks = blocks)
                found = true
                break
            }
        }

        if (!found) {
            val placeholder = ensureStreamingPlaceholder(currentMessages, event)
            val updatedBlocks = placeholder.blocks.toMutableList()
            val target = (if (toolCallId.isNotBlank()) {
                updatedBlocks.filterIsInstance<ChatBlock.ToolCall>()
                    .firstOrNull { it.id == "tool_$toolCallId" }
            } else null)
                ?: updatedBlocks.filterIsInstance<ChatBlock.ToolCall>()
                    .lastOrNull { toolCallId.isBlank() && it.name == name && (it.state is ToolCallState.Running || it.state is ToolCallState.Pending) }

            if (target != null) {
                val prevInput = when (val s = target.state) {
                    is ToolCallState.Running -> s.input
                    is ToolCallState.Pending -> s.input
                    is ToolCallState.Completed -> s.input
                    is ToolCallState.Failed -> s.input
                }
                val state = if (isError) {
                    ToolCallState.Failed(input = prevInput, error = output)
                } else {
                    ToolCallState.Completed(input = prevInput, output = output)
                }
                updatedBlocks[updatedBlocks.lastIndexOf(target)] = target.copy(state = state)
            }
            currentMessages.putMessage(placeholder.copy(blocks = updatedBlocks))
        }

        val targetStatus = if (this.conversation.status == ConversationStatus.WaitingUser) {
            ConversationStatus.WaitingUser
        } else {
            ConversationStatus.Working
        }
        return this.copy(
            conversation = this.conversation.copy(status = targetStatus),
            messages = currentMessages
        )
    }

    private fun parseToolArgs(args: String): Map<String, String> = ToolArgParser.parse(args)

    private fun CoreEvent.toLastRequestUsage(): LastRequestUsage = LastRequestUsage(
        inputTokens = payload["inputTokens"]?.toLongOrNull() ?: 0,
        outputTokens = payload["outputTokens"]?.toLongOrNull() ?: 0,
        cachedTokens = payload["cachedTokens"]?.toLongOrNull()
    )
}

/** 渐进迁移类型别名，确保历史引用向后兼容。 */
typealias SnapshotReducer = ConversationStateReducer
