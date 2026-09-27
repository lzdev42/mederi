package xyz.mederi.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import kotlinx.coroutines.launch
import xyz.mederi.core.contract.models.SkillItem
import xyz.mederi.ui.appstate.SkillStore
import xyz.mederi.isDesktopPlatform
import xyz.mederi.theme.MederiColors
import xyz.mederi.ui.components.atoms.CardHeader
import xyz.mederi.ui.components.atoms.MederiCompatBadge
import xyz.mederi.ui.components.atoms.MederiGhostButton
import xyz.mederi.ui.components.atoms.MederiMinimalIconButton
import xyz.mederi.ui.components.atoms.MederiPanelHeaderIconButton
import xyz.mederi.ui.components.atoms.MederiPrimaryDecisionButton
import xyz.mederi.ui.components.atoms.PanelCard
import xyz.mederi.ui.components.atoms.PanelEmptyState
import xyz.mederi.util.pickDirectory
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.cancel
import mederi.app.shared.generated.resources.pick_directory_title
import mederi.app.shared.generated.resources.skill_deleting
import mederi.app.shared.generated.resources.skill_empty
import mederi.app.shared.generated.resources.skill_empty_hint
import mederi.app.shared.generated.resources.skill_install
import mederi.app.shared.generated.resources.skill_install_action
import mederi.app.shared.generated.resources.skill_install_failed
import mederi.app.shared.generated.resources.skill_install_prompt
import mederi.app.shared.generated.resources.skill_installing
import mederi.app.shared.generated.resources.skill_path_required
import mederi.app.shared.generated.resources.skill_pick_dir_native
import mederi.app.shared.generated.resources.skill_refresh
import mederi.app.shared.generated.resources.skill_root_dir
import mederi.app.shared.generated.resources.skill_root_prompt
import mederi.app.shared.generated.resources.skill_root_prompt_remote
import mederi.app.shared.generated.resources.skill_save
import mederi.app.shared.generated.resources.skill_save_dir_failed
import mederi.app.shared.generated.resources.skill_saving
import mederi.app.shared.generated.resources.skill_set_root_desc
import mederi.app.shared.generated.resources.skill_set_root_title
import mederi.app.shared.generated.resources.skill_set_root_title_remote
import mederi.app.shared.generated.resources.skill_title
import mederi.app.shared.generated.resources.skill_uninstall
import mederi.app.shared.generated.resources.skill_uninstall_confirm
import mederi.app.shared.generated.resources.skill_uninstall_confirm_action
import mederi.app.shared.generated.resources.skill_uninstall_title
import mederi.app.shared.generated.resources.skill_url_required
import org.jetbrains.compose.resources.stringResource

/**
 * 概览面板内的 Skill 管理卡片：具备展示列表、刷新、安装、设定根目录与卸载能力。
 *
 * 依赖全局唯一真理源 [SkillStore]，与未来的 Skill 市场等其他组件实时共享状态。
 */
