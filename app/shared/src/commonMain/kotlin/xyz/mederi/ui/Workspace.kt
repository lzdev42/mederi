package xyz.mederi.ui

import xyz.mederi.theme.LocalWorkspaceMotion
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition

import androidx.compose.foundation.background
import androidx.compose.animation.*
import xyz.mederi.theme.WorkspaceStyle
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import xyz.mederi.util.PlatformClipboard
import xyz.mederi.util.copyText
import xyz.mederi.util.readPlainTextFromClip
import xyz.mederi.util.rememberClipboardCopy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.emuci.markdown.renderer.MarkdownTheme
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.Grid
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Shield
import compose.icons.feathericons.Zap
import xyz.mederi.core.contract.models.*
import xyz.mederi.ui.ChatListItem
import xyz.mederi.ui.RawMessagesViewModel
import xyz.mederi.ui.TerminalViewModel
import xyz.mederi.ui.WorkspaceViewModel
import xyz.mederi.ui.WorkspaceViewModel.DocumentTarget
import xyz.mederi.ui.appstate.LocalAppState
import androidx.lifecycle.viewmodel.compose.viewModel
import compose.icons.feathericons.Sidebar
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.input_image_attachment
import mederi.app.shared.generated.resources.input_image_n
import mederi.app.shared.generated.resources.input_pasted_text_n
import mederi.app.shared.generated.resources.mode_auto_approve
import mederi.app.shared.generated.resources.mode_manual_approve
import mederi.app.shared.generated.resources.copy
import mederi.app.shared.generated.resources.ws_add_to_input
import mederi.app.shared.generated.resources.ws_ai_image_n
import mederi.app.shared.generated.resources.ws_paste_to_input
import mederi.app.shared.generated.resources.ws_search
import mederi.app.shared.generated.resources.ws_conversation_id
import mederi.app.shared.generated.resources.ws_generated_image_n
import mederi.app.shared.generated.resources.ws_new_conversation
import mederi.app.shared.generated.resources.overview_show
import mederi.app.shared.generated.resources.overview_hide
import mederi.app.shared.generated.resources.ws_open_menu
import mederi.app.shared.generated.resources.ws_open_sidebar
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.theme.LocalMederiColors
import androidx.compose.runtime.CompositionLocalProvider
import xyz.emuci.inkcompose.LocalSessionKey
import xyz.emuci.inkcompose.MarkdownView
import xyz.mederi.ui.components.EventMessageCard
import xyz.mederi.ui.components.RightExtensionPanel
import xyz.mederi.ui.components.RightDock
import xyz.mederi.ui.components.SystemInfoBar
import xyz.mederi.ui.components.ReasoningBlock
import xyz.mederi.ui.components.ToolCallsBlock
import xyz.mederi.ui.components.SubagentCallsBlock
import xyz.mederi.ui.components.SubagentConversationPanel
import xyz.mederi.ui.components.QuestionCard
import xyz.mederi.ui.components.PlanApprovalCard
import xyz.mederi.ui.components.ChatInputCard
import xyz.mederi.ui.components.HandoffPanelContent
import xyz.mederi.ui.components.WorkspaceArchivedListContent
import xyz.mederi.ui.components.WorkspaceDialogErrorContent
import xyz.mederi.ui.components.WorkspaceManagementPanelContent
import xyz.mederi.ui.components.WorkspaceRecoveryPanelContent
import xyz.mederi.ui.components.EnvironmentEditorPanelContent
import xyz.mederi.ui.components.WorktreeSettingsPanelContent
import xyz.mederi.ui.components.CreateBranchPanelContent
import xyz.mederi.ui.components.StatusBar
import xyz.mederi.ui.components.WorkspaceFloatingOverlay
import xyz.mederi.ui.overview.OverviewWindowState
import xyz.mederi.ui.overview.WorkspaceAgentOverviewStack
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.zIndex
import xyz.mederi.ui.components.ErrorDetailDialog
import xyz.mederi.ui.components.UserPastedTextCard
import xyz.mederi.ui.components.UserMessageFooter
import xyz.mederi.ui.components.AssistantMessageFooter
import xyz.mederi.ui.components.DocumentArtifactCard
import xyz.mederi.ui.components.TurnDiffSummaryCard
import xyz.mederi.ui.components.WorkTraceCard
import androidx.compose.foundation.text.selection.DisableSelection
import xyz.mederi.util.PromptComposer
import xyz.mederi.util.encodeUrlQuery
import xyz.emuci.inkcompose.InkImage
import xyz.emuci.inkcompose.SelectionMenuAction
import xyz.mederi.ui.components.atoms.ConfirmDialog

