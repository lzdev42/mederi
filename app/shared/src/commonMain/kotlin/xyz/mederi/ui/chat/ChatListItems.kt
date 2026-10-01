package xyz.mederi.ui

import xyz.mederi.core.contract.models.*

/**
 * 展平后的聊天列表 item — 每个文本块/思考面板都是独立 LazyColumn item。
 * 所有展示所需的派生数据（聚合文案、目标参数、轮次标记）在此预计算，View 零逻辑。
 */
sealed interface ChatListItem {
    val key: String

    /** 是否为对话轮次的第一个 item（UI 据此加大与上一轮次的间距） */
    val isTurnStart: Boolean

    data class Reasoning(
        override val key: String,
        val text: String,
        val isStreaming: Boolean,
        override val isTurnStart: Boolean = false,
        val durationMs: Long = 0L,
        val isReasoningActive: Boolean = false,
    ) : ChatListItem

    data class ToolCalls(
        override val key: String,
        val toolCalls: List<ToolCallUi>,
        val isStreaming: Boolean,
        override val isTurnStart: Boolean = false,
       val toolSummary: String = "",
       val hasFailedTool: Boolean = false,
       val isRunning: Boolean = false,
   ) : ChatListItem

    data class SubagentCalls(
        override val key: String,
        val subagents: List<ToolCallUi>,
        val isStreaming: Boolean,
        override val isTurnStart: Boolean = false,
       val isRunning: Boolean = false,
       val hasFailed: Boolean = false,
   ) : ChatListItem

    /**
     * 多步任务的工作过程聚合栏（Work 栏）。
     * 将轮次内的所有推理、自说自话过渡语与工具调用折叠聚合为单行汇总条，默认折叠突出最终正文。
     */
    data class WorkTraceBlock(
        override val key: String,
        val items: List<ChatListItem>,
        val totalToolsCount: Int,
        val totalDurationMs: Long,
        val hasFailedTool: Boolean,
        override val isTurnStart: Boolean = false,
    ) : ChatListItem

    data class TextMessage(
        override val key: String,
        val isUser: Boolean,
        val isStreaming: Boolean,
        val isActiveAssistant: Boolean,
        val text: String,
        val partId: String,
        val conversationId: String,
        val images: List<String> = emptyList(),
        override val isTurnStart: Boolean = false,
        val messageId: String = "",
        val createdAt: Long = 0L,
        /** assistant 消息的 footer 元数据——只挂在该轮次最后一个文本块上，其余为 null */
        val assistantFooter: AssistantFooterInfo? = null,
        /** 是否为伴随工具调用的步骤过渡语（自说自话，弱化展示与最终主交付区分） */
        val isStepNarration: Boolean = false,
    ) : ChatListItem

    /** 独立长文 Markdown 产物卡片（点击在右侧扩展窗口打开） */
    data class DocumentCard(
        override val key: String,
        val artifactId: String,
        val title: String,
        val content: String,
        val lineCount: Int,
        val charCount: Int,
        val isCompleted: Boolean,
        val isStreaming: Boolean,
        val createdAt: Long = 0L,
        override val isTurnStart: Boolean = false,
    ) : ChatListItem

    /** assistant 轮次底部的诊断与状态栏（沉底挂载） */
    data class Footer(
        override val key: String,
        val footer: AssistantFooterInfo,
        val lastMessageText: String = "",
        val fullTurnText: String = "",
        override val isTurnStart: Boolean = false,
    ) : ChatListItem

    /** 计划审批卡片（作为持久历史消息留在对话流中，无论后续聊多久均可翻回点击 Proceed） */
    data class PlanApproval(
        override val key: String,
        val request: PlanApprovalRequest,
        override val isTurnStart: Boolean = false,
    ) : ChatListItem

    /** 单轮文件变更汇总卡片（位于正文与 Footer 之间，展示 N files changed +X -Y 及文件清单） */
    data class TurnDiffCard(
        override val key: String,
        val summary: xyz.mederi.core.contract.models.TurnDiffSummaryUi,
        val messageId: String,
        override val isTurnStart: Boolean = false,
    ) : ChatListItem

    /** 系统事件卡片（如子代理主动汇报事件） */
    data class EventMessageCard(
        override val key: String,
        val eventType: String,
        val agentId: String,
        val status: String,
        val role: String,
        val subtaskInfo: String?,
        val reportPath: String?,
        val summary: String,
        override val isTurnStart: Boolean = false,
        val messageId: String = "",
        val createdAt: Long = 0L,
    ) : ChatListItem
}

/**
 * assistant 消息底部 footer 的元数据（预计算，View 零逻辑）。
 * 数据来自 core Message 诊断字段（modelName/agentMode/reasoningLevel/durationMs），
 * 经契约 ChatMessage 透传到 UI。
 */
data class AssistantFooterInfo(
    val modelName: String? = null,
    /** APPROVAL / AUTONOMOUS */
    val agentMode: String? = null,
    /** 推理档位名称（如 HIGH） */
    val thinkingLevel: String? = null,
    val durationMs: Long? = null,
    /** 回复结束时刻（epoch millis）≈ createdAt + durationMs */
    val completedAtMs: Long? = null,
)

data class PlanItem(
    val id: String,
    val title: String,
    val content: String
)

enum class PlanOverviewStatus {
    PendingApproval, // 待批准
    InProgress,      // 执行中
    Completed,       // 已完成
    Voided           // 已作废
}

data class PlanSubtaskOverview(
    val index: Int,
    val name: String,
    val status: String,
    val spec: String?,
    val planDetail: String
)

data class PlanOverviewItem(
    val id: String,
    val title: String,
    val summary: String,
    val planPath: String,
    val planContent: String?,
    val status: PlanOverviewStatus,
    val subtasks: List<PlanSubtaskOverview>
)

sealed interface ArtifactItem {
    val id: String
    val title: String

    data class Text(
        override val id: String,
        override val title: String,
        val content: String,
        val lineCount: Int = 0,
        val charCount: Int = 0,
        val isStreaming: Boolean = false,
    ) : ArtifactItem

    data class Image(
        override val id: String,
        override val title: String,
        val imageUrl: String
    ) : ArtifactItem
}