package xyz.mederi.ui.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.dto.ConversationState
import xyz.mederi.core.contract.models.*
import xyz.mederi.theme.AppTheme
import xyz.mederi.ui.WorkspacePresenter
import xyz.mederi.ui.appstate.ProjectPermissionState
import xyz.mederi.ui.components.PermissionPlanSelectorContent
import xyz.mederi.ui.components.WorkspaceOperationStatusBar
import xyz.mederi.ui.components.WorkspaceSelectorContent

@OptIn(ExperimentalTestApi::class)
class WorkspaceControlsTest {

    private fun SemanticsNodeInteraction.assertWidthAtMost(max: Dp, density: Float): SemanticsNodeInteraction {
        assertTrue(fetchSemanticsNode().boundsInRoot.width <= max.value * density + 1f)
        return this
    }

    @Test
    fun selectsLocalWorktreeBranchCarryAndEnvironment() = runComposeUiTest {
        val selectedKinds = mutableListOf<WorkspaceKind>()
        val selectedBranches = mutableListOf<String?>()
        val carryToggles = mutableListOf<Boolean>()
        val selectedEnvs = mutableListOf<String?>()

        val branches = listOf(
            GitBranchInfo("main", "oid_main"),
            GitBranchInfo("feature/login", "oid_feature")
        )
        val repoInfo = RepositoryInfo(
            isGit = true,
            checkoutRoot = "/test/repo",
            headOid = "oid_main",
            branch = "main",
            branches = branches,
            dirty = true
        )
        val envs = listOf(
            ProjectEnvironment(id = "env_node", name = "Node.js Env")
        )

        var localText = ""
        var worktreeText = ""
        var branchText = ""
        var carryText = ""
        var envNodeText = ""

        setContent {
            AppTheme {
                localText = stringResource(Res.string.workspace_kind_local)
                worktreeText = stringResource(Res.string.workspace_kind_worktree)
                branchText = "feature/login"
                carryText = stringResource(Res.string.workspace_carry_local_changes)
                envNodeText = "Node.js Env"

                WorkspaceSelectorContent(
                    selection = WorkspaceSelection(
                        kind = WorkspaceKind.MANAGED_WORKTREE,
                        baseRef = "main",
                        carryLocalChanges = false,
                        environmentId = null
                    ),
                    repositoryInfo = repoInfo,
                    environments = envs,
                    isExistingConversation = false,
                    activeWorkspace = null,
                    onSelectKind = { selectedKinds.add(it) },
                    onSelectBaseRef = { selectedBranches.add(it) },
                    onToggleCarryLocalChanges = { carryToggles.add(it) },
                    onSelectEnvironmentId = { selectedEnvs.add(it) }
                )
            }
        }

        // 点击选择器按钮打开下拉
        onNodeWithText("main").performClick()

        // 验证 Worktree 展开后的元素
        onNodeWithText(worktreeText).assertIsDisplayed()
        onNodeWithText(localText).assertIsDisplayed()

        // 点击切换到 LOCAL
        onNodeWithText(localText).performScrollTo().performClick()
        runOnIdle {
            assertEquals(listOf(WorkspaceKind.LOCAL), selectedKinds.toList())
        }

        // 选择分支 feature/login
        onNodeWithText(branchText).performScrollTo().performClick()
        runOnIdle {
            assertEquals(listOf("feature/login"), selectedBranches.toList())
        }

        // 切换 carry local changes
        onNodeWithText(carryText).performScrollTo().performClick()
        runOnIdle {
            assertEquals(listOf(true), carryToggles.toList())
        }

        // 选择环境
        onNodeWithText(envNodeText).performScrollTo().performClick()
        runOnIdle {
            assertEquals(listOf("env_node"), selectedEnvs.toList())
        }
    }

