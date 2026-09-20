package xyz.mederi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import xyz.emuci.inkcompose.InkImage
import xyz.emuci.inkcompose.MarkdownView
import xyz.mederi.core.contract.models.*
import xyz.mederi.core.contract.models.Conversation
import xyz.mederi.core.ui.WorkspaceViewModel
import xyz.mederi.core.ui.RightDockPanel
import xyz.mederi.core.ui.ArtifactItem
import xyz.mederi.core.ui.PlanItem
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.attachment_meta
import mederi.app.shared.generated.resources.copy_done
import mederi.app.shared.generated.resources.dock_artifact_empty
import mederi.app.shared.generated.resources.dock_artifact_empty_hint
import mederi.app.shared.generated.resources.dock_browser_desktop_only
import mederi.app.shared.generated.resources.dock_browser_unsupported
import mederi.app.shared.generated.resources.dock_close_panel
import mederi.app.shared.generated.resources.dock_compact_context
import mederi.app.shared.generated.resources.dock_context_metrics
import mederi.app.shared.generated.resources.dock_context_max
import mederi.app.shared.generated.resources.dock_context_unset
import mederi.app.shared.generated.resources.dock_context_used
import mederi.app.shared.generated.resources.dock_requests_count
import mederi.app.shared.generated.resources.dock_copy_full
import mederi.app.shared.generated.resources.dock_cost_title
import mederi.app.shared.generated.resources.dock_diff_empty
import mederi.app.shared.generated.resources.dock_diff_empty_hint
import mederi.app.shared.generated.resources.dock_export_error
import mederi.app.shared.generated.resources.dock_export_html
import mederi.app.shared.generated.resources.dock_export_pdf
import mederi.app.shared.generated.resources.dock_exported
import mederi.app.shared.generated.resources.dock_exporting
import mederi.app.shared.generated.resources.dock_fit_window
import mederi.app.shared.generated.resources.dock_generating
import mederi.app.shared.generated.resources.dock_html_export_failed
import mederi.app.shared.generated.resources.dock_no_text_diff
import mederi.app.shared.generated.resources.dock_original_ratio
import mederi.app.shared.generated.resources.dock_pdf_export_failed
import mederi.app.shared.generated.resources.dock_plan_approve
import mederi.app.shared.generated.resources.pick_file_filter
import mederi.app.shared.generated.resources.pick_save_file_title
import mederi.app.shared.generated.resources.dock_plan_empty
import mederi.app.shared.generated.resources.dock_plan_empty_hint
import mederi.app.shared.generated.resources.dock_plan_modify
import mederi.app.shared.generated.resources.dock_plan_pending
import mederi.app.shared.generated.resources.dock_refresh_changes
import mederi.app.shared.generated.resources.dock_requests_title
import mederi.app.shared.generated.resources.rightdock_artifacts
import mederi.app.shared.generated.resources.rightdock_browser
import mederi.app.shared.generated.resources.rightdock_diff
import mederi.app.shared.generated.resources.rightdock_overview
import mederi.app.shared.generated.resources.rightdock_plan
import mederi.app.shared.generated.resources.rightdock_sub_agents
import mederi.app.shared.generated.resources.rightdock_terminal
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiColors
import xyz.mederi.theme.rememberMederiMarkdownTheme

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import xyz.mederi.core.ui.DebugLog
import xyz.emuci.inkcompose.MarkdownExporter
import xyz.emuci.inkcompose.HtmlExportOptions
import xyz.emuci.inkcompose.PdfExportOptions
import xyz.mederi.util.pickSaveFile
import xyz.mederi.util.writeTextToFile
import xyz.mederi.util.openFile
import kotlinx.coroutines.launch

