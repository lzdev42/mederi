package xyz.mederi.core.bridge

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds
import xyz.mederi.AppInfo
import xyz.mederi.currentTimeMillis
import xyz.mederi.core.contract.AiCore
import xyz.mederi.core.contract.SnapshotReducer
import xyz.mederi.core.contract.dto.ApiError
import xyz.mederi.core.contract.dto.ChatPromptInput
import xyz.mederi.core.contract.dto.ConversationSnapshot
import xyz.mederi.core.contract.dto.CreateConversationInput
import xyz.mederi.core.contract.dto.CreateCustomProviderInput
import xyz.mederi.core.contract.dto.CreateProjectInput
import xyz.mederi.core.contract.dto.McpServerJsonResponse
import xyz.mederi.core.contract.dto.InstallMcpServerInput
import xyz.mederi.core.contract.dto.UpdateMcpServerInput
import xyz.mederi.core.contract.dto.SetMcpServerEnabledInput
import xyz.mederi.core.contract.dto.SetSkillsRootInput
import xyz.mederi.core.contract.dto.InstallSkillInput
import xyz.mederi.core.contract.dto.SkillsRootResponse
import xyz.mederi.core.contract.dto.MessagesPage
import xyz.mederi.core.contract.dto.ProviderUpdateInput
import xyz.mederi.core.contract.dto.RawMessageDto
import xyz.mederi.core.contract.dto.ReadyInfo
import xyz.mederi.core.contract.models.AgentOption
import xyz.mederi.core.contract.models.ApiKeyOption
import xyz.mederi.core.contract.models.ChatMessage
import xyz.mederi.core.contract.models.Conversation
import xyz.mederi.core.contract.models.SubagentConfigItem
import xyz.mederi.core.contract.models.SubagentGlobalSettings
import xyz.mederi.core.contract.models.UpdateSubagentConfigInput
import xyz.mederi.core.contract.models.UpdateSubagentGlobalSettingsInput
import xyz.mederi.core.contract.models.ConversationStatus
import xyz.mederi.core.contract.models.CoreEvent
import xyz.mederi.core.contract.models.CoreEventType
import xyz.mederi.core.contract.models.FileDiff
import xyz.mederi.core.contract.models.McpServerItem
import xyz.mederi.core.contract.models.SkillItem
import xyz.mederi.core.contract.models.ModelOption
import xyz.mederi.core.contract.models.ProcessStats
import xyz.mederi.core.contract.models.Project
import xyz.mederi.core.contract.models.ProviderConfig

/**
 * 基于 HTTP + SSE 的 [AiCore] 实现（遥控端：wasmJs 浏览器 / android / ios）。
 *
 * 与 JVM 端 [MederiAiCore] 共享同一契约语义：
 * - 所有状态变更走 REST（server 端由同一个 MederiAiCore 执行）
 * - 会话快照 = GET snapshot 初始状态 + 会话 SSE 事件经 commonMain [SnapshotReducer] 本地聚合
 * - token 统计由 server 计算（契约层 ChatMessage 不含 inputTokens，客户端无法重算）
 *
 * 鉴权：[passwordProvider] 非空时所有请求（REST + SSE）带 `Authorization: Bearer <password>`。
 *
 * @param baseUrl server 根地址（如 "http://127.0.0.1:8081"）；空串表示未配置，initialize 报错
 */
