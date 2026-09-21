package xyz.mederi.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    fun formatMessageTimestamp(rawIso: String): String {
        if (rawIso.isBlank()) return ""
        return try {
            // 兼容原实现的空格分隔输入（如 "2026-09-20 16:12:28"），Instant 解析要求 'T' 分隔
            val normalized = if ('T' in rawIso) rawIso else rawIso.replace(' ', 'T')
            val dt = Instant.parse(normalized).toLocalDateTime(TimeZone.currentSystemDefault())
            "${dt.monthNumber}/${dt.dayOfMonth} ${dt.hour.toString().padStart(2, '0')}:${dt.minute.toString().padStart(2, '0')}"
        } catch (_: Throwable) {
            rawIso.take(16)
        }
    }

    /**
     * 提取摘要标签（如 "text"、"bash"、"compose-hot-reload_*"、"reasoning + text + bash" 或 "user: ..."）。
     */
    fun extractSummaryLabel(item: RawMessageDto, jsonObj: JsonObject?): String {
        if (jsonObj == null) {
            return item.role.lowercase()
        }
        val role = jsonObj["role"]?.jsonPrimitive?.contentOrNull ?: item.role
        val parts = jsonObj["parts"]?.jsonArray

        if (role.equals("user", ignoreCase = true)) {
            val textPart = parts?.mapNotNull { it.jsonObject }?.firstOrNull {
                it["text"] != null || it["type"]?.jsonPrimitive?.contentOrNull?.contains("Text", ignoreCase = true) == true
            }
            val text = textPart?.get("text")?.jsonPrimitive?.contentOrNull
            if (!text.isNullOrBlank()) {
                val clean = text.substringBefore("<<<NOT_FOR_UI>>>").trim()
                val firstLine = clean.lines().firstOrNull()?.trim().orEmpty()
                return "user: " + (if (firstLine.length > 50) firstLine.take(50) + "..." else firstLine)
            }
            val toolResultPart = parts?.mapNotNull { it.jsonObject }?.firstOrNull {
                it["tool"] != null && it["output"] != null
            }
            if (toolResultPart != null) {
                val toolName = toolResultPart["tool"]?.jsonPrimitive?.contentOrNull ?: "tool"
                return "$toolName result"
            }
            return "user"
        }

        if (parts != null && parts.isNotEmpty()) {
            val partLabels = mutableListOf<String>()
            for (partElement in parts) {
                val partObj = partElement.jsonObject
                val tool = partObj["tool"]?.jsonPrimitive?.contentOrNull
                if (!tool.isNullOrBlank()) {
                    partLabels.add(tool)
                } else if (partObj["content"] != null || partObj["summary"] != null ||
                    partObj["type"]?.jsonPrimitive?.contentOrNull?.contains("Reasoning", ignoreCase = true) == true
                ) {
                    partLabels.add("reasoning")
                } else if (partObj["text"] != null ||
                    partObj["type"]?.jsonPrimitive?.contentOrNull?.contains("Text", ignoreCase = true) == true
                ) {
                    partLabels.add("text")
                }
            }
            if (partLabels.isNotEmpty()) {
                val distinctLabels = mutableListOf<String>()
                for (lbl in partLabels) {
                    if (distinctLabels.isEmpty() || distinctLabels.last() != lbl) {
                        distinctLabels.add(lbl)
                    }
                }
                return distinctLabels.joinToString(" + ")
            }
        }

        return role.lowercase()
    }

    /**
     * 提取 Token 消耗（input / output）。
     */
    fun extractTokens(jsonObj: JsonObject?): String? {
        if (jsonObj == null) return null
        val input = jsonObj["inputTokens"]?.jsonPrimitive?.intOrNull
            ?: jsonObj["tokens"]?.jsonObject?.get("input")?.jsonPrimitive?.intOrNull
        val output = jsonObj["outputTokens"]?.jsonPrimitive?.intOrNull
            ?: jsonObj["tokens"]?.jsonObject?.get("output")?.jsonPrimitive?.intOrNull

        if (input != null || output != null) {
            val inStr = formatNumber(input ?: 0)
            val outStr = formatNumber(output ?: 0)
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