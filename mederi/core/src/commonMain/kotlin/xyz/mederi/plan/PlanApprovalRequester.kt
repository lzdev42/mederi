package xyz.mederi.plan

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import xyz.mederi.domain.model.EventType
import xyz.mederi.domain.model.MederiEvent
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import xyz.mederi.debug.DebugLog

data class PlanApprovalResult(
    val approved: Boolean,
    val superseded: Boolean = false
)

class PlanApprovalRequester(
    private val sessionId: String,
    private val eventBus: MutableSharedFlow<MederiEvent>
) {
    private var pendingId: String? = null
    private var pendingDeferred: CompletableDeferred<PlanApprovalResult>? = null

    suspend fun request(
        planId: String,
        planPath: String,
        title: String,
        summary: String = "",
        planContent: String = "",
        subtaskCount: Int
    ): PlanApprovalResult {
        cancelCurrent("superseded by new plan request")

        val deferred = CompletableDeferred<PlanApprovalResult>()
        pendingId = planId
        pendingDeferred = deferred

        DebugLog.data(
            "PlanApprovalRequester",
            "emitting PLAN_APPROVAL_REQUESTED",
            "planId=$planId, title='$title', summary='$summary', contentLen=${planContent.length}, path='$planPath'"
        )

        eventBus.emit(MederiEvent(
            type = EventType.PLAN_APPROVAL_REQUESTED,
            sessionId = sessionId,
            payload = mapOf(
                "planId" to planId,
                "planPath" to planPath,
                "title" to title,
                "summary" to summary,
                "subtaskCount" to subtaskCount.toString(),
                "planContent" to planContent
            ),
            timestamp = Instant.now().toString()
        ))

        return deferred.await()
    }

    suspend fun resolve(planId: String, approved: Boolean): Boolean {
        if (planId != pendingId) return false
        val deferred = pendingDeferred ?: return false
        pendingId = null
        pendingDeferred = null
        deferred.complete(PlanApprovalResult(approved = approved))

        eventBus.emit(MederiEvent(
            type = EventType.PLAN_APPROVAL_RESOLVED,
            sessionId = sessionId,
            payload = mapOf(
                "planId" to planId,
                "approved" to approved.toString()
            ),
            timestamp = Instant.now().toString()
        ))
        return true
    }

    fun hasPending(): Boolean = pendingDeferred?.isActive == true

    fun cancelAll() {
        cancelCurrent("cancelled")
    }

    private fun cancelCurrent(reason: String) {
        pendingId = null
        pendingDeferred?.let { it.complete(PlanApprovalResult(approved = false, superseded = true)) }
        pendingDeferred = null
    }
}
