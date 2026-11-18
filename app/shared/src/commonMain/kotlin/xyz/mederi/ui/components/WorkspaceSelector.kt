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
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.GitBranch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.ProjectEnvironment
import xyz.mederi.core.contract.models.RepositoryInfo
import xyz.mederi.core.contract.models.WorkspaceInfo
import xyz.mederi.core.contract.models.WorkspaceKind
import xyz.mederi.core.contract.models.WorkspaceSelection
import xyz.mederi.core.contract.models.WorkspaceState
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiRadius
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.theme.WorkspaceStyle
import xyz.mederi.ui.WorkspaceViewModel
import xyz.mederi.ui.resolveWorktreeBaseBranch
import xyz.mederi.ui.components.atoms.WorkspaceDropdownMenu
import xyz.mederi.ui.components.atoms.WorkspaceMenuItem
import xyz.mederi.ui.components.atoms.WorkspaceTextField
import xyz.mederi.ui.components.atoms.WorkspaceTooltipPopup

private const val WorkspaceRefreshIntervalMillis = 15_000L

/**
 * 工作区选择器。检出状态来自宿主探测，草稿只保留显式创建选项。
 */
@Composable
fun WorkspaceSelector(
    viewModel: WorkspaceViewModel,
    modifier: Modifier = Modifier
) {
    val snapshot = viewModel.snapshot
    val conversationId = viewModel.conversationId
    val presenter = viewModel.workspacePresenter
    val selectedProjectId by viewModel.selectedProjectId.collectAsState()
    val conversation = snapshot?.conversation?.takeIf { it.id == conversationId }
    val projectId = if (conversationId == null) selectedProjectId else conversation?.projectId
    val boundWorkspace = presenter.resolveActiveWorkspace(conversationId, snapshot)
    val workspaceId = conversation?.workspaceId ?: boundWorkspace?.workspace?.id
    val generation = boundWorkspace?.workspace?.generation
    val workspaceState = boundWorkspace?.workspace?.state
    // 旧会话未绑定工作区时，执行端会使用项目目录；有工作区 ID 的会话不能借用项目分支。
    val usesProjectCheckout = conversationId == null ||
        (conversation?.projectId != null && workspaceId == null && conversation.directory == null)
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    var refreshRequest by remember { mutableIntStateOf(0) }
    var repo by remember(viewModel, projectId) { mutableStateOf<RepositoryInfo?>(null) }
    var repoChecked by remember(viewModel, projectId) { mutableStateOf(false) }
    var environments by remember(viewModel, projectId) { mutableStateOf(emptyList<ProjectEnvironment>()) }
    var liveWorkspace by remember(viewModel, conversationId, projectId, workspaceId, generation, workspaceState) {
        mutableStateOf<WorkspaceInfo?>(null)
    }
    var workspaceChecked by remember(viewModel, conversationId, projectId, workspaceId, generation, workspaceState) {
        mutableStateOf(false)
    }

    LaunchedEffect(viewModel, projectId, usesProjectCheckout, windowFocused, refreshRequest) {
        if (projectId == null) return@LaunchedEffect
        do {
            val detected = viewModel.readWorkspaceRepository(projectId)
            currentCoroutineContext().ensureActive()
            if (detected != null) repo = detected
            repoChecked = true
            if (!windowFocused || !usesProjectCheckout) break
            delay(WorkspaceRefreshIntervalMillis)
        } while (true)
    }
    LaunchedEffect(viewModel, projectId, refreshRequest) {
        if (projectId == null) return@LaunchedEffect
        val detected = viewModel.readWorkspaceEnvironments(projectId)
        currentCoroutineContext().ensureActive()
        environments = detected
    }
    LaunchedEffect(viewModel, conversationId, projectId, workspaceId, generation, workspaceState, usesProjectCheckout, windowFocused, refreshRequest) {
        if (conversationId == null) return@LaunchedEffect
        do {
            val detected = viewModel.readConversationWorkspace(conversationId)
            currentCoroutineContext().ensureActive()
            if (detected != null &&
                (projectId == null || detected.workspace.projectId == projectId) &&
                (workspaceId == null || detected.workspace.id == workspaceId) &&
                (generation == null || detected.workspace.generation >= generation)
            ) {
                liveWorkspace = detected
            }
            workspaceChecked = true
            if (!windowFocused || usesProjectCheckout) break
            delay(WorkspaceRefreshIntervalMillis)
        } while (true)
    }

    val detectionPending = if (usesProjectCheckout) projectId != null && !repoChecked else !workspaceChecked

    WorkspaceSelectorContent(
        selection = presenter.draftSelection,
        repositoryInfo = repo,
        environments = environments,
        isExistingConversation = conversationId != null,
        activeWorkspace = liveWorkspace ?: boundWorkspace,
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
        modifier = modifier,
        detectionPending = detectionPending,
        hasProject = projectId != null,
        usesProjectCheckout = usesProjectCheckout,
        onOpen = { refreshRequest++ },
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
    modifier: Modifier = Modifier,
    detectionPending: Boolean = false,
    hasProject: Boolean = repositoryInfo != null,
    usesProjectCheckout: Boolean = !isExistingConversation,
    onOpen: () -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()

    val projectDirectoryLabel = stringResource(Res.string.workspace_kind_local)
    val worktreeLabel = stringResource(Res.string.workspace_kind_worktree)
    val kind = activeWorkspace?.workspace?.kind ?: if (isExistingConversation) WorkspaceKind.LOCAL else selection.kind
    val kindLabel = if (kind == WorkspaceKind.LOCAL) projectDirectoryLabel else worktreeLabel
    val localLabel = stringResource(Res.string.workspace_selector_local)
    val detectingLabel = stringResource(Res.string.workspace_selector_detecting)
    val unavailableLabel = stringResource(Res.string.workspace_selector_unavailable)
    val preparing = activeWorkspace?.workspace?.state in setOf(WorkspaceState.REQUESTED, WorkspaceState.PREPARING)
    val projectRepo = repositoryInfo?.takeIf { activeWorkspace == null && usesProjectCheckout }
    val branch = when {
        activeWorkspace != null -> activeWorkspace.branch
        !isExistingConversation && kind == WorkspaceKind.MANAGED_WORKTREE && projectRepo?.isGit == true ->
            selection.baseRef ?: projectRepo.branch
        else -> projectRepo?.branch
    }
    val commit = (activeWorkspace?.headOid ?: projectRepo?.headOid)
        ?.takeIf { it.isNotBlank() }?.take(7)
    val shortLabel = when {
        branch != null -> branch
        commit != null -> commit
        preparing -> stringResource(Res.string.workspace_status_preparing)
        isExistingConversation && activeWorkspace?.workspace?.kind == WorkspaceKind.LOCAL -> localLabel
        projectRepo?.isGit == false || (!isExistingConversation && !hasProject) -> localLabel
        detectionPending -> detectingLabel
        else -> unavailableLabel
    }
    val location = when {
        isExistingConversation && activeWorkspace != null && (branch != null || commit != null) ->
            workspaceLocationLabel(activeWorkspace, repositoryInfo)
        branch != null -> stringResource(Res.string.workspace_kind_with_branch, kindLabel, branch)
        commit != null -> stringResource(Res.string.workspace_location_detached_commit, kindLabel, commit)
        else -> shortLabel
    }

    val tooltipText = if (isExistingConversation) location else {
        val env = environments.firstOrNull { it.id == selection.environmentId }?.name
            ?: stringResource(Res.string.workspace_environment_none)
        val carry = if (selection.carryLocalChanges) stringResource(Res.string.workspace_carry_local_changes) else null
        listOfNotNull(location, env, carry).joinToString(" · ")
    }

    val accessibleDescription = stringResource(Res.string.workspace_selector_description, location)

    Box(modifier = modifier.hoverable(source)) {
        ContextToolChip(
            icon = FeatherIcons.GitBranch,
            label = shortLabel,
            onClick = {
                expanded = !expanded
                if (expanded) onOpen()
            },
            modifier = Modifier
                .widthIn(max = 140.dp)
                .onFocusChanged { focused = it.hasFocus }
                .semantics { contentDescription = accessibleDescription },
        )

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
                },
                location = location,
                detectionPending = detectionPending,
                hasProject = hasProject,
            )
        }
    }
}

