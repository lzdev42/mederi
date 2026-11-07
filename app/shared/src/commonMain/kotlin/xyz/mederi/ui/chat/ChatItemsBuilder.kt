package xyz.mederi.ui

import xyz.mederi.core.contract.dto.ConversationSnapshot
import xyz.mederi.core.contract.models.ChatBlock
import xyz.mederi.core.contract.models.ChatMessage
import xyz.mederi.core.contract.models.ChatRole
import xyz.mederi.core.contract.models.ToolCallState
import xyz.mederi.core.contract.models.ToolCallUi
import xyz.mederi.util.ArtifactParser

/** 子代理工具名集合（computeChatItems 区分主代理工具与子代理调用）。 */
val SUBAGENT_TOOL_NAMES = setOf("subagent")

/** 计划创建/更新工具名集合（write_plan 为核心工具名，兼容历史/别名 create_plan）。 */
val PLAN_CREATION_TOOL_NAMES = setOf("write_plan", "create_plan")


/**
 * 展平后的聊天列表派生算法（纯函数，从 WorkspaceViewModel 抽出的展示逻辑）：
 * snapshot 消息列表 → List<ChatListItem>，View 零逻辑。
 *
 * @param isWorking 当前会话是否处于 Working（由 VM 传入，原实现读取 VM.isWorking）
 * @param snapshot 当前会话快照（planApprovals / pendingPlanApproval / conversation.directory 等派生数据源）
 */
