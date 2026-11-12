package xyz.mederi.core.bridge

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.mederi.AppInfo
import xyz.mederi.Mederi
import xyz.mederi.api.AgentConfig
import xyz.mederi.api.CreateSessionRequest
import xyz.mederi.api.RenameSessionRequest
import xyz.mederi.api.SendMessageRequest
import xyz.mederi.api.SessionApi
import xyz.mederi.config.MederiIdentity
import xyz.mederi.core.autotitle.SessionTitleService
import xyz.mederi.core.contract.models.ChatGptAuthState
import xyz.mederi.core.contract.models.ChatGptUsage
import xyz.mederi.core.contract.models.CHATGPT_USAGE_API_UNAVAILABLE
import xyz.mederi.core.contract.AiCore
import xyz.mederi.core.contract.PendingInteractionTracker
import xyz.mederi.core.contract.SandboxStatusInfo
import xyz.mederi.core.contract.withPendingInteractionStatus
import xyz.mederi.core.contract.models.ExecutionApprovalRequest
import xyz.mederi.core.contract.models.ExecutionPermissionMode
import xyz.mederi.core.contract.models.SandboxSettings
import xyz.mederi.core.contract.dto.ChatPromptInput
import xyz.mederi.core.contract.dto.ConversationSnapshot
import xyz.mederi.core.contract.dto.ConversationState
import xyz.mederi.core.contract.dto.UpdateConversationSettingsInput
import xyz.mederi.core.contract.dto.CreateCustomProviderInput
import xyz.mederi.core.contract.dto.MessagesPage
import xyz.mederi.core.contract.dto.RawMessageDto
import xyz.mederi.core.contract.dto.CreateProjectInput
import xyz.mederi.core.contract.dto.ProviderUpdateInput
import xyz.mederi.core.contract.models.ApiKeyOption
import xyz.mederi.core.contract.models.BackgroundTaskInfo
import xyz.mederi.core.contract.models.ChatMessage
import xyz.mederi.core.contract.models.Conversation
import xyz.mederi.core.contract.models.ConversationStatus
import xyz.mederi.core.contract.models.CoreEvent
import xyz.mederi.core.contract.models.CostSummary
import xyz.mederi.core.contract.models.McpServerItem
import xyz.mederi.core.contract.models.McpServerStatus
import xyz.mederi.core.contract.models.SkillItem
import xyz.mederi.core.contract.models.FileDiff
import xyz.mederi.core.contract.models.ModelOption
import xyz.mederi.core.contract.models.Project
import xyz.mederi.core.contract.models.ProviderConfig
import xyz.mederi.core.contract.models.AgentOption
import xyz.mederi.core.contract.models.ProcessStats
import xyz.mederi.core.contract.models.TokenUsage
import xyz.mederi.core.contract.models.SubagentConfigItem
import xyz.mederi.core.contract.models.SubagentGlobalSettings
import xyz.mederi.core.contract.models.SubagentReportDto
import xyz.mederi.core.contract.models.UpdateSubagentConfigInput
import xyz.mederi.core.contract.models.UpdateSubagentGlobalSettingsInput
import xyz.mederi.browser.BrowserConstants
import xyz.mederi.browser.BrowserKind
import xyz.mederi.browser.BrowserRegistry
import xyz.mederi.browser.obscura.ObscuraBinaryLocator
import xyz.mederi.browser.obscura.ObscuraBrowserControl
import xyz.mederi.browser.obscura.ObscuraMcpClient
import xyz.mederi.domain.model.AgentMode
import xyz.mederi.domain.model.Session
import xyz.mederi.debug.DebugLog
import xyz.mederi.provider.domain.model.Provider
import xyz.mederi.provider.infrastructure.koog.retry.LlmRetryConfig

/** 会话最近一次终态错误的瞬态登记（内存态，不持久化；MESSAGE_ERROR 写入，MESSAGE_COMPLETED/新 turn 清除）。 */
data class LastSessionError(
    val errorMessage: String?,
    val errorId: String?,
    val errorDiagnostic: String?,
    val isStreamInterrupted: Boolean,
    val atMs: Long,
)

/** 从 MESSAGE_ERROR 事件 payload 解析 LastSessionError（payload 键与 SnapshotReducer 读取的一致：error/errorId/fullDiagnostic/failureMode）。 */
internal fun lastErrorFromPayload(payload: Map<String, String>): LastSessionError {
    // 与公共 reducer 一致：切会话/遥端重新取快照不能把主动取消重新显示成错误。
    val cancelled = payload["errorCategory"] == "CANCELLED"
    return LastSessionError(
        errorMessage = if (cancelled) null else payload["error"],
        errorId = if (cancelled) null else payload["errorId"],
        errorDiagnostic = if (cancelled) null else payload["fullDiagnostic"],
        isStreamInterrupted = !cancelled && payload["failureMode"] == "PREMATURE_CLOSE",
        atMs = xyz.mederi.currentTimeMillis(),
    )
}

/**
 * MESSAGE_ERROR 事件到达时按权威会话状态决定侧边栏状态点：
 * - sessionStore 状态为 ERROR（非 transient，不可恢复）→ ConversationStatus.Error（红点）
 * - sessionStore 状态为 IDLE（transient 可恢复，如限流重试耗尽）→ ConversationStatus.Idle（蓝点）
 * - 其他/读取失败 → 保守回退 Error（保持可见，不静默）
 */
internal fun mapMessageErrorToStatus(sessionStatus: xyz.mederi.domain.model.SessionStatus): ConversationStatus =
    when (sessionStatus) {
        xyz.mederi.domain.model.SessionStatus.ERROR -> ConversationStatus.Error
        xyz.mederi.domain.model.SessionStatus.IDLE -> ConversationStatus.Idle
        else -> ConversationStatus.Error
    }

/**
 * 按字符串名映射 MESSAGE_ERROR payload 中的 finalStatus 字段（core 侧 SessionStatus.name）。
 * 用于 MederiAiCore 读 payload["finalStatus"] 时的 string→ConversationStatus 映射；
 * ServerAiCore / SnapshotReducer（commonMain）各自用 when(string) 内联。
 */
internal fun mapMessageErrorToStatusByName(statusName: String): ConversationStatus =
    when (statusName) {
        "ERROR" -> ConversationStatus.Error
        "IDLE" -> ConversationStatus.Idle
        else -> ConversationStatus.Error
    }

/** 水合：error 为 null 时原样返回，否则把错误字段写进快照。 */
internal fun hydrateLastError(snapshot: ConversationSnapshot, error: LastSessionError?): ConversationSnapshot {
    if (error == null) return snapshot
    return snapshot.copy(
        errorMessage = error.errorMessage,
        errorId = error.errorId,
        errorDiagnostic = error.errorDiagnostic,
        errorIsStreamInterrupted = error.isStreamInterrupted,
    )
}

/**
 * 基于真实 Mederi core 的 [AiCore] 实现。
 *
 * 负责：
 * - Mederi 实例生命周期管理
 * - core 领域模型 → UI contract 模型的实时转换
 * - Project / Provider / Conversation 的 StateFlow 维护
 * - 会话事件聚合为 [ConversationSnapshot]
 */
