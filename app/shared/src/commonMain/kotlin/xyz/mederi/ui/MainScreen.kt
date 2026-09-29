package xyz.mederi.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.main_initializing
import mederi.app.shared.generated.resources.main_ready
import mederi.app.shared.generated.resources.pick_directory_title
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.ui.DebugLog
import xyz.mederi.ui.SidebarViewModel
import xyz.mederi.ui.UiEffect
import xyz.mederi.ui.WorkspaceViewModel
import xyz.mederi.ui.appstate.LocalAppState
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.ui.components.InitLoadingOverlay
import xyz.mederi.ui.settings.SettingsDialog
import xyz.mederi.util.pickDirectory

@Composable
fun MainScreen() {
    val appState = LocalAppState.current
    val colors = LocalMederiColors.current

    // ViewModel 经 ViewModelStore 管理（窗口级生命周期），onCleared 协程清理可靠
    val sidebarViewModel: SidebarViewModel = viewModel { SidebarViewModel(appState) }
    val workspaceViewModel: WorkspaceViewModel = viewModel { WorkspaceViewModel(appState) }

    var isSettingsVisible by remember { mutableStateOf(false) }

    // 一次性导航命令"打开设置"经 VM effects Channel 派发，这里 collect 后落为本地
    // isSettingsVisible=true（effect → state 宿主）；关闭设置对话框的 onRequestClose 仍直接
    // 置 false——关闭是纯本地 UI 动作，不走效果通道。
    LaunchedEffect(workspaceViewModel) {
        workspaceViewModel.effects.collect { effect ->
            when (effect) {
                is UiEffect.OpenSettings -> isSettingsVisible = true
                else -> {}
            }
        }
    }

    val filteredProjects by sidebarViewModel.filteredProjects.collectAsState()
    val selectedConversationId by appState.selectedConversationId.collectAsState()
    val isReady by appState.isReady.collectAsState()
    val isPinned by appState.leftSidebarPinned.collectAsState()

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
                    onOpenSettings = { workspaceViewModel.openSettings() }
                )

                // 侧边栏抽屉半透明背景遮罩
                AnimatedVisibility(
                    visible = isMobileDrawerOpen,
                    enter = fadeIn(),
                    exit = fadeOut()
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
                    enter = slideInHorizontally(
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy)
                    ) { -it } + fadeIn(),
                    exit = slideOutHorizontally(
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy)
                    ) { -it } + fadeOut()
                ) {
                    Sidebar(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(minOf(300.dp, screenWidth * 0.85f)),
                        viewModel = sidebarViewModel,
                        isCompact = true,
                        isDrawer = true,
                        onRequestClose = { isMobileDrawerOpen = false },
                        onOpenSettings = {
                            workspaceViewModel.openSettings()
                            isMobileDrawerOpen = false
                        },
                        onOpenProjectPicker = openProjectPicker
                    )
                }

                // 屏幕左边缘未展开时的轻量把手指示器（点击呼出抽屉）
                SidebarEdgeIndicator(
                    isVisible = !isMobileDrawerOpen,
                    modifier = Modifier.align(Alignment.CenterStart),
                    onClick = { isMobileDrawerOpen = true }
                )
            }
        } else {
            // ==========================================
            // 桌面端布局 (Desktop: Side-by-side 常驻 OR 自动隐藏浮层)
            // ==========================================
            var isHoverRevealed by remember { mutableStateOf(false) }
            var isMouseInsideSidebar by remember { mutableStateOf(false) }
            var isSidebarInteracting by remember { mutableStateOf(false) }
            var suppressHoverUntilExit by remember { mutableStateOf(false) }
            var hideJob by remember { mutableStateOf<Job?>(null) }

            fun showHoverSidebar(source: String) {
                if (suppressHoverUntilExit) {
                    DebugLog.info("SidebarHover", "showHoverSidebar ($source) SUPPRESSED: waiting for mouse to leave edge")
                    return
                }
                DebugLog.info("SidebarHover", "showHoverSidebar ($source): cancelling hideJob, isHoverRevealed=$isHoverRevealed")
                hideJob?.cancel()
                isMouseInsideSidebar = true
                isHoverRevealed = true
            }

            fun closeDrawer(source: String) {
                DebugLog.info("SidebarHover", "closeDrawer ($source): closing and setting suppressHoverUntilExit=true")
                hideJob?.cancel()
                isHoverRevealed = false
                isMouseInsideSidebar = false
                suppressHoverUntilExit = true
            }

            fun checkShouldRetract(source: String) {
                DebugLog.info("SidebarHover", "checkShouldRetract ($source): isMouseInside=$isMouseInsideSidebar, isInteracting=$isSidebarInteracting")
                hideJob?.cancel()
                if (!isMouseInsideSidebar && !isSidebarInteracting && isHoverRevealed) {
                    hideJob = coroutineScope.launch {
                        delay(350)
                        if (!isMouseInsideSidebar && !isSidebarInteracting) {
                            DebugLog.info("SidebarHover", "Retracting sidebar: both mouse and interaction are clear!")
                            isHoverRevealed = false
                        } else {
                            DebugLog.info("SidebarHover", "Retract cancelled: isMouseInside=$isMouseInsideSidebar, isInteracting=$isSidebarInteracting")
                        }
                    }
                }
            }

            fun onMouseLeftEdgeZone() {
                if (suppressHoverUntilExit) {
                    DebugLog.info("SidebarHover", "onMouseLeftEdgeZone: resetting suppressHoverUntilExit to false")
                    suppressHoverUntilExit = false
                }
            }

            LaunchedEffect(isSidebarInteracting) {
                if (!isSidebarInteracting && isHoverRevealed) {
                    checkShouldRetract("isSidebarInteractingBecameFalse")
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
                        onOpenProjectPicker = openProjectPicker
                    )
                    Workspace(
                        modifier = Modifier.weight(1f),
                        viewModel = workspaceViewModel,
                        isCompact = false,
                        isLeftSidebarOpen = true,
                        onToggleLeftSidebar = { appState.setLeftSidebarPinned(false) },
                        onOpenProjectPicker = openProjectPicker,
                        onOpenSettings = { workspaceViewModel.openSettings() }
                    )
                }
            } else {
                // 自动隐藏模式 (Auto-hide overlay with hover reveal & edge indicator)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pos = event.changes.firstOrNull()?.position
                                    if (pos != null && pos.x > 50.dp.toPx()) {
                                        onMouseLeftEdgeZone()
                                    }
                                }
                            }
                        }
                ) {
                    Workspace(
                        modifier = Modifier.fillMaxSize(),
                        viewModel = workspaceViewModel,
                        isCompact = false,
                        isLeftSidebarOpen = isHoverRevealed,
                        onToggleLeftSidebar = { appState.setLeftSidebarPinned(true) },
                        onOpenProjectPicker = openProjectPicker,
                        onOpenSettings = { workspaceViewModel.openSettings() }
                    )

                    // 屏幕左边缘指示把手（鼠标移过去自动弹出来，未常驻时常驻底层渲染避免重挂载抖动）
                    SidebarEdgeIndicator(
                        isVisible = !isPinned,
                        isDrawerOpen = isHoverRevealed,
                        modifier = Modifier.align(Alignment.CenterStart),
                        onClick = {
                            suppressHoverUntilExit = false
                            showHoverSidebar("EdgeIndicator:Click")
                        },
                        onHoverChange = { hovered ->
                            if (hovered) {
                                showHoverSidebar("EdgeIndicator:HoverEnter")
                            } else {
                                onMouseLeftEdgeZone()
                                if (!isHoverRevealed) {
                                    isMouseInsideSidebar = false
                                }
                            }
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
                                    closeDrawer("Scrim:Click")
                                }
                        )
                    }

                    // 悬停滑出的浮层抽屉
                    AnimatedVisibility(
                        visible = isHoverRevealed,
                        enter = slideInHorizontally(
                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy)
                        ) { -it } + fadeIn(),
                        exit = slideOutHorizontally(
                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy)
                        ) { -it } + fadeOut()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(245.dp)
                                .pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            when (event.type) {
                                                PointerEventType.Enter -> showHoverSidebar("SidebarBox:Enter")
                                                PointerEventType.Move -> showHoverSidebar("SidebarBox:Move")
                                                PointerEventType.Exit -> {
                                                    isMouseInsideSidebar = false
                                                    checkShouldRetract("SidebarBox:Exit")
                                                }
                                            }
                                        }
                                    }
                                }
                                .background(colors.surfaceSidebar)
                                .border(BorderStroke(1.dp, colors.divider))
                        ) {
                            Sidebar(
                                viewModel = sidebarViewModel,
                                isCompact = false,
                                isDrawer = true,
                                isPinned = false,
                                onActiveInteractionChange = { interacting ->
                                    isSidebarInteracting = interacting
                                    if (interacting) {
                                        hideJob?.cancel()
                                    }
                                },
                                onTogglePin = {
                                    appState.setLeftSidebarPinned(true)
                                    isHoverRevealed = false
                                },
                                onRequestClose = {
                                    closeDrawer("Drawer:onRequestClose")
                                },
                                onOpenSettings = {
                                    workspaceViewModel.openSettings()
                                    closeDrawer("Drawer:onOpenSettings")
                                },
                                onOpenProjectPicker = openProjectPicker
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
            isVisible = isSettingsVisible,
            onClose = { isSettingsVisible = false }
        )
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
    onClick: () -> Unit = {},
    onHoverChange: (Boolean) -> Unit = {}
) {
    if (!isVisible) return

    val colors = LocalMederiColors.current
    var isSelfHovered by remember { mutableStateOf(false) }
    val active = isSelfHovered && !isDrawerOpen

    val animatedWidth by animateDpAsState(
        targetValue = if (active) 8.dp else 5.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
    )
    val animatedHeight by animateDpAsState(
        targetValue = if (active) 100.dp else 72.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
    )
    val indicatorColor by animateColorAsState(
        targetValue = if (active) colors.accentPrimary else colors.textMuted.copy(alpha = 0.45f),
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
    )

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(24.dp)
            .then(if (!isDrawerOpen) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .pointerInput(isDrawerOpen) {
                if (!isDrawerOpen) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            when (event.type) {
                                PointerEventType.Enter, PointerEventType.Move -> {
                                    isSelfHovered = true
                                    onHoverChange(true)
                                }
                                PointerEventType.Exit -> {
                                    isSelfHovered = false
                                    onHoverChange(false)
                                }
                            }
                        }
                    }
                }
            }
            .then(if (!isDrawerOpen) Modifier.clickable { onClick() } else Modifier),
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
