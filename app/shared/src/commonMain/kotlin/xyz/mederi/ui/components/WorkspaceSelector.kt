package xyz.mederi.ui.components

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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.GitBranch
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.ProjectEnvironment
import xyz.mederi.core.contract.models.RepositoryInfo
import xyz.mederi.core.contract.models.WorkspaceInfo
import xyz.mederi.core.contract.models.WorkspaceKind
import xyz.mederi.core.contract.models.WorkspaceSelection
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiRadius
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.theme.WorkspaceStyle
import xyz.mederi.ui.WorkspaceViewModel
import xyz.mederi.ui.components.atoms.WorkspaceDropdownMenu
import xyz.mederi.ui.components.atoms.WorkspaceMenuItem
import xyz.mederi.ui.components.atoms.WorkspaceTextField
import xyz.mederi.ui.components.atoms.WorkspaceTooltipPopup

/**
 * 工作区选择器：GitBranch + Local/Worktree 短标签。
 * 桌面高度对齐 ControlHeight，宽度最多 140dp，标签单行省略，tooltip 显示完整环境与分支信息。
 */
@Composable
fun WorkspaceSelector(
    viewModel: WorkspaceViewModel,
    modifier: Modifier = Modifier
) {
    val snapshot = viewModel.snapshot
    val conversationId = viewModel.conversationId
    val isExisting = conversationId != null && snapshot?.conversation?.id == conversationId
    val presenter = viewModel.workspacePresenter
    val draft = presenter.draftSelection
    val activeWorkspace = presenter.resolveActiveWorkspace(conversationId, snapshot)
    val repo = viewModel.repositoryInfo
    val environments = viewModel.projectEnvironments

    WorkspaceSelectorContent(
        selection = draft,
        repositoryInfo = repo,
        environments = environments,
        isExistingConversation = isExisting,
        activeWorkspace = activeWorkspace,
        onSelectKind = presenter::updateDraftKind,
        onSelectBaseRef = presenter::updateDraftBaseRef,
        onToggleCarryLocalChanges = presenter::updateDraftCarryLocalChanges,
        onSelectEnvironmentId = presenter::updateDraftEnvironmentId,
        onOpenHandoff = { viewModel.openHandoffDialog() },
        onOpenManagement = { viewModel.openManagement() },
        onOpenArchiveList = { viewModel.openArchiveList() },
        onOpenEnvironmentEditor = { viewModel.openEnvironmentEditor() },
        onOpenWorktreeSettings = { viewModel.openWorktreeSettings() },
        onOpenCreateBranch = { viewModel.openCreateBranchDialog() },
        modifier = modifier
    )
}

