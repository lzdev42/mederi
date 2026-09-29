package xyz.mederi.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.rotate
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
import mederi.app.shared.generated.resources.sidebar_pin
import mederi.app.shared.generated.resources.sidebar_projects
import mederi.app.shared.generated.resources.sidebar_rename_conversation
import mederi.app.shared.generated.resources.sidebar_rename_project
import mederi.app.shared.generated.resources.sidebar_settings
import mederi.app.shared.generated.resources.sidebar_toggle_theme
import mederi.app.shared.generated.resources.sidebar_unpin
import mederi.app.shared.generated.resources.sidebar_version
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.AppInfo
import xyz.mederi.core.contract.models.Conversation
import xyz.mederi.core.contract.models.ConversationStatus
import xyz.mederi.core.contract.models.Project
import xyz.mederi.ui.DebugLog
import xyz.mederi.ui.SidebarViewModel
import xyz.mederi.ui.appstate.LocalAppState
import xyz.mederi.theme.AppLanguage
import xyz.mederi.ui.components.atoms.BadgeStatus
import xyz.mederi.ui.components.atoms.ConfirmDialog
import xyz.mederi.ui.components.atoms.InputDialog
import xyz.mederi.ui.components.atoms.MederiIconSquareButton
import xyz.mederi.ui.components.atoms.MederiMinimalIconButton
import xyz.mederi.ui.components.atoms.MederiStatusDot
import xyz.mederi.theme.AppThemeMode
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiColors
import xyz.mederi.theme.MederiRadius
import xyz.mederi.theme.MederiTypeScale

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
                .then(if (isCompact) Modifier.fillMaxWidth() else Modifier.width(245.dp))
                .background(colors.surfaceSidebar)
                .padding(horizontal = 10.dp, vertical = 8.dp)
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
                MederiMinimalIconButton(
                    icon = FeatherIcons.Search,
                    onClick = {},
                    colors = colors
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (onTogglePin != null) {
                        SidebarIconButton(
                            imageVector = FeatherIcons.Sidebar,
                            contentDescription = stringResource(if (isPinned) Res.string.sidebar_unpin else Res.string.sidebar_pin),
                            active = isPinned,
                            colors = colors,
                            onClick = onTogglePin
                        )
                    }
                    if (isDrawer) {
                        SidebarIconButton(
                            imageVector = FeatherIcons.ChevronLeft,
                            contentDescription = stringResource(Res.string.sidebar_cancel),
                            colors = colors,
                            onClick = onRequestClose
                        )
                    }
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

        Spacer(modifier = Modifier.height(20.dp))

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
                animationSpec = tween(120),
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
                // 左「设置」组（原型 .settings-button：padding 4/6、gap 6、圆角 6、12.5sp、⚙16；hover surfaceHover）
                val settingsInteraction = remember { MutableInteractionSource() }
                val settingsHovered by settingsInteraction.collectIsHoveredAsState()
                val settingsBg by animateColorAsState(
                    targetValue = if (settingsHovered) colors.surfaceHover else Color.Transparent,
                    animationSpec = tween(120),
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
                        icon = if (colors.isDark) FeatherIcons.Moon else FeatherIcons.Sun,
                        contentDescription = stringResource(Res.string.sidebar_toggle_theme),
                        onClick = {
                            val nextTheme = when (theme) {
                                AppThemeMode.DARK -> AppThemeMode.LIGHT
                                AppThemeMode.LIGHT -> AppThemeMode.DARK
                            }
                            viewModel.setTheme(nextTheme)
                        },
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
private fun SidebarMenuItem(
    icon: ImageVector,
    title: String,
    isSelected: Boolean,
    colors: MederiColors,
    onClick: () -> Unit = {}
) {
    // 02 §1.9：高 36 / padding 0-12 / gap 10 / icon 16 / 13.5sp；active 用 iris 族（accentBg/accentText/accentBorder），
    // hover 用 surfaceHover + textPrimary；常态预留 1dp 透明 border 防 active 1px 抖动（03 §3.3#10）。
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val bg by animateColorAsState(
        targetValue = when {
            isSelected -> colors.accentBg
            hovered -> colors.surfaceHover
            else -> Color.Transparent
        },
        animationSpec = tween(120),
        label = "sidebarMenuItemBg",
    )
    val contentColor by animateColorAsState(
        targetValue = when {
            isSelected -> colors.accentText
            hovered -> colors.textPrimary
            else -> colors.textSecondary
        },
        animationSpec = tween(120),
        label = "sidebarMenuItemContent",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(MederiRadius.Control))
            .background(bg)
            .border(
                width = 1.dp,
                color = if (isSelected) colors.accentBorder else Color.Transparent,
                shape = RoundedCornerShape(MederiRadius.Control)
            )
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = title,
            color = contentColor,
            style = MederiTypeScale.Section.copy(
                fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
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
        // 项目主行（原型 .project-row：padding 6/8、13sp/500；hover 优先 surfaceHover，展开底色 surfaceCard α0.5 保留）
        val rowInteraction = remember { MutableInteractionSource() }
        val rowHovered by rowInteraction.collectIsHoveredAsState()
        val rowBg by animateColorAsState(
            targetValue = when {
                rowHovered -> colors.surfaceHover
                isExpanded -> colors.surfaceCard.copy(alpha = 0.5f)
                else -> Color.Transparent
            },
            animationSpec = tween(120),
            label = "projectRowBg",
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(MederiRadius.Control))
                .background(rowBg)
                .hoverable(rowInteraction)
                .clickable(interactionSource = rowInteraction) { viewModel.toggleProjectExpanded(project.id) }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // chevron 13dp：展开 rotate 90，展开时 textSecondary
                Icon(
                    imageVector = FeatherIcons.ChevronRight,
                    contentDescription = null,
                    tint = if (isExpanded) colors.textSecondary else colors.textMuted,
                    modifier = Modifier
                        .size(13.dp)
                        .rotate(if (isExpanded) 90f else 0f)
                )
                // folder 14dp：accent 色（原型 folder accent）
                Icon(
                    imageVector = if (isExpanded) FeatherIcons.FolderMinus else FeatherIcons.Folder,
                    contentDescription = null,
                    tint = colors.accentPrimary,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = project.name,
                    color = colors.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

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
                    animationSpec = tween(120),
                    label = "projectPlusBg",
                )
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(MederiRadius.Square))
                        .background(plusBg)
                        .hoverable(plusInteraction)
                        .clickable(interactionSource = plusInteraction) {
                            appState.selectProject(project.id)
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
                    animationSpec = tween(120),
                    label = "projectMoreBg",
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

    // 中性选中族（03 §1.2②）：行高 32 / padding 0-10 / 去掉行级 clip 边框与 accent 底；
    // active = bg surfaceHover + text textPrimary + Medium（无 border、无 accent）；hover 同 surfaceHover + textPrimary（120ms）。
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val bg by animateColorAsState(
        targetValue = if (hovered || isSelected) colors.surfaceHover else Color.Transparent,
        animationSpec = tween(120),
        label = "conversationRowBg",
    )
    val textColor by animateColorAsState(
        targetValue = if (hovered || isSelected) colors.textPrimary else colors.textSecondary,
        animationSpec = tween(120),
        label = "conversationRowText",
    )
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

        // 右置 MoreVertical 20×20 ghost：保留重命名/删除菜单，hover surfaceHover
        val moreInteraction = remember { MutableInteractionSource() }
        val moreHovered by moreInteraction.collectIsHoveredAsState()
        val moreBg by animateColorAsState(
            targetValue = if (moreHovered) colors.surfaceHover else Color.Transparent,
            animationSpec = tween(120),
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
