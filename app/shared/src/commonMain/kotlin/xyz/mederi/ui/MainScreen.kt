package xyz.mederi.ui

import xyz.mederi.theme.LocalWorkspaceMotion
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition

import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.Info
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.compaction_ok
import mederi.app.shared.generated.resources.compaction_skipped_low_tokens
import mederi.app.shared.generated.resources.compaction_skipped_title
import mederi.app.shared.generated.resources.compaction_skipped_too_few
import mederi.app.shared.generated.resources.main_initializing
import mederi.app.shared.generated.resources.main_ready
import mederi.app.shared.generated.resources.pick_directory_title
import mederi.app.shared.generated.resources.sidebar_edge_handle
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.ui.SidebarViewModel
import xyz.mederi.ui.RightDockPanel
import xyz.mederi.ui.WorkspaceViewModel
import xyz.mederi.ui.appstate.LocalAppState
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.theme.WorkspaceStyle
import xyz.mederi.ui.components.atoms.MederiDialog
import xyz.mederi.ui.components.atoms.WorkspaceActionButton
import xyz.mederi.ui.components.InitLoadingOverlay
import xyz.mederi.ui.settings.SettingsDialog
import xyz.mederi.ui.settings.ProjectSettingsPanel
import xyz.mederi.util.pickDirectory