@Composable
fun RightExtensionPanel(
    isOpen: Boolean,
    onClose: () -> Unit,
    viewModel: WorkspaceViewModel,
    isCompact: Boolean = false,
    maxPanelWidth: Dp = Dp.Infinity,
    modifier: Modifier = Modifier
) {
    var defaultPanelWidthDp by remember { mutableStateOf(340f) }
    var browserPanelWidthDp by remember { mutableStateOf(850f) }

    val currentPanel = viewModel.activeDockPanel
    // 关闭动画期间保持上一个面板渲染（收缩动画中内容不闪空）。
    // 副作用经 LaunchedEffect，不在组合期直接写状态
    var panelToDisplay by remember { mutableStateOf(currentPanel ?: RightDockPanel.OVERVIEW) }
    LaunchedEffect(currentPanel) {
        if (currentPanel != null) panelToDisplay = currentPanel
    }

    val isBrowser = panelToDisplay == RightDockPanel.BROWSER
    val panelWidthDp = if (isBrowser) browserPanelWidthDp else defaultPanelWidthDp
    // 布局期钳制：面板拖宽后窗口缩小、或面板打开时窗口较窄，面板让位给对话区（不超 maxPanelWidth）
    val effectivePanelWidthDp = panelWidthDp.coerceAtMost(maxPanelWidth.value)

    DebugLog.debug("UI", "RightExtensionPanel: isOpen=$isOpen, currentPanel=$currentPanel, rendering=$panelToDisplay, width=$effectivePanelWidthDp (raw=$panelWidthDp, maxPanel=$maxPanelWidth)")

    val handleWidthChange: (Float) -> Unit = { newWidth ->
        // 不设固定上限（原 800/1600dp）：宽度上限 = maxPanelWidth，保证对话区 ≥ 手机宽度
        if (isBrowser) {
            browserPanelWidthDp = newWidth.coerceAtLeast(400f).coerceAtMost(maxPanelWidth.value)
        } else {
            defaultPanelWidthDp = newWidth.coerceAtLeast(240f).coerceAtMost(maxPanelWidth.value)
        }
    }

    if (isCompact) {
        RightExtensionPanelContent(
            panelWidthDp = effectivePanelWidthDp,
            maxPanelWidth = maxPanelWidth,
            onWidthChange = handleWidthChange,
            onClose = onClose,
            panel = panelToDisplay,
            viewModel = viewModel,
            isCompact = true,
            modifier = modifier
        )
    } else {
        AnimatedVisibility(
            visible = isOpen,
            // 右侧面板从右边缘展开/收缩（expandFrom=End）：居中展开会在动画期间两侧露出底层白缝
            enter = fadeIn() + expandHorizontally(animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy), expandFrom = Alignment.End),
            exit = fadeOut() + shrinkHorizontally(animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy), shrinkTowards = Alignment.End)
        ) {
            RightExtensionPanelContent(
                panelWidthDp = effectivePanelWidthDp,
                maxPanelWidth = maxPanelWidth,
                onWidthChange = handleWidthChange,
                onClose = onClose,
                panel = panelToDisplay,
                viewModel = viewModel,
                isCompact = false,
                modifier = modifier
            )
        }
    }
}

@Composable
private fun RightExtensionPanelContent(
    panelWidthDp: Float,
    maxPanelWidth: Dp = Dp.Infinity,
    onWidthChange: (Float) -> Unit,
    onClose: () -> Unit,
    panel: RightDockPanel,
    viewModel: WorkspaceViewModel,
    isCompact: Boolean = false,
    modifier: Modifier
) {
    val colors = LocalMederiColors.current
    val density = LocalDensity.current
    val currentWidthState = rememberUpdatedState(panelWidthDp)
    val maxPanelWidthState = rememberUpdatedState(maxPanelWidth)
    val onWidthChangeState = rememberUpdatedState(onWidthChange)

    val panelIcon = when (panel) {
        RightDockPanel.OVERVIEW -> FeatherIcons.Activity
        RightDockPanel.DIFF -> FeatherIcons.GitCommit
        RightDockPanel.PLAN -> FeatherIcons.FileText
        RightDockPanel.SUB_AGENTS -> FeatherIcons.Users
        RightDockPanel.ARTIFACTS -> FeatherIcons.File
        RightDockPanel.TERMINAL -> FeatherIcons.Terminal
        RightDockPanel.BROWSER -> FeatherIcons.Globe
    }

    Row(
        modifier = modifier
            .fillMaxHeight()
            .then(if (isCompact) Modifier.fillMaxWidth() else Modifier.width(panelWidthDp.dp))
            .background(colors.surfaceWorkspace)
            .border(width = 1.dp, color = colors.divider)
    ) {
        // 1. 可拖拽分割线 (Resize Handle) —— 仅桌面端显示
        if (!isCompact) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(6.dp)
                    .pointerHoverIcon(PointerIcon.Crosshair)
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures { change, dragAmount ->
                            change.consume()
                            val dragDp = with(density) { dragAmount.toDp().value }
                            val current = currentWidthState.value
                            val minW = if (panel == RightDockPanel.BROWSER) 400f else 200f
                            // 上限 = maxPanelWidth（对话区保底手机宽度），不设固定上限
                            val newWidth = (current - dragDp).coerceAtLeast(minW).coerceAtMost(maxPanelWidthState.value.value)
                            DebugLog.event("UI", "RightExtensionPanel drag: dragAmountPx=$dragAmount, dragDp=$dragDp, currentWidth=${current}dp, newWidth=${newWidth}dp")
                            onWidthChangeState.value(newWidth)
                        }
                    }
                    .background(colors.divider.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(1.dp)
                        .background(colors.divider)
                )
            }
        }

        // 2. 面板主内容容器
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            // 独立面板 Header
            SinglePanelHeader(
                title = rightDockPanelTitle(panel),
                icon = panelIcon,
                onClose = onClose,
                colors = colors
            )

            HorizontalDivider(color = colors.divider)

            // 独立面板内容
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when (panel) {
                    RightDockPanel.OVERVIEW -> OverviewTabContent(
                        panelWidthDp = panelWidthDp,
                        viewModel = viewModel,
                        colors = colors
                    )
                    RightDockPanel.DIFF -> DiffPanelContent(
                        viewModel = viewModel,
                        colors = colors
                    )
                    RightDockPanel.PLAN -> PlanPanelContent(
                        viewModel = viewModel,
                        colors = colors
                    )
                    RightDockPanel.SUB_AGENTS -> SubAgentTabContent(
                        subagents = viewModel.subagents,
                        colors = colors
                    )
                    RightDockPanel.ARTIFACTS -> ArtifactsPanelContent(
                        viewModel = viewModel,
                        colors = colors
                    )
                    RightDockPanel.TERMINAL -> TerminalPanelContent(
                        viewModel = viewModel,
                        colors = colors
                    )
                    RightDockPanel.BROWSER -> BrowserPanelContent(
                        viewModel = viewModel,
                        colors = colors
                    )
                }
            }
        }
    }
}

