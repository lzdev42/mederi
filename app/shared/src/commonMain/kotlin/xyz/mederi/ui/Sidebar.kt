package xyz.mederi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import mederi.app.shared.generated.resources.sidebar_projects
import mederi.app.shared.generated.resources.sidebar_rename_conversation
import mederi.app.shared.generated.resources.sidebar_rename_project
import mederi.app.shared.generated.resources.sidebar_settings
import mederi.app.shared.generated.resources.sidebar_toggle_theme
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.AppInfo
import xyz.mederi.core.contract.models.Conversation
import xyz.mederi.core.contract.models.Project
import xyz.mederi.ui.DebugLog
import xyz.mederi.ui.SidebarViewModel
import xyz.mederi.ui.appstate.LocalAppState
import xyz.mederi.theme.AppLanguage
import xyz.mederi.ui.components.ConversationStatusDot
import xyz.mederi.ui.components.atoms.ConfirmDialog
import xyz.mederi.ui.components.atoms.InputDialog
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
    val theme by viewModel.theme.collectAsState()

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
                verticalAlignment = Alignment.CenterVertically
            ) {
                SidebarIconButton(
                    imageVector = FeatherIcons.Search,
                    colors = colors
                )
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
                    val currentLanguage by viewModel.language.collectAsState()

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
                                        viewModel.setLanguage(lang)
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
                                val nextTheme = when (theme) {
                                    AppThemeMode.DARK -> AppThemeMode.LIGHT
                                    AppThemeMode.LIGHT -> AppThemeMode.DARK
                                }
                                viewModel.setTheme(nextTheme)
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
        InputDialog(
            title = stringResource(Res.string.sidebar_rename_project),
            initialValue = project.name,
            confirmLabel = stringResource(Res.string.sidebar_confirm_rename),
            cancelLabel = stringResource(Res.string.sidebar_cancel),
            onConfirm = {
                viewModel.renameProject(project.id, it.trim())
                isRenameOpen = false
            },
            onDismiss = { isRenameOpen = false },
        )
    }

    // 删除确认弹窗
    if (isDeleteOpen) {
        ConfirmDialog(
            title = stringResource(Res.string.sidebar_delete_project_title),
            message = stringResource(Res.string.sidebar_delete_project_message, project.name),
            confirmLabel = stringResource(Res.string.sidebar_confirm_delete),
            cancelLabel = stringResource(Res.string.sidebar_cancel),
            danger = true,
            titleColor = colors.accentDanger,
            onConfirm = {
                viewModel.deleteProject(project.id)
                isDeleteOpen = false
            },
            onDismiss = { isDeleteOpen = false },
        )
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
        InputDialog(
            title = stringResource(Res.string.sidebar_rename_conversation),
            initialValue = conversation.title,
            confirmLabel = stringResource(Res.string.sidebar_confirm_rename),
            cancelLabel = stringResource(Res.string.sidebar_cancel),
            onConfirm = {
                viewModel.renameConversation(conversation.id, it.trim())
                isRenameOpen = false
            },
            onDismiss = { isRenameOpen = false },
        )
    }

    // 删除会话确认弹窗
    if (isDeleteOpen) {
        ConfirmDialog(
            title = stringResource(Res.string.sidebar_delete_conversation_title),
            message = stringResource(Res.string.sidebar_delete_conversation_message, conversation.title),
            confirmLabel = stringResource(Res.string.sidebar_confirm_delete),
            cancelLabel = stringResource(Res.string.sidebar_cancel),
            danger = true,
            titleColor = colors.accentDanger,
            onConfirm = {
                viewModel.deleteConversation(conversation.id)
                isDeleteOpen = false
            },
            onDismiss = { isDeleteOpen = false },
        )
    }
}
