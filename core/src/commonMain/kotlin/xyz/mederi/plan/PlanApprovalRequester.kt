package xyz.mederi.plan

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import xyz.mederi.domain.model.EventType
import xyz.mederi.domain.model.MederiEvent
import java.time.Instant
import xyz.mederi.debug.DebugLog

data class PlanApprovalResult(
    val approved: Boolean,
    val superseded: Boolean = false
)

class PlanApprovalRequester(
    private val sessionId: String,
    private val eventBus: MutableSharedFlow<MederiEvent>
) {
    private class Pending(
        val id: String,
        val commitApproval: () -> Boolean,
        val result: CompletableDeferred<PlanApprovalResult> = CompletableDeferred(),
    )

    private val lock = Any()
    private var pending: Pending? = null

    suspend fun request(
        planId: String,
        planPath: String,
        title: String,
        summary: String = "",
        subtaskCount: Int,
        subtasksJson: String = "",
        replacesPlanId: String? = null,
        commitApproval: () -> Boolean,
    ): PlanApprovalResult {
        val request = Pending(planId, commitApproval)
        synchronized(lock) {
            pending?.result?.complete(PlanApprovalResult(approved = false, superseded = true))
            pending = request
        }

        DebugLog.data(
            "PlanApprovalRequester",
            "emitting PLAN_APPROVAL_REQUESTED",
            "planId=$planId, title='$title', summary='$summary', path='$planPath'"
        )

        val payloadMap = mutableMapOf(
            "planId" to planId,
            "planPath" to planPath,
            "title" to title,
            "summary" to summary,
            "subtaskCount" to subtaskCount.toString()
        )
        if (subtasksJson.isNotBlank()) {
            payloadMap["subtasks"] = subtasksJson
        }
        replacesPlanId?.let { payloadMap["replacesPlanId"] = it }

        return try {
            eventBus.emit(MederiEvent(
                type = EventType.PLAN_APPROVAL_REQUESTED,
                sessionId = sessionId,
                payload = payloadMap,
                timestamp = Instant.now().toString()
            ))
            request.result.await()
        } finally {
            synchronized(lock) { if (pending === request) pending = null }
        }
    }

    suspend fun resolve(planId: String, approved: Boolean): Boolean {
        val request = synchronized(lock) {
            val request = pending?.takeIf { it.id == planId } ?: return false
            if (approved && !request.commitApproval()) return false
            pending = null
            request
        }
        try {
            eventBus.emit(MederiEvent(
                type = EventType.PLAN_APPROVAL_RESOLVED,
                sessionId = sessionId,
                payload = mapOf(
                    "planId" to planId,
                    "approved" to approved.toString()
                ),
                timestamp = Instant.now().toString()
            ))
        } finally {
            request.result.complete(PlanApprovalResult(approved = approved))
        }
        return true
    }

    fun hasPending(): Boolean = synchronized(lock) { pending?.result?.isActive == true }

    fun cancelAll() = synchronized(lock) {
        pending?.result?.complete(PlanApprovalResult(approved = false, superseded = true))
        pending = null
    }
}
