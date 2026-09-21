package xyz.mederi.core.ui

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
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import okio.ByteString.Companion.toByteString
import xyz.mederi.core.contract.dto.ChatPromptInput
import xyz.mederi.core.contract.dto.ConversationSnapshot
import xyz.mederi.core.contract.dto.FileAttachment
import xyz.mederi.core.contract.models.*
import xyz.mederi.core.ui.appstate.AppState
import xyz.mederi.core.ui.appstate.McpStore
import xyz.mederi.core.ui.appstate.SkillStore

import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.err_create_conversation_failed
import mederi.app.shared.generated.resources.err_engine_not_ready
import mederi.app.shared.generated.resources.err_generic
import mederi.app.shared.generated.resources.err_rollback_failed
import mederi.app.shared.generated.resources.err_select_model_first
import mederi.app.shared.generated.resources.err_select_project_or_conversation
import mederi.app.shared.generated.resources.err_send_failed

import xyz.mederi.isDesktopPlatform
import xyz.emuci.inkcompose.MermaidCacheConfig
import xyz.mederi.util.PromptComposer

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
class WorkspaceViewModel(
    private val appState: AppState,
) : ViewModel() {

    /** 内置浏览器宿主（桌面端注入 UiBrowserHost；遥控端/wasm 为 null，UI 渲染占位） */
    val uiBrowserHost get() = appState.uiBrowserHost

    init {
        // UI 层能力（仅桌面端）：动态监听当前选中项目，将 Mermaid 磁盘缓存目录重定向到项目目录下的 .mederi。
        // android/ios 是遥控端：project.directory 是 server 机器的路径，设备上不存在/无写权限，
        // 注入只会让磁盘缓存静默失效——遥控端固定用本地应用缓存目录（Android 宿主启动时注入 cacheDir，
        // iOS 用 NSCaches 默认值），不走项目重定向。wasmJs 无磁盘，setBaseDirectory 本身是 no-op。
        if (isDesktopPlatform) {
            viewModelScope.launch {
                combine(appState.projects, appState.selectedProjectId) { projects, selectedId ->
                    val project = projects.find { it.id == selectedId }
                    val projectDir = project?.directory?.takeIf { it.isNotBlank() }
                    if (projectDir != null) {
                        "$projectDir/.mederi"
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
     * - 切换不会改动已存在的会话配置快照，属于"下一句话生效"的全局偏好
     * - 切换会自动持久化，重启后恢复
     */
    fun selectAgent(id: String?) {
        DebugLog.info("UI", "WorkspaceViewModel.selectAgent: id=$id")
        appState.selectAgent(id)
    }

    /**
     * 切换执行策略（AUTONOMOUS / APPROVAL）。
     * 只写 AppState（唯一真理源），派生流自动更新。
     */
    fun selectAgentMode(mode: AgentMode) {
        DebugLog.info("UI", "WorkspaceViewModel.selectAgentMode: requested=$mode, currentAgentId=${selectedAgentId.value}")
        appState.selectAgentMode(mode)
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
        // 批准时刻输入框选中的模型/推理档位随批准手势传给 core（写入 session，
        // 同一 turn 后续 spawn 的子代理按此模型执行）——与 send() 取值同源
        // （selectedModel / effectiveThinkingLevel 唯一真理源）
        val selectedModel = appState.selectedModel.value
        val thinkingLevel = computeEffectiveThinkingLevel()
        viewModelScope.launch {
            if (currentPending != null && currentPending.id == planId) {
                appState.aiCore.resolvePlanApproval(convId, planId, approved, selectedModel, thinkingLevel)
            } else if (approved) {
                send("请批准并开始执行已制定的计划 $planId")
            }
        }
    }

    var conversationId by mutableStateOf<String?>(null); internal set
    var snapshot by mutableStateOf<ConversationSnapshot?>(null); private set
    var isAttached by mutableStateOf(false); private set

    /**
     * 会话级缓存族（乐观消息 / 计时锚点 / 快照缓存 / 观察 Job）——内部存储收敛为
     * 单一 [SessionUiCache] 实例（字段不对外暴露，对外读取仍走原属性）。
     *
     * 快照缓存解决会话切换闪烁问题（Bug 1 根因）：切换会话时不再 `snapshot = null`
     * 再从 store 重建——而是缓存当前快照，切回来时立即渲染缓存（含正在流式的推理/消息），
     * 再由事件流持续更新。缓存仅在会话离开 Working 后清理（turn 结束/出错/删除）。
     */
    private val sessionCache = SessionUiCache()
    var reasoningExpanded by mutableStateOf<Map<String, Boolean>>(emptyMap()); private set

    // ------------------------------------------------------------------
    // diff 面板状态（内部收敛为单一 diffState；对外属性名/类型不变。
    // activeDockPanel 同时服务全部 dock 面板且多处逻辑依赖，保守保留独立状态）
    // ------------------------------------------------------------------

    /** diff 面板内部存储（items/selectedPath/showPanel 单点状态） */
    private var diffState by mutableStateOf(DiffUiState())

    /** 当前会话的文件 diff 列表 */
    val diffItems: List<FileDiff> get() = diffState.items

    /** 当前选中的 diff 文件路径 */
    val selectedDiffFilePath: String? get() = diffState.selectedPath

    /** diff 面板是否展开 */
    val showDiffPanel: Boolean get() = diffState.showPanel

    /** 右侧独立功能活动栏当前激活的面板（null 表示收起关闭）。写操作只经下方动作方法（单向数据流） */
    var activeDockPanel by mutableStateOf<RightDockPanel?>(null); private set

    /** 右侧面板是否展开（派生：activeDockPanel != null） */
    val isRightPanelOpen: Boolean
        get() = activeDockPanel != null

    fun toggleDockPanel(panel: RightDockPanel) {
        activeDockPanel = if (activeDockPanel == panel) null else {
            if (panel == RightDockPanel.DIFF) openDiff()
            panel
        }
    }

    fun openDockPanel(panel: RightDockPanel) {
        if (panel == RightDockPanel.DIFF) openDiff()
        activeDockPanel = panel
    }

    fun closeDockPanel() {
        activeDockPanel = null
    }

    /**
     * 在内置浏览器面板预览 Office 文档（.docx/.xlsx/.pptx）。
     * 调 core 转换为 HTML → 注入 uiBrowserHost.loadHtml → 自动打开浏览器面板。
     */
    fun previewOffice(path: String) {
        val host = appState.uiBrowserHost ?: return
        if (!host.isAvailable) return
        val conversationId = this.conversationId ?: return
        viewModelScope.launch {
            appState.aiCore.previewOffice(conversationId, path)
                .onSuccess { html ->
                    val title = path.substringAfterLast('/').ifBlank { "Office Preview" }
                    host.loadHtml(title, html)
                    openDockPanel(RightDockPanel.BROWSER)
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


    /** 当前展示的实施计划内容。写操作只经 openPlanInExtension（单向数据流） */
    var currentPlan by mutableStateOf<PlanItem?>(null); private set

    /** 产物与媒体附件集合 */
    val artifactItems = mutableStateListOf<ArtifactItem>()

    /** 当前激活的产物 ID。写操作只经 openArtifact / open*InExtension（单向数据流） */
    var activeArtifactId by mutableStateOf<String?>(null); private set

    /** 激活已有产物（产物面板 tab 点击） */
    fun openArtifact(artifactId: String) {
        activeArtifactId = artifactId
    }

    fun openImageInExtension(title: String, imageUrl: String) {
        val existing = artifactItems.filterIsInstance<ArtifactItem.Image>().find { it.imageUrl == imageUrl }
        if (existing != null) {
            activeArtifactId = existing.id
        } else {
            val id = "img_${xyz.mederi.currentTimeMillis()}"
            artifactItems.add(ArtifactItem.Image(id = id, title = title, imageUrl = imageUrl))
            activeArtifactId = id
        }
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
        val targetId = id ?: "txt_${title.hashCode().toUInt()}"
        val existingIndex = artifactItems.indexOfFirst { it.id == targetId }
        val updatedItem = ArtifactItem.Text(
            id = targetId,
            title = title,
            content = content,
            lineCount = lineCount,
            charCount = charCount,
            isStreaming = isStreaming,
        )
        if (existingIndex != -1) {
            artifactItems[existingIndex] = updatedItem
            activeArtifactId = targetId
        } else {
            artifactItems.add(updatedItem)
            activeArtifactId = targetId
        }
        openDockPanel(RightDockPanel.ARTIFACTS)
        DebugLog.event("UI", "openTextInExtension: title='$title', activeArtifactId=$activeArtifactId, chars=$charCount, isStreaming=$isStreaming")
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
        currentPlan = PlanItem(
            id = planId,
            title = if (title.isNotBlank()) "计划: $title" else "计划详情",
            content = content
        )
        openDockPanel(RightDockPanel.PLAN)
        DebugLog.event("UI", "openPlanInExtension: planId=$planId, title='$title', contentLen=${content.length}")
    }

    fun toggleRightPanel() {
        if (activeDockPanel != null) {
            activeDockPanel = null
        } else {
            activeDockPanel = RightDockPanel.OVERVIEW
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
    // receiveAsFlow 是单消费者语义：同一时刻通常只有一个 UI 实例在收集
    // （两个 ChatInputCard 共享同一 VM，但实际可见的输入框一般只有一个）；
    // 多实例同时可见时的广播竞争是已知边缘情况，可接受（见 ChatInputCard 收集处注释）。
    private val _effects = Channel<UiEffect>(Channel.BUFFERED)
    val effects: kotlinx.coroutines.flow.Flow<UiEffect> = _effects.receiveAsFlow()

    /** 打开设置对话框（一次性导航命令，UI 收集后置 isSettingsVisible = true） */
    fun openSettings() {
        _effects.trySend(UiEffect.OpenSettings)
    }

    /** 打开项目选择菜单（一次性命令，UI 收集后递增计数触发 ProjectSelectorMenu） */
    fun openProjectMenu() {
        _effects.trySend(UiEffect.OpenProjectMenu)
    }

    // ------------------------------------------------------------------
    // 错误族状态（内部存储收敛为单一 errorState；对外属性名/类型不变）
    // ------------------------------------------------------------------

    /** 错误族内部存储（message/diagnostic/id/断流标记/详情弹窗单点状态） */
    private var errorState by mutableStateOf(ErrorState())

    /**
     * 当前会话的头条错误简报（null = 无错误）。
     */
    val error: UiMessage? get() = errorState.message

    /**
     * 一次性轻提示：图片已从发送内容中剔除（值为模型名，null=不显示）。非错误，不走 error。
     */
    var imageStrippedNotice by mutableStateOf<String?>(null); private set

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

    /**
     * 当前错误是否为断流（流式连接提前中断）。为 true 时 ErrorBoard 显示"继续"按钮，
     * 点击经 [continueAfterInterruption] 重发 Continue 续写半截回复。
     */
    val isStreamInterrupted: Boolean get() = errorState.isStreamInterrupted

    /**
     * 是否正在展示详细错误报告弹窗。
     */
    val isErrorDetailOpen: Boolean get() = errorState.isDetailOpen

    fun showErrorDetail() {
        if (!errorDiagnostic.isNullOrBlank() || error != null) {
            errorState = errorState.copy(isDetailOpen = true)
        }
    }

    fun dismissErrorDetail() {
        errorState = errorState.copy(isDetailOpen = false)
    }

    // 乐观用户消息（sessionCache.pendingUserMessages）与 StatusBar 计时锚点（sessionCache.turnStartByConv）
    // 已收敛进 [sessionCache]（SessionUiCache，见文件底部），语义与文档随成员迁移。

    /** 当前会话的乐观消息（兼容旧引用/测试） */
    val optimisticUserMessage: ChatMessage? get() = conversationId?.let { sessionCache.pendingUserMessages[it] }

    /**
     * 输入框草稿（会话级业务状态，唯一真理源在此）。
     *
     * 欢迎页与消息列表两个 ChatInputCard 调用点共享同一草稿：分支切换（收到第一条回复、
     * 清空会话）不再丢失正在输入的内容。选区菜单"添加到对话框"也直接写这里，
     * 无需事件对象绕道。UI 通过 [updateInputDraft]/[clearInputDraft] 单向写入。
     */
    var inputDraft by mutableStateOf(TextFieldValue("")); private set

    fun updateInputDraft(value: TextFieldValue) {
        imageStrippedNotice = null // 下次输入时清空"图片已剔除"轻提示
        inputDraft = value
    }

    fun clearInputDraft() {
        inputDraft = TextFieldValue("")
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

    /** 用户粘贴的大段文本附件（吸附在输入框中，发送时拼装在主指令之后、metaNote 之前） */
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
        val opt = conversationId?.let { sessionCache.pendingUserMessages[it] } ?: return base
        // 因果时序防护：只有在 base 中存在"时间戳在 opt 之后且正在流式中"的回复（即确由 opt 触发的流式响应），
        // 才能把 opt 插在它前面；早于 opt 的历史消息（无论是否残留流式标记）绝对不可被 opt 抢占前面。
        val streamingIndex = base.indexOfFirst { it.isStreaming && it.createdAt >= opt.createdAt }
        return if (streamingIndex >= 0) {
            base.toMutableList().apply { add(streamingIndex, opt) }
        } else {
            base + opt
        }
    }
    val pendingQuestion: QuestionRequest? get() = snapshot?.pendingQuestion
    val tokenUsage: TokenUsage get() = snapshot?.tokenUsage ?: TokenUsage()

    /** 当前上下文真实占用（token）：最近一次请求 API 报告的 prompt 大小，与自动压缩触发同源 */
    val contextUsedTokens: Long get() = snapshot?.contextUsedTokens ?: 0L

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
    // 子代理 MVVM 缓存（P4）：SUBAGENT_* 事件 → SubagentTracker → SubagentState
    // UI 读 [subagents]（当前会话）渲染追踪器卡片/详情；读 [allSubagents] 渲染全局列表。
    // 汇报正文不进这里——它在 subagent(WAIT) 的 tool result 里（数据库），
    // UI 用 SubagentReportMarkdown.fromToolResult(toolName, resultJson) 转 markdown 展开渲染。
    // ------------------------------------------------------------------

    /** 全部会话的子代理（按启动时间排序）；compose state，事件驱动实时更新。 */
    var allSubagents by mutableStateOf<List<xyz.mederi.core.contract.models.SubagentState>>(emptyList())
        private set

    /** 聚合中间态：agentId → SubagentState（SubagentTracker.apply 的累积表）。 */
    private var subagentStates by mutableStateOf<Map<String, xyz.mederi.core.contract.models.SubagentState>>(emptyMap())

    /** 当前会话的子代理列表（读取 allSubagents + conversationId 两个 state，天然响应式）。 */
    val subagents: List<xyz.mederi.core.contract.models.SubagentState>
        get() = allSubagents.filter { it.parentSessionId == conversationId }

    /** 查询单个子代理详情数据（点开详情 UI 的只读数据源；不在缓存 = 已重启丢失，UI 降级只显示汇报）。 */
    fun subagent(agentId: String): xyz.mederi.core.contract.models.SubagentState? =
        subagentStates[agentId]

    /** subagent / wait_agent / agent_status 工具结果 → 汇报 markdown（null = 非子代理汇报，普通工具卡片渲染）。 */
    fun subagentReportMarkdown(toolName: String, resultJson: String): String? =
        SubagentReportMarkdown.fromToolResult(toolName, resultJson)

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

    /** 用户消息条数（扩展面板"请求次数"展示用） */
    val userMessageCount: Int
        get() = messages.count { it.role == ChatRole.User }

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

/** 子代理生命周期事件集（init 订阅过滤用；SubagentTracker 消费）。 */
private val SUBAGENT_EVENT_TYPES = setOf(
    xyz.mederi.core.contract.models.CoreEventType.SUBAGENT_STARTED,
    xyz.mederi.core.contract.models.CoreEventType.SUBAGENT_COMPLETED,
    xyz.mederi.core.contract.models.CoreEventType.SUBAGENT_ERROR,
    xyz.mederi.core.contract.models.CoreEventType.SUBAGENT_STOPPED
)

    /**
     * 展平后的聊天列表（实现已抽至 core/ui/chat/ChatItemsBuilder.kt 顶层 computeChatItems，
     * 此处保留同名成员薄转发，供既有调用点——chatItems 派生缓存与测试——使用）。
     */
    internal fun computeChatItems(msgs: List<ChatMessage>): List<ChatListItem> =
        computeChatItems(msgs, isWorking = isWorking, snapshot = snapshot)

    /** 工具目标探测（实现已抽至 core/ui/chat/ToolTargetResolver.kt 顶层 probeToolTarget，此处薄转发）。 */
    internal fun probeToolTarget(name: String, input: Map<String, String>): String? =
        probeToolTarget(name, input, snapshot)

    // 会话后台观察 Job（sessionCache.observeJobs）已收敛进 [sessionCache]（SessionUiCache，见文件底部）：
    // **核心机制：会话一旦被 attach 过，就常驻一条观察流持续更新快照缓存，
    // 切走不 cancel、切回不重建。** SSE 事件来了 → observeConversation 流 emit →
    // applySnapshot 写入缓存 → 当前会话时同步渲染。切换会话只是把 [conversationId]
    // 指向另一个缓存的键，UI 立即渲染最新缓存（零闪烁、不丢 streaming 增量）。
    // 生命周期：随 viewModelScope 销毁；会话观察抛异常（如会话被删除）时移除并清缓存。
    // 会话离开 Working（turn 结束/出错）后缓存清除（数据已完整落库，下次切回从 store 重建即可），
    // 但观察流保留——新 turn 的事件仍持续聚合。
    // 乐观消息对账日志去重：同一会话的 matched/unmatched 结论只打一次（状态翻转时重置）
    private val pendingOptReconciled = mutableSetOf<String>()
    private var selectionHydrated = false

    init {
        viewModelScope.launch {
            appState.selectedConversationId.collect { id ->
                if (id != conversationId) attach(id)
            }
        }
        // 浏览器任务监听（app 级，不绑定会话）：子 agent 启动内置 JCEF 浏览器 → 自动展开浏览器面板。
        // 遥控端/wasm 端 uiBrowserHost 为 null（canRenderJcef=false）→ 不展开；camoufox 无头任务不展开。
        // 先等 isReady：MederiAiCore.events() 访问 lateinit mederi，初始化完成前订阅会崩。
        viewModelScope.launch {
            appState.aiCore.isReady.first { it }
            appState.aiCore.events()
                .filter {
                    it.type == xyz.mederi.core.contract.models.CoreEventType.BROWSER_TASK_STARTED &&
                        it.payload["browser"] == "jcef"
                }
                .collect {
                    if (appState.canRenderJcef && activeDockPanel != RightDockPanel.BROWSER) {
                        openDockPanel(RightDockPanel.BROWSER)
                    }
                }
        }

        // 子代理生命周期监听（app 级，不绑定会话）：SUBAGENT_* 事件 → SubagentTracker 聚合进
        // MVVM 缓存（每个子代理一个 SubagentState）。UI（未来）读 [allSubagents] / [subagents]
        // 渲染子代理追踪器与详情——"正在干活"的真实状态 + 主代理派发的命令 + 实际使用的模型。
        viewModelScope.launch {
            appState.aiCore.isReady.first { it }
            appState.aiCore.events()
                .filter { it.type in SUBAGENT_EVENT_TYPES }
                .collect {
                    subagentStates = xyz.mederi.core.contract.SubagentTracker.apply(subagentStates, it)
                    allSubagents = subagentStates.values.sortedBy { s -> s.startedAt }
                }
        }
    }

    fun attach(id: String?) {
        DebugLog.event("UI", "attach: id=$id")
        selectionHydrated = false
        resetQuestionState()
        if (id == null) {
            conversationId = null
            snapshot = null
            isAttached = false
            pendingOptReconciled.clear()
            // 错误族批量重置（与旧实现一致：这里不重置 isStreamInterrupted）
            errorState = errorState.copy(message = null, diagnostic = null, id = null, isDetailOpen = false)
            return
        }
        conversationId = id
        pendingOptReconciled.clear()
        // 错误族批量重置（与旧实现一致：这里不重置 isStreamInterrupted，由后续 applySnapshot 覆盖）
        errorState = errorState.copy(message = null, diagnostic = null, id = null, isDetailOpen = false)

        // 切换只换渲染源：立即用该会话的缓存快照渲染（后台观察流一直在持续更新它），
        // 不再 snapshot = null 再从 store 重建，也绝不 cancel 该会话的观察 Job——
        // 这样切回正在流式的会话时 UI 零闪烁、流式增量一个不丢。
        val cached = sessionCache.snapshotCache[id]
        if (cached != null) {
            snapshot = cached
            isAttached = true
            errorState = errorState.copy(
                message = cached.errorMessage?.let { UiMessage(Res.string.err_generic, listOf(it)) },
                diagnostic = cached.errorDiagnostic,
                id = cached.errorId,
                isStreamInterrupted = cached.errorIsStreamInterrupted,
            )
            DebugLog.event("UI", "attach: rendered from cache (status=${cached.conversation.status}, messages=${cached.messages.size})")
        } else {
            // 无缓存（首次打开 / 已 Idle 清理）：异步拉一次初始快照渲染，再交给观察流持续更新
            snapshot = null
            isAttached = false
            viewModelScope.launch {
                val snap = appState.aiCore.getSnapshot(id).getOrNull()
                if (snap == null) {
                    DebugLog.event("UI", "attach: conversation $id not found, clearing stale selection")
                    sessionCache.snapshotCache.remove(id)
                    if (conversationId == id) {
                        conversationId = null
                        snapshot = null
                        isAttached = false
                        appState.selectConversation(null)
                    }
                    return@launch
                }
                applySnapshot(id, snap)
            }
        }
        ensureObserving(id)
    }

    /**
     * 确保该会话有一条常驻观察流（幂等：已有活跃 Job 则不动）。
     *
     * 观察流 = [AiCore.observeConversation]（从 store 构建初始快照 + 订阅该会话事件聚合）。
     * 它持续把最新快照写入 [sessionCache.snapshotCache]（及当前会话的渲染状态）——即使 UI 已切到别的会话，
     * 这条流也不会被取消，所以该会话的流式增量（推理/正文/tool delta）始终完整、始终最新。
     */
    private fun ensureObserving(id: String) {
        if (sessionCache.observeJobs[id]?.isActive == true) return
        sessionCache.observeJobs[id] = viewModelScope.launch {
            try {
                appState.aiCore.observeConversation(id).collect { snap ->
                    applySnapshot(id, snap)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLog.error("UI", "observe conversation $id failed: ${e.message}", e)
                sessionCache.observeJobs.remove(id)
                sessionCache.snapshotCache.remove(id)
            }
        }
    }

    /**
     * 将新快照写入缓存，若该会话是当前渲染会话则同步到 UI 状态（attach/后台观察流共用）。
     */
    private fun applySnapshot(id: String, snap: ConversationSnapshot) {
        DebugLog.debug("UI", "snapshot received: status=${snap.conversation.status}, messages=${snap.messages.size}")
        if (conversationId != id && snap.conversation.status == ConversationStatus.Error) {
            DebugLog.event(
                "UI",
                "background session failed: id=$id, current=$conversationId, error='${snap.errorMessage}', errorId=${snap.errorId}"
            )
        }
        // 乐观消息与快照对账：真实消息已落库（turn 结束/出错兜底时 core 写入）→ 移除乐观消息
        val pendingOpt = sessionCache.pendingUserMessages[id]
        if (pendingOpt != null) {
            val optText = pendingOpt.blocks
                .filterIsInstance<ChatBlock.Text>()
                .joinToString("") { it.text }
            val hasReal = snap.messages.any { msg ->
                if (msg.role == ChatRole.User) {
                    val realText = msg.blocks.filterIsInstance<ChatBlock.Text>()
                        .joinToString("") { it.text }
                    realText == optText || (optText.isNotBlank() && realText.trim() == optText.trim())
                } else false
            }
            if (hasReal) {
                if (!pendingOptReconciled.contains(id)) {
                    pendingOptReconciled.add(id)
                    DebugLog.info("UI", "optimistic user message matched real message in snapshot, clearing optimistic message (id=${pendingOpt.id})")
                }
                sessionCache.pendingUserMessages.remove(id)
                pendingOptReconciled.remove(id)
            } else if (!pendingOptReconciled.contains(id)) {
                pendingOptReconciled.add(id)
                DebugLog.debug("UI", "optimistic user message NOT matched in snapshot, keeping optimistic (id=${pendingOpt.id}, optText='$optText')")
            }
        }
        sessionCache.snapshotCache[id] = snap
        if (conversationId == id) {
            snapshot = snap
            isAttached = true
            errorState = errorState.copy(
                message = snap.errorMessage?.let { UiMessage(Res.string.err_generic, listOf(it)) },
                diagnostic = snap.errorDiagnostic,
                id = snap.errorId,
                isStreamInterrupted = snap.errorIsStreamInterrupted,
            )
        }
        // turn 结束（离开 Working）清除 StatusBar 计时锚点 + 快照缓存
        if (snap.conversation.status != ConversationStatus.Working) {
            sessionCache.turnStartByConv.remove(id)
            // turn 已结束：store 已有完整数据，缓存不再需要（下次切回走 getSnapshot 即可）。
            // 观察流保留——后续新 turn 的事件仍持续聚合进缓存。
            sessionCache.snapshotCache.remove(id)
        }
        if (conversationId == id && !selectionHydrated) {
            selectionHydrated = true
            hydrateSelectionFromConversation(snap.conversation)
        }
    }

    private fun hydrateSelectionFromConversation(conv: Conversation) {
        // 会话快照只做"补位"，不覆盖、不持久化：全局偏好优先。
        // 否则启动恢复或切换会话会用会话创建时的旧快照覆盖用户主动选的 model/agent，
        // 并反向写回偏好文件，导致"上次选中的模型/Agent"被污染、记不住。
        val modelId = conv.modelId
        val modelProvider = conv.modelProvider
        val model = if (modelId != null) {
            appState.availableModels.value.find { it.id == modelId && it.provider == modelProvider }
        } else null

        val agent: AgentOption? = conv.agent?.let { agentOrMode ->
            val mode = runCatching { AgentMode.valueOf(agentOrMode) }.getOrNull()
            if (mode != null) {
                appState.availableAgents.value.find { it.mode == mode }
            } else {
                appState.availableAgents.value.find { it.id == agentOrMode }
            }
        }
        appState.applyConversationDefaults(model, agent)
        // 执行策略不再单独补位：applyConversationDefaults 写 selectedAgentId 后，
        // AppState.selectedAgentMode 派生流自动跟随（唯一真理源，无本地副本可分叉）

        // 思考级别：会话快照不做覆盖（与 model/agent 同理，全局偏好优先），
        // 生效值一律由 effectiveThinkingLevel（ReasoningMenu.resolve 唯一推导链）现算
    }

    fun detach() = attach(null)

    /**
     * 更新推理档位（推理选择器唯一入口）：写入模型记忆（AppState 持久化）。
     * 生效值 = effectiveThinkingLevel 重新推导，显示与发送自动同源，无副本状态。
     */
    fun updateThinkingLevel(level: String?) {
        val model = appState.selectedModel.value ?: return
        appState.setModelReasoningLevel(model.id, level)
    }

    fun selectModel(model: ModelOption?) {
        appState.selectModel(model)
        // 思考级别无需在此设置副本：生效值由 effectiveThinkingLevel 现算
        // （模型记忆 > 默认档），切换模型后自动跟随新模型
    }

    /** 选定当前模型所在供应商的 API Key（UI key 选择器唯一入口）；null = 用该供应商默认 key。 */
    fun selectApiKey(providerId: String, apiKeyId: String?) {
        appState.selectApiKey(providerId, apiKeyId)
    }

    fun selectProject(id: String?) {
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
            errorState = errorState.copy(message = UiMessage(Res.string.err_select_project_or_conversation))
            return
        }
        if (!appState.isReady.value) {
            DebugLog.event("UI", "send blocked: engine not ready")
            errorState = errorState.copy(message = UiMessage(Res.string.err_engine_not_ready))
            return
        }
        val model = appState.selectedModel.value
        if (model == null) {
            DebugLog.event("UI", "send blocked: no model selected")
            errorState = errorState.copy(message = UiMessage(Res.string.err_select_model_first))
            return
        }
        if (hasImages && !guardImageSupport(model)) return
        // 错误族批量重置（与旧实现一致：这里不重置 isStreamInterrupted，由 applySnapshot 覆盖）
        errorState = errorState.copy(message = null, diagnostic = null, id = null, isDetailOpen = false)

        // 组装最终提示词：用户主指令在前，粘贴大文本在后；Core 的 TurnExecutor 会追加 metaNote 保证 metaNote 恒在最底部
        val finalPrompt = PromptComposer.compose(trimmed, pendingPastedTexts)
        val imageAttachments = pendingImages.toList()

        // 乐观更新：立刻在 UI 显示用户消息（按会话归档，切会话不丢）
        val now = xyz.mederi.currentTimeMillis()
        val pendingKey = convId ?: "pending_$now"
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
            id = "optimistic_$now",
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
        sessionCache.pendingUserMessages[pendingKey] = optMsg
        clearPendingAttachments()
        DebugLog.info("UI", "set optimistic user message: key=$pendingKey, id=${optMsg.id}, text='$finalPrompt', images=${imageAttachments.size}")

        val agent = appState.availableAgents.value.find { it.id == appState.selectedAgentId.value }
        val input = ChatPromptInput(
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
        viewModelScope.launch {
            // 未选中会话时：基于已选项目自动创建新会话，再发送
            val targetConvId = if (convId != null) {
                convId
            } else {
                val pid = projectId
                if (pid == null) return@launch
                val created = appState.aiCore.createConversation(pid, agent)
                if (created.isFailure) {
                    val ex = created.exceptionOrNull()
                    DebugLog.error("UI", "auto create conversation failed: ${ex?.message}", ex)
                    errorState = errorState.copy(
                        message = ex?.message?.let { UiMessage(Res.string.err_generic, listOf(it)) }
                            ?: UiMessage(Res.string.err_create_conversation_failed)
                    )
                    sessionCache.pendingUserMessages.remove(pendingKey)
                    return@launch
                }
                val conv = created.getOrThrow()
                // 乐观消息归属从 pending_xxx re-key 到真实会话 id
                sessionCache.pendingUserMessages.remove(pendingKey)
                sessionCache.pendingUserMessages[conv.id] = optMsg.copy(conversationId = conv.id)
                appState.selectProject(pid)
                appState.selectConversation(conv.id)
                conv.id
            }

            val pendingPlan = snapshot?.pendingPlanApproval
            if (pendingPlan != null) {
                DebugLog.info("UI", "user replied while plan approval pending: resolving approval as false (continue discussion), planId=${pendingPlan.id}")
                appState.aiCore.resolvePlanApproval(targetConvId, pendingPlan.id, false)
            }

            // StatusBar 计时锚点：从"发送请求时刻"起算（切会话回来不重置）
            sessionCache.turnStartByConv[targetConvId] = xyz.mederi.currentTimeMillis()

            val r = appState.aiCore.sendMessage(targetConvId, input)
            DebugLog.event("UI", "sendMessage result: isSuccess=${r.isSuccess}")
            if (r.isFailure) {
                val ex = r.exceptionOrNull()
                DebugLog.error("UI", "sendMessage failed: ${ex?.let { it::class.simpleName }}: ${ex?.message}", ex)
                errorState = errorState.copy(
                    message = ex?.message?.let { UiMessage(Res.string.err_generic, listOf(it)) }
                        ?: UiMessage(Res.string.err_send_failed)
                )
                sessionCache.pendingUserMessages.remove(targetConvId)
            }
        }
    }

    fun abort() {
        val id = conversationId ?: return
        viewModelScope.launch { appState.aiCore.abort(id) }
    }

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
        val id = conversationId ?: return
        viewModelScope.launch {
            val r = appState.aiCore.getFileDiffs(id, messageId)
            if (r.isSuccess) {
                val items = r.getOrDefault(emptyList())
                diffState = DiffUiState(
                    items = items,
                    selectedPath = initialFilePath ?: items.firstOrNull()?.filePath,
                    showPanel = true,
                )
                activeDockPanel = RightDockPanel.DIFF
            }
        }
    }

    fun selectDiffFile(path: String) {
        diffState = diffState.copy(selectedPath = path)
    }

    fun closeDiff() {
        diffState = diffState.copy(showPanel = false)
        if (activeDockPanel == RightDockPanel.DIFF) {
            activeDockPanel = null
        }
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
        // 错误族全量重置（含断流标记与详情弹窗）
        errorState = ErrorState()
    }

    /** 关闭"图片已剔除"轻提示（右上角 × 按钮入口）。 */
    fun dismissImageStrippedNotice() {
        imageStrippedNotice = null
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
        val currentSnap = snapshot
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

        // 本地快照立即切除该消息及后续所有记录（UI 零等待/防闪烁）
        if (currentSnap != null) {
            val targetIdx = currentSnap.messages.indexOfFirst { it.id == messageId }
            if (targetIdx >= 0) {
                val remaining = currentSnap.messages.take(targetIdx)
                DebugLog.info("UI", "rollbackMessage: locally slicing messages from ${currentSnap.messages.size} down to ${remaining.size}")
                snapshot = currentSnap.copy(messages = remaining)
            }
        }

        // 服务端回滚；成功后才把内容粘贴回输入区（失败 → 错误走 ErrorBoard，输入区保持原状）
        viewModelScope.launch {
            DebugLog.info("UI", "rollbackMessage: calling aiCore.rollbackToMessage(convId=$conversationId, msgId=$messageId)")
            val rollbackResult = appState.aiCore.rollbackToMessage(conversationId, messageId)
            if (rollbackResult.isFailure) {
                val ex = rollbackResult.exceptionOrNull()
                DebugLog.error("UI", "rollbackMessage rollbackToMessage failed: ${ex?.message}", ex)
                errorState = errorState.copy(message = UiMessage(Res.string.err_rollback_failed, listOf(ex?.message ?: "?")))
                return@launch
            }

            if (restored.isEmpty) {
                DebugLog.info("UI", "rollbackMessage: nothing to restore, leaving input untouched")
                return@launch
            }
            // 回退成功 → 用回退内容重建输入区（替换现有草稿/附件，语义 = "重新编辑这条消息"）
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
        sessionCache.observeJobs.values.forEach { it.cancel() }
        sessionCache.observeJobs.clear()
        super.onCleared()
    }
}

/**
 * 会话级缓存族（内部收敛 holder：VM 顶层 4 个散落 map 归入单一实例，字段不对外暴露）。
 *
 * 各成员的语义与原 VM 顶层散落声明完全一致（本次改动只改内部存储结构，不改行为）：
 * - [pendingUserMessages]：乐观用户消息（按会话归档）。core 在 turn 结束时才把用户消息落库
 *   （ChatMemory store / 失败兜底），turn 进行中快照里没有它——乐观消息是唯一的显示来源。
 *   因此切换会话**不能清除**：切走再切回，消息仍在；等 observe 到的快照里出现匹配的真实
 *   消息后才移除。key = conversationId（新会话创建成功后从 pending_xxx re-key 到真实 id）。
 * - [turnStartByConv]：StatusBar 计时锚点：每次发送（send）记录"请求发出时刻"（epoch ms），
 *   按会话归档。计时 = now - turnStartAt（每秒重算），切换会话回来不重置；
 *   turn 结束（快照 status 离开 Working）时清除。
 * - [snapshotCache]：按会话缓存的最后一次快照（conversationId → ConversationSnapshot），
 *   解决会话切换闪烁问题（Bug 1 根因，详见 [WorkspaceViewModel] 顶部说明）。
 * - [observeJobs]：各会话的后台观察 Job（conversationId → Job）。会话一旦被 attach 过就
 *   常驻一条观察流持续更新 [snapshotCache]，切走不 cancel、切回不重建；随 viewModelScope
 *   销毁，观察流抛异常时移除并清缓存。
 */
private class SessionUiCache {
    /** 乐观用户消息（按会话归档） */
    val pendingUserMessages = mutableStateMapOf<String, ChatMessage>()

    /** StatusBar 计时锚点：conversationId → 发送请求时刻（epoch ms） */
    val turnStartByConv = mutableMapOf<String, Long>()

    /** 按会话缓存的快照（conversationId → 最后一次的 ConversationSnapshot） */
    val snapshotCache = mutableStateMapOf<String, ConversationSnapshot>()

    /** 各会话的后台观察 Job（conversationId → Job） */
    val observeJobs = mutableMapOf<String, Job>()
}