@Composable
private fun SinglePanelHeader(
    title: String,
    icon: ImageVector,
    onClose: () -> Unit,
    colors: MederiColors
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(colors.surfaceSidebar)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.accentPrimary,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = title,
                color = colors.textPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        IconButton(
            onClick = onClose,
            modifier = Modifier.size(24.dp)
        ) {
            Icon(
                FeatherIcons.X,
                contentDescription = stringResource(Res.string.dock_close_panel),
                tint = colors.textMuted,
                modifier = Modifier.size(13.dp)
            )
        }
    }
}

@Composable
private fun DiffPanelContent(
    viewModel: WorkspaceViewModel,
    colors: MederiColors
) {
    val diffs = viewModel.diffItems
    var selectedFilePath by remember(diffs) { mutableStateOf<String?>(diffs.firstOrNull()?.filePath) }

    if (diffs.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = FeatherIcons.GitCommit,
                contentDescription = null,
                tint = colors.textMuted,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(Res.string.dock_diff_empty),
                color = colors.textSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(Res.string.dock_diff_empty_hint),
                color = colors.textMuted,
                fontSize = 11.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = { viewModel.openDiff() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.buttonSecondary,
                    contentColor = colors.textPrimary
                ),
                shape = RoundedCornerShape(6.dp)
            ) {
                Icon(FeatherIcons.RefreshCw, contentDescription = null, modifier = Modifier.size(12.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(Res.string.dock_refresh_changes), fontSize = 11.sp)
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceCard)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                diffs.forEach { diff ->
                    val isSelected = diff.filePath == selectedFilePath
                    val fileName = diff.filePath.substringAfterLast("/")
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isSelected) colors.surfaceWorkspace else Color.Transparent)
                            .border(
                                1.dp,
                                if (isSelected) colors.divider else Color.Transparent,
                                RoundedCornerShape(4.dp)
                            )
                            .clickable { selectedFilePath = diff.filePath }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = fileName,
                            color = if (isSelected) colors.textPrimary else colors.textMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                        if (diff.additions > 0) {
                            Text("+${diff.additions}", color = colors.accentSuccess, fontSize = 10.sp)
                        }
                        if (diff.deletions > 0) {
                            Text("-${diff.deletions}", color = colors.accentDanger, fontSize = 10.sp)
                        }
                    }
                }
            }
            HorizontalDivider(color = colors.divider)

            val currentDiff = diffs.find { it.filePath == selectedFilePath } ?: diffs.first()
            val noDiffText = stringResource(Res.string.dock_no_text_diff)
            val diffMarkdown = remember(currentDiff, noDiffText) {
                formatDiffMarkdown(currentDiff, noDiffText)
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(10.dp)
            ) {
                MarkdownView(
                    content = diffMarkdown,
                    modifier = Modifier.fillMaxSize(),
                    enableScrollOverride = true,
                    markdownTheme = rememberMederiMarkdownTheme()
                )
            }
        }
    }
}

