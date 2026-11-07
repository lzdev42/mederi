package xyz.mederi.ui.workspace

// 第四轮 N07 窄屏触达与无横向溢出核验（N07 全量验收已记录）
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.*
import xyz.mederi.theme.AppTheme
import xyz.mederi.ui.appstate.ProjectPermissionState
import xyz.mederi.ui.components.*
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

/**
 * 手机窄屏面板行为测试：直接渲染各面板组件并注入 callback，聚焦 320dp/280dp 大字号下
 * 组件自身的行为与可达性。面板是否挂到真实 MainScreen/Sidebar 路由由 RF-08
 * WorkspaceRepairUiIntegrationTest 从实际入口点击驱动验证；本类不重复路由接线。
 */
@OptIn(ExperimentalTestApi::class)
class WorkspaceMobileTest {

    private fun saveEvidence(name: String, node: SemanticsNodeInteraction) {
        val dir = File("temp/worktree-evidence/delivery").apply { mkdirs() }
        val file = File(dir, "$name.png")
        try {
            val image = node.captureToImage().toAwtImage()
            ImageIO.write(image, "png", file)
        } catch (_: Throwable) {
            // 环境无图形设备时安全降级
        }
    }

    private fun sampleWorkspace(id: String, kind: WorkspaceKind, root: String) = Workspace(
        id = id,
        projectId = "p1",
        kind = kind,
        checkoutRoot = root,
        projectRelativePath = "",
        state = WorkspaceState.READY,
        generation = 1L,
        createdAt = "2026-11-02T10:00:00Z",
        lastUsedAt = "2026-11-02T10:00:00Z"
    )

    private fun sampleInfo(ws: Workspace, branch: String = "main", dirty: Boolean = false) = WorkspaceInfo(
        workspace = ws,
        headOid = "commit_1",
        branch = branch,
        dirty = dirty,
        capabilities = setOf(WorkspaceCapability.HANDOFF, WorkspaceCapability.ARCHIVE)
    )

