package xyz.mederi.core.contract

import xyz.mederi.core.contract.dto.ConversationState
import xyz.mederi.core.contract.models.ConversationStatus
import xyz.mederi.core.contract.models.CoreEvent
import xyz.mederi.core.contract.models.CoreEventType
import xyz.mederi.core.contract.models.ExecutionApprovalRequest
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** WaitingUser 是所有存活交互的投影，不能由单个 RESOLVED 事件直接清除。 */
fun ConversationState.withPendingInteractionStatus(): ConversationState =
    if (pendingQuestion != null || pendingPlanApproval?.status in setOf("PENDING", "PENDING_APPROVAL") ||
        pendingExecutionApprovals.isNotEmpty()) {
        copy(conversation = conversation.copy(status = ConversationStatus.WaitingUser))
    } else this

/** 两个桥的侧边栏共用的只读投影；刷新 API 与事件收集可跨协程访问。 */
internal class PendingInteractionTracker {
    private val pending = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    private val json = Json { ignoreUnknownKeys = true }

    fun accept(event: CoreEvent) {
        pending.update { current ->
            val ids = current[event.sessionId].orEmpty().toMutableSet()
            when (event.type) {
                CoreEventType.QUESTION_REQUESTED -> event.payload["questionId"]?.let { ids.add("question:$it") }
                CoreEventType.QUESTION_RESOLVED -> ids.removeAll { it.startsWith("question:") }
                CoreEventType.PLAN_APPROVAL_REQUESTED -> event.payload["planId"]?.let { ids.add("plan:$it") }
                CoreEventType.PLAN_APPROVAL_RESOLVED -> event.payload["planId"]?.let { ids.remove("plan:$it") }
                CoreEventType.EXECUTION_APPROVAL_REQUESTED -> event.payload["request"]?.let { raw ->
                    runCatching { json.decodeFromString<ExecutionApprovalRequest>(raw) }.getOrNull()
                        ?.takeIf { it.conversationId == event.sessionId }?.let { ids.add("execution:${it.id}") }
                }
                CoreEventType.EXECUTION_APPROVAL_RESOLVED -> event.payload["requestId"]?.let { ids.remove("execution:$it") }
                CoreEventType.SESSION_UPDATED -> if (event.payload["inputAccepted"] != "true") {
                    ids.removeAll { it.startsWith("question:") || it.startsWith("plan:") }
                }
                CoreEventType.MESSAGE_COMPLETED, CoreEventType.MESSAGE_ERROR ->
                    ids.removeAll { it.startsWith("question:") || it.startsWith("plan:") }
                else -> Unit
            }
            if (ids.isEmpty()) current - event.sessionId else current + (event.sessionId to ids.toSet())
        }
    }

    fun isWaiting(conversationId: String): Boolean = pending.value[conversationId]?.isNotEmpty() == true
}