private fun formatDiffMarkdown(diff: FileDiff, noDiffText: String): String {
    val sb = StringBuilder()
    sb.append("### `${diff.filePath}`\n\n")
    sb.append("```diff\n")
    val beforeLines = diff.before.lines()
    val afterLines = diff.after.lines()
    if (diff.before.isEmpty() && diff.after.isNotEmpty()) {
        afterLines.forEach { sb.append("+ $it\n") }
    } else if (diff.before.isNotEmpty() && diff.after.isEmpty()) {
        beforeLines.forEach { sb.append("- $it\n") }
    } else if (diff.before == diff.after) {
        sb.append(noDiffText + "\n")
    } else {
        val afterSet = afterLines.toSet()
        val beforeSet = beforeLines.toSet()
        var i = 0
        var j = 0
        while (i < beforeLines.size || j < afterLines.size) {
            val b = beforeLines.getOrNull(i)
            val a = afterLines.getOrNull(j)
            if (b != null && a != null && b == a) {
                sb.append("  $b\n")
                i++
                j++
            } else if (b != null && !afterSet.contains(b)) {
                sb.append("- $b\n")
                i++
            } else if (a != null && !beforeSet.contains(a)) {
                sb.append("+ $a\n")
                j++
            } else {
                if (b != null) {
                    sb.append("- $b\n")
                    i++
                }
                if (a != null) {
                    sb.append("+ $a\n")
                    j++
                }
            }
        }
    }
    sb.append("```")
    return sb.toString()
}

@Composable
private fun PlanPanelContent(
    viewModel: WorkspaceViewModel,
    colors: MederiColors
) {
    // 读 VM 派生属性（pendingPlanApproval），不直探内部 snapshot 表示
    val pending = viewModel.pendingPlanApproval
    val currentPlan = viewModel.currentPlan
    val planContent = currentPlan?.content
        ?: pending?.planContent
        ?: (if (pending != null) "# ${pending.title}\n\n${pending.summary}" else null)

    if (planContent.isNullOrBlank()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = FeatherIcons.FileText,
                contentDescription = null,
                tint = colors.textMuted,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(Res.string.dock_plan_empty),
                color = colors.textSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(Res.string.dock_plan_empty_hint),
                color = colors.textMuted,
                fontSize = 11.sp
            )
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            if (pending != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.accentWarning.copy(alpha = 0.12f))
                        .border(1.dp, colors.accentWarning.copy(alpha = 0.3f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(Res.string.dock_plan_pending),
                        color = colors.accentWarning,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = { viewModel.approvePlan(pending.id, approved = false) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.buttonSecondary,
                                contentColor = colors.textPrimary
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(26.dp),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(stringResource(Res.string.dock_plan_modify), fontSize = 10.5.sp)
                        }
                        Button(
                            onClick = { viewModel.approvePlan(pending.id, approved = true) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.accentPrimary,
                                contentColor = colors.onAccentPrimary
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(26.dp),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(stringResource(Res.string.dock_plan_approve), fontSize = 10.5.sp)
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(14.dp)
            ) {
                MarkdownView(
                    content = planContent,
                    modifier = Modifier.fillMaxSize(),
                    enableScrollOverride = true,
                    markdownTheme = rememberMederiMarkdownTheme()
                )
            }
        }
    }
}

@Composable
private fun ArtifactsPanelContent(
    viewModel: WorkspaceViewModel,
    colors: MederiColors
) {
    val items = viewModel.artifactItems
    val activeId = viewModel.activeArtifactId

    if (items.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = FeatherIcons.File,
                contentDescription = null,
                tint = colors.textMuted,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(Res.string.dock_artifact_empty),
                color = colors.textSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(Res.string.dock_artifact_empty_hint),
                color = colors.textMuted,
                fontSize = 11.sp
            )
        }
    } else {
        val activeItem = items.find { it.id == activeId } ?: items.last()
        Column(modifier = Modifier.fillMaxSize()) {
            if (items.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surfaceCard)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items.forEach { item ->
                        val isSelected = item.id == activeItem.id
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isSelected) colors.surfaceWorkspace else Color.Transparent)
                                .clickable { viewModel.openArtifact(item.id) }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = item.title,
                                color = if (isSelected) colors.accentPrimary else colors.textMuted,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
                HorizontalDivider(color = colors.divider)
            }

            when (activeItem) {
                is ArtifactItem.Image -> {
                    ImageViewerTabContent(
                        title = activeItem.title,
                        imageUrl = activeItem.imageUrl,
                        colors = colors
                    )
                }
                is ArtifactItem.Text -> {
                    val liveItem = viewModel.getLiveArtifactItem(activeItem.id) ?: activeItem
                    TextReaderTabContent(
                        title = liveItem.title,
                        content = liveItem.content,
                        lineCount = liveItem.lineCount,
                        charCount = liveItem.charCount,
                        isStreaming = liveItem.isStreaming,
                        colors = colors
                    )
                }
            }
        }
    }
}


