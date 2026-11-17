package xyz.mederi.ui

import xyz.mederi.theme.LocalWorkspaceMotion

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.sidebar_cancel
import mederi.app.shared.generated.resources.sidebar_close
import mederi.app.shared.generated.resources.sidebar_confirm_delete
import mederi.app.shared.generated.resources.sidebar_confirm_rename
import mederi.app.shared.generated.resources.sidebar_conversation_menu
import mederi.app.shared.generated.resources.sidebar_delete_conversation
import mederi.app.shared.generated.resources.sidebar_delete_conversation_message
import mederi.app.shared.generated.resources.sidebar_delete_conversation_title
import mederi.app.shared.generated.resources.sidebar_delete_project
import mederi.app.shared.generated.resources.sidebar_delete_project_message
import mederi.app.shared.generated.resources.sidebar_delete_project_title
import mederi.app.shared.generated.resources.sidebar_dismiss
import mederi.app.shared.generated.resources.sidebar_new_conversation
import mederi.app.shared.generated.resources.sidebar_open_project_directory
import mederi.app.shared.generated.resources.sidebar_project_menu
import mederi.app.shared.generated.resources.project_settings_title
import mederi.app.shared.generated.resources.language_system
import mederi.app.shared.generated.resources.sidebar_language
import mederi.app.shared.generated.resources.sidebar_projects
import mederi.app.shared.generated.resources.sidebar_recent_conversations
import mederi.app.shared.generated.resources.sidebar_rename_conversation
import mederi.app.shared.generated.resources.sidebar_rename_project
import mederi.app.shared.generated.resources.sidebar_settings
import mederi.app.shared.generated.resources.sidebar_toggle_theme
import mederi.app.shared.generated.resources.sidebar_pin_conversation
import mederi.app.shared.generated.resources.sidebar_unpin_conversation
import mederi.app.shared.generated.resources.sidebar_version
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.AppInfo
import xyz.mederi.theme.WorkspaceStyle
import xyz.mederi.core.contract.models.Conversation
import xyz.mederi.core.contract.models.ConversationStatus
import xyz.mederi.core.contract.models.Project
import xyz.mederi.ui.appstate.LocalAppState
import xyz.mederi.theme.AppLanguage
import xyz.mederi.ui.components.atoms.BadgeStatus
import xyz.mederi.ui.components.atoms.ConfirmDialog
import xyz.mederi.ui.components.atoms.ExpandChevron
import xyz.mederi.ui.components.atoms.InputDialog
import xyz.mederi.ui.components.atoms.MederiIconSquareButton
import xyz.mederi.ui.components.atoms.MederiMinimalIconButton
import xyz.mederi.ui.components.atoms.MederiStatusDot
import xyz.mederi.ui.components.atoms.WorkspaceActionButton
import xyz.mederi.theme.AppThemeMode
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiColors
import xyz.mederi.theme.MederiRadius
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.theme.MederiTypeScale
import xyz.mederi.util.TimeFormatter

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