class ServerAiCore(
    private val baseUrl: String,
    private val passwordProvider: suspend () -> String? = { null },
) : AiCore {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val client = HttpClient {
        // 身份头：所有遥控 REST/SSE 请求统一携带 Mederi User-Agent（唯一真理源 = AppInfo.userAgent）
        defaultRequest {
            header(HttpHeaders.UserAgent, AppInfo.userAgent)
        }
        install(ContentNegotiation) { json(json) }
        install(SSE) {
            // 断线自动重连（最多 4 次，间隔 2s），重连期内快照由回查兜底
            maxReconnectionAttempts = 4
            reconnectionTime = 2.seconds
        }
    }

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

    override var builtinPresets: List<String> = emptyList()
        private set

    // ------------------------------------------------------------------
    // HTTP 基础设施
    // ------------------------------------------------------------------

    /** 鉴权 header：request builder 非挂起，密码必须先在挂起上下文取好传入 */
    private fun HttpRequestBuilder.withAuth(password: String?) {
        password?.let { header("Authorization", "Bearer $it") }
    }

    /** 非 2xx → 抛异常（body 是 ApiError 时取 error 文本），由 AiCore 方法边界包装为 Result 失败 */
    private suspend fun check(response: HttpResponse) {
        if (!response.status.isSuccess()) {
            val msg = runCatching { response.body<ApiError>().error }.getOrNull()
                ?: response.status.description
            throw RuntimeException("[${response.status.value}] $msg")
        }
    }

    private suspend inline fun <reified T> httpGet(path: String): T {
        val auth = passwordProvider()
        val response = client.get("$baseUrl$path") { withAuth(auth) }
        check(response)
        return response.body<T>()
    }

    private suspend inline fun <reified T> httpSend(
        path: String,
        method: HttpMethod = HttpMethod.Post,
        requestBody: Any? = null
    ): T {
        val auth = passwordProvider()
        val response = client.request("$baseUrl$path") {
            withAuth(auth)
            this.method = method
            // 通用 request builder 不带 Content-Type，ContentNegotiation 无法推断序列化格式（报
            // "Content-Type: null"），必须显式声明 JSON
            if (requestBody != null) {
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }
        }
        check(response)
        return response.body<T>()
    }

    /** Unit 返回值的方法调用：丢弃响应体 */
    private suspend inline fun httpCall(
        path: String,
        method: HttpMethod = HttpMethod.Post,
        requestBody: Any? = null
    ) {
        val auth = passwordProvider()
        val response = client.request("$baseUrl$path") {
            withAuth(auth)
            this.method = method
            if (requestBody != null) {
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }
        }
        check(response)
        response.bodyAsText()
    }

    /** SSE 事件流：官方 SSE 客户端插件，`incoming` 帧的 data 段 → [CoreEvent]；心跳注释帧默认不投递 */
    private fun sseEvents(path: String): Flow<CoreEvent> = flow {
        val auth = passwordProvider()
        client.sse(
            urlString = "$baseUrl$path",
            request = { withAuth(auth) }
        ) {
            incoming.collect { event ->
                event.data?.let { data ->
                    runCatching { json.decodeFromString<CoreEvent>(data) }
                        .getOrNull()
                        ?.let { emit(it) }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 状态刷新
    // ------------------------------------------------------------------

    private suspend fun refreshProjects() {
        _projects.value = httpGet("/v1/projects")
    }

    /** 镜像 MederiAiCore.refreshProvidersAndAgents 语义：provider 变更影响 models + projects 两处派生状态 */
    private suspend fun refreshProvidersAndModels() {
        _providers.value = httpGet("/v1/providers")
        _availableModels.value = httpGet("/v1/models")
        refreshProjects()
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    override suspend fun initialize(): Result<Unit> = runCatching {
        check(baseUrl.isNotBlank()) { "未配置 Mederi Server 地址，请先在设置中填写遥控服务器" }
        // 轮询 server 就绪（server 启动时同步初始化 core；这里防御冷启动/重启窗口）
        val deadline = currentTimeMillis() + 20_000
        var ready: ReadyInfo? = null
        while (currentTimeMillis() < deadline) {
            ready = runCatching { httpGet<ReadyInfo>("/v1/ready") }.getOrNull()
            if (ready?.ready == true) break
            delay(250)
        }
        check(ready?.ready == true) { "Mederi server not ready at $baseUrl" }

        builtinPresets = httpGet("/v1/presets")
        _availableAgents.value = httpGet("/v1/agents")
        _availableModels.value = httpGet("/v1/models")
        _providers.value = httpGet("/v1/providers")
        _projects.value = httpGet("/v1/projects")
        _isReady.value = true
        startSessionStatusSync()
    }

    /**
     * 监听远端 SSE 事件流，实时更新 _projects StateFlow 中对应会话的状态。
     */
    private fun startSessionStatusSync() {
        scope.launch {
            events().collect { event ->
                val newStatus = when (event.type) {
                    CoreEventType.SESSION_UPDATED -> ConversationStatus.Working
                    CoreEventType.MESSAGE_COMPLETED -> ConversationStatus.Idle
                    CoreEventType.MESSAGE_ERROR -> ConversationStatus.Error
                    CoreEventType.QUESTION_REQUESTED -> ConversationStatus.WaitingUser
                    CoreEventType.QUESTION_RESOLVED -> ConversationStatus.Working
                    CoreEventType.PLAN_APPROVAL_REQUESTED -> ConversationStatus.WaitingUser
                    CoreEventType.PLAN_APPROVAL_RESOLVED -> ConversationStatus.Working
                    else -> null
                }
                if (newStatus != null) {
                    updateConversationStatus(event.sessionId, newStatus)
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

    // ------------------------------------------------------------------
    // Project
    // ------------------------------------------------------------------

    override suspend fun createProject(input: CreateProjectInput): Result<Project> = runCatching {
        val project = httpSend<Project>("/v1/projects", requestBody = input)
        refreshProjects()
        project
    }

    override suspend fun renameProject(projectId: String, name: String): Result<Unit> = runCatching {
        httpCall("/v1/projects/$projectId", HttpMethod.Patch, xyz.mederi.core.contract.dto.RenameProjectInput(name))
        refreshProjects()
    }

    override suspend fun deleteProject(projectId: String): Result<Unit> = runCatching {
        // 与 MederiAiCore 对齐：遍历会话统一走 [deleteConversation]（删除会话的唯一封装入口，
        // 清遥控端本地 Mermaid 缓存 + 经 REST 删服务端会话），最后删项目记录
        projects.value.find { it.id == projectId }?.conversations?.forEach { conv ->
            deleteConversation(conv.id).getOrThrow()
        }
        httpCall("/v1/projects/$projectId", HttpMethod.Delete)
        refreshProjects()
    }

    // ------------------------------------------------------------------
    // Conversation / Session
    // ------------------------------------------------------------------

    override suspend fun createConversation(projectId: String, agent: AgentOption?): Result<Conversation> = runCatching {
        val conversation = httpSend<Conversation>(
            "/v1/sessions",
            requestBody = CreateConversationInput(projectId, agent)
        )
        refreshProjects()
        conversation
    }

    override suspend fun deleteConversation(conversationId: String): Result<Unit> = runCatching {
        xyz.emuci.inkcompose.MermaidCacheConfig.clearSessionCache(conversationId)
        httpCall("/v1/sessions/$conversationId", HttpMethod.Delete)
        refreshProjects()
    }

    override suspend fun renameConversation(conversationId: String, title: String): Result<Unit> = runCatching {
        httpCall("/v1/sessions/$conversationId", HttpMethod.Patch, xyz.mederi.core.contract.dto.RenameConversationInput(title))
        refreshProjects()
    }

    override fun observeConversation(conversationId: String): Flow<ConversationSnapshot> = flow {
        var snapshot = httpGet<ConversationSnapshot>("/v1/sessions/$conversationId/snapshot")
        emit(snapshot)
        sseEvents("/v1/sessions/$conversationId/events").collect { event ->
            val emissions = SnapshotReducer.applyWithRefresh(snapshot, event) {
                runCatching { httpGet<MessagesPage>("/v1/sessions/$conversationId/messages") }.getOrNull()
            }
            emissions.forEach { emit(it) }
            snapshot = emissions.last()
        }
    }

    override suspend fun getSnapshot(conversationId: String): Result<ConversationSnapshot> = runCatching {
        httpGet("/v1/sessions/$conversationId/snapshot")
    }

    // ------------------------------------------------------------------
    // Provider / Model / Key
    // ------------------------------------------------------------------

    override suspend fun configureProvider(providerId: String, input: ProviderUpdateInput): Result<Unit> = runCatching {
        httpCall("/v1/providers/$providerId", HttpMethod.Patch, input)
        refreshProvidersAndModels()
    }

    override suspend fun addBuiltinProvider(name: String, apiKey: String): Result<ProviderConfig> = runCatching {
        val config = httpSend<ProviderConfig>(
            "/v1/providers/builtin",
            requestBody = xyz.mederi.core.contract.dto.BuiltinProviderInput(name, apiKey)
        )
        refreshProvidersAndModels()
        config
    }

    override suspend fun deleteProvider(providerId: String): Result<Unit> = runCatching {
        httpCall("/v1/providers/$providerId", HttpMethod.Delete)
        refreshProvidersAndModels()
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
        httpCall(
            "/v1/providers/$providerId/models",
            requestBody = xyz.mederi.core.contract.dto.AddModelInput(
                providerModelId = providerModelId,
                name = name,
                supportsThinking = supportsThinking,
                supportsImages = supportsImages,
                contextWindow = contextWindow,
                maxTokens = maxTokens,
                reasoningLevels = reasoningLevels,
                isEnabled = isEnabled
            )
        )
        refreshProvidersAndModels()
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
        httpCall(
            "/v1/providers/$providerId/models/$modelId",
            HttpMethod.Patch,
            xyz.mederi.core.contract.dto.UpdateModelInput(
                name = name,
                supportsThinking = supportsThinking,
                supportsImages = supportsImages,
                contextWindow = contextWindow,
                maxTokens = maxTokens,
                reasoningLevels = reasoningLevels,
                isEnabled = isEnabled
            )
        )
        refreshProvidersAndModels()
    }

    override suspend fun deleteProviderModel(providerId: String, modelId: String): Result<Unit> = runCatching {
        httpCall("/v1/providers/$providerId/models/$modelId", HttpMethod.Delete)
        refreshProvidersAndModels()
    }

    override suspend fun setModelEnabled(providerId: String, modelId: String, enabled: Boolean): Result<Unit> = runCatching {
        httpCall("/v1/providers/$providerId/models/$modelId/enabled", requestBody = xyz.mederi.core.contract.dto.SetModelEnabledInput(enabled))
        refreshProvidersAndModels()
    }

    override suspend fun refreshProviderModels(providerId: String): Result<List<String>> = runCatching {
        val ids = httpSend<List<String>>("/v1/providers/$providerId/models/refresh")
        refreshProvidersAndModels()
        ids
    }

    override suspend fun autoSetupProviderModels(providerId: String): Result<Int> = runCatching {
        val updated = httpSend<Int>("/v1/providers/$providerId/models/auto-setup")
        refreshProvidersAndModels()
        updated
    }

    override suspend fun createCustomProvider(input: CreateCustomProviderInput): Result<ProviderConfig> = runCatching {
        val config = httpSend<ProviderConfig>("/v1/providers", requestBody = input)
        refreshProvidersAndModels()
        config
    }

    override suspend fun addProviderApiKey(providerId: String, name: String, key: String, isDefault: Boolean): Result<ApiKeyOption> = runCatching {
        val keyOption = httpSend<ApiKeyOption>(
            "/v1/providers/$providerId/keys",
            requestBody = xyz.mederi.core.contract.dto.AddApiKeyInput(name, key, isDefault)
        )
        refreshProvidersAndModels()
        keyOption
    }

    override suspend fun deleteProviderApiKey(providerId: String, keyId: String): Result<Unit> = runCatching {
        httpCall("/v1/providers/$providerId/keys/$keyId", HttpMethod.Delete)
        refreshProvidersAndModels()
    }

    override suspend fun setDefaultProviderApiKey(providerId: String, keyId: String): Result<Unit> = runCatching {
        httpCall("/v1/providers/$providerId/keys/$keyId/set-default")
        refreshProvidersAndModels()
    }

    // ------------------------------------------------------------------
    // Message
    // ------------------------------------------------------------------

    override suspend fun sendMessage(conversationId: String, input: ChatPromptInput): Result<Unit> = runCatching {
        httpCall("/v1/sessions/$conversationId/messages", requestBody = input)
    }

    override suspend fun steerMessage(conversationId: String, input: ChatPromptInput): Result<Unit> = runCatching {
        httpCall("/v1/sessions/$conversationId/steer", requestBody = input)
    }

    override suspend fun abort(conversationId: String): Result<Unit> = runCatching {
        httpCall("/v1/sessions/$conversationId/abort")
    }

    override suspend fun rollbackToMessage(conversationId: String, messageId: String): Result<Unit> = runCatching {
        httpCall(
            "/v1/sessions/$conversationId/rollback",
            requestBody = xyz.mederi.core.contract.dto.RollbackMessageInput(messageId)
        )
    }

    override suspend fun resolveQuestion(conversationId: String, questionId: String, answers: List<List<String>>): Result<Unit> = runCatching {
        httpCall(
            "/v1/sessions/$conversationId/questions/$questionId",
            requestBody = xyz.mederi.core.contract.dto.ResolveQuestionInput(answers)
        )
    }

    override suspend fun resolvePlanApproval(
        conversationId: String,
        planId: String,
        approved: Boolean,
        model: xyz.mederi.core.contract.models.ModelOption?,
        thinkingLevel: String?
    ): Result<Unit> = runCatching {
        httpCall(
            "/v1/sessions/$conversationId/plans/$planId/approve",
            requestBody = xyz.mederi.core.contract.dto.ResolvePlanApprovalInput(
                approved = approved,
                model = model,
                thinkingLevel = thinkingLevel
            )
        )
    }

    override suspend fun compressHistory(conversationId: String): Result<Unit> = runCatching {
        httpCall("/v1/sessions/$conversationId/compress")
    }

    override suspend fun listMessages(conversationId: String): Result<List<ChatMessage>> = runCatching {
        httpGet<MessagesPage>("/v1/sessions/$conversationId/messages").messages
    }

    override suspend fun listMessagesPage(conversationId: String): Result<MessagesPage> = runCatching {
        httpGet("/v1/sessions/$conversationId/messages")
    }

    override suspend fun getMessage(conversationId: String, messageId: String): Result<ChatMessage> = runCatching {
        httpGet("/v1/sessions/$conversationId/messages/$messageId")
    }

    override suspend fun listRawMessages(conversationId: String): Result<List<RawMessageDto>> = runCatching {
        httpGet("/v1/sessions/$conversationId/messages/raw")
    }

    override suspend fun getProcessStats(): Result<ProcessStats> = runCatching {
        httpGet("/v1/system/stats")
    }

    override fun events(): Flow<CoreEvent> = sseEvents("/v1/events")

    // ------------------------------------------------------------------
    // Diff
    // ------------------------------------------------------------------

    override suspend fun getFileDiffs(conversationId: String, messageId: String?): Result<List<FileDiff>> = runCatching {
        val query = messageId?.let { "?messageId=${it.encodeURLParameter()}" } ?: ""
        httpGet("/v1/sessions/$conversationId/diffs$query")
    }

    override suspend fun previewOffice(conversationId: String, path: String): Result<String> = runCatching {
        val encodedPath = path.encodeURLParameter()
        httpGet("/v1/sessions/$conversationId/office-preview?path=$encodedPath")
    }

    // ------------------------------------------------------------------
    // MCP Server 管理（遥控 REST 桥）
    // ------------------------------------------------------------------

    override suspend fun listMcpServers(): Result<List<McpServerItem>> = runCatching {
        httpGet("/v1/mcp/servers")
    }

    override suspend fun setMcpServerEnabled(name: String, enabled: Boolean): Result<Unit> = runCatching {
        httpCall("/v1/mcp/servers/${name.encodeURLParameter()}/enabled", requestBody = SetMcpServerEnabledInput(enabled))
    }

    override suspend fun installMcpServer(json: String): Result<Unit> = runCatching {
        httpCall("/v1/mcp/servers", requestBody = InstallMcpServerInput(json))
    }

    override suspend fun updateMcpServer(name: String, json: String): Result<Unit> = runCatching {
        httpCall(
            "/v1/mcp/servers/${name.encodeURLParameter()}",
            method = HttpMethod.Patch,
            requestBody = UpdateMcpServerInput(json)
        )
    }

    override suspend fun deleteMcpServer(name: String): Result<Unit> = runCatching {
        httpCall("/v1/mcp/servers/${name.encodeURLParameter()}", method = HttpMethod.Delete)
    }

    override suspend fun verifyMcpServer(name: String): Result<Unit> = runCatching {
        httpCall("/v1/mcp/servers/${name.encodeURLParameter()}/verify")
    }

    override suspend fun getMcpServerJson(name: String): Result<String> = runCatching {
        httpGet<McpServerJsonResponse>("/v1/mcp/servers/${name.encodeURLParameter()}/json").json
    }

    // ------------------------------------------------------------------
    // Skill 管理（遥控 REST 桥）
    // ------------------------------------------------------------------

    override suspend fun listSkills(): Result<List<SkillItem>> = runCatching {
        httpGet("/v1/skills")
    }

    override suspend fun getSkillsRoot(): Result<String> = runCatching {
        httpGet<SkillsRootResponse>("/v1/skills/root").path
    }

    override suspend fun setSkillsRoot(path: String): Result<Unit> = runCatching {
        httpCall("/v1/skills/root", requestBody = SetSkillsRootInput(path))
    }

    override suspend fun installSkill(url: String): Result<SkillItem> = runCatching {
        httpSend<SkillItem>("/v1/skills/install", requestBody = InstallSkillInput(url))
    }

    override suspend fun uninstallSkill(name: String): Result<Unit> = runCatching {
        httpCall("/v1/skills/${name.encodeURLParameter()}", method = HttpMethod.Delete)
    }

    // ------------------------------------------------------------------
    // AGENTS.md 生成（遥控 REST 桥）
    // ------------------------------------------------------------------

    override suspend fun generateAgentsFile(projectId: String, modelId: String?): Result<String> = runCatching {
        httpSend<xyz.mederi.core.contract.dto.GenerateAgentsFileResponse>(
            "/v1/projects/${projectId.encodeURLParameter()}/agents-file/generate",
            requestBody = xyz.mederi.core.contract.dto.GenerateAgentsFileInput(modelId)
        ).content
    }

    // ------------------------------------------------------------------
    // 子代理模型配置（遥控 REST 桥）
    // ------------------------------------------------------------------

    override suspend fun listSubagentConfigs(): Result<List<SubagentConfigItem>> = runCatching {
        httpGet("/v1/subagent-configs")
    }

    override suspend fun updateSubagentConfig(role: String, input: UpdateSubagentConfigInput): Result<Unit> = runCatching {
        httpCall(
            "/v1/subagent-configs/${role.encodeURLParameter()}",
            method = HttpMethod.Put,
            requestBody = input
        )
    }

    override suspend fun getSubagentGlobalSettings(): Result<SubagentGlobalSettings> = runCatching {
        httpGet("/v1/subagent-configs/global")
    }

    override suspend fun updateSubagentGlobalSettings(input: UpdateSubagentGlobalSettingsInput): Result<Unit> = runCatching {
        httpCall(
            "/v1/subagent-configs/global",
            method = HttpMethod.Put,
            requestBody = input
        )
    }

    override suspend fun getSubagentReport(agentId: String): Result<xyz.mederi.tools.subagent.SubagentManager.SubagentReportData> = runCatching {
        httpGet("/v1/subagents/${agentId.encodeURLParameter()}/report")
    }
}
