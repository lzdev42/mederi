package xyz.mederi.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.sidebar_automation
import mederi.app.shared.generated.resources.sidebar_cancel
import mederi.app.shared.generated.resources.sidebar_confirm_delete
import mederi.app.shared.generated.resources.sidebar_confirm_rename
import mederi.app.shared.generated.resources.sidebar_conversation_menu
import mederi.app.shared.generated.resources.sidebar_delete_conversation
import mederi.app.shared.generated.resources.sidebar_delete_conversation_message
import mederi.app.shared.generated.resources.sidebar_delete_conversation_title
import mederi.app.shared.generated.resources.sidebar_delete_project
import mederi.app.shared.generated.resources.sidebar_delete_project_message
import mederi.app.shared.generated.resources.sidebar_delete_project_title
import mederi.app.shared.generated.resources.sidebar_new_conversation
import mederi.app.shared.generated.resources.sidebar_new_task
import mederi.app.shared.generated.resources.sidebar_open_project_directory
import mederi.app.shared.generated.resources.sidebar_plugin_market
import mederi.app.shared.generated.resources.sidebar_project_menu
import mederi.app.shared.generated.resources.language_system
import mederi.app.shared.generated.resources.sidebar_edge_handle
import mederi.app.shared.generated.resources.sidebar_language
import mederi.app.shared.generated.resources.sidebar_pin
import mederi.app.shared.generated.resources.sidebar_projects
import mederi.app.shared.generated.resources.sidebar_rename_conversation
import mederi.app.shared.generated.resources.sidebar_rename_project
import mederi.app.shared.generated.resources.sidebar_settings
import mederi.app.shared.generated.resources.sidebar_toggle_theme
import mederi.app.shared.generated.resources.sidebar_unpin
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.AppInfo
import xyz.mederi.core.contract.models.Conversation
import xyz.mederi.core.contract.models.ConversationStatus
import xyz.mederi.core.contract.models.Project
import xyz.mederi.core.ui.DebugLog
import xyz.mederi.core.ui.SidebarViewModel
import xyz.mederi.core.ui.appstate.LocalAppState
import xyz.mederi.theme.AppLanguage
import xyz.mederi.theme.AppThemeMode
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiColors

class SidebarInteractionState {
    var activeCount by mutableStateOf(0)
        private set

    fun onOpen() {
        activeCount++
    }

    fun onClose() {
        if (activeCount > 0) activeCount--
    }

    val isInteracting: Boolean get() = activeCount > 0
}

val LocalSidebarInteractionState = staticCompositionLocalOf { SidebarInteractionState() }

/**
 * 侧边栏。状态与动作统一经 [SidebarViewModel]（内部转发 AppState 全局真理源），
 * 仅布局导航类副作用（收起抽屉/打开设置）以回调形式上抛。
 */
