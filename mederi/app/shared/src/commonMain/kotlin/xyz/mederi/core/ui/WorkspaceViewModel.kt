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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import xyz.mederi.core.contract.dto.ChatPromptInput
import xyz.mederi.core.contract.dto.ConversationSnapshot
import xyz.mederi.core.contract.dto.FileAttachment
import xyz.mederi.core.contract.models.*
import xyz.mederi.core.ui.appstate.AppState
import xyz.mederi.isDesktopPlatform
import xyz.emuci.inkcompose.MermaidCacheConfig
import xyz.mederi.ui.components.TurnStatus
import xyz.mederi.ui.components.deriveTurnStatus
import xyz.mederi.util.PromptComposer

/**
 * 展平后的聊天列表 item — 每个文本块/思考面板都是独立 LazyColumn item。
 * 所有展示所需的派生数据（聚合文案、目标参数、轮次标记）在此预计算，View 零逻辑。
 */
sealed interface ChatListItem {
    val key: String

    /** 是否为对话轮次的第一个 item（UI 据此加大与上一轮次的间距） */
    val isTurnStart: Boolean

    data class Reasoning(
        override val key: String,
        val text: String,
        val isStreaming: Boolean,
        override val isTurnStart: Boolean = false,
        val durationMs: Long = 0L,
        val isReasoningActive: Boolean = false,
    ) : ChatListItem

    data class ToolCalls(
        override val key: String,
        val toolCalls: List<ToolCallUi>,
        val isStreaming: Boolean,
        override val isTurnStart: Boolean = false,
        val toolSummary: String = "",
        val hasFailedTool: Boolean = false,
        val isRunning: Boolean = false,
    ) : ChatListItem

    data class SubagentCalls(
        override val key: String,
        val subagents: List<ToolCallUi>,
        val isStreaming: Boolean,
        override val isTurnStart: Boolean = false,
        val isRunning: Boolean = false,
        val hasFailed: Boolean = false,
    ) : ChatListItem

    data class TextMessage(
        override val key: String,
        val isUser: Boolean,
        val isStreaming: Boolean,
        val isActiveAssistant: Boolean,
        val text: String,
        val partId: String,
        val conversationId: String,
        val images: List<String> = emptyList(),
        override val isTurnStart: Boolean = false,
        val messageId: String = "",
        val createdAt: Long = 0L,
        /** assistant 消息的 footer 元数据——只挂在该轮次最后一个文本块上，其余为 null */
        val assistantFooter: AssistantFooterInfo? = null,
    ) : ChatListItem

    /** assistant 轮次底部的诊断与状态栏（沉底挂载） */
    data class Footer(
        override val key: String,
        val footer: AssistantFooterInfo,
        override val isTurnStart: Boolean = false,
    ) : ChatListItem

    /** 压缩标记消息（SUMMARY）：既是历史的一部分，也是 AI 视图的分界点 */
    data class SummaryCard(
        override val key: String,
        val text: String,
        override val isTurnStart: Boolean = true,
    ) : ChatListItem

    /** 计划审批卡片（作为持久历史消息留在对话流中，无论后续聊多久均可翻回点击 Proceed） */
    data class PlanApproval(
        override val key: String,
        val request: PlanApprovalRequest,
        override val isTurnStart: Boolean = false,
    ) : ChatListItem
}

/**
 * assistant 消息底部 footer 的元数据（预计算，View 零逻辑）。
 * 数据来自 core Message 诊断字段（modelName/agentMode/reasoningLevel/durationMs），
 * 经契约 ChatMessage 透传到 UI。
 */
data class AssistantFooterInfo(
    val modelName: String? = null,
    /** APPROVAL / AUTONOMOUS */
    val agentMode: String? = null,
    /** 推理档位名称（如 HIGH） */
    val thinkingLevel: String? = null,
    val durationMs: Long? = null,
    /** 回复结束时刻（epoch millis）≈ createdAt + durationMs */
    val completedAtMs: Long? = null,
)

/**
 * 右侧独立功能活动栏枚举。
 */
enum class RightDockPanel(val title: String) {
    OVERVIEW("概览与指标"),
    DIFF("代码差异审查"),
    PLAN("实施计划"),
    SUB_AGENTS("子 Agent 协同"),
    ARTIFACTS("文档与媒体"),
    TERMINAL("终端")
}

data class PlanItem(
    val id: String,
    val title: String,
    val content: String
)

sealed interface ArtifactItem {
    val id: String
    val title: String

    data class Text(
        override val id: String,
        override val title: String,
        val content: String,
        val lineCount: Int = 0,
        val charCount: Int = 0
    ) : ArtifactItem