@Composable
private fun OverviewTabContent(
    panelWidthDp: Float,
    viewModel: WorkspaceViewModel,
    colors: MederiColors
) {
    val scrollState = rememberScrollState()

    // 上下文占用：用真实 prompt tokens（最近一次请求 API 报告值，与自动压缩触发同源），
    // 而非累计消耗（累计值会把每轮增长的前缀重复计算，长会话会虚高到超过 100%）
    val usedTokens = viewModel.contextUsedTokens
    // contextWindow 是 selectedModel 派生 StateFlow：collectAsState 订阅后，
    // 模型切换触发重组，同源读取的 referenceCostUsd 也随之刷新
    val maxTokens by viewModel.contextWindow.collectAsState()
    val requestCount = viewModel.userMessageCount
    // 参考价估算（models.dev 目录价 × token 用量），非真实账单
    val costUsd = viewModel.referenceCostUsd
    val todoList = viewModel.todos

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
    // 响应式判断：只有当宽度 >= 600dp 时才切换双列 Dashboard，窄屏时紧凑单列
    val isWide = panelWidthDp >= 600f
    // null = 模型未配置 contextWindow：映射为 0，ContextMetricsCard 据此显示 "--" 而非伪造 0% 已用
    val maxTokensValue = maxTokens ?: 0

    if (isWide) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                ContextMetricsCard(
                    usedTokens = usedTokens,
                    maxTokens = maxTokensValue,
                    requestCount = requestCount,
                    costUsd = costUsd,
                    onCompact = { viewModel.requestCompaction() },
                    colors = colors
                )
            }

            // 仅当 todoList 有内容时展示 TodoList 板块
            if (todoList.isNotEmpty()) {
                Box(modifier = Modifier.weight(1f)) {
                    TodoListCard(
                        todoList = todoList,
                        colors = colors
                    )
                }
            }
        }
    } else {
        // 紧凑单列布局
        ContextMetricsCard(
            usedTokens = usedTokens,
            maxTokens = maxTokensValue,
            requestCount = requestCount,
            costUsd = costUsd,
            onCompact = { viewModel.requestCompaction() },
            colors = colors
        )

            // 仅当 todoList 有内容时展示 TodoList 板块
            if (todoList.isNotEmpty()) {
                TodoListCard(
                    todoList = todoList,
                    colors = colors
                )
            }
        }

        // MCP 服务管理卡片（与后续市场共享唯一真理源 mcpStore）
        McpManagementCard(
            mcpStore = viewModel.mcpStore,
            colors = colors
        )

        // Skill 技能管理卡片（与后续市场共享唯一真理源 skillStore）
        SkillManagementCard(
            skillStore = viewModel.skillStore,
            colors = colors
        )

        // 原始消息列表（概览下方展示）
        RawMessagesCard(
            viewModel = viewModel,
            colors = colors
        )
    }
}

