package xyz.mederi.core.contract

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import xyz.mederi.core.contract.dto.*
import xyz.mederi.core.contract.models.*

interface AiCore {
    val isReady: StateFlow<Boolean>
    suspend fun initialize(): Result<Unit>

    val projects: StateFlow<List<Project>>
    val providers: StateFlow<List<ProviderConfig>>
    val availableModels: StateFlow<List<ModelOption>>
    val availableAgents: StateFlow<List<AgentOption>>

    /** 内置供应商预设名列表（来自 BuiltinProviders.allEntries 展开，含端点如 "Agnes SG"）。 */
    val builtinPresets: List<String>

    /** 宿主配置/缓存基路径（如 "~/.mederi"）；纯 Web/无持久存储的客户端返回 null。 */
    val configDir: String? get() = null

    suspend fun createProject(input: CreateProjectInput): Result<Project>
    suspend fun renameProject(projectId: String, name: String): Result<Unit>
    /** 删除项目。内部遍历其下所有会话统一走 [deleteConversation]（唯一封装入口），最后删项目记录。 */
    suspend fun deleteProject(projectId: String): Result<Unit>

    suspend fun createConversation(projectId: String, agent: AgentOption? = null): Result<Conversation>
    /**
     * 删除会话的**唯一**封装入口（硬性约定，UI 层与 deleteProject 级联都只准走这里）：
     * abortAndJoin 运行中 turn → 删 session/history/diff 表 → 删 .mederi 下 Mermaid png → 清本地缓存。
     */
    suspend fun deleteConversation(conversationId: String): Result<Unit>
    suspend fun renameConversation(conversationId: String, title: String): Result<Unit>
    fun observeConversation(conversationId: String): Flow<ConversationSnapshot>
    /** 构建会话当前完整快照（不订阅事件流）。客户端建立快照流时的初始状态来源。 */
    suspend fun getSnapshot(conversationId: String): Result<ConversationSnapshot>

    suspend fun configureProvider(providerId: String, input: ProviderUpdateInput): Result<Unit>
    suspend fun addBuiltinProvider(name: String, apiKey: String): Result<ProviderConfig>
    suspend fun deleteProvider(providerId: String): Result<Unit>
    suspend fun addProviderModel(providerId: String, providerModelId: String, name: String, supportsThinking: Boolean = false, supportsImages: Boolean = false, contextWindow: Int? = null, maxTokens: Int? = null, reasoningLevels: List<String> = emptyList(), isEnabled: Boolean = true): Result<Unit>
    suspend fun updateProviderModel(providerId: String, modelId: String, name: String? = null, supportsThinking: Boolean? = null, supportsImages: Boolean? = null, contextWindow: Int? = null, maxTokens: Int? = null, reasoningLevels: List<String>? = null, isEnabled: Boolean? = null): Result<Unit>
    suspend fun deleteProviderModel(providerId: String, modelId: String): Result<Unit>
    suspend fun setModelEnabled(providerId: String, modelId: String, enabled: Boolean): Result<Unit>
    suspend fun refreshProviderModels(providerId: String): Result<List<String>>

    /**
     * 「自动设置」：显式把 models.dev 目录元数据应用到该供应商全部 FETCHED 模型
     * （目录数据进入存量模型的唯一通道——系统不做任何自动纠正；用户覆盖 supportsImagesOverride 优先）。
     *
     * @return 实际更新（字段发生变化）的模型数。
     */
    suspend fun autoSetupProviderModels(providerId: String): Result<Int>
    suspend fun createCustomProvider(input: CreateCustomProviderInput): Result<ProviderConfig>

    suspend fun addProviderApiKey(providerId: String, name: String, key: String, isDefault: Boolean = false): Result<ApiKeyOption>
    suspend fun deleteProviderApiKey(providerId: String, keyId: String): Result<Unit>
    suspend fun setDefaultProviderApiKey(providerId: String, keyId: String): Result<Unit>

    suspend fun sendMessage(conversationId: String, input: ChatPromptInput): Result<Unit>
    suspend fun steerMessage(conversationId: String, input: ChatPromptInput): Result<Unit>
    suspend fun abort(conversationId: String): Result<Unit>
    suspend fun rollbackToMessage(conversationId: String, messageId: String): Result<Unit>
    suspend fun resolveQuestion(conversationId: String, questionId: String, answers: List<List<String>>): Result<Unit>

