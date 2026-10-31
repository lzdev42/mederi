package xyz.mederi.ui.overview

import xyz.mederi.ui.components.atoms.WorkspaceLoadingIndicator

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.Copy
import compose.icons.feathericons.Terminal
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.copy
import mederi.app.shared.generated.resources.copy_done
import mederi.app.shared.generated.resources.rawmsg_summary
import mederi.app.shared.generated.resources.rawmsg_title
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.dto.RawMessageDto
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.WorkspaceStyle
import xyz.mederi.ui.RawMessagesViewModel
import xyz.mederi.ui.WorkspaceViewModel
import xyz.mederi.ui.components.atoms.CopyFeedbackScope
import xyz.mederi.ui.components.atoms.ExpandChevron
import xyz.mederi.ui.components.atoms.ExpandableRow
import xyz.mederi.ui.components.atoms.WorkspaceActionButton
import xyz.mederi.ui.components.atoms.rememberCopyFeedback
import xyz.mederi.ui.components.containScroll

/**
 * 单条原始消息展开后的 JSON 视口**最大**高度：与 UserPastedTextCard(280.dp) / ErrorDetailDialog(280.dp) 对齐。
 */
private val RawMessageMaxJsonHeight = 280.dp

/**
 * 概览浮动窗原始消息行（调试用）。
 *
 * 折叠态摘要：`共 N 条 ›`；空列表且非加载时不渲染整行（无内容不留空行）。
 * 按需懒加载：仅当本行处于 Composition 中（概览窗打开）时 LaunchedEffect 才触发
 * [RawMessagesViewModel.bind]，会话变化自动重绑。
 *
 * 展开态：每条消息使用共享 ExpandableRow，label 与时间/tokens 分层展示。
 * JSON 保持 280dp 有界内部滚动，复制位于独立工具栏，倒序展示（最新在上）。
 */
@Composable
fun OverviewRawMessagesRow(
    viewModel: WorkspaceViewModel,
    rawVm: RawMessagesViewModel,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMederiColors.current
    // 数据拉取与事件订阅在 RawMessagesViewModel（Route 层创建，ViewModelStore 管理生命周期），行只渲染
    val convId = viewModel.conversationId

    LaunchedEffect(convId) {
        rawVm.bind(convId)
    }

    val rawMessages = rawVm.rawMessages
    val isLoading = rawVm.isLoading
    // 展开/收起是纯视图状态，归 UI 本地（按会话记忆）
    var expandedSeqs by remember(convId) { mutableStateOf<Set<Long>>(emptySet()) }

    // 空列表且非加载时不渲染整行
    if (rawMessages.isEmpty() && !isLoading) {
        return
    }

    OverviewAccordionRow(
        icon = FeatherIcons.Terminal,
        title = stringResource(Res.string.rawmsg_title),
        summary = {
            Text(
                text = stringResource(Res.string.rawmsg_summary, rawMessages.size),
                color = colors.textSecondary,
                style = WorkspaceStyle.Caption,
                maxLines = 1,
            )
        },
        expanded = expanded,
        onToggle = onToggle,
        modifier = modifier,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (isLoading && rawMessages.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    WorkspaceLoadingIndicator(
                        modifier = Modifier.size(WorkspaceStyle.IconSize),
                        color = colors.accentPrimary,
                        strokeWidth = 1.5.dp,
                    )
                }
            } else {
                // 倒序展示：最新的一条在最上方
                val displayList = remember(rawMessages) { rawMessages.reversed() }

                for (item in displayList) {
                    key(convId, item.seq) {
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
                        )
                    }
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
) {
    // DTO 的已有投影与格式化职责保持在 VM；View 不解析原始 JSON。
    val label = remember(item) { rawVm.extractSummaryLabel(item) }
    val tokensText = remember(item) { rawVm.extractTokens(item) }
    val timeText = remember(item.createdAt) { rawVm.formatMessageTimestamp(item.createdAt) }
    // 保持按需格式化；首次展开后保留展示缓存，收起动画期间也不会把仍可见的 JSON/复制内容清空。
    val prettyJsonCache = remember(item.payload) { lazy { rawVm.prettyPrintJson(item.payload) } }
    val prettyJson = if (isExpanded || prettyJsonCache.isInitialized()) prettyJsonCache.value else ""
    val copyState = rememberCopyFeedback()
    CopyFeedbackScope(copyState) { copied ->
        RawMessageItemContent(
            label = label,
            timeText = timeText,
            tokensText = tokensText,
            prettyJson = prettyJson,
            isExpanded = isExpanded,
            onToggleExpand = onToggleExpand,
            // 保留既有行为：显示和复制的都是 VM 格式化结果，payload 原文不修改。
            onCopy = { copyState.copy(prettyJson) },
            copied = copied,
        )
    }
}

/**
 * 原始消息的纯展示入口。只接只读展示值和语义回调；复制反馈由调用方共享作用域提供。
 * 复制按钮放在 JSON 之外，保证长行与滚动内容不被悬浮操作遮住。
 */
@Composable
internal fun RawMessageItemContent(
    label: String,
    timeText: String,
    tokensText: String?,
    prettyJson: String,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onCopy: () -> Unit,
    copied: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMederiColors.current
    val metadata = listOfNotNull(
        timeText.takeIf { it.isNotBlank() },
        tokensText?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
    ExpandableRow(
        expanded = isExpanded,
        onExpandedChange = { onToggleExpand() },
        modifier = modifier.fillMaxWidth(),
        showChevron = false,
        header = {
            // 完整占用 header 可用宽度，让标题/metadata 只压缩自己，固定箭头始终可见。
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = label,
                        color = colors.textPrimary,
                        style = WorkspaceStyle.Title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (metadata.isNotBlank()) {
                        Text(
                            text = metadata,
                            color = colors.textMuted,
                            style = WorkspaceStyle.Caption,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                ExpandChevron(expanded = isExpanded, tint = colors.textMuted)
            }

        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                WorkspaceActionButton(
                    text = stringResource(if (copied) Res.string.copy_done else Res.string.copy),
                    icon = if (copied) FeatherIcons.Check else FeatherIcons.Copy,
                    onClick = onCopy,
                    colors = colors,
                )
            }
            SelectionContainer {
                Text(
                    text = prettyJson,
                    color = colors.textPrimary,
                    style = WorkspaceStyle.Caption,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(WorkspaceStyle.ObjectRadius))
                        .background(colors.surfaceCode)
                        .containScroll()
                        // 先限定 maxHeight，再挂滚动，避免概览外层滚动以无限高约束测量 JSON。
                        .heightIn(max = RawMessageMaxJsonHeight)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(8.dp),
                )
            }
        }
    }
}