@Composable
private fun ContextMetricsCard(
    usedTokens: Long,
    maxTokens: Int,
    requestCount: Int,
    costUsd: Double,
    onCompact: () -> Unit,
    colors: MederiColors
) {
    // 模型未配置 contextWindow 时（OPENAI_CHAT 拉模型只返回 id，无元数据），无法计算占比。
    // 不兜底造数据：百分比显示 "--"，进度条不渲染，并提示去供应商设置里补 contextWindow。
    val hasWindow = maxTokens > 0
    val progressRatio = if (hasWindow) (usedTokens.toFloat() / maxTokens.toFloat()).coerceIn(0f, 1f) else 0f
    val percentText = if (hasWindow) "${(progressRatio * 100).toInt()}%" else "--"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceCard)
            .border(1.dp, colors.divider, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Card Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(Res.string.dock_context_metrics),
                color = colors.textMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
            Text(
                text = stringResource(Res.string.dock_context_used, percentText),
                color = colors.accentPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }

        // Progress Bar (更细小 4dp)；无 contextWindow 时不渲染，避免误读为 0% 已用
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (hasWindow) {
                LinearProgressIndicator(
                    progress = { progressRatio },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(CircleShape),
                    color = colors.accentPrimary,
                    trackColor = colors.buttonSecondary
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "$usedTokens Tokens",
                    color = colors.textSecondary,
                    fontSize = 10.sp
                )
                Text(
                    text = if (hasWindow) stringResource(Res.string.dock_context_max, maxTokens)
                           else stringResource(Res.string.dock_context_unset),
                    color = colors.textMuted,
                    fontSize = 10.sp
                )
            }
        }

        // Metrics Grid: Request count & Estimated cost
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Request Count Box
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.surfaceWorkspace)
                    .padding(vertical = 8.dp, horizontal = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(text = stringResource(Res.string.dock_requests_title), color = colors.textMuted, fontSize = 10.sp)
                Text(
                    text = stringResource(Res.string.dock_requests_count, requestCount),
                    color = colors.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Estimated Cost Box
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.surfaceWorkspace)
                    .padding(vertical = 8.dp, horizontal = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(text = stringResource(Res.string.dock_cost_title), color = colors.textMuted, fontSize = 10.sp)
                Text(
                    text = "$${formatCost(costUsd)}",
                    color = colors.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Compact Context Button (扁平小高度 30dp)
        Button(
            onClick = onCompact,
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            shape = RoundedCornerShape(6.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.buttonSecondary,
                contentColor = colors.textPrimary
            )
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = FeatherIcons.Minimize2,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp)
                )
                Text(
                    text = stringResource(Res.string.dock_compact_context),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun TodoListCard(
    todoList: List<TodoItem>,
    colors: MederiColors
) {
    val completedCount = todoList.count { it.status == TodoStatus.Completed }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceCard)
            .border(1.dp, colors.divider, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "TODO-LIST",
                    color = colors.textMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = "$completedCount/${todoList.size}",
                    color = colors.textSecondary,
                    fontSize = 10.sp
                )
            }
        }

        // Tasks list（四态：Pending 灰 / InProgress 高亮 / Completed 绿勾划线 / Failed 红）
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            todoList.forEach { task ->
                val isDone = task.status == TodoStatus.Completed
                val isRunning = task.status == TodoStatus.InProgress
                val isFailed = task.status == TodoStatus.Failed
                val boxColor = when {
                    isDone -> colors.accentPrimary
                    isRunning -> colors.accentPrimary
                    isFailed -> colors.accentDanger
                    else -> colors.buttonSecondary
                }
                val textColor = when {
                    isDone -> colors.textMuted
                    isFailed -> colors.accentDanger
                    else -> colors.textPrimary
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.surfaceWorkspace)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                if (isRunning) boxColor.copy(alpha = 0.25f) else boxColor
                            )
                            .border(
                                1.dp,
                                boxColor,
                                RoundedCornerShape(3.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isDone) {
                            Icon(
                                imageVector = FeatherIcons.Check,
                                contentDescription = null,
                                tint = colors.onAccentPrimary,
                                modifier = Modifier.size(10.dp)
                            )
                        } else if (isRunning) {
                            // 进行中：实心圆点（不引图标，点即"正在做"的视觉焦点）
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(colors.accentPrimary)
                            )
                        }
                    }

                    Text(
                        text = task.content,
                        color = textColor,
                        fontSize = 11.sp,
                        fontWeight = if (isRunning) FontWeight.SemiBold else FontWeight.Normal,
                        textDecoration = if (isDone) TextDecoration.LineThrough else TextDecoration.None,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private fun formatCost(cost: Double): String {
    return if (cost < 0.001) "0.000" else (kotlin.math.round(cost * 1000) / 1000.0).toString()
}

/**
 * 扩展面板图片查看器内容
 */
@Composable
private fun ImageViewerTabContent(
    title: String,
    imageUrl: String,
    colors: MederiColors
) {
    var isOriginalScale by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        // 工具条：标题、比例适应/原始大小切换
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .background(colors.surfaceCard)
                .border(1.dp, colors.surfaceCardBorder)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f, fill = false)
            ) {
                Icon(
                    imageVector = FeatherIcons.Image,
                    contentDescription = null,
                    tint = colors.accentPrimary,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = title,
                    color = colors.textPrimary,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(colors.surfaceWorkspace)
                    .clickable { isOriginalScale = !isOriginalScale }
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = if (isOriginalScale) stringResource(Res.string.dock_fit_window) else stringResource(Res.string.dock_original_ratio),
                    color = colors.accentPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // 大图居中展示画板（暗色背景衬托细节）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.surfaceOverlay.copy(alpha = 0.9f))
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            InkImage(
                model = imageUrl,
                contentDescription = title,
                contentScale = if (isOriginalScale) androidx.compose.ui.layout.ContentScale.None else androidx.compose.ui.layout.ContentScale.Fit,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .then(
                        if (isOriginalScale) {
                            Modifier
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState())
                        } else {
                            Modifier.fillMaxSize()
                        }
                    )
            )
        }
    }
}

/**
 * 扩展面板大文本阅读器内容 (使用 inkcompose.MarkdownView 进行富文本排版)
 */