    @Test
    fun keepsPermissionAndPlanControlsCompactAndIndependent() = runComposeUiTest {
        val state = ProjectPermissionState(projectId = "p1", settings = SandboxSettings())
        val executions = mutableListOf<ExecutionPermissionMode>()
        val plans = mutableListOf<AgentMode>()
        var iconLabel = ""
        var fullAccess = ""
        var manualApprove = ""
        var planTitle = ""
        var density = 1f

        setContent {
            AppTheme {
                density = LocalDensity.current.density
                fullAccess = stringResource(Res.string.settings_sandbox_full_access)
                manualApprove = stringResource(Res.string.mode_manual_approve)
                planTitle = stringResource(Res.string.plan_approval_settings_title)
                iconLabel = stringResource(
                    Res.string.permission_plan_button_description,
                    stringResource(Res.string.settings_sandbox_needs_approval),
                    stringResource(Res.string.mode_auto_approve)
                )

                Box(Modifier.width(320.dp)) {
                    PermissionPlanSelectorContent(
                        state = state,
                        planMode = AgentMode.AUTONOMOUS,
                        onExecution = { executions.add(it) },
                        onPlan = { plans.add(it) },
                        onReload = {},
                        onProjectSettings = {}
                    )
                }
            }
        }

        // 图标单按钮保持紧凑（不超过 48dp）
        onNodeWithContentDescription(iconLabel)
            .assertIsDisplayed()
            .assertWidthAtMost(48.dp, density)
            .performClick()

        // 计划组标题展示
        onNodeWithText(planTitle).assertIsDisplayed()

        // 点击更改执行权限为完全访问，计划模式不改变
        onNodeWithText(fullAccess).performClick()
        runOnIdle {
            assertEquals(listOf(ExecutionPermissionMode.FULL_ACCESS), executions)
            assertTrue(plans.isEmpty())
        }

        // 再次打开选择器，点击手动审批计划，执行权限不受影响
        onNodeWithContentDescription(iconLabel).performClick()
        onNodeWithText(manualApprove).performClick()
        runOnIdle {
            assertEquals(listOf(AgentMode.APPROVAL), plans)
            assertEquals(1, executions.size)
        }
    }

    @Test
    fun disablesUnavailableWorktreeWithConcreteReason() = runComposeUiTest {
        val selectedKinds = mutableListOf<WorkspaceKind>()
        var localText = ""
        var notGitReason = ""
        var noHeadReason = ""
        var unsupportedReason = ""

        // Case A: 不是 Git 仓库
        val nonGitRepo = RepositoryInfo(isGit = false)
        setContent {
            AppTheme {
                localText = stringResource(Res.string.workspace_kind_local)
                notGitReason = stringResource(Res.string.workspace_unavailable_not_git)
                WorkspaceSelectorContent(
                    selection = WorkspaceSelection(kind = WorkspaceKind.LOCAL),
                    repositoryInfo = nonGitRepo,
                    environments = emptyList(),
                    isExistingConversation = false,
                    activeWorkspace = null,
                    onSelectKind = { selectedKinds.add(it) },
                    onSelectBaseRef = {},
                    onToggleCarryLocalChanges = {},
                    onSelectEnvironmentId = {}
                )
            }
        }

        onNodeWithText(localText).performClick()
        onNodeWithText(notGitReason).assertIsDisplayed()

        // 点击禁用的 Worktree 不会触发 onSelectKind
        onNodeWithText(notGitReason).performClick()
        runOnIdle {
            assertTrue(selectedKinds.isEmpty())
        }

        // Case B: 是 Git 但无 HEAD
        selectedKinds.clear()
        val emptyRepo = RepositoryInfo(isGit = true, headOid = null)
        setContent {
            AppTheme {
                localText = stringResource(Res.string.workspace_kind_local)
                noHeadReason = stringResource(Res.string.workspace_unavailable_no_head)
                WorkspaceSelectorContent(
                    selection = WorkspaceSelection(kind = WorkspaceKind.LOCAL),
                    repositoryInfo = emptyRepo,
                    environments = emptyList(),
                    isExistingConversation = false,
                    activeWorkspace = null,
                    onSelectKind = { selectedKinds.add(it) },
                    onSelectBaseRef = {},
                    onToggleCarryLocalChanges = {},
                    onSelectEnvironmentId = {}
                )
            }
        }
        onNodeWithText(localText).performClick()
        onNodeWithText(noHeadReason).assertIsDisplayed()
        onNodeWithText(noHeadReason).performClick()
        runOnIdle {
            assertTrue(selectedKinds.isEmpty())
        }

        // Case C: 有明确不支持原因
        val unsupportedRepo = RepositoryInfo(
            isGit = true,
            headOid = "oid1",
            unsupportedReasons = listOf("Git submodule worktrees unsupported")
        )
        setContent {
            AppTheme {
                localText = stringResource(Res.string.workspace_kind_local)
                unsupportedReason = "Git submodule worktrees unsupported"
                WorkspaceSelectorContent(
                    selection = WorkspaceSelection(kind = WorkspaceKind.LOCAL),
                    repositoryInfo = unsupportedRepo,
                    environments = emptyList(),
                    isExistingConversation = false,
                    activeWorkspace = null,
                    onSelectKind = { selectedKinds.add(it) },
                    onSelectBaseRef = {},
                    onToggleCarryLocalChanges = {},
                    onSelectEnvironmentId = {}
                )
            }
        }
        onNodeWithText(localText).performClick()
        onNodeWithText(unsupportedReason).assertIsDisplayed()
        onNodeWithText(unsupportedReason).performClick()
        runOnIdle {
            assertTrue(selectedKinds.isEmpty())
        }
    }