@Composable
fun MainScreen() {
    val appState = LocalAppState.current
    val colors = LocalMederiColors.current

    // ViewModel 经 ViewModelStore 管理（窗口级生命周期），onCleared 协程清理可靠
    val sidebarViewModel: SidebarViewModel = viewModel { SidebarViewModel(appState) }
    val workspaceViewModel: WorkspaceViewModel = viewModel { WorkspaceViewModel(appState) }

    val effectState = rememberWorkspaceEffectState(workspaceViewModel.effects)
    val isSettingsVisible = effectState.settingsVisible || effectState.projectSettingsProjectId != null
    val compactionNotice = effectState.compactionNotice

    // 设置页打开时收回浏览器面板：JCEF 是原生重量级窗口（heavyweight），
    // 永远渲染在 Compose 轻量级内容之上，不收回会穿透覆盖设置对话框。
    LaunchedEffect(isSettingsVisible) {
        if (isSettingsVisible && workspaceViewModel.activeDockPanel == RightDockPanel.BROWSER) {
            workspaceViewModel.closeDockPanel()
        }
    }

    val filteredProjects by sidebarViewModel.filteredProjects.collectAsState()
    val selectedConversationId by appState.selectedConversationId.collectAsState()
    val isReady by appState.isReady.collectAsState()
    val isPinned by appState.leftSidebarPinned.collectAsState()
    val leftSidebarWidth by appState.leftSidebarWidth.collectAsState()

    // 选中会话时自动展开其所属项目（覆盖程序化选择：自动创建会话、子代理跳转等，用户手点必在已展开项目内）
    LaunchedEffect(selectedConversationId, filteredProjects) {
        val convId = selectedConversationId ?: return@LaunchedEffect
        sidebarViewModel.ensureConversationVisible(convId)
    }

    val coroutineScope = rememberCoroutineScope()
    val pickDirectoryTitle = stringResource(Res.string.pick_directory_title)

    // 目录选择 → 创建项目（平台 IO 胶水，单一定义点）
    val openProjectPicker: () -> Unit = {
        coroutineScope.launch {
            pickDirectory(pickDirectoryTitle)?.let { sidebarViewModel.createProjectFromDirectory(it) }
        }
    }

    val isInitializing = !isReady
    val statusText = if (!isReady) stringResource(Res.string.main_initializing) else stringResource(Res.string.main_ready)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenWidth = maxWidth
        val isCompact = screenWidth < 768.dp

        if (isCompact) {
            // ==========================================
            // 移动端布局 (Mobile: Fullscreen Workspace + Overlay Drawer)
            // ==========================================
            var isMobileDrawerOpen by remember(isCompact) { mutableStateOf(false) }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(isMobileDrawerOpen) {
                        if (!isMobileDrawerOpen) {
                            // 边缘右滑手势：从屏幕左侧（<= 40dp）向右滑出抽屉
                            detectHorizontalDragGestures { change, dragAmount ->
                                if (change.position.x <= 40.dp.toPx() && dragAmount > 15) {
                                    isMobileDrawerOpen = true
                                }
                            }
                        }
                    }
            ) {
                // 主工作区占满全屏
                Workspace(
                    modifier = Modifier.fillMaxSize(),
                    viewModel = workspaceViewModel,
                    isCompact = true,
                    isLeftSidebarOpen = isMobileDrawerOpen,
                    onToggleLeftSidebar = { isMobileDrawerOpen = !isMobileDrawerOpen },
                    onOpenProjectPicker = openProjectPicker,
                    imeOverlayActive = isSettingsVisible || compactionNotice != null,
                    projectMenuExpanded = effectState.projectMenuExpanded,
                    onProjectMenuExpandedChange = effectState::updateProjectMenuExpanded
                )

                // 侧边栏抽屉半透明背景遮罩
                AnimatedVisibility(
                    visible = isMobileDrawerOpen,
                    enter = if (LocalWorkspaceMotion.current.reducedMotion) EnterTransition.None else fadeIn(LocalWorkspaceMotion.current.panelSpec()),
                    exit = if (LocalWorkspaceMotion.current.reducedMotion) ExitTransition.None else fadeOut(LocalWorkspaceMotion.current.panelSpec())
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(colors.surfaceOverlay)
                            .clickable { isMobileDrawerOpen = false }
                    )
                }

                // 左侧滑出抽屉
                AnimatedVisibility(
                    visible = isMobileDrawerOpen,
                    enter = if (LocalWorkspaceMotion.current.reducedMotion) EnterTransition.None else slideInHorizontally(
                        animationSpec = LocalWorkspaceMotion.current.panelSpec()
                    ) { -it } + fadeIn(LocalWorkspaceMotion.current.panelSpec()),
                    exit = if (LocalWorkspaceMotion.current.reducedMotion) ExitTransition.None else slideOutHorizontally(
                        animationSpec = LocalWorkspaceMotion.current.panelSpec()
                    ) { -it } + fadeOut(LocalWorkspaceMotion.current.panelSpec())
                ) {
                    Sidebar(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(screenWidth * 0.75f),
                        viewModel = sidebarViewModel,
                        isCompact = true,
                        isDrawer = true,
                        onRequestClose = { isMobileDrawerOpen = false },
                        onOpenSettings = {
                            workspaceViewModel.openSettings()
                            isMobileDrawerOpen = false
                        },
                        onOpenProjectSettings = workspaceViewModel::openProjectSettings,
                        onOpenProjectPicker = openProjectPicker
                    )
                }

                // 屏幕左边缘未展开时的轻量把手指示器（点击呼出抽屉）
                SidebarEdgeIndicator(
                    isVisible = !isMobileDrawerOpen,
                    modifier = Modifier.align(Alignment.CenterStart),
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = { isMobileDrawerOpen = true }
                )
            }
        } else {
            // ==========================================
            // 桌面端布局 (Desktop: Side-by-side 常驻 OR 自动隐藏浮层)
            // ==========================================
            var isHoverRevealed by remember { mutableStateOf(false) }
            var isSidebarInteracting by remember { mutableStateOf(false) }

            // 自动隐藏抽屉唤出/收起：只有边缘条能唤出，抽屉自身 hover 只维持开启（纯逻辑见 SidebarReveal）
            val edgeInteraction = remember { MutableInteractionSource() }
            val isEdgeHovered by edgeInteraction.collectIsHoveredAsState()
            val drawerInteraction = remember { MutableInteractionSource() }
            val isDrawerHovered by drawerInteraction.collectIsHoveredAsState()
            var isExplicitlyClosed by remember { mutableStateOf(false) }

            fun closeDrawer() {
                isExplicitlyClosed = true
                isSidebarInteracting = false
                isHoverRevealed = false
            }

            // 显式关闭 latch 重新武装：重新贴边，或指针已离开抽屉且无菜单/弹窗交互
            LaunchedEffect(isEdgeHovered, isDrawerHovered, isSidebarInteracting) {
                if (isExplicitlyClosed && SidebarReveal.shouldRearm(isEdgeHovered, isDrawerHovered, isSidebarInteracting)) {
                    isExplicitlyClosed = false
                }
            }

            // 展开/收起：显式关闭立即收起；失去全部 hover/交互后宽限 HIDE_DELAY_MILLIS 再收起
            LaunchedEffect(isEdgeHovered, isDrawerHovered, isSidebarInteracting, isExplicitlyClosed) {
                val wantReveal = SidebarReveal.shouldReveal(isEdgeHovered, isDrawerHovered, isSidebarInteracting, isExplicitlyClosed)
                when {
                    wantReveal -> isHoverRevealed = true
                    isExplicitlyClosed -> isHoverRevealed = false
                    else -> {
                        delay(SidebarReveal.HIDE_DELAY_MILLIS)
                        isHoverRevealed = false
                    }
                }
            }

            if (isPinned) {
                // 常驻分栏模式 (Pinned: Side-by-side)
                Row(modifier = Modifier.fillMaxSize()) {
                    Sidebar(
                        viewModel = sidebarViewModel,
                        isCompact = false,
                        isDrawer = false,
                        isPinned = true,
                        onTogglePin = { appState.setLeftSidebarPinned(false) },
                        onRequestClose = { appState.setLeftSidebarPinned(false) },
                        onOpenSettings = { workspaceViewModel.openSettings() },
                        onOpenProjectSettings = workspaceViewModel::openProjectSettings,
                        onOpenProjectPicker = openProjectPicker
                    )
                    SidebarResizeHandle(
                        onDragDelta = { delta ->
                            appState.setLeftSidebarWidth(appState.leftSidebarWidth.value + delta.toInt())
                        }
                    )
                    Workspace(
                        modifier = Modifier.weight(1f),
                        viewModel = workspaceViewModel,
                        isCompact = false,
                        isLeftSidebarOpen = true,
                        onToggleLeftSidebar = { appState.setLeftSidebarPinned(false) },
                        onOpenProjectPicker = openProjectPicker,
                        imeOverlayActive = isSettingsVisible || compactionNotice != null,
                        projectMenuExpanded = effectState.projectMenuExpanded,
                        onProjectMenuExpandedChange = effectState::updateProjectMenuExpanded
                    )
                }
            } else {
                // 自动隐藏模式 (Auto-hide overlay with hover reveal & edge indicator)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                ) {
                    Workspace(
                        modifier = Modifier.fillMaxSize(),
                        viewModel = workspaceViewModel,
                        isCompact = false,
                        isLeftSidebarOpen = isHoverRevealed,
                        onToggleLeftSidebar = { appState.setLeftSidebarPinned(true) },
                        onOpenProjectPicker = openProjectPicker,
                        imeOverlayActive = isSettingsVisible || compactionNotice != null,
                        projectMenuExpanded = effectState.projectMenuExpanded,
                        onProjectMenuExpandedChange = effectState::updateProjectMenuExpanded
                    )

                    // 屏幕左边缘指示把手（鼠标移过去自动弹出来，未常驻时常驻底层渲染避免重挂载抖动）
                    SidebarEdgeIndicator(
                        isVisible = true,
                        isDrawerOpen = isHoverRevealed,
                        modifier = Modifier.align(Alignment.CenterStart),
                        interactionSource = edgeInteraction,
                        onClick = {
                            isExplicitlyClosed = false
                            isHoverRevealed = true
                        }
                    )

                    // 浮层抽屉展开时的点击外部遮罩（Click outside to dismiss）
                    if (isHoverRevealed) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    closeDrawer()
                                }
                        )
                    }

                    // 悬停滑出的浮层抽屉
                    AnimatedVisibility(
                        visible = isHoverRevealed,
                        enter = if (LocalWorkspaceMotion.current.reducedMotion) EnterTransition.None else slideInHorizontally(
                            animationSpec = LocalWorkspaceMotion.current.panelSpec()
                        ) { -it } + fadeIn(LocalWorkspaceMotion.current.panelSpec()),
                        exit = if (LocalWorkspaceMotion.current.reducedMotion) ExitTransition.None else slideOutHorizontally(
                            animationSpec = LocalWorkspaceMotion.current.panelSpec()
                        ) { -it } + fadeOut(LocalWorkspaceMotion.current.panelSpec())
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(leftSidebarWidth.dp)
                                .hoverable(drawerInteraction)
                                .background(colors.surfaceSidebar)
                                .border(BorderStroke(1.dp, colors.divider))
                        ) {
                            Sidebar(
                                viewModel = sidebarViewModel,
                                isCompact = false,
                                isDrawer = true,
                                isPinned = false,
                                onActiveInteractionChange = { isSidebarInteracting = it },
                                onTogglePin = {
                                    appState.setLeftSidebarPinned(true)
                                    isHoverRevealed = false
                                },
                                onRequestClose = { closeDrawer() },
                                onOpenSettings = {
                                    workspaceViewModel.openSettings()
                                    closeDrawer()
                                },
                                onOpenProjectSettings = { projectId -> workspaceViewModel.openProjectSettings(projectId); closeDrawer() },
                                onOpenProjectPicker = { openProjectPicker(); closeDrawer() }
                            )
                            SidebarResizeHandle(
                                onDragDelta = { delta ->
                                    appState.setLeftSidebarWidth(appState.leftSidebarWidth.value + delta.toInt())
                                },
                                modifier = Modifier.align(Alignment.CenterEnd)
                            )
                        }
                    }
                }
            }
        }

        InitLoadingOverlay(
            isVisible = isInitializing,
            statusText = statusText
        )

        SettingsDialog(
            isVisible = effectState.settingsVisible,
            onClose = { effectState.dismissSettings() }
        )

        effectState.projectSettingsProjectId?.let { projectId ->
            ProjectSettingsPanel(projectId, effectState::dismissProjectSettings)
        }

        // 手动压缩被跳过的一次性提示（"确定" 单按钮关闭）
        compactionNotice?.let { notice ->
            val title = stringResource(Res.string.compaction_skipped_title)
            val body = when (notice.reason) {
                "low_tokens" -> stringResource(Res.string.compaction_skipped_low_tokens)
                "too_few" -> stringResource(Res.string.compaction_skipped_too_few)
                // 未知 reason 兜底到最常见文案，不崩
                else -> stringResource(Res.string.compaction_skipped_low_tokens)
            }
            val okLabel = stringResource(Res.string.compaction_ok)
            MederiDialog(
                onDismiss = { effectState.dismissCompactionNotice() },
                width = 420.dp,
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MederiSpacing.SM),
                    ) {
                        Icon(
                            imageVector = FeatherIcons.Info,
                            contentDescription = null,
                            tint = colors.textSecondary,
                            modifier = Modifier.size(WorkspaceStyle.IconSize),
                        )
                        Text(title, color = colors.textPrimary, style = WorkspaceStyle.Title)
                    }
                },
            ) {
                Text(body, color = colors.textSecondary, style = WorkspaceStyle.Body)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    WorkspaceActionButton(
                        text = okLabel,
                        onClick = { effectState.dismissCompactionNotice() },
                        primary = true,
                    )
                }
            }
        }
    }
}

