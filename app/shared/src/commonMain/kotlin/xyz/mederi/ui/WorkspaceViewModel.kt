package xyz.mederi.ui

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.ByteString.Companion.toByteString
import xyz.mederi.core.contract.dto.ChatPromptInput
import xyz.mederi.core.contract.dto.ConversationSnapshot
import xyz.mederi.core.contract.dto.ConversationState
import xyz.mederi.core.contract.dto.FileAttachment
import xyz.mederi.core.contract.dto.UpdateConversationSettingsInput
import xyz.mederi.core.contract.models.*
import xyz.mederi.ui.appstate.AppState
import xyz.mederi.ui.appstate.McpStore
import xyz.mederi.ui.appstate.SkillStore
import xyz.mederi.ui.browser.BrowserActionKind
import xyz.mederi.ui.browser.BrowserAutomationUiState
import xyz.mederi.ui.browser.BrowserFlowStep
import xyz.mederi.ui.browser.BrowserStepStatus

import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.err_create_conversation_failed
import mederi.app.shared.generated.resources.err_engine_not_ready
import mederi.app.shared.generated.resources.err_generic
import mederi.app.shared.generated.resources.err_rollback_failed
import mederi.app.shared.generated.resources.err_select_model_first
import mederi.app.shared.generated.resources.err_select_project_or_conversation
import mederi.app.shared.generated.resources.err_send_failed
import mederi.app.shared.generated.resources.err_execution_approval_failed

import xyz.mederi.isDesktopPlatform
import xyz.emuci.inkcompose.MermaidCacheConfig
import xyz.mederi.util.PromptComposer
import xyz.mederi.util.FileOpenTarget
import xyz.mederi.util.classifyFilePath
import xyz.mederi.util.codeFenceLanguageFor
import xyz.mederi.util.defaultAppNameFor
import xyz.mederi.util.revealInFolder
// ⚠️ 关键陷阱：PlatformUtils.openFile（打开给系统应用）与本类的 openFile（路由）同名，
// 必须用别名导入，否则 confirmPendingFileOpen 里调用会递归回自己的路由方法。
import xyz.mederi.util.LinkTargetClassifier
import xyz.mederi.util.openFile as openFileInOs
import xyz.mederi.util.openUrl
import kotlin.time.Duration.Companion.milliseconds

/**
 * 聊天工作区 ViewModel（首页主会话区的数据层）。
 *
 * ## 数据流（唯一真理源）
 *
 * ```
 * AiCore.observeConversation(id) → snapshot（含消息/审批/问询等） → Compose 渲染
 * ```
 *
 * 会话消息不在这里手动维护：attach 到某个会话后，事件聚合器把流式事件合并成
 * [ConversationSnapshot] 推给 [snapshot]，UI 全部从派生属性（[messages]、[chatItems]、
 * [pendingQuestion] 等）读取。
 *
 * ## UI 对接速查
 *
 * | UI 交互 | 调用 |
 * |---|---|
 * | 发送消息 | `send(text)`；发送前置校验失败会写 [error]，UI 展示并 `clearError()` |
 * | 中止生成 | `abort()`（流式中发送按钮变停止按钮） |
 * | 切换模型 | `selectModel(modelOption)`（数据源 appState.availableModels） |
 * | 切换 Agent（AUTONOMOUS / APPROVAL） | `selectAgent(id)`，列表 [availableAgents] |
 * | 切换思考等级 | `updateThinkingLevel(level)`，可选项来自选中模型的 `reasoningLevels` |
 * | AI 问询卡片 | 观察 [pendingQuestion]，回复调 `replyQuestion(id, answers)` |
 * | 计划审批卡片（APPROVAL 模式） | 观察 [pendingPlanApproval]，回复调 `approvePlan(id, approved)` |
 * | 手动压缩历史 | `requestCompaction()` |
 * | 文件 diff 面板 | `openDiff()` / `closeDiff()`，数据 [diffItems] |
 * | 子 Agent 会话跳转 | `selectConversation(childId)`，数据 [childConversations] |
 *
 * @param appState 全局 AppState，由外部（MainScreen 层）注入
 */

/**
 * 遥控端不可用能力的一次性轻提示标识（VM state，UI Composable 内经 stringResource 解码渲染）。
 * 桌面端（jvm）实际拥有剪贴板权限，null 时不提示；iOS/Android/wasmJs 剪贴板不可用时通知用户。
 */
enum class RemoteCapabilityNotice {
    /** 剪贴板图片不可用（getImage() 返回 null 且非 desktop） */
    IMAGE_CLIPBOARD,
    /** 剪贴板文本不可用（getText() 返回 null/blank 且非 desktop） */
    TEXT_CLIPBOARD,
}