    @Test
    fun showsPreparationApprovalFailureRetryAndCancel() = runComposeUiTest {
        var retries = 0
        var cancels = 0

        val basePlan = WorkspaceOperationPlan.CreatePlan(
            requestHash = "hash1",
            selection = WorkspaceSelection(),
            targetPath = "/tmp/ws1"
        )

        // 1. 运行中（COPY_CONTENT 阶段）
        val runningOp = WorkspaceOperation(
            id = "op1",
            projectId = "p1",
            sessionId = "s1",
            workspaceId = "ws1",
            kind = WorkspaceOperationKind.SETUP,
            state = WorkspaceOperationState.RUNNING,
            phase = WorkspacePhase.COPY_CONTENT,
            idempotencyKey = "k1",
            requestHash = "h1",
            createdAt = "2026-11-02T10:00:00Z",
            updatedAt = "2026-11-02T10:00:00Z",
            operationPlan = basePlan
        )

        var copyingText = ""
        var cancelText = ""
        var retryText = ""
        var waitingApprovalText = ""
        var failedText = ""
        var cancellingText = ""

        setContent {
            AppTheme {
                copyingText = stringResource(Res.string.workspace_status_copying)
                cancelText = stringResource(Res.string.workspace_action_cancel)
                WorkspaceOperationStatusBar(
                    operation = runningOp,
                    onRetry = { retries++ },
                    onCancel = { cancels++ }
                )
            }
        }

        onNodeWithText(copyingText).assertIsDisplayed()
        onNodeWithText(cancelText).assertIsDisplayed().performClick()
        runOnIdle {
            assertEquals(1, cancels)
        }

        // 2. 等待审批中
        val waitingOp = runningOp.copy(state = WorkspaceOperationState.WAITING_APPROVAL)
        setContent {
            AppTheme {
                waitingApprovalText = stringResource(Res.string.workspace_status_waiting_approval)
                WorkspaceOperationStatusBar(
                    operation = waitingOp,
                    onRetry = { retries++ },
                    onCancel = { cancels++ }
                )
            }
        }
        onNodeWithText(waitingApprovalText).assertIsDisplayed()

        // 3. 失败并提供重试与取消
        val failedOp = runningOp.copy(
            state = WorkspaceOperationState.FAILED,
            error = WorkspaceError(WorkspaceErrorCode.SETUP_FAILED, "Disk space full")
        )
        setContent {
            AppTheme {
                failedText = stringResource(Res.string.workspace_status_failed)
                retryText = stringResource(Res.string.workspace_action_retry)
                cancelText = stringResource(Res.string.workspace_action_cancel)
                WorkspaceOperationStatusBar(
                    operation = failedOp,
                    onRetry = { retries++ },
                    onCancel = { cancels++ }
                )
            }
        }
        onNodeWithText("$failedText: Disk space full").assertIsDisplayed()
        onNodeWithText(retryText).assertIsDisplayed().performClick()
        runOnIdle {
            assertEquals(1, retries)
        }

        // 4. 取消并恢复中
        val cancellingOp = runningOp.copy(state = WorkspaceOperationState.CANCELLING, cancelRequested = true)
        setContent {
            AppTheme {
                cancellingText = stringResource(Res.string.workspace_action_cancelling)
                WorkspaceOperationStatusBar(
                    operation = cancellingOp,
                    onRetry = { retries++ },
                    onCancel = { cancels++ }
                )
            }
        }
        onNodeWithText(cancellingText).assertIsDisplayed()
    }

