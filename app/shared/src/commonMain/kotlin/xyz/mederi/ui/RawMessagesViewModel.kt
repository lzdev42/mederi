package xyz.mederi.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import xyz.mederi.util.TimeFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import xyz.mederi.core.contract.dto.RawMessageDto
import xyz.mederi.core.contract.models.CoreEventType
import xyz.mederi.ui.appstate.AppState

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

    // ── 解析/格式化（自 ui/components/RawMessagesCard.kt 迁入，方便单测；时间戳走 kotlinx-datetime）──

    private companion object {
        private val jsonPretty = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
            ignoreUnknownKeys = true
            isLenient = true
        }

        /**
         * 纯 Kotlin 格式化数字加千分位逗号（跨平台无依赖）。
         */
        private fun formatNumber(n: Int): String {
            val s = n.toString()
            val len = s.length
            if (len <= 3) return s
            val sb = StringBuilder()
            val rem = len % 3
            if (rem > 0) {
                sb.append(s.substring(0, rem))
                if (rem < len) sb.append(',')
            }
            for (i in rem until len step 3) {
                sb.append(s.substring(i, i + 3))
                if (i + 3 < len) sb.append(',')
            }
            return sb.toString()
        }
    }

    /**
     * 格式化并美化 JSON 字符串。
     */
    fun prettyPrintJson(raw: String): String {
        return try {
            val element = Json.parseToJsonElement(raw)
            jsonPretty.encodeToString(JsonElement.serializer(), element)
        } catch (_: Throwable) {
            raw
        }
    }

    /**
     * 格式化 ISO-8601 时间戳为 "M/d HH:mm"（按系统本地时区展示）。
     */
    fun formatMessageTimestamp(rawIso: String): String =
        TimeFormatter.formatMonthDayTime(rawIso)

    /**
     * 提取摘要标签（如 "text"、"bash"、"user: hello..."）。
     * 读取 jvmMain 桥预解析好的 DTO 投影字段 [RawMessageDto.summaryLabel]，
     * commonMain 不再手解 core payload JSON。
     */
    fun extractSummaryLabel(item: RawMessageDto): String {
        return item.summaryLabel ?: item.role.lowercase()
    }

    /**
     * 格式化 Token 消耗显示（如 "1,234 / 567"）。
     * 读取 jvmMain 桥预解析好的 DTO 投影字段 [RawMessageDto.inputTokens] / [RawMessageDto.outputTokens]，
     * commonMain 不再手解 core payload JSON。千分位格式化是 UI 展示关切，留在本层。
     */
    fun extractTokens(item: RawMessageDto): String? {
        val input = item.inputTokens
        val output = item.outputTokens
        if (input != null || output != null) {
            val inStr = formatNumber(input?.toInt() ?: 0)
            val outStr = formatNumber(output?.toInt() ?: 0)
            return "$inStr / $outStr"
        }
        return null
    }

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