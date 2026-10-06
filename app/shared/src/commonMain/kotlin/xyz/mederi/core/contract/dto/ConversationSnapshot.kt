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
     * 主会话 LLM HTTP 请求累计次数：LLM_REQUEST_COMPLETED 事件逐次累加 +
     * refreshPage 从 assistant 消息数回填（历史会话对齐）。
     */
    val requestCount: Int = 0,
    /** 最近一次 LLM 请求的用量（LLM_REQUEST_COMPLETED 事件更新，refreshPage 回填）；null = 尚无请求 */
    val lastRequestUsage: LastRequestUsage? = null,
    /**
     * 环境态状态提示（STATUS 事件驱动，如"供应商限流，重试中 (3/10)"）：
     * 仅内存态、不落库；turn 正常推进（下个 delta/completed）即清除。
     * 与 [errorMessage] 语义不同——errorMessage 是终态错误，statusHint 是过程状态。
     */
    val statusHint: String? = null,
    /**
     * 错误 ID（ErrorCollector 生成）。JVM 端 UI 可经 `ErrorCollector.get(errorId)`
     * 取回完整 [ErrorRecord]（含堆栈、分类、严重级别、cause 链等富字段）。
     * null 表示无错误或错误来自旧路径。
     */
    val errorId: String? = null,
    /**
     * 完整诊断报告（人类可读，纯文本）——点击错误简报时展示的详情。
     * 跨进程（server → wasmJs）场景下这是详细信息的唯一载体；
     * JVM 端可直接展示本字段，或用 [errorId] 从 ErrorCollector 取富对象。
     */
    val errorDiagnostic: String? = null,
    /**
     * 当前错误是否为"流式连接提前中断"（断流，failureMode=PREMATURE_CLOSE）。
     * UI 据此在 ErrorBoard 显示"继续"按钮（重发 Continue 让模型续写半截回复）。
     * turn 正常推进（MESSAGE_DELTA/新的完成事件无错误）时复位为 false。
     */
    val errorIsStreamInterrupted: Boolean = false,
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
    /** 主会话 LLM HTTP 请求累计次数（= assistant 消息数），与 [ConversationSnapshot.requestCount] 同义 */
    val requestCount: Int = 0,
    /** 最近一次 LLM 请求的用量（取最后一条带 usage 的 assistant 消息）；null = 尚无 */
    val lastRequestUsage: LastRequestUsage? = null,
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
    /** 诊断列：从 core RawMessageRecord 同名透传（HistoryStore 已抽取），null = 无该诊断数据 */
    val modelId: String? = null,
    val durationMs: Long? = null,
    val finishReason: String? = null,
    val status: String? = null,
    /**
     * 投影字段（jvmMain 桥从 core payload JSON 提取，commonMain UI 只读不解析）：
     * summaryLabel = 消息摘要标签（如 "text"、"bash"、"user: hello..."），null = 提取失败/无。
     * inputTokens / outputTokens = 该条消息的 token 消耗，null = 无 token 数据。
     */
    val summaryLabel: String? = null,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
)