/** 会话状态 → [MederiStatusDot] 的 [BadgeStatus] 映射（conv 前缀状态点）。 */
private fun ConversationStatus.toBadgeStatus(): BadgeStatus = when (this) {
    ConversationStatus.Working -> BadgeStatus.Working
    ConversationStatus.WaitingUser -> BadgeStatus.Waiting
    ConversationStatus.Idle -> BadgeStatus.Idle
    ConversationStatus.Error -> BadgeStatus.Error
}

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
    onActiveInteractionChange: (Boolean) -> Unit = {},
    onRequestClose: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenProjectSettings: (String) -> Unit = {},
    onOpenProjectPicker: () -> Unit = {},
) {
    val appState = LocalAppState.current
    val colors = LocalMederiColors.current
    val leftSidebarWidth by appState.leftSidebarWidth.collectAsState()
    val projects by viewModel.filteredProjects.collectAsState()
    val recentConversations by viewModel.recentConversations.collectAsState()
    val theme by viewModel.theme.collectAsState()

    val interactionState = remember { SidebarInteractionState() }

    LaunchedEffect(interactionState.activeCount) {
        val interacting = interactionState.isInteracting
        DebugLog.info("SidebarHover", "Sidebar interaction count: ${interactionState.activeCount}, isInteracting=$interacting")
        onActiveInteractionChange(interacting)
    }

    // 抽屉销毁时必须回落交互态：父层唤出判定是派生式的，脏 true 会让抽屉立刻重新弹出
    DisposableEffect(Unit) {
        onDispose { onActiveInteractionChange(false) }
    }

    // 抽屉模式下，改变会话/项目选择的操作同时收起抽屉（桌面常驻侧栏不收起）
    val navigate: () -> Unit = { if (isDrawer) onRequestClose() }

    CompositionLocalProvider(LocalSidebarInteractionState provides interactionState) {
        Column(
            modifier = modifier
                .fillMaxHeight()
                .then(if (isCompact) Modifier.fillMaxWidth() else Modifier.width(leftSidebarWidth.dp))
                .background(colors.surfaceSidebar)
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MederiSpacing.XS),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WorkspaceActionButton(
                    text = stringResource(Res.string.sidebar_new_conversation),
                    icon = FeatherIcons.Plus,
                    primary = true,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        viewModel.newSession()
                        navigate()
                    },
                )
                if (isCompact && isDrawer) {
                    MederiMinimalIconButton(
                        icon = FeatherIcons.X,
                        onClick = onRequestClose,
                        contentDescription = stringResource(Res.string.sidebar_close),
                        colors = colors,
                    )
                }
            }
            Spacer(Modifier.height(MederiSpacing.MD))

            // 操作失败提示（rename/delete 等）：侧边栏唯一的错误出口，可关闭（不再静默失败）
            val errorMessage = viewModel.uiState.error
            if (errorMessage != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(MederiRadius.Control))
                        .background(colors.accentDanger.copy(alpha = 0.12f))
                        .padding(start = 8.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = errorMessage,
                        color = colors.accentDanger,
                        fontSize = 11.5.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    MederiMinimalIconButton(
                        icon = FeatherIcons.X,
                        contentDescription = stringResource(Res.string.sidebar_dismiss),
                        onClick = { viewModel.clearError() },
                        colors = colors
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(MederiSpacing.Micro),
            ) {
                if (recentConversations.isNotEmpty()) {
                    val expanded = viewModel.uiState.recentExpanded
                    SidebarGroupHeader(
                        title = stringResource(Res.string.sidebar_recent_conversations),
                        icon = FeatherIcons.Clock,
                        expanded = expanded,
                        onToggle = viewModel::toggleRecentExpanded,
                        colors = colors,
                    )
                    if (expanded) {
                        SidebarConversationList(recentConversations, viewModel, colors, navigate)
                    }
                    Spacer(Modifier.height(MederiSpacing.SM))
                }

                // 项目分组 Header（原型 .sidebar-section-header：mt 20 / padding 0-6-4-6 / 11sp 500 uppercase + 0.5sp 字距）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(Res.string.sidebar_projects).uppercase(),
                        color = colors.textSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.5.sp
                    )
                    // 20×20 plus 按钮（icon-btn-minimal 缩小实例）：透明底 hover surfaceHover
                    val plusInteraction = remember { MutableInteractionSource() }
                    val plusHovered by plusInteraction.collectIsHoveredAsState()
                    val plusBg by animateColorAsState(
                        targetValue = if (plusHovered) colors.surfaceHover else Color.Transparent,
                        animationSpec = LocalWorkspaceMotion.current.feedbackSpec(),
                        label = "sectionHeaderPlusBg",
                    )
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(RoundedCornerShape(MederiRadius.Square))
                            .background(plusBg)
                            .hoverable(plusInteraction)
                            .clickable(interactionSource = plusInteraction) { onOpenProjectPicker() },
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

                projects.forEach { project ->
                    key(project.id) {
                        ProjectTreeRow(
                            project = project,
                            viewModel = viewModel,
                            colors = colors,
                            navigate = navigate,
                            onOpenProjectSettings = onOpenProjectSettings,
                            isCompact = isCompact,
                        )
                    }
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
                    // 左「设置」组（原型 .settings-button：padding 4/6、gap 6、圆角 6、12.5sp、⚙16；hover surfaceHover）
                    val settingsInteraction = remember { MutableInteractionSource() }
                    val settingsHovered by settingsInteraction.collectIsHoveredAsState()
                    val settingsBg by animateColorAsState(
                        targetValue = if (settingsHovered) colors.surfaceHover else Color.Transparent,
                        animationSpec = LocalWorkspaceMotion.current.feedbackSpec(),
                        label = "settingsButtonBg",
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(MederiRadius.Control))
                            .background(settingsBg)
                            .hoverable(settingsInteraction)
                            .clickable(interactionSource = settingsInteraction) { onOpenSettings() }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = FeatherIcons.Settings,
                            contentDescription = stringResource(Res.string.sidebar_settings),
                            tint = colors.textSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = stringResource(Res.string.sidebar_settings),
                            color = colors.textSecondary,
                            fontSize = 12.5.sp
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
                            // 02 §1.6 icon-square-btn（28×28 / 圆角 6 / bg surfaceHover + 1dp 边框 / icon 14）
                            MederiIconSquareButton(
                                icon = FeatherIcons.Globe,
                                contentDescription = stringResource(Res.string.sidebar_language),
                                onClick = {
                                    DebugLog.info("SidebarHover", "Language menu opened: showLanguageMenu = true")
                                    showLanguageMenu = true
                                },
                                colors = colors
                            )

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

                        // Theme Switch Button（主题写操作唯一通道：AppState；02 §1.6 icon-square-btn）
                        MederiIconSquareButton(
                            icon = if (theme.isDark) FeatherIcons.Moon else FeatherIcons.Sun,
                            contentDescription = stringResource(Res.string.sidebar_toggle_theme),
                            onClick = { viewModel.setTheme(if (theme.isDark) AppThemeMode.LIGHT else AppThemeMode.DARK) },
                            colors = colors
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // 第二行：版本号（原型 .footer-line-2：mono 11sp text-muted padding 0/4，格式 "Mederi vX"）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(Res.string.sidebar_version, AppInfo.VERSION),
                        color = colors.textMuted,
                        fontSize = 11.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SidebarGroupHeader(
    title: String,
    icon: ImageVector,
    expanded: Boolean,
    onToggle: () -> Unit,
    colors: MederiColors,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(
        targetValue = when {
            hovered -> colors.surfaceHover
            expanded -> colors.surfaceCard.copy(alpha = 0.5f)
            else -> Color.Transparent
        },
        animationSpec = LocalWorkspaceMotion.current.feedbackSpec(),
        label = "sidebarGroupBackground",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = WorkspaceStyle.ControlHeight)
            .clip(RoundedCornerShape(MederiRadius.Control))
            .background(background)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, role = Role.Button, onClick = onToggle)
            .padding(horizontal = MederiSpacing.SM, vertical = MederiSpacing.Tight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MederiSpacing.SM),
        ) {
            ExpandChevron(
                expanded = expanded,
                tint = if (expanded) colors.textSecondary else colors.textMuted,
                size = MederiSpacing.Loose,
            )
            Icon(icon, null, tint = colors.accentPrimary, modifier = Modifier.size(MederiSpacing.Loose))
            Text(title, style = MederiTypeScale.Title, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}

@Composable
private fun SidebarConversationList(
    conversations: List<Conversation>,
    viewModel: SidebarViewModel,
    colors: MederiColors,
    navigate: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = MederiSpacing.MD, top = MederiSpacing.XS, bottom = MederiSpacing.XS),
        verticalArrangement = Arrangement.spacedBy(MederiSpacing.Micro),
    ) {
        conversations.forEach { conversation ->
            key(conversation.id) {
                ConversationTreeRow(conversation, viewModel, colors, navigate)
            }
        }
    }
}

@Composable
private fun ProjectTreeRow(
    project: Project,
    viewModel: SidebarViewModel,
    colors: MederiColors,
    navigate: () -> Unit,
    onOpenProjectSettings: (String) -> Unit,
    isCompact: Boolean,
) {
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
        SidebarGroupHeader(
            title = project.name,
            icon = if (isExpanded) FeatherIcons.FolderMinus else FeatherIcons.Folder,
            expanded = isExpanded,
            onToggle = { viewModel.toggleProjectExpanded(project.id) },
            colors = colors,
        ) {
            // 右侧操作图标组 (+ 和 三竖点)：20×20 / 透明底 hover surfaceHover / icon 13dp textMuted
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // + 按钮：新建当前项目对话
                val plusInteraction = remember { MutableInteractionSource() }
                val plusHovered by plusInteraction.collectIsHoveredAsState()
                val plusBg by animateColorAsState(
                    targetValue = if (plusHovered) colors.surfaceHover else Color.Transparent,
                    animationSpec = LocalWorkspaceMotion.current.feedbackSpec(),
                    label = "projectPlusBg",
                )
                Box(
                    modifier = Modifier
                        .size(if (isCompact) 44.dp else WorkspaceStyle.ControlHeight)
                        .clip(RoundedCornerShape(MederiRadius.Square))
                        .background(plusBg)
                        .hoverable(plusInteraction)
                        .clickable(interactionSource = plusInteraction) {
                            viewModel.createConversation(project.id)
                            navigate()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = FeatherIcons.Plus,
                        contentDescription = stringResource(Res.string.sidebar_new_conversation),
                        tint = colors.textMuted,
                        modifier = Modifier.size(13.dp)
                    )
                }

                // 三竖点 按钮：项目管理菜单
                val moreInteraction = remember { MutableInteractionSource() }
                val moreHovered by moreInteraction.collectIsHoveredAsState()
                val moreBg by animateColorAsState(
                    targetValue = if (moreHovered) colors.surfaceHover else Color.Transparent,
                    animationSpec = LocalWorkspaceMotion.current.feedbackSpec(),
                    label = "projectMoreBg",
                )
                Box(
                    modifier = Modifier
                        .size(if (isCompact) 44.dp else WorkspaceStyle.ControlHeight)
                        .clip(RoundedCornerShape(MederiRadius.Square))
                        .background(moreBg)
                        .hoverable(moreInteraction)
                        .clickable(interactionSource = moreInteraction) { isMenuExpanded = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = FeatherIcons.MoreVertical,
                        contentDescription = stringResource(Res.string.sidebar_project_menu),
                        tint = colors.textMuted,
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
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.project_settings_title), fontSize = 12.sp, color = colors.textPrimary) },
                            leadingIcon = { Icon(FeatherIcons.Settings, null, tint = colors.textSecondary, modifier = Modifier.size(14.dp)) },
                            onClick = {
                                isMenuExpanded = false
                                onOpenProjectSettings(project.id)
                                navigate()
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

        if (isExpanded && project.conversations.isNotEmpty()) {
            SidebarConversationList(project.conversations, viewModel, colors, navigate)
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

    // 中性选中族（03 §1.2②）：行高 32 / padding 0-10 / 去掉行级 clip 边框与 accent 底；
    // active = bg surfaceHover + text textPrimary + Medium（无 border、无 accent）；hover 同 surfaceHover + textPrimary（120ms）。
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val bg by animateColorAsState(
        targetValue = if (hovered || isSelected) colors.surfaceHover else Color.Transparent,
        animationSpec = LocalWorkspaceMotion.current.feedbackSpec(),
        label = "conversationRowBg",
    )
    val textColor by animateColorAsState(
        targetValue = if (hovered || isSelected) colors.textPrimary else colors.textSecondary,
        animationSpec = LocalWorkspaceMotion.current.feedbackSpec(),
        label = "conversationRowText",
    )
    // 相对活动时间随 nowTick（60s 周期）定时刷新
    val nowMillis by viewModel.nowTick.collectAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .clip(RoundedCornerShape(MederiRadius.Control))
            .background(bg)
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource) {
                viewModel.selectConversation(conversation.id)
                navigate()
            }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 前缀 6dp 状态点（映射自会话状态，间距 8dp）
        MederiStatusDot(status = conversation.status.toBadgeStatus())

        Text(
            text = conversation.title,
            color = textColor,
            fontSize = MederiTypeScale.Row.fontSize,
            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        // 相对活动时间（无 weight 自然宽度，标题 weight(1f) 自动截断让出空间）
        Text(
            text = remember(conversation.updatedAt, nowMillis) {
                TimeFormatter.formatRelativeActivity(conversation.updatedAt)
            },
            color = colors.textMuted,
            fontSize = 10.sp,
            maxLines = 1
        )

        // 右置 MoreVertical 20×20 ghost：保留重命名/删除菜单，hover surfaceHover
        val moreInteraction = remember { MutableInteractionSource() }
        val moreHovered by moreInteraction.collectIsHoveredAsState()
        val moreBg by animateColorAsState(
            targetValue = if (moreHovered) colors.surfaceHover else Color.Transparent,
            animationSpec = LocalWorkspaceMotion.current.feedbackSpec(),
            label = "conversationMoreBg",
        )
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(MederiRadius.Square))
                .background(moreBg)
                .hoverable(moreInteraction)
                .clickable(interactionSource = moreInteraction) { isMenuExpanded = true },
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
                DropdownMenuItem(
                    text = { Text(
                        stringResource(if (conversation.pinned) Res.string.sidebar_unpin_conversation else Res.string.sidebar_pin_conversation),
                        fontSize = 12.sp,
                        color = colors.textPrimary
                    ) },
                    leadingIcon = { Icon(
                        FeatherIcons.MapPin,
                        null,
                        tint = if (conversation.pinned) colors.accentPrimary else colors.textSecondary,
                        modifier = Modifier.size(14.dp)
                    ) },
                    onClick = {
                        isMenuExpanded = false
                        viewModel.setPinned(conversation.id, !conversation.pinned)
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

/**
 * 侧边栏右边缘拖拽改宽 handle（大屏 pinned 与 overlay 模式共用）。
 * 4dp 宽竖条，hover 高亮，detectHorizontalDragGestures 调 onDragDelta 更新宽度。
 */
@Composable
fun SidebarResizeHandle(
    onDragDelta: (deltaDp: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val colors = LocalMederiColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val bg by animateColorAsState(
        targetValue = if (hovered) colors.borderStrong else colors.surfaceSidebar,
        animationSpec = LocalWorkspaceMotion.current.feedbackSpec(),
        label = "resizeHandleBg",
    )
    Box(
        modifier = modifier
            .width(4.dp)
            .fillMaxHeight()
            .hoverable(interactionSource)
            .pointerHoverIcon(PointerIcon.Hand)
            .background(bg)
            .pointerInput(Unit) {
                detectHorizontalDragGestures { _, dragAmount ->
                    val deltaDp = with(density) { dragAmount.toDp().value }
                    onDragDelta(deltaDp)
                }
            }
    )
}