fun computeChatItems(
    msgs: List<ChatMessage>,
    isWorking: Boolean,
    snapshot: ConversationSnapshot?,
    isSending: Boolean = false,
    pendingStartedAt: Long? = null,
): List<ChatListItem> {
    val result = mutableListOf<ChatListItem>()

    // role 为 null 的段是压缩标记（SUMMARY），独立成卡片
    val turns = mutableListOf<Pair<ChatRole?, List<ChatMessage>>>()
    var currentAssistant = mutableListOf<ChatMessage>()
    for (msg in msgs) {
        if (msg.role == ChatRole.Summary) {
            // SUMMARY（压缩标记）留库不动、AI 视图正常取用，但 UI 不渲染其内容。
            // 仍切断 Assistant 轮次边界（防止前后轮次合并）。
            if (currentAssistant.isNotEmpty()) {
                turns.add(ChatRole.Assistant to currentAssistant.toList())
                currentAssistant = mutableListOf()
            }
            continue
        }
        // 真实用户消息（含有用户文本或文件）：纯中间工具结果消息（blocks 为空）绝不切断 Assistant 轮次
        val isRealUser = msg.role == ChatRole.User && msg.blocks.any {
            it is ChatBlock.Text || it is ChatBlock.File
        }
        if (isRealUser) {
            if (currentAssistant.isNotEmpty()) {
                turns.add(ChatRole.Assistant to currentAssistant.toList())
                currentAssistant = mutableListOf()
            }
            turns.add(ChatRole.User to listOf(msg))
        } else if (msg.role == ChatRole.Assistant) {
            currentAssistant.add(msg)
        }
    }
    if (currentAssistant.isNotEmpty()) {
        turns.add(ChatRole.Assistant to currentAssistant.toList())
    }

    val lastAssistantTurn = turns.lastOrNull { it.first == ChatRole.Assistant }?.second
    val lastResponseByWork = msgs.filter { it.role == ChatRole.Assistant && it.turnId != null }
        .associate { it.turnId to it.id }
    // 执行工作ID可跨插话延续，但显示段必须以真实用户消息为边界，禁止向上合并。
    val hasRunningSubagents = snapshot?.subagents?.any { it.status == "RUNNING" } == true
    for ((turnIndex, turnPair) in turns.withIndex()) {
        val (role, turnMessages) = turnPair
        val isUser = role == ChatRole.User
        if (isUser) {
            for (msg in turnMessages) {
                val messageImages = msg.blocks.filterIsInstance<ChatBlock.File>()
                    .filter { isImageBlock(it) }
                    .map { it.url }
                val textBlocks = msg.blocks.filterIsInstance<ChatBlock.Text>().filter { it.text.isNotBlank() }
                if (textBlocks.isNotEmpty()) {
                    textBlocks.forEachIndexed { blockIndex, block ->
                        val parsedEvent = parseEventMessage(block.text)
                        if (parsedEvent != null) {
                            result.add(
                                ChatListItem.EventMessageCard(
                                    key = "${msg.id}_${block.id}",
                                    eventType = parsedEvent.eventType,
                                    agentId = parsedEvent.agentId,
                                    status = parsedEvent.status,
                                    role = parsedEvent.role,
                                    subtaskInfo = parsedEvent.subtaskInfo,
                                    reportPath = parsedEvent.reportPath,
                                    taskType = parsedEvent.taskType,
                                    summary = parsedEvent.summary,
                                    isTurnStart = true,
                                    messageId = msg.id,
                                    createdAt = msg.createdAt,
                                )
                            )
                        } else {
                            result.add(
                                ChatListItem.TextMessage(
                                    key = "${msg.id}_${block.id}",
                                    isUser = true,
                                    isStreaming = false,
                                    isActiveAssistant = false,
                                    text = block.text,
                                    partId = block.id,
                                    conversationId = msg.conversationId,
                                    images = if (blockIndex == 0) messageImages else emptyList(),
                                    isTurnStart = true,
                                    messageId = msg.id,
                                    createdAt = msg.createdAt,
                                )
                            )
                        }
                    }
                } else if (messageImages.isNotEmpty()) {
                    result.add(
                        ChatListItem.TextMessage(
                            key = "${msg.id}_img",
                            isUser = true,
                            isStreaming = false,
                            isActiveAssistant = false,
                            text = "",
                            partId = "img",
                            conversationId = msg.conversationId,
                            images = messageImages,
                            isTurnStart = true,
                            messageId = msg.id,
                            createdAt = msg.createdAt,
                        )
                    )
                }
            }
            continue
        }

        // Assistant 轮次
        val isStreaming = turnMessages.any { it.isStreaming }
        val isLastMsgTail = turnMessages.lastOrNull()?.id == msgs.lastOrNull()?.id
        val isActiveAssistant = isStreaming ||
            ((isWorking || hasRunningSubagents) && turnMessages == lastAssistantTurn && isLastMsgTail)

        var turnHasFirstItem = false

        // 预解析计划审批卡片匹配
        val allToolCalls = turnMessages.flatMap { it.blocks.filterIsInstance<ChatBlock.ToolCall>() }
            .map { toToolCallUi(it, snapshot) }
        val createPlanCall = allToolCalls.find { it.name in PLAN_CREATION_TOOL_NAMES }
        val (planIdFromTool, planTitleFromTool) = when (val s = createPlanCall?.state) {
            is ToolCallState.Completed -> {
                val idFromInput = s.input["planId"]
                val idFromOutput = s.output.let { out ->
                    Regex("""Plan ID:\s*([a-zA-Z0-9_-]+)""").find(out)?.groupValues?.getOrNull(1)
                        ?: Regex("""(plan_[a-f0-9]{8})""").find(out)?.groupValues?.getOrNull(1)
                }
                Pair(idFromInput ?: idFromOutput, s.input["title"])
            }
            is ToolCallState.Running -> Pair(s.input["planId"], s.input["title"])
            else -> Pair(null, null)
        }
        val matchedPlan = snapshot?.planApprovals?.find {
            (planIdFromTool != null && it.id == planIdFromTool) ||
                (!planTitleFromTool.isNullOrBlank() && it.title == planTitleFromTool)
        } ?: if (turnMessages == lastAssistantTurn) {
            snapshot?.pendingPlanApproval?.takeIf { it.status == "PENDING" || it.status == "PENDING_APPROVAL" }
        } else null

        // 按真实自然时序收集本轮的消息块
        val rawChronologicalItems = mutableListOf<ChatListItem>()
        val itemMessageIds = mutableMapOf<String, String>()
        var planApprovalEmitted = false
        var questionCardEmitted = false

        for ((msgIndex, msg) in turnMessages.withIndex()) {
            val firstItemIndex = rawChronologicalItems.size
            val messageImages = msg.blocks.filterIsInstance<ChatBlock.File>()
                .filter { isImageBlock(it) }
                .map { it.url }
            var imagesHandled = false

            for ((blockIndex, block) in msg.blocks.withIndex()) {
                when (block) {
                    is ChatBlock.Reasoning -> {
                        if (block.text.isNotBlank()) {
                            // 没有推理阶段自己的起终点；完整LLM响应耗时不能冒充推理耗时。
                            val durationMs = 0L
                            val isReasoningActive = msg.isStreaming && blockIndex == msg.blocks.indexOfLast {
                                it is ChatBlock.Reasoning || it is ChatBlock.Text || it is ChatBlock.ToolCall
                            }

                            val item = ChatListItem.Reasoning(
                                key = "${msg.id}_${block.id}",
                                text = block.text,
                                isStreaming = msg.isStreaming,
                                isTurnStart = false,
                                durationMs = durationMs,
                                isReasoningActive = isReasoningActive,
                            )
                            rawChronologicalItems.add(item)
                        }
                    }

                    is ChatBlock.Text -> {
                        if (block.text.isNotBlank()) {
                            val parseResult = ArtifactParser.parse(block.text, isStreaming = msg.isStreaming)
                            val artifact = parseResult.artifact
                            if (artifact != null) {
                                if (parseResult.preamble.isNotBlank()) {
                                    val preItem = ChatListItem.TextMessage(
                                        key = "${msg.id}_${block.id}_pre",
                                        isUser = false,
                                        isStreaming = false,
                                        isActiveAssistant = false,
                                        text = parseResult.preamble,
                                        partId = "${block.id}_pre",
                                        conversationId = msg.conversationId,
                                        images = if (!imagesHandled) messageImages else emptyList(),
                                        isTurnStart = false,
                                        messageId = msg.id,
                                        createdAt = msg.createdAt,
                                        assistantFooter = null,
                                    )
                                    rawChronologicalItems.add(preItem)
                                    imagesHandled = true
                                }

                                rawChronologicalItems.add(
                                    ChatListItem.DocumentCard(
                                        key = "${msg.id}_${block.id}_art_${artifact.identifier}",
                                        artifactId = artifact.identifier,
                                        title = artifact.title,
                                        content = artifact.content,
                                        lineCount = artifact.lineCount,
                                        charCount = artifact.charCount,
                                        isCompleted = artifact.isCompleted,
                                        isStreaming = msg.isStreaming && !artifact.isCompleted,
                                        createdAt = msg.createdAt,
                                        isTurnStart = false,
                                    )
                                )

                                if (parseResult.postscript.isNotBlank()) {
                                    val postItem = ChatListItem.TextMessage(
                                        key = "${msg.id}_${block.id}_post",
                                        isUser = false,
                                        isStreaming = msg.isStreaming,
                                        isActiveAssistant = isActiveAssistant,
                                        text = parseResult.postscript,
                                        partId = "${block.id}_post",
                                        conversationId = msg.conversationId,
                                        images = emptyList(),
                                        isTurnStart = false,
                                        messageId = msg.id,
                                        createdAt = msg.createdAt,
                                        assistantFooter = null,
                                    )
                                    rawChronologicalItems.add(postItem)
                                }
                            } else {
                                val textItem = ChatListItem.TextMessage(
                                    key = "${msg.id}_${block.id}",
                                    isUser = false,
                                    isStreaming = msg.isStreaming,
                                    isActiveAssistant = isActiveAssistant,
                                    text = block.text,
                                    partId = block.id,
                                    conversationId = msg.conversationId,
                                    images = if (!imagesHandled) messageImages else emptyList(),
                                    isTurnStart = false,
                                    messageId = msg.id,
                                    createdAt = msg.createdAt,
                                    assistantFooter = null,
                                )
                                rawChronologicalItems.add(textItem)
                                imagesHandled = true
                            }
                        }
                    }

                    is ChatBlock.ToolCall -> {
                        val toolUi = toToolCallUi(block, snapshot)
                        // 派发通知只确认已经成功的 SPAWN；准备中、失败、查询和停止仍走普通工具过程。
                        // subagent 的已知 Error: 协议可能以 Completed 返回，不能据此宣称启动成功。
                        val completed = toolUi.state as? ToolCallState.Completed
                        val isSubagent = toolUi.name in SUBAGENT_TOOL_NAMES && completed != null &&
                            completed.input["action"] in setOf("SPAWN", "SPAWN_RESEARCHER") &&
                            !completed.output.startsWith("Error:")
                        if (isSubagent) {
                            rawChronologicalItems.add(
                                ChatListItem.SubagentCalls(
                                    key = "${msg.id}_${block.id}",
                                    subagents = listOf(toolUi),
                                    isStreaming = msg.isStreaming,
                                    isTurnStart = false,
                                    hasFailed = toolUi.isFailed,
                                )
                            )
                        } else if (toolUi.name == "ask_user" &&
                            (toolUi.state is ToolCallState.Running || toolUi.state is ToolCallState.Pending) &&
                            snapshot?.pendingQuestion != null
                        ) {
                            // ask_user 挂起中：内联问答卡片取代该工具的 ToolCalls 行（消除"等待用户回答…"重复标签）；
                            // Running = 实时事件流中工具正在跑；Pending = store 重建（切走切回 / 重启）时工具无 result。
                            // 回答后（pendingQuestion 清空）工具出参 → 非 Running/Pending，回归正常 ToolCalls 行。
                            // 单轮内仅发射一次，防止多 toolCall 或时序分裂导致重复卡片。
                            if (!questionCardEmitted) {
                                rawChronologicalItems.add(
                                    ChatListItem.QuestionCard(
                                        key = "${msg.id}_${block.id}_q",
                                        request = snapshot.pendingQuestion,
                                        isTurnStart = false,
                                    )
                                )
                                questionCardEmitted = true
                            }
                        } else {
                            val displayCalls = expandBatchToolCall(toolUi, snapshot)
                            if (displayCalls.isNotEmpty()) rawChronologicalItems.add(
                                ChatListItem.ToolCalls(
                                    key = "${msg.id}_${block.id}",
                                    toolCalls = displayCalls,
                                    isStreaming = msg.isStreaming,
                                    isTurnStart = false,
                                    toolSummary = buildToolSummary(displayCalls),
                                    hasFailedTool = displayCalls.any { it.isFailed },
                                    isRunning = isStreaming && toolUi.state is ToolCallState.Running,
                                )
                            )
                            // 若为计划创建/更新工具且已匹配到计划卡片，紧随其后就地挂载计划审批卡片
                            if (toolUi.name in PLAN_CREATION_TOOL_NAMES && matchedPlan != null && !planApprovalEmitted) {
                                rawChronologicalItems.add(
                                    ChatListItem.PlanApproval(
                                        key = "plan_${matchedPlan.id}",
                                        request = matchedPlan,
                                        isTurnStart = false
                                    )
                                )
                                planApprovalEmitted = true
                            }
                        }
                    }

                    else -> {}
                }
            }

            if (!imagesHandled && messageImages.isNotEmpty()) {
                val imgItem = ChatListItem.TextMessage(
                    key = "${msg.id}_img",
                    isUser = false,
                    isStreaming = msg.isStreaming,
                    isActiveAssistant = isActiveAssistant,
                    text = "",
                    partId = "img",
                    conversationId = msg.conversationId,
                    images = messageImages,
                    isTurnStart = false,
                    messageId = msg.id,
                    createdAt = msg.createdAt,
                )
                rawChronologicalItems.add(imgItem)
            }
            for (item in rawChronologicalItems.drop(firstItemIndex)) itemMessageIds[item.key] = msg.id
        }

        // 新消息只认 Core 的交付标记；旧历史缺标记时，兼容保留最后一条完整响应。
        // 不再根据“最后一个文本块”或是否含工具调用推测答案，artifact 前后文也属于同一响应。
        val hasDeliveryMetadata = turnMessages.any { it.isFinalAnswer != null || it.turnId != null }
        val legacyFinalId = if (!hasDeliveryMetadata && !isActiveAssistant &&
            !hasLaterTurnInSameCycle(turns, turnIndex)
        ) turnMessages.lastOrNull { msg ->
            msg.blocks.any { it is ChatBlock.Text && it.text.isNotBlank() || it is ChatBlock.File }
        }?.id else null
        val finalMessages = turnMessages.filter { it.isFinalAnswer == true || it.id == legacyFinalId }
        val finalMessageIds = finalMessages.map { it.id }.toSet()
        val workItems = mutableListOf<ChatListItem>()
        val deliverableItems = mutableListOf<ChatListItem>()
        for (item in rawChronologicalItems) {
            when (item) {
                is ChatListItem.PlanApproval, is ChatListItem.QuestionCard, is ChatListItem.ExecutionApproval,
                is ChatListItem.SubagentCalls -> deliverableItems.add(item)
                is ChatListItem.TextMessage, is ChatListItem.DocumentCard -> {
                    if (itemMessageIds[item.key] in finalMessageIds) deliverableItems.add(item)
                    else workItems.add(item)
                }
                else -> workItems.add(item)
            }
        }
        if (workItems.isNotEmpty() || isActiveAssistant) {
            val activity = if (isActiveAssistant) deriveWorkTraceActivity(workItems,
                snapshot?.subagents?.count { it.status == "RUNNING" } ?: 0) else null
            // 阶段交付并不代表整项工作结束：等待子代理时保留运行与计时，但自动收起旧过程。
            // 只检查最新响应，后续主代理恢复推理/工具调用时应重新展开。
            val latestResponseDelivered = turnMessages.lastOrNull()?.id in finalMessageIds
            result.add(
                ChatListItem.WorkTraceBlock(
                    key = "${turnMessages.firstOrNull()?.id ?: "active"}_worktrace",
                    items = workItems,
                    totalToolsCount = workItems.sumOf { item ->
                        when (item) {
                            is ChatListItem.ToolCalls -> item.toolCalls.size
                            is ChatListItem.SubagentCalls -> item.subagents.size
                            else -> 0
                        }
                    },
                    hasFailedTool = workItems.any { item ->
                        item is ChatListItem.ToolCalls && item.hasFailedTool ||
                            item is ChatListItem.SubagentCalls && item.hasFailed
                    },
                    isRunning = isActiveAssistant,
                    turnId = turnMessages.firstOrNull()?.turnId,
                    // 同一工作可跨用户插话有多段显示，累计计时只放在最后一段，旧段不再跳动。
                    startedAt = if (turnMessages.any { it.id == lastResponseByWork[it.turnId] || it.turnId == null && turnMessages == lastAssistantTurn })
                        turnMessages.mapNotNull { it.workStartedAt }.minOrNull() else null,
                    completedAt = if (turnMessages.any { it.id == lastResponseByWork[it.turnId] || it.turnId == null && turnMessages == lastAssistantTurn })
                        turnMessages.mapNotNull { it.workCompletedAt }.maxOrNull() else null,
                    activity = activity,
                    defaultExpanded = !latestResponseDelivered && activity !is WorkTraceActivity.WaitingSubagents,
                    isTurnStart = true,
                )
            )
            turnHasFirstItem = true
        }
        // 待用户操作的卡片必须留在折叠块外，不能因等待而藏起入口。
        if (matchedPlan != null && deliverableItems.none { it is ChatListItem.PlanApproval } &&
            result.none { it is ChatListItem.PlanApproval && it.request.id == matchedPlan.id }
        ) deliverableItems.add(ChatListItem.PlanApproval(key = "plan_${matchedPlan.id}", request = matchedPlan))
        deliverableItems.forEach { item ->
            if (item is ChatListItem.QuestionCard && result.any { it is ChatListItem.QuestionCard }) return@forEach
            if (item is ChatListItem.PlanApproval && result.any { it is ChatListItem.PlanApproval && it.request.id == item.request.id }) return@forEach
            result.add(item.withTurnStart(!turnHasFirstItem))
            turnHasFirstItem = true
        }

        if (!isActiveAssistant) {
            // 单轮文件变更汇总卡片
            val turnDiffSummary = turnMessages.mapNotNull { it.turnDiffSummary }.lastOrNull()
            val lastMsg = finalMessages.lastOrNull()
            if (turnDiffSummary != null && turnDiffSummary.files.isNotEmpty() && !isStreaming) {
                result.add(
                    ChatListItem.TurnDiffCard(
                        key = "${turnMessages.firstOrNull()?.id ?: ""}_turndiff",
                        summary = turnDiffSummary,
                        messageId = lastMsg?.id ?: "",
                        isTurnStart = !turnHasFirstItem,
                    )
                )
                turnHasFirstItem = true
            }

            // 诊断与默认复制内容都绑定完整交付消息，不能只复制最后一句或混入过程说明。
            if (lastMsg != null && !isStreaming) {
                val lastMessageText = finalMessages.flatMap { it.blocks }
                    .filterIsInstance<ChatBlock.Text>()
                    .filter { it.text.isNotBlank() }
                    .joinToString("\n\n") { it.text }

                val fullTurnText = buildString {
                    for (msg in turnMessages) {
                        for (block in msg.blocks) {
                            when (block) {
                                is ChatBlock.Reasoning -> {
                                    if (block.text.isNotBlank()) {
                                        if (isNotEmpty()) append("\n\n")
                                        append("> Thinking:\n").append(block.text.trim())
                                    }
                                }
                                is ChatBlock.ToolCall -> {
                                    if (isNotEmpty()) append("\n\n")
                                    append("[Tool: ").append(block.name).append("]")
                                }
                                is ChatBlock.Text -> {
                                    if (block.text.isNotBlank()) {
                                        if (isNotEmpty()) append("\n\n")
                                        append(block.text)
                                    }
                                }
                                else -> {}
                            }
                        }
                    }
                }.ifBlank { lastMessageText }

                result.add(
                    ChatListItem.Footer(
                        key = "${turnMessages.firstOrNull()?.id ?: ""}_footer",
                        footer = lastMsg.toAssistantFooter(),
                        lastMessageText = lastMessageText,
                        fullTurnText = fullTurnText,
                        isTurnStart = false,
                    )
                )
            }
        }
    }

    // 请求已提交/工作已开始但 assistant 消息尚未创建时，同样需要可见的等待行。
    // 只检查最后一个用户气泡之后，不能把上一段的过程误当成本次输入的反馈。
    val lastUserIndex = result.indexOfLast { it is ChatListItem.TextMessage && it.isUser }
    val hasTailWork = result.drop(lastUserIndex + 1)
        .any { it is ChatListItem.WorkTraceBlock && it.isRunning }
    if ((isWorking || isSending) && !hasTailWork) {
        val lastInput = msgs.lastOrNull { it.role == ChatRole.User }
        result.add(ChatListItem.WorkTraceBlock(
            key = "${lastInput?.id ?: snapshot?.conversation?.id ?: "active"}_waiting_worktrace",
            items = emptyList(),
            totalToolsCount = 0,
            hasFailedTool = false,
            isRunning = true,
            turnId = lastInput?.turnId,
            startedAt = lastInput?.workStartedAt ?: pendingStartedAt,
            activity = deriveWorkTraceActivity(emptyList(),
                snapshot?.subagents?.count { it.status == "RUNNING" } ?: 0),
            isTurnStart = true,
        ))
    }

    snapshot?.pendingQuestion?.let { question ->
        if (result.none { it is ChatListItem.QuestionCard && it.request.id == question.id }) {
            result.add(ChatListItem.QuestionCard(key = "question_${question.id}", request = question, isTurnStart = true))
        }
    }
    val pending = snapshot?.pendingPlanApproval?.takeIf { it.status == "PENDING" || it.status == "PENDING_APPROVAL" }
    if (pending != null && result.none { it is ChatListItem.PlanApproval && it.request.id == pending.id }) {
        result.add(
            ChatListItem.PlanApproval(
                key = "plan_${pending.id}",
                request = pending,
                isTurnStart = true
            )
        )
    }

    // 存活执行请求来自 core 快照，永不从工具历史推断，也不折叠进过程栏。
    snapshot?.pendingExecutionApprovals.orEmpty().distinctBy { it.id }.forEach { request ->
        if (request.conversationId == snapshot?.conversation?.id) {
            result.add(ChatListItem.ExecutionApproval(
                key = "execution_approval_${request.conversationId}_${request.id}",
                request = request,
                isTurnStart = true,
            ))
        }
    }

    // 活跃工作区操作状态条（准备/复制/等批准/初始化/恢复/失败重试）
    val activeOp = snapshot?.activeWorkspaceOperation ?: snapshot?.workspaceInfo?.operation
    if (activeOp != null && activeOp.state != xyz.mederi.core.contract.models.WorkspaceOperationState.SUCCEEDED) {
        val convId = snapshot?.conversation?.id
        if (convId != null && (activeOp.sessionId == null || activeOp.sessionId == convId)) {
            result.add(
                ChatListItem.WorkspaceOperationBar(
                    key = "op_${activeOp.id}",
                    operation = activeOp,
                    isTurnStart = true
                )
            )
        }
    }
    return result
}

