package xyz.mederi.ui

import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.*
import mederi.app.shared.generated.resources.*

/** 仅为窗口阅读器的本地身份，不属于Core或持久化契约。 */
data class ReportKey(val sessionId: String, val eventId: String)
data class ReportRequest(
    val key: ReportKey,
    val path: String?,
    val summary: String,
    val planId: String? = null,
)

internal sealed interface ReportLoadState {
    data object Idle : ReportLoadState
    data object Loading : ReportLoadState
    data class Content(val text: String) : ReportLoadState
    data object Missing : ReportLoadState
    data class Error(val message: UiMessage) : ReportLoadState
}

/** 一次只加载当前阅读目标；缓存绑定完整请求及原文件来源，不使用后来会话的provider重读旧报告。 */
internal class ReportLoadPresenter(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private class Entry(
        val request: ReportRequest,
        val source: Any?,
        val read: (suspend () -> Result<String?>)?,
        var generation: Long = 0,
        var job: Job? = null,
    )
    private val entries = mutableMapOf<ReportKey, Entry>()
    private val states = mutableStateMapOf<ReportKey, ReportLoadState>()
    private var active: ReportKey? = null
    private var closed = false

    fun state(key: ReportKey): ReportLoadState = states[key] ?: ReportLoadState.Idle

    fun select(request: ReportRequest, source: Any?, read: (suspend () -> Result<String?>)?, force: Boolean = false) {
        if (closed) return
        if (active != request.key) deactivate()
        val prior = entries[request.key]
        if (prior == null || prior.request != request || prior.source !== source) {
            prior?.let { cancel(it) }
            entries[request.key] = Entry(request, source, read)
            states[request.key] = ReportLoadState.Idle
        }
        active = request.key
        val entry = entries.getValue(request.key)
        if (force) cancel(entry)
        if (force || state(request.key) == ReportLoadState.Idle) start(entry)
    }

    fun activate(key: ReportKey) {
        val entry = entries[key] ?: return
        select(entry.request, entry.source, entry.read)
    }

    fun retry(key: ReportKey) {
        if (active != key || closed) return
        val entry = entries[key] ?: return
        select(entry.request, entry.source, entry.read, force = true)
    }

    fun deactivate() {
        active?.let { key -> entries[key]?.let { if (state(key) == ReportLoadState.Loading) cancel(it) } }
        active = null
    }

    private fun cancel(entry: Entry) {
        entry.generation++
        entry.job?.cancel(); entry.job = null
        if (states[entry.request.key] == ReportLoadState.Loading) states[entry.request.key] = ReportLoadState.Idle
    }

    private fun current(entry: Entry, token: Long): Boolean = !closed && active == entry.request.key &&
        entries[entry.request.key] === entry && entry.generation == token

    private fun start(entry: Entry) {
        val key = entry.request.key
        if (entry.read == null) {
            states[key] = ReportLoadState.Missing
            return
        }
        val token = ++entry.generation
        states[key] = ReportLoadState.Loading
        val launched = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = try { withContext(dispatcher) { entry.read.invoke() } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { Result.failure(failure) }
                if (!current(entry, token)) return@launch
                val failure = result.exceptionOrNull()
                if (failure is CancellationException) throw failure
                states[key] = if (result.isFailure) ReportLoadState.Error(UiMessage(Res.string.report_load_failed, listOf(failure?.message.orEmpty())))
                else result.getOrNull()?.takeIf { it.isNotBlank() }?.let { ReportLoadState.Content(it) } ?: ReportLoadState.Missing
            } finally {
                if (current(entry, token)) {
                    entry.job = null
                    if (states[key] == ReportLoadState.Loading) states[key] = ReportLoadState.Idle
                }
            }
        }
        entry.job = launched
        launched.start()
    }

    fun close() {
        closed = true
        entries.values.forEach { cancel(it) }
        active = null
    }
}