@Composable
fun SkillManagementCard(
    skillStore: SkillStore,
    colors: MederiColors,
    modifier: Modifier = Modifier
) {
    var isAddDialogOpen by remember { mutableStateOf(false) }
    var isRootDialogOpen by remember { mutableStateOf(false) }
    var uninstallTarget by remember { mutableStateOf<SkillItem?>(null) }
    var isExpanded by remember { mutableStateOf(false) }

    val skills by skillStore.skills.collectAsState()
    val isRefreshing by skillStore.isRefreshing.collectAsState()
    val skillsRoot by skillStore.skillsRoot.collectAsState()
    val isOperating by skillStore.isOperating.collectAsState()

    val coroutineScope = rememberCoroutineScope()
    val pickDirectoryTitle = stringResource(Res.string.skill_set_root_title)

    val onSelectDirectory = {
        coroutineScope.launch {
            if (isDesktopPlatform) {
                val picked = pickDirectory(pickDirectoryTitle)
                if (!picked.isNullOrBlank()) {
                    skillStore.setRootDirectory(picked)
                }
            } else {
                isRootDialogOpen = true
            }
        }
    }

    PanelCard(modifier = modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 卡片 Header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .clickable { isExpanded = !isExpanded }
        ) {
            CardHeader(
                icon = FeatherIcons.Package,
                title = stringResource(Res.string.skill_title),
                count = {
                    Text(
                        text = "${skills.size}",
                        color = colors.accentPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                },
                actions = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 设定根目录按钮（收敛为 MederiPanelHeaderIconButton；优先调用系统原生选择器）
                        MederiPanelHeaderIconButton(
                            icon = FeatherIcons.Folder,
                            onClick = { onSelectDirectory() },
                            contentDescription = stringResource(Res.string.skill_set_root_desc),
                        )

                        // 刷新按钮内联保留：isRefreshing → accent 高亮为状态反馈，atom 暂无状态色参数，待对齐
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { skillStore.refresh() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = FeatherIcons.RefreshCw,
                                contentDescription = stringResource(Res.string.skill_refresh),
                                tint = if (isRefreshing) colors.accentPrimary else colors.textMuted,
                                modifier = Modifier.size(12.dp)
                            )
                        }

                        // 安装按钮（收敛为 MederiPanelHeaderIconButton）
                        MederiPanelHeaderIconButton(
                            icon = FeatherIcons.Plus,
                            onClick = { isAddDialogOpen = true },
                            contentDescription = stringResource(Res.string.skill_install),
                        )

                        // 折叠/展开箭头
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isExpanded) FeatherIcons.ChevronUp else FeatherIcons.ChevronDown,
                                contentDescription = null,
                                tint = colors.textMuted,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            )
        }

        // 展开内容区域（支持平滑折叠）
        AnimatedVisibility(visible = isExpanded) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 当前目录提示小字（内联保留：folder 图标 + 单行 ellipsis + 整行点击重选目录，MederiProjectPill 无图标/ellipsis/onClick，待配色迁移时对齐）
                if (skillsRoot.isNotBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(colors.surfaceWorkspace.copy(alpha = 0.5f))
                            .clickable { onSelectDirectory() }
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = FeatherIcons.Folder,
                            contentDescription = null,
                            tint = colors.textMuted,
                            modifier = Modifier.size(10.dp)
                        )
                        Text(
                            text = stringResource(Res.string.skill_root_dir, skillsRoot),
                            color = colors.textMuted,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // 列表区
                if (skills.isEmpty()) {
                    PanelEmptyState(
                        icon = FeatherIcons.Package,
                        title = stringResource(Res.string.skill_empty),
                        hint = stringResource(Res.string.skill_empty_hint)
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        skills.forEach { skill ->
                            SkillItemRow(
                                skill = skill,
                                colors = colors,
                                onUninstall = { uninstallTarget = skill }
                            )
                        }
                    }
                }
            }
        }
        }
    }

    // 安装 Skill 弹窗
    if (isAddDialogOpen) {
        InstallSkillDialog(
            onDismiss = { isAddDialogOpen = false },
            onConfirm = { url -> skillStore.install(url) },
            colors = colors
        )
    }

    // 设置根目录弹窗
    if (isRootDialogOpen) {
        SetSkillRootDialog(
            initialPath = skillsRoot,
            onDismiss = { isRootDialogOpen = false },
            onConfirm = { path -> skillStore.setRootDirectory(path) },
            colors = colors
        )
    }

    // 卸载确认弹窗
    uninstallTarget?.let { target ->
        UninstallSkillConfirmDialog(
            skill = target,
            isDeleting = isOperating,
            onDismiss = { uninstallTarget = null },
            onConfirm = {
                val res = skillStore.uninstall(target.name)
                if (res.isSuccess) {
                    uninstallTarget = null
                }
            },
            colors = colors
        )
    }
}

@Composable
private fun SkillItemRow(
    skill: SkillItem,
    colors: MederiColors,
    onUninstall: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(colors.surfaceWorkspace)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Skill 信息
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = skill.name,
                    color = colors.textPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                skill.compatibility?.takeIf { it.isNotBlank() }?.let { compat ->
                    // 兼容性徽标：收敛为 MederiCompatBadge（warning 软底 + 描边，对齐标准 §3.6）
                    MederiCompatBadge(text = compat)
                }
            }

            if (skill.description.isNotBlank()) {
                Text(
                    text = skill.description,
                    color = colors.textSecondary,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // 卸载按钮（收敛为 MederiMinimalIconButton：透明方形 + hover 反馈）
        MederiMinimalIconButton(
            icon = FeatherIcons.Trash2,
            onClick = { onUninstall() },
            contentDescription = stringResource(Res.string.skill_uninstall),
        )
    }
}

@Composable
private fun InstallSkillDialog(
    onDismiss: () -> Unit,
    onConfirm: suspend (String) -> Result<SkillItem>,
    colors: MederiColors
) {
    var urlText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isInstalling by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // 错误文案在组合上下文取值（Button onClick / scope.launch 不是 @Composable）
    val urlRequiredMsg = stringResource(Res.string.skill_url_required)
    val installFailedMsg = stringResource(Res.string.skill_install_failed)

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .width(420.dp)
                .padding(16.dp),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = colors.surfaceCard),
            border = BorderStroke(1.dp, colors.divider)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(Res.string.skill_install),
                    color = colors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = stringResource(Res.string.skill_install_prompt),
                    color = colors.textMuted,
                    fontSize = 11.sp
                )

                OutlinedTextField(
                    value = urlText,
                    onValueChange = {
                        urlText = it
                        errorMessage = null
                    },
                    placeholder = {
                        Text(
                            text = "https://example.com/skills/my-skill.zip",
                            color = colors.textMuted,
                            fontSize = 11.sp
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = colors.textPrimary
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.accentPrimary,
                        unfocusedBorderColor = colors.divider,
                        focusedContainerColor = colors.surfaceWorkspace,
                        unfocusedContainerColor = colors.surfaceWorkspace
                    ),
                    singleLine = true
                )

                errorMessage?.let {
                    Text(
                        text = it,
                        color = colors.accentDanger,
                        fontSize = 10.sp
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 取消/安装：收敛为 MederiGhostButton + MederiPrimaryDecisionButton（安装中 spinner 收敛为文案切换）
                    MederiGhostButton(
                        text = stringResource(Res.string.cancel),
                        onClick = onDismiss,
                        enabled = !isInstalling,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    MederiPrimaryDecisionButton(
                        text = if (isInstalling) stringResource(Res.string.skill_installing) else stringResource(Res.string.skill_install_action),
                        onClick = {
                            val trimmed = urlText.trim()
                            if (trimmed.isBlank()) {
                                errorMessage = urlRequiredMsg
                            } else {
                                scope.launch {
                                    isInstalling = true
                                    val res = onConfirm(trimmed)
                                    isInstalling = false
                                    if (res.isSuccess) {
                                        onDismiss()
                                    } else {
                                        errorMessage = res.exceptionOrNull()?.message ?: installFailedMsg
                                    }
                                }
                            }
                        },
                        enabled = !isInstalling && urlText.isNotBlank(),
                    )
                }
            }
        }
    }
}