@Composable
fun Sidebar(
    modifier: Modifier = Modifier,
    viewModel: SidebarViewModel,
    isCompact: Boolean = false,
    isDrawer: Boolean = false,
    isPinned: Boolean = false,
    onTogglePin: (() -> Unit)? = null,
    onActiveInteractionChange: (Boolean) -> Unit = {},
    onRequestClose: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenProjectPicker: () -> Unit = {},
) {
    val appState = LocalAppState.current
    val colors = LocalMederiColors.current
    val projects by viewModel.filteredProjects.collectAsState()
    val selectedProjectId by appState.selectedProjectId.collectAsState()
    val selectedConversationId by appState.selectedConversationId.collectAsState()
    val theme by appState.theme.collectAsState()

    val interactionState = remember { SidebarInteractionState() }

    LaunchedEffect(interactionState.activeCount) {
        val interacting = interactionState.isInteracting
        DebugLog.info("SidebarHover", "Sidebar interaction count: ${interactionState.activeCount}, isInteracting=$interacting")
        onActiveInteractionChange(interacting)
    }

    // 抽屉模式下，改变会话/项目选择的操作同时收起抽屉（桌面常驻侧栏不收起）
    val navigate: () -> Unit = { if (isDrawer) onRequestClose() }

    CompositionLocalProvider(LocalSidebarInteractionState provides interactionState) {
        Column(
            modifier = modifier
                .fillMaxHeight()
                .then(if (isCompact) Modifier.fillMaxWidth() else Modifier.width(260.dp))
                .background(colors.surfaceSidebar)
                .padding(12.dp)
        ) {
        // Top Toolbar (40dp 高度对齐全屏顶栏线条)
        if (isCompact) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)).background(colors.accentPrimary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(FeatherIcons.Cpu, null, tint = colors.accentPrimary, modifier = Modifier.size(13.dp))
                    }
                    Text("Mederi", color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                SidebarIconButton(imageVector = FeatherIcons.X, colors = colors, onClick = onRequestClose)
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SidebarIconButton(
                        imageVector = FeatherIcons.Sidebar,
                        contentDescription = stringResource(if (isPinned) Res.string.sidebar_unpin else Res.string.sidebar_pin),
                        colors = colors,
                        onClick = onRequestClose
                    )
                    SidebarIconButton(
                        imageVector = FeatherIcons.Search,
                        colors = colors
                    )
                }

                if (onTogglePin != null) {
                    SidebarIconButton(
                        imageVector = if (isPinned) FeatherIcons.Columns else FeatherIcons.Sidebar,
                        contentDescription = stringResource(if (isPinned) Res.string.sidebar_unpin else Res.string.sidebar_pin),
                        colors = colors,
                        active = isPinned,
                        onClick = onTogglePin
                    )
                }
            }
        }

        // 顶层三大固定菜单 (新建任务、插件市场、自动化 - 对齐图 2)
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SidebarMenuItem(
                icon = FeatherIcons.MessageSquare,
                title = stringResource(Res.string.sidebar_new_task),
                isSelected = false,
                colors = colors,
                onClick = {
                    viewModel.newSession()
                    navigate()
                }
            )
            SidebarMenuItem(
                icon = FeatherIcons.Grid,
                title = stringResource(Res.string.sidebar_plugin_market),
                isSelected = false,
                colors = colors
            )
            SidebarMenuItem(
                icon = FeatherIcons.Clock,
                title = stringResource(Res.string.sidebar_automation),
                isSelected = false,
                colors = colors
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 项目分组 Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(Res.string.sidebar_projects),
                color = colors.textSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onOpenProjectPicker() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = FeatherIcons.Plus,
                    contentDescription = stringResource(Res.string.sidebar_open_project_directory),
                    tint = colors.textSecondary,
                    modifier = Modifier.size(13.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // 项目层级树与会话列表（已由 SidebarViewModel.filteredProjects 提供）
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            projects.forEach { project ->
                ProjectTreeRow(
                    project = project,
                    viewModel = viewModel,
                    colors = colors,
                    navigate = navigate
                )
            }
        }

        HorizontalDivider(
            color = colors.divider,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        // Sidebar Footer (两行布局：设置 + 主题切换，全称版本号)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onOpenSettings() }
                        .padding(vertical = 4.dp, horizontal = 4.dp)
                ) {
                    Icon(
                        imageVector = FeatherIcons.Settings,
                        contentDescription = stringResource(Res.string.sidebar_settings),
                        tint = colors.textSecondary,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(Res.string.sidebar_settings),
                        color = colors.textSecondary,
                        fontSize = 12.sp
                    )
                }

                // 右侧：多语言切换与主题切换（对应红框位置）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // 多语言切换按钮与下拉菜单
                    val interaction = LocalSidebarInteractionState.current
                    var showLanguageMenu by remember { mutableStateOf(false) }
                    val currentLanguage by appState.language.collectAsState()

                    DisposableEffect(showLanguageMenu) {
                        if (showLanguageMenu) {
                            interaction.onOpen()
                            onDispose { interaction.onClose() }
                        } else onDispose {}
                    }

                    Box {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(colors.surfaceCard)
                                .clickable {
                                    DebugLog.info("SidebarHover", "Language menu opened: showLanguageMenu = true")
                                    showLanguageMenu = true
                                }
                                .padding(5.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = FeatherIcons.Globe,
                                contentDescription = stringResource(Res.string.sidebar_language),
                                tint = colors.textSecondary,
                                modifier = Modifier.size(15.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = showLanguageMenu,
                            onDismissRequest = {
                                DebugLog.info("SidebarHover", "Language menu dismissed: showLanguageMenu = false")
                                showLanguageMenu = false
                            },
                            modifier = Modifier.background(colors.surfaceCard)
                        ) {
                            AppLanguage.entries.forEach { lang ->
                                val isSelected = currentLanguage == lang
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = if (lang == AppLanguage.SYSTEM) stringResource(Res.string.language_system) else lang.nativeName,
                                                color = if (isSelected) colors.accentPrimary else colors.textPrimary,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                fontSize = 13.sp
                                            )
                                            if (isSelected) {
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Icon(
                                                    imageVector = FeatherIcons.Check,
                                                    contentDescription = null,
                                                    tint = colors.accentPrimary,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        appState.setLanguage(lang)
                                        showLanguageMenu = false
                                    }
                                )
                            }
                        }
                    }

                    // Theme Switch Button（主题写操作唯一通道：AppState）
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.surfaceCard)
                            .clickable {
                                appState.setTheme(if (theme.isDark) AppThemeMode.LIGHT else AppThemeMode.DARK)
                            }
                            .padding(5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (colors.isDark) FeatherIcons.Moon else FeatherIcons.Sun,
                            contentDescription = stringResource(Res.string.sidebar_toggle_theme),
                            tint = colors.accentPrimary,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 第二行：版本号
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = AppInfo.VERSION,
                    color = colors.textMuted,
                    fontSize = 10.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            }
        }
    }
}
}

