package xyz.mederi.core.contract

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import xyz.mederi.core.contract.dto.ConversationSnapshot
import xyz.mederi.core.contract.dto.MessagesPage
import xyz.mederi.core.contract.models.ChatBlock
import xyz.mederi.core.contract.models.ChatMessage
import xyz.mederi.core.contract.models.ChatRole
import xyz.mederi.core.contract.models.ConversationStatus
import xyz.mederi.core.contract.models.CoreEvent
import xyz.mederi.core.contract.models.CoreEventType
import xyz.mederi.core.contract.models.QuestionRequest
import xyz.mederi.core.contract.models.PlanApprovalRequest
import xyz.mederi.core.contract.models.PlanSubtaskItem
import xyz.mederi.core.contract.models.TodoItem
import xyz.mederi.core.contract.models.TodoWireItem
import xyz.mederi.core.contract.models.ToolCallState
import xyz.mederi.currentTimeMillis

/**
 * 把契约层 [CoreEvent] 应用到 [ConversationSnapshot] 上的纯逻辑（跨平台）。
 *
 * 同一份聚合逻辑服务两端：
 * - JVM（desktop 进程内 / server 进程内）：MederiEventAggregator 提供 core 数据源
 * - wasmJs（浏览器）：ServerAiCore 提供 HTTP 数据源
 *
 * 规则（从 jvm 桥接层原样迁移）：
 * - [CoreEventType.MESSAGE_DELTA] 追加到当前 streaming Assistant 占位消息
 * - [CoreEventType.MESSAGE_COMPLETED] 标记占位消息完成，再回查落库数据对齐
 * - [CoreEventType.MESSAGE_ERROR] 设置 errorMessage 并回查落库数据
 * - [CoreEventType.TOOL_CALLED] / [CoreEventType.TOOL_RESULT] 维护 ToolCall block 状态机
 * - APPROVAL / QUESTION / PLAN_APPROVAL 系列事件维护 pending 状态
 */
object SnapshotReducer {

    /**
     * 应用单个事件，返回**要发出的快照序列**（1 或 2 个）。
     *
     * MESSAGE_COMPLETED 先发出"完成状态"快照（按钮立即变化），再回查 [refreshPage]
     * 落库数据发出对齐后的最终快照；MESSAGE_ERROR 同理回查但只发最终一个。
     * 其余事件只发一个。回查失败（[refreshPage] 返回 null 或抛异常）时跳过对齐。
     */
    suspend fun applyWithRefresh(
        snapshot: ConversationSnapshot,
        event: CoreEvent,
        refreshPage: suspend () -> MessagesPage?,
    ): List<ConversationSnapshot> = when (event.type) {
        CoreEventType.MESSAGE_COMPLETED -> {
            // 1. 先发出完成状态（按钮立即变化），保留流式累积的内容，标记 streaming 结束
            val idle = apply(snapshot, event)
            listOf(idle) + listOfNotNull(
                runCatching { refreshPage() }.getOrNull()?.let { page ->
                    idle.copy(
                        messages = page.messages,
                        tokenUsage = page.tokenUsage,
                        contextUsedTokens = page.contextUsedTokens,
                    )
                }
            )
        }

        CoreEventType.SESSION_UPDATED -> {
            val updated = apply(snapshot, event)
            // 新 turn 开始（包含 durable-first 用户消息刚落库）：
            // 立即回查对齐落库消息，使快照立刻包含刚发送的用户消息
            val page = runCatching { refreshPage() }.getOrNull()
            listOf(page?.let { p ->
                updated.copy(messages = p.messages, tokenUsage = p.tokenUsage, contextUsedTokens = p.contextUsedTokens)
            } ?: updated)
        }

        CoreEventType.MESSAGE_ERROR -> {
            val errored = apply(snapshot, event)
            // 回查落库数据对齐（失败保持错误快照），只发最终一个
            val page = runCatching { refreshPage() }.getOrNull()
            listOf(page?.let { p ->
                errored.copy(messages = p.messages, tokenUsage = p.tokenUsage, contextUsedTokens = p.contextUsedTokens)
            } ?: errored)
        }

        else -> listOf(apply(snapshot, event))
    }

    /**
     * 应用单个事件到快照（不含落库回查）。
     */
    fun apply(snapshot: ConversationSnapshot, event: CoreEvent): ConversationSnapshot = when (event.type) {
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
            pendingPlanApproval = null
        )

        CoreEventType.MESSAGE_DELTA -> snapshot.withDelta(event.payload).copy(statusHint = null, errorIsStreamInterrupted = false)

