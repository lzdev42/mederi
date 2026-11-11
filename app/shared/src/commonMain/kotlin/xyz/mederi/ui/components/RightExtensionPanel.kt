package xyz.mederi.ui.components

import xyz.mederi.theme.LocalWorkspaceMotion
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.dock_close_panel
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.ui.DebugLog
import xyz.mederi.ui.RawMessagesViewModel
import xyz.mederi.ui.RightDockPanel
import xyz.mederi.ui.TerminalViewModel
import xyz.mederi.ui.WorkspaceViewModel
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiColors
import xyz.mederi.ui.components.atoms.WorkspacePanelHeader
import xyz.mederi.theme.WorkspaceStyle

@Composable
fun RightExtensionPanel(
    isOpen: Boolean,
    onClose: () -> Unit,
    viewModel: WorkspaceViewModel,
    rawMessagesViewModel: RawMessagesViewModel,
    terminalViewModel: TerminalViewModel,
    isCompact: Boolean = false,
    screenWidth: Dp = 1200.dp,
    maxPanelWidth: Dp = Dp.Infinity,
    modifier: Modifier = Modifier
) {
    val defaultReaderWidthDp = remember(screenWidth, maxPanelWidth) {
        (screenWidth.value * (2f / 3f)).coerceAtMost(maxPanelWidth.value).coerceAtLeast(340f)
    }

    val currentPanel = viewModel.activeDockPanel
    // 关闭动画期间保持上一个面板渲染（收缩动画中内容不闪空）。
    // 副作用经 LaunchedEffect，不在组合期直接写状态
    var panelToDisplay by remember { mutableStateOf(currentPanel ?: RightDockPanel.DIFF) }

    var readerWidthDp by remember { mutableStateOf(defaultReaderWidthDp) }

    LaunchedEffect(currentPanel) {
        if (currentPanel != null) {
            panelToDisplay = currentPanel
            // 开启浏览器或阅读面板时，默认宽度占全屏 2/3（受 maxPanelWidth 保护）
            if (currentPanel == RightDockPanel.BROWSER_AUTOMATION) {
                readerWidthDp = defaultReaderWidthDp
            } else if (currentPanel == RightDockPanel.ARTIFACTS && readerWidthDp < defaultReaderWidthDp) {
                readerWidthDp = defaultReaderWidthDp
            }
        }
    }

    val currentWidthDp = readerWidthDp
    // 布局期钳制：面板拖宽后窗口缩小、或面板打开时窗口较窄，面板让位给对话区（不超 maxPanelWidth）
    val effectivePanelWidthDp = currentWidthDp.coerceAtMost(maxPanelWidth.value)

    DebugLog.debug("UI", "RightExtensionPanel: isOpen=$isOpen, currentPanel=$currentPanel, rendering=$panelToDisplay, width=$effectivePanelWidthDp (raw=$currentWidthDp, maxPanel=$maxPanelWidth)")

    val handleWidthChange: (Float) -> Unit = { newWidth ->
        // 不设固定上限：宽度上限 = maxPanelWidth，保证对话区 ≥ 手机宽度
        readerWidthDp = newWidth.coerceAtLeast(240f).coerceAtMost(maxPanelWidth.value)
    }

    if (isCompact) {
        RightExtensionPanelContent(
            panelWidthDp = effectivePanelWidthDp,
            maxPanelWidth = maxPanelWidth,
            onWidthChange = handleWidthChange,
            onClose = onClose,
            panel = panelToDisplay,
            viewModel = viewModel,
            rawMessagesViewModel = rawMessagesViewModel,
            terminalViewModel = terminalViewModel,
            isCompact = true,
            modifier = modifier
        )
    } else {
        AnimatedVisibility(
            visible = isOpen,
            // 右侧面板从右边缘展开/收缩（expandFrom=End）：居中展开会在动画期间两侧露出底层白缝
            enter = if (LocalWorkspaceMotion.current.reducedMotion) EnterTransition.None else fadeIn(LocalWorkspaceMotion.current.panelSpec()) + expandHorizontally(animationSpec = LocalWorkspaceMotion.current.panelSpec(), expandFrom = Alignment.End),
            exit = if (LocalWorkspaceMotion.current.reducedMotion) ExitTransition.None else fadeOut(LocalWorkspaceMotion.current.panelSpec()) + shrinkHorizontally(animationSpec = LocalWorkspaceMotion.current.panelSpec(), shrinkTowards = Alignment.End)
        ) {
            RightExtensionPanelContent(
                panelWidthDp = effectivePanelWidthDp,
                maxPanelWidth = maxPanelWidth,
                onWidthChange = handleWidthChange,
                onClose = onClose,
                panel = panelToDisplay,
                viewModel = viewModel,
                rawMessagesViewModel = rawMessagesViewModel,
                terminalViewModel = terminalViewModel,
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
    rawMessagesViewModel: RawMessagesViewModel,
    terminalViewModel: TerminalViewModel,
    isCompact: Boolean = false,
    modifier: Modifier
) {
    val colors = LocalMederiColors.current
    val density = LocalDensity.current
    val currentWidthState = rememberUpdatedState(panelWidthDp)
    val maxPanelWidthState = rememberUpdatedState(maxPanelWidth)
    val onWidthChangeState = rememberUpdatedState(onWidthChange)

    val panelIcon = when (panel) {
        RightDockPanel.DIFF -> FeatherIcons.Folder
        RightDockPanel.FILES -> FeatherIcons.Folder
        RightDockPanel.ARTIFACTS -> FeatherIcons.FileText
        RightDockPanel.TERMINAL -> FeatherIcons.Terminal
        RightDockPanel.BROWSER_AUTOMATION -> FeatherIcons.Globe
        RightDockPanel.BROWSER -> FeatherIcons.Globe
    }

    Row(
        modifier = modifier
            .fillMaxHeight()
            .then(if (isCompact) Modifier.fillMaxWidth() else Modifier.width(panelWidthDp.dp))
            .background(colors.surfaceWorkspace)
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
                            val minW = 240f
                            // 上限 = maxPanelWidth（对话区保底手机宽度），不设固定上限
                            val newWidth = (current - dragDp).coerceAtLeast(minW).coerceAtMost(maxPanelWidthState.value.value)
                            DebugLog.event("UI", "RightExtensionPanel drag: dragAmountPx=$dragAmount, dragDp=$dragDp, current=${current}dp, newWidth=${newWidth}dp")
                            onWidthChangeState.value(newWidth)
                        }
                    }
                    .background(colors.surfaceWorkspace),
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
            WorkspacePanelHeader(
                title = rightDockPanelTitle(panel),
                icon = panelIcon,
                onClose = onClose,
                colors = colors
            )

            HorizontalDivider(color = colors.divider.copy(alpha = 0.5f))

            // 独立面板内容
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when (panel) {
                    RightDockPanel.DIFF -> DiffPanelContent(
                        viewModel = viewModel,
                        colors = colors
                    )
                    RightDockPanel.FILES -> TreePanelContent(
                        viewModel = viewModel,
                        colors = colors
                    )
                    RightDockPanel.ARTIFACTS -> ArtifactsPanelContent(
                        viewModel = viewModel,
                        colors = colors
                    )
                    RightDockPanel.TERMINAL -> TerminalPanelContent(
                        viewModel = viewModel,
                        terminalVm = terminalViewModel,
                        colors = colors
                    )
                    RightDockPanel.BROWSER_AUTOMATION -> BrowserAutomationDockPanel(
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
