package xyz.mederi.domain.model

import kotlinx.serialization.Serializable

/** 对话模型请求的账本记录。子代理请求归父会话，保留实际执行会话标识。 */
@Serializable
data class ModelRequestRecord(
    val requestId: String,
    val ownerSessionId: String,
    val executionSessionId: String,
    val startedAt: String,
    val usage: Message? = null,
)