/** assistant 消息 → footer 元数据（诊断字段来自 core Message） */
private fun ChatMessage.toAssistantFooter(): AssistantFooterInfo {
    val modelId = model
    return AssistantFooterInfo(
        modelName = modelName ?: modelId,
        agentMode = agentMode,
        thinkingLevel = thinkingLevel,
        durationMs = durationMs,
        completedAtMs = completedAt?.takeIf { it > 0 } ?: (createdAt + (durationMs ?: 0)),
    )
}

/** 从工具参数中探测"核心目标"单行摘要（文件路径 / 命令等），供 ToolCallUi.target 使用。 */
internal fun toToolCallUi(toolCall: ChatBlock.ToolCall, snapshot: ConversationSnapshot?): ToolCallUi {
    val input = when (val s = toolCall.state) {
        is ToolCallState.Running -> s.input
        is ToolCallState.Completed -> s.input
        is ToolCallState.Failed -> s.input
        is ToolCallState.Pending -> s.input
    }
    return ToolCallUi(
        id = toolCall.id,
        name = toolCall.name,
        state = toolCall.state,
        target = probeToolTarget(toolCall.name, input, snapshot),
        isFailed = toolCall.state is ToolCallState.Failed,
    )
}

/** 工具调用聚合摘要：同名工具计次合并，如 "read_file ×3, edit_file"。 */
private fun buildToolSummary(toolCalls: List<ToolCallUi>): String {
    if (toolCalls.isEmpty()) return ""
    return toolCalls.groupingBy { it.name }.eachCount().entries.joinToString(", ") { (name, count) ->
        if (count > 1) "$name ×$count" else name
    }
}

