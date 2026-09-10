package xyz.mederi.store

import xyz.mederi.domain.model.Message

/**
 * 对话历史存储接口。
 *
 * 管理一个 session 内所有消息的持久化。
 *
 * 核心操作：
 * - [load]: 加载完整历史，用于构建 Prompt
 * - [append]: 实时追加单条消息
 * - [replace]: 压缩后全量替换
 * - [delete]: 删除整个 session 的历史
 * - [rollbackTo]: 回退到指定消息点（删除该序号之后的所有消息）
 * - [listSummary]: 获取摘要列表（不含完整数据，用于列表预览）
 *
 * 内置实现：SqliteHistoryStore（基于 SQLDelight + SQLite）
 * 企业实现：自行实现此接口，用 PostgreSQL / MySQL / MongoDB 等
 *
 * 不传此接口 = 不持久化，历史仅存在于 agent 运行期间的内存中。
 */
interface HistoryStore {
    /**
     * 加载指定 session 的完整对话历史。
     *
     * @param sessionId Session ID。
     * @return 按序号升序排列的消息列表。如果 session 不存在或没有历史，返回空列表。
     */
    suspend fun load(sessionId: String): List<Message>

    /**
     * 追加单条消息到指定 session 的历史。
     *
     * 用于 agent 运行中的实时同步：用户消息、LLM 响应、工具调用/结果等。
     *
     * @param sessionId Session ID。
     * @param message 要追加的消息。
     */
    suspend fun append(sessionId: String, message: Message)

    /**
     * 全量替换指定 session 的对话历史。
     *
     * 用于历史压缩操作（如 replaceHistoryWithTLDR、dropLastNMessages、leaveLastNMessages）后，
     * 将压缩后的消息列表整体替换原有历史。
     *
     * @param sessionId Session ID。
     * @param messages 替换后的完整消息列表。
     */
    suspend fun replace(sessionId: String, messages: List<Message>)

    /**
     * 删除指定 session 的所有对话历史。
     *
     * Session 删除时调用，清理相关数据。
     *
     * @param sessionId Session ID。
     */
    suspend fun delete(sessionId: String)

    /**
     * 回退到指定消息点。
     *
     * 删除 seq 之后的所有消息，保留 seq 及之前的消息。
     * 用于"退回到某段对话重新开始"的场景。
     *
     * @param sessionId Session ID。
     * @param seq 要回退到的消息序号（保留该序号及之前的消息）。
     */
    suspend fun rollbackTo(sessionId: String, seq: Long)

    /**
     * 获取历史摘要列表（用于列表预览）。
     *
     * 返回每条消息的摘要信息，不包含完整的序列化数据。
     * 比加载完整历史更轻量，适合 UI 列表展示。
     *
     * @param sessionId Session ID。
     * @return 摘要列表，按序号升序排列。
     */
    suspend fun listSummary(sessionId: String): List<MessageSummary>

    /**
     * 原始消息直读（调试用）：返回每条消息落库时的原始 JSON payload，不做任何解析/映射。
     *
     * 这是"原始消息"功能的数据源——存储层即真相，UI 渲染只是投影。
     * debug 时直接看模型实际看到/产出的 JSON（含工具调用参数、工具结果原文、
     * token 用量、诊断元数据），绕过所有 UI 渲染层。
     *
     * @param sessionId Session ID。
     * @return 原始 JSON 记录列表，按 seq 升序。
     */
    suspend fun listRaw(sessionId: String): List<RawMessageRecord>
}

/**
 * 原始消息记录（调试用）：payload 是落库时的原始 JSON 字符串，原样透传。
 * 诊断字段从同步写入的诊断列读取——查看器列表可直接按模型筛选/按时长排序，无需解析 payload。
 */
data class RawMessageRecord(
    val seq: Long,
    val messageId: String?,
    val role: String,
    val payload: String,
    val createdAt: String,
    val modelId: String? = null,
    val durationMs: Long? = null,
    val finishReason: String? = null,
    val status: String? = null
)

/**
 * 消息摘要（用于列表预览，不含完整数据）。
 *
 * @param seq 消息序号。
 * @param messageId Koog Message.id（可空）。
 * @param role 消息角色（SYSTEM / USER / ASSISTANT）。
 * @param content 摘要文本（取消息中第一个文本片段的内容）。
 * @param createdAt 创建时间（ISO 8601）。
 */
data class MessageSummary(
    val seq: Long,
    val messageId: String?,
    val role: String,
    val content: String,
    val createdAt: String
)
