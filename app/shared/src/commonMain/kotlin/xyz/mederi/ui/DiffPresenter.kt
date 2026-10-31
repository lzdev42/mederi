package xyz.mederi.ui

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import mederi.app.shared.generated.resources.*
import xyz.mederi.core.contract.AiCore

/** 只管理Core查询的本地表现，不控制Dock，也不改变任何文件或任务。入口由VM串行调用。 */
internal class DiffPresenter(private val core: AiCore, private val scope: CoroutineScope) {
    private val sessions = mutableStateMapOf<String, DiffUiState>()
    private var sessionId by mutableStateOf<String?>(null)
    val state: DiffUiState get() = sessionId?.let { sessions[it] } ?: DiffUiState()
    private var visible = false
    private var generation = 0L
    private var job: Job? = null
    private var closed = false
    private var pendingOpen = false

    fun bind(id: String?) {
        if (closed || sessionId == id) return
        cancel()
        visible = false
        pendingOpen = false
        sessionId = id
    }

    fun setVisible(value: Boolean) {
        if (closed) return
        visible = value
        if (!value) cancel()
        else if (state.requested && !state.loading && (pendingOpen || !state.loaded && state.error == null)) start()
    }

    fun open(messageId: String?, initialPath: String?) {
        if (closed || sessionId == null) return
        val prior = state
        val same = prior.requested && prior.messageId == messageId
        if (same && prior.loading) {
            if (initialPath != null) update(state.copy(selectedPath = initialPath))
            return
        }
        cancel()
        update(if (same) prior.copy(loading = false, error = null, selectedPath = initialPath ?: prior.selectedPath)
            else DiffUiState(messageId = messageId, requested = true, selectedPath = initialPath))
        pendingOpen = true
        if (visible) start()
    }

    fun refresh() {
        if (closed || !visible || sessionId == null || state.loading) return
        update(state.copy(requested = true, error = null))
        start()
    }

    fun select(path: String) {
        if (closed || state.items.none { it.filePath == path }) return
        update(state.copy(selectedPath = path))
    }

    private fun update(value: DiffUiState) { sessionId?.let { sessions[it] = value } }
    private fun cancel() {
        generation++
        job?.cancel(); job = null
        if (state.loading) update(state.copy(loading = false))
    }

    private fun start() {
        val id = sessionId ?: return
        pendingOpen = false
        val target = state.messageId
        val token = ++generation
        update(state.copy(loading = true, error = null))
        fun current() = !closed && visible && sessionId == id && generation == token
        val launched = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = try { core.getFileDiffs(id, target) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { Result.failure(failure) }
                if (!current()) return@launch
                val failure = result.exceptionOrNull()
                if (failure is CancellationException) throw failure
                if (result.isSuccess) {
                    val items = result.getOrThrow()
                    val selected = state.selectedPath?.takeIf { path -> items.any { it.filePath == path } } ?: items.firstOrNull()?.filePath
                    update(state.copy(items = items, selectedPath = selected, loaded = true, error = null))
                } else update(state.copy(error = UiMessage(Res.string.diff_load_failed, listOf(failure?.message.orEmpty()))))
            } finally {
                if (current()) { job = null; update(state.copy(loading = false)) }
            }
        }
        job = launched; launched.start()
    }

    fun close() { cancel(); closed = true; visible = false }
}