@Composable
private fun workspaceLocationLabel(activeWorkspace: WorkspaceInfo, repositoryInfo: RepositoryInfo?): String {
    val kind = stringResource(if (activeWorkspace.workspace.kind == WorkspaceKind.LOCAL) {
        Res.string.workspace_kind_local
    } else {
        Res.string.workspace_kind_worktree
    })
    val baseBranch = resolveWorktreeBaseBranch(activeWorkspace, repositoryInfo)
    val shortCommit = activeWorkspace.headOid?.takeIf { it.isNotBlank() }?.take(7)
    return when {
        activeWorkspace.branch != null -> stringResource(Res.string.workspace_kind_with_branch, kind, activeWorkspace.branch)
        shortCommit != null && baseBranch != null ->
            stringResource(Res.string.workspace_location_detached_commit_base, kind, shortCommit, baseBranch)
        shortCommit != null -> stringResource(Res.string.workspace_location_detached_commit, kind, shortCommit)
        else -> kind
    }
}

@Composable
internal fun WorkspaceSelectorDropdownContent(
    selection: WorkspaceSelection,
    repositoryInfo: RepositoryInfo?,
    environments: List<ProjectEnvironment>,
    isExistingConversation: Boolean,
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
    location: String,
    detectionPending: Boolean,
    hasProject: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = LocalMederiColors.current
    var branchQuery by remember { mutableStateOf("") }

    val isGit = repositoryInfo?.isGit == true
    val hasHead = repositoryInfo?.headOid != null
    val unsupportedReasons = repositoryInfo?.unsupportedReasons ?: emptyList()

    val worktreeUnavailableReason = when {
        detectionPending && repositoryInfo == null -> stringResource(Res.string.workspace_selector_detecting)
        hasProject && repositoryInfo == null -> stringResource(Res.string.workspace_selector_unavailable)
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
                text = location,
                style = WorkspaceStyle.Body,
                color = colors.textPrimary,
                modifier = Modifier.padding(horizontal = MederiSpacing.MD)
            )
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
                            repositoryInfo?.branch?.let { stringResource(Res.string.workspace_local_branch_desc, it) }
                                ?: stringResource(Res.string.workspace_local_desc),
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

                val candidateBranches = repositoryInfo?.branches.orEmpty().map { it.name }
                    .ifEmpty { listOfNotNull(repositoryInfo?.branch) }
                val filteredBranches = candidateBranches.filter {
                    branchQuery.isBlank() || it.contains(branchQuery, ignoreCase = true)
                }

                filteredBranches.forEach { branchName ->
                    val isSelected = (selection.baseRef ?: repositoryInfo?.branch) == branchName
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
