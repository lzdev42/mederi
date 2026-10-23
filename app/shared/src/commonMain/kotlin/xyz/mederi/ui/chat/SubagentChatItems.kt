package xyz.mederi.ui

import kotlinx.datetime.Instant
import xyz.mederi.core.contract.models.ChatBlock
import xyz.mederi.core.contract.models.ChatMessage
import xyz.mederi.core.contract.models.ChatRole
import xyz.mederi.core.contract.models.SubagentBlock
import xyz.mederi.core.contract.models.SubagentState

/**
 * 子代理对话视图合成器：把 [SubagentState]（含结构化 blocks）合成标准 [ChatMessage]，
 * 喂给 [computeChatItems] 复用主对话全部渲染逻辑。
 *
 * 产出两条消息：
 * 1. User 消息 = 主代理下发指令（task + briefing），顶部第一条；
 * 2. Assistant 消息 = 子代理工作流（blocks 映射为 ChatBlock），isStreaming 随运行态。
 */
object SubagentChatItems {

    fun build(subagent: SubagentState): List<ChatListItem> {
        val isRunning = subagent.status.equals("RUNNING", ignoreCase = true)
        val createdAt = runCatching { Instant.parse(subagent.startedAt).toEpochMilliseconds() }.getOrDefault(0L)

        // 1) User 消息：主代理下发指令（task + briefing）
        val instruction = buildString {
            append(subagent.task)
            if (!subagent.briefing.isNullOrBlank()) {
                append("\n\n").append(subagent.briefing)
            }
        }
        val userMsg = ChatMessage(
            id = "sub_user_${subagent.agentId}",
            conversationId = subagent.agentId,
            role = ChatRole.User,
            blocks = listOf(ChatBlock.Text(id = "t0", text = instruction)),
            createdAt = createdAt,
            completedAt = null,
            parentMessageId = null,
            model = null,
            agent = null,
        )

        // 2) Assistant 消息：子代理工作流 blocks → ChatBlock
        val assistantBlocks = subagent.blocks.mapIndexed { idx, block -> block.toChatBlock(idx) }
        val assistantMsg = ChatMessage(
            id = "sub_assistant_${subagent.agentId}",
            conversationId = subagent.agentId,
            role = ChatRole.Assistant,
            blocks = assistantBlocks,
            createdAt = createdAt,
            completedAt = if (!isRunning) createdAt else null,
            parentMessageId = null,
            model = subagent.modelId.ifBlank { null },
            agent = null,
            isStreaming = isRunning,
            modelName = subagent.modelName.ifBlank { null },
            thinkingLevel = subagent.reasoningLevel,
        )

        return computeChatItems(
            msgs = listOf(userMsg, assistantMsg),
            isWorking = isRunning,
            snapshot = null,
        )
    }

    /** SubagentBlock → ChatBlock 映射（id 用索引保证唯一）。 */
    private fun SubagentBlock.toChatBlock(index: Int): ChatBlock = when (this) {
        is SubagentBlock.Reasoning -> ChatBlock.Reasoning(id = "r$index", text = text)
        is SubagentBlock.ToolCall -> ChatBlock.ToolCall(id = "tc$index", name = name, state = state)
        is SubagentBlock.Text -> ChatBlock.Text(id = "t$index", text = text)
    }
}
