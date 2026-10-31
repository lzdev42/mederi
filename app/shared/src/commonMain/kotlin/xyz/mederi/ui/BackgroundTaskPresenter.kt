package xyz.mederi.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import mederi.app.shared.generated.resources.*
import xyz.mederi.core.contract.AiCore
import xyz.mederi.core.contract.models.BackgroundTaskInfo

/** Core列表的会话级表现快照；查询反馈与停止反馈分别保留。 */
internal data class BackgroundTaskQueryState(
    val sessionId: String? = null,
    val tasks: List<BackgroundTaskInfo> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val queryError: UiMessage? = null,
    val stoppingTaskIds: Set<String> = emptySet(),
    val stopErrors: Map<String, UiMessage> = emptyMap(),
)

/** 仅观察Core；所有入口由窗口VM/Route在UI线程调用，工作生命周期归Core。 */
internal class BackgroundTaskPresenter(
    private val core: AiCore,
    private val scope: CoroutineScope,
    private val intervalMillis: Long = 2000L,
) {
    private val sessions = mutableStateMapOf<String, BackgroundTaskQueryState>()
    private var activeId by mutableStateOf<String?>(null)
    val state: BackgroundTaskQueryState get() = activeId?.let { sessions[it] } ?: BackgroundTaskQueryState()
    private var observers = 0
    private var generation = 0L
    private var job: Job? = null
    private var pollingSession: String? = null
    private var refreshSignal: Channel<Unit>? = null
    private var closed = false

    fun bind(sessionId: String?) {
        if (closed || activeId == sessionId) return
        cancelPolling()
        activeId = sessionId
        sessionId?.let { sessions.getOrPut(it) { BackgroundTaskQueryState(sessionId = it) } }
        startPolling()
    }

    /** 订阅是幂等释放的租约；多个视图不会建立多条轮询。 */
    fun observe(): () -> Unit {
        if (closed) return {}
        observers++
        if (observers == 1) startPolling()
        var released = false
        return {
            if (!released) {
                released = true
                observers--
                if (observers == 0) cancelPolling()
            }
        }
    }

    fun refresh() {
        if (closed || observers == 0 || activeId == null) return
        if (job == null) startPolling() else refreshSignal?.trySend(Unit)
    }

    private fun isCurrent(sessionId: String, token: Long): Boolean =
        !closed && observers > 0 && activeId == sessionId && generation == token

    private fun cancelPolling() {
        generation++
        job?.cancel(); job = null
        refreshSignal?.close(); refreshSignal = null
        pollingSession?.let { id -> sessions[id]?.let { sessions[id] = it.copy(loading = false) } }
        pollingSession = null
    }

    private fun startPolling() {
        val id = activeId ?: return
        if (closed || observers == 0 || job != null) return
        val token = ++generation
        val signal = Channel<Unit>(Channel.CONFLATED)
        refreshSignal = signal
        pollingSession = id
        // LAZY确保job引用先完成赋值，避免立即执行完成时覆盖后来启动的job。
        sessions[id] = sessions.getValue(id).copy(loading = true)
        val launched = scope.launch(start = CoroutineStart.LAZY) {
            try {
                core.isReady.first { it }
                while (isActive && isCurrent(id, token)) {
                    read(id, token)
                    if (!isCurrent(id, token)) break
                    withTimeoutOrNull(intervalMillis) { signal.receive() }
                }
            } finally {
                if (isCurrent(id, token)) {
                    sessions[id] = sessions.getValue(id).copy(loading = false)
                    job = null
                    refreshSignal = null
                    pollingSession = null
                }
            }
        }
        job = launched
        launched.start()
    }

    private suspend fun read(id: String, token: Long) {
        if (!isCurrent(id, token)) return
        sessions[id] = sessions.getValue(id).copy(loading = true)
        val result = try { core.listBackgroundTasks(id) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { Result.failure(failure) }
        // 网络/宿主可能不响应取消；代际校验不能省略。
        if (!isCurrent(id, token)) return
        result.fold(onSuccess = { tasks ->
            sessions[id] = sessions.getValue(id).copy(tasks = tasks, loading = false, loaded = true, queryError = null)
        }, onFailure = { failure ->
            if (failure is CancellationException) throw failure
            sessions[id] = sessions.getValue(id).copy(loading = false,
                queryError = UiMessage(Res.string.background_tasks_query_failed, listOf(failure.message.orEmpty())))
        })
    }

    fun stop(taskId: String) {
        val id = activeId ?: return
        if (closed) return
        val current = sessions[id] ?: return
        if (taskId in current.stoppingTaskIds || current.tasks.none { it.taskId == taskId && it.status == "running" }) return
        sessions[id] = current.copy(stoppingTaskIds = current.stoppingTaskIds + taskId, stopErrors = current.stopErrors - taskId)
        scope.launch {
            try {
                val result = try { core.stopBackgroundTask(taskId) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { Result.failure(failure) }
                if (closed) return@launch
                val latest = sessions[id] ?: return@launch
                val failure = result.exceptionOrNull()
                if (failure is CancellationException) throw failure
                sessions[id] = latest.copy(stopErrors = if (result.isSuccess) latest.stopErrors - taskId else latest.stopErrors +
                    (taskId to UiMessage(Res.string.background_task_stop_failed, listOf(taskId, failure?.message.orEmpty()))))
                if (result.isSuccess && activeId == id) refresh()
            } finally {
                if (!closed) sessions[id]?.let { sessions[id] = it.copy(stoppingTaskIds = it.stoppingTaskIds - taskId) }
            }
        }
    }

    fun dismissStopError(taskId: String) {
        val id = activeId ?: return
        sessions[id]?.let { sessions[id] = it.copy(stopErrors = it.stopErrors - taskId) }
    }

    fun close() {
        closed = true
        cancelPolling()
    }
}