/** 文件块是否为图片（按 mimeType / data:image URL / 常见图片扩展名判别）。 */
internal fun isImageBlock(block: ChatBlock.File): Boolean {
    val mime = block.mimeType?.lowercase()
    if (mime?.startsWith("image/") == true) return true
    val url = block.url.lowercase()
    if (url.startsWith("data:image/")) return true
    val cleanUrl = url.substringBefore('?').substringBefore('#')
    return cleanUrl.endsWith(".png") || cleanUrl.endsWith(".jpg") ||
        cleanUrl.endsWith(".jpeg") || cleanUrl.endsWith(".webp") ||
        cleanUrl.endsWith(".gif") || cleanUrl.endsWith(".svg") ||
        cleanUrl.endsWith(".bmp") || cleanUrl.endsWith(".ico")
}

/** 事件消息解析数据模型 */
data class ParsedEventMessage(
    val eventType: String,
    val agentId: String,
    val status: String,
    val role: String,
    val subtaskInfo: String?,
    val reportPath: String?,
    val taskType: String?,
    val summary: String
)

private val EVENT_MESSAGE_REGEX = Regex(
    """<event_message\s+type="([^"]+)"\s+agentId="([^"]+)"\s+status="([^"]+)">([\s\S]*?)</event_message>"""
)

