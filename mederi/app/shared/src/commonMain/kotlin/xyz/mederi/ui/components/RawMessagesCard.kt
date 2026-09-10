package xyz.mederi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.Copy
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import xyz.emuci.inkcompose.MarkdownView
import xyz.mederi.core.contract.dto.RawMessageDto
import xyz.mederi.core.ui.RawMessagesViewModel
import xyz.mederi.core.ui.WorkspaceViewModel
import xyz.mederi.core.ui.appstate.LocalAppState
import xyz.mederi.theme.MederiColors

private val jsonPretty = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * 格式化并美化 JSON 字符串。
 */
private fun prettyPrintJson(raw: String): String {
    return try {
        val element = Json.parseToJsonElement(raw)
        jsonPretty.encodeToString(JsonElement.serializer(), element)
    } catch (_: Throwable) {
        raw
    }
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

/**
 * 格式化 ISO-8601 时间戳为 "M/d HH:mm"。
 */
private fun formatMessageTimestamp(rawIso: String): String {
    if (rawIso.isBlank()) return ""
    return try {
        val tIndex = rawIso.indexOf('T').let { if (it == -1) rawIso.indexOf(' ') else it }
        if (tIndex >= 10) {
            val datePart = rawIso.substring(0, tIndex)
            val timePart = rawIso.substring(tIndex + 1)
            val dateTokens = datePart.split("-")
            val month = dateTokens.getOrNull(1)?.toIntOrNull()?.toString() ?: ""
            val day = dateTokens.getOrNull(2)?.toIntOrNull()?.toString() ?: ""
            val timeTokens = timePart.split(":")
            val hour = timeTokens.getOrNull(0) ?: ""
            val min = timeTokens.getOrNull(1) ?: ""
            if (month.isNotEmpty() && day.isNotEmpty() && hour.isNotEmpty() && min.isNotEmpty()) {
                "$month/$day $hour:$min"
            } else {
                rawIso.take(16)
            }
        } else {
            rawIso.take(16)
        }
    } catch (_: Throwable) {
        rawIso.take(16)
    }
}

/**
 * 提取摘要标签（如 "text"、"bash"、"compose-hot-reload_*"、"reasoning + text + bash" 或 "user: ..."）。
 */
private fun extractSummaryLabel(item: RawMessageDto, jsonObj: JsonObject?): String {
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
private fun extractTokens(jsonObj: JsonObject?): String? {
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
 * 概览下方的原始消息卡片。
 *
 * 具备按需懒加载特性：仅当该卡片处于 Composition 中时（右侧面板打开且切到概览 Tab），
 * 协程才会启动拉取与监听事件流；一旦切走或折叠，协程自动 Cancel，零多余消耗。
 */
@Composable
fun RawMessagesCard(
    viewModel: WorkspaceViewModel,
    colors: MederiColors,
    modifier: Modifier = Modifier
) {
    val appState = viewModel.appStateRef
    // 数据拉取与事件订阅在 RawMessagesViewModel（经 ViewModelStore 管理），卡片只渲染
    val rawVm: RawMessagesViewModel = viewModel { RawMessagesViewModel(appState) }
    val convId = viewModel.conversationId

    LaunchedEffect(convId) {
        rawVm.bind(convId)
    }

    val rawMessages = rawVm.rawMessages
    val isLoading = rawVm.isLoading
    // 展开/收起是纯视图状态，归 UI 本地
    var expandedSeqs by remember(convId) { mutableStateOf<Set<Long>>(emptySet()) }

    if (rawMessages.isEmpty() && !isLoading) {
        return
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 卡片标题
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "原始消息",
                color = colors.textMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp
            )
            if (rawMessages.isNotEmpty()) {
                Text(
                    text = "${rawMessages.size} 条",
                    color = colors.textMuted,
                    fontSize = 10.sp
                )
            }
        }

        if (isLoading && rawMessages.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = colors.accentPrimary,
                    strokeWidth = 2.dp
                )
            }
        } else {
            // 倒序展示：最新的一条在最上方
            val displayList = remember(rawMessages) { rawMessages.reversed() }

            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                for (item in displayList) {
                    val isExpanded = item.seq in expandedSeqs
                    RawMessageItemRow(
                        item = item,
                        isExpanded = isExpanded,
                        onToggleExpand = {
                            expandedSeqs = if (isExpanded) {
                                expandedSeqs - item.seq
                            } else {
                                expandedSeqs + item.seq
                            }
                        },
                        colors = colors
                    )
                }
            }
        }
    }
}

@Composable
private fun RawMessageItemRow(
    item: RawMessageDto,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    colors: MederiColors
) {
    val jsonObj = remember(item.payload) {
        runCatching { Json.parseToJsonElement(item.payload).jsonObject }.getOrNull()
    }
    val label = remember(item, jsonObj) { extractSummaryLabel(item, jsonObj) }
    val tokensText = remember(jsonObj) { extractTokens(jsonObj) }
    val timeText = remember(item.createdAt) { formatMessageTimestamp(item.createdAt) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (isExpanded) colors.surfaceCard else colors.surfaceCard.copy(alpha = 0.5f))
            .border(
                1.dp,
                if (isExpanded) colors.accentPrimary.copy(alpha = 0.35f) else colors.divider.copy(alpha = 0.4f),
                RoundedCornerShape(6.dp)
            )
    ) {
        // 单行预览头
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleExpand() }
                .padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = if (isExpanded) colors.accentPrimary else colors.textPrimary,
                fontSize = 11.sp,
                fontWeight = if (isExpanded) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!tokensText.isNullOrBlank()) {
                    Text(
                        text = tokensText,
                        color = colors.textMuted,
                        fontSize = 10.sp
                    )
                }
                if (timeText.isNotBlank()) {
                    Text(
                        text = timeText,
                        color = colors.textMuted,
                        fontSize = 10.sp
                    )
                }
            }
        }

        // 展开的格式化 JSON 区域
        if (isExpanded) {
            HorizontalDivider(
                color = colors.divider.copy(alpha = 0.4f),
                thickness = 0.5.dp
            )

            val prettyJson = remember(item.payload) {
                prettyPrintJson(item.payload)
            }
            val markdownContent = remember(prettyJson) {
                "```json\n$prettyJson\n```"
            }
            val clipboardManager = LocalClipboardManager.current
            var copied by remember { mutableStateOf(false) }

            LaunchedEffect(copied) {
                if (copied) {
                    delay(2000)
                    copied = false
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceWorkspace)
                    .padding(8.dp)
            ) {
                MarkdownView(
                    content = markdownContent,
                    modifier = Modifier.fillMaxWidth(),
                    enableScrollOverride = false
                )

                // 右上角浮动复制按钮
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.surfaceCard.copy(alpha = 0.85f))
                        .clickable {
                            clipboardManager.setText(AnnotatedString(prettyJson))
                            copied = true
                        }
                        .padding(5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (copied) FeatherIcons.Check else FeatherIcons.Copy,
                        contentDescription = if (copied) "已复制" else "复制",
                        tint = if (copied) colors.accentSuccess else colors.textMuted,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
        }
    }
}