@Composable
private fun TextReaderTabContent(
    title: String,
    content: String,
    lineCount: Int,
    charCount: Int,
    isStreaming: Boolean = false,
    colors: MederiColors
) {
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }

    var exportingHtml by remember { mutableStateOf(false) }
    var exportedHtml by remember { mutableStateOf(false) }

    var exportingPdf by remember { mutableStateOf(false) }
    var exportedPdf by remember { mutableStateOf(false) }

    var exportError by remember { mutableStateOf<String?>(null) }
    // 导出错误文案在组合上下文取值（coroutineScope.launch / 回调不是 @Composable）
    val htmlSaveFailedMsg = stringResource(Res.string.dock_html_export_failed)
    val pdfExportFailedMsg = stringResource(Res.string.dock_pdf_export_failed)
    val exportErrorMsg = stringResource(Res.string.dock_export_error)

    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(2000)
            copied = false
        }
    }

    LaunchedEffect(exportedHtml) {
        if (exportedHtml) {
            kotlinx.coroutines.delay(2500)
            exportedHtml = false
        }
    }

    LaunchedEffect(exportedPdf) {
        if (exportedPdf) {
            kotlinx.coroutines.delay(2500)
            exportedPdf = false
        }
    }

    LaunchedEffect(exportError) {
        if (exportError != null) {
            kotlinx.coroutines.delay(3500)
            exportError = null
        }
    }

    val safeBaseName = remember(title) {
        val cleaned = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        cleaned.ifBlank { "document" }
    }

    val saveDialogTitle = stringResource(Res.string.pick_save_file_title)
    val htmlFilterLabel = stringResource(Res.string.pick_file_filter, "HTML", "html")
    val pdfFilterLabel = stringResource(Res.string.pick_file_filter, "PDF", "pdf")

    val infiniteTransition = rememberInfiniteTransition(label = "streamingIndicator")
    val streamGlowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "streamGlowAlpha"
    )

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部信息条
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .background(colors.surfaceCard)
                .border(1.dp, colors.surfaceCardBorder)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f, fill = false)
            ) {
                Icon(
                    imageVector = FeatherIcons.File,
                    contentDescription = null,
                    tint = colors.accentSecondary,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = title,
                    color = colors.textPrimary,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (charCount > 0 || lineCount > 0) {
                    Text(
                        text = stringResource(Res.string.attachment_meta, lineCount, charCount),
                        color = colors.textMuted,
                        fontSize = 11.sp
                    )
                }
                if (isStreaming) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(colors.accentPrimary.copy(alpha = 0.12f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(colors.accentPrimary.copy(alpha = streamGlowAlpha))
                        )
                        Text(
                            text = stringResource(Res.string.dock_generating),
                            color = colors.accentPrimary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // 操作按钮区：复制全文、导出 HTML、导出 PDF
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (exportError != null) {
                    Text(
                        text = exportError ?: "",
                        color = colors.accentDanger,
                        fontSize = 10.5.sp,
                        maxLines = 1
                    )
                }

                // 复制全文按钮
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (copied) colors.accentSuccess.copy(alpha = 0.15f) else colors.surfaceWorkspace)
                        .clickable {
                            clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(content))
                            copied = true
                        }
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Icon(
                        imageVector = if (copied) FeatherIcons.Check else FeatherIcons.Copy,
                        contentDescription = null,
                        tint = if (copied) colors.accentSuccess else colors.textMuted,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = if (copied) stringResource(Res.string.copy_done) else stringResource(Res.string.dock_copy_full),
                        color = if (copied) colors.accentSuccess else colors.textPrimary,
                        fontSize = 11.sp
                    )
                }

                // 导出 HTML 按钮
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            when {
                                exportedHtml -> colors.accentSuccess.copy(alpha = 0.15f)
                                exportingHtml -> colors.accentPrimary.copy(alpha = 0.15f)
                                else -> colors.surfaceWorkspace
                            }
                        )
                        .clickable(enabled = !exportingHtml && !exportingPdf) {
                            coroutineScope.launch {
                                val path = pickSaveFile(safeBaseName, "html", saveDialogTitle, htmlFilterLabel) ?: return@launch
                                exportingHtml = true
                                try {
                                    val html = MarkdownExporter.toHtml(content, HtmlExportOptions(title = title))
                                    val ok = writeTextToFile(path, html)
                                    if (ok) {
                                        exportedHtml = true
                                        openFile(path)
                                    } else {
                                        exportError = htmlSaveFailedMsg
                                    }
                                } catch (e: Exception) {
                                    exportError = e.message ?: exportErrorMsg
                                } finally {
                                    exportingHtml = false
                                }
                            }
                        }
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Icon(
                        imageVector = if (exportedHtml) FeatherIcons.Check else FeatherIcons.Code,
                        contentDescription = null,
                        tint = when {
                            exportedHtml -> colors.accentSuccess
                            exportingHtml -> colors.accentPrimary
                            else -> colors.textMuted
                        },
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = when {
                            exportingHtml -> stringResource(Res.string.dock_exporting)
                            exportedHtml -> stringResource(Res.string.dock_exported)
                            else -> stringResource(Res.string.dock_export_html)
                        },
                        color = when {
                            exportedHtml -> colors.accentSuccess
                            exportingHtml -> colors.accentPrimary
                            else -> colors.textPrimary
                        },
                        fontSize = 11.sp
                    )
                }

                // 导出 PDF 按钮
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            when {
                                exportedPdf -> colors.accentSuccess.copy(alpha = 0.15f)
                                exportingPdf -> colors.accentPrimary.copy(alpha = 0.15f)
                                else -> colors.surfaceWorkspace
                            }
                        )
                        .clickable(enabled = !exportingHtml && !exportingPdf) {
                            coroutineScope.launch {
                                val path = pickSaveFile(safeBaseName, "pdf", saveDialogTitle, pdfFilterLabel) ?: return@launch
                                exportingPdf = true
                                try {
                                    val result = MarkdownExporter.toPdf(content, path, PdfExportOptions(title = title))
                                    if (result.isSuccess) {
                                        exportedPdf = true
                                        openFile(path)
                                    } else {
                                        exportError = result.exceptionOrNull()?.message ?: pdfExportFailedMsg
                                    }
                                } catch (e: Exception) {
                                    exportError = e.message ?: exportErrorMsg
                                } finally {
                                    exportingPdf = false
                                }
                            }
                        }
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Icon(
                        imageVector = if (exportedPdf) FeatherIcons.Check else FeatherIcons.Download,
                        contentDescription = null,
                        tint = when {
                            exportedPdf -> colors.accentSuccess
                            exportingPdf -> colors.accentPrimary
                            else -> colors.textMuted
                        },
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = when {
                            exportingPdf -> stringResource(Res.string.dock_exporting)
                            exportedPdf -> stringResource(Res.string.dock_exported)
                            else -> stringResource(Res.string.dock_export_pdf)
                        },
                        color = when {
                            exportedPdf -> colors.accentSuccess
                            exportingPdf -> colors.accentPrimary
                            else -> colors.textPrimary
                        },
                        fontSize = 11.sp
                    )
                }
            }
        }

        // 正文阅读器区域：使用 inkcompose.MarkdownView 统一高质量渲染，支持流式渲染状态
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.surfaceWorkspace)
                .padding(14.dp)
        ) {
            MarkdownView(
                content = content,
                modifier = Modifier.fillMaxSize(),
                enableScrollOverride = true,
                isStreaming = isStreaming,
                markdownTheme = rememberMederiMarkdownTheme()
            )
        }
    }
}