class MederiAiCore(
    override val configDir: String,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val retryMinDelayMs: Long? = null,
    private val retryMaxDelayMs: Long? = null,
) : AiCore {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val stateMutex = Mutex()

    private val _compactionThresholdPercent = MutableStateFlow(70)
    override val compactionThresholdPercent: StateFlow<Int> = _compactionThresholdPercent.asStateFlow()
    override suspend fun setCompactionThresholdPercent(percent: Int) {
        _compactionThresholdPercent.value = percent.coerceIn(1, 100)
    }

    /** 会话终态错误内存注册表：MESSAGE_ERROR 写入，MESSAGE_COMPLETED/新 turn 清除。跨 re-attach 水合用。 */
    private val lastErrorBySessionId = ConcurrentHashMap<String, LastSessionError>()

    /**
     * 子代理运行中集合（sessionId -> agentId 集合）：SUBAGENT_STARTED 记入、终态移出。
     * MESSAGE_COMPLETED 时若集合非空则保持 Working（主 turn 结束但子代理仍在跑）。
     * 仅 startSessionStatusSync 的 collect 协程内读写（单协程串行，无需并发容器）。
     */
    private val runningSubagentIds = mutableMapOf<String, MutableSet<String>>()

    /**
     * 主 turn 是否活跃（sessionId -> Boolean）：SESSION_UPDATED 置 true，MESSAGE_COMPLETED/ERROR 置 false。
     * 子代理全部结束后仅在主 turn 不活跃时才回 Idle，避免主 turn 运行中被错误置闲。
     */
    private val mainTurnActive = mutableMapOf<String, Boolean>()

    /** 会话自动命名服务（首条消息发出后生成标题），随 initialize 挂载事件流；依赖 mederi（lateinit），lazy 推迟到 start() 时构造 */
    private val autotitleService by lazy {
        SessionTitleService(mederi, onRenamed = { refreshProjects() }, preferredApiKeyId = lastApiKeyIdByProvider::get)
    }

    /**
     * 供应商 → 该供应商最近一次发送选定的 apiKeyId（仅内存记忆，方案 A 不落库）。
     * 供会话自动命名等"非本 turn"操作跟随用户选定的 key；null = 用默认 key。
     */
    private val lastApiKeyIdByProvider = java.util.concurrent.ConcurrentHashMap<String, String>()

    private lateinit var mederi: Mederi

    /** shutdown 幂等门闩（requestClose 与 JVM shutdown hook 共用，双触发只执行一次）。 */
    private val shutdownOnce = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 可注入的 Obscura 二进制解析器；null = 未找到（不注册，不报错）。internal 供 jvmTest 注入假值。 */
    internal var obscuraBinaryResolver: () -> String? = { ObscuraBinaryLocator.resolve() }

    /**
     * 注册 Obscura 浏览器后端（§1.5 合规：挂 initialize()）。
     * 幂等——Registry.register 同名覆盖。factory 不起进程：每任务由
     * BrowserTaskManager 调 factory 新建轻量 control，start() 才 spawn obscura mcp --stealth。
     * internal 供 jvmTest 直调（真实 initialize() 需完整运行环境，见 ObscuraRegistrationTest 注释）。
     */
    internal fun registerObscuraBackend(resolver: () -> String? = obscuraBinaryResolver) {
        runCatching {
            val binary = resolver()
            if (binary == null) {
                DebugLog.info("AiCore", "Obscura binary not found; browser backend not registered.")
            } else {
                BrowserRegistry.register(
                    BrowserConstants.DEFAULT_BROWSER_OBSCURA,
                    BrowserKind.OBSCURA,
                    factory = { _, _ -> ObscuraBrowserControl(ObscuraMcpClient(binary), sessionLifecycleOwner = true) }
                )
                BrowserRegistry.defaultName = BrowserConstants.DEFAULT_BROWSER_OBSCURA
            }
        }.onFailure { DebugLog.error("AiCore", "Obscura backend registration failed: ${it.message}", it) }
    }

    override suspend fun getSandboxSettings(projectId: String): Result<SandboxSettings> = runCatching {
        mederi.sandbox.getSettings(projectId).toContract()
    }

    override suspend fun updateSandboxSettings(projectId: String, settings: SandboxSettings): Result<SandboxSettings> = runCatching {
        mederi.sandbox.updateSettings(projectId, xyz.mederi.tools.sandbox.SandboxSettings(
            mode = xyz.mederi.tools.sandbox.ExecutionPermissionMode.valueOf(settings.mode.name),
            extraWritablePaths = settings.extraWritablePaths,
            version = settings.version,
            deniedPaths = settings.deniedPaths,
            allowedCommands = settings.allowedCommands,
            deniedCommands = settings.deniedCommands,
        )).toContract()
    }

    override suspend fun getSandboxStatus(projectId: String): Result<SandboxStatusInfo> = runCatching {
        val status = mederi.sandbox.getStatus(projectId)
        SandboxStatusInfo(status.backend, status.available, status.shell, status.detail)
    }

    override suspend fun resolveExecutionApproval(conversationId: String, requestId: String, approved: Boolean): Result<Unit> = runCatching {
        mederi.sandbox.resolveExecutionApproval(conversationId, requestId, approved)
    }

    private fun xyz.mederi.tools.sandbox.SandboxSettings.toContract() = SandboxSettings(
        mode = ExecutionPermissionMode.valueOf(mode.name), extraWritablePaths = extraWritablePaths, version = version,
        deniedPaths = deniedPaths, allowedCommands = allowedCommands, deniedCommands = deniedCommands,
    )

    private fun liveExecutionApprovals(conversationId: String): List<ExecutionApprovalRequest> =
        mederi.sandbox.pendingApprovals(conversationId).map { request ->
            ExecutionApprovalRequest(request.id, request.conversationId, request.executionSessionId,
                request.toolName, request.command, request.paths, request.cwd, request.access, request.reason)
        }

    private val pendingInteractions = PendingInteractionTracker()

    // modelId -> providerId，用于 Conversation.modelProvider 回填
    private var modelToProvider: Map<String, String> = emptyMap()

    private val _isReady = MutableStateFlow(false)
    override val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    override val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    private val _providers = MutableStateFlow<List<ProviderConfig>>(emptyList())
    override val providers: StateFlow<List<ProviderConfig>> = _providers.asStateFlow()

    private val _availableModels = MutableStateFlow<List<ModelOption>>(emptyList())
    override val availableModels: StateFlow<List<ModelOption>> = _availableModels.asStateFlow()

    private val _availableAgents = MutableStateFlow<List<AgentOption>>(emptyList())
    override val availableAgents: StateFlow<List<AgentOption>> = _availableAgents.asStateFlow()

    override val builtinPresets: List<String> = BuiltinProviders.allEntries().map { it.name }

    override suspend fun initialize(): Result<Unit> = runCatching {
        withContext(dispatcher) {
            // 宿主注入的限流重试参数写回 core 全局（RetryableLLMClient 在包装时读取）
            retryMinDelayMs?.let { LlmRetryConfig.minDelayMs = it }
            retryMaxDelayMs?.let { LlmRetryConfig.maxDelayMs = it }
            mederi = Mederi.create {
                configDir = this@MederiAiCore.configDir
                // 出站 HTTP User-Agent 唯一注入点：所有 Koog 链路请求带上 Mederi 身份头
                userAgent = AppInfo.userAgent
                // 自动压缩阈值：UI 拖拽写入 MutableStateFlow，core 压缩判定读此 lambda
                compactionThresholdPercent = { _compactionThresholdPercent.value }
            }
            cleanupLegacyBuiltinProviders()
            cleanupStaleRunningSessions()
            // R09: 生产初始化门禁——重启后扫描并恢复上次崩溃残留的未完成工作区操作
            // （RUNNING/TRANSFERRING/ARCHIVING 等，含 SUCCEEDED Handoff 的绑定坏账核对）。
            // desktop 与 server 共用本桥，任一宿主初始化都触发同一恢复扫描；必须在 _isReady
            // 放行新执行之前完成，避免 readiness 前的并发执行踩到未归位的工作区。
            runCatching { mederi.workspaceManager.scanAndRecoverPendingOperations() }
                .onFailure { DebugLog.event("AiCore", "Workspace crash recovery scan failed: ${it.message}") }
            syncBuiltinProviders()
            refreshGlobalState()
            _isReady.value = true
            // models.dev 元数据目录：启动立即拉取 + 每小时静默刷新（纯内存，不阻塞启动）
            mederi.modelCatalog.start()
            // 后台刷新内置 Google 供应商的模型列表（不阻塞启动）
            autoRefreshBuiltinGoogleModels()
            // 存量模型元数据回填已移除（2026-09 机制翻转）：系统永不自动纠正存量数据，
            // 目录元数据只在用户点「自动设置」时应用（autoSetupProviderModels）
            // 会话自动命名：挂 core 事件流，首条消息发出后生成标题。
            // 挂在 initialize 而非各宿主 main——desktop / server（headless 遥控端）
            // 共用同一桥实现，谁初始化谁生效，宿主无需各自接线。
            autotitleService.start()
            startSessionStatusSync()
            // Obscura 后端注册：未命中 resolver 时只记日志，不注册不报错
            registerObscuraBackend()
            // SIGTERM（kill 无 -9）/ Ctrl+C 兜底：JVM 退出前 abort 在飞 turn + drain 写队列。
            // 手动关闭（requestClose）走同一 [shutdown]；幂等。
            Runtime.getRuntime().addShutdownHook(Thread {
                runCatching { shutdown() }
                    .onFailure { DebugLog.error("AiCore", "shutdown hook failed: ${it.message}") }
            })
        }
    }

    /**
     * 幂等关闭：abortAndJoin 全部 RUNNING 会话 → drain data.db 写队列（限时 2s）。
     * 关闭时的半截正文由流式取消路径落库（collectAssistantPreservingPartial 的
     * persist-then-rethrow），此处只做 turn 善后与写队列排干。
     */
    fun shutdown() {
        if (!shutdownOnce.compareAndSet(false, true)) return
        kotlinx.coroutines.runBlocking {
            runCatching { cleanupStaleRunningSessions() }
                .onFailure { DebugLog.error("AiCore", "shutdown abort failed: ${it.message}") }
            runCatching { mederi.dataDatabaseManager?.drainAndClose(2000) }
                .onFailure { DebugLog.error("AiCore", "shutdown drain failed: ${it.message}") }
        }
    }

    /**
     * 监听 core 事件流，实时将会话状态同步至 _projects StateFlow（驱动侧边栏指示灯）。
     */
    private fun startSessionStatusSync() {
        scope.launch {
            mederi.sessions.events().collect { event ->
                pendingInteractions.accept(MederiModelMapper.toCoreEvent(event))
                // 子代理运行中跟踪：STARTED 记入集合并置 Working；终态移出集合，
                // 集合清空且主 turn 不活跃时才回 Idle（主 turn 运行中由 SESSION_UPDATED/MESSAGE_COMPLETED 驱动状态）。
                when (event.type) {
                    xyz.mederi.domain.model.EventType.SUBAGENT_STARTED -> {
                        val agentId = event.payload["agentId"]
                        if (agentId != null) {
                            runningSubagentIds.getOrPut(event.sessionId) { mutableSetOf() }.add(agentId)
                            updateConversationStatus(event.sessionId, ConversationStatus.Working)
                        }
                    }
                    xyz.mederi.domain.model.EventType.SUBAGENT_COMPLETED,
                    xyz.mederi.domain.model.EventType.SUBAGENT_ERROR,
                    xyz.mederi.domain.model.EventType.SUBAGENT_STOPPED,
                    xyz.mederi.domain.model.EventType.SUBAGENT_DISCARDED -> {
                        val agentId = event.payload["agentId"]
                        if (agentId != null) {
                            val set = runningSubagentIds[event.sessionId]
                            if (set != null) {
                                set.remove(agentId)
                                if (set.isEmpty()) {
                                    runningSubagentIds.remove(event.sessionId)
                                    if (mainTurnActive[event.sessionId] != true) {
                                        updateConversationStatus(event.sessionId, ConversationStatus.Idle)
                                    }
                                }
                            }
                        }
                    }
                    else -> {}
                }
                val newStatus = when (event.type) {
                    xyz.mederi.domain.model.EventType.SESSION_UPDATED -> {
                        // 接收插话不是新 turn，不能把 WaitingUser 或子代理生命周期误翻为 Working。
                        if (event.payload["inputAccepted"] == "true") null else {
                            mainTurnActive[event.sessionId] = true
                            ConversationStatus.Working
                        }
                    }
                    xyz.mederi.domain.model.EventType.MESSAGE_COMPLETED -> {
                        // 主 turn 结束：子代理集合非空时保持 Working（子代理仍在跑），否则回 Idle
                        mainTurnActive[event.sessionId] = false
                        if (runningSubagentIds[event.sessionId]?.isNotEmpty() == true) null else ConversationStatus.Idle
                    }
                    xyz.mederi.domain.model.EventType.MESSAGE_ERROR -> {
                        mainTurnActive[event.sessionId] = false
                        // 优先读 payload["finalStatus"]（TurnExecutor.emit() 注入的权威会话终态），
                        // 兜底 sessionStore 读取（过渡期 payload 缺失时）；取不到时保守回退 Error。
                        val finalStatus = event.payload["finalStatus"]
                        if (finalStatus != null) {
                            mapMessageErrorToStatusByName(finalStatus)
                        } else {
                            val ss = runCatching { mederi.sessions.get(event.sessionId).status }.getOrNull()
                                ?: xyz.mederi.domain.model.SessionStatus.ERROR
                            mapMessageErrorToStatus(ss)
                        }
                    }
                    xyz.mederi.domain.model.EventType.QUESTION_REQUESTED -> ConversationStatus.WaitingUser
                    xyz.mederi.domain.model.EventType.QUESTION_RESOLVED -> ConversationStatus.Working
                    xyz.mederi.domain.model.EventType.PLAN_APPROVAL_REQUESTED -> ConversationStatus.WaitingUser
                    xyz.mederi.domain.model.EventType.PLAN_APPROVAL_RESOLVED -> ConversationStatus.Working
                    xyz.mederi.domain.model.EventType.EXECUTION_APPROVAL_REQUESTED -> ConversationStatus.WaitingUser
                    xyz.mederi.domain.model.EventType.EXECUTION_APPROVAL_RESOLVED ->
                        if (mainTurnActive[event.sessionId] == true || runningSubagentIds[event.sessionId]?.isNotEmpty() == true)
                            ConversationStatus.Working else ConversationStatus.Idle
                    else -> null
                }
                if (pendingInteractions.isWaiting(event.sessionId)) {
                    updateConversationStatus(event.sessionId, ConversationStatus.WaitingUser)
                } else if (newStatus != null) {
                    updateConversationStatus(event.sessionId, newStatus)
                }
                // 终态错误内存注册表：MESSAGE_ERROR 与带断流警告的 MESSAGE_COMPLETED 写入，
                // 正常 MESSAGE_COMPLETED 与新 turn SESSION_UPDATED 清除（跨 re-attach 水合用）
                when (event.type) {
                    xyz.mederi.domain.model.EventType.MESSAGE_ERROR ->
                        lastErrorBySessionId[event.sessionId] = lastErrorFromPayload(event.payload)
                    xyz.mederi.domain.model.EventType.MESSAGE_COMPLETED ->
                        if (event.payload["error"] != null) {
                            lastErrorBySessionId[event.sessionId] = lastErrorFromPayload(event.payload)
                        } else {
                            lastErrorBySessionId.remove(event.sessionId)
                        }
                    xyz.mederi.domain.model.EventType.SESSION_UPDATED ->
                        lastErrorBySessionId.remove(event.sessionId)
                    else -> {}
                }
                // 终端事件：从 DB 重读全量会话数据（updatedAt/status/title 均为 DB 真理源），
                // 驱动侧边栏列表重排与相对时间刷新。比内存 patch 更简单可靠。
                when (event.type) {
                    xyz.mederi.domain.model.EventType.SESSION_UPDATED,
                    xyz.mederi.domain.model.EventType.MESSAGE_COMPLETED,
                    xyz.mederi.domain.model.EventType.MESSAGE_ERROR -> refreshProjects()
                    else -> {}
                }
            }
        }
    }

    private fun updateConversationStatus(sessionId: String, status: ConversationStatus) {
        _projects.update { currentProjects ->
            currentProjects.map { project ->
                if (project.conversations.any { it.id == sessionId }) {
                    project.copy(
                        conversations = project.conversations.map { conv ->
                            if (conv.id == sessionId) conv.copy(status = status) else conv
                        }
                    )
                } else {
                    project
                }
            }
        }
    }

    /**
     * 后台刷新内置 Google 供应商的模型列表。
     *
     * Google 不内置模型（原生 models.list 可拉取），添加后每次启动自动同步远端
     * （只新增模型进列表，不碰已有模型元数据——机制翻转见 autoSetupProviderModels），
     * 失败静默记录，不打扰启动流程。
     */
    private fun autoRefreshBuiltinGoogleModels() {
        scope.launch {
            try {
                val googleProviders = mederi.providers.list().filter {
                    it.type == xyz.mederi.provider.domain.model.ProviderType.GOOGLE &&
                        BuiltinProviders.isBuiltinName(it.name) &&
                        it.defaultApiKey != null
                }
                for (provider in googleProviders) {
                    runCatching { mederi.providers.refreshModels(provider.id) }
                        .onFailure { DebugLog.event("AiCore", "Google 模型自动刷新失败: ${it.message}") }
                }
                if (googleProviders.isNotEmpty()) refreshGlobalState()
            } catch (e: Exception) {
                DebugLog.event("AiCore", "Google 模型自动刷新异常: ${e.message}")
            }
        }
    }

    /**
     * 启动时清理上次崩溃残留的 RUNNING 状态 session。
     * 上次如果 agent 正在跑时被杀进程，session 状态会卡在 RUNNING，UI 会永远显示"发送中"。
     * 必须用 abortAndJoin 而非 abort：abort 对"无活跃 turn 的残留 RUNNING"整体 no-op
     * （activeJobs 是内存态，新建进程后为空，abort 直接 return），状态永远复位不回来；
     * abortAndJoin 对陈旧 RUNNING 有兜底复位（sessionStore.update(IDLE)），且同步等待完成无竞态。
     */
    private suspend fun cleanupStaleRunningSessions() {
        val runningSessions = mederi.sessions.list().filter { it.status == xyz.mederi.domain.model.SessionStatus.RUNNING }
        for (session in runningSessions) {
            mederi.sessions.abortAndJoin(session.id)
        }
    }

    /**
     * 清理历史 Bug 产生的垃圾数据：内置供应商（预设模板）不允许以"无 API Key"的 Provider 形态存在。
     *
     * 早期实现每次启动都把 OpenAI / Google Gemini / Hetzner AI 自动写入配置，
     * 导致未填 Key 的模型也出现在可选列表。这里把"无 Key 且名字命中内置预设"的 Provider 删除。
     * 用户通过 [addBuiltinProvider] 主动添加时才会重新创建（且必须带 Key）。
     */
    private suspend fun cleanupLegacyBuiltinProviders() {
        val coreProviders = mederi.providers.list()
        for (entry in BuiltinProviders.allEntries()) {
            val existing = coreProviders.find { it.name.equals(entry.name, ignoreCase = true) }
            if (existing != null && existing.defaultApiKey == null) {
                mederi.providers.delete(existing.id)
            }
        }
    }

    /**
     * 启动时同步内置供应商配置。
     *
     * 对于已存在的内置供应商（用户已添加且配了 Key），检查其 baseUrl、reasoningParameter、
     * responseSanitization 是否与代码中的预设模板一致。不一致则更新。
     * 模型列表不在此同步——模型由用户从远端拉取或手动添加，不属于预设模板。
     */
    private suspend fun syncBuiltinProviders() {
        val coreProviders = mederi.providers.list()
        for (entry in BuiltinProviders.allEntries()) {
            val existing = coreProviders.find { it.name.equals(entry.name, ignoreCase = true) }
                ?: continue  // 用户未添加此预设供应商，跳过

            val expectedReasoningParameter = entry.def.reasoningParameter
                ?: xyz.mederi.provider.domain.model.ReasoningParameter.forType(entry.def.type)

            val needsUpdate = existing.baseUrl != entry.baseUrl ||
                existing.reasoningParameter != expectedReasoningParameter ||
                existing.responseSanitization != entry.def.responseSanitization ||
                existing.modelsDevKey != entry.def.modelsDevKey

            if (needsUpdate) {
                DebugLog.event("AiCore", "同步内置供应商配置: ${entry.name}")
                mederi.providers.update(
                    existing.id,
                    xyz.mederi.api.UpdateProviderRequest(
                        baseUrl = entry.baseUrl,
                        reasoningParameter = expectedReasoningParameter,
                        modelsDevKey = entry.def.modelsDevKey
                    )
                )
            }
        }
    }

    /**
     * 按内置预设添加供应商。用户只需选择预设 + 填 API Key。
     *
     * 若同名内置供应商已存在（配过 Key），则只更新 Key 并返回现有配置。
     *
     * @param name 内置供应商完整名（如 "Agnes SG"、"Google Gemini"）。
     * @param apiKey 必填 API Key。
     */
    override suspend fun addBuiltinProvider(name: String, apiKey: String): Result<ProviderConfig> = runCatching {
        require(apiKey.isNotBlank()) { "API Key 不能为空" }
        val entry = BuiltinProviders.allEntries().find { it.name.equals(name, ignoreCase = true) }
            ?: throw IllegalArgumentException("未知的内置供应商: $name")

        val existing = mederi.providers.list().find { it.name.equals(entry.name, ignoreCase = true) }
        val provider = if (existing != null) {
            mederi.providers.addKey(
                existing.id,
                xyz.mederi.api.CreateApiKeyRequest(name = "default", value = apiKey, isDefault = true)
            )
            mederi.providers.get(existing.id)
        } else {
            createBuiltin(entry.def, entry.endpoint, apiKey)
        }
        refreshProvidersAndAgents()
        MederiModelMapper.toProviderConfig(provider)
    }

    private suspend fun createBuiltin(def: BuiltinProviderDef, endpoint: EndpointDef, apiKey: String): xyz.mederi.provider.domain.model.Provider {
        return mederi.providers.create(
            xyz.mederi.api.CreateProviderRequest(
                name = def.displayName(endpoint),
                type = def.type.name,
                baseUrl = endpoint.url,
                apiKeys = listOf(
                    xyz.mederi.api.CreateApiKeyRequest(
                        name = "default",
                        value = apiKey,
                        isDefault = true
                    )
                ),
                models = def.models.map {
                    xyz.mederi.api.CreateModelRequest(
                        providerModelId = it.providerModelId,
                        name = it.name,
                        supportsReasoning = it.supportsReasoning,
                        reasoningLevel = it.reasoningLevel,
                        supportsImages = it.supportsImages,
                        reasoningLevels = it.reasoningLevels
                    )
                },
                reasoningParameter = def.reasoningParameter
                    ?: xyz.mederi.provider.domain.model.ReasoningParameter.forType(def.type),
                responseSanitization = def.responseSanitization,
                // 内置供应商填 key 添加是唯一一次自动刷新：拉端点 /models + models.dev 目录补参数
                fetchModels = true,
                modelsDevKey = def.modelsDevKey
            )
        )
    }

    // ------------------------------------------------------------------
    // Project
    // ------------------------------------------------------------------

    override suspend fun createProject(input: CreateProjectInput): Result<Project> = runCatching {
        val coreProject = mederi.projects.create(MederiInputMapper.toCreateProjectRequest(input))
        refreshProjects()
        findUiProject(coreProject.id) ?: error("Project created but not found: ${coreProject.id}")
    }

    override suspend fun renameProject(projectId: String, name: String): Result<Unit> = runCatching {
        mederi.projects.rename(projectId, xyz.mederi.api.RenameProjectRequest(name))
        refreshProjects()
    }

    override suspend fun deleteProject(projectId: String): Result<Unit> = runCatching {
        // 删除项目 = 遍历其下所有会话统一走 [deleteConversation]（删除会话的唯一封装入口）：
        // 内部完成 abortAndJoin 运行中 turn → 删 session/history/diff 表 → 删磁盘 png → 清本地缓存。
        // 循环后只剩项目记录本身和项目目录残留的 .mederi/mermaid 目录需要收尾。
        mederi.sessions.list().filter { it.projectId == projectId }.forEach { session ->
            deleteConversation(session.id).getOrThrow()
        }
        runCatching {
            val project = mederi.projects.get(projectId)
            project.directory.let { dirPath ->
                val mermaidDir = File(dirPath, MederiIdentity.projectWorkdirName + "/mermaid")
                if (mermaidDir.exists()) {
                    mermaidDir.deleteRecursively()
                    DebugLog.event("AiCore", "Cleaned up mermaid cache for deleted project: $dirPath")
                }
            }
        }
        mederi.projects.delete(projectId)
        refreshProjects()
    }

    // ------------------------------------------------------------------
    // Conversation / Session
    // ------------------------------------------------------------------

    override suspend fun createConversation(projectId: String, agent: AgentOption?): Result<Conversation> =
        createConversation(xyz.mederi.core.contract.dto.CreateConversationInput(projectId = projectId, agent = agent))

    override suspend fun createConversation(input: xyz.mederi.core.contract.dto.CreateConversationInput): Result<Conversation> = runCatching {
        if (!::mederi.isInitialized) {
            _isReady.first { it }
        }
        val agentMode = input.agent?.let {
            runCatching { AgentMode.valueOf(it.mode.name) }.getOrNull()
        } ?: AgentMode.AUTONOMOUS
        val session = mederi.sessions.create(
            CreateSessionRequest(
                agentConfig = AgentConfig(
                    agentMode = agentMode
                ),
                projectId = input.projectId,
                title = "",
                workspaceSelection = input.workspaceSelection.toCore()
            )
        )
        // 为会话创建关联的 workspace (REQUESTED)
        val project = mederi.projects.get(input.projectId)
        mederi.workspaceManager.createWorkspace(
            projectId = input.projectId,
            sourceRepoRoot = project.directory,
            selection = input.workspaceSelection.toCore(),
            sessionId = session.id
        )
        refreshProjects()
        val updatedSession = mederi.sessions.get(session.id)
        MederiModelMapper.toConversation(updatedSession, providerId = modelToProvider[updatedSession.aiModel?.id])
    }

    override suspend fun deleteConversation(conversationId: String): Result<Unit> = runCatching {
        // 1. 删除磁盘上的 Mermaid 缓存图片
        runCatching {
            val session = mederi.sessions.get(conversationId)
            val project = mederi.projects.get(session.projectId)
            project.directory.let { dirPath ->
                val mermaidDir = File(dirPath, MederiIdentity.projectWorkdirName + "/mermaid")
                if (mermaidDir.exists() && mermaidDir.isDirectory) {
                    val sanitized = conversationId.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(32)
                    val prefix = "${sanitized}_"
                    mermaidDir.listFiles()?.forEach { file ->
                        if (file.name.startsWith(prefix) && file.name.endsWith(".png")) {
                            file.delete()
                            DebugLog.event("AiCore", "Deleted session mermaid cache: ${file.name}")
                        }
                    }
                }
            }
        }
        // 2. 清理 Inkcompose 客户端缓存
        xyz.emuci.inkcompose.MermaidCacheConfig.clearSessionCache(conversationId)

        mederi.sessions.delete(conversationId)
        refreshProjects()
    }

    override suspend fun renameConversation(conversationId: String, title: String): Result<Unit> = runCatching {
        mederi.sessions.rename(conversationId, RenameSessionRequest(title))
        refreshProjects()
    }

    override fun observeConversation(conversationId: String): Flow<ConversationSnapshot> = conversationFlow(
        conversationId = conversationId,
        sessions = mederi.sessions,
        modelToProvider = { modelToProvider[it] },
        initialSnapshot = { buildInitialSnapshot(conversationId) },
    )

    /**
     * 会话流装配（internal 供 jvmTest 注入 fake 依赖验证线程边界）。
     * 线程边界强制点：flow body（initialSnapshot 首帧构建、计划文件扫描、落库回查）一律由 flowOn
     * 移到 [Dispatchers.IO]，收集者（UI 主线程）只接收已算好的快照，不做数据加工。
     */
    internal fun conversationFlow(
        conversationId: String,
        sessions: SessionApi,
        modelToProvider: (String) -> String?,
        initialSnapshot: suspend () -> ConversationState,
    ): Flow<ConversationSnapshot> = MederiEventAggregator.observe(
        conversationId = conversationId,
        sessions = sessions,
        initialSnapshot = initialSnapshot,
        modelToProvider = modelToProvider,
    ).flowOn(Dispatchers.IO)

    private suspend fun planStoreForSession(conversationId: String): xyz.mederi.plan.PlanStore {
        val session = mederi.sessions.get(conversationId)
        val project = mederi.projects.get(session.projectId)
        return xyz.mederi.plan.PlanStore(
            listOf(project.directory),
            authorityRoot = mederi.sandboxManager.protectedDirectories.firstOrNull()
        )
    }

    private fun xyz.mederi.plan.Plan.toApprovalRequest(store: xyz.mederi.plan.PlanStore) =
        xyz.mederi.core.contract.models.PlanApprovalRequest(
            id = id,
            conversationId = sessionId,
            planPath = store.getPlanAbsolutePath(id).orEmpty(),
            title = title,
            summary = summary,
            subtaskCount = subtasks.size,
            status = if (status == xyz.mederi.plan.PlanStatus.PENDING_APPROVAL) "PENDING" else status.name,
            subtasks = subtasks.map { task ->
                xyz.mederi.core.contract.models.PlanSubtaskItem(
                    index = task.index,
                    name = task.name,
                    status = task.status.name,
                    hasSpec = !task.spec.isNullOrBlank(),
                    acceptanceCases = task.acceptanceCases.map { case ->
                        xyz.mederi.core.contract.models.PlanAcceptanceCaseSummary(case.id, case.manual)
                    }
                )
            }
        )

    /**
     * 单次计划扫描装配：pending + 全量列表同源（[PlanApprovalHydration] 恰好承载一次扫描）。
     * 保持旧语义：loadBySession = listBySession.firstOrNull { !isTerminal }（PlanStore.kt:150-151），
     * 且仅 PENDING_APPROVAL 计划才上审批卡片（不可简化成"列表里第一个 PENDING"）。
     */
    private suspend fun initialPlanHydration(conversationId: String): MederiEventAggregator.PlanApprovalHydration {
        val store = planStoreForSession(conversationId)
        val plans = store.listBySession(conversationId)
        val pending = plans.firstOrNull { !it.isTerminal }
            ?.takeIf { it.status == xyz.mederi.plan.PlanStatus.PENDING_APPROVAL }
            ?.toApprovalRequest(store)
        return MederiEventAggregator.PlanApprovalHydration(pending, plans.map { it.toApprovalRequest(store) })
    }

    /** 生产装配 [MederiEventAggregator.buildInitialSnapshot]：observe 首帧与 getSnapshot 共用同一实现。 */
    private suspend fun buildInitialSnapshot(conversationId: String): ConversationSnapshot {
        // 子代理映射含 progressEvents 回放；buildInitialSnapshot 的 subagents 参数是非挂起 lambda，
        // 须先在协程体内取最终列表再注入（逻辑与旧 getSnapshot :836-852 完全一致）。
        val subagents = mederi.sessions.listSubagentsForSession(conversationId).map { bg ->
            val metadata = xyz.mederi.core.contract.models.SubagentState(
                agentId = bg.agentId,
                parentSessionId = bg.parentSessionId,
                role = bg.role.name,
                modelId = bg.aiModel?.id ?: "",
                modelName = bg.aiModel?.name ?: "",
                reasoningLevel = bg.reasoningLevel?.name,
                task = bg.task,
                briefing = bg.briefing,
                status = bg.status.name,
                startedAt = bg.startedAt,
                completedAt = bg.completedAt,
            )
            val events = synchronized(bg.progressEvents) { bg.progressEvents.toList() }
            xyz.mederi.core.contract.SubagentTracker.replay(metadata, events.map(MederiModelMapper::toCoreEvent))
        }
        return MederiEventAggregator.buildInitialSnapshot(
            conversationId = conversationId,
            sessions = mederi.sessions,
            modelToProvider = { modelToProvider[it] },
            planHydration = { initialPlanHydration(conversationId) },
            executionApprovals = { liveExecutionApprovals(conversationId) },
            // 切换/重连重放与实时相同的标准子事件，不能只水合元数据而丢失推理/工具结果
            // （observe 首帧与 getSnapshot 合一后，两条路径都带 progressEvents 回放）。
            subagents = { subagents },
            lastError = { lastErrorBySessionId[conversationId] },
            // T09：初始快照水合工作区投影（active/associated + 活跃操作）；未绑定工作区的会话返回 null（runCatching 吞未找到异常）。
            workspaceInfo = { runCatching { mederi.workspaceManager.getConversationWorkspace(conversationId).toContract() }.getOrNull() },
        )
    }

    /**
     * 构建会话当前完整快照（不订阅事件流）。
     * server 模式下由 GET /v1/sessions/{id}/snapshot 暴露给 wasmJs 客户端作初始状态。
     */
    override suspend fun getSnapshot(conversationId: String): Result<ConversationSnapshot> = withContext(Dispatchers.IO) {
        runCatching { buildInitialSnapshot(conversationId) }
    }

    // ------------------------------------------------------------------
    // Provider / Model
    // ------------------------------------------------------------------

    private val chatGptModelsSynced = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val chatGptModelSyncErrors = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val chatGptModelSyncLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private val chatGptLoginObservers = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    /** 授权成功后由 AiCore 自动同步，不依赖设置页仍在观察。 */
    private fun observeChatGptLogin(providerId: String) {
        chatGptLoginObservers.remove(providerId)?.cancel()
        val observer = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            repeat(600) {
                val state = mederi.chatGptAuth.state(providerId)
                if (state.status != "PENDING") {
                    if (state.status == "CONNECTED") syncChatGptModels(providerId)
                    refreshProvidersAndAgents()
                    return@launch
                }
                kotlinx.coroutines.delay(1000)
            }
        }
        chatGptLoginObservers[providerId] = observer
        observer.start()
    }

    private suspend fun syncChatGptModels(providerId: String) {
        chatGptModelSyncLocks.computeIfAbsent(providerId) { Mutex() }.withLock {
            if (!chatGptModelsSynced.add(providerId)) return@withLock
            // 授权与模型同步是两个结果；目录暂时失败不撤销已经成功的授权。
            try {
                mederi.providers.refreshModels(providerId)
                chatGptModelSyncErrors.remove(providerId)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                chatGptModelsSynced.remove(providerId)
                throw cancelled
            } catch (failure: Exception) {
                chatGptModelSyncErrors[providerId] = failure.message ?: "Model synchronization failed"
            }
            refreshProvidersAndAgents()
        }
    }

    private suspend fun requireChatGptProvider(providerId: String): Provider {
        val provider = mederi.providerManager.require(providerId)
        require(provider.authentication == xyz.mederi.provider.domain.model.ProviderAuthentication.CHATGPT_OAUTH) {
            "Provider does not use ChatGPT authorization"
        }
        return provider
    }

    override val supportsChatGptLogin: Boolean get() = true

    override suspend fun startChatGptLogin(providerId: String?): Result<ChatGptAuthState> = runCatching {
        val provider = if (providerId == null) mederi.providerManager.createChatGptProvider() else requireChatGptProvider(providerId)
        chatGptLoginObservers.remove(provider.id)?.cancel()
        chatGptModelsSynced.remove(provider.id)
        chatGptModelSyncErrors.remove(provider.id)
        val state = mederi.chatGptAuth.start(provider.id)
        observeChatGptLogin(provider.id)
        refreshProvidersAndAgents()
        ChatGptAuthState(state.providerId, state.status, state.authUrl, state.email, state.error)
    }

    override suspend fun getChatGptAuthState(providerId: String): Result<ChatGptAuthState> = runCatching {
        requireChatGptProvider(providerId)
        val state = mederi.chatGptAuth.state(providerId)
        if (state.status == "CONNECTED") syncChatGptModels(providerId)
        ChatGptAuthState(state.providerId, state.status, state.authUrl, state.email, state.error, chatGptModelSyncErrors[providerId])
    }

    override suspend fun cancelChatGptLogin(providerId: String): Result<Unit> = runCatching {
        requireChatGptProvider(providerId)
        chatGptLoginObservers.remove(providerId)?.cancel()
        mederi.chatGptAuth.cancel(providerId)
        refreshProvidersAndAgents()
    }

    override suspend fun disconnectChatGpt(providerId: String): Result<Unit> = runCatching {
        requireChatGptProvider(providerId)
        chatGptLoginObservers.remove(providerId)?.cancel()
        try {
            mederi.chatGptAuth.disconnect(providerId)
        } finally {
            chatGptModelsSynced.remove(providerId)
            chatGptModelSyncErrors.remove(providerId)
            refreshProvidersAndAgents()
        }
    }

    override suspend fun readChatGptUsage(providerId: String): Result<ChatGptUsage> = runCatching {
        requireChatGptProvider(providerId)
        check(mederi.chatGptAuth.isConnected(providerId)) { "ChatGPT is not connected" }
        // 新 SIWC 文档未提供可验证的订阅额度 HTTP 接口；不可用 token 消耗估算套餐额度。
        ChatGptUsage(unavailableReason = CHATGPT_USAGE_API_UNAVAILABLE)
    }

    override suspend fun configureProvider(
        providerId: String,
        input: ProviderUpdateInput
    ): Result<Unit> = runCatching {
        if (input.baseUrl != null || input.name != null) {
            mederi.providers.update(providerId, MederiInputMapper.toUpdateProviderRequest(input))
        }
        if (!input.apiKey.isNullOrBlank()) {
            mederi.providers.addKey(
                providerId,
                xyz.mederi.api.CreateApiKeyRequest(
                    name = "default",
                    value = input.apiKey,
                    isDefault = true
                )
            )
        }
        refreshProvidersAndAgents()
    }

    override suspend fun setProviderRetrySetting(
        providerId: String,
        autoRetryEnabled: Boolean,
        retryMaxDelaySeconds: Int?
    ): Result<Unit> = runCatching {
        mederi.providers.setRetrySettings(providerId, autoRetryEnabled, retryMaxDelaySeconds)
        refreshProvidersAndAgents()
    }

    override suspend fun deleteProvider(providerId: String): Result<Unit> = runCatching {
        chatGptLoginObservers.remove(providerId)?.cancel()
        chatGptModelsSynced.remove(providerId)
        chatGptModelSyncErrors.remove(providerId)
        mederi.providers.delete(providerId)
        refreshProvidersAndAgents()
    }

    override suspend fun addProviderModel(
        providerId: String,
        providerModelId: String,
        name: String,
        supportsThinking: Boolean,
        supportsImages: Boolean,
        contextWindow: Int?,
        maxTokens: Int?,
        reasoningLevels: List<String>,
        isEnabled: Boolean
    ): Result<Unit> = runCatching {
        val parsedLevels = reasoningLevels.mapNotNull { lvl ->
            runCatching { xyz.mederi.provider.domain.model.ReasoningLevel.valueOf(lvl) }.getOrNull()
        }
        mederi.providers.addModel(
            providerId = providerId,
            request = xyz.mederi.api.CreateModelRequest(
                providerModelId = providerModelId,
                name = name,
                supportsReasoning = supportsThinking,
                reasoningLevel = if (supportsThinking) (parsedLevels.firstOrNull() ?: xyz.mederi.provider.domain.model.ReasoningLevel.MEDIUM) else xyz.mederi.provider.domain.model.ReasoningLevel.NONE,
                contextWindow = contextWindow,
                maxTokens = maxTokens,
                supportsImages = supportsImages,
                reasoningLevels = parsedLevels,
                isEnabled = isEnabled
            )
        )
        refreshProvidersAndAgents()
    }

    override suspend fun deleteProviderModel(providerId: String, modelId: String): Result<Unit> = runCatching {
        mederi.providers.deleteModel(providerId, modelId)
        refreshProvidersAndAgents()
    }

    override suspend fun updateProviderModel(
        providerId: String,
        modelId: String,
        name: String?,
        supportsThinking: Boolean?,
        supportsImages: Boolean?,
        contextWindow: Int?,
        maxTokens: Int?,
        reasoningLevels: List<String>?,
        isEnabled: Boolean?,
        optimizeForRequestCount: Boolean?,
    ): Result<Unit> = runCatching {
        val parsedLevels = reasoningLevels?.mapNotNull { lvl ->
            runCatching { xyz.mederi.provider.domain.model.ReasoningLevel.valueOf(lvl) }.getOrNull()
        }
        mederi.providers.updateModel(
            providerId = providerId,
            modelId = modelId,
            request = xyz.mederi.api.UpdateModelRequest(
                name = name,
                supportsReasoning = supportsThinking,
                supportsImages = supportsImages,
                contextWindow = contextWindow,
                maxTokens = maxTokens,
                reasoningLevels = parsedLevels,
                isEnabled = isEnabled,
                optimizeForRequestCount = optimizeForRequestCount,
            )
        )
        refreshProvidersAndAgents()
    }

    override suspend fun setModelEnabled(providerId: String, modelId: String, enabled: Boolean): Result<Unit> = runCatching {
        mederi.providers.updateModel(
            providerId = providerId,
            modelId = modelId,
            request = xyz.mederi.api.UpdateModelRequest(isEnabled = enabled)
        )
        refreshProvidersAndAgents()
    }

    override suspend fun refreshProviderModels(providerId: String): Result<List<String>> = runCatching {
        // 必须走 ProviderApi.refreshModels（拉取 + 合并落库）；
        // 不能用 providerManager.fetchRemoteModelIds —— 那是只拉取不落库的裸方法
        val ids = mederi.providers.refreshModels(providerId)
        chatGptModelSyncErrors.remove(providerId)
        // 落库后必须刷新 UI StateFlow，否则设置页模型列表停留在旧数据
        refreshProvidersAndAgents()
        ids
    }

    override suspend fun autoSetupProviderModels(providerId: String): Result<Int> = runCatching {
        val updated = mederi.providers.autoSetupModels(providerId)
        refreshProvidersAndAgents()
        updated
    }

    override suspend fun createCustomProvider(input: CreateCustomProviderInput): Result<ProviderConfig> = runCatching {
        val created = mederi.providers.create(MederiInputMapper.toCreateProviderRequest(input))
        refreshProvidersAndAgents()
        // ProviderApi.create 返回的是 key/model 添加前的对象，需要重新 get 才能拿到合并后的 key。
        val provider = mederi.providers.get(created.id)
        MederiModelMapper.toProviderConfig(provider)
    }

    override suspend fun addProviderApiKey(
        providerId: String,
        name: String,
        key: String,
        isDefault: Boolean
    ): Result<ApiKeyOption> = runCatching {
        val apiKey = mederi.providers.addKey(
            providerId,
            xyz.mederi.api.CreateApiKeyRequest(
                name = name,
                value = key,
                isDefault = isDefault
            )
        )
        refreshProvidersAndAgents()
        MederiModelMapper.toApiKeyOption(apiKey)
    }

    override suspend fun deleteProviderApiKey(providerId: String, keyId: String): Result<Unit> = runCatching {
        mederi.providers.deleteKey(providerId, keyId)
        refreshProvidersAndAgents()
    }

    override suspend fun setDefaultProviderApiKey(providerId: String, keyId: String): Result<Unit> = runCatching {
        mederi.providers.setDefaultKey(providerId, keyId)
        refreshProvidersAndAgents()
    }

    // ------------------------------------------------------------------
    // Message
    // ------------------------------------------------------------------

    override suspend fun sendMessage(conversationId: String, input: ChatPromptInput): Result<Unit> = runCatching {
        DebugLog.section("AiCore", "MederiAiCore.sendMessage")
        DebugLog.data("AiCore", "conversationId", conversationId)
        DebugLog.data("AiCore", "input.model", "${input.model?.id} (${input.model?.name})")
        DebugLog.data("AiCore", "input.apiKeyId", input.apiKeyId)
        DebugLog.data("AiCore", "input.agent", "${input.agent?.id} (${input.agent?.name})")
        DebugLog.data("AiCore", "input.thinkingLevel", input.thinkingLevel)
        val model = input.model?.let { findCoreModel(it.id) }
        DebugLog.data("AiCore", "findCoreModel result", "${model?.id} (${model?.name}), providerModelId=${model?.providerModelId}, supportsReasoning=${model?.supportsReasoning}")
        val agentConfig = MederiInputMapper.toAgentConfig(input.agent, model, input.thinkingLevel)
        DebugLog.data("AiCore", "AgentConfig", "agentMode=${agentConfig.agentMode}, aiModel=${agentConfig.aiModel?.id}, reasoningLevel=${agentConfig.reasoningLevel}")
        val parts = MederiInputMapper.toMessageParts(input)
        DebugLog.data("AiCore", "parts count", parts.size)
        DebugLog.data("AiCore", "text", (parts.filterIsInstance<xyz.mederi.domain.model.MessagePart.Text>().firstOrNull()?.text ?: ""))
        val request = SendMessageRequest(
            agentConfig = agentConfig,
            parts = parts,
            apiKeyId = input.apiKeyId,
            messageId = input.messageId
        )
        // 记录该供应商最近一次选定的 key（供自动命名等非 turn 操作跟随；仅内存，不落库）
        if (input.apiKeyId != null) {
            input.model?.let { lastApiKeyIdByProvider[it.provider] = input.apiKeyId }
        }
        // 新 turn 开始：清除陈旧错误，避免 re-attach 水合到上一轮的错误
        lastErrorBySessionId.remove(conversationId)
        mederi.sessions.sendMessage(conversationId, request)
    }

    // 旧调用方兼容入口：发送/插话的状态决策全部由 Core 执行。
    override suspend fun steerMessage(conversationId: String, input: ChatPromptInput): Result<Unit> =
        sendMessage(conversationId, input)

    override suspend fun abort(conversationId: String): Result<Unit> = runCatching {
        mederi.sessions.abort(conversationId)
    }

    override suspend fun rollbackToMessage(conversationId: String, messageId: String): Result<Unit> = runCatching {
        mederi.sessions.rollbackToMessage(conversationId, messageId)
    }

    override suspend fun resolveQuestion(
        conversationId: String,
        questionId: String,
        answers: List<List<String>>
    ): Result<Unit> = runCatching {
        mederi.sessions.resolveQuestion(conversationId, questionId, answers)
    }

    override suspend fun recordPlanManualAcceptance(
        conversationId: String, planId: String, input: xyz.mederi.core.contract.models.ManualPlanAcceptanceInput
    ): Result<Unit> = runCatching {
        mederi.sessions.recordPlanManualAcceptance(
            conversationId, planId, input.caseId, input.observed, input.evidence, input.confirmedBy
        )
    }

    override suspend fun getPlanAcceptanceReport(conversationId: String, planId: String): Result<String> = runCatching {
        mederi.sessions.getPlanAcceptanceReport(conversationId, planId)
    }

    /**
     * 按需读盘计划文档：subtaskIndex=null → planMarkdown 全文；非 null → 该子任务 Spec 原文。
     * 空/越界子任务与计划不存在同样按 Not Found 语义失败。
     */
    override suspend fun getPlanDocument(
        conversationId: String, planId: String, subtaskIndex: Int?
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val store = planStoreForSession(conversationId)
            val plan = store.load(planId)
                ?: throw xyz.mederi.api.exception.MederiNotFoundException("Plan not found: $planId")
            if (subtaskIndex == null) {
                store.planMarkdown(plan)
            } else {
                plan.subtasks.getOrNull(subtaskIndex)?.spec?.takeIf { it.isNotBlank() }
                    ?: throw xyz.mederi.api.exception.MederiNotFoundException(
                        "Spec not found for subtask $subtaskIndex of plan: $planId"
                    )
            }
        }
    }

    override suspend fun resolvePlanApproval(
        conversationId: String,
        planId: String,
        approved: Boolean
    ): Result<Unit> = runCatching {
        mederi.sessions.resolvePlanApproval(conversationId, planId, approved)
    }

    override suspend fun updateConversationSettings(
        conversationId: String,
        input: UpdateConversationSettingsInput
    ): Result<Unit> = runCatching {
        DebugLog.section("AiCore", "MederiAiCore.updateConversationSettings")
        DebugLog.data("AiCore", "conversationId", conversationId)
        DebugLog.data("AiCore", "input.model", "${input.model?.id} (${input.model?.name})")
        DebugLog.data("AiCore", "input.agent", "${input.agent?.id} (${input.agent?.name})")
        DebugLog.data("AiCore", "input.apiKeyId", input.apiKeyId)
        DebugLog.data("AiCore", "input.thinkingLevel", input.thinkingLevel)
        DebugLog.data("AiCore", "input.draftTextLen", input.draftText.length.toString())
        val coreModel = input.model?.let { findCoreModel(it.id) }
        // 全量覆盖写（updateComposeState 做 SET 非 COALESCE）：agentMode 参数非 null，
        // agent 为 null 时兜底 AUTONOMOUS（null 语义由调用方传入的 input 字段表达，agent 空 = 清空态）。
        val agentMode = input.agent?.let {
            runCatching { xyz.mederi.domain.model.AgentMode.valueOf(it.mode.name) }.getOrNull()
        } ?: xyz.mederi.domain.model.AgentMode.AUTONOMOUS
        val reasoningLevel = MederiInputMapper.toReasoningLevel(input.thinkingLevel)
        mederi.sessions.updateComposeState(
            conversationId,
            agentMode = agentMode,
            aiModel = coreModel,
            reasoningLevel = reasoningLevel,
            apiKeyId = input.apiKeyId,
            draftText = input.draftText
        )
    }

    override suspend fun compressHistory(conversationId: String): Result<Unit> = runCatching {
        mederi.sessions.compressHistory(conversationId)
    }

    override suspend fun listMessages(conversationId: String): Result<List<ChatMessage>> = runCatching {
        val messages = mederi.sessions.listMessages(conversationId)
        val toolResults = MederiModelMapper.buildToolResultsById(messages)
        messages.map { MederiModelMapper.toChatMessage(it, toolResults) }
    }

    override suspend fun listMessagesPage(conversationId: String): Result<MessagesPage> = runCatching {
        val messages = mederi.sessions.listMessages(conversationId)
        val toolResults = MederiModelMapper.buildToolResultsById(messages)
        val backfill = MederiModelMapper.toUsageBackfill(messages)
        MessagesPage(
            messages = messages.map { MederiModelMapper.toChatMessage(it, toolResults) },
            tokenUsage = MederiModelMapper.toTokenUsage(messages),
            contextUsedTokens = mederi.sessions.contextUsedTokens(conversationId).toLong(),
            requestCount = backfill.requestCount,
            lastRequestUsage = backfill.lastRequestUsage
        )
    }

    override suspend fun getMessage(
        conversationId: String,
        messageId: String
    ): Result<ChatMessage> = runCatching {
        // 单条消息无法拿到与 ToolCall 跨消息配对的 ToolResult（在同会话紧随的 user 消息），保持单条映射
        val msg = mederi.sessions.getMessage(conversationId, messageId)
        MederiModelMapper.toChatMessage(msg)
    }

    override suspend fun listRawMessages(conversationId: String): Result<List<RawMessageDto>> = runCatching {
        mederi.sessions.listRawMessages(conversationId).map { record ->
            val proj = rawMessageProjection(record)
            RawMessageDto(
                seq = record.seq,
                messageId = record.messageId,
                role = record.role,
                payload = record.payload,
                createdAt = record.createdAt,
                // 4 个诊断列：从 core RawMessageRecord 同名透传（HistoryStore 已抽取落库）
                modelId = record.modelId,
                durationMs = record.durationMs,
                finishReason = record.finishReason,
                status = record.status,
                // 投影字段：jvmMain 侧解析 core payload JSON（core schema 在 JVM 侧合法可见）
                summaryLabel = proj.summaryLabel,
                inputTokens = proj.inputTokens,
                outputTokens = proj.outputTokens
            )
        }
    }

    /**
     * 从 core RawMessageRecord 提取投影字段（summaryLabel + token）。
     *
     * 这段逻辑此前在 commonMain RawMessagesViewModel 里手解 JSON key——core 的落库 JSON schema
     * 在 JVM 侧合法可见，移到 jvmMain 桥是合规的。commonMain UI 只读 DTO 字段，不碰 JSON。
     *
     * 解析失败 / 缺字段 → null（不崩，不兜底）。
     */
    private data class RawMessageProjection(
        val summaryLabel: String?,
        val inputTokens: Long?,
        val outputTokens: Long?,
    )

    private fun rawMessageProjection(record: xyz.mederi.api.RawMessageDto): RawMessageProjection {
        val json = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(record.payload)
        }.getOrNull() ?: return RawMessageProjection(null, null, null)

        val jsonObj = runCatching { json.jsonObject }.getOrNull()
            ?: return RawMessageProjection(null, null, null)

        // ── token 提取 ──
        val inputTokens = jsonObj["inputTokens"]?.let {
            runCatching { it.jsonPrimitive.content.toLongOrNull() }.getOrNull()
        } ?: jsonObj["tokens"]?.let { tokensObj ->
            runCatching { tokensObj.jsonObject["input"]?.jsonPrimitive?.content?.toLongOrNull() }.getOrNull()
        }
        val outputTokens = jsonObj["outputTokens"]?.let {
            runCatching { it.jsonPrimitive.content.toLongOrNull() }.getOrNull()
        } ?: jsonObj["tokens"]?.let { tokensObj ->
            runCatching { tokensObj.jsonObject["output"]?.jsonPrimitive?.content?.toLongOrNull() }.getOrNull()
        }

        // ── summary label 提取 ──
        val summaryLabel = extractSummaryLabelFromJson(jsonObj, record.role)

        return RawMessageProjection(summaryLabel, inputTokens, outputTokens)
    }

    /**
     * 从 core payload JSON 提取消息摘要标签（如 "text"、"bash"、"user: hello..."）。
     * 逻辑与旧 RawMessagesViewModel.extractSummaryLabel 完全一致，但读的是 core 的 JSON schema。
     */
    private fun extractSummaryLabelFromJson(jsonObj: kotlinx.serialization.json.JsonObject, recordRole: String): String? {
        val role = jsonObj["role"]?.let {
            runCatching { it.jsonPrimitive.content }.getOrNull()
        } ?: recordRole
        val parts = jsonObj["parts"]?.let {
            runCatching { it.jsonArray }.getOrNull()
        }

        if (role.equals("user", ignoreCase = true)) {
            val textPart = parts?.mapNotNull {
                runCatching { it.jsonObject }.getOrNull()
            }?.firstOrNull {
                it["text"] != null ||
                    it["type"]?.let { t ->
                        runCatching { t.jsonPrimitive.content.contains("Text", ignoreCase = true) }.getOrDefault(false)
                    } ?: false
            }
            val text = textPart?.get("text")?.let {
                runCatching { it.jsonPrimitive.content }.getOrNull()
            }
            if (!text.isNullOrBlank()) {
                val clean = xyz.mederi.util.PromptComposer.sanitizeUserVisibleText(text).trim()
                val firstLine = clean.lines().firstOrNull()?.trim().orEmpty()
                return "user: " + (if (firstLine.length > 50) firstLine.take(50) + "..." else firstLine)
            }
            val toolResultPart = parts?.mapNotNull {
                runCatching { it.jsonObject }.getOrNull()
            }?.firstOrNull {
                it["tool"] != null && it["output"] != null
            }
            if (toolResultPart != null) {
                val toolName = toolResultPart["tool"]?.let {
                    runCatching { it.jsonPrimitive.content }.getOrNull()
                } ?: "tool"
                return "$toolName result"
            }
            return "user"
        }

        if (parts != null && parts.isNotEmpty()) {
            val partLabels = mutableListOf<String>()
            for (partElement in parts) {
                val partObj = runCatching { partElement.jsonObject }.getOrNull() ?: continue
                val tool = partObj["tool"]?.let {
                    runCatching { it.jsonPrimitive.content }.getOrNull()
                }
                if (!tool.isNullOrBlank()) {
                    partLabels.add(tool)
                } else if (partObj["content"] != null || partObj["summary"] != null ||
                    partObj["type"]?.let { t ->
                        runCatching { t.jsonPrimitive.content.contains("Reasoning", ignoreCase = true) }.getOrDefault(false)
                    } ?: false
                ) {
                    partLabels.add("reasoning")
                } else if (partObj["text"] != null ||
                    partObj["type"]?.let { t ->
                        runCatching { t.jsonPrimitive.content.contains("Text", ignoreCase = true) }.getOrDefault(false)
                    } ?: false
                ) {
                    partLabels.add("text")
                }
            }
            if (partLabels.isNotEmpty()) {
                val distinctLabels = mutableListOf<String>()
                for (lbl in partLabels) {
                    if (distinctLabels.isEmpty() || distinctLabels.last() != lbl) {
                        distinctLabels.add(lbl)
                    }
                }
                return distinctLabels.joinToString(" + ")
            }
        }

        return role.lowercase()
    }

    override suspend fun getProcessStats(): Result<ProcessStats> = runCatching {
        // 本进程 = desktop/server 宿主进程整体（UI、core、Ktor、GC 全部线程都在口径内）
        val stats = xyz.mederi.debug.ProcessStatsMonitor.snapshot()
        ProcessStats(
            cpuUsage = stats.cpuUsage,
            cpuCores = stats.cpuCores,
            heapUsedBytes = stats.heapUsedBytes,
            heapCommittedBytes = stats.heapCommittedBytes,
            heapMaxBytes = stats.heapMaxBytes,
            rssBytes = stats.rssBytes,
            timestampMillis = stats.timestampMillis
        )
    }

    override fun events(): Flow<CoreEvent> =
        mederi.sessions.events().map { MederiModelMapper.toCoreEvent(it) }

    // ------------------------------------------------------------------
    // Diff
    // ------------------------------------------------------------------

    override suspend fun getFileDiffs(
        conversationId: String,
        messageId: String?
    ): Result<List<FileDiff>> = runCatching {
        mederi.sessions.getFileDiffs(conversationId, messageId)
            .map { MederiModelMapper.toFileDiff(it) }
    }

    override suspend fun previewOffice(conversationId: String, path: String): Result<String> = runCatching {
        val session = mederi.sessions.get(conversationId)
        val project = mederi.projects.get(session.projectId)
        // 路径解析：相对路径基于项目目录
        val file = java.io.File(path).let { f ->
            if (f.isAbsolute) f else java.io.File(project.directory, path)
        }
        if (!file.exists()) throw IllegalArgumentException("File not found: $path")
        if (!file.isFile) throw IllegalArgumentException("Not a file: $path")
        xyz.mederi.office.OfficeConverter.toHtml(file.absolutePath)
    }

    // ------------------------------------------------------------------
    // State refresh
    // ------------------------------------------------------------------

    private suspend fun refreshGlobalState() = stateMutex.withLock {
        refreshProvidersAndModelsLocked()
        refreshProjectsLocked()
    }

    private suspend fun refreshProjects() = stateMutex.withLock {
        refreshProjectsLocked()
    }

    private suspend fun refreshProjectsLocked() {
        val coreProjects = mederi.projects.list()
        val coreSessions = mederi.sessions.list()
        val sessionMap = coreSessions.groupBy { it.projectId }

        _projects.value = coreProjects.map { coreProject ->
            val conversations = sessionMap[coreProject.id].orEmpty().map { session ->
                MederiModelMapper.toConversation(session, modelToProvider[session.aiModel?.id]).let { conversation ->
                    if (pendingInteractions.isWaiting(session.id) || mederi.sandbox.pendingApprovals(session.id).isNotEmpty())
                        conversation.copy(status = ConversationStatus.WaitingUser) else conversation
                }
            }
            MederiModelMapper.toProject(coreProject, conversations)
        }
    }

    private suspend fun refreshProvidersAndAgents() = stateMutex.withLock {
        refreshProvidersAndModelsLocked()
        refreshProjectsLocked()
    }

    /**
     * 重建 providers / models / agents 状态（调用方须已持有 stateMutex）。
     */
    private suspend fun refreshProvidersAndModelsLocked() {
        val coreProviders = mederi.providers.list()
        rebuildModelToProvider(coreProviders)

        _providers.value = coreProviders.map { MederiModelMapper.toProviderConfig(it, mederi.chatGptAuth.isConnected(it.id)) }
        // 只暴露已配置 API Key 的供应商模型，未填 Key 的模型不出现在可选列表；
        // 用户关闭（isEnabled=false）的模型也不出现
        _availableModels.value = coreProviders
            .filter { it.defaultApiKey != null || (it.authentication == xyz.mederi.provider.domain.model.ProviderAuthentication.CHATGPT_OAUTH && mederi.chatGptAuth.isConnected(it.id)) }
            .flatMap { provider ->
                provider.models
                    .filter { it.isEnabled }
                    .map { MederiModelMapper.toModelOption(it, provider.id, provider.reasoningParameter) }
            }
        _availableAgents.value = BuiltinAgents.ALL
    }

    private fun rebuildModelToProvider(providers: List<Provider>) {
        modelToProvider = providers.flatMap { provider ->
            provider.models.map { it.id to provider.id }
        }.toMap()
    }

    private suspend fun findUiProject(projectId: String): Project? {
        return _projects.value.find { it.id == projectId }
    }

    private suspend fun findCoreModel(modelId: String): xyz.mederi.domain.model.AIModel? {
        return mederi.models.get(modelId)
    }

    override suspend fun listMcpServers(): Result<List<McpServerItem>> = runCatching {
        if (!::mederi.isInitialized) {
            xyz.mederi.ui.DebugLog.info("MCP", "listMcpServers: mederi not initialized yet, waiting for isReady...")
            _isReady.first { it }
        }
        val rawList = mederi.mcpServers.list()
        xyz.mederi.ui.DebugLog.info("MCP", "listMcpServers: fetched ${rawList.size} servers from mederi.mcpServers")
        rawList.map {
            McpServerItem(
                name = it.name,
                enabled = it.enabled,
                kind = it.kind,
                summary = it.summary,
                status = when (it.status) {
                    xyz.mederi.mcp.servers.domain.McpServerStatus.OK -> McpServerStatus.OK
                    xyz.mederi.mcp.servers.domain.McpServerStatus.FAILED -> McpServerStatus.FAILED
                    else -> McpServerStatus.UNCHECKED
                },
                toolCount = it.toolCount,
                lastError = it.lastError
            )
        }
    }

    override suspend fun setMcpServerEnabled(name: String, enabled: Boolean): Result<Unit> = runCatching {
        mederi.mcpServers.setEnabled(name, enabled)
    }

    override suspend fun installMcpServer(json: String): Result<Unit> = runCatching {
        val res = mederi.mcpServers.install(json)
        if (!res.success) {
            error(res.errors.joinToString("; ").ifEmpty { "安装 MCP 服务失败" })
        }
    }

    override suspend fun updateMcpServer(name: String, json: String): Result<Unit> = runCatching {
        val res = mederi.mcpServers.update(name, json)
        if (!res.success) {
            error(res.errors.joinToString("; ").ifEmpty { "更新 MCP 服务失败" })
        }
    }

    override suspend fun deleteMcpServer(name: String): Result<Unit> = runCatching {
        mederi.mcpServers.delete(name)
    }

    override suspend fun verifyMcpServer(name: String): Result<Unit> = runCatching {
        mederi.mcpServers.verify(name)
    }

    override suspend fun getMcpServerJson(name: String): Result<String> = runCatching {
        mederi.mcpServers.getJson(name)
    }

    // ==========================================
    // Skill 管理（进程内直调 core）
    // ==========================================

    override suspend fun listSkills(): Result<List<SkillItem>> = runCatching {
        if (!::mederi.isInitialized) {
            _isReady.first { it }
        }
        mederi.skills.list().map {
            SkillItem(
                name = it.name,
                description = it.description,
                location = it.location,
                license = it.license,
                compatibility = it.compatibility,
                allowedTools = it.allowedTools,
            )
        }
    }

    override suspend fun getSkillsRoot(): Result<String> = runCatching {
        if (!::mederi.isInitialized) {
            _isReady.first { it }
        }
        mederi.skills.getRootDirectory()
    }

    override suspend fun setSkillsRoot(path: String): Result<Unit> = runCatching {
        if (!::mederi.isInitialized) {
            _isReady.first { it }
        }
        mederi.skills.setRootDirectory(path)
    }

    override suspend fun installSkill(url: String): Result<SkillItem> = runCatching {
        if (!::mederi.isInitialized) {
            _isReady.first { it }
        }
        mederi.skills.install(url).let {
            SkillItem(
                name = it.name,
                description = it.description,
                location = it.location,
                license = it.license,
                compatibility = it.compatibility,
                allowedTools = it.allowedTools,
            )
        }
    }

    override suspend fun uninstallSkill(name: String): Result<Unit> = runCatching {
        if (!::mederi.isInitialized) {
            _isReady.first { it }
        }
        mederi.skills.uninstall(name)
    }

    override suspend fun listActiveSkills(conversationId: String): Result<List<SkillItem>> = runCatching {
        if (!::mederi.isInitialized) {
            _isReady.first { it }
        }
        mederi.sessions.listActiveSkills(conversationId).map {
            SkillItem(
                name = it.name,
                description = it.description,
                location = it.location,
                license = it.license,
                compatibility = it.compatibility,
                allowedTools = it.allowedTools,
            )
        }
    }

    override suspend fun activateSkill(conversationId: String, skillName: String): Result<SkillItem> = runCatching {
        if (!::mederi.isInitialized) {
            _isReady.first { it }
        }
        mederi.sessions.activateSkill(conversationId, skillName).let {
            SkillItem(
                name = it.name,
                description = it.description,
                location = it.location,
                license = it.license,
                compatibility = it.compatibility,
                allowedTools = it.allowedTools,
            )
        }
    }

    override suspend fun deactivateSkill(conversationId: String, skillName: String): Result<Unit> = runCatching {
        if (!::mederi.isInitialized) {
            _isReady.first { it }
        }
        mederi.sessions.deactivateSkill(conversationId, skillName)
    }

    // ==========================================
    // AGENTS.md 生成（进程内直调 core；读取/注入在 TurnExecutor 代码级完成）
    // ==========================================

    /** 懒构造：依赖 mederi（lateinit），推迟到首次调用（同 autotitleService 模式） */
    private val agentsFileGenerator by lazy {
        xyz.mederi.infrastructure.koog.AgentsFileGenerator(
            projectManager = mederi.projectManager,
            sessionManager = mederi.sessionManager,
            providerManager = mederi.providerManager
        )
    }

    override suspend fun generateAgentsFile(projectId: String, modelId: String?): Result<String> = runCatching {
        if (!::mederi.isInitialized) {
            _isReady.first { it }
        }
        agentsFileGenerator.generate(projectId, modelId).getOrThrow()
    }

    // ==========================================
    // 子代理模型配置
    // ==========================================

    override suspend fun listSubagentConfigs(): Result<List<SubagentConfigItem>> = runCatching {
        if (!::mederi.isInitialized) {
            _isReady.first { it }
        }
        mederi.subagentConfigs.list().map {
            SubagentConfigItem(
                role = it.role.name,
                displayName = it.displayName,
                description = it.description,
                modelId = it.modelId,
                modelName = it.modelName,
                reasoningLevel = it.reasoningLevel?.name,
                apiKeyId = it.apiKeyId,
                isInheriting = it.isInheriting
            )
        }
    }

    override suspend fun updateSubagentConfig(role: String, input: UpdateSubagentConfigInput): Result<Unit> = runCatching {
        if (!::mederi.isInitialized) {
            _isReady.first { it }
        }
        val subagentRole = xyz.mederi.domain.model.SubagentRole.valueOf(role)
        val reasoningLevel = input.reasoningLevel?.let {
            xyz.mederi.provider.domain.model.ReasoningLevel.valueOf(it)
        }
        mederi.subagentConfigs.update(
            subagentRole,
            xyz.mederi.api.UpdateSubagentConfigRequest(
                modelId = input.modelId,
                reasoningLevel = reasoningLevel,
                apiKeyId = input.apiKeyId
            )
        )
    }

    override suspend fun getSubagentGlobalSettings(): Result<SubagentGlobalSettings> = runCatching {
        if (!::mederi.isInitialized) {
            _isReady.first { it }
        }
        val settings = mederi.subagentConfigs.getGlobalSettings()
        SubagentGlobalSettings(maxConcurrentAgents = settings.maxConcurrentAgents)
    }

    override suspend fun updateSubagentGlobalSettings(input: UpdateSubagentGlobalSettingsInput): Result<Unit> = runCatching {
        if (!::mederi.isInitialized) {
            _isReady.first { it }
        }
        mederi.subagentConfigs.updateGlobalSettings(
            xyz.mederi.api.UpdateSubagentGlobalSettingsRequest(maxConcurrentAgents = input.maxConcurrentAgents)
        )
    }

    override suspend fun listSessionSubagentConfigs(conversationId: String): Result<List<SubagentConfigItem>> = runCatching {
        if (!::mederi.isInitialized) _isReady.first { it }
        mederi.subagentConfigs.list(sessionId = conversationId).map {
            SubagentConfigItem(
                role = it.role.name, displayName = it.displayName, description = it.description,
                modelId = it.modelId, modelName = it.modelName, reasoningLevel = it.reasoningLevel?.name,
                apiKeyId = it.apiKeyId, isInheriting = it.isInheriting, usesDefault = it.usesDefault
            )
        }
    }

    override suspend fun updateSessionSubagentConfig(
        conversationId: String, role: String, input: UpdateSubagentConfigInput?
    ): Result<Unit> = runCatching {
        if (!::mederi.isInitialized) _isReady.first { it }
        val subagentRole = xyz.mederi.domain.model.SubagentRole.valueOf(role)
        if (input == null) {
            mederi.subagentConfigs.clear(subagentRole, conversationId)
        } else {
            mederi.subagentConfigs.update(subagentRole, xyz.mederi.api.UpdateSubagentConfigRequest(
                modelId = input.modelId,
                reasoningLevel = input.reasoningLevel?.let { xyz.mederi.provider.domain.model.ReasoningLevel.valueOf(it) },
                apiKeyId = input.apiKeyId
            ), sessionId = conversationId)
        }
    }

    override suspend fun getSessionSubagentGlobalSettings(conversationId: String): Result<SubagentGlobalSettings> = runCatching {
        if (!::mederi.isInitialized) _isReady.first { it }
        val settings = mederi.subagentConfigs.getGlobalSettings(sessionId = conversationId)
        SubagentGlobalSettings(settings.maxConcurrentAgents, usesDefault = settings.usesDefault)
    }

    override suspend fun updateSessionSubagentGlobalSettings(
        conversationId: String, input: UpdateSubagentGlobalSettingsInput?
    ): Result<Unit> = runCatching {
        if (!::mederi.isInitialized) _isReady.first { it }
        if (input == null) mederi.subagentConfigs.clearGlobalSettings(conversationId)
        else mederi.subagentConfigs.updateGlobalSettings(
            xyz.mederi.api.UpdateSubagentGlobalSettingsRequest(input.maxConcurrentAgents), sessionId = conversationId)
    }

    override suspend fun getSubagentReport(agentId: String): Result<SubagentReportDto> = runCatching {
        if (!::mederi.isInitialized) {
            _isReady.first { it }
        }
        val report = mederi.sessions.getSubagentReport(agentId)
            ?: throw xyz.mederi.api.exception.MederiNotFoundException("Subagent report not found for agent: $agentId")
        SubagentReportDto(
            agentId = report.agentId,
            role = report.role.name,
            status = report.status.name,
            reportPath = report.reportPath,
            content = report.content
        )
    }

    override suspend fun stopSubagent(agentId: String): Result<Unit> = runCatching {
        if (!::mederi.isInitialized) {
            _isReady.first { it }
        }
        mederi.sessions.stopSubagent(agentId)
    }

    override suspend fun listBackgroundTasks(sessionId: String): Result<List<BackgroundTaskInfo>> = runCatching {
        if (!::mederi.isInitialized) {
            _isReady.first { it }
        }
        mederi.sessions.listBackgroundTasksForSession(sessionId).map { entry ->
            BackgroundTaskInfo(
                taskId = entry.taskId,
                taskType = entry.type.name.lowercase(),
                status = entry.status.name.lowercase(),
                command = entry.command,
                description = entry.description,
                pid = entry.pid,
                exitCode = entry.exitCode,
                startedAt = entry.startedAt,
                completedAt = entry.completedAt,
                durationSeconds = entry.durationSeconds
            )
        }
    }

    override suspend fun stopBackgroundTask(taskId: String): Result<Unit> = runCatching {
        if (!::mederi.isInitialized) {
            _isReady.first { it }
        }
        mederi.sessions.stopBackgroundTask(taskId) ?: Unit
    }

    override suspend fun stopBrowserTask(taskId: String): Result<Unit> = runCatching {
        if (!::mederi.isInitialized) {
            _isReady.first { it }
        }
        // null = 任务不存在，如实抛错而非伪装成功（调用方/UI 会看到真实失败）
        mederi.sessions.stopBrowserTask(taskId)
            ?: throw NoSuchElementException("Browser task not found: $taskId")
    }

    // ------------------------------------------------------------------
    // Workspace Management
    // ------------------------------------------------------------------

    override suspend fun getRepositoryInfo(projectId: String): Result<xyz.mederi.core.contract.models.RepositoryInfo> = runCatching {
        if (!::mederi.isInitialized) _isReady.first { it }
        val project = mederi.projects.get(projectId)
        mederi.workspaceManager.getRepositoryInfo(project.directory).toContract()
    }

    override suspend fun listWorkspaces(projectId: String): Result<List<xyz.mederi.core.contract.models.WorkspaceInfo>> = runCatching {
        if (!::mederi.isInitialized) _isReady.first { it }
        mederi.workspaceManager.listWorkspaces(projectId).map { it.toContract() }
    }

    override suspend fun getConversationWorkspace(sessionId: String): Result<xyz.mederi.core.contract.models.ConversationWorkspaceInfo> = runCatching {
        if (!::mederi.isInitialized) _isReady.first { it }
        mederi.workspaceManager.getConversationWorkspace(sessionId).toContract()
    }

    override suspend fun previewWorkspaceHandoff(sessionId: String, input: xyz.mederi.core.contract.models.HandoffPreviewInput): Result<xyz.mederi.core.contract.models.HandoffPreview> = runCatching {
        if (!::mederi.isInitialized) _isReady.first { it }
        mederi.workspaceManager.previewHandoff(sessionId, input.toCore()).toContract()
    }

    override suspend fun handoffWorkspace(sessionId: String, input: xyz.mederi.core.contract.models.HandoffInput): Result<xyz.mederi.core.contract.models.WorkspaceOperation> = runCatching {
        if (!::mederi.isInitialized) _isReady.first { it }
        val op = mederi.workspaceManager.executeHandoff(sessionId, input.toCore()).toContract()
        refreshProjects()
        op
    }

    override suspend fun restoreWorkspace(workspaceId: String, input: xyz.mederi.core.contract.models.RestoreWorkspaceInput): Result<xyz.mederi.core.contract.models.WorkspaceOperation> = runCatching {
        if (!::mederi.isInitialized) _isReady.first { it }
        val op = mederi.workspaceManager.restoreWorkspace(workspaceId, input.toCore()).toContract()
        refreshProjects()
        op
    }

    override suspend fun archiveWorkspace(workspaceId: String, input: xyz.mederi.core.contract.models.ArchiveWorkspaceInput): Result<xyz.mederi.core.contract.models.WorkspaceOperation> = runCatching {
        if (!::mederi.isInitialized) _isReady.first { it }
        val op = mederi.workspaceManager.archiveWorkspace(workspaceId, input.toCore()).toContract()
        refreshProjects()
        op
    }

    override suspend fun createWorkspaceBranch(workspaceId: String, input: xyz.mederi.core.contract.models.CreateWorkspaceBranchInput): Result<xyz.mederi.core.contract.models.WorkspaceInfo> = runCatching {
        if (!::mederi.isInitialized) _isReady.first { it }
        mederi.workspaceManager.createWorkspaceBranch(workspaceId, input.toCore()).toContract()
    }

    override suspend fun getWorkspaceOperation(id: String): Result<xyz.mederi.core.contract.models.WorkspaceOperation> = runCatching {
        if (!::mederi.isInitialized) _isReady.first { it }
        mederi.workspaceManager.getOperation(id).toContract()
    }

    override suspend fun cancelWorkspaceOperation(id: String): Result<xyz.mederi.core.contract.models.WorkspaceOperation> = runCatching {
        if (!::mederi.isInitialized) _isReady.first { it }
        mederi.workspaceManager.cancelOperation(id).toContract()
    }

    override suspend fun listWorkspaceOperations(projectId: String): Result<List<xyz.mederi.core.contract.models.WorkspaceOperation>> = runCatching {
        if (!::mederi.isInitialized) _isReady.first { it }
        mederi.workspaceManager.listWorkspaceOperations(projectId).map { it.toContract() }
    }

    override suspend fun setConversationArchived(sessionId: String, archived: Boolean, requestId: String): Result<xyz.mederi.core.contract.models.WorkspaceOperation> = runCatching {
        if (!::mederi.isInitialized) _isReady.first { it }
        val op = mederi.workspaceManager.setConversationArchived(sessionId, archived, requestId).toContract()
        refreshProjects()
        op
    }

    override suspend fun setConversationPinned(sessionId: String, pinned: Boolean): Result<Conversation> = runCatching {
        if (!::mederi.isInitialized) _isReady.first { it }
        val session = mederi.workspaceManager.setConversationPinned(sessionId, pinned)
        refreshProjects()
        MederiModelMapper.toConversation(session, providerId = modelToProvider[session.aiModel?.id])
    }

    override suspend fun listArchivedConversations(projectId: String): Result<List<Conversation>> = runCatching {
        if (!::mederi.isInitialized) _isReady.first { it }
        mederi.sessions.list()
            .filter { it.projectId == projectId && it.archivedAt != null }
            .map { MederiModelMapper.toConversation(it, providerId = modelToProvider[it.aiModel?.id]) }
    }

    override suspend fun getWorktreeSettings(): Result<xyz.mederi.core.contract.models.WorktreeSettings> = runCatching {
        if (!::mederi.isInitialized) _isReady.first { it }
        mederi.workspaceManager.getWorktreeSettings().toContract()
    }

    override suspend fun updateWorktreeSettings(settings: xyz.mederi.core.contract.models.WorktreeSettings): Result<xyz.mederi.core.contract.models.WorktreeSettings> = runCatching {
        if (!::mederi.isInitialized) _isReady.first { it }
        mederi.workspaceManager.updateWorktreeSettings(settings.toCore()).toContract()
    }

    override suspend fun listProjectEnvironments(projectId: String, workspaceId: String?): Result<List<xyz.mederi.core.contract.models.ProjectEnvironment>> = runCatching {
        if (!::mederi.isInitialized) _isReady.first { it }
        mederi.workspaceManager.listProjectEnvironments(projectId, workspaceId).map { it.toContract() }
    }

    override suspend fun saveProjectEnvironment(projectId: String, environment: xyz.mederi.core.contract.models.ProjectEnvironment, expectedVersion: Long, workspaceId: String?): Result<xyz.mederi.core.contract.models.ProjectEnvironment> = runCatching {
        if (!::mederi.isInitialized) _isReady.first { it }
        mederi.workspaceManager.saveProjectEnvironment(projectId, environment.toCore(), expectedVersion, workspaceId).toContract()
    }

    override suspend fun runWorkspaceAction(workspaceId: String, input: xyz.mederi.core.contract.models.WorkspaceActionInput): Result<xyz.mederi.core.contract.models.WorkspaceOperation> = runCatching {
        if (!::mederi.isInitialized) _isReady.first { it }
        mederi.workspaceManager.runWorkspaceAction(workspaceId, input.toCore()).toContract()
    }

    override suspend fun getWorkspaceOperationLog(id: String, afterOffset: Long, maxBytes: Int): Result<xyz.mederi.core.contract.models.LogChunk> = runCatching {
        if (!::mederi.isInitialized) _isReady.first { it }
        mederi.workspaceManager.getWorkspaceOperationLog(id, afterOffset, maxBytes).toContract()
    }

    override suspend fun listWorkspaceFiles(workspaceId: String, path: String, area: xyz.mederi.core.contract.models.WorkspaceFileArea): Result<List<xyz.mederi.core.contract.models.WorkspaceFileEntry>> = runCatching {
        if (!::mederi.isInitialized) _isReady.first { it }
        mederi.workspaceManager.listWorkspaceFiles(workspaceId, path, area.toCore()).map { it.toContract() }
    }

    override suspend fun readWorkspaceFile(workspaceId: String, path: String, offset: Long, maxBytes: Int, area: xyz.mederi.core.contract.models.WorkspaceFileArea): Result<xyz.mederi.core.contract.models.WorkspaceFileContent> = runCatching {
        if (!::mederi.isInitialized) _isReady.first { it }
        mederi.workspaceManager.readWorkspaceFile(workspaceId, path, offset, maxBytes, area.toCore()).toContract()
    }

    override suspend fun retryWorkspacePreparation(sessionId: String, failedOperationId: String, requestId: String): Result<xyz.mederi.core.contract.models.WorkspaceOperation> = runCatching {
        if (!::mederi.isInitialized) _isReady.first { it }
        mederi.workspaces.retryWorkspacePreparation(sessionId, failedOperationId, requestId).toContract()
    }

    override suspend fun retryWorkspaceSetup(workspaceId: String, failedOperationId: String, expectedEnvironmentHash: String, requestId: String): Result<xyz.mederi.core.contract.models.WorkspaceOperation> = runCatching {
        if (!::mederi.isInitialized) _isReady.first { it }
        mederi.workspaceManager.retrySetup(workspaceId, failedOperationId, expectedEnvironmentHash, requestId).toContract()
    }

    override suspend fun previewWorkspaceRecovery(operationId: String, strategy: xyz.mederi.core.contract.models.RecoveryStrategy, snapshotId: String?, keepWorkspaceId: String?): Result<xyz.mederi.core.contract.models.RecoveryPreview> = runCatching {
        if (!::mederi.isInitialized) _isReady.first { it }
        mederi.workspaceManager.previewRecovery(operationId, strategy.toCore(), snapshotId, keepWorkspaceId).toContract()
    }

    override suspend fun recoverWorkspaceOperation(operationId: String, input: xyz.mederi.core.contract.models.WorkspaceRecoveryInput): Result<xyz.mederi.core.contract.models.WorkspaceOperation> = runCatching {
        if (!::mederi.isInitialized) _isReady.first { it }
        val op = mederi.workspaceManager.executeRecovery(input.toCore()).toContract()
        refreshProjects()
        op
    }
}
