package xyz.mederi.ui

import androidx.compose.runtime.*

internal sealed interface FeedbackOwner {
    data class Session(val id: String) : FeedbackOwner
    data class Draft(val projectId: String?) : FeedbackOwner
}

/** 仅为本地操作的反馈票据，不控制Core操作是否执行。 */
internal data class FeedbackOperation(val owner: FeedbackOwner, val sequence: Long)

/**
 * Core投影、本地失败与用户关闭意图分开。Core字段只由accept更新；本地失败没有Core诊断/断流能力。
 * 所有入口由VM串行调用，异步操作只携带原目标票据；不取消发送或回滚。
 */
internal class WorkspaceFeedbackPresenter {
    private data class Entry(
        val core: ErrorState = ErrorState(),
        val local: UiMessage? = null,
        val dismissedCore: ErrorState? = null,
        val detailFor: ErrorState? = null,
        val sequence: Long = 0,
    ) {
        fun visible(): ErrorState = local?.let { ErrorState(message = it) }
            ?: core.takeUnless { it == dismissedCore } ?: ErrorState()
    }
    private val entries = mutableStateMapOf<FeedbackOwner, Entry>()
    private var active by mutableStateOf<FeedbackOwner?>(null)
    val state: ErrorState get() {
        val entry = active?.let { entries[it] } ?: Entry()
        val visible = entry.visible()
        return visible.copy(isDetailOpen = entry.detailFor != null && entry.detailFor == visible)
    }
    fun bind(owner: FeedbackOwner) {
        if (active == owner) return
        active?.let { key -> entries[key]?.let { entries[key] = it.copy(detailFor = null) } }
        active = owner
    }
    fun accept(owner: FeedbackOwner, core: ErrorState) {
        val clean = core.copy(isDetailOpen = false)
        val old = entries[owner] ?: Entry()
        if (old.core == clean) return
        val hasError = clean.message != null || !clean.diagnostic.isNullOrBlank() || clean.isStreamInterrupted
        entries[owner] = old.copy(core = clean, dismissedCore = null, detailFor = null,
            local = if (hasError) null else old.local,
            sequence = if (hasError) old.sequence + 1 else old.sequence)
    }
    fun begin(owner: FeedbackOwner): FeedbackOperation {
        val old = entries[owner] ?: Entry()
        val next = old.copy(local = null, dismissedCore = old.core, detailFor = null, sequence = old.sequence + 1)
        entries[owner] = next
        return FeedbackOperation(owner, next.sequence)
    }
    fun isCurrent(operation: FeedbackOperation): Boolean = entries[operation.owner]?.sequence == operation.sequence

    fun publish(operation: FeedbackOperation, message: UiMessage) {
        val old = entries[operation.owner] ?: return
        if (old.sequence != operation.sequence) return
        entries[operation.owner] = old.copy(local = message, detailFor = null)
    }
    fun showLocal(owner: FeedbackOwner, message: UiMessage) { publish(begin(owner), message) }
    fun dismiss() {
        val key = active ?: return
        val old = entries[key] ?: Entry()
        entries[key] = old.copy(local = null, dismissedCore = old.core, detailFor = null, sequence = old.sequence + 1)
    }
    fun showDetail() {
        val key = active ?: return
        val old = entries[key] ?: Entry()
        val visible = old.visible()
        if (visible.message != null || !visible.diagnostic.isNullOrBlank()) entries[key] = old.copy(detailFor = visible)
    }
    fun dismissDetail() {
        val key = active ?: return
        entries[key]?.let { entries[key] = it.copy(detailFor = null) }
    }
}
