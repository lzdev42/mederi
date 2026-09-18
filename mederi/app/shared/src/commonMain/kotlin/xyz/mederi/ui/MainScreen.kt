package xyz.mederi.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import xyz.mederi.core.ui.SidebarViewModel
import xyz.mederi.core.ui.WorkspaceViewModel
import xyz.mederi.core.ui.appstate.LocalAppState
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

    val filteredProjects by sidebarViewModel.filteredProjects.collectAsState()
    val selectedConversationId by appState.selectedConversationId.collectAsState()
    val isReady by appState.isReady.collectAsState()

    // 选中会话时自动展开其所属项目（覆盖程序化选择：自动创建会话、子代理跳转等，用户手点必在已展开项目内）
    LaunchedEffect(selectedConversationId, filteredProjects) {
        val convId = selectedConversationId ?: return@LaunchedEffect
        sidebarViewModel.ensureConversationVisible(convId)
    }

    val coroutineScope = rememberCoroutineScope()

    // 目录选择 → 创建项目（平台 IO 胶水，单一定义点）
    val openProjectPicker: () -> Unit = {
        coroutineScope.launch {
            pickDirectory()?.let { sidebarViewModel.createProjectFromDirectory(it) }
        }
    }

    val isInitializing = !isReady
    val statusText = if (!isReady) "正在初始化..." else "已就绪"

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenWidth = maxWidth
        val isCompact = screenWidth < 768.dp

        // 移动端抽屉默认关闭（false），桌面端侧边栏默认展开（true）
        var isLeftSidebarOpen by remember(isCompact) { mutableStateOf(!isCompact) }

        if (isCompact) {
            // ==========================================
            // 移动端布局 (Mobile: Fullscreen Workspace + Overlay Drawer)
            // ==========================================
            Box(modifier = Modifier.fillMaxSize()) {
                // 主工作区占满全屏
                Workspace(
                    modifier = Modifier.fillMaxSize(),
                    viewModel = workspaceViewModel,
                    isCompact = true,
                    isLeftSidebarOpen = isLeftSidebarOpen,
                    onToggleLeftSidebar = { isLeftSidebarOpen = !isLeftSidebarOpen },
                    onOpenProjectPicker = openProjectPicker,
                    onOpenSettings = { isSettingsVisible = true }
                )

                // 侧边栏抽屉半透明背景遮罩
                AnimatedVisibility(
                    visible = isLeftSidebarOpen,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(colors.surfaceOverlay)
                            .clickable { isLeftSidebarOpen = false }
                    )
                }

                // 左侧滑出抽屉
                AnimatedVisibility(
                    visible = isLeftSidebarOpen,
                    enter = slideInHorizontally(animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy)) { -it } + fadeIn(),
                    exit = slideOutHorizontally(animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy)) { -it } + fadeOut()
                ) {
                    Sidebar(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(minOf(300.dp, screenWidth * 0.85f)),
                        viewModel = sidebarViewModel,
                        isCompact = true,
                        isDrawer = true,
                        onRequestClose = { isLeftSidebarOpen = false },
                        onOpenSettings = {
                            isSettingsVisible = true
                            isLeftSidebarOpen = false
                        },
                        onOpenProjectPicker = openProjectPicker
                    )
                }
            }
        } else {
            // ==========================================
            // 桌面端布局 (Desktop: Side-by-side)
            // ==========================================
            // 容器底层必须上色（surfaceSidebar）：侧边栏开合动画期间透明底层会露出窗口白底
            Row(modifier = Modifier.fillMaxSize().background(colors.surfaceSidebar)) {
                AnimatedVisibility(
                    visible = isLeftSidebarOpen,
                    // 左侧边栏从左边缘展开/收缩（expandFrom=Start）：默认居中展开会在动画期间两侧露出底层白底
                    enter = slideInHorizontally(animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy)) { -it } +
                            expandHorizontally(animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy), expandFrom = Alignment.Start) +
                            fadeIn(),
                    exit = slideOutHorizontally(animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy)) { -it } +
                            shrinkHorizontally(animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy), shrinkTowards = Alignment.Start) +
                            fadeOut()
                ) {
                    Sidebar(
                        viewModel = sidebarViewModel,
                        onRequestClose = { isLeftSidebarOpen = false },
                        onOpenSettings = { isSettingsVisible = true },
                        onOpenProjectPicker = openProjectPicker
                    )
                }
                Workspace(
                    modifier = Modifier.weight(1f),
                    viewModel = workspaceViewModel,
                    isCompact = false,
                    isLeftSidebarOpen = isLeftSidebarOpen,
                    onToggleLeftSidebar = { isLeftSidebarOpen = !isLeftSidebarOpen },
                    onOpenProjectPicker = openProjectPicker,
                    onOpenSettings = { isSettingsVisible = true }
                )
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
