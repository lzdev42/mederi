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
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import xyz.emuci.inkcompose.MarkdownView
import xyz.mederi.core.contract.dto.RawMessageDto
import xyz.mederi.core.ui.RawMessagesViewModel
import xyz.mederi.core.ui.WorkspaceViewModel
import xyz.mederi.theme.MederiColors
import xyz.mederi.theme.rememberMederiMarkdownTheme
import xyz.mederi.ui.components.atoms.CardHeader
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.copy
import mederi.app.shared.generated.resources.copy_done
import mederi.app.shared.generated.resources.rawmsg_count
import mederi.app.shared.generated.resources.rawmsg_title
import org.jetbrains.compose.resources.stringResource

/**
 * 概览下方的原始消息卡片。
 *
 * 具备按需懒加载特性：仅当该卡片处于 Composition 中时（右侧面板打开且切到概览 Tab），
 * 协程才会启动拉取与监听事件流；一旦切走或折叠，协程自动 Cancel，零多余消耗。
 */
@Composable
fun RawMessagesCard(
    viewModel: WorkspaceViewModel,
    rawVm: RawMessagesViewModel,
    colors: MederiColors,
    modifier: Modifier = Modifier
) {
    // 数据拉取与事件订阅在 RawMessagesViewModel（Route 层创建，ViewModelStore 管理生命周期），卡片只渲染
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
        CardHeader(
            icon = null,
            title = stringResource(Res.string.rawmsg_title),
            count = {
                Text(
                    text = stringResource(Res.string.rawmsg_count, rawMessages.size),
                    color = colors.textMuted,
                    fontSize = 10.sp
                )
            }
        )

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
                        rawVm = rawVm,
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
    rawVm: RawMessagesViewModel,
    item: RawMessageDto,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    colors: MederiColors
) {
    val jsonObj = remember(item.payload) {
        runCatching { Json.parseToJsonElement(item.payload).jsonObject }.getOrNull()
    }
    val label = remember(item, jsonObj) { rawVm.extractSummaryLabel(item, jsonObj) }
    val tokensText = remember(jsonObj) { rawVm.extractTokens(jsonObj) }
    val timeText = remember(item.createdAt) { rawVm.formatMessageTimestamp(item.createdAt) }

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
                rawVm.prettyPrintJson(item.payload)
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
                    enableScrollOverride = false,
                    markdownTheme = rememberMederiMarkdownTheme()
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
                        contentDescription = stringResource(if (copied) Res.string.copy_done else Res.string.copy),
                        tint = if (copied) colors.accentSuccess else colors.textMuted,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
        }
    }
}
