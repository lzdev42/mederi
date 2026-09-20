package xyz.mederi.core.ui.appstate

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import xyz.mederi.core.bridge.BuiltinAgents
import xyz.mederi.core.contract.AiCore
import xyz.mederi.core.contract.SandboxHooks
import xyz.mederi.core.contract.TerminalManager
import xyz.mederi.core.contract.models.*
import xyz.mederi.core.contract.preferences.PreferencesStore
import xyz.mederi.core.ui.DebugLog
import xyz.mederi.theme.AppLanguage
import xyz.mederi.theme.AppThemeMode

/** 内嵌遥控 server 启动结果。 */
sealed interface RemoteStartResult {
    /** 启动成功；[port] 为实际监听端口；[portFallback] 为 true 表示请求端口被占用、已由 OS 另配端口 */
    data class Started(val port: Int, val portFallback: Boolean = false) : RemoteStartResult
    /** 启动失败（含换端口重试仍失败） */
    data class Failed(val reason: String) : RemoteStartResult
}

/** 遥控 server UI 状态（设置页遥控卡片据此展示）。 */
sealed interface RemoteServerUiState {
    /** 关闭或未启动 */
    data object Idle : RemoteServerUiState
    /** 正在启动中 */
    data object Starting : RemoteServerUiState
    /** 运行中；[portFallback] 为 true 表示"上次用的端口被占用，已自动改用该端口" */
    data class Running(val port: Int, val portFallback: Boolean = false) : RemoteServerUiState
    /** 启动失败 */
    data class Failed(val reason: String) : RemoteServerUiState
}

/** Cloudflare 隧道启动结果。 */
sealed interface TunnelStartResult {
    /** 隧道已启动；[url] 为隧道公网地址（从 cloudflared config.yml 解析，解析不到为 null） */
    data class Started(val url: String?) : TunnelStartResult
    /** 本机未安装 cloudflared */
    data object NotInstalled : TunnelStartResult
    /** 启动失败 */
    data class Failed(val reason: String) : TunnelStartResult
}

/** Cloudflare 隧道 UI 状态。 */
sealed interface TunnelUiState {
    /** 未启动 */
    data object Idle : TunnelUiState
    /** 启动中 */
    data object Starting : TunnelUiState
    /** 运行中；[url] 为隧道公网地址（可为 null，解析不到时由 UI 提示看 cloudflared 日志） */
    data class Running(val url: String?) : TunnelUiState
    /** 失败；[notInstalled] 为 true 表示本机未装 cloudflared */
    data class Failed(val reason: String, val notInstalled: Boolean = false) : TunnelUiState
}

/**
 * 远程遥控开关钩子：由宿主注入实现（desktop = 内嵌 server 的启停 + cloudflared 隧道；其他端 null）。
 * commonMain 设置页只依赖此接口，不感知 server/ktor/pty4j。
 */
interface RemoteControlHooks {
    suspend fun start(port: Int, password: String?): RemoteStartResult
    fun stop()
    val isRunning: Boolean

    /** 本机局域网地址（site-local IPv4）；拿不到返回 null */
    val localAddress: String?

    /** 探测本机是否已安装 cloudflared */
    fun isCloudflaredInstalled(): Boolean

    /** 启动 Cloudflare 隧道（pty 挂 cloudflared，随本进程生命周期）。[port] 为内嵌 server 当前监听端口 */
    fun startTunnel(port: Int): TunnelStartResult

    /** 停止隧道 */
    fun stopTunnel()
}