import mederi.app.shared.generated.resources.cancel
import mederi.app.shared.generated.resources.file_open_confirm_generic
import mederi.app.shared.generated.resources.file_open_confirm_reveal
import mederi.app.shared.generated.resources.file_open_confirm_directory
import mederi.app.shared.generated.resources.file_open_confirm_with_app
import mederi.app.shared.generated.resources.file_open_open
import mederi.app.shared.generated.resources.handoff_dialog_title
import mederi.app.shared.generated.resources.workspace_dialog_retry
import mederi.app.shared.generated.resources.workspace_recovery_title
import mederi.app.shared.generated.resources.handoff_executing
import mederi.app.shared.generated.resources.workspace_dialog_error

import compose.icons.feathericons.Menu
import androidx.compose.ui.text.style.TextOverflow

@Composable
fun Workspace(
    modifier: Modifier = Modifier,
    viewModel: WorkspaceViewModel,
    isCompact: Boolean = false,
    isLeftSidebarOpen: Boolean = true,
    onToggleLeftSidebar: () -> Unit = {},
    onOpenProjectPicker: () -> Unit = {},
    imeOverlayActive: Boolean = false,
    projectMenuExpanded: Boolean,
    onProjectMenuExpandedChange: (Boolean) -> Unit,
) {
    val colors = LocalMederiColors.current
    val appState = LocalAppState.current
    // 覆盖层状态传递：任何 Dialog/错误详情/文件确认弹窗打开时，ChatInputCard 需隐藏 IME 桥接 textarea
    val wsOverlayActive = imeOverlayActive ||
        viewModel.isErrorDetailOpen ||
        viewModel.pendingFileOpen != null
    // 子面板 VM 在 Route 层统一创建（ViewModelStore 管理生命周期），组件只接收实例渲染
    val rawMessagesViewModel: RawMessagesViewModel = viewModel { RawMessagesViewModel(appState) }
    val terminalViewModel: TerminalViewModel = viewModel { TerminalViewModel(appState) }
    val isRightPanelOpen = viewModel.isRightPanelOpen
    val overviewState = remember { OverviewWindowState() }
    var overviewLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(appState.preferencesStore) {
        overviewState.load(appState.preferencesStore)
        overviewLoaded = true
    }
    LaunchedEffect(overviewState.visible, overviewLoaded) {
        if (overviewLoaded) overviewState.save(appState.preferencesStore)
    }

    DisposableEffect(viewModel, overviewLoaded, overviewState.visible) {
        val release = if (overviewLoaded && overviewState.visible) viewModel.observeBackgroundTasks() else null
        onDispose { release?.invoke() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surfaceWorkspace)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            val screenWidth = maxWidth
            // 右侧扩展面板可用最大宽度 = 工作区宽度 - 对话视图最小宽度（手机宽度）- 常驻 Dock 宽度。
            // 面板不设固定上限（原 800/1600dp）：唯一约束是不得把对话区压到手机宽度以下。
            val maxPanelWidth = (screenWidth - ChatLayout.conversationMinWidth - ChatLayout.rightDockWidth)
                .coerceAtLeast(0.dp)
            Row(
                modifier = Modifier.fillMaxSize()
            ) {
                // 中央主工作区 (包含顶部 Header 与 聊天/消息区域 + 浮动概览窗 overlay)
                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        // 对话视图最小宽度 = 手机宽度：扩展面板再宽也不能把对话区压到手机宽度以下
                        .widthIn(min = if (isCompact) Dp.Unspecified else ChatLayout.conversationMinWidth)
                ) {
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // 中央区 Header (40dp 高度对齐全屏顶栏线条)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .background(colors.surfaceWorkspace)
                            .border(width = 1.dp, color = colors.divider)
                            .padding(horizontal = if (isCompact) 8.dp else 12.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 侧边栏开闭按钮：移动端显示 Menu 图标，桌面端在侧边栏未固定/未打开时显示 Sidebar 图标
                            if (isCompact || !isLeftSidebarOpen) {
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { onToggleLeftSidebar() },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (isCompact) FeatherIcons.Menu else FeatherIcons.Sidebar,
                                        contentDescription = stringResource(if (isCompact) Res.string.ws_open_menu else Res.string.ws_open_sidebar),
                                        tint = colors.textSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                            }

                            // 顶部面包屑：项目名 / 对话名（派生 StateFlow，UI collectAsState 订阅）
                            val header by viewModel.headerTitle.collectAsState()

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                val projectName = header.projectName
                                if (projectName != null && !isCompact) {
                                    Text(
                                        text = projectName,
                                        color = colors.textSecondary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "/",
                                        color = colors.textMuted,
                                        fontSize = 12.sp
                                    )
                                }
                                Text(
                                    text = header.conversationName
                                        ?: (header.conversationId?.let { stringResource(Res.string.ws_conversation_id, it.take(8)) }
                                            ?: stringResource(Res.string.ws_new_conversation)),
                                    color = colors.textPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.weight(1f))

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isCompact) {
                                    run {
                                        // 唯一真理源：AppState.selectedAgentMode 派生流，collectAsState 订阅
                                        val mode by viewModel.selectedAgentMode.collectAsState()
                                        val isAutonomous = mode == AgentMode.AUTONOMOUS
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(if (isAutonomous) colors.accentPrimary.copy(alpha = 0.15f) else colors.accentWarning.copy(alpha = 0.15f))
                                                .border(1.dp, if (isAutonomous) colors.accentPrimary.copy(alpha = 0.4f) else colors.accentWarning.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                                                .clickable {
                                                    viewModel.selectAgentMode(if (isAutonomous) AgentMode.APPROVAL else AgentMode.AUTONOMOUS)
                                                }
                                                .padding(horizontal = 6.dp, vertical = 3.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                                            ) {
                                                Icon(
                                                    imageVector = if (isAutonomous) FeatherIcons.Zap else FeatherIcons.Shield,
                                                    contentDescription = null,
                                                    tint = if (isAutonomous) colors.accentPrimary else colors.accentWarning,
                                                    modifier = Modifier.size(12.dp)
                                                )
                                                Text(
                                                    text = if (isAutonomous) stringResource(Res.string.mode_auto_approve) else stringResource(Res.string.mode_manual_approve),
                                                    color = if (isAutonomous) colors.accentPrimary else colors.accentWarning,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Medium
                                                )
                                            }
                                        }
                                    }

                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable { viewModel.selectConversation(null) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = FeatherIcons.Plus,
                                            contentDescription = stringResource(Res.string.ws_new_conversation),
                                            tint = colors.textSecondary,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                }

                                // 概览独立开关：选中状态直接对应窗口可见性，扩展面板仍由 Dock 控制。
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (overviewState.visible) colors.accentBg else Color.Transparent)
                                        .semantics { selected = overviewState.visible }
                                        .clickable { overviewState.visible = !overviewState.visible },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = FeatherIcons.Grid,
                                        contentDescription = stringResource(if (overviewState.visible) Res.string.overview_hide else Res.string.overview_show),
                                        tint = if (overviewState.visible) colors.accentText else colors.textSecondary,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                        }
                    }

                    // 主内容区（消息列表与输入框）
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        val contentMaxWidth = ChatLayout.contentMaxWidth
                        when {
                            viewModel.messages.isEmpty() && !viewModel.isWorking -> {
                                // 空状态：首帧快照未到（会话已选中但尚未 attach）时骨架占位，否则居中欢迎页
                                val awaitingFirstSnapshot = viewModel.conversationId != null && !viewModel.isAttached
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(
                                            bottom = if (isCompact) 20.dp else 60.dp,
                                            start = if (isCompact) 12.dp else 24.dp,
                                            end = if (isCompact) 12.dp else 24.dp
                                        ),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = if (awaitingFirstSnapshot) Arrangement.Top else Arrangement.Center
                                ) {
                                    if (awaitingFirstSnapshot) {
                                        ChatListSkeleton(
                                            modifier = Modifier.fillMaxWidth(),
                                            contentMaxWidth = contentMaxWidth,
                                            horizontalPadding = if (isCompact) 8.dp else 24.dp
                                        )
                                    } else {
                                        Text(
                                            text = "mederi",
                                            color = colors.textPrimary,
                                            fontSize = if (isCompact) 24.sp else 28.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = (-0.5).sp
                                        )
                                        Spacer(modifier = Modifier.height(16.dp))
                                    }
                                    Column(
                                        modifier = Modifier
                                            .widthIn(max = if (isCompact) Dp.Unspecified else 700.dp)
                                            .fillMaxWidth(),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        WorkspaceFloatingOverlay(
                                            viewModel = viewModel,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .zIndex(10f)
                                                .padding(bottom = 6.dp)
                                        )

                                        ChatInputCard(
                                            viewModel = viewModel,
                                            modifier = Modifier.fillMaxWidth(),
                                            onOpenProjectPicker = onOpenProjectPicker,
                                            imeOverlayActive = wsOverlayActive,
                                            projectMenuExpanded = projectMenuExpanded,
                                            onProjectMenuExpandedChange = onProjectMenuExpandedChange
                                        )
                                    }
                                }
                            }
                            else -> {
                                // 有消息
                                MessageList(
                                    viewModel = viewModel,
                                    contentMaxWidth = contentMaxWidth,
                                    isCompact = isCompact,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = if (isCompact) 8.dp else 24.dp)
                                        .padding(bottom = if (isCompact) 8.dp else 24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .widthIn(max = contentMaxWidth)
                                            .fillMaxWidth(),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        // 顶层浮动状态栈 (Floating Overlay Stack, zIndex 顶层)
                                        WorkspaceFloatingOverlay(
                                            viewModel = viewModel,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .zIndex(10f)
                                                .padding(bottom = 6.dp)
                                        )

                                        ChatInputCard(
                                            viewModel = viewModel,
                                            modifier = Modifier.fillMaxWidth(),
                                            onOpenProjectPicker = onOpenProjectPicker,
                                            imeOverlayActive = wsOverlayActive,
                                            projectMenuExpanded = projectMenuExpanded,
                                            onProjectMenuExpandedChange = onProjectMenuExpandedChange
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 浮动概览窗 overlay（固定内容区右上角，非模态）
                WorkspaceAgentOverviewStack(
                    viewModel = viewModel,
                    rawVm = rawMessagesViewModel,
                    state = overviewState,
                    maxHeight = maxHeight,
                    maxWidth = maxWidth,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
                } // end Box（中央主工作区）

                // 桌面端：独立功能面板与常驻 Dock 栏
                if (!isCompact) {
                    RightExtensionPanel(
                        isOpen = isRightPanelOpen,
                        onClose = { viewModel.closeDockPanel() },
                        viewModel = viewModel,
                        rawMessagesViewModel = rawMessagesViewModel,
                        terminalViewModel = terminalViewModel,
                        isCompact = false,
                        screenWidth = screenWidth,
                        maxPanelWidth = maxPanelWidth
                    )

                    RightDock(
                        activePanel = viewModel.activeDockPanel,
                        onSelectPanel = { panel -> viewModel.toggleDockPanel(panel) },
                        hasRunningSubagents = viewModel.hasRunningSubagents
                    )

                    // 子代理对话面板（与 Dock 互斥；expandHorizontally 从右边缘展开）
                    AnimatedVisibility(
                        visible = viewModel.activeSubagentPanel != null,
                        enter = if (LocalWorkspaceMotion.current.reducedMotion) EnterTransition.None else fadeIn(LocalWorkspaceMotion.current.panelSpec()) +
                            expandHorizontally(
                                animationSpec = LocalWorkspaceMotion.current.panelSpec(),
                                expandFrom = Alignment.End,
                            ),
                        exit = if (LocalWorkspaceMotion.current.reducedMotion) ExitTransition.None else fadeOut(LocalWorkspaceMotion.current.panelSpec()) +
                            shrinkHorizontally(
                                animationSpec = LocalWorkspaceMotion.current.panelSpec(),
                                shrinkTowards = Alignment.End,
                            ),
                    ) {
                        SubagentConversationPanel(
                            viewModel = viewModel,
                            onDismiss = { viewModel.closeSubagentPanel() },
                            modifier = Modifier
                                .fillMaxHeight()
                                .width((screenWidth.value * (2f / 3f)).coerceAtLeast(340f).dp)
                        )
                    }
                }
            }

            // 移动端：右侧扩展面板右滑抽屉（Right Modal Drawer）+ 半透明背景遮罩
            if (isCompact) {
                // 半透明背景遮罩
                androidx.compose.animation.AnimatedVisibility(
                    visible = isRightPanelOpen,
                    enter = if (LocalWorkspaceMotion.current.reducedMotion) EnterTransition.None else fadeIn(LocalWorkspaceMotion.current.panelSpec()),
                    exit = if (LocalWorkspaceMotion.current.reducedMotion) ExitTransition.None else fadeOut(LocalWorkspaceMotion.current.panelSpec())
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(colors.surfaceOverlay)
                            .clickable { viewModel.closeDockPanel() }
                    )
                }

                // 右侧滑出抽屉
                androidx.compose.animation.AnimatedVisibility(
                    visible = isRightPanelOpen,
                    enter = if (LocalWorkspaceMotion.current.reducedMotion) EnterTransition.None else slideInHorizontally(animationSpec = LocalWorkspaceMotion.current.panelSpec()) { it } + fadeIn(LocalWorkspaceMotion.current.panelSpec()),
                    exit = if (LocalWorkspaceMotion.current.reducedMotion) ExitTransition.None else slideOutHorizontally(animationSpec = LocalWorkspaceMotion.current.panelSpec()) { it } + fadeOut(LocalWorkspaceMotion.current.panelSpec()),
                    modifier = Modifier.align(Alignment.CenterEnd)
                ) {
                    RightExtensionPanel(
                        isOpen = isRightPanelOpen,
                        onClose = { viewModel.closeDockPanel() },
                        viewModel = viewModel,
                        rawMessagesViewModel = rawMessagesViewModel,
                        terminalViewModel = terminalViewModel,
                        isCompact = true,
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(minOf(360.dp, screenWidth * 0.85f))
                    )
                }

                // 子代理面板移动端抽屉（与 Dock 抽屉互斥）
                androidx.compose.animation.AnimatedVisibility(
                    visible = viewModel.activeSubagentPanel != null,
                    enter = if (LocalWorkspaceMotion.current.reducedMotion) EnterTransition.None else fadeIn(LocalWorkspaceMotion.current.panelSpec()),
                    exit = if (LocalWorkspaceMotion.current.reducedMotion) ExitTransition.None else fadeOut(LocalWorkspaceMotion.current.panelSpec())
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(colors.surfaceOverlay)
                            .clickable { viewModel.closeSubagentPanel() }
                    )
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = viewModel.activeSubagentPanel != null,
                    enter = if (LocalWorkspaceMotion.current.reducedMotion) EnterTransition.None else slideInHorizontally(animationSpec = LocalWorkspaceMotion.current.panelSpec()) { it } + fadeIn(LocalWorkspaceMotion.current.panelSpec()),
                    exit = if (LocalWorkspaceMotion.current.reducedMotion) ExitTransition.None else slideOutHorizontally(animationSpec = LocalWorkspaceMotion.current.panelSpec()) { it } + fadeOut(LocalWorkspaceMotion.current.panelSpec()),
                    modifier = Modifier.align(Alignment.CenterEnd)
                ) {
                    SubagentConversationPanel(
                        viewModel = viewModel,
                        onDismiss = { viewModel.closeSubagentPanel() },
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(minOf(360.dp, screenWidth * 0.85f))
                    )
                }
            }
        }

        val processStats by appState.processStats.collectAsState()

        // 底部吸底系统信息栏（纯 CPU/内存资源监控；错误统一走 ErrorBoard）
        SystemInfoBar(
            stats = processStats
        )

        // 详细错误报告弹窗
        val feedbackState = viewModel.feedbackState
        if (feedbackState.isDetailOpen) {
            ErrorDetailDialog(
                errorSummary = feedbackState.message?.let { stringResource(it.key, *it.args.toTypedArray()) }.orEmpty(),
                errorDiagnostic = feedbackState.diagnostic.orEmpty(),
                onDismiss = viewModel::dismissErrorDetail
            )
        }

        // 文件/目录打开确认框（openFile 路由落在 EXTERNAL / REVEAL / DIRECTORY 档时弹出）
        viewModel.pendingFileOpen?.let { pending ->
            val fileName = pending.path.substringAfterLast('/').ifBlank { pending.path }
            val message = when (pending) {
                is PendingFileOpen.OpenExternally -> stringResource(Res.string.file_open_confirm_with_app, pending.appName)
                is PendingFileOpen.RevealInFolder -> stringResource(Res.string.file_open_confirm_reveal)
                is PendingFileOpen.OpenDirectory -> stringResource(Res.string.file_open_confirm_directory)
            }
            ConfirmDialog(
                title = fileName,
                message = message,
                confirmLabel = stringResource(Res.string.file_open_open),
                cancelLabel = stringResource(Res.string.cancel),
                onConfirm = { viewModel.confirmPendingFileOpen() },
                onDismiss = { viewModel.cancelPendingFileOpen() },
            )
        }

        // ── 工作区面板对话框（Handoff / 管理 / 归档列表 / 恢复，RF-08）──
        // 唯一状态源 = viewModel.dialogState；各面板均为可滚动内容，动作直连真实 VM → AiCore。
        WorkspaceDialogs(viewModel)
    }
}

