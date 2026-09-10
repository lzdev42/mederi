package xyz.mederi.core.contract.models

import kotlinx.serialization.Serializable

enum class ConversationStatus {
    Idle,
    Working,
    Error
}

@Serializable
data class Conversation(
    val id: String,
    val projectId: String?,
    val title: String,
    val status: ConversationStatus,
    val createdAt: Long,
    val updatedAt: Long,
    val parentConversationId: String? = null,
    /** 会话绑定的模型 ID（来自 session.model.id）。点开会话时用于回填输入框模型选择。 */
    val modelId: String? = null,
    /** 会话绑定的供应商 ID（来自 session.model.providerID）。 */
    val modelProvider: String? = null,
    /**
     * 会话实际使用的推理档位（仅回显 core session.reasoningLevel——send 时 core 写回的诊断记录）。
     * **不是** UI 推理档位的真理源：显示与发送一律经 ReasoningMenu.resolve 推导（模型记忆 > 默认档）。
     */
    val thinkingLevel: String? = null,
    /** 会话绑定的 Agent ID（来自 session.agent）。点开会话时回填 Agent 选择。 */
    val agent: String? = null,
    /** 会话挂载目录（来自 session.location.directory）。用于本地按项目子目录分组。 */
    val directory: String? = null,
    /** 工作用途（来自 session.workType）。用于区分 Code / Work 过滤显示。 */
    val workType: WorkType = WorkType.CODE,
)

enum class ChatRole { User, Assistant, System, Summary }

@Serializable
data class ChatMessage(
    val id: String,
    val conversationId: String,
    val role: ChatRole,
    val blocks: List<ChatBlock>,
    val createdAt: Long,
    val completedAt: Long?,
    val parentMessageId: String?,
    val model: String?,
    val agent: String?,
    val isStreaming: Boolean = false,
    val error: String? = null,
)

/**
 * 消息内容块。多态序列化（kotlinx 默认 "type" 判别字段），可跨进程传输
 * （server → wasmJs 浏览器）。
 */
@Serializable
sealed class ChatBlock {
    abstract val id: String

    @Serializable
    data class Text(
        override val id: String,
        val text: String,
    ) : ChatBlock()

    @Serializable
    data class Reasoning(
        override val id: String,
        val text: String,
    ) : ChatBlock()

    @Serializable
    data class ToolCall(
        override val id: String,
        val name: String,
        val state: ToolCallState,
    ) : ChatBlock()

    @Serializable
    data class File(
        override val id: String,
        val name: String,
        val url: String,
        val mimeType: String?,
    ) : ChatBlock()

    @Serializable
    data class Diff(
        override val id: String,
        val filePath: String,
        val before: String,
        val after: String,
    ) : ChatBlock()

    @Serializable
    data class Unknown(
        override val id: String,
        val type: String,
    ) : ChatBlock()
}

@Serializable
sealed class ToolCallState {
    @Serializable
    data object Pending : ToolCallState()

    @Serializable
    data class Running(
        val input: Map<String, String> = emptyMap(),
    ) : ToolCallState()

    @Serializable
    data class Completed(
        val input: Map<String, String> = emptyMap(),
        val output: String,
    ) : ToolCallState()

    @Serializable
    data class Failed(
        val input: Map<String, String> = emptyMap(),
        val error: String,
    ) : ToolCallState()
}

/**
 * 展平后的工具调用展示模型（ViewModel 预计算，View 零逻辑）。
 *
 * @param target 核心目标参数单行摘要（文件路径 / 命令等），由 ViewModel 从 input 键探测得出
 */
@Serializable
data class ToolCallUi(
    val id: String,
    val name: String,
    val state: ToolCallState,
    val target: String? = null,
    val isFailed: Boolean = false,
)

/**
 * 契约层全局事件类型。
 */
enum class CoreEventType {
    SESSION_CREATED,
    SESSION_UPDATED,
    MESSAGE_DELTA,
    MESSAGE_COMPLETED,
    MESSAGE_ERROR,
    TOOL_CALLED,
    TOOL_RESULT,
    QUESTION_REQUESTED,
    QUESTION_RESOLVED,
    PLAN_APPROVAL_REQUESTED,
    PLAN_APPROVAL_RESOLVED,
    PLAN_PROGRESS,

    /**
     * 环境态状态事件（不改变会话状态机、不落库）：供应商限流重试等"正在发生但不是
     * 业务结果"的事实。payload: scope/code/message/attempt/maxAttempts。
     * UI 据此在状态栏显示"重试中"；会话 status 仍为 Working，不进 Error。
     */
    STATUS
}

/**
 * 契约层会话/系统事件。
 */
@Serializable
data class CoreEvent(
    val type: CoreEventType,
    val sessionId: String,
    val messageId: String? = null,
    val payload: Map<String, String> = emptyMap(),
    val timestamp: String = ""
)

/**
 * 用户粘贴的大段文本项。
 */
@Serializable
data class PastedTextAttachment(
    val id: String,
    val index: Int,
    val text: String,
    val lineCount: Int,
    val charCount: Int
)

/**
 * 待发送图片项。
 */
@Serializable
data class ImageAttachment(
    val id: String,
    val name: String,
    val mimeType: String,
    val bytes: ByteArray,
    val base64DataUrl: String,
    val width: Int = 0,
    val height: Int = 0
) {
    override fun equals(other: Any?): Boolean = other is ImageAttachment &&
        id == other.id && name == other.name && mimeType == other.mimeType && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * id.hashCode() + bytes.contentHashCode()
}
