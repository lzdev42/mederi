package xyz.mederi.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.mederi.core.contract.AiCore
import xyz.mederi.core.contract.models.ChatGptAuthState
import xyz.mederi.core.contract.models.ChatGptUsage

/** 只保留授权交互投影；凭据和登录会话生命周期由 core 管理。 */
internal class ChatGptAuthorizationViewModel(private val core: AiCore) : ViewModel() {
    var auth by mutableStateOf<ChatGptAuthState?>(null)
        private set
    var usage by mutableStateOf<ChatGptUsage?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var browserOpenFailed by mutableStateOf(false)
        private set
    private var job: Job? = null
    private var generation = 0L

    fun inspect(providerId: String?) {
        // 新连接切到详情时沿用已经收到的地址/观察任务，不能重置掉浏览器打开事件。
        if (providerId != null && auth?.providerId == providerId && job?.isActive == true) return
        val current = ++generation
        job?.cancel()
        auth = null
        usage = null
        error = null
        busy = false
        browserOpenFailed = false
        if (providerId != null) job = viewModelScope.launch { poll(providerId, current) }
    }

    fun stopObserving() {
        generation++
        job?.cancel()
        job = null
        auth = null
        usage = null
        busy = false
        error = null
        browserOpenFailed = false
    }

    fun start(providerId: String?, onCreated: (String) -> Unit, openBrowser: (String) -> Boolean) {
        if (busy || auth?.status == "PENDING") return
        val current = ++generation
        job?.cancel()
        busy = true
        error = null
        usage = null
        browserOpenFailed = false
        job = viewModelScope.launch {
            try {
                val started = core.startChatGptLogin(providerId).getOrThrow()
                if (generation != current) return@launch
                auth = started
                val id = auth!!.providerId
                // 先交付打开浏览器动作，再导航。不能依赖一次可能被页面切换取消的 LaunchedEffect。
                if (started.status == "PENDING") {
                    started.authUrl?.let { openAuthorization(it, openBrowser) }
                }
                busy = false
                onCreated(id)
                poll(id, current)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation == current) error = failure.message
            } finally {
                if (generation == current) busy = false
            }
        }
    }

    fun openAuthorization(url: String, openBrowser: (String) -> Boolean) {
        browserOpenFailed = !runCatching { openBrowser(url) }.getOrDefault(false)
    }

    private suspend fun poll(providerId: String, current: Long) {
        try {
            // 只在当前设置页观察；core 回调自身有超时，退出页面不留下 UI 轮询。
            repeat(300) {
                val state = core.getChatGptAuthState(providerId).getOrThrow()
                if (generation != current) return
                auth = state
                if (state.status != "PENDING") {
                    if (state.status == "CONNECTED") loadUsage(providerId, current)
                    return
                }
                delay(1000)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (generation == current) error = failure.message
        }
    }

    fun cancel() = act { id -> core.cancelChatGptLogin(id).getOrThrow() }
    fun disconnect() = act { id -> core.disconnectChatGpt(id).getOrThrow() }

    private fun act(action: suspend (String) -> Unit) {
        val id = auth?.providerId ?: return
        if (busy) return
        val current = ++generation
        job?.cancel()
        busy = true
        error = null
        job = viewModelScope.launch {
            try {
                action(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation == current) error = failure.message
            } finally {
                if (generation != current) return@launch
                busy = false
                // 注销撤销失败也应重新读取 core 已清理的本地状态。
                try {
                    val updated = core.getChatGptAuthState(id).getOrThrow()
                    if (generation != current) return@launch
                    auth = updated
                    if (auth?.status != "CONNECTED") usage = null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (generation == current) error = error ?: failure.message
                }
            }
        }
    }

    fun refreshUsage() {
        val id = auth?.takeIf { it.status == "CONNECTED" }?.providerId ?: return
        val current = generation
        viewModelScope.launch { loadUsage(id, current) }
    }

    private suspend fun loadUsage(providerId: String, current: Long) {
        try {
            val loaded = core.readChatGptUsage(providerId).getOrThrow()
            if (generation == current && auth?.providerId == providerId && auth?.status == "CONNECTED") usage = loaded
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (generation == current && auth?.providerId == providerId) error = failure.message
        }
    }
}