@Composable
fun WorkspaceSelectorContent(
    selection: WorkspaceSelection,
    repositoryInfo: RepositoryInfo?,
    environments: List<ProjectEnvironment>,
    isExistingConversation: Boolean,
    activeWorkspace: WorkspaceInfo?,
    onSelectKind: (WorkspaceKind) -> Unit,
    onSelectBaseRef: (String?) -> Unit,
    onToggleCarryLocalChanges: (Boolean) -> Unit,
    onSelectEnvironmentId: (String?) -> Unit,
    onOpenHandoff: () -> Unit = {},
    onOpenManagement: () -> Unit = {},
    onOpenArchiveList: () -> Unit = {},
    onOpenEnvironmentEditor: () -> Unit = {},
    onOpenWorktreeSettings: () -> Unit = {},
    onOpenCreateBranch: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val colors = LocalMederiColors.current

    val shortLabel = if (isExistingConversation && activeWorkspace != null) {
        if (activeWorkspace.workspace.kind == WorkspaceKind.LOCAL) {
            stringResource(Res.string.workspace_kind_local)
        } else {
            activeWorkspace.branch ?: activeWorkspace.workspace.taskBranchName ?: stringResource(Res.string.workspace_kind_worktree)
        }
    } else {
        if (selection.kind == WorkspaceKind.LOCAL) {
            stringResource(Res.string.workspace_kind_local)
        } else {
            selection.baseRef ?: stringResource(Res.string.workspace_kind_worktree)
        }
    }

    val tooltipText = remember(isExistingConversation, activeWorkspace, selection, repositoryInfo) {
        if (isExistingConversation && activeWorkspace != null) {
            val kindStr = if (activeWorkspace.workspace.kind == WorkspaceKind.LOCAL) "Local" else "Worktree"
            val branchStr = activeWorkspace.branch ?: activeWorkspace.workspace.taskBranchName ?: "HEAD"
            "$kindStr ($branchStr)"
        } else {
            val kindStr = if (selection.kind == WorkspaceKind.LOCAL) "Local" else "Worktree"
            val branchStr = selection.baseRef ?: repositoryInfo?.branch ?: "HEAD"
            val envStr = selection.environmentId ?: "default"
            val carryStr = if (selection.carryLocalChanges) "with uncommitted changes" else "clean"
            "$kindStr ($branchStr, $carryStr) - env: $envStr"
        }
    }

    val accessibleDescription = stringResource(Res.string.workspace_selector_description, shortLabel)

    Box(modifier = modifier.hoverable(source)) {
        Surface(
            onClick = { expanded = !expanded },
            modifier = Modifier
                .height(WorkspaceStyle.ControlHeight)
                .widthIn(max = 140.dp)
                .onFocusChanged { focused = it.hasFocus }
                .semantics { contentDescription = accessibleDescription },
            shape = RoundedCornerShape(MederiRadius.Control),
            color = colors.surfaceInput,
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.surfaceCardBorder)
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = FeatherIcons.GitBranch,
                    contentDescription = null,
                    tint = colors.textSecondary,
                    modifier = Modifier.size(WorkspaceStyle.IconSize)
                )
                Text(
                    text = shortLabel,
                    style = WorkspaceStyle.Body.copy(fontSize = 12.sp),
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
        }

        if ((hovered || focused) && !expanded) {
            WorkspaceTooltipPopup(tooltipText, onDismiss = { focused = false })
        }

        WorkspaceDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 260.dp, max = 320.dp)
        ) {
            WorkspaceSelectorDropdownContent(
                selection = selection,
                repositoryInfo = repositoryInfo,
                environments = environments,
                isExistingConversation = isExistingConversation,
                activeWorkspace = activeWorkspace,
                onSelectKind = { onSelectKind(it) },
                onSelectBaseRef = { onSelectBaseRef(it) },
                onToggleCarryLocalChanges = { onToggleCarryLocalChanges(it) },
                onSelectEnvironmentId = { onSelectEnvironmentId(it) },
                onOpenHandoff = {
                    expanded = false
                    onOpenHandoff()
                },
                onOpenManagement = {
                    expanded = false
                    onOpenManagement()
                },
                onOpenArchiveList = {
                    expanded = false
                    onOpenArchiveList()
                },
                onOpenEnvironmentEditor = {
                    expanded = false
                    onOpenEnvironmentEditor()
                },
                onOpenWorktreeSettings = {
                    expanded = false
                    onOpenWorktreeSettings()
                },
                onOpenCreateBranch = {
                    expanded = false
                    onOpenCreateBranch()
                }
            )
        }
    }
}

