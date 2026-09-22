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
        _projects.value = _projects.value.map { p ->
            p.copy(conversations = p.conversations.filterNot { it.id == conversationId })
        }
    }

    override suspend fun renameConversation(conversationId: String, title: String): Result<Unit> = runCatching {
        conversations[conversationId]?.let { sf ->
            val conv = sf.value.conversation.copy(title = title, updatedAt = currentTimeMillis())
            sf.value = sf.value.copy(conversation = conv)
        }
        _projects.value = _projects.value.map { p ->
            p.copy(conversations = p.conversations.map { if (it.id == conversationId) it.copy(title = title, updatedAt = currentTimeMillis()) else it })
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
        isEnabled: Boolean?
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
                        isEnabled = isEnabled ?: m.isEnabled
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
            id = idGenerator.next(), conversationId = conversationId, role = ChatRole.User,
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

    override suspend fun resolvePlanApproval(
        conversationId: String,
        planId: String,
        approved: Boolean,
        model: xyz.mederi.core.contract.models.ModelOption?,
        thinkingLevel: String?
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

    override suspend fun compressHistory(conversationId: String): Result<Unit> = runCatching {
        // Mock: no-op
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
        )
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
            isInheriting = input.modelId == null && input.reasoningLevel == null
        )
    }

    fun injectSnapshot(conversationId: String, snapshot: ConversationSnapshot) {
        conversations[conversationId]?.value = snapshot
    }
}