@Composable
private fun SidebarMenuItem(
    icon: ImageVector,
    title: String,
    isSelected: Boolean,
    colors: MederiColors,
    onClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (isSelected) colors.surfaceCard else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isSelected) colors.accentPrimary else colors.iconMuted,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            color = if (isSelected) colors.textPrimary else colors.textSecondary,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
        )
    }
}

@Composable
private fun SidebarIconButton(
    imageVector: ImageVector,
    colors: MederiColors,
    contentDescription: String? = null,
    active: Boolean = false,
    onClick: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) colors.accentPrimary.copy(alpha = 0.15f) else Color.Transparent)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = if (active) colors.accentPrimary else colors.textSecondary,
            modifier = Modifier.size(15.dp)
        )
    }
}

@Composable
private fun ProjectTreeRow(
    project: Project,
    viewModel: SidebarViewModel,
    colors: MederiColors,
    navigate: () -> Unit
) {
    val appState = LocalAppState.current
    val selectedProjectId by appState.selectedProjectId.collectAsState()
    val isExpanded = project.id in viewModel.uiState.expandedProjectIds
    val interaction = LocalSidebarInteractionState.current
    var isMenuExpanded by remember { mutableStateOf(false) }
    var isRenameOpen by remember { mutableStateOf(false) }
    var isDeleteOpen by remember { mutableStateOf(false) }

    DisposableEffect(isMenuExpanded) {
        if (isMenuExpanded) {
            interaction.onOpen()
            onDispose { interaction.onClose() }
        } else onDispose {}
    }
    DisposableEffect(isRenameOpen) {
        if (isRenameOpen) {
            interaction.onOpen()
            onDispose { interaction.onClose() }
        } else onDispose {}
    }
    DisposableEffect(isDeleteOpen) {
        if (isDeleteOpen) {
            interaction.onOpen()
            onDispose { interaction.onClose() }
        } else onDispose {}
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // 项目主行
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(if (isExpanded) colors.surfaceCard.copy(alpha = 0.5f) else Color.Transparent)
                .clickable { viewModel.toggleProjectExpanded(project.id) }
                .padding(horizontal = 6.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isExpanded) FeatherIcons.FolderMinus else FeatherIcons.Folder,
                    contentDescription = null,
                    tint = if (isExpanded) colors.accentPrimary else colors.textSecondary,
                    modifier = Modifier
                        .size(14.dp)
                        .padding(end = 6.dp)
                )
                Text(
                    text = project.name,
                    color = if (isExpanded) colors.textPrimary else colors.textSecondary,
                    fontSize = 12.sp,
                    fontWeight = if (isExpanded) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 右侧操作图标组 (+ 和 三竖点)
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // + 按钮：新建当前项目对话
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable {
                            appState.selectProject(project.id)
                            viewModel.createConversation(project.id)
                            navigate()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = FeatherIcons.Plus,
                        contentDescription = stringResource(Res.string.sidebar_new_conversation),
                        tint = colors.textSecondary,
                        modifier = Modifier.size(13.dp)
                    )
                }

                // 三竖点 按钮：项目管理菜单
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { isMenuExpanded = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = FeatherIcons.MoreVertical,
                        contentDescription = stringResource(Res.string.sidebar_project_menu),
                        tint = colors.textSecondary,
                        modifier = Modifier.size(13.dp)
                    )

                    DropdownMenu(
                        expanded = isMenuExpanded,
                        onDismissRequest = { isMenuExpanded = false },
                        containerColor = colors.surfaceSidebar,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(8.dp))
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.sidebar_new_conversation), fontSize = 12.sp, color = colors.textPrimary) },
                            leadingIcon = { Icon(FeatherIcons.Plus, null, tint = colors.accentPrimary, modifier = Modifier.size(14.dp)) },
                            onClick = {
                                isMenuExpanded = false
                                viewModel.createConversation(project.id)
                                navigate()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.sidebar_rename_project), fontSize = 12.sp, color = colors.textPrimary) },
                            leadingIcon = { Icon(FeatherIcons.Edit2, null, tint = colors.textSecondary, modifier = Modifier.size(14.dp)) },
                            onClick = {
                                isMenuExpanded = false
                                isRenameOpen = true
                            }
                        )
                        HorizontalDivider(color = colors.divider)
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.sidebar_delete_project), fontSize = 12.sp, color = colors.accentDanger) },
                            leadingIcon = { Icon(FeatherIcons.Trash2, null, tint = colors.accentDanger, modifier = Modifier.size(14.dp)) },
                            onClick = {
                                isMenuExpanded = false
                                isDeleteOpen = true
                            }
                        )
                    }
                }
            }
        }

        // 会话子列表
        if (isExpanded && project.conversations.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                project.conversations.forEach { conversation ->
                    ConversationTreeRow(
                        conversation = conversation,
                        viewModel = viewModel,
                        colors = colors,
                        navigate = navigate
                    )
                }
            }
        }
    }

    // 重命名项目弹窗
    if (isRenameOpen) {
        var newName by remember { mutableStateOf(project.name) }
        Dialog(onDismissRequest = { isRenameOpen = false }) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.surfaceSidebar,
                border = BorderStroke(1.dp, colors.surfaceCardBorder),
                modifier = Modifier.width(320.dp).padding(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(Res.string.sidebar_rename_project), color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { isRenameOpen = false }) { Text(stringResource(Res.string.sidebar_cancel), color = colors.textSecondary) }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = {
                            if (newName.isNotBlank()) {
                                viewModel.renameProject(project.id, newName.trim())
                            }
                            isRenameOpen = false
                        }) { Text(stringResource(Res.string.sidebar_confirm_rename)) }
                    }
                }
            }
        }
    }

    // 删除确认弹窗
    if (isDeleteOpen) {
        Dialog(onDismissRequest = { isDeleteOpen = false }) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.surfaceSidebar,
                border = BorderStroke(1.dp, colors.surfaceCardBorder),
                modifier = Modifier.width(320.dp).padding(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(Res.string.sidebar_delete_project_title), color = colors.accentDanger, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(Res.string.sidebar_delete_project_message, project.name), color = colors.textSecondary, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { isDeleteOpen = false }) { Text(stringResource(Res.string.sidebar_cancel), color = colors.textSecondary) }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            colors = ButtonDefaults.buttonColors(containerColor = colors.accentDanger),
                            onClick = {
                                viewModel.deleteProject(project.id)
                                isDeleteOpen = false
                            }
                        ) { Text(stringResource(Res.string.sidebar_confirm_delete), color = colors.onAccentPrimary) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationTreeRow(
    conversation: Conversation,
    viewModel: SidebarViewModel,
    colors: MederiColors,
    navigate: () -> Unit
) {
    val appState = LocalAppState.current
    val selectedConversationId by appState.selectedConversationId.collectAsState()
    val isSelected = conversation.id == selectedConversationId
    val interaction = LocalSidebarInteractionState.current
    var isMenuExpanded by remember { mutableStateOf(false) }
    var isRenameOpen by remember { mutableStateOf(false) }
    var isDeleteOpen by remember { mutableStateOf(false) }

    DisposableEffect(isMenuExpanded) {
        if (isMenuExpanded) {
            interaction.onOpen()
            onDispose { interaction.onClose() }
        } else onDispose {}
    }
    DisposableEffect(isRenameOpen) {
        if (isRenameOpen) {
            interaction.onOpen()
            onDispose { interaction.onClose() }
        } else onDispose {}
    }
    DisposableEffect(isDeleteOpen) {
        if (isDeleteOpen) {
            interaction.onOpen()
            onDispose { interaction.onClose() }
        } else onDispose {}
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (isSelected) colors.accentPrimary.copy(alpha = 0.12f) else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (isSelected) colors.accentPrimary.copy(alpha = 0.3f) else Color.Transparent,
                shape = RoundedCornerShape(6.dp)
            )
            .clickable {
                viewModel.selectConversation(conversation.id)
                navigate()
            }
            .padding(start = 8.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = conversation.title,
            color = if (isSelected) colors.textPrimary else colors.textSecondary,
            fontSize = 11.5.sp,
            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            ConversationStatusDot(
                status = conversation.status,
                colors = colors
            )

            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { isMenuExpanded = true },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = FeatherIcons.MoreVertical,
                    contentDescription = stringResource(Res.string.sidebar_conversation_menu),
                    tint = if (isSelected) colors.textSecondary else colors.textMuted,
                    modifier = Modifier.size(12.dp)
                )

                DropdownMenu(
                    expanded = isMenuExpanded,
                    onDismissRequest = { isMenuExpanded = false },
                    containerColor = colors.surfaceSidebar,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(8.dp))
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.sidebar_rename_conversation), fontSize = 12.sp, color = colors.textPrimary) },
                        leadingIcon = { Icon(FeatherIcons.Edit2, null, tint = colors.textSecondary, modifier = Modifier.size(14.dp)) },
                        onClick = {
                            isMenuExpanded = false
                            isRenameOpen = true
                        }
                    )
                    HorizontalDivider(color = colors.divider)
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.sidebar_delete_conversation), fontSize = 12.sp, color = colors.accentDanger) },
                        leadingIcon = { Icon(FeatherIcons.Trash2, null, tint = colors.accentDanger, modifier = Modifier.size(14.dp)) },
                        onClick = {
                            isMenuExpanded = false
                            isDeleteOpen = true
                        }
                    )
                }
            }
        }
    }

    // 重命名会话弹窗
    if (isRenameOpen) {
        var newTitle by remember { mutableStateOf(conversation.title) }
        Dialog(onDismissRequest = { isRenameOpen = false }) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.surfaceSidebar,
                border = BorderStroke(1.dp, colors.surfaceCardBorder),
                modifier = Modifier.width(320.dp).padding(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(Res.string.sidebar_rename_conversation), color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = newTitle,
                        onValueChange = { newTitle = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { isRenameOpen = false }) { Text(stringResource(Res.string.sidebar_cancel), color = colors.textSecondary) }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = {
                            if (newTitle.isNotBlank()) {
                                viewModel.renameConversation(conversation.id, newTitle.trim())
                            }
                            isRenameOpen = false
                        }) { Text(stringResource(Res.string.sidebar_confirm_rename)) }
                    }
                }
            }
        }
    }

    // 删除会话确认弹窗
    if (isDeleteOpen) {
        Dialog(onDismissRequest = { isDeleteOpen = false }) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.surfaceSidebar,
                border = BorderStroke(1.dp, colors.surfaceCardBorder),
                modifier = Modifier.width(320.dp).padding(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(Res.string.sidebar_delete_conversation_title), color = colors.accentDanger, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(Res.string.sidebar_delete_conversation_message, conversation.title), color = colors.textSecondary, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { isDeleteOpen = false }) { Text(stringResource(Res.string.sidebar_cancel), color = colors.textSecondary) }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            colors = ButtonDefaults.buttonColors(containerColor = colors.accentDanger),
                            onClick = {
                                viewModel.deleteConversation(conversation.id)
                                isDeleteOpen = false
                            }
                        ) { Text(stringResource(Res.string.sidebar_confirm_delete), color = colors.onAccentPrimary) }
                    }
                }
            }
        }
    }
}

