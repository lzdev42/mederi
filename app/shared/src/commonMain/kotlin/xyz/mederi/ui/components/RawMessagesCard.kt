package xyz.mederi.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import xyz.mederi.util.rememberClipboardCopy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.Copy
import compose.icons.feathericons.Terminal
import kotlinx.coroutines.delay
import xyz.mederi.core.contract.dto.RawMessageDto
import xyz.mederi.ui.RawMessagesViewModel
import xyz.mederi.ui.WorkspaceViewModel
import xyz.mederi.theme.MederiColors
import xyz.mederi.ui.components.atoms.ExpandChevron
import xyz.mederi.ui.components.atoms.MederiIconButton
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.copy
import mederi.app.shared.generated.resources.copy_done
import mederi.app.shared.generated.resources.rawmsg_summary
import org.jetbrains.compose.resources.stringResource

/**
 * 单条原始消息展开后的 JSON 视口**最大**高度：与 UserPastedTextCard(280.dp) / ErrorDetailDialog(280.dp) 对齐。
 */
private val RawMessageMaxJsonHeight = 280.dp

/**
 * 概览下方的原始消息卡片 —— 原型 accordion（.raw-messages-collapsible）。
 *
 * 外层 surfaceCard 壳 + divider 描边，默认折叠为一行 summary-bar（共 N 条 ›），
 * 展开后每条消息是 40dp 高的 .raw-log-item 行（surfaceHover 底、无描边），
 * 点击行头展开该条 JSON（mono 11.5sp + 复制按钮）。
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

    // 外层卡壳 = 原型 .raw-messages-collapsible（gray-2 底 + gray-6 描边 + radius-card，overflow hidden）
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceCard)
            .border(1.dp, colors.divider, RoundedCornerShape(8.dp))
    ) {
        // summary-bar（默认折叠，点击整行切换）：terminal 图标 + 共 N 条 › + 旋转 chevron
        var isCollapsed by remember(convId) { mutableStateOf(true) }
        val summaryInteraction = remember { MutableInteractionSource() }
        val isSummaryHovered by summaryInteraction.collectIsHoveredAsState()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (isSummaryHovered) colors.surfaceHover else Color.Transparent)
                .clickable(interactionSource = summaryInteraction, indication = null) { isCollapsed = !isCollapsed }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = FeatherIcons.Terminal,
                contentDescription = null,
                tint = colors.textMuted,
                modifier = Modifier.size(13.dp)
            )
            Text(
                text = stringResource(Res.string.rawmsg_summary, rawMessages.size),
                color = if (isSummaryHovered) colors.textPrimary else colors.textSecondary,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            ExpandChevron(expanded = !isCollapsed, tint = colors.textMuted, size = 13.dp)
        }

        AnimatedVisibility(visible = !isCollapsed) {
            Column(
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
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
}

@Composable
private fun RawMessageItemRow(
    rawVm: RawMessagesViewModel,
    item: RawMessageDto,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    colors: MederiColors
) {
    // 投影字段由 jvmMain 桥预解析填进 DTO，commonMain UI 只读不解析 payload JSON
    val label = remember(item) { rawVm.extractSummaryLabel(item) }
    val tokensText = remember(item) { rawVm.extractTokens(item) }
    val timeText = remember(item.createdAt) { rawVm.formatMessageTimestamp(item.createdAt) }

    // 行壳 = 原型 .raw-log-item：6dp 圆角 + surfaceHover 底，无描边（isExpanded 不再换底）
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(colors.surfaceHover)
    ) {
        // header 行（.raw-log-header）：40dp 高、padding 0/12，点击切换展开
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clickable { onToggleExpand() }
                .padding(start = 12.dp, end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // role 名：13sp/500 textPrimary（保留现有 label 派生）
            Text(
                text = label,
                color = colors.textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (timeText.isNotBlank()) {
                Text(
                    text = timeText,
                    color = colors.textMuted,
                    fontSize = 11.sp
                )
            }
            if (!tokensText.isNullOrBlank()) {
                Text(
                    text = tokensText,
                    color = colors.textMuted,
                    fontSize = 12.sp
                )
            }
            ExpandChevron(expanded = isExpanded, tint = colors.textMuted, size = 12.dp)
        }

        // 展开 body（.raw-log-body）：顶部 1dp divider 描边 + padding 8/12，mono JSON + 右上复制
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
                            // 先限高把约束变有界，短 JSON 仍按内容收缩（与 UserPastedTextCard/ErrorDetailDialog 同一模式）。
                            .heightIn(max = RawMessageMaxJsonHeight)
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState()),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.5.sp,
                        lineHeight = 17.25.sp,
                        color = colors.textPrimary,
                    )
                }

                // 右上角浮动复制按钮（收敛为 MederiIconButton：copied → active 高亮 + Check，对齐复制反馈语义）
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