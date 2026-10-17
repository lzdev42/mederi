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
 * - [CoreEventType.MESSAGE_DELTA] 追加到当前 streaming Assistant 占位消息
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
                    messages = p.messages,
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
    fun apply(snapshot: ConversationState, event: CoreEvent): ConversationState = when (event.type) {
        CoreEventType.SESSION_CREATED -> snapshot

        CoreEventType.SESSION_UPDATED -> snapshot.copy(
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

        CoreEventType.MESSAGE_DELTA -> snapshot.withDelta(event.payload).copy(statusHint = null, errorIsStreamInterrupted = false)

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

            val completed = snapshot.copy(
                conversation = snapshot.conversation.copy(status = ConversationStatus.Idle),
                messages = snapshot.messages.map { msg ->
                    val isTarget = if (diffMessageId != null) msg.id == diffMessageId else msg.isStreaming
                    val updatedDiff = if (isTarget && diffSummary != null) diffSummary else msg.turnDiffSummary
                    if (msg.isStreaming) msg.copy(isStreaming = false, completedAt = now, turnDiffSummary = updatedDiff)
                    else if (isTarget && diffSummary != null) msg.copy(turnDiffSummary = updatedDiff)
                    else msg
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
            val fs = event.payload["finalStatus"]
            val status = when (fs) {
                "IDLE" -> ConversationStatus.Idle
                else -> ConversationStatus.Error
            }
            snapshot.copy(
                conversation = snapshot.conversation.copy(status = status),
                messages = snapshot.messages.map { msg ->
                    if (msg.isStreaming) msg.copy(isStreaming = false) else msg
                },
                errorMessage = event.payload["error"],
                errorId = event.payload["errorId"],
                errorDiagnostic = event.payload["fullDiagnostic"],
                errorIsStreamInterrupted = event.payload["failureMode"] == "PREMATURE_CLOSE",
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
            } else snapshot
        }

        CoreEventType.TOOL_CALLED -> snapshot.withToolCalled(
            toolCallId = event.payload["toolCallId"] ?: "",
            name = event.payload["tool"] ?: "tool",
            args = event.payload["args"] ?: ""
        ).copy(statusHint = null)

        CoreEventType.TOOL_RESULT -> snapshot.withToolResult(
            toolCallId = event.payload["toolCallId"] ?: "",
            name = event.payload["tool"] ?: "tool",
            output = event.payload["output"] ?: "",
            isError = event.payload["isError"] == "true"
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
                planContent = event.payload["planContent"],
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
                    "approved" -> "APPROVED"
                    "subtask-started" -> "IN_PROGRESS"
                    else -> null
                }
                snapshot.planApprovals.map { p ->
                    if (p.id == planId) {
                        p.copy(
                            status = newStatus ?: p.status,
                            subtasks = newSubtasks ?: p.subtasks
                        )
                    } else p
                }
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
                steps = emptyList()
            )
            snapshot.copy(
                browserAutomation = updated,
                activeDockPanel = if (browser == "jcef") RightDockPanel.BROWSER else snapshot.activeDockPanel
            )
        }

        CoreEventType.BROWSER_TASK_STEP -> {
            val current = snapshot.browserAutomation ?: BrowserAutomationUiState(isAutomating = true, isPanelOpen = true)
            val updatedSteps = parseBrowserStep(event, current.steps)
            snapshot.copy(browserAutomation = current.copy(steps = updatedSteps))
        }

        CoreEventType.BROWSER_TASK_COMPLETED,
        CoreEventType.BROWSER_TASK_ERROR,
        CoreEventType.BROWSER_TASK_STOPPED -> {
            val current = snapshot.browserAutomation
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
                        completedAtMillis = currentTimeMillis(),
                        steps = updatedSteps
                    )
                )
            } else snapshot
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
    }

    // ------------------------------------------------------------------
    // 私有：Todo 投影解码（TODO_UPDATED 使用，无状态整体替换）
    // ------------------------------------------------------------------

    private val todoWireJson = Json { ignoreUnknownKeys = true }
    private val todoWireSerializer = ListSerializer(TodoWireItem.serializer())
    private val planSubtasksSerializer = ListSerializer(PlanSubtaskItem.serializer())

    private val questionWireJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
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

    private fun ConversationState.withDelta(payload: Map<String, String>): ConversationState {
        val type = payload["type"] ?: "text"
        val content = payload["content"] ?: ""
        val currentMessages = this.messages.toMutableList()

        val placeholder = ensureStreamingPlaceholder(currentMessages)
        val updatedBlocks = placeholder.blocks.toMutableList()

        when (type) {
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

        currentMessages.add(placeholder.copy(blocks = updatedBlocks))

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

    @OptIn(ExperimentalUuidApi::class)
    private fun ConversationState.ensureStreamingPlaceholder(currentMessages: MutableList<ChatMessage>): ChatMessage {
        val last = currentMessages.lastOrNull()
        if (last != null && last.role == ChatRole.Assistant && (last.isStreaming || this.conversation.status == ConversationStatus.WaitingUser)) {
            currentMessages.removeLast()
            return last
        }
        return ChatMessage(
            id = "streaming_${Uuid.random()}",
            conversationId = this.conversation.id,
            role = ChatRole.Assistant,
            blocks = emptyList(),
            createdAt = currentTimeMillis(),
            completedAt = null,
            parentMessageId = null,
            model = this.conversation.modelId,
            agent = this.conversation.agent,
            isStreaming = true,
            error = null
        )
    }

    private fun ConversationState.withToolCalled(
        toolCallId: String,
        name: String,
        args: String
    ): ConversationState {
        val input = parseToolArgs(args)
        val currentMessages = this.messages.toMutableList()
        val placeholder = ensureStreamingPlaceholder(currentMessages)
        val updatedBlocks = placeholder.blocks.toMutableList()

        val existing = (if (toolCallId.isNotBlank()) {
            updatedBlocks.filterIsInstance<ChatBlock.ToolCall>()
                .firstOrNull { it.id == "tool_$toolCallId" }
        } else null)
            ?: updatedBlocks.filterIsInstance<ChatBlock.ToolCall>()
                .lastOrNull { it.name == name && (it.state is ToolCallState.Running || it.state is ToolCallState.Pending) }
            ?: updatedBlocks.filterIsInstance<ChatBlock.ToolCall>()
                .lastOrNull { it.state is ToolCallState.Running || it.state is ToolCallState.Pending }

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

        currentMessages.add(placeholder.copy(blocks = updatedBlocks))
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
        isError: Boolean
    ): ConversationState {
        val currentMessages = this.messages.toMutableList()

        var found = false
        for (i in currentMessages.indices.reversed()) {
            val msg = currentMessages[i]
            if (msg.role != ChatRole.Assistant) continue
            val blocks = msg.blocks.toMutableList()
            val target = (if (toolCallId.isNotBlank()) {
                blocks.filterIsInstance<ChatBlock.ToolCall>()
                    .firstOrNull { it.id == "tool_$toolCallId" }
            } else null)
                ?: blocks.filterIsInstance<ChatBlock.ToolCall>()
                    .lastOrNull { it.name == name && (it.state is ToolCallState.Running || it.state is ToolCallState.Pending) }

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
            val placeholder = ensureStreamingPlaceholder(currentMessages)
            val updatedBlocks = placeholder.blocks.toMutableList()
            val target = (if (toolCallId.isNotBlank()) {
                updatedBlocks.filterIsInstance<ChatBlock.ToolCall>()
                    .firstOrNull { it.id == "tool_$toolCallId" }
            } else null)
                ?: updatedBlocks.filterIsInstance<ChatBlock.ToolCall>()
                    .lastOrNull { it.name == name && (it.state is ToolCallState.Running || it.state is ToolCallState.Pending) }

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
            currentMessages.add(placeholder.copy(blocks = updatedBlocks))
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