/**
 * 侧边栏会话状态指示灯组件。
 * - [ConversationStatus.Working]：琥珀橙呼吸光晕动效
 * - [ConversationStatus.WaitingUser]：翡翠绿微脉冲波纹 (Beacon Ping)
 * - [ConversationStatus.Idle]：晴空蓝低噪声静态微圆点
 * - [ConversationStatus.Error]：玫瑰红静态警示点
 */
@Composable
private fun ConversationStatusDot(
    status: ConversationStatus,
    colors: MederiColors,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.size(16.dp),
        contentAlignment = Alignment.Center
    ) {
        when (status) {
            ConversationStatus.Working -> {
                val transition = rememberInfiniteTransition(label = "working_pulse")
                val alpha by transition.animateFloat(
                    initialValue = 0.4f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(800, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "working_alpha"
                )
                val scale by transition.animateFloat(
                    initialValue = 0.85f,
                    targetValue = 1.15f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(800, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "working_scale"
                )
                val amberColor = Color(0xFFF59E0B)
                // 外层柔光晕
                Box(
                    modifier = Modifier
                        .size(11.dp)
                        .graphicsLayer(scaleX = scale, scaleY = scale, alpha = alpha * 0.35f)
                        .clip(CircleShape)
                        .background(amberColor)
                )
                // 内核实心点
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .graphicsLayer(alpha = alpha)
                        .clip(CircleShape)
                        .background(amberColor)
                )
            }
            ConversationStatus.WaitingUser -> {
                val transition = rememberInfiniteTransition(label = "waiting_ping")
                val pingScale by transition.animateFloat(
                    initialValue = 0.9f,
                    targetValue = 2.1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1400, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "waiting_pingScale"
                )
                val pingAlpha by transition.animateFloat(
                    initialValue = 0.7f,
                    targetValue = 0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1400, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "waiting_pingAlpha"
                )
                val emeraldColor = Color(0xFF10B981)
                // 外层扩散波纹
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .graphicsLayer(scaleX = pingScale, scaleY = pingScale, alpha = pingAlpha)
                        .clip(CircleShape)
                        .background(emeraldColor)
                )
                // 内核实心点
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(emeraldColor)
                )
            }
            ConversationStatus.Idle -> {
                // 正常结束：晴空蓝静态低噪微圆点（5dp）
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF38BDF8).copy(alpha = 0.85f))
                )
            }
            ConversationStatus.Error -> {
                // 报错：玫瑰红静态警示点（6dp）
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFEF4444))
                )
            }
        }
    }
}