private val BACKGROUND_TASK_EVENT_MESSAGE_REGEX = Regex(
    """<event_message\s+type="([^"]+)"\s+taskId="([^"]+)"\s+taskType="([^"]+)"\s+status="([^"]+)">([\s\S]*?)</event_message>"""
)

/** 解析 <event_message> 标签文本 */
fun parseEventMessage(text: String): ParsedEventMessage? {
    val trimmed = text.trim()
    // 先试子代理形态（agentId 属性）
    EVENT_MESSAGE_REGEX.find(trimmed)?.let { match ->
        val eventType = match.groupValues[1]
        val agentId = match.groupValues[2]
        val status = match.groupValues[3]
        val body = match.groupValues[4]

        var role = "AGENT"
        var subtaskInfo: String? = null
        var reportPath: String? = null

        Regex("""^Role:\s*(.*)$""", RegexOption.MULTILINE).find(body)?.let { roleMatch ->
            role = roleMatch.groupValues[1].trim()
        }
        Regex("""^Subtask:\s*(.*)$""", RegexOption.MULTILINE).find(body)?.let { subtaskMatch ->
            subtaskInfo = subtaskMatch.groupValues[1].trim()
        }
        Regex("""^ReportPath:\s*(.*)$""", RegexOption.MULTILINE).find(body)?.let { reportPathMatch ->
            reportPath = reportPathMatch.groupValues[1].trim()
        }

        val summaryIdx = body.indexOf("Summary:")
        val summary = if (summaryIdx != -1) body.substring(summaryIdx + "Summary:".length).trim() else body.trim()

        return ParsedEventMessage(
            eventType = eventType, agentId = agentId, status = status,
            role = role, subtaskInfo = subtaskInfo, reportPath = reportPath,
            taskType = null, summary = summary
        )
    }
    // 再试后台任务形态（taskId/taskType 属性）
    BACKGROUND_TASK_EVENT_MESSAGE_REGEX.find(trimmed)?.let { match ->
        val eventType = match.groupValues[1]
        val taskType = match.groupValues[3]
        val status = match.groupValues[4]
        val body = match.groupValues[5].trim()

        return ParsedEventMessage(
            eventType = eventType, agentId = "", status = status,
            role = "BACKGROUND_TASK", subtaskInfo = null, reportPath = null,
            taskType = taskType, summary = body
        )
    }
    return null
}

