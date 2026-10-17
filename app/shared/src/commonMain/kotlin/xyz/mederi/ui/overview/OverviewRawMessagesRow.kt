package xyz.mederi.ui.overview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.Copy
import compose.icons.feathericons.Terminal
import kotlinx.coroutines.delay
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.copy
import mederi.app.shared.generated.resources.copy_done
import mederi.app.shared.generated.resources.rawmsg_summary
import mederi.app.shared.generated.resources.rawmsg_title
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.dto.RawMessageDto
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.ui.RawMessagesViewModel
import xyz.mederi.ui.WorkspaceViewModel
import xyz.mederi.ui.components.atoms.ExpandChevron
import xyz.mederi.ui.components.atoms.MederiIconButton
import xyz.mederi.ui.components.containScroll
import xyz.mederi.util.rememberClipboardCopy

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
 * 展开态：每条文消息为一个 40dp 高行头（label + 时间 + tokens + chevron），
 * 点击行头展开该条 JSON（mono 11.5sp + 右上复制按钮），倒序展示（最新在上）。
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
                fontSize = 12.sp,
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
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = colors.accentPrimary,
                        strokeWidth = 2.dp,
                    )
                }
            } else {
                // 倒序展示：最新的一条在最上方
                val displayList = remember(rawMessages) { rawMessages.reversed() }

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
) {
    val colors = LocalMederiColors.current
    // 投影字段由 jvmMain 桥预解析填进 DTO，commonMain UI 只读不解析 payload JSON
    val label = remember(item) { rawVm.extractSummaryLabel(item) }
    val tokensText = remember(item) { rawVm.extractTokens(item) }
    val timeText = remember(item.createdAt) { rawVm.formatMessageTimestamp(item.createdAt) }

    // hover 底色：常态透明，鼠标移入才浮出（对齐 OverviewAccordionRow 行头范式）
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    // 行壳：无底卡，header hover 时浮出 surfaceHover（紧凑版 36dp header）
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        // header 行：36dp 高、padding 0/10，点击切换展开
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .background(if (isHovered) colors.surfaceHover else Color.Transparent)
                .clickable(interactionSource = interactionSource, indication = null) { onToggleExpand() }
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // label：12.5sp/500 textPrimary（保留现有 label 派生）
            Text(
                text = label,
                color = colors.textPrimary,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (timeText.isNotBlank()) {
                Text(
                    text = timeText,
                    color = colors.textMuted,
                    fontSize = 10.5.sp,
                )
            }
            if (!tokensText.isNullOrBlank()) {
                Text(
                    text = tokensText,
                    color = colors.textMuted,
                    fontSize = 11.sp,
                )
            }
            ExpandChevron(expanded = isExpanded, tint = colors.textMuted, size = 12.dp)
        }

        // 展开 body：顶部 1dp divider 描边 + padding 8/12，mono JSON + 右上复制
        if (isExpanded) {
            HorizontalDivider(color = colors.divider, thickness = 1.dp)

            val prettyJson = remember(item.payload) {
                rawVm.prettyPrintJson(item.payload)
            }
            val copyToClipboard = rememberClipboardCopy()
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
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                SelectionContainer {
                    Text(
                        text = prettyJson,
                        modifier = Modifier
                            .fillMaxWidth()
                            .containScroll()
                            // heightIn 必须排在 verticalScroll 之前：概览面板本身是
                            // Column(Modifier.verticalScroll)（InfoPanels），它以 maxHeight=Infinity 度量子内容，
                            // 内层再挂 verticalScroll 会拿到无界约束并抛
                            // IllegalStateException("Vertically scrollable component was measured with an
                            // infinity maximum height constraints, which is disallowed. ... nesting layouts
                            // like LazyColumn and Column(Modifier.verticalScroll())") —— 展开任一条即崩。
                            // 先限高把约束变有界，短 JSON 仍按内容收缩（与 RawMessagesCard 同一模式）。
                            .heightIn(max = RawMessageMaxJsonHeight)
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState()),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.5.sp,
                        lineHeight = 17.25.sp,
                        color = colors.textPrimary,
                    )
                }

                // 右上角浮动复制按钮（copied → active 高亮 + Check，对齐复制反馈语义）
                Box(modifier = Modifier.align(Alignment.TopEnd)) {
                    MederiIconButton(
                        icon = if (copied) FeatherIcons.Check else FeatherIcons.Copy,
                        onClick = {
                            copyToClipboard(prettyJson)
                            copied = true
                        },
                        contentDescription = stringResource(if (copied) Res.string.copy_done else Res.string.copy),
                        size = 24,
                        active = copied,
                        activeTint = colors.accentSuccess,
                        shape = RoundedCornerShape(4.dp),
                    )
                }
            }
        }
    }
}