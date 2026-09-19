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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import xyz.mederi.AppInfo
import xyz.mederi.Mederi
import xyz.mederi.api.AgentConfig
import xyz.mederi.api.CreateSessionRequest
import xyz.mederi.api.RenameSessionRequest
import xyz.mederi.api.SendMessageRequest
import xyz.mederi.core.autotitle.SessionTitleService
import xyz.mederi.core.contract.AiCore
import xyz.mederi.plan.toTodoProjection
import xyz.mederi.core.contract.dto.ChatPromptInput
import xyz.mederi.core.contract.dto.ConversationSnapshot
import xyz.mederi.core.contract.dto.CreateCustomProviderInput
import xyz.mederi.core.contract.dto.MessagesPage
import xyz.mederi.core.contract.dto.RawMessageDto
import xyz.mederi.core.contract.dto.CreateProjectInput
import xyz.mederi.core.contract.dto.ProviderUpdateInput
import xyz.mederi.core.contract.models.ApiKeyOption
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
import xyz.mederi.domain.model.AgentMode
import xyz.mederi.domain.model.Session
import xyz.mederi.debug.DebugLog
import xyz.mederi.provider.domain.model.Provider

/** 会话最近一次终态错误的瞬态登记（内存态，不持久化；MESSAGE_ERROR 写入，MESSAGE_COMPLETED/新 turn 清除）。 */
data class LastSessionError(
    val errorMessage: String?,
    val errorId: String?,
    val errorDiagnostic: String?,
    val isStreamInterrupted: Boolean,
    val atMs: Long,
)

