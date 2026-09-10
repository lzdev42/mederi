package xyz.mederi.core.contract.dto

import kotlinx.serialization.Serializable
import xyz.mederi.core.contract.models.*

@Serializable
data class ConversationSnapshot(
    val conversation: Conversation,
    val messages: List<ChatMessage>,
    val tokenUsage: TokenUsage,
    /** 当前上下文真实占用（token）= 最近一条 Assistant 的 inputTokens，与自动压缩触发同源 */
    val contextUsedTokens: Long = 0,
    val cost: CostSummary,
    val pendingQuestion: QuestionRequest? = null,
    val pendingPlanApproval: PlanApprovalRequest? = null,
    val planApprovals: List<PlanApprovalRequest> = emptyList(),
    val todos: List<TodoItem> = emptyList(),
    val childConversations: List<Conversation> = emptyList(),
    val errorMessage: String? = null,
    /**
     * 环境态状态提示（STATUS 事件驱动，如"供应商限流，重试中 (3/10)"）：
     * 仅内存态、不落库；turn 正常推进（下个 delta/completed）即清除。
     * 与 [errorMessage] 语义不同——errorMessage 是终态错误，statusHint 是过程状态。
     */
    val statusHint: String? = null,
)

/**
 * 会话消息页：消息列表 + token 统计。
 *
 * MESSAGE_COMPLETED / MESSAGE_ERROR 后对齐落库数据时整体返回，
 * 避免 wasmJs 客户端从 contract ChatMessage 重算 token（core 的 inputTokens 等字段不在契约层）。
 */
@Serializable
data class MessagesPage(
    val messages: List<ChatMessage>,
    val tokenUsage: TokenUsage,
    /** 当前上下文真实占用（token）= 最近一条 Assistant 的 inputTokens */
    val contextUsedTokens: Long = 0,
)

/**
 * 原始消息记录（调试用）：[payload] 是消息落库时的原始 JSON 字符串，原样透传不解析。
 *
 * 这是"原始消息"功能的数据源——存储层即真相，UI 渲染只是投影。
 * 查看器直接展示这个 JSON：工具调用参数、工具结果原文、token 用量、
 * 诊断元数据（模型/供应商/模式/项目/耗时）全部在内。
 */
@Serializable
data class RawMessageDto(
    val seq: Long,
    val messageId: String? = null,
    val role: String,
    val payload: String,
    val createdAt: String,
)