        CoreEventType.MESSAGE_COMPLETED -> {
            val now = currentTimeMillis()
            // 流式传输异常（如"流式连接提前中断"）随完成事件以 ErrorRecord payload 到达
            // （error/errorId/fullDiagnostic）：内容照常落库展示，错误统一进 ErrorBoard
            // （输入框上方），statusHint 只保留给运转过程提示（限流重试），不再承载警告。
            // failureMode=PREMATURE_CLOSE 标记断流 → UI 显示"继续"按钮（重发 Continue 续写）。
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

        CoreEventType.MESSAGE_ERROR -> snapshot.copy(
            conversation = snapshot.conversation.copy(status = ConversationStatus.Error),
            messages = snapshot.messages.map { msg ->
                if (msg.isStreaming) msg.copy(isStreaming = false) else msg
            },
            errorMessage = event.payload["error"],
            errorId = event.payload["errorId"],
            errorDiagnostic = event.payload["fullDiagnostic"],
            errorIsStreamInterrupted = event.payload["failureMode"] == "PREMATURE_CLOSE",
            statusHint = null
        )

        CoreEventType.STATUS -> {
            // 环境态状态事件：过程状态提示，不碰会话状态机。
            // 其他 scope 的 STATUS 事件忽略（当前只有 provider 一个 scope）
            if (event.payload["scope"] == "provider" && event.payload["code"] == "RETRYING") {
                val attempt = event.payload["attempt"] ?: "?"
                val max = event.payload["maxAttempts"] ?: "?"
                // message 是 Core 传来的真实错误原因（如 "Insufficient Balance"、"Engine overloaded"），
                // 直接保留到 statusHint，由 UI 层决定怎么渲染，不在这里写死文案。
                val serverMsg = event.payload["message"]?.takeIf { it.isNotBlank() }
                val delayMs = event.payload["delayMs"]?.toLongOrNull()
                val retryAt = delayMs?.let { xyz.mederi.currentTimeMillis() + it }
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

        CoreEventType.QUESTION_REQUESTED -> {
            val rawQuestions = event.payload["questions"] ?: "[]"
            val questions = runCatching {
                questionWireJson.decodeFromString(questionListSerializer, rawQuestions)
            }.getOrElse { ex ->
                xyz.mederi.ui.DebugLog.error("SnapshotReducer", "Failed to decode questions: ${ex.message}", ex)
                emptyList()
            }
            snapshot.copy(
                conversation = snapshot.conversation.copy(status = ConversationStatus.WaitingUser),
                // 交互挂起 = 当前 step 生成完毕，暂停等待用户，不再属于流式中
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

        CoreEventType.QUESTION_RESOLVED -> snapshot.copy(
            conversation = snapshot.conversation.copy(status = ConversationStatus.Working),
            pendingQuestion = null
        )

        CoreEventType.PLAN_APPROVAL_REQUESTED -> {
            val subtasksJson = event.payload["subtasks"]
            val subtasks = if (!subtasksJson.isNullOrBlank()) {
                runCatching {
                    Json { ignoreUnknownKeys = true }.decodeFromString<List<PlanSubtaskItem>>(subtasksJson)
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
                // 审批挂起 = 当前计划生成完毕，暂停等待用户审批，不再属于流式中
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
            val todos = event.payload["todos"]?.let(::decodeTodoProjection)
            val planId = event.payload["planId"]
            val action = event.payload["action"]
            val subtasksJson = event.payload["subtasks"]
            val newSubtasks = if (!subtasksJson.isNullOrBlank()) {
                runCatching {
                    Json { ignoreUnknownKeys = true }.decodeFromString<List<PlanSubtaskItem>>(subtasksJson)
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

            val withTodos = if (todos != null) snapshot.copy(todos = todos) else snapshot
            withTodos.copy(planApprovals = updatedApprovals)
        }

        CoreEventType.PLAN_APPROVAL_RESOLVED -> {
            val planId = event.payload["planId"]
            val approved = event.payload["approved"]?.toBooleanStrictOrNull() ?: true
            val updatedList = snapshot.planApprovals.map {
                if (it.id == planId) it.copy(status = if (approved) "APPROVED" else "REJECTED") else it
            }
            snapshot.copy(
                conversation = snapshot.conversation.copy(status = ConversationStatus.Working),
                pendingPlanApproval = null,
                planApprovals = updatedList
            )
        }

        // 浏览器任务事件不改变会话快照（UI 通过事件 payload 直接消费：browser=="jcef" 时自动展开面板）
        CoreEventType.BROWSER_TASK_STARTED,
        CoreEventType.BROWSER_TASK_STEP,
        CoreEventType.BROWSER_TASK_COMPLETED,
        CoreEventType.BROWSER_TASK_ERROR,
        CoreEventType.BROWSER_TASK_STOPPED -> snapshot

        // 子代理生命周期事件不改变会话快照（子代理状态由独立的 SubagentTracker 聚合，
        // ViewModel 持缓存；会话快照只反映主代理视角）
        CoreEventType.SUBAGENT_STARTED,
        CoreEventType.SUBAGENT_COMPLETED,
        CoreEventType.SUBAGENT_ERROR,
        CoreEventType.SUBAGENT_STOPPED -> snapshot
    }

    // ------------------------------------------------------------------
    // 私有：Todo 投影解码（TODO_UPDATED / PLAN_PROGRESS 共用，无状态整体替换）
    // ------------------------------------------------------------------

    private val todoWireJson = Json { ignoreUnknownKeys = true }
    private val todoWireSerializer = ListSerializer(TodoWireItem.serializer())

    private val questionWireJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }
    private val questionListSerializer = ListSerializer(QuestionRequest.Question.serializer())

    /**
     * 事件 payload → 契约 Todo 列表（与 core 侧唯一编码点 encodeTodos 的 JSON 形状对齐）。
     * id 是渲染 key：在投影边界按序合成（"t$index"），不持久化不交换。
     * 解码失败返回 null（调用方丢弃该事件、保留先前快照）——禁止字符串手术挽救畸形数据。
     * 空列表是合法值（= 清空 todo）。
     */
    private fun decodeTodoProjection(raw: String): List<TodoItem>? = runCatching {
        todoWireJson.decodeFromString(todoWireSerializer, raw)
            .mapIndexed { i, w -> TodoItem(id = "t$i", content = w.content, status = w.status) }
    }.getOrNull()

    // ------------------------------------------------------------------
    // 私有：消息/block 状态机（与原 jvm MederiEventAggregator 逐行等价）
    // ------------------------------------------------------------------

    /**
     * 在当前 streaming Assistant 占位消息上追加 delta。
     * 如果不存在或最后一条不是 streaming Assistant，则新建一条占位消息。
     */
    private fun ConversationSnapshot.withDelta(payload: Map<String, String>): ConversationSnapshot {
        val type = payload["type"] ?: "text"
        val content = payload["content"] ?: ""
        val currentMessages = this.messages.toMutableList()

        val placeholder = ensureStreamingPlaceholder(currentMessages)
        val updatedBlocks = placeholder.blocks.toMutableList()

        when (type) {
            // 块按事件到达顺序排列（最后一块 = 最近活动）：只有当最后一块是同类型时才合并，
            // 否则新开块。跨轮的 reasoning/text 各自成块，deriveTurnStatus 才能按"最近活动块"判态。
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
                // 增量阶段：LLM 正在流式生成 tool call args。
                // content 可能是片段（非完整 JSON），parseToolArgs 失败时 input 留空，
                // 等待 TOOL_CALLED（带完整 args）覆盖；不设 output（真实 output 由 TOOL_RESULT 提供）。
                val name = payload["name"] ?: "tool"
                val input = if (content.isBlank()) emptyMap() else parseToolArgs(content)
                val lastBlock = updatedBlocks.lastOrNull()
                if (lastBlock is ChatBlock.ToolCall && lastBlock.state is ToolCallState.Running) {
                    updatedBlocks[updatedBlocks.lastIndexOf(lastBlock)] = lastBlock.copy(
                        name = name,
                        state = ToolCallState.Running(input = input)
                    )
                } else {
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

        return this.copy(
            conversation = this.conversation.copy(status = ConversationStatus.Working),
            messages = currentMessages
        )
    }

    /**
     * 取得（必要时创建）当前 streaming Assistant 占位消息，并从 [currentMessages] 中移除以便后续改完再加回。
     */
    @OptIn(ExperimentalUuidApi::class)
    private fun ConversationSnapshot.ensureStreamingPlaceholder(currentMessages: MutableList<ChatMessage>): ChatMessage {
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

    /**
     * 处理 TOOL_CALLED 事件：LLM 已生成完整 args，工具开始执行。
     *
     * 用完整 args 更新 input（可靠解析出 path/content 等），状态置 Running。
     * 匹配上一个 delta 增量创建的占位 block；找不到则新建。
     */
    private fun ConversationSnapshot.withToolCalled(
        toolCallId: String,
        name: String,
        args: String
    ): ConversationSnapshot {
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

        // 防御：args 解析失败（ToolArgParser 返回空 map）时保留已有 block 的非空 input，
        // 避免"坏数据覆盖好数据"——上游一旦再出序列化缺陷，已显示的路径/命令不丢。
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

    /**
     * 处理 TOOL_RESULT 事件：工具执行完成。
     *
     * 填入真实 output，状态改 Completed/Failed。保留之前的 input。
     * 按 toolCallId 精确匹配 block（TOOL_CALLED 时 block id 即 "tool_{toolCallId}"），
     * 否则回退到最后一个 Running/Pending 的同名 block。
     */
    private fun ConversationSnapshot.withToolResult(
        toolCallId: String,
        name: String,
        output: String,
        isError: Boolean
    ): ConversationSnapshot {
        val currentMessages = this.messages.toMutableList()
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
                is ToolCallState.Pending -> emptyMap()
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

    /**
     * 解析审批事件的工具参数 JSON。
     * 工具参数是扁平的键值对对象（如 {"path":"...","content":"..."}）。
     * 走 [ToolArgParser] 宽容解析（容忍数字/布尔等非字符串值，避免整条参数丢失）；
     * 非对象结构或解析失败返回空 map，不崩溃（审批卡片入参区显示为空即可）。
     */
    private fun parseToolArgs(args: String): Map<String, String> = ToolArgParser.parse(args)
}