/** 从 MESSAGE_ERROR 事件 payload 解析 LastSessionError（payload 键与 SnapshotReducer 读取的一致：error/errorId/fullDiagnostic/failureMode）。 */
internal fun lastErrorFromPayload(payload: Map<String, String>): LastSessionError =
    LastSessionError(
        errorMessage = payload["error"],
        errorId = payload["errorId"],
        errorDiagnostic = payload["fullDiagnostic"],
        isStreamInterrupted = payload["failureMode"] == "PREMATURE_CLOSE",
        atMs = xyz.mederi.currentTimeMillis(),
    )

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
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) : AiCore, xyz.mederi.core.contract.SandboxHooks {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val stateMutex = Mutex()

    /** 会话终态错误内存注册表：MESSAGE_ERROR 写入，MESSAGE_COMPLETED/新 turn 清除。跨 re-attach 水合用。 */
    private val lastErrorBySessionId = ConcurrentHashMap<String, LastSessionError>()

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

    /**
     * 沙盒全局白名单写穿：设置页改白名单后实时写入进程级 SandboxConfig，
     * 下一个 turn 的 CommandSandbox 即生效，无需重启。
     * 仅嵌入式 core 支持（sandbox 配置是本机进程偏好，不进 AiCore 契约）。
     */
    override fun setSandboxExtraPaths(paths: List<String>) {
        xyz.mederi.tools.sandbox.SandboxConfig.extraWritablePaths = paths
    }

    /** 平台沙箱状态（设置页状态卡）：项目无关，探测一次即用 */
    override fun sandboxStatus(): xyz.mederi.core.contract.SandboxStatusInfo {
        val status = xyz.mederi.tools.sandbox.CommandSandbox(emptyList()).status()
        return xyz.mederi.core.contract.SandboxStatusInfo(status.backend, status.available, status.shell, status.detail)
    }

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
            mederi = Mederi.create {
                configDir = this@MederiAiCore.configDir
                // 出站 HTTP User-Agent 唯一注入点：所有 Koog 链路请求带上 Mederi 身份头
                userAgent = AppInfo.userAgent
                // 浏览器自动化：注册 Camoufox（core 默认）。UI 层（desktop）再注册内置 JCEF。
                // AI 通过 run_browser_task(browser=...) 选择：测自己网页→jcef，第三方自动化→camoufox。
                xyz.mederi.browser.BrowserRegistry.register(
                    name = "camoufox",
                    kind = xyz.mederi.browser.BrowserKind.CAMOUFOX,
                    factory = {
                        val browserHome = xyz.mederi.browser.install.BrowserHome.of(
                            xyz.mederi.browser.BrowserRuntime.browserHome
                        )
                        val binary = browserHome
                            ?.let { home -> xyz.mederi.browser.install.CamoufoxInstaller(home).installedBinaryPath() }
                            ?: xyz.mederi.browser.BrowserRuntime.camoufoxPath
                            ?: error(
                                "未找到 Camoufox：请先在设置里配置浏览器工作目录并下载 Camoufox，" +
                                    "或手动指定 camoufoxPath"
                            )
                        val profileDir = browserHome
                            ?.profilesDir?.let { it.mkdirs(); it.toPath() }
                            ?: java.nio.file.Files.createTempDirectory("mederi-camoufox-profile-")
                        xyz.mederi.browser.BiDiBrowserControl(
                            binaryPath = binary,
                            profilePath = profileDir
                        )
                    }
                )
            }
            cleanupLegacyBuiltinProviders()
            cleanupStaleRunningSessions()
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
            startCamoufoxUpdateCheck()
        }
    }

    /**
     * 启动时静默检查 Camoufox 是否有更新（不自动下载——浏览器体积大，只在有 browserHome
     * 配置时才查，结果打日志；后续 UI 可订阅/展示）。
     */
    private fun startCamoufoxUpdateCheck() {
        val home = xyz.mederi.browser.install.BrowserHome.of(xyz.mederi.browser.BrowserRuntime.browserHome)
            ?: return  // 未设置浏览器工作目录，跳过
        if (!xyz.mederi.browser.install.CamoufoxPlatform.supported) {
            xyz.mederi.debug.DebugLog.info(
                "Camoufox",
                xyz.mederi.browser.install.CamoufoxPlatform.unsupportedReason
            )
            return
        }
        scope.launch {
            try {
                val installer = xyz.mederi.browser.install.CamoufoxInstaller(home)
                val info = installer.checkForUpdate()
                xyz.mederi.debug.DebugLog.info("Camoufox", "启动更新检查: ${info.reason}")
            } catch (e: Throwable) {
                xyz.mederi.debug.DebugLog.error("Camoufox", "启动更新检查失败: ${e.message}")
            }
        }
    }

    /**
     * 监听 core 事件流，实时将会话状态同步至 _projects StateFlow（驱动侧边栏指示灯）。
     */
    private fun startSessionStatusSync() {
        scope.launch {
            mederi.sessions.events().collect { event ->
                val newStatus = when (event.type) {
                    xyz.mederi.domain.model.EventType.SESSION_UPDATED -> ConversationStatus.Working
                    xyz.mederi.domain.model.EventType.MESSAGE_COMPLETED -> ConversationStatus.Idle
                    xyz.mederi.domain.model.EventType.MESSAGE_ERROR -> {
                        // TurnExecutor 在发 MESSAGE_ERROR 前已更新 sessionStore（ERROR 或 IDLE），
                        // sessionStore 的状态是权威终态：ERROR→红点，IDLE（transient 可恢复，如限流重试耗尽）→蓝点。
                        // 会话被删等异常取不到时保守回退 Error（保持可见，不静默）。
                        val ss = runCatching { mederi.sessions.get(event.sessionId).status }.getOrNull()
                            ?: xyz.mederi.domain.model.SessionStatus.ERROR
                        mapMessageErrorToStatus(ss)
                    }
                    xyz.mederi.domain.model.EventType.QUESTION_REQUESTED -> ConversationStatus.WaitingUser
                    xyz.mederi.domain.model.EventType.QUESTION_RESOLVED -> ConversationStatus.Working
                    xyz.mederi.domain.model.EventType.PLAN_APPROVAL_REQUESTED -> ConversationStatus.WaitingUser
                    xyz.mederi.domain.model.EventType.PLAN_APPROVAL_RESOLVED -> ConversationStatus.Working
                    else -> null
                }
                if (newStatus != null) {
                    updateConversationStatus(event.sessionId, newStatus)
                }
                // 终态错误内存注册表：MESSAGE_ERROR 写入，MESSAGE_COMPLETED 清除（跨 re-attach 水合用）
                when (event.type) {
                    xyz.mederi.domain.model.EventType.MESSAGE_ERROR ->
                        lastErrorBySessionId[event.sessionId] = lastErrorFromPayload(event.payload)
                    xyz.mederi.domain.model.EventType.MESSAGE_COMPLETED ->
                        lastErrorBySessionId.remove(event.sessionId)
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
            project?.directory?.let { dirPath ->
                val mermaidDir = File(dirPath, ".mederi/mermaid")
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

    override suspend fun createConversation(projectId: String, agent: AgentOption?): Result<Conversation> = runCatching {
        val agentMode = agent?.let {
            runCatching { AgentMode.valueOf(it.mode.name) }.getOrNull()
        } ?: AgentMode.AUTONOMOUS
        val session = mederi.sessions.create(
            CreateSessionRequest(
                agentConfig = AgentConfig(
                    agentMode = agentMode
                ),
                projectId = projectId,
                title = ""
            )
        )
        refreshProjects()
        MederiModelMapper.toConversation(session, providerId = modelToProvider[session.aiModel?.id])
    }

    override suspend fun deleteConversation(conversationId: String): Result<Unit> = runCatching {
        // 1. 删除磁盘上的 Mermaid 缓存图片
        runCatching {
            val session = mederi.sessions.get(conversationId)
            val project = mederi.projects.get(session.projectId)
            project.directory.let { dirPath ->
                val mermaidDir = File(dirPath, ".mederi/mermaid")
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

    override fun observeConversation(conversationId: String): Flow<ConversationSnapshot> {
        return MederiEventAggregator.observe(
            conversationId = conversationId,
            sessions = mederi.sessions,
            modelToProvider = { modelToProvider[it] },
            planTodos = { id -> initialTodos(id) },
            lastError = { lastErrorBySessionId[it] }
        )
    }

    /**
     * 初始快照的 todo hydration（与事件流/提示词挂载同源同一投影函数，不产生第二份逻辑）：
     * 有活跃 Plan → Plan 子任务投影（todo 面板显示"该做哪个/正在做哪个/做完哪个"）；
     * 无 Plan → sessions.todos 列的模型 todo。优先级与提示词挂载规则同构（Plan 优先）。
     */
    private suspend fun initialTodos(conversationId: String): List<xyz.mederi.core.contract.models.TodoItem> {
        val session = mederi.sessions.get(conversationId)
        val planTodos = runCatching {
            val project = mederi.projects.get(session.projectId)
            xyz.mederi.plan.PlanStore(listOf(project.directory)).loadBySession(conversationId)
                ?.let { MederiModelMapper.toTodos(it.toTodoProjection()) }
        }.getOrNull().orEmpty()
        return planTodos.ifEmpty { MederiModelMapper.toTodos(session.todos) }
    }

    /**
     * 构建会话当前完整快照（不订阅事件流）。
     * server 模式下由 GET /v1/sessions/{id}/snapshot 暴露给 wasmJs 客户端作初始状态。
     */
    override suspend fun getSnapshot(conversationId: String): Result<ConversationSnapshot> = runCatching {
        val session = mederi.sessions.get(conversationId)
        val messages = mederi.sessions.listMessages(conversationId)
        val toolResults = MederiModelMapper.buildToolResultsById(messages)
        val base = ConversationSnapshot(
            conversation = MederiModelMapper.toConversation(session, session.aiModel?.id?.let { modelToProvider[it] }),
            messages = messages.map { MederiModelMapper.toChatMessage(it, toolResults) },
            tokenUsage = MederiModelMapper.toTokenUsage(messages),
            contextUsedTokens = MederiModelMapper.toContextUsedTokens(messages),
            cost = MederiModelMapper.toCostSummary(),
            todos = initialTodos(conversationId)
        )
        hydrateLastError(base, lastErrorBySessionId[conversationId])
    }

    // ------------------------------------------------------------------
    // Provider / Model
    // ------------------------------------------------------------------

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

    override suspend fun deleteProvider(providerId: String): Result<Unit> = runCatching {
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
        isEnabled: Boolean?
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
                isEnabled = isEnabled
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
            apiKeyId = input.apiKeyId
        )
        // 记录该供应商最近一次选定的 key（供自动命名等非 turn 操作跟随；仅内存，不落库）
        if (input.apiKeyId != null) {
            input.model?.let { lastApiKeyIdByProvider[it.provider] = input.apiKeyId }
        }
        // 新 turn 开始：清除陈旧错误，避免 re-attach 水合到上一轮的错误
        lastErrorBySessionId.remove(conversationId)
        mederi.sessions.sendMessage(conversationId, request)
    }

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

    override suspend fun resolvePlanApproval(
        conversationId: String,
        planId: String,
        approved: Boolean,
        model: ModelOption?,
        thinkingLevel: String?
    ): Result<Unit> = runCatching {
        // 契约 ModelOption → core AIModel（与 sendMessage 同转换：按 id 从供应商解析），
        // thinkingLevel → ReasoningLevel（复用 mapper；null = 不带，core 保留 session 原值）
        val coreModel = model?.let { findCoreModel(it.id) }
        mederi.sessions.resolvePlanApproval(
            conversationId, planId, approved,
            aiModel = coreModel,
            reasoningLevel = MederiInputMapper.toReasoningLevel(thinkingLevel)
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
        MessagesPage(
            messages = messages.map { MederiModelMapper.toChatMessage(it, toolResults) },
            tokenUsage = MederiModelMapper.toTokenUsage(messages),
            contextUsedTokens = MederiModelMapper.toContextUsedTokens(messages)
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
        mederi.sessions.listRawMessages(conversationId).map {
            RawMessageDto(
                seq = it.seq,
                messageId = it.messageId,
                role = it.role,
                payload = it.payload,
                createdAt = it.createdAt
            )
        }
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
                MederiModelMapper.toConversation(session, modelToProvider[session.aiModel?.id])
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

        _providers.value = coreProviders.map { MederiModelMapper.toProviderConfig(it) }
        // 只暴露已配置 API Key 的供应商模型，未填 Key 的模型不出现在可选列表；
        // 用户关闭（isEnabled=false）的模型也不出现
        _availableModels.value = coreProviders
            .filter { it.defaultApiKey != null }
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
            xyz.mederi.core.ui.DebugLog.info("MCP", "listMcpServers: mederi not initialized yet, waiting for isReady...")
            _isReady.first { it }
        }
        val rawList = mederi.mcpServers.list()
        xyz.mederi.core.ui.DebugLog.info("MCP", "listMcpServers: fetched ${rawList.size} servers from mederi.mcpServers")
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
}