@Composable
private fun SetSkillRootDialog(
    initialPath: String,
    onDismiss: () -> Unit,
    onConfirm: suspend (String) -> Result<Unit>,
    colors: MederiColors
) {
    var pathText by remember { mutableStateOf(initialPath) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // 错误文案在组合上下文取值（Button onClick / scope.launch 不是 @Composable）
    val pathRequiredMsg = stringResource(Res.string.skill_path_required)
    val saveDirFailedMsg = stringResource(Res.string.skill_save_dir_failed)
    val pickDirectoryTitle = stringResource(Res.string.pick_directory_title)

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .width(420.dp)
                .padding(16.dp),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = colors.surfaceCard),
            border = BorderStroke(1.dp, colors.divider)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = if (isDesktopPlatform) stringResource(Res.string.skill_set_root_title)
                           else stringResource(Res.string.skill_set_root_title_remote),
                    color = colors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = if (isDesktopPlatform) stringResource(Res.string.skill_root_prompt)
                           else stringResource(Res.string.skill_root_prompt_remote),
                    color = colors.textMuted,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )

                OutlinedTextField(
                    value = pathText,
                    onValueChange = {
                        pathText = it
                        errorMessage = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = if (isDesktopPlatform) {
                        {
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        val picked = pickDirectory(pickDirectoryTitle)
                                        if (!picked.isNullOrBlank()) {
                                            pathText = picked
                                            errorMessage = null
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = FeatherIcons.Folder,
                                    contentDescription = stringResource(Res.string.skill_pick_dir_native),
                                    tint = colors.accentPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    } else null,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = colors.textPrimary
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.accentPrimary,
                        unfocusedBorderColor = colors.divider,
                        focusedContainerColor = colors.surfaceWorkspace,
                        unfocusedContainerColor = colors.surfaceWorkspace
                    ),
                    singleLine = true
                )


                errorMessage?.let {
                    Text(
                        text = it,
                        color = colors.accentDanger,
                        fontSize = 10.sp
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 取消/保存：收敛为 MederiGhostButton + MederiPrimaryDecisionButton
                    MederiGhostButton(
                        text = stringResource(Res.string.cancel),
                        onClick = onDismiss,
                        enabled = !isSaving,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    MederiPrimaryDecisionButton(
                        text = if (isSaving) stringResource(Res.string.skill_saving) else stringResource(Res.string.skill_save),
                        onClick = {
                            val trimmed = pathText.trim()
                            if (trimmed.isBlank()) {
                                errorMessage = pathRequiredMsg
                            } else {
                                scope.launch {
                                    isSaving = true
                                    val res = onConfirm(trimmed)
                                    isSaving = false
                                    if (res.isSuccess) {
                                        onDismiss()
                                    } else {
                                        errorMessage = res.exceptionOrNull()?.message ?: saveDirFailedMsg
                                    }
                                }
                            }
                        },
                        enabled = !isSaving && pathText.isNotBlank(),
                    )
                }
            }
        }
    }
}

@Composable
private fun UninstallSkillConfirmDialog(
    skill: SkillItem,
    isDeleting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: suspend () -> Unit,
    colors: MederiColors
) {
    val scope = rememberCoroutineScope()

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .width(360.dp)
                .padding(16.dp),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = colors.surfaceCard),
            border = BorderStroke(1.dp, colors.divider)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(Res.string.skill_uninstall_title),
                    color = colors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = stringResource(Res.string.skill_uninstall_confirm, skill.name),
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MederiGhostButton(
                        text = stringResource(Res.string.cancel),
                        onClick = onDismiss,
                        enabled = !isDeleting,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    // 危险确认按钮自绘保留：atom 无 danger 变体（accentDanger 容器 + onAccentPrimary 文案），待配色迁移时补 danger 变体
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.accentDanger)
                            .clickable(enabled = !isDeleting) {
                                scope.launch {
                                    onConfirm()
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (isDeleting) stringResource(Res.string.skill_deleting) else stringResource(Res.string.skill_uninstall_confirm_action),
                            color = colors.onAccentPrimary,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
    }
}