    @Test
    fun neverOptimisticallyChangesWorkspaceIdentity() = runComposeUiTest {
        val presenter = WorkspacePresenter()

        val sampleWorkspace = Workspace(
            id = "ws_local",
            projectId = "p1",
            kind = WorkspaceKind.LOCAL,
            checkoutRoot = "/test/p1",
            projectRelativePath = "",
            state = WorkspaceState.READY,
            generation = 1L,
            createdAt = "2026-11-02T10:00:00Z",
            lastUsedAt = "2026-11-02T10:00:00Z"
        )
        val initialInfo = WorkspaceInfo(
            workspace = sampleWorkspace,
            headOid = "commit_1",
            branch = "main",
            dirty = false,
            capabilities = setOf(WorkspaceCapability.OPEN_FILES)
        )
        val state = ConversationState(
            conversation = Conversation(
                id = "conv_1",
                projectId = "p1",
                title = "Test Conv",
                status = ConversationStatus.Idle,
                createdAt = 1700000000000L,
                updatedAt = 1700000000000L,
                agent = "AUTONOMOUS"
            ),
            messages = emptyList(),
            tokenUsage = TokenUsage(),
            cost = CostSummary(),
            workspaceInfo = ConversationWorkspaceInfo(active = initialInfo)
        )

        // 初始已有对话生效的是本地工作区
        val resolved = presenter.resolveActiveWorkspace("conv_1", state)
        assertNotNull(resolved)
        assertEquals(WorkspaceKind.LOCAL, resolved.workspace.kind)

        // 改变草稿配置并不能改变已有对话的身份
        presenter.updateDraftKind(WorkspaceKind.MANAGED_WORKTREE)
        presenter.updateDraftBaseRef("develop")

        val stillResolved = presenter.resolveActiveWorkspace("conv_1", state)
        assertNotNull(stillResolved)
        assertEquals(WorkspaceKind.LOCAL, stillResolved.workspace.kind)

        var handoffOpened = false
        var handoffText = ""
        var localText = ""

        setContent {
            AppTheme {
                localText = stringResource(Res.string.workspace_kind_local)
                handoffText = stringResource(Res.string.workspace_open_handoff)
                WorkspaceSelectorContent(
                    selection = presenter.draftSelection,
                    repositoryInfo = null,
                    environments = emptyList(),
                    isExistingConversation = true,
                    activeWorkspace = stillResolved,
                    onSelectKind = presenter::updateDraftKind,
                    onSelectBaseRef = presenter::updateDraftBaseRef,
                    onToggleCarryLocalChanges = presenter::updateDraftCarryLocalChanges,
                    onSelectEnvironmentId = presenter::updateDraftEnvironmentId,
                    onOpenHandoff = { handoffOpened = true }
                )
            }
        }

        // 点击已有对话的选择器，显示迁移提示并引导至 Handoff，绝不直接切换
        onNodeWithText(localText).performClick()
        onNodeWithText(handoffText).assertIsDisplayed().performClick()
        runOnIdle {
            assertTrue(handoffOpened)
            // 真实工作区依然未改变
            assertEquals(WorkspaceKind.LOCAL, presenter.resolveActiveWorkspace("conv_1", state)?.workspace?.kind)
        }
    }
}
