package xyz.mederi.ui.overview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.Box
import compose.icons.feathericons.Folder
import compose.icons.feathericons.Plus
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.Trash2
import kotlinx.coroutines.launch
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.pick_directory_title
import mederi.app.shared.generated.resources.skill_empty
import mederi.app.shared.generated.resources.skill_empty_hint
import mederi.app.shared.generated.resources.skill_install
import mederi.app.shared.generated.resources.skill_refresh
import mederi.app.shared.generated.resources.skill_root_dir
import mederi.app.shared.generated.resources.skill_set_root_desc
import mederi.app.shared.generated.resources.skill_title
import mederi.app.shared.generated.resources.skill_uninstall
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.SkillItem
import xyz.mederi.isDesktopPlatform
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiColors
import xyz.mederi.theme.MederiRadius
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.ui.WorkspaceViewModel
import xyz.mederi.ui.appstate.SkillStore
import xyz.mederi.ui.components.InstallSkillDialog
import xyz.mederi.ui.components.SetSkillRootDialog
import xyz.mederi.ui.components.UninstallSkillConfirmDialog
import xyz.mederi.ui.components.atoms.MederiCompatBadge
import xyz.mederi.util.pickDirectory

/**
 * 概览浮动窗 Skill 行。
 *
 * 折叠态摘要：已安装 Skill 数量（textSecondary 12sp）。
 *
 * 展开态：操作栏（设根目录 + 刷新 + 安装）+ 根目录显示条（非空时可点击重选）+ 每个 Skill
 * 一行（名称 + 兼容徽标 + 描述 + 卸载按钮）；列表为空时显示空态文案。
 *
 * 复用 [InstallSkillDialog] / [SetSkillRootDialog] / [UninstallSkillConfirmDialog]，不重复实现弹窗。
 */
@Composable
fun OverviewSkillRow(
    viewModel: WorkspaceViewModel,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMederiColors.current
    val skillStore = viewModel.skillStore
    val skills by skillStore.skills.collectAsState()
    val isRefreshing by skillStore.isRefreshing.collectAsState()
    val skillsRoot by skillStore.skillsRoot.collectAsState()
    val isOperating by skillStore.isOperating.collectAsState()

    var isAddDialogOpen by remember { mutableStateOf(false) }
    var isRootDialogOpen by remember { mutableStateOf(false) }
    var uninstallTarget by remember { mutableStateOf<SkillItem?>(null) }

    val scope = rememberCoroutineScope()
    // 原生目录选择器标题在组合上下文预取（scope.launch 不是 @Composable，不能在此调用 stringResource）
    val pickDirectoryTitle = stringResource(Res.string.pick_directory_title)

    fun onSelectDirectory() {
        if (isDesktopPlatform) {
            scope.launch {
                val picked = pickDirectory(pickDirectoryTitle)
                if (!picked.isNullOrBlank()) skillStore.setRootDirectory(picked)
            }
        } else {
            isRootDialogOpen = true
        }
    }

    OverviewAccordionRow(
        icon = FeatherIcons.Box,
        title = stringResource(Res.string.skill_title),
        summary = {
            Text(
                text = "${skills.size}",
                color = colors.textSecondary,
                fontSize = 12.sp,
                maxLines = 1,
            )
        },
        expanded = expanded,
        onToggle = onToggle,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(MederiSpacing.SM),
        ) {
            // 操作栏：设根目录 + 刷新 + 安装
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MederiSpacing.Tight),
            ) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(MederiRadius.Square))
                        .clickable { onSelectDirectory() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = FeatherIcons.Folder,
                        contentDescription = stringResource(Res.string.skill_set_root_desc),
                        tint = colors.textMuted,
                        modifier = Modifier.size(12.dp),
                    )
                }
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(MederiRadius.Square))
                        .clickable { skillStore.refresh() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = FeatherIcons.RefreshCw,
                        contentDescription = stringResource(Res.string.skill_refresh),
                        tint = if (isRefreshing) colors.accentPrimary else colors.textMuted,
                        modifier = Modifier.size(12.dp),
                    )
                }
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(MederiRadius.Square))
                        .clickable { isAddDialogOpen = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = FeatherIcons.Plus,
                        contentDescription = stringResource(Res.string.skill_install),
                        tint = colors.textMuted,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }

            // 根目录显示条：整条可点击重新选择
            if (skillsRoot.isNotBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(MederiRadius.Control))
                        .clickable { onSelectDirectory() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = FeatherIcons.Folder,
                        contentDescription = null,
                        tint = colors.textMuted,
                        modifier = Modifier.size(10.dp),
                    )
                    Text(
                        text = stringResource(Res.string.skill_root_dir, skillsRoot),
                        color = colors.textMuted,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // 列表区
            if (skills.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = MederiSpacing.SM),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(Res.string.skill_empty),
                            color = colors.textMuted,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            text = stringResource(Res.string.skill_empty_hint),
                            color = colors.textMuted,
                            fontSize = 10.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(MederiSpacing.Tight),
                ) {
                    skills.forEach { skill ->
                        OverviewSkillItemRow(
                            skill = skill,
                            colors = colors,
                            onUninstall = { uninstallTarget = skill },
                        )
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
            colors = colors,
        )
    }

    // 设置根目录弹窗
    if (isRootDialogOpen) {
        SetSkillRootDialog(
            initialPath = skillsRoot,
            onDismiss = { isRootDialogOpen = false },
            onConfirm = { path -> skillStore.setRootDirectory(path) },
            colors = colors,
        )
    }

    // 卸载确认弹窗
    uninstallTarget?.let { skill ->
        UninstallSkillConfirmDialog(
            skill = skill,
            isDeleting = isOperating,
            onDismiss = { uninstallTarget = null },
            onConfirm = { skillStore.uninstall(skill.name) },
            colors = colors,
        )
    }
}

/**
 * 单个 Skill 行（紧凑版，参照 SkillManagementCard 的私有 SkillItemRow 自绘）。
 *
 * 名称（1 行省略）+ 兼容徽标（[MederiCompatBadge]，仅 compatibility 非空时）+ 描述
 * （2 行省略，仅 description 非空时）；右侧卸载按钮。
 */
@Composable
private fun OverviewSkillItemRow(
    skill: SkillItem,
    colors: MederiColors,
    onUninstall: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Skill 信息
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = skill.name,
                    color = colors.textPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                skill.compatibility?.takeIf { it.isNotBlank() }?.let { compat ->
                    MederiCompatBadge(text = compat)
                }
            }

            if (skill.description.isNotBlank()) {
                Text(
                    text = skill.description,
                    color = colors.textMuted,
                    fontSize = 9.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // 卸载按钮
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(MederiRadius.Square))
                .clickable { onUninstall() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = FeatherIcons.Trash2,
                contentDescription = stringResource(Res.string.skill_uninstall),
                tint = colors.textMuted,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}