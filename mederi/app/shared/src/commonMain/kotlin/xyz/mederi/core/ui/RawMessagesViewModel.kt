package xyz.mederi.core.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import xyz.mederi.core.contract.dto.RawMessageDto
import xyz.mederi.core.contract.models.CoreEventType
import xyz.mederi.core.ui.appstate.AppState

/**
 * 原始消息调试面板 ViewModel：数据拉取 + 全局事件流订阅 + 加载状态机。
 *
 * 之前这段逻辑堆在 Composable 内（直接调 aiCore + LaunchedEffect 订阅），
 * 与项目内其他数据获取（如 WorkspaceViewModel.openDiff）模式不一致且不可测试。
 * UI 经 `viewModel()` 创建本类，会话变化时调用 [bind] 重新挂载。
 */
class RawMessagesViewModel(
    private val appState: AppState,
) : ViewModel() {

    var rawMessages by mutableStateOf<List<RawMessageDto>>(emptyList()); private set
    var isLoading by mutableStateOf(false); private set

    private var collectJob: Job? = null

    /**
     * 绑定会话：加载原始消息并订阅完成/更新事件实时刷新。
     * conversationId 变化或为 null 时重置状态并取消旧订阅。
     */
    fun bind(conversationId: String?) {
        collectJob?.cancel()
        collectJob = null
        rawMessages = emptyList()
        isLoading = false
        if (conversationId == null) return
        collectJob = viewModelScope.launch {
            isLoading = true
            rawMessages = appState.aiCore.listRawMessages(conversationId).getOrDefault(emptyList())
            isLoading = false

            appState.aiCore.events()
                .filter {
                    it.sessionId == conversationId &&
                        (it.type == CoreEventType.MESSAGE_COMPLETED || it.type == CoreEventType.SESSION_UPDATED)
                }
                .collect {
                    val updated = appState.aiCore.listRawMessages(conversationId).getOrNull()
                    if (updated != null) rawMessages = updated
                }
        }
    }

    override fun onCleared() {
        collectJob?.cancel()
        super.onCleared()
    }
}