class AppState(
    val aiCore: AiCore,
    private val preferences: PreferencesStore,
    private val scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** 宿主启动后注入；null = 当前端不支持遥控 */
    var remoteControl: RemoteControlHooks? = null

    /** 宿主启动后注入；null = 当前端无本地终端（wasm/移动端为遥控端，后续接远程 WS 客户端） */
    var terminalManager: TerminalManager? = null

    /** 宿主启动后注入；null = 当前端不渲染内置浏览器（遥控/wasm 端为 null，见 canRenderJcef） */
    var uiBrowserHost: xyz.mederi.core.ui.browser.UiBrowserHost? = null

    /** 是否可渲染内置 JCEF 浏览器（仅 desktop + 已注入宿主时为 true；遥控端/wasm 恒 false） */
    val canRenderJcef: Boolean get() = uiBrowserHost != null

    /** Skill 状态唯一真理源：概览面板、Skill 市场等全局共享 */
    val skillStore: SkillStore = SkillStore(aiCore, scope)

    /** MCP 服务状态唯一真理源：概览面板、MCP 市场等全局共享 */
    val mcpStore: McpStore = McpStore(aiCore, scope)



    // ───── A. 引擎侧 alias(直接 forward;零缓存) ─────
    val isReady: StateFlow<Boolean> get() = aiCore.isReady
    val projects: StateFlow<List<Project>> get() = aiCore.projects
    val providers: StateFlow<List<ProviderConfig>> get() = aiCore.providers
    val availableModels: StateFlow<List<ModelOption>> get() = aiCore.availableModels
    val availableAgents: StateFlow<List<AgentOption>> get() = aiCore.availableAgents

    // ───── B. UI 全局偏好(MutableStateFlow 内部;StateFlow 对外) ─────
    private val _theme = MutableStateFlow(AppThemeMode.DARK)
    val theme: StateFlow<AppThemeMode> = _theme.asStateFlow()

    /** 界面语言（唯一真理源）。App.kt 观察并喂给 LocalAppLocale / AppEnvironment 生效。 */
    private val _language = MutableStateFlow(AppLanguage.SYSTEM)
    val language: StateFlow<AppLanguage> = _language.asStateFlow()

    /** 左侧边栏是否常驻固定（false = 自动隐藏，鼠标悬浮/把手呼出；true = 常驻分栏） */
    private val _leftSidebarPinned = MutableStateFlow(false)
    val leftSidebarPinned: StateFlow<Boolean> = _leftSidebarPinned.asStateFlow()

    /** 远程遥控开关状态（desktop 内嵌 server）；启停执行由 [remoteControl] hooks 完成 */
    private val _remoteControlEnabled = MutableStateFlow(false)
    val remoteControlEnabled: StateFlow<Boolean> = _remoteControlEnabled.asStateFlow()

    /** 遥控鉴权密码（空串/null = 无鉴权） */
    private val _remoteControlPassword = MutableStateFlow<String?>(null)
    val remoteControlPassword: StateFlow<String?> = _remoteControlPassword.asStateFlow()

    /** 遥控监听端口（上次成功启动后的实际端口，启动时优先用它，被占用则自动换端口） */
    private val _remotePort = MutableStateFlow(8081)
    val remotePort: StateFlow<Int> = _remotePort.asStateFlow()

    /** 遥控 server 运行状态（含端口回退提示），UI 据此展示 */
    private val _remoteServerState = MutableStateFlow<RemoteServerUiState>(RemoteServerUiState.Idle)
    val remoteServerState: StateFlow<RemoteServerUiState> = _remoteServerState.asStateFlow()

    /** Cloudflare 隧道状态（未启动 / 启动中 / 运行中(地址) / 失败） */
    private val _tunnelState = MutableStateFlow<TunnelUiState>(TunnelUiState.Idle)
    val tunnelState: StateFlow<TunnelUiState> = _tunnelState.asStateFlow()

    private val _selectedProjectId = MutableStateFlow<String?>(null)
    val selectedProjectId: StateFlow<String?> = _selectedProjectId.asStateFlow()

    private val _selectedConversationId = MutableStateFlow<String?>(null)
    val selectedConversationId: StateFlow<String?> = _selectedConversationId.asStateFlow()

    private val _selectedModel = MutableStateFlow<ModelOption?>(null)
    val selectedModel: StateFlow<ModelOption?> = _selectedModel.asStateFlow()

    private val _selectedAgentId = MutableStateFlow<String?>(null)
    val selectedAgentId: StateFlow<String?> = _selectedAgentId.asStateFlow()

    /** 沙盒全局白名单（项目外额外可写路径）；写穿到 SandboxConfig，实时生效 */
    private val _sandboxExtraPaths = MutableStateFlow<List<String>>(emptyList())
    val sandboxExtraPaths: StateFlow<List<String>> = _sandboxExtraPaths.asStateFlow()

    private val _modelReasoningLevels = MutableStateFlow<Map<String, String>>(emptyMap())
    val modelReasoningLevels: StateFlow<Map<String, String>> = _modelReasoningLevels.asStateFlow()

    /** 供应商 → 该供应商选定的 API Key ID（providerId -> apiKeyId）。按供应商记忆，跨重启恢复；缺省 = 用默认 key。 */
    private val _selectedApiKeyIds = MutableStateFlow<Map<String, String>>(emptyMap())
    val selectedApiKeyIds: StateFlow<Map<String, String>> = _selectedApiKeyIds.asStateFlow()

    /** 当前选中的执行策略（AUTONOMOUS/APPROVAL），由选中 Agent 派生；无选择时回退 AUTONOMOUS */
    val selectedAgentMode: StateFlow<AgentMode> =
        combine(_selectedAgentId, availableAgents) { agentId, agents ->
            val list = agents.ifEmpty { BuiltinAgents.ALL }
            list.find { it.id == agentId }?.mode ?: AgentMode.AUTONOMOUS
        }.stateIn(scope, SharingStarted.Eagerly, AgentMode.AUTONOMOUS)

    /** 进程真实资源占用（CPU、内存堆与 RSS），由后台协程以 1s 周期轮询 [AiCore.getProcessStats] */
    private val _processStats = MutableStateFlow<ProcessStats?>(null)
    val processStats: StateFlow<ProcessStats?> = _processStats.asStateFlow()

    init {
        // 选中模型自愈：模型列表重建（手动刷新/元数据回填/增删模型）后，把选中项重指向
        // 新列表里的同款实例。否则 selectedModel 一直拿着旧实例，概览卡的已用百分比、
        // 参考成本等永远停留在刷新前的旧元数据，直到重启。
        // 按结构相等去重：元数据没变时 StateFlow 不重复发射；按 id+provider 定位，不碰偏好持久化。
        scope.launch {
            availableModels.collect { models ->
                val current = _selectedModel.value ?: return@collect
                val fresh = models.find { it.id == current.id && it.provider == current.provider }
                if (fresh != null) _selectedModel.value = fresh
            }
        }

        // 轮询进程资源占用（1秒一次，失败静默跳过）
        scope.launch {
            while (isActive) {
                aiCore.getProcessStats().getOrNull()?.let { stats ->
                    _processStats.value = stats
                }
                delay(1000)
            }
        }

        // 按供应商恢复上次选定的 API Key（persist 的 workspace.apiKey.$providerId）；缺省无持久化值 = 用默认 key。
        scope.launch {
            aiCore.providers.collect { providers ->
                providers.forEach { p ->
                    if (_selectedApiKeyIds.value[p.id] == null) {
                        preferences.getString("workspace.apiKey.${p.id}")?.let { key ->
                            _selectedApiKeyIds.update { it + (p.id to key) }
                        }
                    }
                }
            }
        }
    }

    // ───── C. 写侧 action(同步更新内部值 + 异步持久化) ─────

    /** 尚未落盘的持久化写数量（含排队中），退出前用 [flushPreferences] 等待归零。 */
    private val pendingWriteCount = MutableStateFlow(0)

    /** 写侧 action 共用：值非空 -> putString，为空 -> remove（异步持久化，不阻塞 UI）。 */
    private fun <T> persist(key: String, value: T?, encode: (T) -> String) {
        pendingWriteCount.update { it + 1 }
        scope.launch {
            try {
                if (value != null) preferences.putString(key, encode(value))
                else preferences.remove(key)
            } finally {
                pendingWriteCount.update { (it - 1).coerceAtLeast(0) }
            }
        }
    }

    /**
     * 同步落盘：应用退出前调用，等待所有已启动的持久化写完成（带 2s 兜底超时）。
     * 避免用户在最后一次选择后立刻关闭窗口导致偏好丢失。
     */
    suspend fun flushPreferences() {
        withTimeoutOrNull(2_000) { pendingWriteCount.first { it == 0 } }
    }

    /**
     * 会话快照补位：只改内存态，不写偏好文件。
     * 仅当全局尚无选择时用会话固有配置（model/agent）补位，
     * 避免启动恢复或切换会话时用会话旧快照覆盖用户主动偏好。
     */
    fun applyConversationDefaults(model: ModelOption?, agent: AgentOption?) {
        if (_selectedModel.value == null && model != null) _selectedModel.value = model
        if (_selectedAgentId.value == null && agent != null) _selectedAgentId.value = agent.id
    }

    fun setTheme(value: AppThemeMode) {
        _theme.value = value
        persist("app.theme", value) { it.name }
    }

    /** 切换界面语言（设置页-通用调用）。写状态 + 持久化；生效链路在 App.kt → AppEnvironment。 */
    fun setLanguage(value: AppLanguage) {
        _language.value = value
        persist("app.language", value) { it.name }
    }

    /** 切换左侧边栏常驻固定状态（写状态 + 持久化） */
    fun setLeftSidebarPinned(value: Boolean) {
        _leftSidebarPinned.value = value
        persist("ui.sidebar.pinned", value) { it.toString() }
    }

    /**
     * 远程遥控开关（设置页调用）。只写状态 + 持久化；
     * server 启停由 [startRemoteControl]/[stopRemoteControl] 执行（异步拿结果）。
     */
    fun setRemoteControl(enabled: Boolean, password: String?) {
        _remoteControlEnabled.value = enabled
        _remoteControlPassword.value = password?.takeIf { it.isNotBlank() }
        persist("remote.enabled", enabled) { it.toString() }
        persist("remote.password", password?.takeIf { it.isNotBlank() }) { it }
        if (enabled) startRemoteControl() else stopRemoteControl()
    }

    /** 启动遥控 server：用已保存端口起，占用则自动换端口；结果回写 [remoteServerState] 并保存实际端口。 */
    fun startRemoteControl() {
        val hooks = remoteControl ?: run { _remoteServerState.value = RemoteServerUiState.Failed("当前端不支持遥控") ; return }
        val current = _remoteServerState.value
        if (current is RemoteServerUiState.Starting || current is RemoteServerUiState.Running) return
        _remoteServerState.value = RemoteServerUiState.Starting
        scope.launch {
            val result = hooks.start(_remotePort.value, _remoteControlPassword.value)
            _remoteServerState.value = when (result) {
                is RemoteStartResult.Started -> {
                    if (result.port != _remotePort.value) {
                        _remotePort.value = result.port
                        persist("remote.port", result.port) { it.toString() }
                    }
                    RemoteServerUiState.Running(result.port, result.portFallback)
                }
                is RemoteStartResult.Failed -> {
                    hooks.stop()
                    RemoteServerUiState.Failed(result.reason)
                }
            }
        }
    }

    /** 停止遥控 server。 */
    fun stopRemoteControl() {
        remoteControl?.stop()
        _remoteServerState.value = RemoteServerUiState.Idle
    }

    /** 启动 Cloudflare 隧道（仅当遥控 server 运行时可启动）；结果回写 [tunnelState]。 */
    fun startTunnel() {
        val hooks = remoteControl ?: return
        if (remoteServerState.value !is RemoteServerUiState.Running) return
        _tunnelState.value = TunnelUiState.Starting
        val result = hooks.startTunnel(_remotePort.value)
        _tunnelState.value = when (result) {
            is TunnelStartResult.Started -> TunnelUiState.Running(result.url)
            is TunnelStartResult.NotInstalled -> TunnelUiState.Failed("请自行安装 cloudflared", notInstalled = true)
            is TunnelStartResult.Failed -> TunnelUiState.Failed(result.reason)
        }
    }

    /** 停止隧道。 */
    fun stopTunnel() {
        remoteControl?.stopTunnel()
        _tunnelState.value = TunnelUiState.Idle
    }

    fun selectProject(id: String?) {
        _selectedProjectId.value = id
        persist("workspace.lastProjectId", id) { it }
    }

    fun selectConversation(id: String?) {
        _selectedConversationId.value = id
        persist("workspace.lastConversationId", id) { it }
    }

    /**
     * 级联清理:外部(AiCore)删除一个项目成功后调用一次。
     * 这是**唯一**允许偏好与引擎状态对齐的地方,ViewModel **不要**各自手写清理。
     * 内部动作:
     *   - 若 selectedProjectId == projectId → 自动切到 projects.value.firstOrNull()?.id,null 时清空
     *   - 若 selectedConversationId 属于被删项目 → 清空(因为 conversation 已不存在)
     *   - 清理 workspace.lastProjectId / workspace.lastConversationId(若它们指向被删)
     */
    fun handleProjectDeleted(projectId: String) {
        val deletedProjectConversations = projects.value
            .find { it.id == projectId }
            ?.conversations
            ?.map { it.id }
            .orEmpty()
        if (_selectedConversationId.value in deletedProjectConversations || _selectedProjectId.value == projectId) {
            selectConversation(null)
        }
        if (_selectedProjectId.value == projectId) {
            val next = projects.value.firstOrNull { it.id != projectId }?.id
            selectProject(next)
        }
        scope.launch {
            pendingWriteCount.update { it + 1 }
            try {
                if (preferences.getString("workspace.lastProjectId") == projectId) {
                    preferences.remove("workspace.lastProjectId")
                    preferences.remove("workspace.lastConversationId")
                }
            } finally {
                pendingWriteCount.update { (it - 1).coerceAtLeast(0) }
            }
        }
    }

    /**
     * 会话删除后的状态对齐：被删会话如果处于当前选中状态，则清空选中；同时清理持久化偏好。
     */
    fun handleConversationDeleted(conversationId: String) {
        if (_selectedConversationId.value == conversationId) {
            selectConversation(null)
        }
        scope.launch {
            pendingWriteCount.update { it + 1 }
            try {
                if (preferences.getString("workspace.lastConversationId") == conversationId) {
                    preferences.remove("workspace.lastConversationId")
                }
            } finally {
                pendingWriteCount.update { (it - 1).coerceAtLeast(0) }
            }
        }
    }

    fun selectModel(m: ModelOption?) {
        _selectedModel.value = m
        persist("workspace.lastModelId", m) { it.id }
        persist("workspace.lastModelProviderId", m) { it.provider }
    }

    fun getModelReasoningLevel(modelId: String): String? = _modelReasoningLevels.value[modelId]

    fun setModelReasoningLevel(modelId: String, level: String?) {
        _modelReasoningLevels.update { current ->
            if (level != null) current + (modelId to level) else current - modelId
        }
        persist("workspace.reasoningLevel.$modelId", level) { it }
    }

    /** 该供应商选定的 API Key ID；null = 用默认 key。 */
    fun getApiKeyId(providerId: String): String? = _selectedApiKeyIds.value[providerId]

    /** 选定该供应商的 API Key（唯一入口）；apiKeyId 为 null 表示用默认 key。按供应商记忆，跨重启恢复。 */
    fun selectApiKey(providerId: String, apiKeyId: String?) {
        _selectedApiKeyIds.update { current ->
            if (apiKeyId != null) current + (providerId to apiKeyId) else current - providerId
        }
        persist("workspace.apiKey.$providerId", apiKeyId) { it }
    }

    fun selectAgent(id: String?) {
        DebugLog.info("AppState", "selectAgent: requestedId=$id, oldAgentId=${_selectedAgentId.value}")
        _selectedAgentId.value = id
        persist("workspace.lastAgentId", id) { it }
    }

    /**
     * 按执行策略（AUTONOMOUS / APPROVAL）切换 Agent。
     */
    fun selectAgentMode(mode: AgentMode) {
        val agents = availableAgents.value.ifEmpty { BuiltinAgents.ALL }
        val currentAgent = agents.find { it.id == _selectedAgentId.value }
        val targetAgent = agents.find { it.mode == mode }
            ?: BuiltinAgents.ALL.find { it.mode == mode }
        DebugLog.info("AppState", "selectAgentMode: requested=$mode, currentAgentId=${currentAgent?.id}, targetAgentId=${targetAgent?.id}")
        selectAgent(targetAgent?.id)
    }

    /**
     * 增删沙盒全局白名单路径（项目外额外可写）。
     * 同步写偏好（"sandbox.extraPaths" JSON 数组）并写穿 mederi.sandboxExtraPaths 实时生效。
     */
    fun setSandboxExtraPaths(paths: List<String>) {
        _sandboxExtraPaths.value = paths
        persist("sandbox.extraPaths", paths) { json.encodeToString(it) }
        writeThroughSandboxExtraPaths(paths)
    }

    /**
     * 把白名单写进进程级 SandboxConfig（下个 turn 的命令沙箱即生效，无需重启）。
     * AiCore 契约不含沙盒配置（本机进程偏好，同 remote.port）——
     * 仅实现 SandboxHooks 的端（嵌入式 core）支持写穿；ServerAiCore/Mock 等 no-op。
     */
    private fun writeThroughSandboxExtraPaths(paths: List<String>) {
        (aiCore as? SandboxHooks)?.setSandboxExtraPaths(paths)
    }

    // ───── D. 启动 hydration ─────

    suspend fun hydrate() {
        _theme.value = AppThemeMode.fromString(preferences.getString("app.theme"))
        _language.value = AppLanguage.fromString(preferences.getString("app.language"))
        _leftSidebarPinned.value = preferences.getBoolean("ui.sidebar.pinned", false)

        _remoteControlEnabled.value = preferences.getBoolean("remote.enabled")
        _remoteControlPassword.value = preferences.getString("remote.password")
        _remotePort.value = preferences.getString("remote.port")?.toIntOrNull() ?: 8081

        preferences.getString("sandbox.extraPaths")?.let { jsonStr ->
            runCatching { json.decodeFromString<List<String>>(jsonStr) }.getOrNull()
        }?.let { paths ->
            _sandboxExtraPaths.value = paths
            writeThroughSandboxExtraPaths(paths)
        }

        _selectedProjectId.value = preferences.getString("workspace.lastProjectId")
        _selectedConversationId.value = preferences.getString("workspace.lastConversationId")
        _selectedAgentId.value = preferences.getString("workspace.lastAgentId")

        scope.launch {
            val lastModelId = preferences.getString("workspace.lastModelId") ?: return@launch
            val lastProviderId = preferences.getString("workspace.lastModelProviderId")
            withTimeoutOrNull(3_000) {
                availableModels.first { it.isNotEmpty() }
            }?.let { list ->
                _selectedModel.value = list.find { it.id == lastModelId && (lastProviderId == null || it.provider == lastProviderId) }
                    ?: list.firstOrNull()

                // 恢复各支持思考模型的推理等级记忆
                list.filter { it.supportsThinking }.forEach { model ->
                    val savedLevel = preferences.getString("workspace.reasoningLevel.${model.id}")
                    if (savedLevel != null) {
                        _modelReasoningLevels.update { it + (model.id to savedLevel) }
                    }
                }
            }
        }

        scope.launch {
            if (_selectedAgentId.value == null) {
                withTimeoutOrNull(3_000) {
                    availableAgents.first { it.isNotEmpty() }
                }?.let { agents ->
                    _selectedAgentId.value = agents.firstOrNull()?.id
                }
            }
        }
    }
}

val LocalAppState = staticCompositionLocalOf<AppState> {
    error("AppState not provided. Wrap your App root with CompositionLocalProvider(LocalAppState provides ...)")
}