@Composable
internal fun WorkspaceSelectorDropdownContent(
    selection: WorkspaceSelection,
    repositoryInfo: RepositoryInfo?,
    environments: List<ProjectEnvironment>,
    isExistingConversation: Boolean,
    activeWorkspace: WorkspaceInfo?,
    onSelectKind: (WorkspaceKind) -> Unit,
    onSelectBaseRef: (String?) -> Unit,
    onToggleCarryLocalChanges: (Boolean) -> Unit,
    onSelectEnvironmentId: (String?) -> Unit,
    onOpenHandoff: () -> Unit,
    onOpenManagement: () -> Unit,
    onOpenArchiveList: () -> Unit,
    onOpenEnvironmentEditor: () -> Unit = {},
    onOpenWorktreeSettings: () -> Unit = {},
    onOpenCreateBranch: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val colors = LocalMederiColors.current
    var branchQuery by remember { mutableStateOf("") }

    val isGit = repositoryInfo?.isGit == true
    val hasHead = repositoryInfo?.headOid != null
    val unsupportedReasons = repositoryInfo?.unsupportedReasons ?: emptyList()

    val worktreeUnavailableReason = when {
        !isGit -> stringResource(Res.string.workspace_unavailable_not_git)
        !hasHead -> stringResource(Res.string.workspace_unavailable_no_head)
        unsupportedReasons.isNotEmpty() -> unsupportedReasons.first()
        else -> null
    }
    val worktreeEnabled = worktreeUnavailableReason == null

    Column(
        modifier = modifier
            .padding(vertical = MederiSpacing.XS)
            .heightIn(max = 480.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(MederiSpacing.XS)
    ) {
        Text(
            text = stringResource(Res.string.workspace_selector_title),
            style = WorkspaceStyle.Title,
            color = colors.textPrimary,
            modifier = Modifier.padding(horizontal = MederiSpacing.MD, vertical = MederiSpacing.XS)
        )

        if (isExistingConversation) {
            Text(
                text = stringResource(Res.string.workspace_existing_conversation_note),
                style = WorkspaceStyle.Caption,
                color = colors.textMuted,
                modifier = Modifier.padding(horizontal = MederiSpacing.MD)
            )
            WorkspaceMenuItem(
                text = { Text(stringResource(Res.string.workspace_open_handoff)) },
                onClick = onOpenHandoff
            )
            WorkspaceMenuItem(
                text = { Text(stringResource(Res.string.workspace_management_title)) },
                onClick = onOpenManagement
            )
            WorkspaceMenuItem(
                text = { Text(stringResource(Res.string.workspace_archived_list_title)) },
                onClick = onOpenArchiveList
            )
            WorkspaceMenuItem(
                text = { Text(stringResource(Res.string.workspace_env_edit)) },
                onClick = onOpenEnvironmentEditor
            )
            WorkspaceMenuItem(
                text = { Text(stringResource(Res.string.workspace_worktree_settings)) },
                onClick = onOpenWorktreeSettings
            )
            WorkspaceMenuItem(
                text = { Text(stringResource(Res.string.workspace_create_branch)) },
                onClick = onOpenCreateBranch
            )
        } else {
            // 1. 位置选择：Local vs Worktree
            WorkspaceMenuItem(
                text = {
                    Column {
                        Text(stringResource(Res.string.workspace_kind_local))
                        Text(
                            stringResource(Res.string.workspace_local_desc),
                            style = WorkspaceStyle.Caption,
                            color = colors.textMuted
                        )
                    }
                },
                selected = selection.kind == WorkspaceKind.LOCAL,
                twoLines = true,
                onClick = { onSelectKind(WorkspaceKind.LOCAL) }
            )

            WorkspaceMenuItem(
                text = {
                    Column {
                        Text(
                            text = stringResource(Res.string.workspace_kind_worktree),
                            color = if (worktreeEnabled) colors.textPrimary else colors.textMuted
                        )
                        Text(
                            text = worktreeUnavailableReason ?: stringResource(Res.string.workspace_worktree_desc),
                            style = WorkspaceStyle.Caption,
                            color = if (worktreeEnabled) colors.textMuted else colors.accentDanger
                        )
                    }
                },
                selected = selection.kind == WorkspaceKind.MANAGED_WORKTREE,
                twoLines = true,
                onClick = {
                    if (worktreeEnabled) {
                        onSelectKind(WorkspaceKind.MANAGED_WORKTREE)
                    }
                }
            )

            // 2. 基线分支搜索（仅 Worktree）
            if (selection.kind == WorkspaceKind.MANAGED_WORKTREE) {
                HorizontalDivider(color = colors.divider)
                Text(
                    text = stringResource(Res.string.workspace_branch_label),
                    style = WorkspaceStyle.Title,
                    color = colors.textPrimary,
                    modifier = Modifier.padding(horizontal = MederiSpacing.MD, vertical = MederiSpacing.XS)
                )

                WorkspaceTextField(
                    value = branchQuery,
                    onValueChange = { branchQuery = it },
                    placeholder = stringResource(Res.string.workspace_branch_search_placeholder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MederiSpacing.MD)
                )

                val branches = repositoryInfo?.branches ?: emptyList()
                val candidateBranches = if (branches.isEmpty()) {
                    listOfNotNull(repositoryInfo?.branch ?: "main")
                } else {
                    branches.map { it.name }
                }
                val filteredBranches = candidateBranches.filter {
                    branchQuery.isBlank() || it.contains(branchQuery, ignoreCase = true)
                }

                filteredBranches.forEach { branchName ->
                    val isSelected = (selection.baseRef ?: repositoryInfo?.branch ?: "main") == branchName
                    WorkspaceMenuItem(
                        text = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(branchName)
                                if (isSelected) {
                                    Icon(
                                        imageVector = FeatherIcons.Check,
                                        contentDescription = null,
                                        tint = colors.accentPrimary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        },
                        selected = isSelected,
                        onClick = { onSelectBaseRef(branchName) }
                    )
                }

                // 3. 携带本地修改
                HorizontalDivider(color = colors.divider)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(MederiRadius.Control))
                        .clickable { onToggleCarryLocalChanges(!selection.carryLocalChanges) }
                        .padding(horizontal = MederiSpacing.MD, vertical = MederiSpacing.SM),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MederiSpacing.SM)
                ) {
                    Checkbox(
                        checked = selection.carryLocalChanges,
                        onCheckedChange = { onToggleCarryLocalChanges(it) },
                        colors = CheckboxDefaults.colors(
                            checkedColor = colors.accentPrimary,
                            checkmarkColor = colors.onAccentPrimary
                        )
                    )
                    Text(
                        text = stringResource(Res.string.workspace_carry_local_changes),
                        style = WorkspaceStyle.Body,
                        color = colors.textPrimary
                    )
                }

                // 4. 环境配置选择
                HorizontalDivider(color = colors.divider)
                Text(
                    text = stringResource(Res.string.workspace_environment_label),
                    style = WorkspaceStyle.Title,
                    color = colors.textPrimary,
                    modifier = Modifier.padding(horizontal = MederiSpacing.MD, vertical = MederiSpacing.XS)
                )

                WorkspaceMenuItem(
                    text = { Text(stringResource(Res.string.workspace_environment_none)) },
                    selected = selection.environmentId == null,
                    onClick = { onSelectEnvironmentId(null) }
                )

                environments.forEach { env ->
                    WorkspaceMenuItem(
                        text = { Text(env.name) },
                        selected = selection.environmentId == env.id,
                        onClick = { onSelectEnvironmentId(env.id) }
                    )
                }

                // 5. 完整路径只读说明
                HorizontalDivider(color = colors.divider)
                Text(
                    text = stringResource(Res.string.workspace_path_label),
                    style = WorkspaceStyle.Title,
                    color = colors.textPrimary,
                    modifier = Modifier.padding(horizontal = MederiSpacing.MD, vertical = MederiSpacing.XS)
                )
                val previewPath = repositoryInfo?.checkoutRoot?.let { "$it/worktrees/..." } ?: "(auto-managed)"
                Text(
                    text = previewPath,
                    style = WorkspaceStyle.Caption,
                    color = colors.textMuted,
                    modifier = Modifier.padding(horizontal = MederiSpacing.MD, vertical = MederiSpacing.XS)
                )
            }
        }
    }
}