/** 判定一条 User 消息是否纯由 <event_message> 组成（后台子代理唤醒事件，非人类用户消息）。 */
private fun isEventOnlyUserMessage(msg: ChatMessage): Boolean {
    if (msg.role != ChatRole.User) return false
    if (msg.blocks.any { it is ChatBlock.File }) return false
    val textBlocks = msg.blocks.filterIsInstance<ChatBlock.Text>().filter { it.text.isNotBlank() }
    return textBlocks.isNotEmpty() && textBlocks.all { parseEventMessage(it.text) != null }
}

/** 判定当前 Assistant 轮次之后是否紧随同一用户周期内的 <event_message> 唤醒事件（即本轮尚未结束整个用户指令周期）。 */
private fun hasLaterTurnInSameCycle(
    turns: List<Pair<ChatRole?, List<ChatMessage>>>,
    currentTurnIndex: Int,
): Boolean {
    val (nextRole, nextMsgs) = turns.getOrNull(currentTurnIndex + 1) ?: return false
    return nextRole == ChatRole.User && nextMsgs.isNotEmpty() && nextMsgs.all { isEventOnlyUserMessage(it) }
}

/** 辅助扩展：设置或更新 ChatListItem 的 isTurnStart 标志 */
internal fun ChatListItem.withTurnStart(isStart: Boolean): ChatListItem = when (this) {
    is ChatListItem.Reasoning -> copy(isTurnStart = isStart)
    is ChatListItem.TextMessage -> copy(isTurnStart = isStart)
    is ChatListItem.ToolCalls -> copy(isTurnStart = isStart)
    is ChatListItem.SubagentCalls -> copy(isTurnStart = isStart)
    is ChatListItem.WorkTraceBlock -> copy(isTurnStart = isStart)
    is ChatListItem.DocumentCard -> copy(isTurnStart = isStart)
    is ChatListItem.Footer -> copy(isTurnStart = isStart)
    is ChatListItem.PlanApproval -> copy(isTurnStart = isStart)
    is ChatListItem.ExecutionApproval -> copy(isTurnStart = isStart)
    is ChatListItem.QuestionCard -> copy(isTurnStart = isStart)
    is ChatListItem.TurnDiffCard -> copy(isTurnStart = isStart)
    is ChatListItem.EventMessageCard -> copy(isTurnStart = isStart)
    is ChatListItem.WorkspaceOperationBar -> copy(isTurnStart = isStart)
}