/**
 * 工作区面板对话框区（Handoff / 管理 / 归档列表 / 恢复 / 宿主设置 / 建分支 / 环境编辑）。
 * 从 Workspace 路由提取：唯一状态源 = viewModel.dialogState / environmentEditorState，
 * 动作直连真实 VM → AiCore。路由与集成测试共用同一接线（单一事实源）。
 */
@Composable
fun WorkspaceDialogs(viewModel: WorkspaceViewModel) {
    val dialogState by viewModel.dialogState.collectAsState()
    when (val dialog = dialogState) {
        WorkspaceViewModel.WorkspaceDialogState.None -> Unit

        is WorkspaceViewModel.WorkspaceDialogState.Handoff -> {
            Dialog(onDismissRequest = viewModel::dismissDialog) {
                val preview = dialog.preview
                if (preview != null) {
                    HandoffPanelContent(
                        preview = preview,
                        stopAffectedTasks = dialog.stopAffected,
                        loading = dialog.loading,
                        errorMessage = dialog.errorMessage,
                        onToggleStopAffectedTasks = viewModel::setStopAffectedTasks,
                        onExecuteHandoff = viewModel::executeHandoff,
                        onRefreshPreview = viewModel::openHandoffDialog,
                        onDismiss = viewModel::dismissDialog
                    )
                } else {
                    WorkspaceDialogErrorContent(
                        title = stringResource(Res.string.handoff_dialog_title),
                        errorMessage = dialog.errorMessage.orEmpty(),
                        retryLabel = stringResource(Res.string.workspace_dialog_retry),
                        onRetry = viewModel::openHandoffDialog,
                        onDismiss = viewModel::dismissDialog
                    )
                }
            }
        }

        is WorkspaceViewModel.WorkspaceDialogState.Management -> {
            Dialog(onDismissRequest = viewModel::dismissDialog) {
                WorkspaceManagementPanelContent(
                    worktrees = dialog.workspaces,
                    errorMessage = dialog.errorMessage,
                    onEnterSession = viewModel::selectConversation,
                    onArchiveWorkspace = viewModel::archiveWorkspace,
                    onDismiss = viewModel::dismissDialog
                )
            }
        }

        is WorkspaceViewModel.WorkspaceDialogState.ArchiveList -> {
            Dialog(onDismissRequest = viewModel::dismissDialog) {
                WorkspaceArchivedListContent(
                    archivedConversations = dialog.archived,
                    errorMessage = dialog.errorMessage,
                    onRestore = viewModel::restoreArchivedConversation,
                    onDismiss = viewModel::dismissDialog
                )
            }
        }

        is WorkspaceViewModel.WorkspaceDialogState.Recovery -> {
            Dialog(onDismissRequest = viewModel::dismissDialog) {
                val preview = dialog.preview
                if (preview != null) {
                    WorkspaceRecoveryPanelContent(
                        sourcePath = preview.sourceWorkspace?.workspace?.checkoutRoot.orEmpty().ifBlank { "(unknown)" },
                        targetPath = preview.targetWorkspace?.workspace?.checkoutRoot.orEmpty().ifBlank { "(new managed)" },
                        loading = dialog.loading,
                        errorMessage = dialog.errorMessage,
                        onKeepCurrent = { viewModel.executeRecovery(RecoveryStrategy.KEEP_VERIFIED_CURRENT) },
                        onRestoreToNew = { viewModel.executeRecovery(RecoveryStrategy.RESTORE_TO_NEW_MANAGED) }
                    )
                } else {
                    WorkspaceDialogErrorContent(
                        title = stringResource(Res.string.workspace_recovery_title),
                        errorMessage = dialog.errorMessage.orEmpty(),
                        retryLabel = stringResource(Res.string.workspace_dialog_retry),
                        onRetry = { viewModel.previewRecovery(dialog.operationId) },
                        onDismiss = viewModel::dismissDialog
                    )
                }
            }
        }

        is WorkspaceViewModel.WorkspaceDialogState.WorktreeSettings -> {
            Dialog(onDismissRequest = viewModel::dismissDialog) {
                WorktreeSettingsPanelContent(
                    rootPath = dialog.rootPath,
                    max = dialog.max,
                    autoCleanup = dialog.autoCleanup,
                    errorMessage = dialog.errorMessage,
                    onSave = viewModel::updateWorktreeSettings,
                    onDismiss = viewModel::dismissDialog
                )
            }
        }

        is WorkspaceViewModel.WorkspaceDialogState.CreateBranch -> {
            Dialog(onDismissRequest = viewModel::dismissDialog) {
                CreateBranchPanelContent(
                    errorMessage = dialog.errorMessage,
                    onCreate = { name ->
                        val generation = viewModel.snapshot?.workspaceInfo?.active?.workspace?.generation ?: 0L
                        viewModel.createWorkspaceBranch(name, generation)
                    },
                    onDismiss = viewModel::dismissDialog
                )
            }
        }
    }

    // ── 环境编辑面板（独立于 dialogState，由 environmentEditorState 驱动）──
    val environmentEditorState by viewModel.environmentEditorState.collectAsState()
    if (environmentEditorState != WorkspaceViewModel.EnvironmentEditorState.None) {
        Dialog(onDismissRequest = viewModel::dismissEnvironmentEditor) {
            EnvironmentEditorPanelContent(
                state = environmentEditorState,
                onSave = viewModel::saveEnvironment,
                onRunAction = viewModel::runEnvironmentAction,
                onDismiss = viewModel::dismissEnvironmentEditor
            )
        }
    }
}