/**
 * 内置浏览器面板：desktop 注入 UiBrowserHost（tab = KBPage）渲染真内容；
 * 遥控端/wasm 未注入 → 显示"当前端不可用"占位（不尝试渲染 JCEF）。
 */
@Composable
private fun BrowserPanelContent(
    viewModel: WorkspaceViewModel,
    colors: MederiColors
) {
    val host = viewModel.appStateRef.uiBrowserHost
    if (host == null || !host.isAvailable) {
        Box(
            modifier = Modifier.fillMaxSize().background(colors.surfaceWorkspace),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp)
            ) {
                Icon(
                    imageVector = FeatherIcons.Globe,
                    contentDescription = stringResource(Res.string.rightdock_browser),
                    tint = colors.textMuted,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringResource(Res.string.dock_browser_unsupported),
                    color = colors.textSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(Res.string.dock_browser_desktop_only),
                    color = colors.textMuted,
                    fontSize = 11.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
        return
    }

    host.BrowserContent(colors = colors)
}

/** 右侧面板标题唯一映射点（枚举不持有表现层文案，i18n 约定）。 */
@Composable
private fun rightDockPanelTitle(panel: RightDockPanel): String = stringResource(
    when (panel) {
        RightDockPanel.OVERVIEW -> Res.string.rightdock_overview
        RightDockPanel.DIFF -> Res.string.rightdock_diff
        RightDockPanel.PLAN -> Res.string.rightdock_plan
        RightDockPanel.SUB_AGENTS -> Res.string.rightdock_sub_agents
        RightDockPanel.ARTIFACTS -> Res.string.rightdock_artifacts
        RightDockPanel.TERMINAL -> Res.string.rightdock_terminal
        RightDockPanel.BROWSER -> Res.string.rightdock_browser
    }
)