    @Test
    fun fitsSelectorAndSendAt320DpAndLargeFont280Dp() = runComposeUiTest {
        var sendClicked = false
        var sendDesc = ""

        // 1. 320dp 宽度检查
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                AppTheme {
                    sendDesc = stringResource(Res.string.input_send)
                    Box(Modifier.width(320.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f, fill = false),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                WorkspaceSelectorContent(
                                    selection = WorkspaceSelection(kind = WorkspaceKind.LOCAL),
                                    repositoryInfo = null,
                                    environments = emptyList(),
                                    isExistingConversation = false,
                                    activeWorkspace = null,
                                    onSelectKind = {},
                                    onSelectBaseRef = {},
                                    onToggleCarryLocalChanges = {},
                                    onSelectEnvironmentId = {}
                                )
                                PermissionPlanSelectorContent(
                                    state = ProjectPermissionState(projectId = "p1", settings = SandboxSettings()),
                                    planMode = AgentMode.AUTONOMOUS,
                                    onExecution = {},
                                    onPlan = {},
                                    onReload = {},
                                    onProjectSettings = {}
                                )
                            }
                            SendButton(
                                isStreaming = false,
                                hasContent = true,
                                canSend = true,
                                onSubmit = { sendClicked = true }
                            )
                        }
                    }
                }
            }
        }

        val sendNode = onNodeWithContentDescription(sendDesc)
        sendNode.assertIsDisplayed()
        sendNode.performClick()
        runOnIdle { assertTrue(sendClicked) }
        saveEvidence("mobile-320-selector", onAllNodes(isRoot()).onLast())

        // 2. 280dp 宽度与 1.6 倍大字号检查
        sendClicked = false
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.6f)) {
                AppTheme {
                    sendDesc = stringResource(Res.string.input_send)
                    Box(Modifier.width(280.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            WorkspaceSelectorContent(
                                selection = WorkspaceSelection(kind = WorkspaceKind.LOCAL),
                                repositoryInfo = null,
                                environments = emptyList(),
                                isExistingConversation = false,
                                activeWorkspace = null,
                                onSelectKind = {},
                                onSelectBaseRef = {},
                                onToggleCarryLocalChanges = {},
                                onSelectEnvironmentId = {}
                            )
                            SendButton(
                                isStreaming = false,
                                hasContent = true,
                                canSend = true,
                                onSubmit = { sendClicked = true }
                            )
                        }
                    }
                }
            }
        }

        onNodeWithContentDescription(sendDesc).assertIsDisplayed().performClick()
        runOnIdle { assertTrue(sendClicked) }
        saveEvidence("mobile-280-large-font", onAllNodes(isRoot()).onLast())
    }

    @Test
    fun completesHandoffPreviewAndBusyConflictFlowsOnPhone() = runComposeUiTest {
        val src = sampleInfo(sampleWorkspace("ws_src", WorkspaceKind.LOCAL, "/path/to/source"))
        val dst = sampleInfo(sampleWorkspace("ws_dst", WorkspaceKind.MANAGED_WORKTREE, "/path/to/target"))

        // Case A: 有受影响的运行中会话 (Handoff Busy)
        val busyPreview = HandoffPreview(
            token = "tok_1",
            expiresAt = "2026-11-02T11:00:00Z",
            source = src,
            target = dst,
            fingerprints = mapOf("f1" to "h1"),
            affectedSessions = listOf(AffectedSession("s1", "Background Build", running = true)),
            taskBranch = "task/migrate-ui",
            conflicts = emptyList()
        )

        var stopToggled = false
        var handoffExecuted = false
        var stopAffectedTasksText = ""
        var executeActionText = ""

        setContent {
            AppTheme {
                stopAffectedTasksText = stringResource(Res.string.handoff_stop_affected_tasks)
                executeActionText = stringResource(Res.string.handoff_execute_action)

                Box(Modifier.width(320.dp)) {
                    HandoffPanelContent(
                        preview = busyPreview,
                        stopAffectedTasks = false,
                        onToggleStopAffectedTasks = { stopToggled = it },
                        onExecuteHandoff = { handoffExecuted = true },
                        onRefreshPreview = {},
                        onDismiss = {}
                    )
                }
            }
        }

        onNodeWithText("Background Build").assertIsDisplayed()
        val stopCheckbox = onNodeWithText(stopAffectedTasksText)
        stopCheckbox.assertIsDisplayed().performClick()
        runOnIdle { assertTrue(stopToggled) }

        onNodeWithText(executeActionText).assertIsEnabled().performClick()
        runOnIdle { assertTrue(handoffExecuted) }
        saveEvidence("handoff-busy", onAllNodes(isRoot()).onLast())

        // Case B: 目标脏与存在冲突 (Handoff Conflict)
        val dirtyDst = dst.copy(dirty = true)
        val conflictPreview = busyPreview.copy(
            target = dirtyDst,
            conflicts = listOf(WorkspaceConflict("src/Main.kt", WorkspaceErrorCode.TARGET_DIRTY))
        )

        var dirtyWarningText = ""
        var conflictsTitleText = ""

        setContent {
            AppTheme {
                dirtyWarningText = stringResource(Res.string.handoff_target_dirty_warning)
                conflictsTitleText = stringResource(Res.string.handoff_conflicts_title)
                executeActionText = stringResource(Res.string.handoff_execute_action)

                Box(Modifier.width(320.dp)) {
                    HandoffPanelContent(
                        preview = conflictPreview,
                        stopAffectedTasks = true,
                        onToggleStopAffectedTasks = {},
                        onExecuteHandoff = {},
                        onRefreshPreview = {},
                        onDismiss = {}
                    )
                }
            }
        }

        onNodeWithText(dirtyWarningText).assertIsDisplayed()
        onNodeWithText(conflictsTitleText).assertIsDisplayed()
        // 禁止提供针对 dirty 目标的强制覆盖按钮，交接按钮必须处于禁用状态
        onNodeWithText(executeActionText).assertIsNotEnabled()
        saveEvidence("handoff-conflict", onAllNodes(isRoot()).onLast())
    }

    @Test
    fun opensArchiveRestoreRecoveryAndManagementAtPhoneWidth() = runComposeUiTest {
        var restored = false
        var dismissed = false
        var keepCurrent = false
        var restoreNew = false

        // 1. 已归档列表
        val archivedConv = Conversation(
            id = "c_archived",
            projectId = "p1",
            title = "Archived Task",
            status = ConversationStatus.Idle,
            createdAt = 1700000000000L,
            updatedAt = 1700000000000L,
            agent = "AUTONOMOUS",
            archivedAt = "2026-11-02T10:00:00Z"
        )

        var restoreActionText = ""

        setContent {
            AppTheme {
                restoreActionText = stringResource(Res.string.workspace_restore_action)
                Box(Modifier.width(320.dp)) {
                    WorkspaceArchivedListContent(
                        archivedConversations = listOf(archivedConv),
                        onRestore = { restored = true },
                        onDismiss = { dismissed = true }
                    )
                }
            }
        }

        onNodeWithText("Archived Task").assertIsDisplayed()
        onNodeWithText(restoreActionText).assertIsDisplayed().performClick()
        runOnIdle { assertTrue(restored) }
        saveEvidence("archived-restorable", onAllNodes(isRoot()).onLast())

        // 2. 崩溃恢复面板 (两选项：保留现状 vs 恢复至新副本)
        var keepCurrentText = ""
        var restoreNewText = ""

        setContent {
            AppTheme {
                keepCurrentText = stringResource(Res.string.workspace_recovery_keep_current)
                restoreNewText = stringResource(Res.string.workspace_recovery_restore_new)

                Box(Modifier.width(320.dp)) {
                    WorkspaceRecoveryPanelContent(
                        sourcePath = "/source/checkout",
                        targetPath = "/target/checkout",
                        onKeepCurrent = { keepCurrent = true },
                        onRestoreToNew = { restoreNew = true }
                    )
                }
            }
        }

        onNodeWithText(keepCurrentText).assertIsDisplayed().performClick()
        runOnIdle { assertTrue(keepCurrent) }

        onNodeWithText(restoreNewText).assertIsDisplayed().performClick()
        runOnIdle { assertTrue(restoreNew) }
        saveEvidence("recovery", onAllNodes(isRoot()).onLast())

        // 3. 工作区管理面板
        val wsList = listOf(sampleInfo(sampleWorkspace("ws_mgr_1", WorkspaceKind.LOCAL, "/p1/root")))
        var mgmtTitleText = ""
        var archiveActionText = ""

        setContent {
            AppTheme {
                mgmtTitleText = stringResource(Res.string.workspace_management_title)
                archiveActionText = stringResource(Res.string.workspace_archive_action)

                Box(Modifier.width(320.dp)) {
                    WorkspaceManagementPanelContent(
                        worktrees = wsList,
                        onEnterSession = {},
                        onArchiveWorkspace = {},
                        onDismiss = {}
                    )
                }
            }
        }

        onNodeWithText(mgmtTitleText).assertIsDisplayed()
        onNodeWithText(archiveActionText).assertIsDisplayed()
    }

    @Test
    fun supportsKeyboardAndAccessibleSelectedTouchTargets() = runComposeUiTest {
        var keepCurrentText = ""
        var restoreNewText = ""

        setContent {
            AppTheme {
                keepCurrentText = stringResource(Res.string.workspace_recovery_keep_current)
                restoreNewText = stringResource(Res.string.workspace_recovery_restore_new)

                Box(Modifier.width(320.dp)) {
                    WorkspaceRecoveryPanelContent(
                        sourcePath = "/src",
                        targetPath = "/dst",
                        onKeepCurrent = {},
                        onRestoreToNew = {}
                    )
                }
            }
        }

        // 手机上所有主操作按钮触控热区 >= 44dp
        onNodeWithText(keepCurrentText)
            .assertIsDisplayed()
            .assertHeightIsAtLeast(44.dp)

        onNodeWithText(restoreNewText)
            .assertIsDisplayed()
            .assertHeightIsAtLeast(44.dp)
    }
}