    data class Image(
        override val id: String,
        override val title: String,
        val imageUrl: String
    ) : ArtifactItem
}

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
 * | 切换 Agent（APPROVAL/AUTONOMOUS × WORK/CODE） | `selectAgent(id)`，列表 [availableAgents] |
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

    /** 终端面板等 dock 组件需要直接读全局状态（项目选择等） */
    val appStateRef: AppState get() = appState

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
    // Agent 选择（AgentMode × WorkType 切换）
    // ==========================================

    /**
     * 可选 Agent 列表（4 个预设：APPROVAL/AUTONOMOUS × WORK/CODE 组合）。
     *
     * UI 渲染 Agent 选择器（下拉/Chip）时观察此 StateFlow：
     * ```
     * val agents by viewModel.availableAgents.collectAsState()
     * ```
     * 每项的 [AgentOption.mode] 是执行策略（APPROVAL=审批模式 / AUTONOMOUS=自主模式）、
     * [AgentOption.workType] 是工作用途（WORK=非程序员 / CODE=程序员）、
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

    /** 当前选中的 Agent 派生值，未选择时为 null（发消息会回退到默认 AUTONOMOUS+CODE）。 */
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
     * 切换工作用途（CODE / WORK）。
     * 保持当前的执行策略（自主/审批）不变。
     */
    fun selectWorkType(workType: WorkType) {
        DebugLog.info("UI", "WorkspaceViewModel.selectWorkType: workType=$workType")
        appState.selectWorkType(workType)
    }

    /**
     * 切换执行策略（AUTONOMOUS / APPROVAL）。
     * 保持当前的工作用途（编程/通用）不变。只写 AppState（唯一真理源），派生流自动更新。
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
        viewModelScope.launch {
            if (currentPending != null && currentPending.id == planId) {
                appState.aiCore.resolvePlanApproval(convId, planId, approved)
            } else if (approved) {
                send("请批准并开始执行已制定的计划 $planId")
            }
        }
    }

    var conversationId by mutableStateOf<String?>(null); internal set
    var snapshot by mutableStateOf<ConversationSnapshot?>(null); private set
    var isAttached by mutableStateOf(false); private set
    var reasoningExpanded by mutableStateOf<Map<String, Boolean>>(emptyMap()); private set
    var diffItems by mutableStateOf<List<FileDiff>>(emptyList()); private set
    var showDiffPanel by mutableStateOf(false); private set

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

    fun openTextInExtension(title: String, content: String, lineCount: Int = 0, charCount: Int = 0) {
        val existing = artifactItems.filterIsInstance<ArtifactItem.Text>().find { it.content == content }
        if (existing != null) {
            activeArtifactId = existing.id
        } else {
            val id = "txt_${xyz.mederi.currentTimeMillis()}"
            artifactItems.add(
                ArtifactItem.Text(
                    id = id,
                    title = title,
                    content = content,
                    lineCount = lineCount,
                    charCount = charCount
                )
            )
            activeArtifactId = id
        }
        openDockPanel(RightDockPanel.ARTIFACTS)
        DebugLog.event("UI", "openTextInExtension: title='$title', activeArtifactId=$activeArtifactId, chars=$charCount")
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
    var error by mutableStateOf<String?>(null); private set

    /**
     * 当前会话错误的完整诊断报告（纯文本，来自 [ConversationSnapshot.errorDiagnostic]）。
     * UI 点击错误简报时可展示此完整详情；null 表示无错误或旧路径。
     */
    var errorDiagnostic by mutableStateOf<String?>(null); private set

    /**
     * 当前会话错误的 ID（来自 [ConversationSnapshot.errorId]）。
     * JVM 端 UI 可经 `ErrorCollector.get(errorId)` 取回完整结构化的 [ErrorRecord]。
     */
    var errorId by mutableStateOf<String?>(null); private set

    /**
     * 当前错误是否为断流（流式连接提前中断）。为 true 时 ErrorBoard 显示"继续"按钮，
     * 点击经 [continueAfterInterruption] 重发 Continue 续写半截回复。
     */
    var isStreamInterrupted by mutableStateOf(false); private set

    /**
     * 是否正在展示详细错误报告弹窗。
     */
    var isErrorDetailOpen by mutableStateOf(false); private set

    fun showErrorDetail() {
        if (!errorDiagnostic.isNullOrBlank() || !error.isNullOrBlank()) {
            isErrorDetailOpen = true
        }
    }

    fun dismissErrorDetail() {
        isErrorDetailOpen = false
    }

    /**
     * 乐观用户消息（按会话归档）。
     *
     * core 在 turn 结束时才把用户消息落库（ChatMemory store / 失败兜底），turn 进行中
     * 快照里没有它——乐观消息是唯一的显示来源。因此切换会话**不能清除**：
     * 切走再切回，消息仍在；等 observe 到的快照里出现匹配的真实消息后才移除。
     * key = conversationId（新会话创建成功后从 pending_xxx re-key 到真实 id）。
     */
    private val pendingUserMessages = mutableStateMapOf<String, ChatMessage>()

    /**
     * StatusBar 计时锚点：每次发送（send）记录"请求发出时刻"（epoch ms），按会话归档。
     * 计时 = now - turnStartAt（每秒重算），切换会话回来不重置。
     * 与 footer 的 durationMs 语义不同——durationMs 是"API 有回应开始算到回复结束"（core 侧），
     * 本字段是"从用户发出请求开始算"，二者不是同一数据源。
     * turn 结束（快照 status 离开 Working）时清除。
     */
    private val turnStartByConv = mutableMapOf<String, Long>()

    /** 当前会话的乐观消息（兼容旧引用/测试） */
    val optimisticUserMessage: ChatMessage? get() = conversationId?.let { pendingUserMessages[it] }

    /**
     * 输入框草稿（会话级业务状态，唯一真理源在此）。
     *
     * 欢迎页与消息列表两个 ChatInputCard 调用点共享同一草稿：分支切换（收到第一条回复、
     * 清空会话）不再丢失正在输入的内容。选区菜单"添加到对话框"也直接写这里，
     * 无需事件对象绕道。UI 通过 [updateInputDraft]/[clearInputDraft] 单向写入。
     */
    var inputDraft by mutableStateOf(TextFieldValue("")); private set

    fun updateInputDraft(value: TextFieldValue) {
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
     * 内置图片门禁：当前模型不支持图片输入时拒绝入列并给出可见反馈。
     *
     * @return true = 已附加；false = 被门禁拦截（error 已写入展示位）。
     */
    fun tryAttachImage(name: String, mimeType: String, bytes: ByteArray, width: Int = 0, height: Int = 0): Boolean {
        val model = appState.selectedModel.value
        if (model != null && !model.supportsImages) {
            DebugLog.event("UI", "attach blocked: model does not support image input (${model.providerModelId})")
            error = "当前模型「${model.name}」不支持图片输入，请移除图片或切换到支持图片的模型"
            return false
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
        val opt = conversationId?.let { pendingUserMessages[it] } ?: return base
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
     * 来自 [turnStartByConv]（send 时记录，turn 结束清除），切换会话回来不重置。
     * null = 当前会话没有进行中的发送（StatusBar 不显示计时）。
     */
    val turnStartedAt: Long?
        get() = conversationId?.let { turnStartByConv[it] }

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
    data class HeaderTitle(val projectName: String?, val conversationName: String)

    /**
     * 顶部面包屑标题：完全由 AppState 三个 StateFlow combine 的**派生** StateFlow
     * （不是副本：源流任一发射即重算）。UI 用 collectAsState 订阅——此前是 getter
     * 直读 `StateFlow.value`（不建立订阅），项目/会话改名后面包屑会停留旧值。
     */
    val headerTitle: StateFlow<HeaderTitle> = combine(
        appState.projects,
        appState.selectedProjectId,
        appState.selectedConversationId,
    ) { projects, selectedProjectId, convId ->
        val projectName = projects.find { it.id == selectedProjectId }?.name
        val convName = if (convId != null) {
            projects.flatMap { it.conversations }.find { it.id == convId }?.title
                ?: "会话 ${convId.take(8)}"
        } else null
        HeaderTitle(projectName, convName ?: "新对话")
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HeaderTitle(null, "新对话"))

    /** 环境态/过程提示（statusHint，如限流重试中），StatusBar 过程状态展示用。错误/警告不在此——走 ErrorBoard */
    val statusHint: String? get() = snapshot?.statusHint

    /** 当前对话轮次的实时状态（授权等待由卡片本身承载，状态条不重复提示） */
    val turnStatus: TurnStatus
        get() {
            val convId = conversationId
            val hasPendingSend = convId != null && pendingUserMessages.containsKey(convId)
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

val SUBAGENT_TOOL_NAMES = setOf("spawn_agent", "spawn_researcher")

    internal fun computeChatItems(msgs: List<ChatMessage>): List<ChatListItem> {
        val result = mutableListOf<ChatListItem>()

        // role 为 null 的段是压缩标记（SUMMARY），独立成卡片
        val turns = mutableListOf<Pair<ChatRole?, List<ChatMessage>>>()
        var currentAssistant = mutableListOf<ChatMessage>()
        for (msg in msgs) {
            if (msg.role == ChatRole.Summary) {
                if (currentAssistant.isNotEmpty()) {
                    turns.add(ChatRole.Assistant to currentAssistant.toList())
                    currentAssistant = mutableListOf()
                }
                turns.add(null to listOf(msg))
                continue
            }
            // 真实用户消息（含有用户文本或文件）：纯中间工具结果消息（blocks 为空）绝不切断 Assistant 轮次
            val isRealUser = msg.role == ChatRole.User && msg.blocks.any {
                it is ChatBlock.Text || it is ChatBlock.File
            }
            if (isRealUser) {
                if (currentAssistant.isNotEmpty()) {
                    turns.add(ChatRole.Assistant to currentAssistant.toList())
                    currentAssistant = mutableListOf()
                }
                turns.add(ChatRole.User to listOf(msg))
            } else if (msg.role == ChatRole.Assistant) {
                currentAssistant.add(msg)
            }
        }
        if (currentAssistant.isNotEmpty()) {
            turns.add(ChatRole.Assistant to currentAssistant.toList())
        }

        val lastAssistantTurn = turns.lastOrNull { it.first == ChatRole.Assistant }?.second
        for ((role, turnMessages) in turns) {
            if (role == null) {
                val summaryText = turnMessages
                    .flatMap { it.blocks.filterIsInstance<ChatBlock.Text>() }
                    .joinToString("\n\n") { it.text }
                result.add(
                    ChatListItem.SummaryCard(
                        key = "${turnMessages.first().id}_summary",
                        text = summaryText
                    )
                )
                continue
            }

            val isUser = role == ChatRole.User
            if (isUser) {
                for (msg in turnMessages) {
                    val messageImages = msg.blocks.filterIsInstance<ChatBlock.File>()
                        .filter { isImageBlock(it) }
                        .map { it.url }
                    val textBlocks = msg.blocks.filterIsInstance<ChatBlock.Text>().filter { it.text.isNotBlank() }
                    if (textBlocks.isNotEmpty()) {
                        textBlocks.forEachIndexed { blockIndex, block ->
                            result.add(
                                ChatListItem.TextMessage(
                                    key = "${msg.id}_${block.id}",
                                    isUser = true,
                                    isStreaming = false,
                                    isActiveAssistant = false,
                                    text = block.text,
                                    partId = block.id,
                                    conversationId = msg.conversationId,
                                    images = if (blockIndex == 0) messageImages else emptyList(),
                                    isTurnStart = true,
                                    messageId = msg.id,
                                    createdAt = msg.createdAt,
                                )
                            )
                        }
                    } else if (messageImages.isNotEmpty()) {
                        result.add(
                            ChatListItem.TextMessage(
                                key = "${msg.id}_img",
                                isUser = true,
                                isStreaming = false,
                                isActiveAssistant = false,
                                text = "",
                                partId = "img",
                                conversationId = msg.conversationId,
                                images = messageImages,
                                isTurnStart = true,
                                messageId = msg.id,
                                createdAt = msg.createdAt,
                            )
                        )
                    }
                }
                continue
            }

            // Assistant 轮次
            val isStreaming = turnMessages.any { it.isStreaming }
            val isActiveAssistant = isStreaming || (isWorking && turnMessages == lastAssistantTurn)

            val allToolCalls = turnMessages.flatMap { it.blocks.filterIsInstance<ChatBlock.ToolCall>() }
                .map { toToolCallUi(it) }
            val regularToolCalls = allToolCalls.filter { it.name !in SUBAGENT_TOOL_NAMES }
            val subagentCalls = allToolCalls.filter { it.name in SUBAGENT_TOOL_NAMES }

            var turnHasFirstItem = false

            // 按真实时序遍历本轮的消息与块，交替输出 Reasoning 和 Text
            for (msg in turnMessages) {
                val messageImages = msg.blocks.filterIsInstance<ChatBlock.File>()
                    .filter { isImageBlock(it) }
                    .map { it.url }
                var imagesHandled = false

                for ((blockIndex, block) in msg.blocks.withIndex()) {
                    when (block) {
                        is ChatBlock.Reasoning -> {
                            if (block.text.isNotBlank()) {
                                val durationMs = if (msg.completedAt != null && msg.completedAt > msg.createdAt) {
                                    msg.completedAt - msg.createdAt
                                } else (msg.durationMs ?: 0L)
                                val hasSubsequentText = msg.blocks.drop(blockIndex + 1).any { it is ChatBlock.Text && it.text.isNotBlank() }
                                    || turnMessages.dropWhile { it != msg }.drop(1).any { m -> m.blocks.any { it is ChatBlock.Text && it.text.isNotBlank() } }
                                val isReasoningActive = isStreaming && !hasSubsequentText

                                result.add(
                                    ChatListItem.Reasoning(
                                        key = "${msg.id}_${block.id}",
                                        text = block.text,
                                        isStreaming = isStreaming,
                                        isTurnStart = !turnHasFirstItem,
                                        durationMs = durationMs,
                                        isReasoningActive = isReasoningActive,
                                    )
                                )
                                turnHasFirstItem = true
                            }
                        }

                        is ChatBlock.Text -> {
                            if (block.text.isNotBlank()) {
                                result.add(
                                    ChatListItem.TextMessage(
                                        key = "${msg.id}_${block.id}",
                                        isUser = false,
                                        isStreaming = isStreaming,
                                        isActiveAssistant = isActiveAssistant,
                                        text = block.text,
                                        partId = block.id,
                                        conversationId = msg.conversationId,
                                        images = if (!imagesHandled) messageImages else emptyList(),
                                        isTurnStart = !turnHasFirstItem,
                                        messageId = msg.id,
                                        createdAt = msg.createdAt,
                                        assistantFooter = null
                                    )
                                )
                                imagesHandled = true
                                turnHasFirstItem = true
                            }
                        }

                        else -> {}
                    }
                }

                if (!imagesHandled && messageImages.isNotEmpty()) {
                    result.add(
                        ChatListItem.TextMessage(
                            key = "${msg.id}_img",
                            isUser = false,
                            isStreaming = isStreaming,
                            isActiveAssistant = isActiveAssistant,
                            text = "",
                            partId = "img",
                            conversationId = msg.conversationId,
                            images = messageImages,
                            isTurnStart = !turnHasFirstItem,
                            messageId = msg.id,
                            createdAt = msg.createdAt,
                        )
                    )
                    turnHasFirstItem = true
                }
            }

            // 活跃 assistant 刚开始流式时，若尚无内容输出，放一个占位思考微条
            if (isActiveAssistant && !turnHasFirstItem) {
                val firstMsg = turnMessages.firstOrNull()
                result.add(
                    ChatListItem.Reasoning(
                        key = "${firstMsg?.id ?: "active"}_reasoning",
                        text = "",
                        isStreaming = true,
                        isTurnStart = true,
                        durationMs = 0L,
                        isReasoningActive = true,
                    )
                )
                turnHasFirstItem = true
            }

            // 沉底汇总区（红框位置）：
            // 1. 普通工具调用汇总微条
            if (regularToolCalls.isNotEmpty()) {
                result.add(
                    ChatListItem.ToolCalls(
                        key = "${turnMessages.first().id}_toolcalls",
                        toolCalls = regularToolCalls,
                        isStreaming = isStreaming,
                        isTurnStart = !turnHasFirstItem,
                        toolSummary = buildToolSummary(regularToolCalls),
                        hasFailedTool = regularToolCalls.any { it.isFailed },
                        isRunning = isStreaming && regularToolCalls.any { it.state is ToolCallState.Running },
                    )
                )
                turnHasFirstItem = true
            }

            // 2. 子 Agent 独立汇总微条
            if (subagentCalls.isNotEmpty()) {
                result.add(
                    ChatListItem.SubagentCalls(
                        key = "${turnMessages.first().id}_subagents",
                        subagents = subagentCalls,
                        isStreaming = isStreaming,
                        isTurnStart = !turnHasFirstItem,
                        isRunning = isStreaming && subagentCalls.any { it.state is ToolCallState.Running },
                        hasFailed = subagentCalls.any { it.isFailed },
                    )
                )
                turnHasFirstItem = true
            }

            // 3. 计划审批卡片
            val createPlanCall = allToolCalls.find { it.name == "create_plan" }
            val planIdFromTool = when (val s = createPlanCall?.state) {
                is ToolCallState.Completed -> s.input["planId"]
                is ToolCallState.Running -> s.input["planId"]
                else -> null
            }
            val matchedPlan = snapshot?.planApprovals?.find { planIdFromTool != null && it.id == planIdFromTool }
                ?: if (turnMessages == lastAssistantTurn) snapshot?.pendingPlanApproval else null

            if (matchedPlan != null && result.none { it is ChatListItem.PlanApproval && it.request.id == matchedPlan.id }) {
                result.add(
                    ChatListItem.PlanApproval(
                        key = "plan_${matchedPlan.id}",
                        request = matchedPlan,
                        isTurnStart = !turnHasFirstItem
                    )
                )
                turnHasFirstItem = true
            }

            // 4. 轮次底部的诊断与状态栏（非流式结束状态输出）
            val lastMsg = turnMessages.lastOrNull { it.role == ChatRole.Assistant }
            if (lastMsg != null && !isStreaming) {
                result.add(
                    ChatListItem.Footer(
                        key = "${turnMessages.first().id}_footer",
                        footer = lastMsg.toAssistantFooter(),
                        isTurnStart = false,
                    )
                )
            }
        }

        val pending = snapshot?.pendingPlanApproval
        if (pending != null && result.none { it is ChatListItem.PlanApproval && it.request.id == pending.id }) {
            result.add(
                ChatListItem.PlanApproval(
                    key = "plan_${pending.id}",
                    request = pending,
                    isTurnStart = true
                )
            )
        }

        return result
    }

    /** assistant 消息 → footer 元数据（诊断字段来自 core Message） */
    private fun ChatMessage.toAssistantFooter(): AssistantFooterInfo {
        val modelId = model
        return AssistantFooterInfo(
            modelName = modelName ?: modelId,
            agentMode = agentMode,
            thinkingLevel = thinkingLevel,
            durationMs = durationMs,
            completedAtMs = completedAt?.takeIf { it > 0 } ?: (createdAt + (durationMs ?: 0)),
        )
    }

    /** 从工具参数中探测"核心目标"单行摘要（文件路径 / 命令等），供 ToolCallUi.target 使用。 */
    private fun toToolCallUi(toolCall: ChatBlock.ToolCall): ToolCallUi {
        val input = when (val s = toolCall.state) {
            is ToolCallState.Running -> s.input
            is ToolCallState.Completed -> s.input
            is ToolCallState.Failed -> s.input
            ToolCallState.Pending -> emptyMap()
        }
        return ToolCallUi(
            id = toolCall.id,
            name = toolCall.name,
            state = toolCall.state,
            target = probeToolTarget(toolCall.name, input),
            isFailed = toolCall.state is ToolCallState.Failed,
        )
    }

    /**
     * 按工具名探测目标摘要：
     * - 文件工具（read/write/edit/list）→ 路径（会话目录内显示相对路径，目录外保留绝对路径）；
     * - 命令工具（execute_command/bash）→ 命令原文；
     * - apply_patch → 补丁内涉及的文件清单（Add/Update/Delete/Move 去重取前 3）；
     * - 其余 → 回退到 path/file/command 等常见键，最后兜底第一个参数值。
     */
    private fun probeToolTarget(name: String, input: Map<String, String>): String? {
        val baseDir = snapshot?.conversation?.directory?.takeIf { it.isNotBlank() }
        val fileOrDirKeys = listOf(
            "path", "file", "targetFile", "filePath", "file_path",
            "dir", "directory", "dir_path", "directory_path", "DirectoryPath", "SearchDirectory"
        )
        val firstFileValue = fileOrDirKeys.firstNotNullOfOrNull { input[it]?.takeIf { v -> v.isNotBlank() } }
        val isDirTool = name.contains("list") || name.contains("dir") || name.contains("tree")
        val isSearchTool = name.contains("search") || name.contains("grep") || name.contains("find")
        val searchQuery = if (isSearchTool) {
            input["query"] ?: input["pattern"] ?: input["regex"] ?: input["Query"] ?: input["Pattern"]
        } else null

        return when {
            name in SUBAGENT_TOOL_NAMES ->
                input["task"]?.lines()?.firstOrNull { it.isNotBlank() } ?: input["briefing"]?.lines()?.firstOrNull { it.isNotBlank() }
            name == "apply_patch" ->
                input["patch"]?.let { extractPatchFiles(it) }?.takeIf { it.isNotBlank() }
            name == "execute_command" || name == "bash" ->
                input["command"]?.takeIf { it.isNotBlank() } ?: input["cmd"]?.takeIf { it.isNotBlank() }
            name == "ask_user" ->
                extractAskUserSummary(input)
            searchQuery != null ->
                searchQuery
            firstFileValue != null -> {
                val display = toDisplayPath(firstFileValue, baseDir)
                if (display == "." || display.isBlank()) "directory" else display
            }
            isDirTool ->
                "directory"
            else ->
                input["command"]?.takeIf { it.isNotBlank() }
                    ?: input["cmd"]?.takeIf { it.isNotBlank() }
                    ?: input["patch"]?.let { extractPatchFiles(it) }?.takeIf { it.isNotBlank() }
                    ?: input.values.firstOrNull { it.isNotBlank() }
        }
    }

    private fun extractAskUserSummary(input: Map<String, String>): String? {
        input["prompt"]?.takeIf { it.isNotBlank() }?.let { return it }
        val rawQuestions = input["questions"] ?: return null
        val match = Regex("\"prompt\"\\s*:\\s*\"([^\"]+)\"").find(rawQuestions)
        return match?.groupValues?.get(1) ?: rawQuestions.take(50)
    }

    /**
     * 路径显示规则：会话所属目录内 → 相对路径；目录外 → 保留绝对路径（用户能看出 AI 动了哪个位置）。
     */
    private fun toDisplayPath(raw: String, baseDir: String?): String {
        val path = raw.trim()
        if (path.isBlank()) return path
        val base = baseDir?.trim()?.trimEnd('/')
        if (base != null && isAbsolutePath(path)) {
            if (path.startsWith("$base/")) return path.removePrefix("$base/")
            if (path == base) return path
        }
        return path
    }

    private fun isAbsolutePath(path: String): Boolean =
        path.startsWith("/") || Regex("^[A-Za-z]:[/\\\\]").containsMatchIn(path)

    /** 从 apply_patch 补丁文本中提取涉及的文件路径（Add/Update/Delete/Move，去重后取前 3）。 */
    private fun extractPatchFiles(patch: String): String? {
        if (patch.isBlank()) return null
        val baseDir = snapshot?.conversation?.directory?.takeIf { it.isNotBlank() }
        val paths = patch.lineSequence()
            .mapNotNull { line ->
                Regex("^\\*\\*\\* (?:Add File|Update File|Delete File|Move to): (.+)$")
                    .find(line.trim())?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
            }
            .distinct()
            .toList()
        if (paths.isEmpty()) return null
        return paths.take(3).joinToString(", ") { toDisplayPath(it, baseDir) } +
            if (paths.size > 3) ", …" else ""
    }

    private fun buildToolSummary(toolCalls: List<ToolCallUi>): String {
        if (toolCalls.isEmpty()) return ""
        return toolCalls.groupingBy { it.name }.eachCount().entries.joinToString(", ") { (name, count) ->
            if (count > 1) "$name ×$count" else name
        }
    }


    internal fun isImageBlock(block: ChatBlock.File): Boolean {
        val mime = block.mimeType?.lowercase()
        if (mime?.startsWith("image/") == true) return true
        val url = block.url.lowercase()
        if (url.startsWith("data:image/")) return true
        val cleanUrl = url.substringBefore('?').substringBefore('#')
        return cleanUrl.endsWith(".png") || cleanUrl.endsWith(".jpg") ||
            cleanUrl.endsWith(".jpeg") || cleanUrl.endsWith(".webp") ||
            cleanUrl.endsWith(".gif") || cleanUrl.endsWith(".svg") ||
            cleanUrl.endsWith(".bmp") || cleanUrl.endsWith(".ico")
    }

    private var observeJob: Job? = null
    // 乐观消息对账日志去重：同一会话的 matched/unmatched 结论只打一次（状态翻转时重置）
    private val pendingOptReconciled = mutableSetOf<String>()
    private var selectionHydrated = false

    init {
        viewModelScope.launch {
            appState.selectedConversationId.collect { id ->
                if (id != conversationId) attach(id)
            }
        }
    }

    fun attach(id: String?) {
        DebugLog.event("UI", "attach: id=$id")
        observeJob?.cancel()
        selectionHydrated = false
        resetQuestionState()
        if (id == null) {
            conversationId = null
            snapshot = null
            isAttached = false
            pendingOptReconciled.clear()
            error = null
            errorDiagnostic = null
            errorId = null
            isErrorDetailOpen = false
            return
        }
        conversationId = id
        snapshot = null
        isAttached = false
        pendingOptReconciled.clear()
        error = null
        errorDiagnostic = null
        errorId = null
        isErrorDetailOpen = false
        observeJob = viewModelScope.launch {
            // 先校验会话可达（不存在/已删 → 失败）。启动时 lastConversationId 可能指向
            // 已被删除的会话（如删库重建），此时按"没有了就是没有"处理：清空选择态并
            // 清掉残留偏好，而不是让 observe 流抛 Session not found 异常。
            val initial = appState.aiCore.getSnapshot(id).getOrNull()
            if (initial == null) {
                DebugLog.event("UI", "attach: conversation $id not found, clearing stale selection")
                if (conversationId == id) {
                    conversationId = null
                    snapshot = null
                    isAttached = false
                    appState.selectConversation(null)
                }
                return@launch
            }
            appState.aiCore.observeConversation(id).collect { snap ->
                DebugLog.debug("UI", "snapshot received: status=${snap.conversation.status}, messages=${snap.messages.size}")
                // 乐观消息与快照对账：真实消息已落库（turn 结束/出错兜底时 core 写入）→ 移除乐观消息
                // 日志只在状态变化时打一条（matched/unmatched 翻转），避免流式期间每快照重复刷屏
                val pendingOpt = pendingUserMessages[id]
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
                        pendingUserMessages.remove(id)
                        pendingOptReconciled.remove(id)
                    } else if (!pendingOptReconciled.contains(id)) {
                        pendingOptReconciled.add(id)
                        DebugLog.debug("UI", "optimistic user message NOT matched in snapshot, keeping optimistic (id=${pendingOpt.id}, optText='$optText')")
                    }
                }
                snapshot = snap
                isAttached = true
                error = snap.errorMessage
                errorDiagnostic = snap.errorDiagnostic
                errorId = snap.errorId
                isStreamInterrupted = snap.errorIsStreamInterrupted
                // turn 结束（离开 Working）清除 StatusBar 计时锚点
                if (snap.conversation.status != ConversationStatus.Working) {
                    turnStartByConv.remove(id)
                }
                if (!selectionHydrated) {
                    selectionHydrated = true
                    hydrateSelectionFromConversation(snap.conversation)
                }
            }
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
                appState.availableAgents.value.find { it.workType == conv.workType && it.mode == mode }
                    ?: appState.availableAgents.value.find { it.mode == mode }
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

    fun selectProject(id: String?) {
        appState.selectProject(id)
    }

    fun selectConversation(id: String?) {
        appState.selectConversation(id)
    }

    /**
     * 图片附件门禁（唯一守卫，send 与 rollbackMessage 共用）：
     * 当前模型不支持图片输入时拒绝发送，不让请求到达供应商后报 400。
     *
     * @return true = 放行；false = 已拦截并写入 [error]。
     */
    private fun guardImageSupport(model: ModelOption): Boolean {
        if (model.supportsImages) return true
        DebugLog.event("UI", "send blocked: model does not support image input (${model.providerModelId})")
        error = "当前模型「${model.name}」不支持图片输入，请移除图片或切换到支持图片的模型"
        return false
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

        if (trimmed.isEmpty() && !hasPasted && !hasImages) {
            DebugLog.event("UI", "send blocked: text and attachments are all empty")
            return
        }
        val convId = conversationId
        // 未选中会话：若已选中项目，则发送时自动创建新会话；两者都无才报错
        val projectId = if (convId == null) appState.selectedProjectId.value else null
        if (convId == null && projectId == null) {
            DebugLog.event("UI", "send blocked: no conversation and no project")
            error = "请先选择项目以创建会话，或选择一个已有会话"
            return
        }
        if (!appState.isReady.value) {
            DebugLog.event("UI", "send blocked: engine not ready")
            error = "Engine 尚未就绪,请稍后"
            return
        }
        val model = appState.selectedModel.value
        if (model == null) {
            DebugLog.event("UI", "send blocked: no model selected")
            error = "请先在输入框选择模型"
            return
        }
        if (hasImages && !guardImageSupport(model)) return
        error = null
        errorDiagnostic = null
        errorId = null
        isErrorDetailOpen = false

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
        pendingUserMessages[pendingKey] = optMsg
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
                    error = ex?.message ?: "创建会话失败"
                    pendingUserMessages.remove(pendingKey)
                    return@launch
                }
                val conv = created.getOrThrow()
                // 乐观消息归属从 pending_xxx re-key 到真实会话 id
                pendingUserMessages.remove(pendingKey)
                pendingUserMessages[conv.id] = optMsg.copy(conversationId = conv.id)
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
            turnStartByConv[targetConvId] = xyz.mederi.currentTimeMillis()

            val r = appState.aiCore.sendMessage(targetConvId, input)
            DebugLog.event("UI", "sendMessage result: isSuccess=${r.isSuccess}")
            if (r.isFailure) {
                val ex = r.exceptionOrNull()
                DebugLog.error("UI", "sendMessage failed: ${ex?.let { it::class.simpleName }}: ${ex?.message}", ex)
                val msg = ex?.message ?: "发送失败，服务器无响应或用量受限"
                error = msg
                pendingUserMessages.remove(targetConvId)
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

    fun openDiff(messageId: String? = null) {
        val id = conversationId ?: return
        viewModelScope.launch {
            val r = appState.aiCore.getFileDiffs(id, messageId)
            if (r.isSuccess) {
                diffItems = r.getOrDefault(emptyList())
                showDiffPanel = true
            }
        }
    }

    fun closeDiff() {
        showDiffPanel = false
    }

    fun toggleReasoning(blockId: String) {
        val cur = reasoningExpanded[blockId] ?: false
        reasoningExpanded = reasoningExpanded + (blockId to !cur)
    }

    fun requestCompaction() {
        val id = conversationId ?: return
        viewModelScope.launch {
            appState.aiCore.compressHistory(id)
        }
    }

    fun clearError() {
        error = null
        errorDiagnostic = null
        errorId = null
        isStreamInterrupted = false
        isErrorDetailOpen = false
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
                error = "回退失败: ${ex?.message ?: "无法回滚消息"}"
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
        observeJob?.cancel()
        super.onCleared()
    }
}

/** 回退消息后放回输入框的内容：主指令 + 大段文本附件 + 图片附件（原样恢复，非脱壳简化） */
data class RestoredInput(
    val instruction: String,
    val pastedTexts: List<PastedTextAttachment>,
    val images: List<ImageAttachment>,
) {
    val isEmpty: Boolean get() = instruction.isBlank() && pastedTexts.isEmpty() && images.isEmpty()
}

/**
 * 从一条已发送的用户消息反解出可放回输入框的内容（纯函数，单测直接覆盖）：
 * - 主指令 = `PromptComposer.parse` 剥离大段文本 XML 标签后的主指令部分
 * - `pastedTexts` = parse 拆出的大段文本附件，index 重新编号、id 改为当前时间戳前缀
 * - `images` = 消息中 `data:` URL 的 `ChatBlock.File` → base64 解码还原 `ImageAttachment`
 *   （回退是"恢复已发内容"，不做模型图片能力门禁；真发送时 send() 的 guardImageSupport 会拦）
 */
fun restoreInputFromMessage(targetMsg: ChatMessage?, fallbackText: String): RestoredInput {
    val fullText = targetMsg?.blocks
        ?.filterIsInstance<ChatBlock.Text>()
        ?.joinToString("") { it.text }
        ?.ifBlank { fallbackText } ?: fallbackText
    val parsed = PromptComposer.parse(fullText)
    val images = targetMsg?.blocks
        ?.filterIsInstance<ChatBlock.File>()
        ?.filter { it.url.startsWith("data:") && it.url.contains("base64,") }
        ?.mapIndexedNotNull { i, block ->
            val bytes = block.url.substringAfter("base64,", "").decodeBase64()?.toByteArray()
            if (bytes != null) {
                ImageAttachment(
                    id = "img_${targetMsg.id}_$i",
                    name = block.name.ifBlank { "image_${i + 1}" },
                    mimeType = block.mimeType ?: "image/png",
                    bytes = bytes,
                    base64DataUrl = block.url,
                )
            } else null
        } ?: emptyList()
    val pastedTexts = parsed.pastedTexts.mapIndexed { i, item ->
        item.copy(
            id = "pasted_${targetMsg?.id ?: "rollback"}_${i + 1}",
            index = i + 1
        )
    }
    return RestoredInput(
        instruction = parsed.instruction,
        pastedTexts = pastedTexts,
        images = images
    )
}
