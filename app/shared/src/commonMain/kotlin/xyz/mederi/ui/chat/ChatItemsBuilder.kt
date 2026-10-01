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

/**
 * 交互工具名集合：这些工具是人机交互用途（提问/计划审批），
 * 它们前面的 Text 是 AI 给用户的分析/解释，不是步骤间的过渡旁白。
 * findFinalText 判断"后面是否还有 ToolCall"时应排除这些工具。
 */
private val INTERACTIVE_TOOL_NAMES = setOf("ask_user", "create_plan")

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
): List<ChatListItem> {
    val result = mutableListOf<ChatListItem>()

    // role 为 null 的段是压缩标记（SUMMARY），独立成卡片
    val turns = mutableListOf<Pair<ChatRole?, List<ChatMessage>>>()
    var currentAssistant = mutableListOf<ChatMessage>()
    for (msg in msgs) {
        if (msg.role == ChatRole.Summary) {
            if (currentAssistant.isNotEmpty()) {
                turns.add(ChatRole.Assistant to currentAssistant.toList())
                currentAssistant = mutableListOf()
            }
            // 压缩总结（TLDR）：按 AI 消息渲染（走正常 MarkdownView 管线），不特殊卡片。
            // 独立成 turn（不与后续 assistant 合并——合并后若后续轮次含工具调用，
            // TLDR 会被误判为步骤叙述折叠进工作过程栏）。
            turns.add(ChatRole.Assistant to listOf(msg))
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
    for ((role, turnMessages) in turns) {
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
        // 活跃判定：正在流式，或会话在工作且本 turn 是最后一个 assistant turn 且其最后一条
        // 消息仍是会话最新消息（尾巴检查）。缺尾巴检查时，新用户消息已发出、新 turn 的
        // assistant 尚未开始流式的窗口期内，lastAssistantTurn 仍指向旧完成 turn，会把它
        // 误判为活跃 → 已折叠的 WorkTraceBlock 平铺展开（历史 bug）。
        val isLastMsgTail = turnMessages.lastOrNull()?.id == msgs.lastOrNull()?.id
        val isActiveAssistant = isStreaming || (isWorking && turnMessages == lastAssistantTurn && isLastMsgTail)

        val hasAnyToolCallInTurn = turnMessages.any { m -> m.blocks.any { it is ChatBlock.ToolCall } }
        var turnHasFirstItem = false

        val workItems = mutableListOf<ChatListItem>()
        val deliverableItems = mutableListOf<ChatListItem>()

        // 后缀扫描（O(n) 一次倒序遍历）：预计算每条消息"之后"是否还有工具调用/非空文本，
        // 替代循环内原先对后续消息的 O(n) 线性扫描（整轮 O(n²)），改为 O(1) 查表。
        val n = turnMessages.size
        val suffixMsgHasToolCall = BooleanArray(n)
        for (i in n - 2 downTo 0) {
            val nextHasTool = turnMessages[i + 1].blocks.any { it is ChatBlock.ToolCall }
            suffixMsgHasToolCall[i] = nextHasTool || suffixMsgHasToolCall[i + 1]
        }
        val suffixMsgHasText = BooleanArray(n)
        for (i in n - 2 downTo 0) {
            val nextHasText = turnMessages[i + 1].blocks.any { it is ChatBlock.Text && it.text.isNotBlank() }
            suffixMsgHasText[i] = nextHasText || suffixMsgHasText[i + 1]
        }

        // 最终收尾文本定位：turn 内最后一个"其后绝无工具调用"的非空 Text block 视为最终总结。
        // 有工具的轮次（完成状态下会创建 WorkTraceBlock）中，除最终总结外的一切内容
        // （所有推理、所有过渡文本、所有工具调用）都归入工作过程栏内。
        // 只要文本之后（当前消息或后续消息中）存在工具调用，即为执行过程叙述，绝不可作为整个轮次的最终收尾总结。
        var finalTextMsgIndex = -1
        var finalTextBlockIndex = -1
        findFinalText@ for (i in turnMessages.indices.reversed()) {
            if (suffixMsgHasToolCall[i]) continue
            val msg = turnMessages[i]
            for (b in msg.blocks.indices.reversed()) {
                val block = msg.blocks[b]
                if (block is ChatBlock.Text && block.text.isNotBlank()) {
                    val hasLaterToolInMsg = msg.blocks.drop(b + 1).any { it is ChatBlock.ToolCall }
                    if (!hasLaterToolInMsg) {
                        finalTextMsgIndex = i
                        finalTextBlockIndex = b
                        break@findFinalText
                    }
                }
            }
        }

        // 预解析计划审批卡片匹配
        val allToolCalls = turnMessages.flatMap { it.blocks.filterIsInstance<ChatBlock.ToolCall>() }
            .map { toToolCallUi(it, snapshot) }
        val createPlanCall = allToolCalls.find { it.name == "create_plan" }
        val (planIdFromTool, planTitleFromTool) = when (val s = createPlanCall?.state) {
            is ToolCallState.Completed -> {
                val idFromInput = s.input["planId"]
                val idFromOutput = s.output?.let { out ->
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
        } ?: if (turnMessages == lastAssistantTurn) snapshot?.pendingPlanApproval else null

        // 按真实自然时序收集本轮的消息块
        val rawChronologicalItems = mutableListOf<Pair<Boolean /* isFinalText */, ChatListItem>>()
        var planApprovalEmitted = false

        for ((msgIndex, msg) in turnMessages.withIndex()) {
            val subsequentHasToolCall = suffixMsgHasToolCall[msgIndex]
            val isMsgStepNarration = hasAnyToolCallInTurn && (msg.blocks.any { it is ChatBlock.ToolCall } || subsequentHasToolCall)

            val messageImages = msg.blocks.filterIsInstance<ChatBlock.File>()
                .filter { isImageBlock(it) }
                .map { it.url }
            var imagesHandled = false

            for ((blockIndex, block) in msg.blocks.withIndex()) {
                val isFinalText = hasAnyToolCallInTurn && msgIndex == finalTextMsgIndex && blockIndex == finalTextBlockIndex
                val isStepNarration = hasAnyToolCallInTurn && !isFinalText
                when (block) {
                    is ChatBlock.Reasoning -> {
                        if (block.text.isNotBlank()) {
                            val durationMs = if (msg.completedAt != null && msg.completedAt > msg.createdAt) {
                                msg.completedAt - msg.createdAt
                            } else (msg.durationMs ?: 0L)
                            val hasSubsequentText = msg.blocks.drop(blockIndex + 1).any { it is ChatBlock.Text && it.text.isNotBlank() } || suffixMsgHasText[msgIndex]
                            val isReasoningActive = isStreaming && !hasSubsequentText

                            val item = ChatListItem.Reasoning(
                                key = "${msg.id}_${block.id}",
                                text = block.text,
                                isStreaming = isStreaming,
                                isTurnStart = false,
                                durationMs = durationMs,
                                isReasoningActive = isReasoningActive,
                            )
                            rawChronologicalItems.add(false to item)
                        }
                    }

                    is ChatBlock.Text -> {
                        if (block.text.isNotBlank()) {
                            val parseResult = ArtifactParser.parse(block.text, isStreaming = isStreaming)
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
                                        isStepNarration = isStepNarration,
                                    )
                                    rawChronologicalItems.add(false to preItem)
                                    imagesHandled = true
                                }

                                rawChronologicalItems.add(
                                    isFinalText to ChatListItem.DocumentCard(
                                        key = "${msg.id}_${block.id}_art_${artifact.identifier}",
                                        artifactId = artifact.identifier,
                                        title = artifact.title,
                                        content = artifact.content,
                                        lineCount = artifact.lineCount,
                                        charCount = artifact.charCount,
                                        isCompleted = artifact.isCompleted,
                                        isStreaming = isStreaming && !artifact.isCompleted,
                                        createdAt = msg.createdAt,
                                        isTurnStart = false,
                                    )
                                )

                                if (parseResult.postscript.isNotBlank()) {
                                    val postItem = ChatListItem.TextMessage(
                                        key = "${msg.id}_${block.id}_post",
                                        isUser = false,
                                        isStreaming = isStreaming,
                                        isActiveAssistant = isActiveAssistant,
                                        text = parseResult.postscript,
                                        partId = "${block.id}_post",
                                        conversationId = msg.conversationId,
                                        images = emptyList(),
                                        isTurnStart = false,
                                        messageId = msg.id,
                                        createdAt = msg.createdAt,
                                        assistantFooter = null,
                                        isStepNarration = isStepNarration,
                                    )
                                    rawChronologicalItems.add(isFinalText to postItem)
                                }
                            } else {
                                val textItem = ChatListItem.TextMessage(
                                    key = "${msg.id}_${block.id}",
                                    isUser = false,
                                    isStreaming = isStreaming,
                                    isActiveAssistant = isActiveAssistant,
                                    text = block.text,
                                    partId = block.id,
                                    conversationId = msg.conversationId,
                                    images = if (!imagesHandled) messageImages else emptyList(),
                                    isTurnStart = false,
                                    messageId = msg.id,
                                    createdAt = msg.createdAt,
                                    assistantFooter = null,
                                    isStepNarration = isStepNarration,
                                )
                                rawChronologicalItems.add(isFinalText to textItem)
                                imagesHandled = true
                            }
                        }
                    }

                    is ChatBlock.ToolCall -> {
                        val toolUi = toToolCallUi(block, snapshot)
                        val isSubagent = toolUi.name in SUBAGENT_TOOL_NAMES
                        if (isSubagent) {
                            rawChronologicalItems.add(
                                false to ChatListItem.SubagentCalls(
                                    key = "${msg.id}_${block.id}",
                                    subagents = listOf(toolUi),
                                    isStreaming = isStreaming,
                                    isTurnStart = false,
                                    isRunning = isStreaming && toolUi.state is ToolCallState.Running,
                                    hasFailed = toolUi.isFailed,
                                )
                            )
                        } else {
                            rawChronologicalItems.add(
                                false to ChatListItem.ToolCalls(
                                    key = "${msg.id}_${block.id}",
                                    toolCalls = listOf(toolUi),
                                    isStreaming = isStreaming,
                                    isTurnStart = false,
                                    toolSummary = buildToolSummary(listOf(toolUi)),
                                    hasFailedTool = toolUi.isFailed,
                                    isRunning = isStreaming && toolUi.state is ToolCallState.Running,
                                )
                            )
                            // 若为 create_plan 且已匹配到计划卡片，紧随其后就地挂载计划审批卡片
                            if (toolUi.name == "create_plan" && matchedPlan != null && !planApprovalEmitted) {
                                rawChronologicalItems.add(
                                    false to ChatListItem.PlanApproval(
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
                    isStreaming = isStreaming,
                    isActiveAssistant = isActiveAssistant,
                    text = "",
                    partId = "img",
                    conversationId = msg.conversationId,
                    images = messageImages,
                    isTurnStart = false,
                    messageId = msg.id,
                    createdAt = msg.createdAt,
                    isStepNarration = isMsgStepNarration,
                )
                rawChronologicalItems.add(false to imgItem)
            }
        }

        // 过程步骤与工作轨迹生命周期：
        // - Turn 执行中（isActiveAssistant = true）：所有步骤直接按自然时序平铺在聊天流中，实时展示各步骤行与命令输出；
        // - Turn 完成后（isActiveAssistant = false）：轮次内所有中间过程（思考、工具调用、过渡语）折叠进顶部的 WorkTraceBlock，外部仅留最终答复正文。
        if (isActiveAssistant) {
            if (rawChronologicalItems.isEmpty() && !turnHasFirstItem) {
                val firstMsg = turnMessages.firstOrNull()
                result.add(
                    ChatListItem.Reasoning(
                        key = "${firstMsg?.id ?: "active"}_reasoning",
                        text = "",
                        isStreaming = true,
                        isTurnStart = true,
                        durationMs = 0L,
                        isReasoningActive = true,
                    )
                )
                turnHasFirstItem = true
            } else {
                rawChronologicalItems.forEachIndexed { idx, (_, item) ->
                    val itemWithTurnStart = if (!turnHasFirstItem && idx == 0) item.withTurnStart(true) else item
                    result.add(itemWithTurnStart)
                    turnHasFirstItem = true
                }
            }

            // 兜底：若 matchedPlan 存在且未在遍历 create_plan 时挂载，追加到尾部
            if (matchedPlan != null && result.none { it is ChatListItem.PlanApproval && it.request.id == matchedPlan.id }) {
                result.add(
                    ChatListItem.PlanApproval(
                        key = "plan_${matchedPlan.id}",
                        request = matchedPlan,
                        isTurnStart = !turnHasFirstItem
                    )
                )
                turnHasFirstItem = true
            }
        } else {
            // Turn 已完成
            if (!hasAnyToolCallInTurn) {
                // 无工具调用：全时序输出
                rawChronologicalItems.forEachIndexed { idx, (_, item) ->
                    val itemWithTurnStart = if (!turnHasFirstItem && idx == 0) item.withTurnStart(true) else item
                    result.add(itemWithTurnStart)
                    turnHasFirstItem = true
                }
            } else {
                // 有工具调用：轮次内所有中间过程（思考、工具调用、过渡语）折叠进顶部的 WorkTraceBlock，外部仅留最终答复正文
                val workItems = mutableListOf<ChatListItem>()
                val deliverableItems = mutableListOf<ChatListItem>()
                var planApprovalItem: ChatListItem.PlanApproval? = null

                for ((isFinal, item) in rawChronologicalItems) {
                    when {
                        item is ChatListItem.PlanApproval -> {
                            planApprovalItem = item
                        }
                        isFinal -> {
                            deliverableItems.add(item)
                        }
                        else -> {
                            workItems.add(item)
                        }
                    }
                }

                if (workItems.isNotEmpty()) {
                    var totalToolsCount = 0
                    var hasFailed = false
                    for (it in workItems) {
                        when (it) {
                            is ChatListItem.ToolCalls -> {
                                totalToolsCount += it.toolCalls.size
                                if (it.hasFailedTool) hasFailed = true
                            }
                            is ChatListItem.SubagentCalls -> {
                                totalToolsCount += it.subagents.size
                                if (it.hasFailed) hasFailed = true
                            }
                            else -> {}
                        }
                    }
                    val totalDurationMs = turnMessages.mapNotNull { it.durationMs }.sum()
                    result.add(
                        ChatListItem.WorkTraceBlock(
                            key = "${turnMessages.firstOrNull()?.id ?: ""}_worktrace",
                            items = workItems,
                            totalToolsCount = totalToolsCount,
                            totalDurationMs = totalDurationMs,
                            hasFailedTool = hasFailed,
                            isTurnStart = !turnHasFirstItem,
                        )
                    )
                    turnHasFirstItem = true
                }

                // 计划卡片展示在工作过程之后
                val effectivePlan = planApprovalItem ?: matchedPlan?.let {
                    ChatListItem.PlanApproval(key = "plan_${it.id}", request = it, isTurnStart = !turnHasFirstItem)
                }
                if (effectivePlan != null && result.none { it is ChatListItem.PlanApproval && it.request.id == effectivePlan.request.id }) {
                    result.add(effectivePlan.withTurnStart(!turnHasFirstItem))
                    turnHasFirstItem = true
                }

                // 交付内容（最终答复正文）
                deliverableItems.forEach { item ->
                    result.add(item.withTurnStart(!turnHasFirstItem))
                    turnHasFirstItem = true
                }
            }

            // 单轮文件变更汇总卡片
            val turnDiffSummary = turnMessages.mapNotNull { it.turnDiffSummary }.lastOrNull()
            val lastMsg = turnMessages.lastOrNull { it.role == ChatRole.Assistant }
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

            // 轮次底部的诊断与状态栏
            if (lastMsg != null && !isStreaming) {
                val lastMessageText = if (finalTextMsgIndex >= 0 && finalTextBlockIndex >= 0) {
                    (turnMessages[finalTextMsgIndex].blocks.getOrNull(finalTextBlockIndex) as? ChatBlock.Text)?.text.orEmpty()
                } else {
                    turnMessages.flatMap { it.blocks }.filterIsInstance<ChatBlock.Text>().lastOrNull()?.text.orEmpty()
                }

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

    val pending = snapshot?.pendingPlanApproval
    if (pending != null && result.none { it is ChatListItem.PlanApproval && it.request.id == pending.id }) {
        result.add(
            ChatListItem.PlanApproval(
                key = "plan_${pending.id}",
                request = pending,
                isTurnStart = true
            )
        )
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
    val summary: String
)

private val EVENT_MESSAGE_REGEX = Regex(
    """<event_message\s+type="([^"]+)"\s+agentId="([^"]+)"\s+status="([^"]+)">([\s\S]*?)</event_message>"""
)

/** 解析 <event_message> 标签文本 */
fun parseEventMessage(text: String): ParsedEventMessage? {
    val trimmed = text.trim()
    val match = EVENT_MESSAGE_REGEX.find(trimmed) ?: return null
    val eventType = match.groupValues[1]
    val agentId = match.groupValues[2]
    val status = match.groupValues[3]
    val body = match.groupValues[4]

    var role = "AGENT"
    var subtaskInfo: String? = null
    var reportPath: String? = null

    val roleMatch = Regex("""^Role:\s*(.*)$""", RegexOption.MULTILINE).find(body)
    if (roleMatch != null) role = roleMatch.groupValues[1].trim()

    val subtaskMatch = Regex("""^Subtask:\s*(.*)$""", RegexOption.MULTILINE).find(body)
    if (subtaskMatch != null) subtaskInfo = subtaskMatch.groupValues[1].trim()

    val reportPathMatch = Regex("""^ReportPath:\s*(.*)$""", RegexOption.MULTILINE).find(body)
    if (reportPathMatch != null) reportPath = reportPathMatch.groupValues[1].trim()

    val summaryIdx = body.indexOf("Summary:")
    val summary = if (summaryIdx != -1) {
        body.substring(summaryIdx + "Summary:".length).trim()
    } else {
        body.trim()
    }

    return ParsedEventMessage(
        eventType = eventType,
        agentId = agentId,
        status = status,
        role = role,
        subtaskInfo = subtaskInfo,
        reportPath = reportPath,
        summary = summary
    )
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
    is ChatListItem.TurnDiffCard -> copy(isTurnStart = isStart)
    is ChatListItem.EventMessageCard -> copy(isTurnStart = isStart)
}