class WorkspaceViewModel(
    private val appState: AppState,
) : ViewModel() {

    /** 内置浏览器宿主（桌面端注入 UiBrowserHost；遥控端/wasm 为 null，UI 渲染占位） */
    val uiBrowserHost get() = appState.uiBrowserHost

    // ==========================================
    // 浏览器自动化流程视图（MVVM 状态与动作入口）
    // ==========================================

    /** 未绑定会话时的浏览器自动化 UI 状态兜底 */
    private var unattachedBrowserAutomation by mutableStateOf(BrowserAutomationUiState())

    /**
     * 浏览器面板右侧「自动化操作流程视图」的唯一 MVVM 状态源。
     * 直接绑定/代理到当前会话的 [conversationState.browserAutomation]（切会话天然隔离）。
     */
    val browserAutomationState: BrowserAutomationUiState
        get() = conversationState?.browserAutomation ?: unattachedBrowserAutomation

    /**
     * 单向更新浏览器自动化 UI 状态：
     * 若已绑定会话，同时写入 [conversationState] 及 [sessionCache.conversationStates]；
     * 否则写入未绑定兜底状态。
     */
    private fun updateBrowserAutomation(transform: (BrowserAutomationUiState) -> BrowserAutomationUiState) {
        val convId = conversationId
        val current = conversationState
        if (convId != null && current != null) {
            val currentUi = current.browserAutomation ?: BrowserAutomationUiState()
            val updatedUi = transform(currentUi)
            val updatedState = current.copy(browserAutomation = updatedUi)
            conversationState = updatedState
            sessionCache.conversationStates[convId] = updatedState
        } else {
            unattachedBrowserAutomation = transform(unattachedBrowserAutomation)
        }
    }

    /**
     * 启动浏览器自动化流程（展开右侧网页面板）。
     * 供业务侧在浏览器自动化任务开始时调用。
     */
    fun startBrowserAutomation(taskSummary: String? = null, clearPrevious: Boolean = true) {
        isBrowserAutomationFloatingExpanded = false
        val now = xyz.mederi.currentTimeMillis()
        updateBrowserAutomation { state ->
            state.copy(
                isAutomating = true,
                isPanelOpen = false,
                startedAtMillis = now,
                completedAtMillis = null,
                taskSummary = taskSummary ?: if (clearPrevious) null else state.taskSummary,
                steps = if (clearPrevious) emptyList() else state.steps,
            )
        }
        if (activeDockPanel != RightDockPanel.BROWSER_AUTOMATION) {
            openDockPanel(RightDockPanel.BROWSER_AUTOMATION)
        }
    }

    /**
     * 结束浏览器自动化流程（解除不可关闭锁定，并将未结束的 RUNNING 动作标记为 COMPLETED）。
     * 供业务侧在浏览器自动化任务完成或终止时调用。
     */
    fun finishBrowserAutomation(completedAtMillis: Long = xyz.mederi.currentTimeMillis()) {
        updateBrowserAutomation { state ->
            val updatedSteps = state.steps.map { step ->
                if (step is BrowserFlowStep.Action && step.status == BrowserStepStatus.RUNNING) {
                    step.copy(status = BrowserStepStatus.COMPLETED)
                } else {
                    step
                }
            }
            state.copy(
                isAutomating = false,
                completedAtMillis = completedAtMillis,
                steps = updatedSteps,
            )
        }
    }

    /**
     * 向浏览器自动化流程时间线追加一个节点（AI 旁白 [BrowserFlowStep.Narration] /
     * 动作 [BrowserFlowStep.Action] / 截图 [BrowserFlowStep.Screenshot]）。
     */
    fun appendBrowserFlowStep(step: BrowserFlowStep) {
        updateBrowserAutomation { state ->
            state.copy(
                isPanelOpen = true,
                steps = state.steps + step,
            )
        }
    }

    /**
     * 更新已有浏览器动作节点的状态、耗时或目标描述。
     */
    fun updateBrowserFlowAction(
        stepId: String,
        status: BrowserStepStatus,
        durationMs: Long? = null,
        target: String? = null,
    ) {
        updateBrowserAutomation { state ->
            val updatedSteps = state.steps.map { step ->
                if (step is BrowserFlowStep.Action && step.id == stepId) {
                    step.copy(
                        status = status,
                        durationMs = durationMs ?: step.durationMs,
                        target = target ?: step.target,
                    )
                } else {
                    step
                }
            }
            state.copy(steps = updatedSteps)
        }
    }

    /** 直接整体更新浏览器自动化 UI 状态（供业务聚合器或预览使用）。 */
    fun updateBrowserAutomationState(state: BrowserAutomationUiState) {
        updateBrowserAutomation { state }
    }

    /** 点击流程内截图缩略图，打开高清大图预览弹窗（Lightbox）。 */
    fun openBrowserScreenshotPreview(screenshot: BrowserFlowStep.Screenshot?) {
        updateBrowserAutomation { it.copy(previewScreenshot = screenshot) }
    }

    /** 关闭截图高清大图预览弹窗。 */
    fun closeBrowserScreenshotPreview() {
        updateBrowserAutomation { it.copy(previewScreenshot = null) }
    }

    /** 浏览器自动化主对话浮动详情卡片是否展开 */
    var isBrowserAutomationFloatingExpanded: Boolean by mutableStateOf(false)
        private set

    /** 展开/折叠主对话浏览器自动化详情卡片 */
    fun toggleBrowserAutomationFloating() {
        isBrowserAutomationFloatingExpanded = !isBrowserAutomationFloatingExpanded
    }

    /** 收起主对话浏览器自动化详情卡片（不影响自动化任务继续执行） */
    fun closeBrowserAutomationFloating() {
        isBrowserAutomationFloatingExpanded = false
    }

    /** 展开主对话浏览器自动化详情卡片 */
    fun openBrowserAutomationFloating() {
        isBrowserAutomationFloatingExpanded = true
    }

    /**
     * 中止当前浏览器自动化任务：经契约真正停止 core 的浏览器任务（关闭其浏览器）。
     * 不再中止主对话 turn——浏览器任务与主 turn 无生命周期绑定，误伤主 turn 是旧实现的副作用。
     */
    fun stopBrowserAutomation() {
        viewModelScope.launch {
            val taskId = browserAutomationState.taskId
            try {
                if (taskId.isNullOrBlank()) {
                    // 状态里没有 taskId：如实记录，不伪装成功
                    DebugLog.error("WorkspaceVM", "stopBrowserAutomation: no active browser taskId in state")
                } else {
                    appState.aiCore.stopBrowserTask(taskId).onFailure { e ->
                        DebugLog.error("WorkspaceVM", "stopBrowserTask($taskId) failed: ${e.message}", e)
                    }
                }
            } catch (e: Throwable) {
                DebugLog.error("WorkspaceVM", "stopBrowserAutomation failed: ${e.message}", e)
            } finally {
                finishBrowserAutomation()
                closeBrowserAutomationFloating()
            }
        }
    }

    /** 项目列表与选中项目（窄访问器，供文件树等派生 UI 使用；与 TerminalViewModel 同款转发） */
    val projects: StateFlow<List<Project>> = appState.projects
    val selectedProjectId: StateFlow<String?> = appState.selectedProjectId
    internal val projectPermissionStore get() = appState.projectPermissionStore

    val workspacePresenter = WorkspacePresenter()

    var repositoryInfo by mutableStateOf<RepositoryInfo?>(null)
        private set

    var projectEnvironments by mutableStateOf<List<ProjectEnvironment>>(emptyList())
        private set

    fun refreshWorkspaceContext(projectId: String?) {
        if (projectId == null) {
            repositoryInfo = null
            projectEnvironments = emptyList()
            return
        }
        viewModelScope.launch {
            repositoryInfo = appState.aiCore.getRepositoryInfo(projectId).getOrNull()
            projectEnvironments = appState.aiCore.listProjectEnvironments(projectId).getOrDefault(emptyList())
        }
    }

    fun retryWorkspaceOperation(operation: WorkspaceOperation) {
        val convId = conversationId ?: operation.sessionId ?: return
        viewModelScope.launch {
            appState.aiCore.retryWorkspacePreparation(
                sessionId = convId,
                failedOperationId = operation.id,
                requestId = "retry_${xyz.mederi.currentTimeMillis()}"
            )
        }
    }

    fun cancelWorkspaceOperation(operation: WorkspaceOperation) {
        viewModelScope.launch {
            appState.aiCore.cancelWorkspaceOperation(operation.id)
        }
    }

    // ==========================================
    // 工作区面板对话框（Handoff / 管理 / 归档列表 / 恢复）
    // 唯一状态源 [dialogState]，Workspace.kt Route collectAsState 后按分支挂载对应面板。
    // 所有动作都走真实 AiCore 工作区契约：预览 token、requestId 幂等、结果回显均以 core 为准。
    // ==========================================
    sealed interface WorkspaceDialogState {
        data object None : WorkspaceDialogState

        data class Handoff(
            /** 预览成功才有值；预览失败时对话框以错误态打开，保持可重试。 */
            val preview: HandoffPreview?,
            /** R13：与源类型不同的目标类型（core 拒绝源目标同类型），预览与执行使用同一目标。 */
            val targetKind: WorkspaceKind,
            val stopAffected: Boolean,
            val loading: Boolean,
            val errorMessage: String? = null
        ) : WorkspaceDialogState

        data class Management(
            val workspaces: List<WorkspaceInfo>,
            val errorMessage: String? = null
        ) : WorkspaceDialogState

        data class ArchiveList(
            val archived: List<Conversation>,
            val errorMessage: String? = null
        ) : WorkspaceDialogState

        data class Recovery(
            /** 预览成功才有值；预览失败时对话框以错误态打开，保持可重试。 */
            val preview: RecoveryPreview?,
            val operationId: String,
            val loading: Boolean,
            val errorMessage: String? = null
        ) : WorkspaceDialogState

        data class WorktreeSettings(
            val rootPath: String,
            val max: Int,
            val autoCleanup: Boolean,
            val version: Long = 1L,
            val errorMessage: String? = null
        ) : WorkspaceDialogState

        data class CreateBranch(
            val errorMessage: String? = null
        ) : WorkspaceDialogState
    }

    private val _dialogState = MutableStateFlow<WorkspaceDialogState>(WorkspaceDialogState.None)
    val dialogState: StateFlow<WorkspaceDialogState> = _dialogState

    /**
     * 当前对话框归属的会话（R15）：异步完成回写 [dialogState] 前必须仍等于当前会话，
     * 防止 A 会话的预览/提交响应在用户切到 B 会话后污染 B 的面板。
     * [attach] 切会话与 [dismissDialog] 关闭面板时清空。
     */
    private var dialogOwnerSessionId: String? = null

    /**
     * 面板请求代次（T12）：仅靠 [dialogOwnerSessionId] 无法防御**同会话**内的串写——
     * 同一会话先开 recovery 预览、期间 dismiss 后又开 handoff（owner 不变），
     * 旧 recovery 响应回来时 owner 校验仍会通过，把 A 面板的数据写进 B 面板。
     * 每次面板请求递增 [dialogRequestGeneration] 并捕获 [DialogRequest]，
     * 异步完成点除 owner 外还校验代次：旧代次响应（迟到预览 / 迟到错误）一律丢弃。
     */
    private var dialogRequestGeneration: Long = 0L

    /** 面板请求票据：归属会话 + 面板类型 + 请求代次（kind = "handoff"/"management"/"archive"/"recovery"）。 */
    private data class DialogRequest(
        val ownerSessionId: String,
        val projectId: String,
        val kind: String,
        val generation: Long
    )

    /** 当前有效面板请求（null = 无在途请求：切会话 / dismiss / 提交成功后清空）。 */
    private var activeDialogRequest: DialogRequest? = null

    /** 异步完成点守卫：票据仍是当前请求且代次未过期、归属会话未变时，旧响应才允许回写 [dialogState]。 */
    private fun isCurrentDialogRequest(req: DialogRequest): Boolean =
        activeDialogRequest === req &&
            req.generation == dialogRequestGeneration &&
            req.ownerSessionId == conversationId

    /** 对话框可展示的错误文案（用户可见格式由 UI 层按资源 key 套用）。 */
    private fun dialogErrorMessage(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error::class.simpleName ?: "unknown error"

    /** 面板操作所属项目：已绑定会话用会话项目；未绑定用当前选中项目。 */
    private fun workspaceProjectId(): String? =
        conversationId?.let { id -> snapshot?.conversation?.projectId } ?: appState.selectedProjectId.value

    /** 生成带前缀的唯一 requestId（core 工作区操作幂等键的组成部分）。 */
    private fun newWorkspaceRequestId(prefix: String): String = "$prefix-${kotlin.uuid.Uuid.random()}"

    /** 关闭所有工作区面板对话框。 */
    fun dismissDialog() {
        dialogOwnerSessionId = null
        activeDialogRequest = null
        _dialogState.value = WorkspaceDialogState.None
    }

    /**
     * 打开 Handoff 迁移面板（WorkspaceSelector「迁移工作区」菜单项入口）：
     * 先向 core 请求预览（真实 preview token / 源目标 / 受影响会话 / 冲突），
     * 成功后把 [WorkspaceDialogState.Handoff] 写入 [dialogState] 供 UI 挂载 HandoffPanelContent。
     */
    fun openHandoffDialog() {
        val sessionId = conversationId ?: return
        // R13：目标必须与源类型不同（真实 Manager 拒绝源目标同类型）。
        // 源 Local → 目标 MANAGED_WORKTREE；源 MANAGED_WORKTREE（或未知）→ 目标 Local。
        val sourceKind = snapshot?.workspaceInfo?.active?.workspace?.kind
        val targetKind = if (sourceKind == WorkspaceKind.LOCAL) {
            WorkspaceKind.MANAGED_WORKTREE
        } else {
            WorkspaceKind.LOCAL
        }
        val req = DialogRequest(sessionId, workspaceProjectId().orEmpty(), "handoff", ++dialogRequestGeneration)
        activeDialogRequest = req
        dialogOwnerSessionId = sessionId
        viewModelScope.launch {
            appState.aiCore.previewWorkspaceHandoff(
                sessionId,
                HandoffPreviewInput(targetKind = targetKind)
            ).onSuccess { preview ->
                if (isCurrentDialogRequest(req)) {
                    _dialogState.value = WorkspaceDialogState.Handoff(
                        preview = preview,
                        targetKind = targetKind,
                        stopAffected = false,
                        loading = false,
                        errorMessage = null
                    )
                }
            }.onFailure { error ->
                if (isCurrentDialogRequest(req)) {
                    _dialogState.value = WorkspaceDialogState.Handoff(
                        preview = null,
                        targetKind = targetKind,
                        stopAffected = false,
                        loading = false,
                        errorMessage = dialogErrorMessage(error)
                    )
                }
            }
        }
    }

    /** 切换「停止受影响任务」勾选（Handoff 面板复选框）。 */
    fun toggleStopAffected() {
        val state = _dialogState.value as? WorkspaceDialogState.Handoff ?: return
        _dialogState.value = state.copy(stopAffected = !state.stopAffected)
    }

    /** 面板复选框回调直接落值（供 HandoffPanelContent 的 (Boolean)->Unit 签名使用，与 [toggleStopAffected] 同源）。 */
    fun setStopAffectedTasks(enabled: Boolean) {
        val state = _dialogState.value as? WorkspaceDialogState.Handoff ?: return
        _dialogState.value = state.copy(stopAffected = enabled)
    }

    /**
     * 提交 Handoff：携带预览返回的**同一个** preview token 与唯一 requestId 调用 core；
     * 幂等由 core 按 requestId 去重。成功后关闭对话框；失败保留对话框（loading 复位）。
     */
    fun executeHandoff() {
        val state = _dialogState.value as? WorkspaceDialogState.Handoff ?: return
        val sessionId = conversationId ?: return
        val preview = state.preview ?: return
        if (state.loading) return
        val req = DialogRequest(sessionId, workspaceProjectId().orEmpty(), "handoff", ++dialogRequestGeneration)
        activeDialogRequest = req
        dialogOwnerSessionId = sessionId
        _dialogState.value = state.copy(loading = true, errorMessage = null)
        viewModelScope.launch {
            appState.aiCore.handoffWorkspace(
                sessionId,
                HandoffInput(
                    targetKind = state.targetKind,
                    previewToken = preview.token,
                    requestId = newWorkspaceRequestId("handoff"),
                    stopAffectedSessions = state.stopAffected,
                    taskBranchName = preview.taskBranch
                )
            ).onSuccess {
                if (isCurrentDialogRequest(req)) {
                    dialogOwnerSessionId = null
                    activeDialogRequest = null
                    _dialogState.value = WorkspaceDialogState.None
                }
            }.onFailure { error ->
                if (isCurrentDialogRequest(req)) {
                    _dialogState.value = state.copy(loading = false, errorMessage = dialogErrorMessage(error))
                }
            }
        }
    }

    /** 打开工作副本管理面板：listWorkspaces 结果写入 [dialogState]。 */
    fun openManagement() {
        val projectId = workspaceProjectId() ?: return
        val owner = conversationId
        val req = DialogRequest(owner.orEmpty(), projectId, "management", ++dialogRequestGeneration)
        activeDialogRequest = req
        dialogOwnerSessionId = owner
        viewModelScope.launch {
            appState.aiCore.listWorkspaces(projectId).onSuccess { workspaces ->
                if (isCurrentDialogRequest(req)) {
                    _dialogState.value = WorkspaceDialogState.Management(workspaces)
                }
            }.onFailure { error ->
                if (isCurrentDialogRequest(req)) {
                    _dialogState.value = WorkspaceDialogState.Management(emptyList(), dialogErrorMessage(error))
                }
            }
        }
    }

    /** 归档指定工作区（requestId 幂等）；成功后刷新当前面板列表回显 core 快照。 */
    fun archiveWorkspace(workspaceId: String) {
        viewModelScope.launch {
            appState.aiCore.archiveWorkspace(
                workspaceId,
                ArchiveWorkspaceInput(requestId = newWorkspaceRequestId("archive"))
            ).onSuccess {
                when {
                    _dialogState.value is WorkspaceDialogState.Management -> openManagement()
                    _dialogState.value is WorkspaceDialogState.ArchiveList -> refreshArchiveList()
                }
            }.onFailure { error ->
                val req = activeDialogRequest
                if (req != null && isCurrentDialogRequest(req)) {
                    val current = _dialogState.value
                    _dialogState.value = when (current) {
                        is WorkspaceDialogState.Management -> current.copy(errorMessage = dialogErrorMessage(error))
                        is WorkspaceDialogState.ArchiveList -> current.copy(errorMessage = dialogErrorMessage(error))
                        else -> current
                    }
                }
            }
        }
    }

    /** 打开已归档会话列表。 */
    fun openArchiveList() {
        val projectId = workspaceProjectId() ?: return
        val owner = conversationId
        val req = DialogRequest(owner.orEmpty(), projectId, "archive", ++dialogRequestGeneration)
        activeDialogRequest = req
        dialogOwnerSessionId = owner
        viewModelScope.launch {
            appState.aiCore.listArchivedConversations(projectId).onSuccess { archived ->
                if (isCurrentDialogRequest(req)) {
                    _dialogState.value = WorkspaceDialogState.ArchiveList(archived)
                }
            }.onFailure { error ->
                if (isCurrentDialogRequest(req)) {
                    _dialogState.value = WorkspaceDialogState.ArchiveList(emptyList(), dialogErrorMessage(error))
                }
            }
        }
    }

    /** 归档列表面板「恢复」：取消会话归档并刷新列表。 */
    fun restoreArchivedConversation(conversation: Conversation) {
        viewModelScope.launch {
            appState.aiCore.setConversationArchived(
                conversation.id,
                archived = false,
                requestId = newWorkspaceRequestId("restore")
            ).onSuccess {
                refreshArchiveList()
            }
        }
    }

    private fun refreshArchiveList() {
        val projectId = workspaceProjectId() ?: return
        val owner = conversationId
        val req = DialogRequest(owner.orEmpty(), projectId, "archive", ++dialogRequestGeneration)
        activeDialogRequest = req
        dialogOwnerSessionId = owner
        viewModelScope.launch {
            appState.aiCore.listArchivedConversations(projectId).onSuccess { archived ->
                if (isCurrentDialogRequest(req) && _dialogState.value is WorkspaceDialogState.ArchiveList) {
                    _dialogState.value = WorkspaceDialogState.ArchiveList(archived)
                }
            }.onFailure { error ->
                if (isCurrentDialogRequest(req) && _dialogState.value is WorkspaceDialogState.ArchiveList) {
                    _dialogState.value = (_dialogState.value as WorkspaceDialogState.ArchiveList)
                        .copy(errorMessage = dialogErrorMessage(error))
                }
            }
        }
    }

    /** 固定 / 取消固定会话（会话列表 pin 入口）。 */
    fun setPinned(sessionId: String, pinned: Boolean) {
        viewModelScope.launch {
            appState.aiCore.setConversationPinned(sessionId, pinned)
        }
    }

    /** 打开恢复策略面板：对指定 operation 请求恢复预览（token / operationVersion / 可用快照）。 */
    fun previewRecovery(
        operationId: String,
        strategy: RecoveryStrategy = RecoveryStrategy.RESTORE_TO_NEW_MANAGED
    ) {
        val owner = conversationId ?: return
        val req = DialogRequest(owner, workspaceProjectId().orEmpty(), "recovery", ++dialogRequestGeneration)
        activeDialogRequest = req
        dialogOwnerSessionId = owner
        viewModelScope.launch {
            appState.aiCore.previewWorkspaceRecovery(operationId, strategy)
                .onSuccess { preview ->
                    if (isCurrentDialogRequest(req)) {
                        _dialogState.value = WorkspaceDialogState.Recovery(
                            preview = preview,
                            operationId = operationId,
                            loading = false,
                            errorMessage = null
                        )
                    }
                }
                .onFailure { error ->
                    if (isCurrentDialogRequest(req)) {
                        _dialogState.value = WorkspaceDialogState.Recovery(
                            preview = null,
                            operationId = operationId,
                            loading = false,
                            errorMessage = dialogErrorMessage(error)
                        )
                    }
                }
        }
    }

    /** 按选定策略提交恢复（携带预览 token / operationVersion / requestId）。 */
    fun executeRecovery(strategy: RecoveryStrategy) {
        val state = _dialogState.value as? WorkspaceDialogState.Recovery ?: return
        val preview = state.preview ?: return
        if (state.loading) return
        val sessionId = conversationId ?: return
        val req = DialogRequest(sessionId, workspaceProjectId().orEmpty(), "recovery", ++dialogRequestGeneration)
        activeDialogRequest = req
        dialogOwnerSessionId = sessionId
        _dialogState.value = state.copy(loading = true, errorMessage = null)
        viewModelScope.launch {
            appState.aiCore.recoverWorkspaceOperation(
                state.operationId,
                WorkspaceRecoveryInput(
                    strategy = strategy,
                    snapshotId = preview.eligibleSnapshots.firstOrNull()?.snapshotId,
                    keepWorkspaceId = if (strategy == RecoveryStrategy.KEEP_VERIFIED_CURRENT) {
                        preview.sourceWorkspace?.workspace?.id
                    } else {
                        null
                    },
                    expectedOperationVersion = preview.operationVersion,
                    previewToken = preview.token,
                    requestId = newWorkspaceRequestId("recover")
                )
            ).onSuccess {
                if (isCurrentDialogRequest(req)) {
                    dialogOwnerSessionId = null
                    activeDialogRequest = null
                    _dialogState.value = WorkspaceDialogState.None
                }
            }.onFailure { error ->
                if (isCurrentDialogRequest(req)) {
                    _dialogState.value = state.copy(loading = false, errorMessage = dialogErrorMessage(error))
                }
            }
        }
    }

    // ==========================================
    // S13 环境编辑 / 宿主设置 / 建分支
    // ==========================================

    sealed interface EnvironmentEditorState {
        data object None : EnvironmentEditorState
        data class Editing(
            val environmentId: String?,
            val name: String,
            val setupScript: String,
            val actions: List<EnvActionRow>,
            val version: Long,
            val originalEnvironment: ProjectEnvironment? = null
        ) : EnvironmentEditorState
        data class Error(val message: String) : EnvironmentEditorState
    }

    data class EnvActionRow(val id: String, val name: String, val script: String)

    private val _environmentEditorState = MutableStateFlow<EnvironmentEditorState>(EnvironmentEditorState.None)
    val environmentEditorState: StateFlow<EnvironmentEditorState> = _environmentEditorState

    fun openEnvironmentEditor() {
        val workspaceId = snapshot?.workspaceInfo?.active?.workspace?.id ?: return
        val projectId = workspaceProjectId() ?: return
        val currentConvId = conversationId ?: return
        val req = DialogRequest(currentConvId, projectId, "environment", ++dialogRequestGeneration)
        activeDialogRequest = req
        viewModelScope.launch {
            val envs = appState.aiCore.listProjectEnvironments(projectId, workspaceId).getOrDefault(emptyList())
            val targetEnvId = snapshot?.workspaceInfo?.active?.workspace?.pendingSelection?.environmentId
            val env = if (targetEnvId != null) envs.find { it.id == targetEnvId } ?: envs.firstOrNull() else envs.firstOrNull()
            if (!isCurrentDialogRequest(req)) return@launch
            _environmentEditorState.value = if (env != null) {
                EnvironmentEditorState.Editing(
                    environmentId = env.id,
                    name = env.name,
                    setupScript = env.setup?.default.orEmpty(),
                    actions = env.actions.map { EnvActionRow(it.id, it.name, it.script.default.orEmpty()) },
                    version = env.version,
                    originalEnvironment = env
                )
            } else {
                EnvironmentEditorState.Editing(
                    environmentId = null,
                    name = "",
                    setupScript = "",
                    actions = emptyList(),
                    version = 0L,
                    originalEnvironment = null
                )
            }
        }
    }

    fun saveEnvironment(name: String, setupScript: String, actions: List<EnvActionRow>, expectedVersion: Long) {
        val state = _environmentEditorState.value as? EnvironmentEditorState.Editing ?: return
        val workspaceId = snapshot?.workspaceInfo?.active?.workspace?.id ?: return
        val projectId = workspaceProjectId() ?: return
        val currentConvId = conversationId ?: return
        val req = DialogRequest(currentConvId, projectId, "environment", ++dialogRequestGeneration)
        activeDialogRequest = req
        viewModelScope.launch {
            val envId = state.environmentId ?: "env_${kotlin.uuid.Uuid.random()}"
            val orig = state.originalEnvironment
            val updatedSetup = if (setupScript.isNotBlank()) {
                orig?.setup?.copy(default = setupScript) ?: PlatformScript(default = setupScript)
            } else null

            val updatedActions = actions.map { row ->
                val origAction = orig?.actions?.find { it.id == row.id }
                if (origAction != null) {
                    origAction.copy(name = row.name, script = origAction.script.copy(default = row.script))
                } else {
                    EnvironmentAction(id = row.id, name = row.name, script = PlatformScript(default = row.script))
                }
            }

            val env = orig?.copy(
                id = envId,
                name = name,
                version = expectedVersion,
                setup = updatedSetup,
                actions = updatedActions
            ) ?: ProjectEnvironment(
                id = envId,
                name = name,
                version = expectedVersion,
                setup = updatedSetup,
                actions = updatedActions
            )

            appState.aiCore.saveProjectEnvironment(projectId, env, expectedVersion, workspaceId)
                .onSuccess { saved ->
                    if (!isCurrentDialogRequest(req)) return@launch
                    _environmentEditorState.value = EnvironmentEditorState.Editing(
                        environmentId = saved.id,
                        name = saved.name,
                        setupScript = saved.setup?.default.orEmpty(),
                        actions = saved.actions.map { EnvActionRow(it.id, it.name, it.script.default.orEmpty()) },
                        version = saved.version,
                        originalEnvironment = saved
                    )
                }
                .onFailure { e ->
                    if (!isCurrentDialogRequest(req)) return@launch
                    _environmentEditorState.value = EnvironmentEditorState.Error(e.message ?: "Save failed")
                }
        }
    }

    fun runEnvironmentAction(environmentId: String, actionId: String, version: Long) {
        val currentConvId = conversationId ?: return
        val workspaceId = snapshot?.takeIf { it.conversation.id == currentConvId }
            ?.workspaceInfo?.active?.workspace?.id ?: return
        val request = WorkspaceActionInput(
            environmentId = environmentId,
            actionId = actionId,
            environmentVersion = version,
            requestId = newWorkspaceRequestId("action"),
            conversationId = currentConvId
        )
        val dialogRequest = activeDialogRequest
        viewModelScope.launch {
            appState.aiCore.runWorkspaceAction(workspaceId, request).onFailure { e ->
                if (dialogRequest != null && isCurrentDialogRequest(dialogRequest)) {
                    _environmentEditorState.value = EnvironmentEditorState.Error(e.message ?: "Action failed")
                }
                DebugLog.error("WorkspaceVM", "runEnvironmentAction failed: ${e.message}", e)
            }
        }
    }

    fun updateWorktreeSettings(rootPath: String, max: Int, autoCleanup: Boolean) {
        val curVersion = (_dialogState.value as? WorkspaceDialogState.WorktreeSettings)?.version ?: 1L
        viewModelScope.launch {
            appState.aiCore.updateWorktreeSettings(
                WorktreeSettings(rootPath = rootPath, maxManagedWorktrees = max, automaticCleanup = autoCleanup, version = curVersion)
            ).onSuccess {
                _dialogState.value = WorkspaceDialogState.None
            }.onFailure { e ->
                val current = _dialogState.value as? WorkspaceDialogState.WorktreeSettings
                if (current != null) {
                    _dialogState.value = current.copy(errorMessage = e.message)
                }
            }
        }
    }

    fun createWorkspaceBranch(name: String, expectedGeneration: Long) {
        val active = snapshot?.workspaceInfo?.active ?: return
        val ws = active.workspace
        val headOid = active.headOid ?: return
        viewModelScope.launch {
            appState.aiCore.createWorkspaceBranch(
                ws.id,
                CreateWorkspaceBranchInput(name = name, expectedHead = headOid, expectedGeneration = expectedGeneration)
            ).onSuccess {
                _dialogState.value = WorkspaceDialogState.None
            }.onFailure { e ->
                _dialogState.value = WorkspaceDialogState.CreateBranch(errorMessage = e.message)
            }
        }
    }

    fun openWorktreeSettings() {
        val owner = conversationId
        viewModelScope.launch {
            val settings = appState.aiCore.getWorktreeSettings().getOrNull()
            if (owner == conversationId) {
                _dialogState.value = WorkspaceDialogState.WorktreeSettings(
                    rootPath = settings?.rootPath.orEmpty(),
                    max = settings?.maxManagedWorktrees ?: 15,
                    autoCleanup = settings?.automaticCleanup ?: true,
                    version = settings?.version ?: 1L
                )
            }
        }
    }

    fun openCreateBranchDialog() {
        _dialogState.value = WorkspaceDialogState.CreateBranch()
    }

    fun dismissEnvironmentEditor() {
        activeDialogRequest = null
        _environmentEditorState.value = EnvironmentEditorState.None
    }

    /** 当前输入所属项目；已有会话以自己的项目为准，不能读另一个项目的权限。 */
    internal val executionProjectId: String?
        get() = resolvePermissionProjectId(conversationId, conversationState?.conversation,
            appState.projects.value, appState.selectedProjectId.value)

    init {
        // UI 层能力（仅桌面端）：动态监听当前选中项目，将 Mermaid 磁盘缓存目录重定向到 core 暴露的项目工作目录。
        // android/ios 是遥控端：project.directory 是 server 机器的路径，设备上不存在/无写权限，
        // 注入只会让磁盘缓存静默失效——遥控端固定用本地应用缓存目录（Android 宿主启动时注入 cacheDir，
        // iOS 用 NSCaches 默认值），不走项目重定向。wasmJs 无磁盘，setBaseDirectory 本身是 no-op。
        if (isDesktopPlatform) {
            viewModelScope.launch {
                combine(appState.projects, appState.selectedProjectId) { projects, selectedId ->
                    val workDir = projects.find { it.id == selectedId }?.workDir
                    if (!workDir.isNullOrBlank()) {
                        workDir
                    } else {
                        appState.aiCore.configDir
                    }
                }.collect { targetDir ->
                    if (!targetDir.isNullOrBlank()) {
                        MermaidCacheConfig.setBaseDirectory(targetDir)
                    }
                }
            }
        }
        // 程序关闭时同步保存当前会话 compose state（AppState.flushAll 先跑全部 flush 回调再落偏好）
        appState.registerFlushCallback { flushComposeState() }

        viewModelScope.launch {
            appState.selectedProjectId.collect { pid ->
                refreshWorkspaceContext(pid)
            }
        }
    }


    // ==========================================
    // 图片能力（唯一推导，UI 显隐/警告与发送门禁同源）
    // ==========================================

    /**
     * 当前选中模型是否支持图片输入（唯一推导，禁止 UI 各处手写 `selectedModel?.supportsImages` 判断）：
     * 附件按钮显隐、附件区警告、tryAttachImage 拦截、guardImageSupport 发送守卫全部同源于此。
     * 无选中模型 = false（不提供图片功能）。
     */
    val modelSupportsImages: StateFlow<Boolean> = appState.selectedModel
        .map { it?.supportsImages == true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // ==========================================
    // Agent 选择（AgentMode 切换）
    // ==========================================

    /**
     * 可选 Agent 列表（2 个预设：AUTONOMOUS 自动审批 / APPROVAL 人工审批）。
     *
     * UI 渲染 Agent 选择器（下拉/Chip）时观察此 StateFlow：
     * ```
     * val agents by viewModel.availableAgents.collectAsState()
     * ```
     * 每项的 [AgentOption.mode] 是执行策略（APPROVAL=人工审批 / AUTONOMOUS=自动审批）、
     * [AgentOption.name]/[AgentOption.description] 是现成的显示文案。
     */
    val availableAgents: StateFlow<List<AgentOption>> get() = appState.availableAgents

    /**
     * 当前选中的 Agent ID（持久化，重启后恢复）。
     * UI 判断选中态用：`agents.find { it.id == selectedAgentId }`。
     */
    val selectedAgentId: StateFlow<String?> get() = appState.selectedAgentId

    /**
     * 当前选中的执行策略（AUTONOMOUS/APPROVAL）。
     * 唯一真理源 = [AppState.selectedAgentMode]（由选中 Agent 派生的 StateFlow），此处直接转发：
     * 不持有 Compose 副本，双源分叉在结构上不可能发生。UI 用 `collectAsState()` 订阅。
     */
    val selectedAgentMode: StateFlow<AgentMode> get() = appState.selectedAgentMode

    /** 当前选中的 Agent 派生值，未选择时为 null（发消息会回退到默认 AUTONOMOUS）。 */
    val selectedAgent: AgentOption?
        get() = availableAgents.value.find { it.id == selectedAgentId.value }

    /**
     * 切换 Agent；Agent 选择器每项的 onClick 调这个。
     *
     * ```
     * viewModel.selectAgent(agent.id)      // 选中
     * ```
     *
     * 生效时机说明（UI AI 必须理解）：
     * - 该选择**立即影响之后发送的每一条消息**（[send] 每次都会按当前选中 Agent
     *   组装 AgentConfig：APPROVAL 走计划审批流，AUTONOMOUS 直接执行）
     * - 已绑定会话时切换会**立即持久化到该会话**（updateConversationSettings，见下方实现），
     *   切换会话后按会话快照恢复；未绑定会话时仅改全局偏好，重启后恢复
     */
    fun selectAgent(id: String?) {
        DebugLog.info("UI", "WorkspaceViewModel.selectAgent: id=$id")
        appState.selectAgent(id)
        saveComposeState()
    }

    /**
     * 切换执行策略（AUTONOMOUS / APPROVAL）。
     * 只写 AppState（唯一真理源），派生流自动更新。
     */
    fun selectAgentMode(mode: AgentMode) {
        DebugLog.info("UI", "WorkspaceViewModel.selectAgentMode: requested=$mode, currentAgentId=${selectedAgentId.value}")
        appState.selectAgentMode(mode)
        saveComposeState()
    }

    // ==========================================
    // 计划审批（APPROVAL 模式核心交互）
    // ==========================================

    /**
     * 待审批的计划请求；null 表示当前没有挂起的审批。
     *
     * 完整触发链路（UI AI 必须理解，APPROVAL 模式专属）：
     * 1. 用户通过 [selectAgent] 选中 mode=APPROVAL 的 Agent
     * 2. 用户发消息 → AI 调 create_plan 工具列出计划
 * 3. core 发 PLAN_APPROVAL_REQUESTED 事件 → 事件聚合器写入 snapshot.pendingPlanApproval
 *    （卡片只带标题 + 子任务数；摘要 = AI 调 create_plan 前的聊天消息，详细内容点按钮打开 planPath）
 * 4. UI 观察到此值非 null → 渲染计划审批卡片（标题 + 查看详细计划 + 批准按钮）
     * 5. 用户点批准/不批准 → 调 [approvePlan]
     * 6. core 发 PLAN_APPROVAL_RESOLVED → 本属性自动回到 null → UI 收起卡片
     *
     * 注意：AUTONOMOUS 模式下永远不会出现待审批计划（AI 建了计划自动批准），
     * 所以审批卡片只在 APPROVAL 模式的会话里渲染。
     */
    val pendingPlanApproval: PlanApprovalRequest? get() = snapshot?.pendingPlanApproval

    /** 提交状态按原会话和请求标识隔离，切换会话不会把旧结果写进当前会话。 */
    private data class ExecutionApprovalSubmission(val submitting: Boolean, val error: UiMessage? = null)
    private val executionApprovalSubmissions = mutableStateMapOf<Pair<String, String>, ExecutionApprovalSubmission>()

    fun isExecutionApprovalSubmitting(request: ExecutionApprovalRequest): Boolean =
        executionApprovalSubmissions[request.conversationId to request.id]?.submitting == true

    fun executionApprovalError(request: ExecutionApprovalRequest): UiMessage? =
        executionApprovalSubmissions[request.conversationId to request.id]?.error

    fun resolveExecutionApproval(request: ExecutionApprovalRequest, approved: Boolean) {
        val key = request.conversationId to request.id
        if (executionApprovalSubmissions[key]?.submitting == true) return
        executionApprovalSubmissions[key] = ExecutionApprovalSubmission(submitting = true)
        viewModelScope.launch {
            try {
                appState.aiCore.resolveExecutionApproval(request.conversationId, request.id, approved).getOrThrow()
                // API 成功已表示请求被消费；等待 core 事件或快照移除卡片，不开放重复批准。
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                executionApprovalSubmissions[key] = ExecutionApprovalSubmission(submitting = false,
                    error = UiMessage(Res.string.err_execution_approval_failed, listOf(failure.message.orEmpty())))
            }
        }
    }

    /**
     * 回复计划审批；审批卡片按钮的 onClick 调这个。
     *
     * ```
     * viewModel.approvePlan(request.id, approved = true)    // 批准 → AI 开始执行计划
     * viewModel.approvePlan(request.id, approved = false)   // 不批准 → AI 绝不执行；
     *                                                       // 用户继续聊天即可让 AI 修订计划，
     *                                                       // 修订后的新审批自动取代旧审批
     * ```
     *
     * @param planId 计划 ID，来自 [PlanApprovalRequest.id]（不要自己生成）
     * @param approved true=批准并开始执行；false=不批准（没有"拒绝并终止"语义）
     */
    fun approvePlan(planId: String, approved: Boolean = true) {
        val convId = conversationId ?: return
        val currentPending = snapshot?.pendingPlanApproval
        DebugLog.event("UI", "approvePlan called: planId=$planId, approved=$approved, currentPendingId=${currentPending?.id}")
        viewModelScope.launch {
            appState.aiCore.resolvePlanApproval(convId, planId, approved)
        }
    }

    var conversationId by mutableStateOf<String?>(null); internal set
    var conversationState by mutableStateOf<ConversationState?>(null); private set

    /** 向后兼容快照访问器：直接重定向到 conversationState */
    val snapshot: ConversationState? get() = conversationState

    var isAttached by mutableStateOf(false); private set

    /**
     * 会话级缓存族（乐观消息 / 计时锚点 / 状态机池 / 观察 Job）——内部存储收敛为
     * 单一 [SessionUiCache] 实例（字段不对外暴露，对外读取仍走原属性）。
     *
     * 状态机池解决会话切换闪烁问题：切换会话时不再 `conversationState = null`
     * 再从 store 重建——而是缓存当前状态机，切回来时立即渲染（含正在流式的推理/消息），
     * 再由事件流持续更新。
     */
    private val sessionCache = SessionUiCache()
    /** 记录已水合过输入框设置的会话 ID 集合。确保每个会话仅在进入/初次打开时水合一次设置，绝不被后续快照倒灌覆盖 */
    private val hydratedSessions = mutableSetOf<String>()
    var reasoningExpanded by mutableStateOf<Map<String, Boolean>>(emptyMap()); private set

    // ------------------------------------------------------------------
    // diff查询由会话级表现协作者持有，对外只保留只读兼容投影
    // ------------------------------------------------------------------

    /** Core内容真理源不变；查询/选择反馈由DiffPresenter按会话持有。 */
    private val diffPresenter = DiffPresenter(appState.aiCore, viewModelScope)
    internal val diffPanelState: DiffUiState get() = diffPresenter.state
    private val diffState: DiffUiState get() = diffPanelState

    /** 当前会话的文件 diff 列表 */
    val diffItems: List<FileDiff> get() = diffState.items

    /** 当前选中的 diff 文件路径 */
    val selectedDiffFilePath: String? get() = diffState.selectedPath

    /** diff 面板是否展开 */
    val showDiffPanel: Boolean get() = activeDockPanel == RightDockPanel.DIFF

    /** 未绑定会话时的 Dock 面板激活状态兜底 */
    private var unattachedDockPanel by mutableStateOf<RightDockPanel?>(null)

    /**
     * 右侧独立功能活动栏当前激活的面板（null 表示收起关闭）。
     * 绑定到当前会话的 [conversationState.activeDockPanel]，切会话天然隔离。
     */
    val activeDockPanel: RightDockPanel?
        get() = if (conversationId != null) conversationState?.activeDockPanel else unattachedDockPanel

    /** 右侧面板是否展开（派生：activeDockPanel != null） */
    val isRightPanelOpen: Boolean
        get() = activeDockPanel != null

    /**
     * 更新当前活动栏激活面板：写入当前会话状态机与缓存。
     */
    private fun updateActiveDockPanel(panel: RightDockPanel?) {
        diffPresenter.setVisible(panel == RightDockPanel.DIFF)
        if (panel == RightDockPanel.ARTIFACTS) {
            selectedReport()?.let { reportLoader.activate(it.request.key) } ?: reportLoader.deactivate()
        } else reportLoader.deactivate()
        val convId = conversationId
        val current = conversationState
        DebugLog.event("UI-Dock", "updateActiveDockPanel: convId=$convId, panel=$panel")
        if (convId != null && current != null) {
            val updated = current.copy(activeDockPanel = panel)
            conversationState = updated
            sessionCache.conversationStates[convId] = updated
        } else {
            unattachedDockPanel = panel
        }
    }

    fun toggleDockPanel(panel: RightDockPanel) {
        if (activeDockPanel != panel && panel == RightDockPanel.DIFF) {
            openDiff()
            return
        }
        updateActiveDockPanel(if (activeDockPanel == panel) null else panel)
        closeSubagentPanel()
    }

    fun openDockPanel(panel: RightDockPanel) {
        if (panel == RightDockPanel.DIFF) { openDiff(); return }
        updateActiveDockPanel(panel)
        closeSubagentPanel()
    }

    fun closeDockPanel() {
        updateActiveDockPanel(null)
    }

    /** 未绑定会话时的子代理对话面板激活状态兜底 */
    private var unattachedSubagentPanelAgentId by mutableStateOf<String?>(null)

    /** 右侧子代理对话面板当前打开的子代理（按 agentId 从 subagents 解析；null 表示收起）。 */
    val activeSubagentPanel: xyz.mederi.core.contract.models.SubagentState?
        get() = if (conversationId != null) {
            conversationState?.activeSubagentPanelAgentId?.let { subagent(it) }
        } else {
            unattachedSubagentPanelAgentId?.let { subagent(it) }
        }

    /** 子代理对话视图的 ChatListItem（派生：activeSubagentPanel 变化时重算）。 */
    val subagentChatItems: List<ChatListItem> by derivedStateOf {
        activeSubagentPanel?.let { SubagentChatItems.build(it) } ?: emptyList()
    }

    private fun updateActiveSubagentPanel(agentId: String?) {
        val convId = conversationId
        val current = conversationState
        if (convId != null && current != null) {
            val updated = current.copy(activeSubagentPanelAgentId = agentId)
            conversationState = updated
            sessionCache.conversationStates[convId] = updated
        } else {
            unattachedSubagentPanelAgentId = agentId
        }
    }

    /** 打开子代理对话面板（与 Dock 面板互斥：先关 Dock）。 */
    fun openSubagentPanel(sub: xyz.mederi.core.contract.models.SubagentState) {
        closeDockPanel()
        updateActiveSubagentPanel(sub.agentId)
    }

    /** 关闭子代理对话面板。 */
    fun closeSubagentPanel() {
        updateActiveSubagentPanel(null)
    }

    /**
     * Office 文档预览（.docx/.xlsx/.pptx）。
     */
    fun previewOffice(path: String) {
        val conversationId = this.conversationId ?: return
        viewModelScope.launch {
            appState.aiCore.previewOffice(conversationId, path)
                .onSuccess { html ->
                    DebugLog.event("UI", "previewOffice success: html generated for $path (${html.length} chars)")
                }
                .onFailure { err ->
                    DebugLog.event("UI", "previewOffice failed: ${err.message}")
                }
        }
    }

    /** Skill 唯一真理源代理（与 AppState 共享同一实例） */
    val skillStore: SkillStore get() = appState.skillStore

    /** MCP 唯一真理源代理（与 AppState 共享同一实例） */
    val mcpStore: McpStore get() = appState.mcpStore

    /** 已安装 MCP Server 列表（由 McpStore 统一驱动，向后兼容快捷访问） */
    val mcpServers: List<xyz.mederi.core.contract.models.McpServerItem> get() = mcpStore.mcpServers.value
    val isMcpRefreshing: Boolean get() = mcpStore.isRefreshing.value

    fun refreshMcpServers() = mcpStore.refresh()
    fun toggleMcpServer(name: String, enabled: Boolean) = mcpStore.toggleEnabled(name, enabled)
    suspend fun installMcpServer(json: String): Result<Unit> = mcpStore.install(json)
    suspend fun updateMcpServer(name: String, json: String): Result<Unit> = mcpStore.update(name, json)
    fun deleteMcpServer(name: String) = mcpStore.delete(name)
    fun verifyMcpServer(name: String) = mcpStore.verify(name)
    suspend fun getMcpServerJson(name: String): Result<String> = mcpStore.getJson(name)


    /** 当前展示的计划内容。写操作只经 openPlanInExtension（单向数据流） */
    var currentPlan by mutableStateOf<PlanItem?>(null); private set

    private val reportLoader = ReportLoadPresenter(viewModelScope)
    internal fun reportState(key: ReportKey): ReportLoadState = reportLoader.state(key)

    private fun selectedReport(): ArtifactItem.Report? =
        readerState.activeItem as? ArtifactItem.Report

    fun openEventReport(item: ChatListItem.EventMessageCard, title: String) {
        // 后台命令/定时器的详情本来就是事件正文，没有独立报告文件，不能伪报文件缺失。
        if (item.eventType.startsWith("background_task_") && item.reportPath.isNullOrBlank()) {
            openDocument(DocumentTarget.Content("event:${conversationId}:${item.key}", title, item.summary))
            return
        }
        val sessionId = conversationId ?: return
        val request = ReportRequest(ReportKey(sessionId, item.key), item.reportPath, item.summary)
        openDocument(DocumentTarget.Report("report:$sessionId:${item.key}", title, request))
    }

    suspend fun recordPlanManualAcceptance(
        planId: String,
        input: xyz.mederi.core.contract.models.ManualPlanAcceptanceInput
    ): Result<Unit> {
        val sessionId = conversationId?.takeIf { planOverviewList.any { plan -> plan.id == planId } }
            ?: return Result.failure(IllegalStateException("Plan is no longer in the selected session."))
        return appState.aiCore.recordPlanManualAcceptance(sessionId, planId, input)
    }

    fun openPlanAcceptanceReport(planId: String, title: String) {
        val sessionId = conversationId ?: return
        val key = ReportKey(sessionId, "plan-acceptance:$planId")
        val request = ReportRequest(key, path = null, summary = "", planId = planId)
        openReportInExtension("report:$sessionId:${key.eventId}", title, request, force = true)
    }

    private fun openReportInExtension(id: String, title: String, request: ReportRequest, force: Boolean = false) {
        beginReaderAction(cancelReport = false)
        val provider = appState.fileTreeProvider
        val core = appState.aiCore
        val planId = request.planId
        val path = request.path?.takeIf { it.isNotBlank() }
        val read: (suspend () -> Result<String?>)? = when {
            planId != null -> { { core.getPlanAcceptanceReport(request.key.sessionId, planId) } }
            provider != null && path != null -> { { provider.readTextResult(path) } }
            else -> null
        }
        reportLoader.select(request, if (planId != null) core else provider, read, force)
        val updated = ArtifactItem.Report(id, title, request)
        updateReader { it.open(updated) }
        openDockPanel(RightDockPanel.ARTIFACTS)
    }

    fun retryReport(key: ReportKey) {
        val item = selectedReport()?.takeIf { it.request.key == key } ?: return
        if (activeDockPanel != RightDockPanel.ARTIFACTS) return
        fileOpenRequestSeq++
        if (key.sessionId == conversationId) openReportInExtension(item.id, item.title, item.request, force = true)
        else reportLoader.retry(key) // 旧会话的已打开标签复用原来源，不能改读当前项目的相对路径。
    }

    private val readerStates = mutableStateMapOf<ReaderOwner, ReaderPresentationState>()
    private val readerOwner: ReaderOwner
        get() = conversationId?.let { ReaderOwner.Session(it) } ?: ReaderOwner.Draft(appState.selectedProjectId.value)
    private val readerState: ReaderPresentationState get() = readerStates[readerOwner] ?: ReaderPresentationState()
    private fun updateReader(change: (ReaderPresentationState) -> ReaderPresentationState) {
        readerStates[readerOwner] = change(readerState)
    }

    /** 只读兼容投影；标签/选择/待确认意图只有readerStates一个写入口。 */
    val artifactItems: List<ArtifactItem> get() = readerState.items
    val activeArtifactId: String? get() = readerState.activeId
    internal val readerPanelState: ReaderPanelState
        get() {
            val state = readerState
            val selected = state.activeItem
            val live = if (selected is ArtifactItem.Text) getLiveArtifactItem(selected.id) ?: selected else selected
            return ReaderPanelState(state.items, live, (live as? ArtifactItem.Report)?.let { reportState(it.request.key) } ?: ReportLoadState.Idle)
        }

    private fun beginReaderAction(cancelReport: Boolean = true) {
        fileOpenRequestSeq++
        pendingFileOpen = null
        if (cancelReport) reportLoader.deactivate()
    }

    /** 激活当前owner中存在的标签；过期/跨会话的回调无副作用。 */
    fun openArtifact(artifactId: String) {
        if (artifactItems.none { it.id == artifactId }) return
        beginReaderAction(cancelReport = false)
        updateReader { it.select(artifactId) }
        if (activeDockPanel == RightDockPanel.ARTIFACTS) {
            selectedReport()?.let { reportLoader.activate(it.request.key) } ?: reportLoader.deactivate()
        } else reportLoader.deactivate()
    }

    fun openImageInExtension(title: String, imageUrl: String) {
        beginReaderAction()
        val existing = artifactItems.filterIsInstance<ArtifactItem.Image>().find { it.imageUrl == imageUrl }
        val image = existing ?: ArtifactItem.Image(id = kotlin.uuid.Uuid.random().toString(), title = title, imageUrl = imageUrl)
        updateReader { it.open(image) }
        openDockPanel(RightDockPanel.ARTIFACTS)
        DebugLog.event("UI", "openImageInExtension: title='$title', activeArtifactId=$activeArtifactId")
    }

    fun openTextInExtension(
        title: String,
        content: String,
        lineCount: Int = 0,
        charCount: Int = 0,
        id: String? = null,
        isStreaming: Boolean = false,
    ) {
        beginReaderAction()
        val targetId = id ?: "txt_${title.hashCode().toUInt()}"
        val updatedItem = ArtifactItem.Text(
            id = targetId,
            title = title,
            content = content,
            lineCount = lineCount,
            charCount = charCount,
            isStreaming = isStreaming,
        )
        updateReader { it.open(updatedItem) }
        openDockPanel(RightDockPanel.ARTIFACTS)
        DebugLog.event("UI", "openTextInExtension: title='$title', activeArtifactId=$activeArtifactId, chars=$charCount, isStreaming=$isStreaming")
    }

    /** 文件打开确认态（EXTERNAL / REVEAL 档），非 null 时 Workspace 渲染 ConfirmDialog。 */
    var pendingFileOpen: PendingFileOpen?
        get() = readerState.pendingFileOpen
        private set(value) { updateReader { it.copy(pendingFileOpen = value) } }

    /**
     * 文件打开请求序号：异步解析（读盘 / 查默认应用名）期间用户又点了别的文件时，
     * 只有序号仍是当前值的请求才允许写 [pendingFileOpen]，防止旧请求覆盖新请求。
     */
    private var fileOpenRequestSeq = 0

    /**
     * 对话流文件点击的**唯一路由**：按扩展名分级——内部能看的直接在右侧 dock 打开，
     * 其余写入 [pendingFileOpen] 由 UI 弹确认框（用 XXX 打开 / 打开所在目录）。
     *
     * 注意同名陷阱：这里调的是路由本身；真正「打开给系统」的那一步在 [confirmPendingFileOpen]
     * 里经别名导入的 `openFileInOs`（= `xyz.mederi.util.openFile` / PlatformUtils）执行。
     *
     * 平台 I/O（读盘、查默认应用名）一律移出组合线程：在 viewModelScope 内异步执行，
     * 命令等待带超时（PlatformUtils 侧），绝不在点击回调里同步起进程。
     */
    fun openFile(path: String) {
        beginReaderAction()
        val owner = readerOwner
        val provider = appState.fileTreeProvider
        val resolvedPath = provider?.resolvePath(path) ?: path
        val fileName = resolvedPath.substringAfterLast('/').ifBlank { resolvedPath }

        // 1. 目录点击处理（Directory Target）：桌面端弹窗提示打开该目录；非桌面端忽略
        if (provider?.isDirectory(resolvedPath) == true) {
            if (isDesktopPlatform) {
                pendingFileOpen = PendingFileOpen.OpenDirectory(resolvedPath)
            }
            return
        }

        when (classifyFilePath(resolvedPath)) {
            FileOpenTarget.INTERNAL_IMAGE -> openImageInExtension(title = fileName, imageUrl = resolvedPath)
            FileOpenTarget.INTERNAL_TEXT -> {
                val requestId = ++fileOpenRequestSeq
                viewModelScope.launch {
                    val content = withContext(Dispatchers.Default) { provider?.readText(resolvedPath) }
                    if (requestId != fileOpenRequestSeq || readerOwner != owner) return@launch // 新目标/会话/项目已取代
                    if (content == null || content.contains('\u0000')) {
                        // 二进制 / 读不到 → 若为桌面端则尝试查询应用或打开目录；非桌面端直接忽略
                        if (!isDesktopPlatform) return@launch
                        routeExternalOrReveal(resolvedPath, requestId)
                    } else {
                        // md/markdown 原样直出（查看器按 Markdown 渲染）；其余按词法器语言标签
                        // 包进代码围栏（有标签 → ```lang 高亮；无标签 → 裸 ``` 等宽不高亮）。
                        // 只包 content 本身，不改 lineCount/charCount —— 信息条按文件真实行列。
                        val ext = resolvedPath.substringAfterLast('.', "").lowercase().trim()
                        val fence = codeFenceLanguageFor(resolvedPath)
                        val rendered = if (ext == "md" || ext == "markdown") {
                            content
                        } else {
                            (if (fence != null) "```$fence\n" else "```\n") + content + "\n```"
                        }
                        // lineCount 必须一起给：查看器信息条按 (行 · 字符) 渲染，缺省 0 会显示「0 行 · N 字符」
                        openTextInExtension(
                            title = fileName,
                            content = rendered,
                            lineCount = content.count { it == '\n' } + 1,
                            charCount = content.length
                        )
                    }
                }
            }
            FileOpenTarget.EXTERNAL -> {
                if (isDesktopPlatform) {
                    routeExternalOrReveal(resolvedPath, ++fileOpenRequestSeq)
                }
            }
            FileOpenTarget.REVEAL_IN_FOLDER -> {
                if (isDesktopPlatform) {
                    pendingFileOpen = PendingFileOpen.RevealInFolder(resolvedPath)
                }
            }
        }
    }

    /** EXTERNAL 档：查系统默认应用名，有则「用 [appName] 打开」，查不到降级「打开文件所在目录？」。非桌面端不弹。 */
    private fun routeExternalOrReveal(path: String, requestId: Int) {
        if (!isDesktopPlatform) return
        val owner = readerOwner
        viewModelScope.launch {
            val appName = withContext(Dispatchers.Default) { defaultAppNameFor(path) }
            if (requestId != fileOpenRequestSeq || readerOwner != owner) return@launch // 新目标/会话/项目已取代
            pendingFileOpen = if (appName != null) {
                PendingFileOpen.OpenExternally(path, appName)
            } else {
                PendingFileOpen.RevealInFolder(path)
            }
        }
    }

    /**
     * 链接点击路由：本地路径（file:// / file: / 绝对路径 / Windows 盘符）走 [openFile] 统一路由，
     * 其余（http/https/mailto…）交系统浏览器。纯字符串判定，commonMain 禁用 java.net.URI。
     */
    fun openLink(url: String) {
        val localPath = LinkTargetClassifier.localPathOrNull(url)
        if (localPath != null) {
            openDocument(DocumentTarget.Path(localPath))
        } else {
            openUrl(url)
        }
    }

    /**
     * 计划全文打开路由：全平台统一经 [openPlanDocument] 按需读盘——文件真理源在 server 机器上，
     * 本地 fileTreeProvider（桌面端）不参与拼接，四个平台同一链路，不再有"遥控端只见标题摘要占位"的降级。
     */
    /**
     * 统一定义需要阅读或打开的文档目标载体。
     */
    sealed interface DocumentTarget {
        /** 磁盘上的物理路径（源码文件、已落盘的 plan.md、子代理报告等） */
        data class Path(val filePath: String) : DocumentTarget

        /** 内存中的文本内容（虚拟文档、用户粘贴文本、未落盘的 Plan/Spec/Artifact） */
        data class Content(
            val id: String,
            val title: String,
            val content: String,
            val lineCount: Int = 0,
            val charCount: Int = 0,
            val isStreaming: Boolean = false,
        ) : DocumentTarget

        /** 按需异步读取的事件报告，来源/摘要由本地请求绑定。 */
        data class Report(val id: String, val title: String, val request: ReportRequest) : DocumentTarget

        /** 图片资源（网络链接或本地图片路径） */
        data class Image(val title: String, val url: String) : DocumentTarget
    }

    /**
     * 整个工作区统一的文档/文件打开入口 Action。
     * 所有入口（Markdown 链接、计划卡、Spec、改动文件、用户粘贴大文本、产物文档、文件树）一律收敛于此。
     */
    fun openDocument(target: DocumentTarget) {
        when (target) {
            is DocumentTarget.Path -> openFile(target.filePath)
            is DocumentTarget.Report -> openReportInExtension(target.id, target.title, target.request)
            is DocumentTarget.Content -> openTextInExtension(
                title = target.title,
                content = target.content,
                lineCount = target.lineCount,
                charCount = target.charCount,
                id = target.id,
                isStreaming = target.isStreaming,
            )
            is DocumentTarget.Image -> openImageInExtension(
                title = target.title,
                imageUrl = target.url,
            )
        }
    }

    /** 优先阅读 Core 投影的审批内容。旧快照没有内容时才按文件路径读取。 */
    fun openPlanItem(item: PlanOverviewItem) {
        openPlanFile(item)
    }

    /** 用户确认：执行打开 / 揭示，并清态。 */
    fun confirmPendingFileOpen() {
        val pending = pendingFileOpen ?: return
        when (pending) {
            // openFileInOs = PlatformUtils.openFile（open 命令 / Desktop.open），**不是** this.openFile（路由，会递归）
            is PendingFileOpen.OpenExternally -> {
                if (!openFileInOs(pending.path)) {
                    // 打开失败必须显性化：macOS 沙箱会拦 LaunchServices（错误 -54/256），
                    // 静默吞异常 = 用户点「打开」毫无反应（历史 bug）。
                    fileOpenErrorNotice = true
                    viewModelScope.launch {
                        delay(5000)
                        fileOpenErrorNotice = false
                    }
                }
            }
            is PendingFileOpen.RevealInFolder -> revealInFolder(pending.path)
            is PendingFileOpen.OpenDirectory -> {
                if (!openFileInOs(pending.path)) {
                    revealInFolder(pending.path)
                }
            }
        }
        pendingFileOpen = null
    }

    /** 用户取消：清态。 */
    fun cancelPendingFileOpen() {
        pendingFileOpen = null
    }

    /**
     * 获取当前最新的产物文档模型（结合当前活跃聊天流中最新的流式内容动态响应）
     */
    fun getLiveArtifactItem(id: String): ArtifactItem.Text? {
        val docCard = chatItems.filterIsInstance<ChatListItem.DocumentCard>().lastOrNull { it.artifactId == id }
        if (docCard != null) {
            return ArtifactItem.Text(
                id = docCard.artifactId,
                title = docCard.title,
                content = docCard.content,
                lineCount = docCard.lineCount,
                charCount = docCard.charCount,
                isStreaming = docCard.isStreaming
            )
        }
        return artifactItems.filterIsInstance<ArtifactItem.Text>().find { it.id == id }
    }

    fun openPlanInExtension(planId: String, title: String, content: String) {
        val displayTitle = if (title.isNotBlank()) "计划: $title" else "计划详情"
        currentPlan = PlanItem(
            id = planId,
            title = displayTitle,
            content = content
        )
        openDocument(
            DocumentTarget.Content(
                id = "plan_$planId",
                title = displayTitle,
                content = content,
                lineCount = content.count { it == '\n' } + 1,
                charCount = content.length
            )
        )
        DebugLog.event("UI", "openPlanInExtension: planId=$planId, title='$title', contentLen=${content.length}")
    }

    /**
     * 计划/Spec 按需读盘统一入口（四平台同链路）：经 [AiCore.getPlanDocument] 异步读盘，
     * 成功后按 [subtaskIndex] 区分打开计划全文（null）或该子任务的 Spec（非 null）；
     * 失败只置一次性轻提示 [planReadErrorNotice]，不打开阅读器、不用降级内容伪装成功。
     */
    fun openPlanDocument(
        planId: String,
        title: String,
        subtaskIndex: Int? = null,
        subtaskName: String = "",
    ) {
        val convId = conversationId ?: return
        viewModelScope.launch {
            appState.aiCore.getPlanDocument(convId, planId, subtaskIndex)
                .onSuccess { content ->
                    if (subtaskIndex == null) {
                        openPlanInExtension(planId, title, content)
                    } else {
                        openSpecInExtension(planId, title, subtaskIndex, subtaskName, content)
                    }
                }
                .onFailure { e ->
                    DebugLog.error("UI", "open plan document failed: planId=$planId, subtask=$subtaskIndex: ${e.message}", e)
                    planReadErrorNotice = title
                    viewModelScope.launch {
                        delay(5000)
                        planReadErrorNotice = null
                    }
                }
        }
    }

    /**
     * 概览「阅读 plan.md 全文」：薄转发到按需读盘统一入口（不再读本地 fileTreeProvider / planPath）。
     */
    fun openPlanFile(item: PlanOverviewItem) {
        openPlanDocument(planId = item.id, title = item.title)
    }

    /**
     * 在右侧统一阅读器打开子任务的执行清单（Spec）
     */
    fun openSpecInExtension(planId: String, planTitle: String, subtaskIndex: Int, subtaskName: String, specContent: String?) {
        val title = "步骤 ${subtaskIndex + 1} Spec: $subtaskName"
        val fullContent = buildString {
            appendLine("# $title")
            appendLine("> 所属计划：$planTitle (`$planId`)")
            appendLine()
            if (!specContent.isNullOrBlank()) {
                appendLine(specContent)
            } else {
                appendLine("_（该子任务尚未生成执行清单 Spec）_")
            }
        }
        currentPlan = PlanItem(
            id = "${planId}_spec_$subtaskIndex",
            title = title,
            content = fullContent
        )
        openDocument(
            DocumentTarget.Content(
                id = "spec_${planId}_$subtaskIndex",
                title = title,
                content = fullContent,
                lineCount = fullContent.count { it == '\n' } + 1,
                charCount = fullContent.length
            )
        )
        DebugLog.event("UI", "openSpecInExtension: planId=$planId, subtask=$subtaskIndex, specLen=${specContent?.length ?: 0}")
    }

    /**
     * 派生当前会话制定过的全部计划列表（概览面板真理源）。
     * 涵盖待批准、执行中、已完成、已作废四个生命周期阶段。
     */
    val planOverviewList: List<PlanOverviewItem>
        get() {
            val approvals = snapshot?.planApprovals.orEmpty()
            val pending = snapshot?.pendingPlanApproval
            val merged = if (pending != null && approvals.none { it.id == pending.id }) {
                listOf(pending) + approvals
            } else {
                approvals
            }
            return merged.map { req ->
                val status = when (req.status.uppercase()) {
                    "PENDING" -> PlanOverviewStatus.PendingApproval
                    "APPROVED", "IN_PROGRESS", "AUTO_APPROVED" -> PlanOverviewStatus.InProgress
                    "COMPLETED" -> PlanOverviewStatus.Completed
                    "VOIDED" -> PlanOverviewStatus.Voided
                    else -> PlanOverviewStatus.InProgress
                }
                PlanOverviewItem(
                    id = req.id,
                    title = req.title.ifBlank { "未命名计划" },
                    summary = req.summary,
                    planPath = req.planPath,
                    status = status,
                    subtasks = req.subtasks.map {
                        PlanSubtaskOverview(
                            index = it.index,
                            name = it.name,
                            status = it.status,
                            hasSpec = it.hasSpec
                        )
                    },
                    manualCaseIds = req.subtasks.flatMap { task ->
                        task.acceptanceCases.filter { it.manual }.map { it.id }
                    }
                )
            }
        }

    fun toggleRightPanel() {
        if (activeDockPanel != null) {
            updateActiveDockPanel(null)
        } else {
            // 概览已迁出 Dock（浮动窗常驻），打开 DIFF 作为默认面板
            updateActiveDockPanel(RightDockPanel.DIFF)
        }
    }
    // ------------------------------------------------------------------
    // 一次性 UI 效果通道（Channel<UiEffect>）
    // ------------------------------------------------------------------
    // 真正的一次性导航/打开类命令（打开设置对话框、打开项目选择菜单）经效果通道派发，
    // UI 层 collect 消费后落为本地 UI 状态；持续性展示状态（error 家族/ErrorBoard、
    // imageStrippedNotice 轻提示）仍保留为 VM state（见 UiEffect KDoc 的迁移边界说明）——
    // 它们由 UI 常驻展示并在合适时机清空，不是"触发一次的导航命令"。
    //
    // 只有MainScreen Route通过rememberWorkspaceEffectState消费；叶子不得订阅此流。
    private val _effects = Channel<UiEffect>(Channel.BUFFERED)
    val effects: kotlinx.coroutines.flow.Flow<UiEffect> = _effects.receiveAsFlow()

    /** 打开设置对话框（一次性导航命令，UI 收集后置 isSettingsVisible = true） */
    fun openSettings() {
        _effects.trySend(UiEffect.OpenSettings)
    }

    fun openProjectSettings(projectId: String) {
        _effects.trySend(UiEffect.OpenProjectSettings(projectId))
    }

    /** 打开项目选择菜单（一次性命令，Route消费后置项目菜单展开，叶子只接收受控状态） */
    fun openProjectMenu() {
        _effects.trySend(UiEffect.OpenProjectMenu)
    }

    // ------------------------------------------------------------------
    // 错误反馈按会话/草稿归档，兼容属性只读同一投影
    // ------------------------------------------------------------------

    /** Core投影与本地失败/关闭/详情意图分离，异步结果绑定原操作票据。 */
    private val feedbackPresenter = WorkspaceFeedbackPresenter()
    private val feedbackOwner: FeedbackOwner
        get() = conversationId?.let { FeedbackOwner.Session(it) } ?: FeedbackOwner.Draft(appState.selectedProjectId.value)
    internal val feedbackState: ErrorState get() = feedbackPresenter.state
    private val errorState: ErrorState get() = feedbackState

    private fun acceptCoreFeedback(id: String, snap: ConversationState) {
        feedbackPresenter.accept(FeedbackOwner.Session(id), ErrorState(
            message = snap.errorMessage?.let { UiMessage(Res.string.err_generic, listOf(it)) },
            diagnostic = snap.errorDiagnostic, id = snap.errorId,
            isStreamInterrupted = snap.errorIsStreamInterrupted,
        ))
    }

    /**
     * 当前会话的头条错误简报（null = 无错误）。
     */
    val error: UiMessage? get() = errorState.message

    /**
     * 一次性轻提示：图片已从发送内容中剔除（值为模型名，null=不显示）。非错误，不走 error。
     */
    var imageStrippedNotice by mutableStateOf<String?>(null); private set

    /**
     * 一次性轻提示：遥控端剪贴板能力不可用（null=不显示，标识枚举驱动 UI 渲染解码 i18n 文案）。
     * 桌面端有真实剪贴板权限，null 时不提示；iOS/Android/wasmJs 无剪贴板权限时给轻提示。
     */
    var remoteCapabilityNotice by mutableStateOf<RemoteCapabilityNotice?>(null); private set

    /**
     * 当前会话错误的完整诊断报告（纯文本，来自 [ConversationSnapshot.errorDiagnostic]）。
     * UI 点击错误简报时可展示此完整详情；null 表示无错误或旧路径。
     */
    val errorDiagnostic: String? get() = errorState.diagnostic

    /**
     * 当前会话错误的 ID（来自 [ConversationSnapshot.errorId]）。
     * JVM 端 UI 可经 `ErrorCollector.get(errorId)` 取回完整结构化的 [ErrorRecord]。
     */
    val errorId: String? get() = errorState.id

    /** 断流标记（自愈已下沉到 TurnExecutor；此属性仅供手动"继续"按钮兜底读取） */

    /**
     * 当前错误是否为断流（流式连接提前中断）。为 true 时 ErrorBoard 显示"继续"按钮，
     * 点击经 [continueAfterInterruption] 重发 Continue 续写半截回复。
     */
    val isStreamInterrupted: Boolean get() = errorState.isStreamInterrupted

    /** 排队消息序号自增器（确保同一毫秒内多条消息的 ID 唯一） */

    /**
     * 是否正在展示详细错误报告弹窗。
     */
    val isErrorDetailOpen: Boolean get() = errorState.isDetailOpen

    fun showErrorDetail() { feedbackPresenter.showDetail() }

    fun dismissErrorDetail() { feedbackPresenter.dismissDetail() }

    // 乐观用户消息（sessionCache.pendingUserMessages）与 StatusBar 计时锚点（sessionCache.turnStartByConv）
    // 已收敛进 [sessionCache]（SessionUiCache，见文件底部），语义与文档随成员迁移。

    /** 当前会话的乐观消息（兼容旧引用/测试） */
    val optimisticUserMessage: ChatMessage? get() = conversationId?.let { sessionCache.pendingUserMessages[it]?.lastOrNull() }

    /** 当前会话的排队消息列表（排队模式 / 引导模式） */

    /**
     * 输入框草稿（会话级业务状态，唯一真理源在此）。
     *
     * 欢迎页与消息列表两个 ChatInputCard 调用点共享同一草稿：分支切换（收到第一条回复、
     * 清空会话）不再丢失正在输入的内容。选区菜单"添加到对话框"也直接写这里，
     * 无需事件对象绕道。UI 通过 [updateInputDraft]/[clearInputDraft] 单向写入。
     */
    var inputDraft by mutableStateOf(TextFieldValue("")); private set

    /** 未绑定会话（conversationId == null）时的草稿文本，切走再切回新会话时恢复 */
    private var unattachedDraft: String = ""

    /** 尚无会话 ID 的子代理设置只留在草稿中，首次发送创建会话后再落库。 */
    var draftSubagentSettings by mutableStateOf(xyz.mederi.ui.settings.DraftSubagentSettings())
        private set

    // 这里只合并创建会话的在途传输，不缓存/调度业务消息；消息仍立即交给 Core。
    private data class ConversationCreation(
        val projectId: String,
        val contextVersion: Long,
        val result: Deferred<Result<Conversation>>,
    )
    private var conversationCreation: ConversationCreation? = null

    private var inputContextVersion = 0L
    private var inputProjectId: String? = appState.selectedProjectId.value
    private var creationDraftTarget: String? = null
    private data class UnacceptedInput(
        val text: String,
        val pasted: List<PastedTextAttachment>,
        val images: List<ImageAttachment>,
    )
    // 传输失败时保留原上下文的可编辑草稿；切回时恢复，不污染当前会话。
    private val unacceptedInputs = mutableMapOf<String, MutableList<UnacceptedInput>>()

    private fun restoreUnacceptedInputs(key: String) {
        val failed = unacceptedInputs.remove(key) ?: return
        val merged = (failed.map { it.text } + inputDraft.text).filter { it.isNotBlank() }.joinToString("\n\n")
        failed.flatMap { it.pasted }.forEach { item ->
            if (pendingPastedTexts.none { it.id == item.id }) pendingPastedTexts.add(item.copy(index = pendingPastedTexts.size + 1))
        }
        failed.flatMap { it.images }.forEach { item ->
            if (pendingImages.none { it.id == item.id }) pendingImages.add(item)
        }
        // 正文恢复作为完成信号，观察到正文时附件也已恢复。
        updateInputDraft(TextFieldValue(merged, TextRange(merged.length)))
    }

    /** 草稿输入防抖 Job：连续打字 300ms 无新输入后自动保存到当前会话 */
    private var draftDebounceJob: kotlinx.coroutines.Job? = null

    fun updateInputDraft(value: TextFieldValue) {
        imageStrippedNotice = null // 下次输入时清空"图片已剔除"轻提示
        remoteCapabilityNotice = null // 下次输入时清空"遥控端剪贴板不可用"轻提示
        inputDraft = value
        val convId = conversationId
        if (convId == null) {
            unattachedDraft = value.text
        } else {
            draftDebounceJob?.cancel()
            draftDebounceJob = viewModelScope.launch {
                kotlinx.coroutines.delay(300)
                saveComposeState()
            }
        }
    }

    fun clearInputDraft() {
        inputDraft = TextFieldValue("")
        draftDebounceJob?.cancel()
        val convId = conversationId
        if (convId != null) {
            sessionCache.conversationStates[convId]?.let { cached ->
                sessionCache.conversationStates[convId] = cached.copy(
                    conversation = cached.conversation.copy(draftText = "")
                )
            }
            viewModelScope.launch {
                val input = buildComposeStateInput().copy(draftText = "")
                appState.aiCore.updateConversationSettings(convId, input)
            }
        } else {
            unattachedDraft = ""
        }
    }

    /**
     * 读取当前输入框全部设置的最新值，组装全量 UpdateConversationSettingsInput。
     * 任意设置变化时由 [saveComposeState] 调用——一个入口，一个数据结构，全量覆盖写。
     */
    private fun buildComposeStateInput(): UpdateConversationSettingsInput {
        val model = appState.selectedModel.value
        val agentId = appState.selectedAgentId.value
        val agent = appState.availableAgents.value.find { it.id == agentId }
        val apiKeyId = model?.let { appState.getApiKeyId(it.provider) }
        val thinkingLevel = model?.let { appState.getModelReasoningLevel(it.id) }
        val draftText = inputDraft.text
        return UpdateConversationSettingsInput(
            model = model,
            agent = agent,
            apiKeyId = apiKeyId,
            thinkingLevel = thinkingLevel,
            draftText = draftText,
        )
    }

    /** 切换会话水合设置期间为 true，禁止回写触发脏写。 */
    private var isHydratingSettings = false

    /**
     * 保存当前会话的 compose state（全量覆盖写）。
     * 单一入口：同步更新内存快照缓存（UI 零延迟即时生效）+ 后台协程同步到数据库。
     * 会话未绑定（conversationId == null）或水合中时跳过。
     */
    fun saveComposeState() {
        if (isHydratingSettings) return
        val convId = conversationId ?: return
        val input = buildComposeStateInput()
        updateConversationStateCache(convId, input)
        viewModelScope.launch {
            appState.aiCore.updateConversationSettings(convId, input)
        }
    }

    /**
     * 同步更新会话状态机中的 Conversation 对象，使切回来时 [applySessionSettings] 能读到最新值。
     * 状态机不存在时 no-op（切回来走 getSnapshot 从库读）。
     */
    private fun updateConversationStateCache(convId: String, input: UpdateConversationSettingsInput) {
        sessionCache.conversationStates[convId]?.let { cached ->
            val conv = cached.conversation
            sessionCache.conversationStates[convId] = cached.copy(
                conversation = conv.copy(
                    draftText = input.draftText,
                    apiKeyId = input.apiKeyId,
                    thinkingLevel = input.thinkingLevel,
                    modelId = input.model?.id ?: conv.modelId,
                    modelProvider = input.model?.provider ?: conv.modelProvider,
                    agent = input.agent?.mode?.name ?: conv.agent,
                )
            )
        }
    }

    /**
     * 程序关闭时同步保存当前会话 compose state（由 AppState flushAll 回调调用）。
     */
    private suspend fun flushComposeState() {
        val convId = conversationId ?: return
        draftDebounceJob?.cancel()
        val input = buildComposeStateInput()
        updateConversationStateCache(convId, input)
        appState.aiCore.updateConversationSettings(convId, input)
    }

    // ───── 输入容量预算（业务规则归口 VM，UI 只渲染提示） ─────

    /** 单次输入安全字符预算：上下文窗口按比例折算，并受硬上限约束 */
    fun maxSafeInputChars(contextWindowTokens: Int): Int =
        minOf(MAX_SAFE_INPUT_CHARS_HARD_CAP, (contextWindowTokens * CONTEXT_WINDOW_CHARS_FACTOR).toInt())

    companion object {
        /** 输入字符预算硬上限（即使上下文窗口很大也不超过） */
        private const val MAX_SAFE_INPUT_CHARS_HARD_CAP = 100_000

        /** 上下文窗口 token → 字符预算的折算系数（token 数 ≥ 字符数，留余量防截断） */
        private const val CONTEXT_WINDOW_CHARS_FACTOR = 2.5

        /** 未选中模型时的默认上下文窗口（token） */
        const val DEFAULT_CONTEXT_WINDOW_TOKENS = 128_000
    }

    /** 用户粘贴的大段文本附件（吸附在输入框中，发送时拼装在主指令之后） */
    val pendingPastedTexts = mutableStateListOf<PastedTextAttachment>()

    /** 待发送图片附件 */
    val pendingImages = mutableStateListOf<ImageAttachment>()

    fun addPastedText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val idx = pendingPastedTexts.size + 1
        val lines = trimmed.lines().size
        val chars = trimmed.length
        val item = PastedTextAttachment(
            id = "pasted_${xyz.mederi.currentTimeMillis()}_$idx",
            index = idx,
            text = trimmed,
            lineCount = lines,
            charCount = chars
        )
        pendingPastedTexts.add(item)
    }

    fun removePastedText(id: String) {
        pendingPastedTexts.removeAll { it.id == id }
        val reindexed = pendingPastedTexts.mapIndexed { index, item ->
            item.copy(index = index + 1)
        }
        pendingPastedTexts.clear()
        pendingPastedTexts.addAll(reindexed)
    }

    /**
     * 附加剪贴板图片的唯一 UI 入口（附件按钮与 Ctrl/Cmd+V 粘贴共用）。
     * 剔除放行：当前模型不支持图片输入时**不拒绝附加**——图片照常入列，
     * 发送时由 core 按模型能力在 AI 视图剔除（历史保留），这里只给一次性轻提示。
     *
     * @return true = 已附加（始终 true，不再因模型图片能力拦截）。
     */
    fun tryAttachImage(name: String, mimeType: String, bytes: ByteArray, width: Int = 0, height: Int = 0): Boolean {
        val model = appState.selectedModel.value
        if (model != null && !model.supportsImages) {
            DebugLog.event("UI", "attach: model does not support image input (${model.providerModelId}); allowed, will be stripped on send")
            imageStrippedNotice = model.name
        }
        addImage(name, mimeType, bytes, width, height)
        return true
    }

    private fun addImage(name: String, mimeType: String, bytes: ByteArray, width: Int = 0, height: Int = 0) {
        val base64 = bytes.toByteString().base64()
        val dataUrl = "data:$mimeType;base64,$base64"
        val item = ImageAttachment(
            id = "img_${xyz.mederi.currentTimeMillis()}_${pendingImages.size + 1}",
            name = name,
            mimeType = mimeType,
            bytes = bytes,
            base64DataUrl = dataUrl,
            width = width,
            height = height
        )
        pendingImages.add(item)
    }

    fun removeImage(id: String) {
        pendingImages.removeAll { it.id == id }
    }

    fun clearPendingAttachments() {
        pendingPastedTexts.clear()
        pendingImages.clear()
    }

    val isWorking: Boolean get() = snapshot?.conversation?.status == ConversationStatus.Working
    val messages: List<ChatMessage> get() {
        val base = snapshot?.messages ?: emptyList()
        val opts = conversationId?.let { sessionCache.pendingUserMessages[it] }?.toList() ?: emptyList()
        if (opts.isEmpty()) return base
        // 本地只补充尚未被 Core 确认的输入，接纳与执行时序由 Core 快照决定。
        val baseIds = base.mapTo(HashSet()) { it.id } // O(n) 一次建集，避免逐条 O(n) 线性扫描
        return base + opts.filter { it.id !in baseIds }
    }
    val pendingQuestion: QuestionRequest? get() = snapshot?.pendingQuestion
    val tokenUsage: TokenUsage get() = snapshot?.tokenUsage ?: TokenUsage()

    /** 当前上下文真实占用（token）：最近一次请求 API 报告的 prompt 大小，与自动压缩触发同源 */
    val contextUsedTokens: Long get() = snapshot?.contextUsedTokens ?: 0L

    /** 自动压缩阈值百分比（30-95），UI Slider 直接转发 AppState（persist + aiCore 透传异步） */
    val compactionThresholdPercent: StateFlow<Int> = appState.compactionThresholdPercent
    fun setCompactionThreshold(value: Int) = appState.setCompactionThreshold(value)

    /** 主会话 LLM HTTP 请求累计次数（事件累加 + 历史回填，见 SnapshotReducer/MederiModelMapper） */
    val requestCount: Int get() = snapshot?.requestCount ?: 0

    /** 最近一次 LLM 请求用量（null = 无数据，UI 卡片隐藏） */
    val lastRequestUsage: LastRequestUsage? get() = snapshot?.lastRequestUsage

    /**
     * 当前会话的"发送请求时刻"（epoch ms）——StatusBar 计时锚点。
     * 来自 [sessionCache.turnStartByConv]（send 时记录，turn 结束清除），切换会话回来不重置。
     * null = 当前会话没有进行中的发送（StatusBar 不显示计时）。
     */
    val turnStartedAt: Long?
        get() = conversationId?.let { sessionCache.turnStartByConv[it] }

    /**
     * 上下文窗口（token）：唯一真理源 = AppState.selectedModel 的派生 StateFlow。
     * UI 用 collectAsState 订阅（读 `.value` 不建立订阅，模型切换后指标卡会停留旧值）。
     */
    val contextWindow: StateFlow<Int?> = appState.selectedModel
        .map { it?.contextWindow }
        .stateIn(viewModelScope, SharingStarted.Eagerly, appState.selectedModel.value?.contextWindow)
    val cost: CostSummary get() = snapshot?.cost ?: CostSummary()

    /**
     * 预估成本（参考价）。
     *
     * 用当前选中模型的 models.dev 目录参考价 × 会话累计 token 估算。
     * 是量级参考不是真实账单——不知道用户 plan/折扣，也不区分会话中途换过模型。
     * 任一单价缺失（目录没标价）时返回 0，卡片显示 $0.00。
     *
     * 响应式说明：本 getter 读取 selectedModel 与 snapshot（tokenUsage），在组合期间
     * 只要消费方订阅了 [contextWindow]（同源 selectedModel 流）或读取了任一 Compose
     * State（tokenUsage），getter 就会在每次重组时重新求值，不会停留旧值。
     */
    val referenceCostUsd: Double
        get() {
            val model = appState.selectedModel.value ?: return 0.0
            val inputPrice = model.inputPricePerMillion ?: return 0.0
            val outputPrice = model.outputPricePerMillion ?: return 0.0
            val usage = tokenUsage
            return usage.input / 1_000_000.0 * inputPrice + usage.output / 1_000_000.0 * outputPrice
        }
    val todos: List<TodoItem> get() = snapshot?.todos ?: emptyList()
    val childConversations: List<Conversation> get() = snapshot?.childConversations ?: emptyList()
    val conversation: Conversation? get() = snapshot?.conversation

    // ------------------------------------------------------------------
    // 子代理 MVVM 状态：直接收敛入 ConversationState（单一真理源）
    // UI 读 [subagents]（当前会话）渲染追踪器卡片/详情；读 [allSubagents] 渲染全局列表。
    // 汇报正文不进这里——它在 subagent(WAIT) 的 tool result 里（数据库），
    // UI 用 SubagentReportMarkdown.fromToolResult(toolName, resultJson) 转 markdown 展开渲染。
    // ------------------------------------------------------------------

    /** 全部会话的子代理（从缓存中所有会话的 conversationState.subagents 汇总派生，按启动时间排序）。 */
    val allSubagents: List<xyz.mederi.core.contract.models.SubagentState>
        get() = sessionCache.conversationStates.values
            .flatMap { it.subagents }
            .distinctBy { it.agentId }
            .sortedBy { it.startedAt }

    /** 当前会话的子代理列表（直接来自当前会话状态机，天然响应式）。 */
    val subagents: List<xyz.mederi.core.contract.models.SubagentState>
        get() = conversationState?.subagents.orEmpty()

    /** 查询单个子代理详情数据（优先当前会话，其次从所有会话状态机中查找）。 */
    fun subagent(agentId: String): xyz.mederi.core.contract.models.SubagentState? {
        return conversationState?.subagents?.find { it.agentId == agentId }
            ?: sessionCache.conversationStates.values
                .flatMap { it.subagents }
                .find { it.agentId == agentId }
    }

    /** subagent / wait_agent / agent_status 工具结果 → 汇报 markdown（null = 非子代理汇报，普通工具卡片渲染）。 */
    fun subagentReportMarkdown(toolName: String, resultJson: String): String? =
        SubagentReportMarkdown.fromToolResult(toolName, resultJson)

    private val backgroundTaskPresenter = BackgroundTaskPresenter(appState.aiCore, viewModelScope)
    internal val backgroundTaskState: BackgroundTaskQueryState get() = backgroundTaskPresenter.state
    val backgroundTasks get() = backgroundTaskState.tasks

    /** Route持有可见性租约；叶子不调度轮询，释放只暂停读取。 */
    fun observeBackgroundTasks(): () -> Unit = backgroundTaskPresenter.observe()
    fun refreshBackgroundTasks() = backgroundTaskPresenter.refresh()
    fun clearBackgroundTaskStopError(taskId: String) = backgroundTaskPresenter.dismissStopError(taskId)

    /**
     * 展平后的聊天列表（派生缓存）。
     *
     * derivedStateOf：snapshot / optimisticUserMessage 任一变化时自动重算，
     * 无变化时重组直接复用旧 List 实例（引用相等 → LazyColumn 跳过无关节点重组）。
     * 相比手动 recomputeChatItems() 的优势：派生关系由框架保证，不存在
     * "忘了手动调用导致 UI 不同步"的问题。
     */
    val chatItems: List<ChatListItem> by derivedStateOf {
        computeChatItems(messages)
    }

    /**
     * 本会话的全部文档（概览"文档"行数据源）：从对话流投影 artifact 文档卡，
     * 按 artifactId 去重保序——不依赖阅读器是否打开过；计划不在此列（实施计划行单独展示）。
     */
    val sessionDocuments: List<ChatListItem.DocumentCard> by derivedStateOf {
        chatItems.filterIsInstance<ChatListItem.DocumentCard>()
            .distinctBy { it.artifactId }
    }

    /**
     * 当前会话是否正在手动压缩中（compaction RUNNING/IDLE STATUS 事件维护的 VM 本地态）。
     * 不写快照、不动契约：仅供 UI（Workspace）读取覆盖 TurnStatus，压缩结束由 IDLE 事件 /
     * 切会话 / snapshot status 三重复位。
     */
    var isCompacting by mutableStateOf(false)
        private set

    /**
     * 压缩完成的一次性轻提示（秒级自动消失）。false = 不显示。
     * IDLE 事件（压缩成功）时置位 true，3 秒后自动清除。
     */
    var compactionCompletedNotice by mutableStateOf(false)
        private set

    /**
     * 「用默认程序打开文件失败」的一次性提示（5 秒自动消失）。false = 不显示。
     * `confirmPendingFileOpen` 收到 openFile 返回 false 时置位——打开失败绝不静默
     * （历史教训：PlatformUtils.openFile 曾吞异常，macOS 沙箱拦截 LaunchServices 时
     * 用户点「打开」毫无反应）。
     */
    var fileOpenErrorNotice by mutableStateOf(false)
        private set

    /**
     * 「计划/Spec 按需读盘失败」的一次性轻提示（值为读盘失败的计划标题，5 秒自动消失）。
     * null = 不显示；[openPlanDocument] 读盘失败时置位——失败不静默、不打开阅读器、
     * 不用降级占位内容伪装成功（历史问题：遥控端 fileTreeProvider=null 时只见标题摘要占位）。
     */
    var planReadErrorNotice by mutableStateOf<String?>(null)
        private set

    /**
     * 是否有子智能体正在工作（当前会话存在 RUNNING 状态的子代理）。
     * 状态判定复用 `ui/SubagentLifecycleNotification.kt` 的纯函数 [hasRunningSubagents]（同一谓词单一真理源，
     * 以 SUBAGENT_* 事件驱动），不再叠加 chatItems 层判定。
     */
    val hasRunningSubagents: Boolean by derivedStateOf {
        hasRunningSubagents(subagents)
    }

    // ==========================================
    // 派生展示状态（View 直接读取，零业务逻辑）
    // ==========================================

    /** 顶部面包屑标题（项目名 + 会话名，含 fallback 规则） */
    data class HeaderTitle(val projectName: String?, val conversationName: String?, val conversationId: String?)

    /**
     * 顶部面包屑标题：完全由 AppState 三个 StateFlow combine 的**派生** StateFlow
     * （不是副本：源流任一发射即重算）。UI 用 collectAsState 订阅——此前是 getter
     * 直读 `StateFlow.value`（不建立订阅），项目/会话改名后面包屑会停留旧值。
     * conversationName 为 null = 未选中会话或标题缺失，fallback 文案由 UI 层决定（i18n）。
     */
    val headerTitle: StateFlow<HeaderTitle> = combine(
        appState.projects,
        appState.selectedProjectId,
        appState.selectedConversationId,
    ) { projects, selectedProjectId, convId ->
        val projectName = projects.find { it.id == selectedProjectId }?.name
        val convName = if (convId != null) {
            projects.flatMap { it.conversations }.find { it.id == convId }?.title
        } else null
        HeaderTitle(projectName, convName, convId)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HeaderTitle(null, null, null))

    /** 环境态/过程提示（statusHint，如限流重试中），StatusBar 过程状态展示用。错误/警告不在此——走 ErrorBoard */
    val statusHint: String? get() = snapshot?.statusHint

    /** 当前对话轮次的实时状态（授权等待由卡片本身承载，状态条不重复提示） */
    val turnStatus: TurnStatus
        get() {
            val convId = conversationId
            val hasPendingSend = convId != null && sessionCache.pendingUserMessages.containsKey(convId)
            val snap = snapshot
            // 快照未就绪（刚 attach / 新会话创建中）：有待发送消息 = 正在发送
            if (snap == null) {
                return if (hasPendingSend) TurnStatus.Sending else TurnStatus.Idle
            }
            val derived = deriveTurnStatus(snap)
            // 消息已提交但 turn 还没开始（乐观消息尚未被快照认领）→ 发送中
            if (derived == TurnStatus.Idle && hasPendingSend) return TurnStatus.Sending
            return derived
        }

    /**
     * 实际生效的思考等级——**唯一真理源**（[ReasoningMenu.resolve] 推导）：
     * 模型记忆（AppState 持久化，用户上次为该模型选择）> 模型默认档（MEDIUM 优先，否则首档）。
     *
     * 这是 [AppState.selectedModel] × [AppState.modelReasoningLevels] 的**派生** StateFlow
     * （同一 resolve 推导链，不是副本）：模型切换、档位记忆更新都自动重新推导，
     * 显示（推理选择器，collectAsState 订阅）与发送（[computeEffectiveThinkingLevel]）同源。
     * 禁止另行回退或持有瞬态副本状态——历史上瞬态 + 快照 + 记忆多源并存，
     * 导致"界面显示推理高、实际发送 null→NONE 没推理"的显示与发送不一致。
     * null 表示模型不支持推理（UI 隐藏选择器，发送侧解析为关闭推理）。
     */
    val effectiveThinkingLevel: StateFlow<String?> = combine(
        appState.selectedModel,
        appState.modelReasoningLevels,
    ) { model, memoryLevels ->
        resolveEffectiveThinkingLevel(model, model?.let { memoryLevels[it.id] })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, computeEffectiveThinkingLevel())

    /**
     * 同步现算生效思考等级：事件处理器（send / rollbackMessage）在非组合上下文调用，
     * 直接读 StateFlow.value 同样可靠（Eagerly 常驻），但显式走同一推导函数语义更明确。
     */
    private fun computeEffectiveThinkingLevel(): String? {
        val model = appState.selectedModel.value
        return resolveEffectiveThinkingLevel(model, appState.getModelReasoningLevel(model?.id ?: ""))
    }

    private fun resolveEffectiveThinkingLevel(model: ModelOption?, memoryLevel: String?): String? {
        if (model == null) return null
        if (!model.supportsThinking || model.reasoningLevels.isEmpty()) return null
        return ReasoningMenu.resolve(
            modelMemoryLevel = memoryLevel,
            modelLevels = model.reasoningLevels
        )
    }

    // ==========================================
    // 问询（ask_user）交互状态机
    // ==========================================

    /** 每题收集的答案（题 index -> 用户选择的选项文本列表） */
    var questionAnswers by mutableStateOf<Map<Int, List<String>>>(emptyMap())
        private set

    /** 当前展示的题页 index */
    var questionPage by mutableStateOf(0)
        private set

    fun answerQuestion(index: Int, answer: String) {
        questionAnswers = questionAnswers + (index to listOf(answer))
    }

    fun answerQuestion(index: Int, answers: List<String>) {
        questionAnswers = questionAnswers + (index to answers)
    }

    fun nextQuestionPage() {
        questionPage++
    }

    fun prevQuestionPage() {
        if (questionPage > 0) questionPage--
    }

    /**
     * 提交全部答案。组装 core 契约：answers[i] = 第 i 题用户选择的选项文本列表（顺序对应 questions）。
     */
    fun submitQuestion() {
        val question = pendingQuestion ?: return
        val answers = question.questions.mapIndexed { idx, _ ->
            questionAnswers[idx] ?: emptyList()
        }
        resetQuestionState()
        replyQuestion(question.id, answers)
    }

    private fun resetQuestionState() {
        questionAnswers = emptyMap()
        questionPage = 0
    }

    /**
     * 展平后的聊天列表（实现已抽至 core/ui/chat/ChatItemsBuilder.kt 顶层 computeChatItems，
     * 此处保留同名成员薄转发，供既有调用点——chatItems 派生缓存与测试——使用）。
     */
    internal fun computeChatItems(msgs: List<ChatMessage>): List<ChatListItem> =
        computeChatItems(
            msgs,
            isWorking = isWorking,
            snapshot = snapshot,
            isSending = turnStatus == TurnStatus.Sending,
            pendingStartedAt = conversationId?.let { sessionCache.pendingUserMessages[it]?.firstOrNull()?.createdAt },
        )

    /** 工具目标探测（实现已抽至 core/ui/chat/ToolTargetResolver.kt 顶层 probeToolTarget，此处薄转发）。 */
    internal fun probeToolTarget(name: String, input: Map<String, String>): String? =
        probeToolTarget(name, input, snapshot)

    // 会话后台观察 Job（sessionCache.observeJobs）已收敛进 [sessionCache]（SessionUiCache，见文件底部）：
    // **核心机制：会话一旦被 attach 过，就常驻一条观察流持续更新会话状态机，
    // 切走不 cancel、切回不重建。** SSE 事件来了 → observeConversation 流 emit →
    // applySnapshot 写入缓存 → 当前会话时同步渲染。切换会话只是把 [conversationId]
    // 指向另一个缓存的键，UI 立即渲染最新状态机（零闪烁、不丢 streaming 增量、保持子代理与扩展面板）。
    // 生命周期：随 viewModelScope 销毁；会话观察抛异常（如会话被删除）时按 1s→30s 封顶无限退避自动重试，不清缓存。
    // 仅 Job 正常结束/取消（含 viewModelScope 销毁）时移除 observeJobs 条目，下次 attach 重建。
    // 乐观消息对账日志去重：同一会话的 matched/unmatched 结论只打一次（状态翻转时重置）
    private val pendingOptReconciled = mutableSetOf<String>()

    init {
        feedbackPresenter.bind(feedbackOwner)
        viewModelScope.launch {
            appState.selectedConversationId.collect { id ->
                if (id != conversationId) attach(id)
            }
        }
        viewModelScope.launch {
            appState.selectedProjectId.collect { id ->
                if (id != inputProjectId) {
                    inputProjectId = id
                    if (conversationId == null) {
                        fileOpenRequestSeq++
                        reportLoader.deactivate()
                        readerStates.keys.filterIsInstance<ReaderOwner.Draft>().forEach { owner ->
                            readerStates[owner]?.let { readerStates[owner] = it.copy(pendingFileOpen = null) }
                        }
                        inputContextVersion++
                    }
                }
                if (conversationId == null) {
                    feedbackPresenter.bind(FeedbackOwner.Draft(id))
                    restoreUnacceptedInputs("new:$id")
                }
            }
        }

        // 手动压缩预检旁路：core 判定"没有可压缩内容"时只发 STATUS 事件（scope=compaction），
        // 不翻会话状态机 → 没有 SESSION_UPDATED，onSnapshot 那条清锚点路径不会触发。
        // 这里收尾：清掉 requestCompaction 事先设的 turnStart 锚点（否则 StatusBar 计时挂着一个
        // "已耗时 0"），再派发一次性提示 effect。按会话过滤，避免别的会话的压缩事件串到当前弹窗。
        viewModelScope.launch {
            appState.aiCore.isReady.first { it }
            appState.aiCore.events()
                .filter { it.type == xyz.mederi.core.contract.models.CoreEventType.STATUS &&
                    it.payload["scope"] == "compaction" &&
                    it.sessionId == conversationId }
                .collect { e ->
                    when (e.payload["code"]) {
                        // RUNNING：压缩进行中，仅置位本地 isCompacting。不清 turnStart 锚点
                        // （压缩中 StatusBar 计时仍从 turnStart 算，属同一 turn），不派提示。
                        "RUNNING" -> isCompacting = true
                        // IDLE：压缩结束，复位本地态。
                        "IDLE" -> {
                            isCompacting = false
                            compactionCompletedNotice = true
                            viewModelScope.launch {
                                delay(3000)
                                compactionCompletedNotice = false
                            }
                        }
                        // SKIPPED（或其它）：预检命中，core 没翻状态机，turnStart 锚点是
                        // requestCompaction 设的，这里收尾清掉，否则 StatusBar 计时会挂着
                        // 一个"已耗时 0"不清；再派一次性提示 effect。
                        else -> {
                            sessionCache.turnStartByConv.remove(e.sessionId)
                            _effects.trySend(
                                UiEffect.ShowCompactionNotice(
                                    code = e.payload["code"] ?: "SKIPPED",
                                    reason = e.payload["reason"]
                                )
                            )
                        }
                    }
                }
        }
    }

    fun attach(id: String?) {
        DebugLog.event("UI", "attach: id=$id")
        backgroundTaskPresenter.bind(id)
        diffPresenter.bind(id)
        if (conversationId != id) {
            beginReaderAction()
            inputContextVersion++
            if (creationDraftTarget != id) creationDraftTarget = null
            // R15：切会话立即失效旧对话框归属并关闭面板，防止旧会话的异步预览/提交响应写入新会话。
            dialogOwnerSessionId = null
            // T12：切会话同时作废在途面板请求（代次不再匹配），旧会话的迟到响应一律丢弃。
            activeDialogRequest = null
            _dialogState.value = WorkspaceDialogState.None
        }
        draftDebounceJob?.cancel()
        resetQuestionState()
        // compaction 本地态不随会话走：切会话复位，防别的会话压缩事件串台
        isCompacting = false
        compactionCompletedNotice = false
        if (id == null) {
            conversationId = null
            conversationState = null
            isAttached = false
            pendingOptReconciled.clear()
            feedbackPresenter.bind(feedbackOwner)
            inputDraft = TextFieldValue(unattachedDraft)
            restoreUnacceptedInputs("new:${appState.selectedProjectId.value}")
            return
        }
        conversationId = id
        pendingOptReconciled.clear()
        feedbackPresenter.bind(feedbackOwner)

        // 切换只换渲染源：立即用该会话的缓存状态机渲染（后台观察流一直在持续更新它），
        // 不再 conversationState = null 再从 store 重建，也绝不 cancel 该会话的观察 Job——
        // 这样切回正在流式的会话时 UI 零闪烁、流式增量一个不丢，子代理与 Dock 展开状态完整保留。
        val cached = sessionCache.conversationStates[id]
        if (cached != null) {
            conversationState = cached
            isAttached = true
            acceptCoreFeedback(id, cached)
            // 切换到已缓存的会话时，显式按缓存会话对象直接恢复设置（model / agent / apiKey / thinkingLevel）。
            // 仅在切换回当前对话时允许读一次设置，其他时候绝不允许读取或覆盖。
            applySessionSettings(cached.conversation)
            hydratedSessions.add(id)
            DebugLog.event(
                "UI",
                "attach: rendered from state pool (status=${cached.conversation.status}, messages=${cached.messages.size}, subagents=${cached.subagents.size}, dock=${cached.activeDockPanel})"
            )
        } else {
            // 无缓存（首次打开）：直接从已有的 projects 会话列表寻找该会话恢复设置，并重置渲染态等观察流首帧快照
            conversationState = null
            isAttached = false
            val projectConv = appState.projects.value.flatMap { it.conversations }.find { it.id == id }
            if (projectConv != null) {
                applySessionSettings(projectConv)
                hydratedSessions.add(id)
            }
        }
        ensureObserving(id)
        diffPresenter.setVisible(activeDockPanel == RightDockPanel.DIFF)
        if (activeDockPanel == RightDockPanel.ARTIFACTS) selectedReport()?.let { reportLoader.activate(it.request.key) }
    }

    /**
     * 确保该会话有一条常驻观察流（幂等：已有活跃 Job 则不动）。
     *
     * 观察流 = [AiCore.observeConversation]（从 store 构建初始快照 + 订阅该会话事件聚合）。
     * 它持续把最新状态机写入 [sessionCache.conversationStates]（及当前会话的渲染状态）——即使 UI 已切到别的会话，
     * 这条流也不会被取消，所以该会话的流式增量（推理/正文/tool delta）始终完整、始终最新。
     *
     * 失败语义：观察流任意异常（会话被删的 NotFound、跨进程 DB busy、HTTP/SSE 抖动等）都按
     * 1s 起倍增、封顶 30s 的无限退避自动重试（[CancellationException] 除外，直接透传不重试）；
     * 失败期间**不删除** [sessionCache.conversationStates] 缓存条目（该条目只允许 attach 流程与
     * 会话删除清理等既有路径删除）。Job 结束（flow 正常完成/取消）时移除 [sessionCache.observeJobs]
     * 条目，下次 attach 可重建。
     */
    private fun ensureObserving(id: String) {
        if (sessionCache.observeJobs[id]?.isActive == true) return
        sessionCache.observeJobs[id] = viewModelScope.launch {
            var attempt = 0
            try {
                appState.aiCore.observeConversation(id)
                    .retryWhen { e, _ ->
                        if (e is CancellationException) return@retryWhen false
                        attempt++
                        val delayMs = (1000L shl (attempt - 1).coerceAtMost(5)).coerceAtMost(30_000L)
                        DebugLog.error("UI", "observe conversation $id failed (attempt=$attempt): ${e.message}, retry in ${delayMs}ms", e)
                        delay(delayMs)
                        true
                    }
                    .collect { snap -> applySnapshot(id, snap) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 无限重试下正常不可达；仅为防御性兜底
                DebugLog.error("UI", "observe conversation $id terminated: ${e.message}", e)
            } finally {
                sessionCache.observeJobs.remove(id)
            }
        }
    }

    /**
     * 将新快照写入缓存，若该会话是当前渲染会话则同步到 UI 状态（attach/后台观察流共用）。
     */
    private fun applySnapshot(id: String, snap: ConversationState) {
        val liveExecutionRequests = snap.pendingExecutionApprovals.mapTo(mutableSetOf()) { it.id }
        executionApprovalSubmissions.keys.filter { it.first == id && it.second !in liveExecutionRequests }
            .forEach { executionApprovalSubmissions.remove(it) }
        DebugLog.debug("UI", "snapshot received: status=${snap.conversation.status}, messages=${snap.messages.size}, subagents=${snap.subagents.size}")
        if (conversationId != id && snap.conversation.status == ConversationStatus.Error) {
            DebugLog.event(
                "UI",
                "background session failed: id=$id, current=$conversationId, error='${snap.errorMessage}', errorId=${snap.errorId}"
            )
        }
        // Core 接收即按稳定 ID 落库；相同正文、图片和重复输入不能互相确认。
        val acceptedIds = snap.messages.mapTo(mutableSetOf()) { it.id }
        sessionCache.pendingUserMessages[id]?.let { pending ->
            pending.removeAll { it.id in acceptedIds }
            if (pending.isEmpty()) sessionCache.pendingUserMessages.remove(id)
        }
        val prevSnap = sessionCache.conversationStates[id]
        val wasWorking = prevSnap?.conversation?.status == ConversationStatus.Working
        // 合并会话专属的本地 UI 状态（写侧输入设置 / activeDockPanel / browserAutomation / 子代理），
        // 防被来自 Core 的空值冲刷；activeDockPanel 新规则（browserAutomation 非空时信任快照值）见 mergeLocalUiState。
        val effectiveSnap = mergeLocalUiState(snap, prevSnap)
        val effectiveConv = effectiveSnap.conversation
        sessionCache.conversationStates[id] = effectiveSnap
        acceptCoreFeedback(id, effectiveSnap)
        if (conversationId == id) {
            conversationState = effectiveSnap
            diffPresenter.setVisible(activeDockPanel == RightDockPanel.DIFF)
            isAttached = true
            // 首次收到快照且该会话尚未水合过设置（冷启动等场景）：水合一次会话设置
            if (hydratedSessions.add(id)) {
                applySessionSettings(effectiveConv)
            }
            // compaction 兜底复位：当前会话快照已 Idle/Error（MESSAGE_COMPLETED/ERROR）而
            // compaction IDLE 事件丢失时，isCompacting 可能卡 true——按快照状态复位。
            if (snap.conversation.status == ConversationStatus.Idle || snap.conversation.status == ConversationStatus.Error) {
                isCompacting = false
            }
        }
        // 仅在真实离开 Working 状态（turn 结束）时清除 StatusBar 计时锚点；
        // 状态机保留在 sessionCache.conversationStates 池中，保证切走切回零闪烁并保持子代理与 Dock 展开状态
        if (wasWorking && snap.conversation.status != ConversationStatus.Working && snap.conversation.status != ConversationStatus.WaitingUser) {
            DebugLog.debug("UI-Timer", "turn ended for $id (wasWorking=$wasWorking, newStatus=${snap.conversation.status}), removing timer")
            sessionCache.turnStartByConv.remove(id)


        }
    }

    /**
     * attach 拿到会话对象后直接读其四列（model / apiKey / agent / thinkingLevel）设四个选择器。
     * 会话是真理源：只改 AppState 内存态（selectModel/selectApiKey/selectAgent/setModelReasoningLevel
     * 均为纯设值，不写库、不写偏好文件），写库只由 WorkspaceViewModel 同名方法（UI 用户操作）负责。
     *
     * - 模型解析：优先 id+provider 精确匹配；modelProvider 可能为 null（AIModel 不含 provider，
     *   modelToProvider 映射缺失时），此时回退到按 id 匹配，避免会话有模型却恢复不出 → 输入框不刷新。
     * - apiKeyId：按当前模型供应商写入 per-provider map，null = 移除条目（默认 key 语义）。
     * - thinkingLevel：写进模型记忆载体（setModelReasoningLevel，null = 清 key），
     *   供 [effectiveThinkingLevel] 推导链使用（记忆 > 默认档）。
     * - 全新未发送会话（modelId == null）→ 不覆盖选择器（保留用户当前选择）；冷启动兜底在 init。
     */
    private fun applySessionSettings(conv: Conversation) {
        isHydratingSettings = true
        try {
            // 恢复草稿文本（即使无模型也要恢复）
            if (creationDraftTarget == conv.id) {
                // 创建请求挂起期间用户可能继续输入，首次水合不得用创建前的空草稿覆盖。
                creationDraftTarget = null
            } else {
                inputDraft = TextFieldValue(conv.draftText ?: "")
            }
            restoreUnacceptedInputs(conv.id)
            if (conv.modelId == null) return
            val all = appState.availableModels.value
            val model = all.find { it.id == conv.modelId && it.provider == conv.modelProvider }
                ?: all.find { it.id == conv.modelId }

            val agent: AgentOption? = conv.agent?.let { agentOrMode ->
                val mode = runCatching { AgentMode.valueOf(agentOrMode) }.getOrNull()
                if (mode != null) {
                    appState.availableAgents.value.find { it.mode == mode }
                } else {
                    appState.availableAgents.value.find { it.id == agentOrMode }
                }
            }
            // model 解析不出（模型已下架/未加载）时保持当前 selection 不清空；agent 仍按会话恢复
            model?.let { appState.selectModel(it) }
            agent?.let { appState.selectAgent(it.id) }
            model?.let { m ->
                appState.selectApiKey(m.provider, conv.apiKeyId)
                appState.setModelReasoningLevel(m.id, conv.thinkingLevel)
            }
        } finally {
            isHydratingSettings = false
        }
    }

    fun detach() = attach(null)

    /**
     * 更新推理档位（推理选择器唯一入口）：写入模型记忆（AppState 持久化）。
     * 生效值 = effectiveThinkingLevel 重新推导，显示与发送自动同源，无副本状态。
     */
    fun updateThinkingLevel(level: String?) {
        val model = appState.selectedModel.value ?: return
        appState.setModelReasoningLevel(model.id, level)
        saveComposeState()
    }

    fun selectModel(model: ModelOption?) {
        appState.selectModel(model)
        saveComposeState()
    }

    /** 选定当前模型所在供应商的 API Key（UI key 选择器唯一入口）；null = 用该供应商默认 key。 */
    fun selectApiKey(providerId: String, apiKeyId: String?) {
        appState.selectApiKey(providerId, apiKeyId)
        saveComposeState()
    }

    fun selectProject(id: String?) {
        workspacePresenter.resetDraft()
        refreshWorkspaceContext(id)
        appState.selectProject(id)
    }

    fun selectConversation(id: String?) {
        appState.selectConversation(id)
    }

    /**
     * 图片附件守卫（唯一守卫，send 与 rollbackMessage 共用）：
     * 当前模型不支持图片输入时剔除放行——不拒绝发送，设置一次性轻提示 [imageStrippedNotice]；
     * 图片剔除由 core 按模型能力在 AI 视图完成，历史仍保留图片块。
     *
     * @return 始终 true（剔除放行）。
     */
    private fun guardImageSupport(model: ModelOption): Boolean {
        if (model.supportsImages) return true
        DebugLog.event("UI", "send: model does not support image input (${model.providerModelId}); stripping images, kept in history")
        imageStrippedNotice = model.name
        return true // 剔除放行：不拒绝发送；core 按模型能力在 AI 视图剔除图片，历史仍保留图片块
    }

    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    fun send(text: String) {
        if (pendingQuestion != null) {
            resetQuestionState()
        }
        val trimmed = text.trim()
        val hasPasted = pendingPastedTexts.isNotEmpty()
        val hasImages = pendingImages.isNotEmpty()
        DebugLog.section("UI", "WorkspaceViewModel.send")
        DebugLog.data("UI", "raw text", "'$text'")
        DebugLog.data("UI", "trimmed text", "'$trimmed'")
        DebugLog.data("UI", "pastedCount", pendingPastedTexts.size)
        DebugLog.data("UI", "imagesCount", pendingImages.size)

        // 图片剔除轻提示"下次发送时清空"：本次 guard 的赋值在 send 流程中途发生，本次提示仍会显示
        imageStrippedNotice = null

        if (trimmed.isEmpty() && !hasPasted && !hasImages) {
            DebugLog.event("UI", "send blocked: text and attachments are all empty")
            return
        }
        val convId = conversationId
        // 未选中会话：若已选中项目，则发送时自动创建新会话；两者都无才报错
        val projectId = if (convId == null) appState.selectedProjectId.value else null
        if (convId == null && projectId == null) {
            DebugLog.event("UI", "send blocked: no conversation and no project")
            feedbackPresenter.showLocal(feedbackOwner, UiMessage(Res.string.err_select_project_or_conversation))
            return
        }
        if (!appState.isReady.value) {
            DebugLog.event("UI", "send blocked: engine not ready")
            feedbackPresenter.showLocal(feedbackOwner, UiMessage(Res.string.err_engine_not_ready))
            return
        }
        val model = appState.selectedModel.value
        if (model == null) {
            DebugLog.event("UI", "send blocked: no model selected")
            feedbackPresenter.showLocal(feedbackOwner, UiMessage(Res.string.err_select_model_first))
            return
        }
        if (hasImages && !guardImageSupport(model)) return
        val sendFeedback = feedbackPresenter.begin(feedbackOwner)

        // 组装最终提示词：用户主指令在前，粘贴大文本在后
        val pastedAttachments = pendingPastedTexts.toList()
        val finalPrompt = PromptComposer.compose(trimmed, pastedAttachments)
        val imageAttachments = pendingImages.toList()
        // 选择项目的 StateFlow 观察可能尚未运行，发送时先同步当前项目，
        // 避免把延迟到达的首次观察误当成“创建期间用户切走”。
        if (convId == null && inputProjectId != projectId) {
            inputProjectId = projectId
            inputContextVersion++
        }
        val originalContextVersion = inputContextVersion
        val originalSettings = buildComposeStateInput().copy(draftText = "")
        val originalDraftSubagents = draftSubagentSettings
        val draftSubagents = originalDraftSubagents.snapshot()
        val originalInput = UnacceptedInput(trimmed, pastedAttachments, imageAttachments)

        // 乐观更新：立刻在 UI 显示用户消息（按会话归档，切会话不丢）
        val now = xyz.mederi.currentTimeMillis()
        val messageId = "msg_${kotlin.uuid.Uuid.random()}"
        val pendingKey = convId ?: "pending_$messageId"
        val optBlocks = mutableListOf<ChatBlock>()
        imageAttachments.forEachIndexed { i, img ->
            optBlocks.add(
                ChatBlock.File(
                    id = "optimistic_img_${now}_$i",
                    name = img.name,
                    url = img.base64DataUrl,
                    mimeType = img.mimeType
                )
            )
        }
        if (finalPrompt.isNotEmpty()) {
            optBlocks.add(ChatBlock.Text(id = "optimistic_text_$now", text = finalPrompt))
        }
        val optMsg = ChatMessage(
            id = messageId,
            conversationId = pendingKey,
            role = ChatRole.User,
            blocks = optBlocks,
            createdAt = now,
            completedAt = now,
            parentMessageId = null,
            model = null,
            agent = null,
            isStreaming = false,
            error = null
        )
        sessionCache.addPendingUserMessage(pendingKey, optMsg)
        clearInputDraft()
        clearPendingAttachments()
        DebugLog.info("UI", "set optimistic user message: key=$pendingKey, id=${optMsg.id}, text='$finalPrompt', images=${imageAttachments.size}")

        val agent = appState.availableAgents.value.find { it.id == appState.selectedAgentId.value }
        val input = ChatPromptInput(
            messageId = messageId,
            text = finalPrompt,
            model = model,
            agent = agent,
            // 唯一真理源：发 effectiveThinkingLevel（与推理选择器显示值同源，ReasoningMenu.resolve 推导）
            thinkingLevel = computeEffectiveThinkingLevel(),
            attachments = imageAttachments.map {
                FileAttachment(
                    name = it.name,
                    mimeType = it.mimeType,
                    bytes = it.bytes
                )
            },
            // 唯一真理源：所选模型供应商选定的 API Key ID（null = 用默认 key）
            apiKeyId = appState.getApiKeyId(model.provider),
        )
        DebugLog.data("UI", "conversationId", convId)
        DebugLog.data("UI", "model", "${model.id} (${model.name}), provider=${model.provider}")
        DebugLog.data("UI", "model.providerModelId", model.providerModelId)
        DebugLog.data("UI", "model.supportsThinking", model.supportsThinking)
        DebugLog.data("UI", "model.reasoningLevels", model.reasoningLevels)
        DebugLog.data("UI", "model.contextWindow", "${model.contextWindow}, maxTokens=${model.maxTokens}")
        DebugLog.data("UI", "agent", "${agent?.id} (${agent?.name}), reasoningLevel=${agent?.reasoningLevel}")
        DebugLog.data("UI", "thinkingLevel (sent)", computeEffectiveThinkingLevel())
        val creation = if (convId == null) {
            val pid = projectId ?: return
            conversationCreation?.takeIf { it.projectId == pid && it.contextVersion == originalContextVersion }
                ?: ConversationCreation(pid, originalContextVersion, viewModelScope.async(start = CoroutineStart.LAZY) {
                    val input = xyz.mederi.core.contract.dto.CreateConversationInput(
                        projectId = pid,
                        agent = agent,
                        workspaceSelection = workspacePresenter.draftSelection
                    )
                    val created = appState.aiCore.createConversation(input)
                    if (created.isSuccess) {
                        val conv = created.getOrThrow()
                        val configured = appState.aiCore.updateConversationSettings(conv.id, originalSettings)
                        if (configured.isFailure) return@async Result.failure(configured.exceptionOrNull()!!)
                        val subagentsConfigured = draftSubagents.persist(appState.aiCore, conv.id)
                        if (subagentsConfigured.isFailure) return@async Result.failure(subagentsConfigured.exceptionOrNull()!!)
                    }
                    created
                }).also { conversationCreation = it }
        } else null
        fun recoverInput(targetId: String?) {
            val key = targetId ?: "new:$projectId"
            unacceptedInputs.getOrPut(key) { mutableListOf() }.add(originalInput)
            val stillOriginal = conversationId == convId && inputContextVersion == originalContextVersion &&
                (convId != null || appState.selectedProjectId.value == projectId)
            if (conversationId == targetId && targetId != null || stillOriginal) restoreUnacceptedInputs(key)
        }
        viewModelScope.launch {
            // 连续首发共用同一个创建请求；每条输入仍以自己的消息 ID 独立发送。
            val targetConvId = if (convId != null) {
                convId
            } else {
                val created = creation!!.result.await()
                if (created.isFailure) {
                    if (conversationCreation === creation) conversationCreation = null
                    val ex = created.exceptionOrNull()
                    DebugLog.error("UI", "auto create conversation failed: ${ex?.message}", ex)
                    feedbackPresenter.publish(sendFeedback,
                        ex?.message?.let { UiMessage(Res.string.err_generic, listOf(it)) }
                            ?: UiMessage(Res.string.err_create_conversation_failed))
                    sessionCache.removePendingUserMessage(pendingKey, messageId)
                    recoverInput(null)
                    return@launch
                }
                val conv = created.getOrThrow()
                if (draftSubagentSettings === originalDraftSubagents) {
                    draftSubagentSettings = xyz.mederi.ui.settings.DraftSubagentSettings()
                }
                sessionCache.removePendingUserMessage(pendingKey, messageId)
                sessionCache.addPendingUserMessage(conv.id, optMsg.copy(conversationId = conv.id))
                if (conversationId == null && inputContextVersion == originalContextVersion &&
                    appState.selectedProjectId.value == projectId
                ) {
                    // 首发创建成功且用户仍在原草稿：打开的文档随草稿移交给新会话。
                    // 切去已有会话或创建失败均不转移，避免同项目不同对话共用标签。
                    readerStates.remove(ReaderOwner.Draft(projectId))?.let { draft ->
                        readerStates[ReaderOwner.Session(conv.id)] = draft.copy(pendingFileOpen = null)
                    }
                    creationDraftTarget = conv.id
                    appState.selectConversation(conv.id)
                }
                conv.id
            }

            // StatusBar 计时锚点：从"发送请求时刻"起算（切会话回来不重置）
            sessionCache.turnStartByConv.getOrPut(targetConvId) { xyz.mederi.currentTimeMillis() }

            val targetFeedback = if (convId == null) feedbackPresenter.begin(FeedbackOwner.Session(targetConvId)) else sendFeedback
            val r = appState.aiCore.sendMessage(targetConvId, input)
            DebugLog.event("UI", "sendMessage result: isSuccess=${r.isSuccess}")
            if (r.isFailure) {
                val ex = r.exceptionOrNull()
                DebugLog.error("UI", "sendMessage failed: ${ex?.let { it::class.simpleName }}: ${ex?.message}", ex)
                feedbackPresenter.publish(targetFeedback,
                    ex?.message?.let { UiMessage(Res.string.err_generic, listOf(it)) }
                        ?: UiMessage(Res.string.err_send_failed))
                sessionCache.removePendingUserMessage(targetConvId, messageId)
                recoverInput(targetConvId)
                if (sessionCache.pendingUserMessages[targetConvId].isNullOrEmpty() && !isWorking) {
                    sessionCache.turnStartByConv.remove(targetConvId)
                }
            }
        }
    }

    fun abort() {
        val id = conversationId ?: return
        viewModelScope.launch { appState.aiCore.abort(id) }
    }

    fun stopSubagent(agentId: String) {
        viewModelScope.launch {
            val r = appState.aiCore.stopSubagent(agentId)
            if (r.isFailure) {
                val ex = r.exceptionOrNull()
                DebugLog.error("UI", "stopSubagent failed: ${ex?.message}", ex)
            }
        }
    }

    /** 停止目标与反馈绑定当前会话；查询刷新由协作者串行合流。 */
    fun stopBackgroundTask(taskId: String) = backgroundTaskPresenter.stop(taskId)

    /**
     * 断流"继续"：重发英文 Continue 走正常发消息流程（新 turn），
     * 历史里的半截 assistant 回复让模型自然续写。
     */
    fun continueAfterInterruption() {
        val id = conversationId ?: return
        DebugLog.event("UI", "continue after stream interruption, conversation=$id")
        send("Continue")
    }

    fun replyQuestion(requestId: String, answers: List<List<String>>) {
        val convId = conversationId ?: return
        viewModelScope.launch {
            appState.aiCore.resolveQuestion(convId, requestId, answers)
        }
    }

    fun rejectQuestion(requestId: String) {
        val convId = conversationId ?: return
        viewModelScope.launch {
            appState.aiCore.resolveQuestion(convId, requestId, emptyList())
        }
    }

    fun refreshFiles() {
    }

    fun openDiff(messageId: String? = null, initialFilePath: String? = null) {
        if (conversationId != null) diffPresenter.open(messageId, initialFilePath)
        updateActiveDockPanel(RightDockPanel.DIFF)
        closeSubagentPanel()
    }

    /** 刷新当前范围，不将单轮变更悄悄切回全会话。 */
    fun refreshDiff() { diffPresenter.refresh() }

    fun selectDiffFile(path: String) { diffPresenter.select(path) }

    fun closeDiff() {
        if (activeDockPanel == RightDockPanel.DIFF) updateActiveDockPanel(null)
    }

    fun toggleReasoning(blockId: String) {
        val cur = reasoningExpanded[blockId] ?: false
        reasoningExpanded = reasoningExpanded + (blockId to !cur)
    }

    fun requestCompaction() {
        val id = conversationId ?: return
        // StatusBar 计时锚点：压缩也是运转过程（core 发 SESSION_UPDATED → Working），
        // 但不经 sendMessage，需手动记录起始时刻——否则重试/等待时 StatusBar 的
        // 已耗时恒为 0（看起来"一直是 0，不动"，分不清是否卡住）。
        // 压缩结束 status 离开 Working 时由 onSnapshot 自动清除锚点。
        sessionCache.turnStartByConv[id] = xyz.mederi.currentTimeMillis()
        viewModelScope.launch {
            appState.aiCore.compressHistory(id)
        }
    }

    fun clearError() {
        feedbackPresenter.dismiss()
    }

    /** 关闭"图片已剔除"轻提示（右上角 × 按钮入口）。 */
    fun dismissImageStrippedNotice() {
        imageStrippedNotice = null
    }

    /** 关闭"计划文档读取失败"轻提示（右上角 × 按钮入口）。 */
    fun dismissPlanReadErrorNotice() {
        planReadErrorNotice = null
    }

    /** 设置遥控端剪贴板能力不可用轻提示（ChatInputCard 剪贴板调用点非 desktop 时调用）。 */
    fun showRemoteCapabilityNotice(notice: RemoteCapabilityNotice) {
        remoteCapabilityNotice = notice
    }

    /** 关闭遥控端剪贴板不可用轻提示（右上角 × 按钮入口）。 */
    fun dismissRemoteCapabilityNotice() {
        remoteCapabilityNotice = null
    }

    /**
     * 选区菜单"添加到对话框"：把文本追加到草稿（直接写草稿真理源）。
     * 草稿为空时直接放入；否则换行追加，光标移到末尾。
     */
    fun appendToInput(text: String) {
        val current = inputDraft.text
        val merged = if (current.isBlank()) text else "$current\n$text"
        inputDraft = TextFieldValue(merged, TextRange(merged.length))
    }

    /**
     * 回退用户消息：删除这条消息及之后的所有记录，然后把消息内容完整粘贴回输入框
     * （主指令回输入框、大段文本附件与图片附件恢复到附件队列达标注原貌）——
     * 用户可切换模型/模式/Agent、修改内容后自行发送，而非原封不动自动重试。
     */
    fun rollbackMessage(conversationId: String, messageId: String, messageText: String) {
        val rollbackFeedback = feedbackPresenter.begin(FeedbackOwner.Session(conversationId))
        val rollbackContextVersion = inputContextVersion
        val rollbackDraftText = inputDraft.text
        val rollbackPasted = pendingPastedTexts.toList()
        val rollbackImages = pendingImages.toList()
        val currentSnap = snapshot?.takeIf { it.conversation.id == conversationId }
        val targetMsg = currentSnap?.messages?.find { it.id == messageId }
        // 先验后切：附着物反解在此完成，回滚失败时输入框与附件队列保持原状
        val restored = restoreInputFromMessage(targetMsg, messageText)

        DebugLog.section("UI", "WorkspaceViewModel.rollbackMessage (退回)")
        DebugLog.info(
            "UI",
            "rollbackMessage: convId=$conversationId, msgId=$messageId, instructionLen=${restored.instruction.length}, pasted=${restored.pastedTexts.size}, images=${restored.images.size}"
        )
        // 不在这里提前 abort()：服务端 rollbackToMessage 内部会 abortAndJoin（等旧 turn
        // 死透再截断）。fire-and-forget 的前置 abort 与回滚请求并发到达服务端时，
        // 滞后的 abort 可能命中刚重发的新 turn 并把它杀掉。

        // 本地快照立即切除该消息及后续所有记录（UI 零等待/防闪烁），并无感剔除未创立的子 Agent
        if (currentSnap != null) {
            val targetIdx = currentSnap.messages.indexOfFirst { it.id == messageId }
            if (targetIdx >= 0) {
                val remaining = currentSnap.messages.take(targetIdx)
                val subagentRegex = Regex("""\bsub_[a-zA-Z0-9]{8}\b""")
                val keptIds = remaining.flatMap { it.blocks }.flatMap { block ->
                    when (block) {
                        is ChatBlock.Text -> subagentRegex.findAll(block.text).map { it.value }.toList()
                        is ChatBlock.ToolCall -> {
                            val values = when (val st = block.state) {
                                is ToolCallState.Pending -> st.input.values
                                is ToolCallState.Running -> st.input.values
                                is ToolCallState.Completed -> st.input.values + st.output
                                is ToolCallState.Failed -> st.input.values + st.error
                            }
                            values.flatMap { subagentRegex.findAll(it).map { m -> m.value } }
                        }
                        else -> emptyList()
                    }
                }.toSet()
                val remainingSubagents = currentSnap.subagents.filter { it.agentId in keptIds }
                DebugLog.info(
                    "UI",
                    "rollbackMessage: locally slicing messages from ${currentSnap.messages.size} down to ${remaining.size}, subagents from ${currentSnap.subagents.size} to ${remainingSubagents.size}"
                )
                val updatedState = currentSnap.copy(
                    messages = remaining,
                    subagents = remainingSubagents
                )
                conversationState = updatedState
                sessionCache.conversationStates[conversationId] = updatedState
            }
        }

        // 服务端回滚；成功后才把内容粘贴回输入区（失败 → 错误走 ErrorBoard，输入区保持原状）
        viewModelScope.launch {
            DebugLog.info("UI", "rollbackMessage: calling aiCore.rollbackToMessage(convId=$conversationId, msgId=$messageId)")
            val rollbackResult = appState.aiCore.rollbackToMessage(conversationId, messageId)
            if (rollbackResult.isFailure) {
                val ex = rollbackResult.exceptionOrNull()
                DebugLog.error("UI", "rollbackMessage rollbackToMessage failed: ${ex?.message}", ex)
                feedbackPresenter.publish(rollbackFeedback, UiMessage(Res.string.err_rollback_failed, listOf(ex?.message ?: "?")))
                return@launch
            }

            if (restored.isEmpty) {
                DebugLog.info("UI", "rollbackMessage: nothing to restore, leaving input untouched")
                return@launch
            }
            // Core回滚照常完成；仅原输入上下文仍有效且未被更新操作取代时恢复编辑。
            if (this@WorkspaceViewModel.conversationId != conversationId || inputContextVersion != rollbackContextVersion ||
                !feedbackPresenter.isCurrent(rollbackFeedback) || inputDraft.text != rollbackDraftText ||
                pendingPastedTexts.toList() != rollbackPasted || pendingImages.toList() != rollbackImages) return@launch
            clearPendingAttachments()
            inputDraft = TextFieldValue(restored.instruction, TextRange(restored.instruction.length))
            if (restored.images.isNotEmpty()) pendingImages.addAll(restored.images)
            if (restored.pastedTexts.isNotEmpty()) pendingPastedTexts.addAll(restored.pastedTexts)
            DebugLog.info(
                "UI",
                "rollbackMessage: restored input (instruction + ${restored.pastedTexts.size} pasted + ${restored.images.size} images)"
            )
        }
    }

    override fun onCleared() {
        diffPresenter.close()
        reportLoader.close()
        backgroundTaskPresenter.close()
        sessionCache.observeJobs.values.forEach { it.cancel() }
        sessionCache.observeJobs.clear()
        super.onCleared()
    }
}

/**
 * 会话级缓存族（内部收敛 holder：VM 顶层 4 个散落 map 归入单一实例，字段不对外暴露）。
 *
 * 各成员的语义与原 VM 顶层散落声明完全一致（本次改动只改内部存储结构，不改行为）：
 * - [pendingUserMessages]：等待 Core 接纳确认的本地输入，按稳定消息 ID 对账。
 *   切会话保留；Core 接收即落库，快照包含相同 ID 后移除。
 *   key = conversationId（新会话创建成功后从 pending_xxx re-key 到真实 id）。
 * - [turnStartByConv]：StatusBar 计时锚点：每次发送（send）记录"请求发出时刻"（epoch ms），
 *   按会话归档。计时 = now - turnStartAt（每秒重算），切换会话回来不重置；
 *   turn 结束（快照 status 离开 Working）时清除。
 * - [snapshotCache]：按会话缓存的最后一次快照（conversationId → ConversationSnapshot），
 *   解决会话切换闪烁问题（Bug 1 根因，详见 [WorkspaceViewModel] 顶部说明）。
 * - [observeJobs]：各会话的后台观察 Job（conversationId → Job）。会话一旦被 attach 过就
 *   常驻一条观察流持续更新 [snapshotCache]，切走不 cancel、切回不重建；随 viewModelScope
 *   销毁。观察流抛异常时按 1s→30s 封顶无限退避自动重试（CancellationException 透传）、
 *   不再删缓存；Job 结束（正常完成/取消）时移除条目，下次 attach 可重建。
 */
private class SessionUiCache {
    /** 乐观用户消息列表（按会话归档，支持常规发送与运行中 Steering 插入多条插话） */
    val pendingUserMessages = mutableStateMapOf<String, androidx.compose.runtime.snapshots.SnapshotStateList<ChatMessage>>()

    fun addPendingUserMessage(convId: String, msg: ChatMessage) {
        val list = pendingUserMessages.getOrPut(convId) { androidx.compose.runtime.mutableStateListOf() }
        list.add(msg)
    }

    fun removePendingUserMessage(convId: String, msgId: String) {
        pendingUserMessages[convId]?.removeAll { it.id == msgId }
        if (pendingUserMessages[convId]?.isEmpty() == true) {
            pendingUserMessages.remove(convId)
        }
    }

    /** StatusBar 计时锚点：conversationId → 发送请求时刻（epoch ms） */
    val turnStartByConv = mutableMapOf<String, Long>()

    /** 按会话缓存的状态机池（conversationId → 当前全量 ConversationState） */
    val conversationStates = mutableStateMapOf<String, ConversationState>()

    /** 向后兼容快照缓存访问器别名 */
    val snapshotCache: androidx.compose.runtime.snapshots.SnapshotStateMap<String, ConversationState> get() = conversationStates

    /** 各会话的后台观察 Job（conversationId → Job） */
    val observeJobs = mutableMapOf<String, Job>()


}

/**
 * 合并快照与会话缓存中的本地 UI 状态（[WorkspaceViewModel.applySnapshot] 使用）。
 *
 * 规则：
 * - 写侧输入设置（modelId / modelProvider / agent / apiKeyId / thinkingLevel / draftText）以 prevSnap 优先，
 *   绝不被 Core 推送的快照冲刷；
 * - **activeDockPanel（边缘驱动合并）**：activeDockPanel 是 UI-only 字段——用户点击只写本地
 *   （prevSnap），聚合器归约状态感知不到本地点击，其快照里的 activeDockPanel 只反映事件流的
 *   显式意图（jcef BROWSER_TASK_STARTED 置 BROWSER / 终态置 null）。因此只在事件显式改 dock 的
 *   边沿信任快照（开始边沿开实时浏览器面板、结束边沿关实时浏览器面板），其余一律本地 prevSnap
 *   优先（无 prevSnap 才用快照值）——否则浏览器任务跑过一次的会话里，任何手动开的 Dock 面板
 *   都会被聚合器快照里的陈旧值冲掉（点开即折叠 bug）；
 * - browserAutomation 非空优先；subagents的成员以快照为准，同ID按进度序号和终态时间单调合并。
 */
internal fun mergeLocalUiState(snap: ConversationState, prevSnap: ConversationState?): ConversationState {
    val effectiveConv = if (prevSnap != null) {
        snap.conversation.copy(
            modelId = prevSnap.conversation.modelId ?: snap.conversation.modelId,
            modelProvider = prevSnap.conversation.modelProvider ?: snap.conversation.modelProvider,
            agent = prevSnap.conversation.agent ?: snap.conversation.agent,
            apiKeyId = prevSnap.conversation.apiKeyId ?: snap.conversation.apiKeyId,
            thinkingLevel = prevSnap.conversation.thinkingLevel ?: snap.conversation.thinkingLevel,
            draftText = prevSnap.conversation.draftText ?: snap.conversation.draftText,
        )
    } else {
        snap.conversation
    }
    // activeDockPanel 边缘驱动合并（见函数注释）：只在浏览器任务生命周期边沿信任快照的
    // dock 意图，其余本地 prevSnap 优先——旧规则按"browserAutomation 非空即信任快照"过宽，
    // 浏览器任务跑过一次的会话里每个后续事件快照都会用聚合器的陈旧 null 冲掉手动点击。
    val prevAutomating = prevSnap?.browserAutomation?.isAutomating == true
    val snapAutomating = snap.browserAutomation?.isAutomating == true
    val dockPanel = when {
        // 开始边沿 + 快照携带非空面板（jcef BROWSER_TASK_STARTED → BROWSER）：开实时浏览器面板
        snapAutomating && !prevAutomating && snap.activeDockPanel != null -> snap.activeDockPanel
        // 结束边沿 + 前一状态开的是实时浏览器面板（jcef 终态关 BROWSER）：null 生效关面板
        !snapAutomating && prevAutomating && snap.activeDockPanel == null &&
                prevSnap?.activeDockPanel == RightDockPanel.BROWSER -> null
        // 其余：本地 prevSnap 优先（含 null = 用户手动关）；无 prevSnap（首帧）才用快照值
        prevSnap != null -> prevSnap.activeDockPanel
        else -> snap.activeDockPanel
    }
    return snap.copy(
        conversation = effectiveConv,
        activeDockPanel = dockPanel,
        browserAutomation = snap.browserAutomation ?: prevSnap?.browserAutomation,
        subagents = xyz.mederi.core.contract.SubagentTracker.reconcileSnapshots(
            snap.subagents, prevSnap?.subagents.orEmpty()),
        // activeSubagentPanelAgentId 是 UI-only 字段（Core 不认识，快照里恒为 null），
        // 必须从 prevSnap 兜住，否则每次 Core 发新快照（MESSAGE_DELTA/TOOL_CALLED 等）
        // 都会把子代理面板冲成 null → 面板自动关闭。
        activeSubagentPanelAgentId = snap.activeSubagentPanelAgentId ?: prevSnap?.activeSubagentPanelAgentId,
    )
}