/**
 * 侧边栏隐藏时的屏幕边缘微光指示把手（Micro Tactile Handle）。
 * 参考 Linear / Zed 极简无感设计：
 * - 纯净贴边，无笨重突兀的凸出卡片或箭头；
 * - 平常（Idle）：3dp 宽、36dp 高的极简微胶囊细线，静若处子，零视觉噪音；
 * - 悬停（Hover）：平滑伸展至 56dp 高、4.5dp 宽，点亮为主题 accentPrimary 呼吸光条；
 * - 支持点击唤醒或贴边悬停唤醒。
 */
@Composable
private fun SidebarEdgeIndicator(
    isVisible: Boolean,
    modifier: Modifier = Modifier,
    isDrawerOpen: Boolean = false,
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit = {},
) {
    if (!isVisible) return

    val colors = LocalMederiColors.current
    val edgeHandleLabel = stringResource(Res.string.sidebar_edge_handle)
    val isHovered by interactionSource.collectIsHoveredAsState()
    val active = isHovered && !isDrawerOpen

    val animatedWidth by animateDpAsState(
        targetValue = if (active) 8.dp else 5.dp,
        animationSpec = LocalWorkspaceMotion.current.feedbackSpec()
    )
    val animatedHeight by animateDpAsState(
        targetValue = if (active) 100.dp else 72.dp,
        animationSpec = LocalWorkspaceMotion.current.feedbackSpec()
    )
    val indicatorColor by animateColorAsState(
        targetValue = if (active) colors.accentPrimary else colors.textMuted.copy(alpha = 0.45f),
        animationSpec = LocalWorkspaceMotion.current.feedbackSpec()
    )

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(24.dp)
            .then(if (!isDrawerOpen) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .semantics { contentDescription = edgeHandleLabel },
        contentAlignment = Alignment.CenterStart
    ) {
        if (!isDrawerOpen) {
            // 悬停呼吸微光晕
            if (active) {
                Box(
                    modifier = Modifier
                        .width(animatedWidth + 6.dp)
                        .height(animatedHeight + 12.dp)
                        .clip(RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp))
                        .background(colors.accentPrimary.copy(alpha = 0.2f))
                )
            }

            // 核心微胶囊指示条
            Box(
                modifier = Modifier
                    .width(animatedWidth)
                    .height(animatedHeight)
                    .clip(RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp))
                    .background(indicatorColor)
            )
        }
    }
}
