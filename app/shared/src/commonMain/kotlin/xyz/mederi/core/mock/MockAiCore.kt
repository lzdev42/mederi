package xyz.mederi.core.mock

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import xyz.mederi.core.contract.AiCore
import xyz.mederi.core.contract.dto.*
import xyz.mederi.core.contract.models.*
import xyz.mederi.currentTimeMillis

class MockAiCore(
    dispatcher: CoroutineDispatcher = Dispatchers.Default
) : AiCore {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val idGenerator = MockIdGenerator()

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

    override val builtinPresets: List<String> = MockSeedData.builtinPresets

    private val conversations = mutableMapOf<String, MutableStateFlow<ConversationSnapshot>>()
    private val activeJobs = mutableMapOf<String, Job>()
    private val questionAnswers = mutableMapOf<String, CompletableDeferred<List<List<String>>?>>()

    private var scriptCounter = 0

    private val projectSandboxSettings = mutableMapOf<String, SandboxSettings>()
    private val sandboxSettingsMutex = Mutex()

    private fun requireSandboxProject(projectId: String) {
        require(_projects.value.any { it.id == projectId }) { "Project not found: $projectId" }
    }

    override suspend fun getSandboxSettings(projectId: String): Result<SandboxSettings> = runCatching {
        sandboxSettingsMutex.withLock {
            requireSandboxProject(projectId)
            projectSandboxSettings[projectId] ?: SandboxSettings()
        }
    }

    override suspend fun updateSandboxSettings(projectId: String, settings: SandboxSettings): Result<SandboxSettings> = runCatching {
        sandboxSettingsMutex.withLock {
            requireSandboxProject(projectId)
            val current = projectSandboxSettings[projectId] ?: SandboxSettings()
            check(settings.version == current.version) { "Project execution permissions changed; reload settings before saving." }
            settings.copy(version = current.version + 1).also { projectSandboxSettings[projectId] = it }
        }
    }

    override suspend fun getSandboxStatus(projectId: String): Result<xyz.mederi.core.contract.SandboxStatusInfo> = runCatching {
        requireSandboxProject(projectId)
        xyz.mederi.core.contract.SandboxStatusInfo("mock", false, "", "Preview: no commands are executed")
    }

    override suspend fun resolveExecutionApproval(conversationId: String, requestId: String, approved: Boolean): Result<Unit> =
        Result.failure(IllegalStateException("No live execution approval: $requestId"))

    override suspend fun initialize(): Result<Unit> = runCatching {
        _projects.value = MockSeedData.projects
        _providers.value = MockSeedData.providers
        _availableModels.value = MockSeedData.models
        _availableAgents.value = MockSeedData.agents

        conversations["conv_101"] = MutableStateFlow(MockSeedData.conv101Snapshot)
        conversations["conv_101_sub_1"] = MutableStateFlow(MockSeedData.subConv101Snapshot)
        conversations["conv_error"] = MutableStateFlow(MockSeedData.convErrorSnapshot)
        conversations["conv_102"] = MutableStateFlow(
            ConversationSnapshot(
                conversation = MockSeedData.projects[0].conversations[1],
                messages = emptyList(),
                tokenUsage = TokenUsage(),
                cost = CostSummary(),
            )
        )
        conversations["conv_103"] = MutableStateFlow(
            ConversationSnapshot(
                conversation = MockSeedData.projects[1].conversations[0],
                messages = emptyList(),
                tokenUsage = TokenUsage(),
                cost = CostSummary(),
            )
        )

        delay(500)
        _isReady.value = true
    }

    override suspend fun createProject(input: CreateProjectInput): Result<Project> = runCatching {
        val project = Project(id = "proj_${idGenerator.next()}", name = input.name, directory = input.directory, conversations = emptyList())
        _projects.value = _projects.value + project
        project
    }

    override suspend fun renameProject(projectId: String, name: String): Result<Unit> = runCatching {
        val projects = _projects.value.map { if (it.id == projectId) it.copy(name = name) else it }
        _projects.value = projects
    }

    override suspend fun deleteProject(projectId: String): Result<Unit> = runCatching {
        _projects.value = _projects.value.filterNot { it.id == projectId }
    }

    override suspend fun createConversation(projectId: String, agent: AgentOption?): Result<Conversation> = runCatching {
        val conv = Conversation(id = "conv_${idGenerator.next()}", projectId = projectId, title = "Untitled", status = ConversationStatus.Idle, createdAt = currentTimeMillis(), updatedAt = currentTimeMillis())
        _projects.value = _projects.value.map { p ->
            if (p.id == projectId) p.copy(conversations = listOf(conv) + p.conversations) else p
        }
        conversations[conv.id] = MutableStateFlow(
            ConversationSnapshot(conversation = conv, messages = emptyList(), tokenUsage = TokenUsage(), cost = CostSummary())
        )
        conv
    }

    override suspend fun deleteConversation(conversationId: String): Result<Unit> = runCatching {
        conversations.remove(conversationId)
        sessionSubagentConfigs.remove(conversationId)
        sessionSubagentConcurrency.remove(conversationId)
        _projects.value = _projects.value.map { p ->
            p.copy(conversations = p.conversations.filterNot { it.id == conversationId })
        }
    }

    override suspend fun renameConversation(conversationId: String, title: String): Result<Unit> = runCatching {
        conversations[conversationId]?.let { sf ->
            val conv = sf.value.conversation.copy(title = title)
            sf.value = sf.value.copy(conversation = conv)
        }
        _projects.value = _projects.value.map { p ->
            p.copy(conversations = p.conversations.map { if (it.id == conversationId) it.copy(title = title) else it })
        }
    }

    override fun observeConversation(conversationId: String): Flow<ConversationSnapshot> = flow {
        val sf = conversations[conversationId] ?: return@flow
        emit(sf.value)
        sf.collect { value -> emit(value) }
    }.catch { emit(conversations[conversationId]?.value ?: return@catch) }

    override suspend fun getSnapshot(conversationId: String): Result<ConversationSnapshot> = runCatching {
        conversations[conversationId]?.value
            ?: throw NoSuchElementException("Conversation not found: $conversationId")
    }

    override suspend fun configureProvider(providerId: String, input: ProviderUpdateInput): Result<Unit> = runCatching {
        _providers.value = _providers.value.map { p ->
            if (p.id == providerId) {
                val updated = p.copy(
                    baseUrl = input.baseUrl ?: p.baseUrl,
                    isConnected = input.enabled ?: p.isConnected,
                    customModels = input.customModels ?: p.customModels,
                )
                if (!p.isConnected && updated.isConnected) updated.copy(models = p.models.map { m ->
                    m.copy(provider = providerId)
                }) else updated
            } else p
        }
        _availableModels.value = _providers.value.flatMap { it.models.filter { m -> m.isEnabled } }
    }

    override suspend fun addBuiltinProvider(name: String, apiKey: String): Result<ProviderConfig> = runCatching {
        // Mock: 直接返回一个内置示例
        ProviderConfig(
            id = "builtin_${idGenerator.next()}",
            name = name,
            type = ProviderType.Builtin,
            baseUrl = null,
            isConnected = true,
            models = emptyList(),
            supportsApiKey = true,
            supportsBaseUrl = false
        )
    }

    override suspend fun deleteProvider(providerId: String): Result<Unit> = runCatching {
        _providers.value = _providers.value.filterNot { it.id == providerId }
        _availableModels.value = _providers.value.flatMap { it.models.filter { m -> m.isEnabled } }
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
        val newModel = ModelOption(
            id = "mdl_${idGenerator.next()}",
            name = name,
            provider = providerId,
            supportsThinking = supportsThinking,
            supportsImages = supportsImages,
            reasoningLevels = if (supportsThinking) reasoningLevels else emptyList(),
            providerModelId = providerModelId,
            origin = ModelOrigin.MANUAL,
            isEnabled = isEnabled
        )
        _providers.value = _providers.value.map { p ->
            if (p.id == providerId) p.copy(models = p.models + newModel) else p
        }
        _availableModels.value = _providers.value.flatMap { it.models.filter { m -> m.isEnabled } }
    }

    override suspend fun deleteProviderModel(providerId: String, modelId: String): Result<Unit> = runCatching {
        _providers.value = _providers.value.map { p ->
            if (p.id == providerId) p.copy(models = p.models.filterNot { it.id == modelId }) else p
        }
        _availableModels.value = _providers.value.flatMap { it.models.filter { m -> m.isEnabled } }
    }

    override suspend fun setModelEnabled(providerId: String, modelId: String, enabled: Boolean): Result<Unit> = runCatching {
        _providers.value = _providers.value.map { p ->
            if (p.id == providerId) {
                p.copy(models = p.models.map { m ->
                    if (m.id == modelId) m.copy(isEnabled = enabled) else m
                })
            } else p
        }
        _availableModels.value = _providers.value.flatMap { it.models.filter { m -> m.isEnabled } }
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
        _providers.value = _providers.value.map { p ->
            if (p.id == providerId) {
                p.copy(models = p.models.map { m ->
                    if (m.id == modelId) m.copy(
                        name = name ?: m.name,
                        supportsThinking = supportsThinking ?: m.supportsThinking,
                        supportsImages = supportsImages ?: m.supportsImages,
                        contextWindow = contextWindow ?: m.contextWindow,
                        reasoningLevels = reasoningLevels ?: m.reasoningLevels,
                        isEnabled = isEnabled ?: m.isEnabled,
                        optimizeForRequestCount = optimizeForRequestCount ?: m.optimizeForRequestCount,
                    ) else m
                })
            } else p
        }
        _availableModels.value = _providers.value.flatMap { it.models.filter { m -> m.isEnabled } }
    }

    override suspend fun refreshProviderModels(providerId: String): Result<List<String>> = runCatching {
        _availableModels.value.filter { it.provider == providerId }.map { it.providerModelId }
    }

    override suspend fun autoSetupProviderModels(providerId: String): Result<Int> = runCatching { 0 }

    override suspend fun createCustomProvider(input: CreateCustomProviderInput): Result<ProviderConfig> = runCatching {
        val config = ProviderConfig(
            id = "custom_${idGenerator.next()}",
            name = input.name,
            type = ProviderType.Custom,
            baseUrl = input.baseUrl,
            isConnected = input.apiKey != null,
            models = input.customModels.map { cm ->
                ModelOption(
                    id = cm.id,
                    name = cm.name,
                    provider = "",
                    supportsThinking = cm.supportsThinking,
                    supportsImages = cm.supportsImages,
                    reasoningLevels = cm.reasoningLevels,
                    providerModelId = cm.id,
                    origin = ModelOrigin.MANUAL
                )
            },
            customModels = input.customModels,
            supportsApiKey = true,
            supportsBaseUrl = true,
            apiKeys = if (input.apiKey != null) listOf(
                ApiKeyOption(id = "key_${idGenerator.next()}", name = "default", maskedValue = "sk-...${input.apiKey.takeLast(4)}", isDefault = true)
            ) else emptyList()
        )
        _providers.value = _providers.value + config
        _availableModels.value = _providers.value.flatMap { it.models.filter { m -> m.isEnabled } }
        config
    }

    override suspend fun addProviderApiKey(
        providerId: String,
        name: String,
        key: String,
        isDefault: Boolean
    ): Result<ApiKeyOption> = runCatching {
        val newKey = ApiKeyOption(
            id = "key_${idGenerator.next()}",
            name = name,
            maskedValue = if (key.length <= 7) "***" else "${key.take(3)}...${key.takeLast(4)}",
            isDefault = isDefault
        )
        _providers.value = _providers.value.map { p ->
            if (p.id == providerId) {
                val updatedKeys = if (isDefault) {
                    p.apiKeys.map { it.copy(isDefault = false) } + newKey
                } else {
                    p.apiKeys + newKey
                }
                p.copy(apiKeys = updatedKeys, isConnected = true)
            } else p
        }
        newKey
    }

    override suspend fun deleteProviderApiKey(providerId: String, keyId: String): Result<Unit> = runCatching {
        _providers.value = _providers.value.map { p ->
            if (p.id == providerId) {
                val updatedKeys = p.apiKeys.filterNot { it.id == keyId }
                p.copy(apiKeys = updatedKeys, isConnected = updatedKeys.isNotEmpty())
            } else p
        }
    }

    override suspend fun setDefaultProviderApiKey(providerId: String, keyId: String): Result<Unit> = runCatching {
        _providers.value = _providers.value.map { p ->
            if (p.id == providerId) {
                val updatedKeys = p.apiKeys.map { it.copy(isDefault = it.id == keyId) }
                p.copy(apiKeys = updatedKeys)
            } else p
        }
    }

    override suspend fun sendMessage(conversationId: String, input: ChatPromptInput): Result<Unit> = runCatching {
        if (conversationId == "conv_error") throw Exception("Simulated backend error")
        val sf = conversations[conversationId] ?: throw Exception("Conversation not found")
        val userMsg = ChatMessage(
            id = input.messageId ?: idGenerator.next(), conversationId = conversationId, role = ChatRole.User,
            blocks = listOf(ChatBlock.Text(idGenerator.next(), input.text)),
            createdAt = currentTimeMillis(), completedAt = currentTimeMillis(), parentMessageId = null,
            model = null, agent = null,
        )
        val assistantPlaceholder = ChatMessage(
            id = idGenerator.next(), conversationId = conversationId, role = ChatRole.Assistant,
            blocks = emptyList(), createdAt = currentTimeMillis(), completedAt = null, parentMessageId = userMsg.id,
            model = input.model?.id, agent = input.agent?.id,
            isStreaming = true,
        )
        val newMessages = sf.value.messages + listOf(userMsg, assistantPlaceholder)
        sf.value = sf.value.copy(messages = newMessages, conversation = sf.value.conversation.copy(status = ConversationStatus.Working, updatedAt = currentTimeMillis()))

        activeJobs[conversationId]?.cancel()
        activeJobs[conversationId] = scope.launch {
            MockScenarios.runScript(conversationId, input, sf, idGenerator, questionAnswers)
        }
    }

    override suspend fun steerMessage(conversationId: String, input: ChatPromptInput): Result<Unit> =
        sendMessage(conversationId, input)

    override suspend fun abort(conversationId: String): Result<Unit> = runCatching {
        activeJobs.remove(conversationId)?.cancel()
        val sf = conversations[conversationId]
        sf?.let {
            val updated = it.value.messages.map { msg ->
                if (msg.isStreaming && msg.role == ChatRole.Assistant) msg.copy(isStreaming = false, completedAt = currentTimeMillis()) else msg
            }
            it.value = it.value.copy(messages = updated, conversation = it.value.conversation.copy(status = ConversationStatus.Idle, updatedAt = currentTimeMillis()))
        }
    }

    override suspend fun resolveQuestion(conversationId: String, questionId: String, answers: List<List<String>>): Result<Unit> = runCatching {
        val sf = conversations[conversationId] ?: return@runCatching
        sf.value = sf.value.copy(
            conversation = sf.value.conversation.copy(status = ConversationStatus.Working),
            pendingQuestion = null
        )
        _projects.value = _projects.value.map { p ->
            p.copy(conversations = p.conversations.map { c ->
                if (c.id == conversationId) c.copy(status = ConversationStatus.Working) else c
            })
        }
    }

    override suspend fun recordPlanManualAcceptance(
        conversationId: String, planId: String, input: xyz.mederi.core.contract.models.ManualPlanAcceptanceInput
    ): Result<Unit> = Result.failure(UnsupportedOperationException("Manual acceptance unavailable in mock"))

    override suspend fun getPlanAcceptanceReport(conversationId: String, planId: String): Result<String> =
        Result.failure(NoSuchElementException(planId))

    override suspend fun resolvePlanApproval(
        conversationId: String,
        planId: String,
        approved: Boolean
    ): Result<Unit> = runCatching {
        val sf = conversations[conversationId] ?: return@runCatching
        sf.value = sf.value.copy(
            conversation = sf.value.conversation.copy(status = ConversationStatus.Working),
            pendingPlanApproval = null
        )
        _projects.value = _projects.value.map { p ->
            p.copy(conversations = p.conversations.map { c ->
                if (c.id == conversationId) c.copy(status = ConversationStatus.Working) else c
            })
        }
    }

    override suspend fun updateConversationSettings(
        conversationId: String,
        input: UpdateConversationSettingsInput
    ): Result<Unit> = runCatching {
        // Mock: 把设置写回内存会话快照，供预览/测试观察
        // 全量覆盖写（与真实实现 updateComposeState 的 SET 语义一致）：null/空值直接写入，不再保留旧值
        val sf = conversations[conversationId] ?: return@runCatching
        val conv = sf.value.conversation
        sf.value = sf.value.copy(
            conversation = conv.copy(
                modelId = input.model?.id,
                modelProvider = input.model?.provider,
                agent = input.agent?.mode?.name,
                apiKeyId = input.apiKeyId,
                thinkingLevel = input.thinkingLevel,
                draftText = input.draftText
            )
        )
        _projects.value = _projects.value.map { p ->
            p.copy(conversations = p.conversations.map { c ->
                if (c.id == conversationId) {
                    c.copy(
                        modelId = input.model?.id,
                        modelProvider = input.model?.provider,
                        agent = input.agent?.mode?.name,
                        apiKeyId = input.apiKeyId,
                        thinkingLevel = input.thinkingLevel,
                        draftText = input.draftText
                    )
                } else c
            })
        }
    }

    override suspend fun compressHistory(conversationId: String): Result<Unit> = runCatching {
        // Mock: no-op
    }

    private val _compactionThresholdPercent = MutableStateFlow(70)
    override val compactionThresholdPercent: StateFlow<Int> = _compactionThresholdPercent.asStateFlow()
    override suspend fun setCompactionThresholdPercent(percent: Int) {
        _compactionThresholdPercent.value = percent.coerceIn(1, 100)
    }

    override suspend fun listMessages(conversationId: String): Result<List<ChatMessage>> = runCatching {
        conversations[conversationId]?.value?.messages ?: emptyList()
    }

    override suspend fun listMessagesPage(conversationId: String): Result<MessagesPage> = runCatching {
        val snapshot = conversations[conversationId]?.value
            ?: throw NoSuchElementException("Conversation not found: $conversationId")
        MessagesPage(
            messages = snapshot.messages,
            tokenUsage = snapshot.tokenUsage,
            contextUsedTokens = snapshot.contextUsedTokens
        )
    }

    override suspend fun getMessage(conversationId: String, messageId: String): Result<ChatMessage> = runCatching {
        val snapshot = conversations[conversationId]?.value
            ?: throw NoSuchElementException("Conversation not found: $conversationId")
        snapshot.messages.find { it.id == messageId }
            ?: throw NoSuchElementException("Message not found: $messageId")
    }

    override suspend fun rollbackToMessage(conversationId: String, messageId: String): Result<Unit> = runCatching {
        // 与真实实现语义对齐：会话/消息不存在时必须失败，不能静默 no-op
        val sf = conversations[conversationId]
            ?: throw NoSuchElementException("Conversation not found: $conversationId")
        val msgs = sf.value.messages
        val idx = msgs.indexOfFirst { it.id == messageId }
        if (idx < 0) {
            throw NoSuchElementException("Message not found: $messageId")
        }
        sf.value = sf.value.copy(messages = msgs.take(idx))
    }

    override suspend fun listRawMessages(conversationId: String): Result<List<RawMessageDto>> = runCatching {
        emptyList()
    }

    override suspend fun getProcessStats(): Result<ProcessStats> = runCatching {
        // mock 固定值：够 UI 画曲线，不代表真机
        ProcessStats(
            cpuUsage = 0.18,
            cpuCores = 8,
            heapUsedBytes = 512L * 1024 * 1024,
            heapCommittedBytes = 768L * 1024 * 1024,
            heapMaxBytes = 4L * 1024 * 1024 * 1024,
            rssBytes = 1024L * 1024 * 1024,
            timestampMillis = currentTimeMillis()
        )
    }

    override fun events(): Flow<CoreEvent> = emptyFlow()

    override suspend fun getFileDiffs(conversationId: String, messageId: String?): Result<List<FileDiff>> = runCatching {
        val sf = conversations[conversationId] ?: throw Exception("Not found")
        listOf(FileDiff(
            filePath = "ui/src/commonMain/kotlin/xyz/mederi/ui/WorkspaceViewModel.kt",
            before = "// old code", after = "// new code", additions = 10, deletions = 3
        ))
    }

    override suspend fun previewOffice(conversationId: String, path: String): Result<String> = runCatching {
        "<html><body><h1>Mock preview for $path</h1></body></html>"
    }

    private val subagentConfigs = mutableMapOf(
        "EXECUTOR" to SubagentConfigItem(
            role = "EXECUTOR",
            displayName = "执行器 (Executor)",
            description = "负责执行计划中的具体子任务，拥有代码修改与命令执行权限"
        ),
        "RESEARCHER" to SubagentConfigItem(
            role = "RESEARCHER",
            displayName = "研究员 (Researcher)",
            description = "负责只读调研代码库与分析上下文，无写入与命令执行权限"
        ),
        "BROWSER_OPERATOR" to SubagentConfigItem("BROWSER_OPERATOR", "浏览器操作员 (Operator)", "浏览器物理操作与感知"),
        "BROWSER_BRAIN" to SubagentConfigItem("BROWSER_BRAIN", "浏览器大脑 (Brain)", "页面内容分析与判定")
    )

    override suspend fun listSubagentConfigs(): Result<List<SubagentConfigItem>> = runCatching {
        subagentConfigs.values.toList()
    }

    override suspend fun updateSubagentConfig(role: String, input: UpdateSubagentConfigInput): Result<Unit> = runCatching {
        val current = subagentConfigs[role] ?: SubagentConfigItem(
            role = role,
            displayName = role,
            description = ""
        )
        val modelName = availableModels.value.find { it.id == input.modelId }?.name
        subagentConfigs[role] = current.copy(
            modelId = input.modelId,
            modelName = modelName,
            reasoningLevel = input.reasoningLevel,
            apiKeyId = input.apiKeyId,
            isInheriting = input.modelId == null && input.reasoningLevel == null && input.apiKeyId == null
        )
    }

    private var subagentGlobalSettings = SubagentGlobalSettings(maxConcurrentAgents = 2)

    override suspend fun getSubagentGlobalSettings(): Result<SubagentGlobalSettings> = runCatching {
        subagentGlobalSettings
    }

    override suspend fun updateSubagentGlobalSettings(input: UpdateSubagentGlobalSettingsInput): Result<Unit> = runCatching {
        require(input.maxConcurrentAgents >= 1) { "maxConcurrentAgents must be >= 1" }
        subagentGlobalSettings = subagentGlobalSettings.copy(maxConcurrentAgents = input.maxConcurrentAgents)
    }

    private val sessionSubagentConfigs = mutableMapOf<String, MutableMap<String, SubagentConfigItem>>()
    private val sessionSubagentConcurrency = mutableMapOf<String, SubagentGlobalSettings>()

    private fun requireSubagentConversation(conversationId: String) {
        require(conversationId in conversations) { "Conversation not found: $conversationId" }
    }

    override suspend fun listSessionSubagentConfigs(conversationId: String): Result<List<SubagentConfigItem>> = runCatching {
        requireSubagentConversation(conversationId)
        subagentConfigs.filterKeys { it == "EXECUTOR" || it == "RESEARCHER" }.map { (role, defaults) ->
            sessionSubagentConfigs[conversationId]?.get(role) ?: defaults.copy(usesDefault = true)
        }
    }

    override suspend fun updateSessionSubagentConfig(
        conversationId: String, role: String, input: UpdateSubagentConfigInput?
    ): Result<Unit> = runCatching {
        requireSubagentConversation(conversationId)
        require(role == "EXECUTOR" || role == "RESEARCHER") { "Role has no session settings: $role" }
        val defaults = subagentConfigs[role] ?: error("Unknown subagent role: $role")
        if (input == null) {
            sessionSubagentConfigs[conversationId]?.remove(role)
        } else {
            sessionSubagentConfigs.getOrPut(conversationId) { mutableMapOf() }[role] = defaults.copy(
                modelId = input.modelId,
                modelName = availableModels.value.find { it.id == input.modelId }?.name,
                reasoningLevel = input.reasoningLevel, apiKeyId = input.apiKeyId,
                isInheriting = input.modelId == null && input.reasoningLevel == null && input.apiKeyId == null,
                usesDefault = false
            )
        }
        Unit
    }

    override suspend fun getSessionSubagentGlobalSettings(conversationId: String): Result<SubagentGlobalSettings> = runCatching {
        requireSubagentConversation(conversationId)
        sessionSubagentConcurrency[conversationId] ?: subagentGlobalSettings.copy(usesDefault = true)
    }

    override suspend fun updateSessionSubagentGlobalSettings(
        conversationId: String, input: UpdateSubagentGlobalSettingsInput?
    ): Result<Unit> = runCatching {
        requireSubagentConversation(conversationId)
        if (input == null) sessionSubagentConcurrency.remove(conversationId)
        else {
            require(input.maxConcurrentAgents >= 1) { "maxConcurrentAgents must be >= 1" }
            sessionSubagentConcurrency[conversationId] = SubagentGlobalSettings(input.maxConcurrentAgents, usesDefault = false)
        }
        Unit
    }

    private val mockWorkspaces = mutableMapOf<String, WorkspaceInfo>()
    private val mockOperations = mutableMapOf<String, WorkspaceOperation>()
    private val mockOperationLogs = mutableMapOf<String, StringBuilder>()
    private var mockWorktreeSettings = WorktreeSettings(rootPath = "/mock/worktrees", maxManagedWorktrees = 15, automaticCleanup = true, version = 1L)
    private val mockEnvironments = mutableMapOf<String, MutableMap<String, ProjectEnvironment>>()

    override suspend fun createConversation(input: CreateConversationInput): Result<Conversation> = runCatching {
        val baseConv = createConversation(input.projectId, input.agent).getOrThrow()
        val wsId = "ws_${baseConv.id}"
        val ws = Workspace(
            id = wsId,
            projectId = input.projectId,
            kind = input.workspaceSelection.kind,
            checkoutRoot = "/mock/projects/${input.projectId}",
            projectRelativePath = "",
            state = if (input.workspaceSelection.kind == WorkspaceKind.LOCAL) WorkspaceState.READY else WorkspaceState.REQUESTED,
            generation = 1L,
            associatedSessionId = baseConv.id,
            pendingSelection = PendingWorkspaceSelection(
                selection = input.workspaceSelection,
                resolvedBaseOid = "oid_base",
                sourceCheckoutId = null,
                environmentId = input.workspaceSelection.environmentId,
                environmentVersion = 1L,
                environmentHash = "hash1"
            ),
            createdAt = "2026-11-02T00:00:00Z",
            lastUsedAt = "2026-11-02T00:00:00Z"
        )
        val info = WorkspaceInfo(
            workspace = ws,
            headOid = "oid_head",
            branch = "main",
            dirty = false,
            capabilities = setOf(WorkspaceCapability.OPEN_FILES, WorkspaceCapability.OPEN_TERMINAL, WorkspaceCapability.HANDOFF)
        )
        mockWorkspaces[wsId] = info
        val updatedConv = baseConv.copy(workspaceId = wsId)
        val snap = conversations[baseConv.id]?.value
        if (snap != null) {
            // 与真实 core 的 getSnapshot 对齐：会话快照携带绑定的 workspaceInfo，
            // 使 UI 选择器能据 active workspace 展示 kind/分支标签。
            conversations[baseConv.id]?.value = snap.copy(
                conversation = updatedConv,
                workspaceInfo = ConversationWorkspaceInfo(active = info, associated = null, operation = null)
            )
        }
        updatedConv
    }

    override suspend fun getRepositoryInfo(projectId: String): Result<RepositoryInfo> = runCatching {
        require(_projects.value.any { it.id == projectId }) { "Project not found: $projectId" }
        RepositoryInfo(
            isGit = true,
            checkoutRoot = "/mock/projects/$projectId",
            projectRelativePath = "",
            headOid = "head_$projectId",
            branch = "main",
            branches = listOf(GitBranchInfo(name = "main", headOid = "head_$projectId", inUseByCheckout = null)),
            dirty = false,
            unsupportedReasons = emptyList()
        )
    }

    override suspend fun listWorkspaces(projectId: String): Result<List<WorkspaceInfo>> = runCatching {
        mockWorkspaces.values.filter { it.workspace.projectId == projectId }
    }

    override suspend fun getConversationWorkspace(sessionId: String): Result<ConversationWorkspaceInfo> = runCatching {
        val snap = conversations[sessionId]?.value ?: throw NoSuchElementException("Session not found: $sessionId")
        val wsId = snap.conversation.workspaceId ?: throw NoSuchElementException("Session $sessionId has no workspace")
        val ws = mockWorkspaces[wsId] ?: throw NoSuchElementException("Workspace $wsId not found")
        val op = mockOperations.values.firstOrNull { it.workspaceId == wsId && it.state in listOf(WorkspaceOperationState.PENDING, WorkspaceOperationState.RUNNING) }
        ConversationWorkspaceInfo(active = ws, associated = null, operation = op)
    }

    override suspend fun previewWorkspaceHandoff(sessionId: String, input: HandoffPreviewInput): Result<HandoffPreview> = runCatching {
        val snap = conversations[sessionId]?.value ?: throw NoSuchElementException("Session not found: $sessionId")
        val sourceWs = mockWorkspaces[snap.conversation.workspaceId] ?: throw NoSuchElementException("Source workspace not found")
        val targetWs = sourceWs.copy(
            workspace = sourceWs.workspace.copy(id = "ws_target_${sourceWs.workspace.id}", kind = input.targetKind, state = WorkspaceState.READY)
        )
        HandoffPreview(
            token = "token_preview_${sessionId.take(8)}",
            expiresAt = "2026-11-02T01:00:00Z",
            source = sourceWs,
            target = targetWs,
            fingerprints = mapOf("source" to "f1", "target" to "f2"),
            affectedSessions = emptyList(),
            taskBranch = "mederi/task_${sessionId.take(8)}",
            conflicts = emptyList()
        )
    }

    override suspend fun handoffWorkspace(sessionId: String, input: HandoffInput): Result<WorkspaceOperation> = runCatching {
        val key = "HANDOFF:${input.requestId}"
        val existing = mockOperations.values.find { it.idempotencyKey == key }
        if (existing != null) return@runCatching existing

        val op = WorkspaceOperation(
            id = "op_${input.requestId}",
            projectId = "p1",
            sessionId = sessionId,
            workspaceId = "ws_handoff",
            kind = WorkspaceOperationKind.HANDOFF,
            state = WorkspaceOperationState.SUCCEEDED,
            phase = WorkspacePhase.COMPLETE,
            idempotencyKey = key,
            requestHash = "hash_${input.requestId}",
            createdAt = "2026-11-02T00:00:00Z",
            updatedAt = "2026-11-02T00:00:00Z",
            operationPlan = WorkspaceOperationPlan.HandoffPlan(
                requestHash = "hash_${input.requestId}",
                targetKind = input.targetKind,
                taskBranchName = input.taskBranchName ?: "mederi/task",
                sourceWorkspaceId = "ws_src",
                targetWorkspaceId = "ws_tgt",
                expectedSourceHeadOid = "oid_src",
                expectedTargetHeadOid = null,
                previewToken = input.previewToken
            )
        )
        mockOperations[op.id] = op
        op
    }

    override suspend fun restoreWorkspace(workspaceId: String, input: RestoreWorkspaceInput): Result<WorkspaceOperation> = runCatching {
        val key = "RESTORE:${input.requestId}"
        val existing = mockOperations.values.find { it.idempotencyKey == key }
        if (existing != null) return@runCatching existing

        val ws = mockWorkspaces[workspaceId]?.workspace ?: throw NoSuchElementException("Workspace $workspaceId not found")
        val op = WorkspaceOperation(
            id = "op_${input.requestId}",
            projectId = ws.projectId,
            workspaceId = workspaceId,
            kind = WorkspaceOperationKind.RESTORE,
            state = WorkspaceOperationState.SUCCEEDED,
            phase = WorkspacePhase.COMPLETE,
            idempotencyKey = key,
            requestHash = "hash_${input.requestId}",
            createdAt = "2026-11-02T00:00:00Z",
            updatedAt = "2026-11-02T00:00:00Z",
            operationPlan = WorkspaceOperationPlan.RestorePlan(
                requestHash = "hash_${input.requestId}",
                workspaceId = workspaceId,
                snapshotId = "snap_restore",
                targetCheckoutPath = ws.checkoutRoot
            )
        )
        mockOperations[op.id] = op
        mockWorkspaces[workspaceId] = mockWorkspaces[workspaceId]!!.copy(
            workspace = ws.copy(state = WorkspaceState.READY)
        )
        op
    }

    override suspend fun archiveWorkspace(workspaceId: String, input: ArchiveWorkspaceInput): Result<WorkspaceOperation> = runCatching {
        val key = "ARCHIVE:${input.requestId}"
        val existing = mockOperations.values.find { it.idempotencyKey == key }
        if (existing != null) return@runCatching existing

        val ws = mockWorkspaces[workspaceId]?.workspace ?: throw NoSuchElementException("Workspace $workspaceId not found")
        val op = WorkspaceOperation(
            id = "op_${input.requestId}",
            projectId = ws.projectId,
            workspaceId = workspaceId,
            kind = WorkspaceOperationKind.ARCHIVE,
            state = WorkspaceOperationState.SUCCEEDED,
            phase = WorkspacePhase.COMPLETE,
            idempotencyKey = key,
            requestHash = "hash_${input.requestId}",
            createdAt = "2026-11-02T00:00:00Z",
            updatedAt = "2026-11-02T00:00:00Z",
            operationPlan = WorkspaceOperationPlan.ArchivePlan(
                requestHash = "hash_${input.requestId}",
                workspaceId = workspaceId,
                checkoutPath = ws.checkoutRoot
            )
        )
        mockOperations[op.id] = op
        mockWorkspaces[workspaceId] = mockWorkspaces[workspaceId]!!.copy(
            workspace = ws.copy(state = WorkspaceState.ARCHIVED)
        )
        op
    }

    override suspend fun createWorkspaceBranch(workspaceId: String, input: CreateWorkspaceBranchInput): Result<WorkspaceInfo> = runCatching {
        val wsInfo = mockWorkspaces[workspaceId] ?: throw NoSuchElementException("Workspace $workspaceId not found")
        if (wsInfo.workspace.generation != input.expectedGeneration) {
            throw IllegalStateException("Generation mismatch: expected ${input.expectedGeneration}, actual ${wsInfo.workspace.generation}")
        }
        val updated = wsInfo.copy(
            branch = input.name,
            workspace = wsInfo.workspace.copy(generation = wsInfo.workspace.generation + 1, taskBranchName = input.name)
        )
        mockWorkspaces[workspaceId] = updated
        updated
    }

    override suspend fun getWorkspaceOperation(id: String): Result<WorkspaceOperation> = runCatching {
        mockOperations[id] ?: throw NoSuchElementException("Operation not found: $id")
    }

    override suspend fun cancelWorkspaceOperation(id: String): Result<WorkspaceOperation> = runCatching {
        val op = mockOperations[id] ?: throw NoSuchElementException("Operation not found: $id")
        val updated = op.copy(state = WorkspaceOperationState.CANCELLED, cancelRequested = true)
        mockOperations[id] = updated
        updated
    }

    override suspend fun listWorkspaceOperations(projectId: String): Result<List<WorkspaceOperation>> = runCatching {
        mockOperations.values.filter { it.projectId == projectId }
    }

    override suspend fun setConversationArchived(sessionId: String, archived: Boolean, requestId: String): Result<WorkspaceOperation> = runCatching {
        val snap = conversations[sessionId]?.value ?: throw NoSuchElementException("Session not found: $sessionId")
        val now = if (archived) "2026-11-02T00:00:00Z" else null
        val updated = snap.conversation.copy(archivedAt = now, pinned = if (archived) false else snap.conversation.pinned)
        conversations[sessionId]?.value = snap.copy(conversation = updated)
        val op = WorkspaceOperation(
            id = "op_archive_$sessionId",
            projectId = updated.projectId ?: "p1",
            sessionId = sessionId,
            workspaceId = updated.workspaceId ?: "ws_$sessionId",
            kind = WorkspaceOperationKind.ARCHIVE,
            state = WorkspaceOperationState.SUCCEEDED,
            phase = WorkspacePhase.COMPLETE,
            idempotencyKey = "ARCHIVE:$requestId",
            requestHash = "hash_$requestId",
            createdAt = "2026-11-02T00:00:00Z",
            updatedAt = "2026-11-02T00:00:00Z",
            operationPlan = WorkspaceOperationPlan.ArchivePlan(
                requestHash = "hash_$requestId",
                workspaceId = updated.workspaceId ?: "ws_$sessionId",
                checkoutPath = "/mock"
            )
        )
        mockOperations[op.id] = op
        op
    }

    override suspend fun setConversationPinned(sessionId: String, pinned: Boolean): Result<Conversation> = runCatching {
        val snap = conversations[sessionId]?.value ?: throw NoSuchElementException("Session not found: $sessionId")
        val updated = snap.conversation.copy(pinned = pinned)
        conversations[sessionId]?.value = snap.copy(conversation = updated)
        updated
    }

    override suspend fun listArchivedConversations(projectId: String): Result<List<Conversation>> = runCatching {
        conversations.values
            .map { it.value.conversation }
            .filter { it.projectId == projectId && it.archivedAt != null }
    }

    override suspend fun getWorktreeSettings(): Result<WorktreeSettings> = runCatching {
        mockWorktreeSettings
    }

    override suspend fun updateWorktreeSettings(settings: WorktreeSettings): Result<WorktreeSettings> = runCatching {
        if (settings.version != mockWorktreeSettings.version) {
            throw IllegalStateException("Settings version mismatch: expected ${mockWorktreeSettings.version}, actual ${settings.version}")
        }
        val updated = settings.copy(version = mockWorktreeSettings.version + 1)
        mockWorktreeSettings = updated
        updated
    }

    override suspend fun listProjectEnvironments(projectId: String, workspaceId: String?): Result<List<ProjectEnvironment>> = runCatching {
        if (workspaceId != null) {
            val ws = mockWorkspaces[workspaceId] ?: throw NoSuchElementException("Workspace $workspaceId not found")
            if (ws.workspace.projectId != projectId) throw IllegalArgumentException("Workspace $workspaceId does not belong to $projectId")
        }
        mockEnvironments[projectId]?.values?.toList() ?: emptyList()
    }

    override suspend fun saveProjectEnvironment(
        projectId: String,
        environment: ProjectEnvironment,
        expectedVersion: Long,
        workspaceId: String?
    ): Result<ProjectEnvironment> = runCatching {
        if (workspaceId != null) {
            val ws = mockWorkspaces[workspaceId] ?: throw NoSuchElementException("Workspace $workspaceId not found")
            if (ws.workspace.projectId != projectId) throw IllegalArgumentException("Workspace $workspaceId does not belong to $projectId")
        }
        val envMap = mockEnvironments.getOrPut(projectId) { mutableMapOf() }
        val existingEnv: ProjectEnvironment? = envMap[environment.id]
        if (existingEnv != null && existingEnv.version != expectedVersion) {
            throw IllegalStateException("Environment version mismatch: expected $expectedVersion, actual ${existingEnv.version}")
        }
        val updated = environment.copy(version = expectedVersion + 1)
        envMap[environment.id] = updated
        updated
    }

    override suspend fun runWorkspaceAction(workspaceId: String, input: WorkspaceActionInput): Result<WorkspaceOperation> = runCatching {
        val ws = mockWorkspaces[workspaceId]?.workspace ?: throw NoSuchElementException("Workspace $workspaceId not found")
        input.conversationId?.let { conversationId ->
            val conversation = conversations[conversationId]?.value?.conversation
                ?: throw NoSuchElementException("Conversation $conversationId not found")
            require(conversation.projectId == ws.projectId && conversation.workspaceId == workspaceId) {
                "Conversation $conversationId is not bound to workspace $workspaceId"
            }
        }
        val key = "ACTION:${input.requestId}"
        val existing = mockOperations.values.find { it.idempotencyKey == key }
        if (existing != null) return@runCatching existing

        val op = WorkspaceOperation(
            id = "op_${input.requestId}",
            projectId = ws.projectId,
            sessionId = input.conversationId,
            workspaceId = workspaceId,
            kind = WorkspaceOperationKind.ACTION,
            state = WorkspaceOperationState.SUCCEEDED,
            phase = WorkspacePhase.COMPLETE,
            idempotencyKey = key,
            requestHash = "hash_${input.requestId}",
            createdAt = "2026-11-02T00:00:00Z",
            updatedAt = "2026-11-02T00:00:00Z",
            operationPlan = WorkspaceOperationPlan.ActionPlan(
                requestHash = "hash_${input.requestId}",
                workspaceId = workspaceId,
                environmentId = input.environmentId,
                actionId = input.actionId,
                environmentVersion = input.environmentVersion
            )
        )
        mockOperations[op.id] = op
        op
    }

    override suspend fun getWorkspaceOperationLog(id: String, afterOffset: Long, maxBytes: Int): Result<LogChunk> = runCatching {
        val text = mockOperationLogs[id]?.toString().orEmpty()
        val slice = if (afterOffset >= text.length) "" else text.substring(afterOffset.toInt())
        LogChunk(bytesUtf8 = slice, nextOffset = text.length.toLong(), eof = true, truncated = false)
    }

    /**
     * RF-09: 与真实 WorkspaceManager 对齐的路径穿越防御（mock 侧字符串级近似）。
     * 真实实现用 canonical 路径 + Path.startsWith 逐组件比较并抛 PATH_DENIED；
     * MockAiCore 位于 commonMain（不可见 core 异常类型），用同样的拒绝语义抛
     * IllegalArgumentException("Path escapes workspace root: ...")，调用方表现为 Result.failure。
     */
    private fun requireWorkspaceRelativePath(path: String) {
        if (path.isBlank()) return
        if (path.startsWith("/") || path.startsWith("\\") || path.matches(Regex("^[A-Za-z]:.*"))) {
            throw IllegalArgumentException("Path escapes workspace root: $path")
        }
        if (path.split('/', '\\').any { it == ".." }) {
            throw IllegalArgumentException("Path escapes workspace root: $path")
        }
    }

    override suspend fun listWorkspaceFiles(workspaceId: String, path: String, area: WorkspaceFileArea): Result<List<WorkspaceFileEntry>> = runCatching {
        mockWorkspaces[workspaceId] ?: throw NoSuchElementException("Workspace $workspaceId not found")
        requireWorkspaceRelativePath(path)
        listOf(
            WorkspaceFileEntry(name = "README.md", relativePath = "README.md", kind = "file", size = 12L)
        )
    }

    override suspend fun readWorkspaceFile(
        workspaceId: String,
        path: String,
        offset: Long,
        maxBytes: Int,
        area: WorkspaceFileArea
    ): Result<WorkspaceFileContent> = runCatching {
        mockWorkspaces[workspaceId] ?: throw NoSuchElementException("Workspace $workspaceId not found")
        requireWorkspaceRelativePath(path)
        WorkspaceFileContent(relativePath = path, text = "mock content", mime = "text/plain", truncated = false, nextOffset = 12L)
    }

    override suspend fun retryWorkspacePreparation(sessionId: String, failedOperationId: String, requestId: String): Result<WorkspaceOperation> = runCatching {
        val snap = conversations[sessionId]?.value ?: throw NoSuchElementException("Session not found: $sessionId")
        val wsId = snap.conversation.workspaceId ?: "ws_$sessionId"
        val op = WorkspaceOperation(
            id = "op_retry_prep_$requestId",
            projectId = snap.conversation.projectId ?: "p1",
            sessionId = sessionId,
            workspaceId = wsId,
            kind = WorkspaceOperationKind.CREATE,
            state = WorkspaceOperationState.SUCCEEDED,
            phase = WorkspacePhase.COMPLETE,
            idempotencyKey = "RETRY_PREP:$requestId",
            requestHash = "hash_$requestId",
            createdAt = "2026-11-02T00:00:00Z",
            updatedAt = "2026-11-02T00:00:00Z",
            parentOperationId = failedOperationId,
            operationPlan = WorkspaceOperationPlan.CreatePlan(
                requestHash = "hash_$requestId",
                selection = WorkspaceSelection(),
                targetPath = "/mock"
            )
        )
        mockOperations[op.id] = op
        op
    }

    override suspend fun retryWorkspaceSetup(
        workspaceId: String,
        failedOperationId: String,
        expectedEnvironmentHash: String,
        requestId: String
    ): Result<WorkspaceOperation> = runCatching {
        val ws = mockWorkspaces[workspaceId]?.workspace ?: throw NoSuchElementException("Workspace $workspaceId not found")
        val op = WorkspaceOperation(
            id = "op_retry_setup_$requestId",
            projectId = ws.projectId,
            workspaceId = workspaceId,
            kind = WorkspaceOperationKind.SETUP,
            state = WorkspaceOperationState.SUCCEEDED,
            phase = WorkspacePhase.COMPLETE,
            idempotencyKey = "RETRY_SETUP:$requestId",
            requestHash = expectedEnvironmentHash,
            createdAt = "2026-11-02T00:00:00Z",
            updatedAt = "2026-11-02T00:00:00Z",
            parentOperationId = failedOperationId,
            operationPlan = WorkspaceOperationPlan.SetupPlan(
                requestHash = expectedEnvironmentHash,
                workspaceId = workspaceId,
                environmentId = "env1",
                environmentVersion = 1L,
                environmentHash = expectedEnvironmentHash
            )
        )
        mockOperations[op.id] = op
        op
    }

    override suspend fun previewWorkspaceRecovery(
        operationId: String,
        strategy: RecoveryStrategy,
        snapshotId: String?,
        keepWorkspaceId: String?
    ): Result<RecoveryPreview> = runCatching {
        val op = mockOperations[operationId] ?: throw NoSuchElementException("Operation not found: $operationId")
        RecoveryPreview(
            token = "recov_token_$operationId",
            operationVersion = op.version,
            sourceWorkspace = mockWorkspaces[op.workspaceId],
            targetWorkspace = op.targetWorkspaceId?.let { mockWorkspaces[it] },
            eligibleSnapshots = listOf(
                RecoverySnapshotInfo(
                    snapshotId = snapshotId ?: "snap_default",
                    createdAt = "2026-11-02T00:00:00Z",
                    reason = "recovery test",
                    sizeBytes = 100L
                )
            ),
            conflicts = emptyList(),
            expiresAt = "2026-11-02T01:00:00Z"
        )
    }

    override suspend fun recoverWorkspaceOperation(operationId: String, input: WorkspaceRecoveryInput): Result<WorkspaceOperation> = runCatching {
        val op = mockOperations[operationId] ?: throw NoSuchElementException("Operation not found: $operationId")
        if (op.version != input.expectedOperationVersion) {
            throw IllegalStateException("Operation version mismatch: expected ${input.expectedOperationVersion}, actual ${op.version}")
        }
        val recovered = op.copy(
            state = WorkspaceOperationState.SUCCEEDED,
            recoveryResolution = RecoveryResolution(
                strategy = input.strategy,
                snapshotId = input.snapshotId,
                keepWorkspaceId = input.keepWorkspaceId,
                resolvedAt = "2026-11-02T00:00:00Z"
            )
        )
        mockOperations[operationId] = recovered
        recovered
    }

    fun injectSnapshot(conversationId: String, snapshot: ConversationSnapshot) {
        conversations[conversationId]?.value = snapshot
    }

    /** 测试/演示辅助：直接注入工作区操作记录（与 [injectSnapshot] 同模式，供恢复/取消/重试接线验证）。 */
    fun injectWorkspaceOperation(operation: WorkspaceOperation) {
        mockOperations[operation.id] = operation
    }
}