@Composable
private fun MessageList(
    viewModel: WorkspaceViewModel,
    modifier: Modifier = Modifier,
    isCompact: Boolean = false,
    contentMaxWidth: Dp = ChatLayout.contentMaxWidth,
) {
    // 切会话使用独立列表状态；reverseLayout 的 index 0 即内容底部。
    val lazyListState = key(viewModel.conversationId) { rememberLazyListState() }

    // 展平后的聊天列表 item 由 WorkspaceViewModel 计算
    val chatItems = viewModel.chatItems

    // 选区菜单 actions——在 item 外部 remember，避免每个 item 都重建
    // （stringResource 必须在组合上下文取值后传入：remember lambda 与操作用户回调都不是 @Composable）
    val addToInputLabel = stringResource(Res.string.ws_add_to_input)
    val copyLabel = stringResource(Res.string.copy)
    val searchLabel = stringResource(Res.string.ws_search)
    val pasteLabel = stringResource(Res.string.ws_paste_to_input)
    val clipboard = LocalClipboard.current
    val clipboardScope = rememberCoroutineScope()
    val selectionMenuActions = remember(
        viewModel, addToInputLabel, copyLabel, searchLabel, pasteLabel, clipboard, clipboardScope,
    ) {
        listOf(
            SelectionMenuAction.Custom(copyLabel, onClick = { text ->
                clipboard.copyText(clipboardScope, text)
            }),
            SelectionMenuAction.Custom(searchLabel, onClick = { text ->
                viewModel.openLink("https://www.google.com/search?q=" + encodeUrlQuery(text))
            }),
            SelectionMenuAction.Custom(pasteLabel, onClick = { _ ->
                clipboardScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    val clip = PlatformClipboard.getTextAsync()
                        ?: readPlainTextFromClip(clipboard.getClipEntry())
                    if (!clip.isNullOrEmpty()) viewModel.appendToInput(clip)
                }
            }),
            SelectionMenuAction.Custom(addToInputLabel, onClick = { text ->
                viewModel.appendToInput(text)
            }),
        )
    }

    // 仅用户发送消息时主动回到底部。内容增高、展开和追加步骤由底部锚定布局处理，
    // 不再监听 layoutInfo 后二次滚动，也不把布局导致的位置变化误判成用户上翻。
    val optimisticMessageId = viewModel.optimisticUserMessage?.id
    LaunchedEffect(viewModel.conversationId, optimisticMessageId) {
        if (optimisticMessageId != null) lazyListState.requestScrollToItem(0)
    }

    // 渲染：复用可组合的 ChatListContent（主对话消息列表；LazyColumn + item 分发主体在 ui/chat/ChatListContent.kt）
    ChatListContent(
        viewModel = viewModel,
        chatItems = chatItems,
        lazyListState = lazyListState,
        selectionMenuActions = selectionMenuActions,
        modifier = modifier,
        contentMaxWidth = contentMaxWidth,
        isCompact = isCompact,
        showUserMessageActions = true,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AssistantImagesView(
    images: List<String>,
    viewModel: WorkspaceViewModel,
    colors: xyz.mederi.theme.MederiColors,
    modifier: Modifier = Modifier
) {
    FlowRow(
        modifier = modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        images.forEachIndexed { imgIdx, imgUrl ->
            val generatedTitle = stringResource(Res.string.ws_generated_image_n, imgIdx + 1)
            val generatedDescription = stringResource(Res.string.ws_ai_image_n, imgIdx + 1)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.surfaceCard)
                    .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(8.dp))
                    .clickable {
                        viewModel.openDocument(
                            DocumentTarget.Image(
                                title = generatedTitle,
                                url = imgUrl
                            )
                        )
                    }
            ) {
                InkImage(
                    model = imgUrl,
                    contentDescription = generatedDescription,
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier
                        .widthIn(min = 100.dp, max = 360.dp)
                        .heightIn(min = 80.dp, max = 260.dp)
                )
            }
        }
    }
}
