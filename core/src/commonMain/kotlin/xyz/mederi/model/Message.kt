package xyz.mederi.domain.model

import kotlinx.serialization.Serializable

/**
 * 消息角色。
 *
 * 对应 Koog 的 Message.Role 枚举。
 * 注意：Koog 没有 TOOL 角色，工具调用/结果是 MessagePart，分别属于 Assistant 和 User。
 *
 * SUMMARY 是压缩标记消息：内容为 LLM 生成的 TLDR 总结。
 * 它既是对话历史的一部分（UI 可见），也是压缩节点——发给 AI 时，
 * 只发送最后一条 SUMMARY 及其之后的消息，之前的历史不进 prompt。
 */
@Serializable
enum class MessageRole {
    SYSTEM, USER, ASSISTANT, SUMMARY
}

/**
 * 消息状态。
 *
 * 这是 Mederi 的扩展字段，Koog 的 Message 没有状态概念。
 * 用于跟踪消息在 agent loop 中的处理状态。
 */
@Serializable
enum class MessageStatus {
    PROCESSING, COMPLETED, ERROR
}

/**
 * 消息内容片段。
 *
 * 对应 Koog 的 MessagePart sealed interface。
 * 一个消息可包含多个片段（如文本 + 工具调用 + 推理过程）。
 *
 * 使用 sealed class + kotlinx.serialization，序列化为 JSON 存储到 SQL data 列。
 */
@Serializable
sealed class MessagePart {

    /** 文本内容 */
    @Serializable
    data class Text(val text: String) : MessagePart()

    /** 图片附件（URL 或 base64） */
    @Serializable
    data class Image(val url: String, val mimeType: String? = null) : MessagePart()

    /** 文件附件（路径引用） */
    @Serializable
    data class File(val path: String) : MessagePart()

    /** 工具调用（出现在 Assistant 消息中） */
    @Serializable
    data class ToolCall(
        val id: String? = null,
        val tool: String,
        val args: String
    ) : MessagePart()

    /**
     * 工具结果（出现在 User 消息中）——原始 JSON 真相的一部分。
     *
     * [status]/[durationMs]/[error] 补齐工具执行生命周期：
     * debug 时可直接看出"AI 断了"是 LLM 没发工具调用，还是工具执行失败/超时。
     */
    @Serializable
    data class ToolResult(
        val id: String? = null,
        val tool: String,
        val output: String,
        val isError: Boolean = false,
        val status: String? = null,
        val durationMs: Long? = null,
        val error: String? = null
    ) : MessagePart()

    /** 推理/思考过程（出现在 Assistant 消息中，对应 Koog 的 Reasoning） */
    @Serializable
    data class Reasoning(
        val content: List<String>,
        val summary: List<String>? = null,
        val encrypted: String? = null,
        val id: String? = null
    ) : MessagePart()
}

/**
 * 对话历史中的一条消息。
 *
 * 这是 Mederi 的存储模型，对应 Koog 的 Message sealed interface。
 * 包含 Koog Message 的核心字段，以及 Mederi 扩展的状态字段。
 *
 * 完整序列化为 JSON 存储到 SQL data 列，同时提取关键字段到独立列用于查询。
 *
 * @param id 消息唯一 ID（对应 Koog Message.id，可空）。
 * @param sessionId 所属 Session ID（Mederi 扩展，用于关联）。
 * @param role 消息角色。
 * @param parts 消息内容片段列表。
 * @param status 消息状态（Mederi 扩展）。
 * @param createdAt 创建时间，ISO 8601（从 metaInfo.timestamp 提取）。
 * @param finishReason 停止原因（仅 Assistant 消息，如 "stop"、"tool_calls"）。
 * @param totalTokens token 总数（仅 Assistant 消息，从 ResponseMetaInfo 提取）。
 * @param inputTokens 输入 token 数（仅 Assistant 消息）。
 * @param outputTokens 输出 token 数（仅 Assistant 消息）。
 * @param cachedTokens 缓存命中 token 数（仅 Assistant 消息，部分供应商支持）。
 * @param providerId 供应商 ID（原始消息诊断字段）。
 * @param modelId 模型 ID（原始消息诊断字段）。
 * @param agentMode Agent 模式（原始消息诊断字段）。
 * @param projectId 所属项目（原始消息诊断字段）。
 * @param durationMs LLM 请求耗时毫秒（仅 Assistant 消息；从响应创建到落库的近似值）。
 */
@Serializable
data class Message(
    val id: String? = null,
    val sessionId: String,
    val role: MessageRole,
    val parts: List<MessagePart>,
    val status: MessageStatus = MessageStatus.COMPLETED,
    val createdAt: String,
    val finishReason: String? = null,
    val totalTokens: Int? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val cachedTokens: Int? = null,
    val providerId: String? = null,
    val modelId: String? = null,
    /** 模型显示名（AIModel.name，原始消息诊断字段）——footer 展示用 */
    val modelName: String? = null,
    /** 实际使用的推理档位（effectiveReasoningLevel，原始消息诊断字段）——footer 展示用 */
    val reasoningLevel: String? = null,
    val agentMode: String? = null,
    val projectId: String? = null,
    val durationMs: Long? = null,
    /** 本轮 Turn 产生的文件变更摘要（仅本轮最后一条 Assistant 消息持有） */
    val turnDiffSummary: xyz.mederi.tools.diff.TurnDiffSummary? = null
)

/**
 * UI 隐藏标记：消息文本中该标记之后的内容（元数据，如发送时间）不展示给用户，但会发给 AI。
 * 选 `<<<...>>>` 三尖括号形式——任何 Markdown 渲染器都不会把它解析为结构，字符串本身也极难与用户输入撞车。
 */
const val UI_HIDDEN_MARKER = "<<<NOT_FOR_UI>>>"