    /**
     * 回复计划审批。
     *
     * @param model 批准时刻输入框选中的模型：非 null 且批准时，core 把它写入 session
     * （"最后一次选择"语义）——create_plan 挂起等批准期间用户可能切了模型，
     * 同一 turn 后续 spawn_agent 动态读 session，子代理按批准时刻的模型执行。
     * null = 不改变（旧客户端兼容，行为同以往）。
     * @param thinkingLevel 批准时刻的推理档位，与 [model] 成对（唯一真理源 effectiveThinkingLevel）。
     */
    suspend fun resolvePlanApproval(
        conversationId: String,
        planId: String,
        approved: Boolean,
        model: ModelOption? = null,
        thinkingLevel: String? = null
    ): Result<Unit>
    suspend fun compressHistory(conversationId: String): Result<Unit>
    suspend fun listMessages(conversationId: String): Result<List<ChatMessage>>
    /** 消息页：消息列表 + token 统计（快照流对齐落库数据用；wasmJs 无法从契约消息重算 token）。 */
    suspend fun listMessagesPage(conversationId: String): Result<MessagesPage>
    suspend fun getMessage(conversationId: String, messageId: String): Result<ChatMessage>
    /** 原始消息直读（调试用）：落库时的原始 JSON payload，不解析不映射。 */
    suspend fun listRawMessages(conversationId: String): Result<List<RawMessageDto>>

    /**
     * 宿主进程资源真实占用（CPU / 堆 / RSS，诊断"卡是程序还是机器"）。
     * desktop/server 返回本进程；遥控端经 REST 拿到的是被遥控端 server 进程的数据。
     * UI 建议 500ms~1s 轮询；失败（Result）时按无数据显示，勿重试风暴。
     */
    suspend fun getProcessStats(): Result<ProcessStats>

    fun events(): Flow<CoreEvent>

    suspend fun getFileDiffs(conversationId: String, messageId: String? = null): Result<List<FileDiff>>

    /**
     * 将 Office 文档（.docx/.xlsx/.pptx）转换为 HTML，供 UI 内置浏览器预览。
     * path 解析与会话所属项目目录同源。返回完整 HTML 文档字符串。
     */
    suspend fun previewOffice(conversationId: String, path: String): Result<String>

    // ==========================================
    // MCP Server 管理契约
    // ==========================================
    suspend fun listMcpServers(): Result<List<McpServerItem>> = Result.success(emptyList())
    suspend fun setMcpServerEnabled(name: String, enabled: Boolean): Result<Unit> = Result.success(Unit)
    suspend fun installMcpServer(json: String): Result<Unit> = Result.success(Unit)
    suspend fun updateMcpServer(name: String, json: String): Result<Unit> = Result.success(Unit)
    suspend fun deleteMcpServer(name: String): Result<Unit> = Result.success(Unit)
    suspend fun verifyMcpServer(name: String): Result<Unit> = Result.success(Unit)
    suspend fun getMcpServerJson(name: String): Result<String> = Result.success("{}")

    // ==========================================
    // Skill 管理契约（UI 薄触发，文件操作全在 core）
    // ==========================================
    suspend fun listSkills(): Result<List<SkillItem>> = Result.success(emptyList())
    suspend fun getSkillsRoot(): Result<String> = Result.success("")
    suspend fun setSkillsRoot(path: String): Result<Unit> = Result.success(Unit)
    suspend fun installSkill(url: String): Result<SkillItem> = Result.failure(IllegalStateException("skills 未启用"))
    suspend fun uninstallSkill(name: String): Result<Unit> = Result.success(Unit)

    // ==========================================
    // 子代理模型配置契约
    // ==========================================
    suspend fun listSubagentConfigs(): Result<List<SubagentConfigItem>> = Result.success(emptyList())
    suspend fun updateSubagentConfig(role: String, input: UpdateSubagentConfigInput): Result<Unit> = Result.success(Unit)
    suspend fun getSubagentReport(agentId: String): Result<xyz.mederi.tools.subagent.SubagentManager.SubagentReportData> =
        Result.failure(xyz.mederi.api.exception.MederiNotFoundException("Subagent report not found for agent: $agentId"))

    // ==========================================
    // AGENTS.md 生成契约（API 形态，暂无命令/UI 入口）
    // ==========================================

    /**
     * 扫描项目并生成（已存在则原地改进）项目根的 AGENTS.md，返回写入的内容。
     * 读取与注入是代码级自动完成的（系统提示词 + 工具懒发现），本方法只提供"生成/改进"能力。
     *
     * @param projectId 项目 ID。
     * @param modelId 生成用模型 ID；null 时用项目最近会话选用的模型，都没有则 failure。
     */
    suspend fun generateAgentsFile(projectId: String, modelId: String? = null): Result<String> =
        Result.failure(IllegalStateException("AGENTS.md 生成未启用"))
}
