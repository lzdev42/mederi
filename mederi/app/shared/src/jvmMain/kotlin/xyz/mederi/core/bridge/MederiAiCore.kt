package xyz.mederi.core.bridge

import java.io.File
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import xyz.mederi.Mederi
import xyz.mederi.api.AgentConfig
import xyz.mederi.api.CreateSessionRequest
import xyz.mederi.api.RenameSessionRequest
import xyz.mederi.api.SendMessageRequest
import xyz.mederi.core.autotitle.SessionTitleService
import xyz.mederi.core.contract.AiCore
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
import xyz.mederi.core.contract.models.CoreEvent
import xyz.mederi.core.contract.models.CostSummary
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

    /** 会话自动命名服务（首条消息发出后生成标题），随 initialize 挂载事件流 */
    private val autotitleService = SessionTitleService(this)

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
            mederi = Mederi.local(configDir)
            cleanupLegacyBuiltinProviders()
            cleanupStaleRunningSessions()
            syncBuiltinProviders()
            refreshGlobalState()
            _isReady.value = true
            // models.dev 元数据目录：启动立即拉取 + 每小时静默刷新（纯内存，不阻塞启动）
            mederi.modelCatalog.start()
            // 后台刷新内置 Google 供应商的模型列表（不阻塞启动）
            autoRefreshBuiltinGoogleModels()
            // 存量模型元数据一次性回填（迁移，非定时刷新）
            backfillModelMetadata()
            // 会话自动命名：挂 core 事件流，首条消息发出后生成标题。
            // 挂在 initialize 而非各宿主 main——desktop / server（headless 遥控端）
            // 共用同一桥实现，谁初始化谁生效，宿主无需各自接线。
            autotitleService.start()
        }
    }

    /**
     * 存量模型元数据一次性回填。
     *
     * 元数据目录功能上线之前同步过模型的供应商，其模型字段（contextWindow/价格/图片等）
     * 全是空——同步触发点只覆盖"之后"的添加/手动刷新，老数据永远等不到补全。
     * 这里在目录首次加载成功后补一次：**只填空字段，已有值一律不动**（迁移语义，不是刷新）。
     * 目录拉取失败（离线）静默跳过，下次启动再试；没有定时重试。
     */
    private fun backfillModelMetadata() {
        scope.launch {
            // 等目录首次加载（start() 的立即刷新），最多 30s；离线则放弃，下次启动再补
            val loaded = withTimeoutOrNull(30_000) {
                mederi.modelCatalog.version.first { it > 0 }
            } ?: return@launch
            try {
                var touchedModels = 0
                for (provider in mederi.providers.list()) {
                    if (provider.models.isEmpty()) continue
                    for (model in provider.models) {
                        val meta = mederi.modelCatalog.getFor(
                            provider.modelsDevKey,
                            provider.baseUrl,
                            model.providerModelId
                        ) ?: continue

                        // 逐字段填空：null 表示"本地已有/目录没有，不动"
                        val contextWindow = model.contextWindow ?: meta.contextWindow
                        val maxTokens = model.maxTokens ?: meta.maxOutputTokens
                        val inputPrice = model.inputPricePerMillion ?: meta.inputPricePerMillion
                        val outputPrice = model.outputPricePerMillion ?: meta.outputPricePerMillion
                        val supportsImages = if (!model.supportsImages) meta.supportsImages else null
                        val supportsReasoning = if (!model.supportsReasoning) meta.supportsReasoning else null
                        val reasoningLevels = meta.reasoningLevels.takeIf { it.isNotEmpty() && model.reasoningLevels.isEmpty() }

                        val hasFill = contextWindow != model.contextWindow ||
                            maxTokens != model.maxTokens ||
                            inputPrice != model.inputPricePerMillion ||
                            outputPrice != model.outputPricePerMillion ||
                            supportsImages != null ||
                            supportsReasoning != null ||
                            reasoningLevels != null
                        if (!hasFill) continue

                        mederi.providerManager.updateModel(
                            providerId = provider.id,
                            modelId = model.id,
                            name = null,
                            supportsReasoning = supportsReasoning,
                            reasoningLevel = null,
                            contextWindow = contextWindow,
                            maxTokens = maxTokens,
                            supportsImages = supportsImages,
                            reasoningLevels = reasoningLevels,
                            isEnabled = null,
                            inputPricePerMillion = inputPrice,
                            outputPricePerMillion = outputPrice
                        )
                        touchedModels++
                    }
                }
                if (touchedModels > 0) {
                    DebugLog.event("AiCore", "存量模型元数据回填: $touchedModels 个模型已补全")
                    refreshProvidersAndAgents()
                }
            } catch (e: Exception) {
                DebugLog.error("AiCore", "存量模型元数据回填失败: ${e.message}", e)
            }
        }
    }

    /**
     * 后台刷新内置 Google 供应商的模型列表。
     *
     * Google 不内置模型（原生 models.list 可拉取），添加后每次启动自动同步远端
     * （displayName / 上下文窗口 / 是否支持 thinking），失败静默记录，不打扰启动流程。
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
     */
    private suspend fun cleanupStaleRunningSessions() {
        val runningSessions = mederi.sessions.list().filter { it.status == xyz.mederi.domain.model.SessionStatus.RUNNING }
        for (session in runningSessions) {
            mederi.sessions.abort(session.id)
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
        mederi.projects.delete(projectId)
        refreshProjects()
    }

    override suspend fun addProjectDirectory(projectId: String, directory: String): Result<Unit> = runCatching {
        mederi.projects.addDirectory(projectId, xyz.mederi.api.AddProjectDirectoryRequest(directory))
        refreshProjects()
    }

    override suspend fun removeProjectDirectory(projectId: String, directory: String): Result<Unit> = runCatching {
        mederi.projects.removeDirectory(projectId, xyz.mederi.api.RemoveProjectDirectoryRequest(directory))
        refreshProjects()
    }

    // ------------------------------------------------------------------
    // Conversation / Session
    // ------------------------------------------------------------------

    override suspend fun createConversation(projectId: String, agent: AgentOption?): Result<Conversation> = runCatching {
        val agentMode = agent?.let {
            runCatching { AgentMode.valueOf(it.mode.name) }.getOrNull()
        } ?: AgentMode.AUTONOMOUS
        val workType = agent?.let {
            runCatching { xyz.mederi.domain.model.WorkType.valueOf(it.workType.name) }.getOrNull()
        } ?: xyz.mederi.domain.model.WorkType.CODE
        val session = mederi.sessions.create(
            CreateSessionRequest(
                agentConfig = AgentConfig(
                    agentMode = agentMode,
                    workType = workType
                ),
                projectId = projectId,
                title = ""
            )
        )
        refreshProjects()
        MederiModelMapper.toConversation(session, providerId = modelToProvider[session.aiModel?.id])
    }

    override suspend fun deleteConversation(conversationId: String): Result<Unit> = runCatching {
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
            modelToProvider = { modelToProvider[it] }
        )
    }

    /**
     * 构建会话当前完整快照（不订阅事件流）。
     * server 模式下由 GET /v1/sessions/{id}/snapshot 暴露给 wasmJs 客户端作初始状态。
     */
    override suspend fun getSnapshot(conversationId: String): Result<ConversationSnapshot> = runCatching {
        val session = mederi.sessions.get(conversationId)
        val messages = mederi.sessions.listMessages(conversationId)
        ConversationSnapshot(
            conversation = MederiModelMapper.toConversation(session, session.aiModel?.id?.let { modelToProvider[it] }),
            messages = messages.map { MederiModelMapper.toChatMessage(it) },
            tokenUsage = MederiModelMapper.toTokenUsage(messages),
            contextUsedTokens = MederiModelMapper.toContextUsedTokens(messages),
            cost = MederiModelMapper.toCostSummary()
        )
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
        DebugLog.data("AiCore", "input.agent", "${input.agent?.id} (${input.agent?.name})")
        DebugLog.data("AiCore", "input.thinkingLevel", input.thinkingLevel)
        val model = input.model?.let { findCoreModel(it.id) }
        DebugLog.data("AiCore", "findCoreModel result", "${model?.id} (${model?.name}), providerModelId=${model?.providerModelId}, supportsReasoning=${model?.supportsReasoning}")
        val agentConfig = MederiInputMapper.toAgentConfig(input.agent, model, input.thinkingLevel)
        DebugLog.data("AiCore", "AgentConfig", "agentMode=${agentConfig.agentMode}, workType=${agentConfig.workType}, aiModel=${agentConfig.aiModel?.id}, reasoningLevel=${agentConfig.reasoningLevel}")
        val parts = MederiInputMapper.toMessageParts(input)
        DebugLog.data("AiCore", "parts count", parts.size)
        DebugLog.data("AiCore", "text", (parts.filterIsInstance<xyz.mederi.domain.model.MessagePart.Text>().firstOrNull()?.text ?: ""))
        val request = SendMessageRequest(
            agentConfig = agentConfig,
            parts = parts
        )
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
        approved: Boolean
    ): Result<Unit> = runCatching {
        mederi.sessions.resolvePlanApproval(conversationId, planId, approved)
    }

    override suspend fun compressHistory(conversationId: String): Result<Unit> = runCatching {
        mederi.sessions.compressHistory(conversationId)
    }

    override suspend fun listMessages(conversationId: String): Result<List<ChatMessage>> = runCatching {
        mederi.sessions.listMessages(conversationId).map { MederiModelMapper.toChatMessage(it) }
    }

    override suspend fun listMessagesPage(conversationId: String): Result<MessagesPage> = runCatching {
        val messages = mederi.sessions.listMessages(conversationId)
        MessagesPage(
            messages = messages.map { MederiModelMapper.toChatMessage(it) },
            tokenUsage = MederiModelMapper.toTokenUsage(messages),
            contextUsedTokens = MederiModelMapper.toContextUsedTokens(messages)
        )
    }

    override suspend fun getMessage(
        conversationId: String,
        messageId: String
    ): Result<ChatMessage> = runCatching {
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
}
