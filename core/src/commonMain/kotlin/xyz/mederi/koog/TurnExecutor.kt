package xyz.mederi.infrastructure.koog

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.chatMemory.feature.ChatMemoryPreProcessor
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.utils.time.KoogClock
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.ext.agent.HistoryCompressionConfig
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.model.PromptExecutorBuilder
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.serialization.kotlinx.toKotlinxJsonElement
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import xyz.mederi.api.AgentConfig
import xyz.mederi.api.SendMessageRequest
import xyz.mederi.debug.DebugLog
import xyz.mederi.debug.ErrorCollector
import xyz.mederi.debug.ErrorContext
import xyz.mederi.debug.ErrorSeverity
import xyz.mederi.debug.StreamCloseDiagnostics
import xyz.mederi.debug.StreamTimingLog
import xyz.mederi.debug.StreamTrace
import xyz.mederi.domain.model.AgentCapabilities
import xyz.mederi.mcp.engine.McpConnector
import xyz.mederi.mcp.engine.McpSession
import xyz.mederi.provider.infrastructure.koog.retry.LlmRetryConfig
import xyz.mederi.provider.infrastructure.koog.retry.RetryableLLMClient
import xyz.mederi.provider.infrastructure.koog.sanitize.MederiOpenAILLMClient
import xyz.mederi.tools.contextUsedTokens
import xyz.mederi.domain.model.AIModel
import xyz.mederi.domain.model.AgentMode
import xyz.mederi.domain.model.EventType
import xyz.mederi.domain.model.MederiEvent
import xyz.mederi.domain.model.Message
import xyz.mederi.domain.model.UI_HIDDEN_MARKER
import xyz.mederi.domain.model.MessagePart
import xyz.mederi.domain.model.MessageRole
import xyz.mederi.domain.model.MessageStatus
import xyz.mederi.domain.model.SessionStatus
import xyz.mederi.domain.model.SubagentRole
import xyz.mederi.koog.MANUAL_KEEP_LAST_MESSAGES
import xyz.mederi.koog.manualCompactionSkipReason
import xyz.mederi.project.ProjectManager
import xyz.mederi.prompt.EnvironmentInfoProvider
import xyz.mederi.prompt.SystemPrompts
import xyz.mederi.provider.API_KEY_ROTATION_ID
import xyz.mederi.provider.ApiKeyResolver
import xyz.mederi.provider.ProviderManager
import xyz.mederi.provider.domain.model.ReasoningLevel
import xyz.mederi.provider.infrastructure.koog.KoogClientFactory
import xyz.mederi.provider.infrastructure.koog.KoogModelBuilder
import xyz.mederi.provider.infrastructure.koog.KoogParamsBuilder
import xyz.mederi.store.DiffStore
import xyz.mederi.tools.diff.FileDiffSummary
import xyz.mederi.tools.diff.TurnDiffSummary
import xyz.mederi.tools.diff.countChanges
import xyz.mederi.store.HistoryStore
import xyz.mederi.store.SessionStore
import xyz.mederi.skills.SkillManager
import xyz.mederi.tools.CustomToolRegistry
import xyz.mederi.tools.ToolFactory
import xyz.mederi.tools.diff.TurnDiffTracker
import xyz.mederi.tools.subagent.SubagentConfigManager
import xyz.mederi.tools.subagent.SubagentManager
import xyz.mederi.tools.subagent.SubagentRunnerImpl
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 工具参数/结果序列化器（AIAgentConfig 用）。
 *
 * Koog JVM 的 AIAgentConfigBuilder 默认 JacksonSerializer()——裸 ObjectMapper 没注册
 * KotlinModule，无法反序列化 Kotlin data class（症状："no Creators, like default
 * constructor, exist"，所有带嵌套参数的工具调用必炸，如 write_plan）。
 * 显式换 KotlinxSerializer：与工具 Args 的 kotlinx @Serializable 同源，且
 * ignoreUnknownKeys/coerceInputValues/explicitNulls=false 对 LLM 生成的参数健壮
 * （多传字段忽略、null 当缺省、非法枚举回退默认）。
 */
internal val mederiToolSerializer = ai.koog.serialization.kotlinx.KotlinxSerializer(
    kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }
)

/**
 * Turn 执行器。
 *
 * 每次 sendMessage 创建一个 Koog AIAgent，跑完销毁。
 * Session 只记录 agentMode 和用户最后一次选择的 aiModel / reasoningLevel。
 * 系统提示词由 agentMode 通过 [SystemPrompts.build] 现算。
 *
 * 使用 AIAgent 的 EventHandler 拦截 LLM 流式输出帧，通过 eventBus 发送 MESSAGE_DELTA。
 * 对话历史通过 ChatMemory + HistoryStoreChatHistoryProvider 按 sessionId 管理。
 * AIAgent 本身无会话状态，跑完即销毁。
 *
 * @param sessionStore Session 存储。
 * @param historyStore 对话历史存储。
 * @param eventBus 事件总线。
 * @param providerManager 供应商管理器。
 * @param projectManager 项目管理器。
 */
class TurnExecutor(
    private val sessionStore: SessionStore,
    private val historyStore: HistoryStore,
    private val eventBus: MutableSharedFlow<MederiEvent>,
    private val providerManager: ProviderManager,
    private val projectManager: ProjectManager,
    private val diffStore: DiffStore? = null,
    private val mcpConnector: McpConnector? = null,
    private val skills: SkillManager? = null,
    private val scope: CoroutineScope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() +
        CoroutineExceptionHandler { _, error ->
            DebugLog.error("TurnExec", "Uncaught background error: ${error.message}", error)
        }
    ),
    /** 执行器子代理的文件写入回调（SubagentRunnerImpl 挂，收集 touched files）。 */
    private val onFileTouched: ((String) -> Unit)? = null,
    /**
     * turn 结束时回传本 turn 的 [xyz.mederi.tools.diff.TurnDiff]（buildDiff 之后调用）。
     * SubagentRunnerImpl 挂此回调，把子代理 turn 的文件改动经 ParentDiffRegistry
     * 合并进父会话当前活跃 turn 的 tracker——修复"turn 改动摘要不含子代理改动"。
     */
    private val onTurnDiff: ((xyz.mederi.tools.diff.TurnDiff) -> Unit)? = null,
    private val subagentConfigManager: SubagentConfigManager? = null,
    private val compactionThresholdPercent: () -> Int = { 70 },
    /**
     * 浏览器任务运行目录解析（browserHome 懒解析，每次浏览器任务调用；null = BrowserTaskManager
     * 用默认 workingDir/recipeStore）。由 Mederi 装配注入，用户改 browserHome 后下一任务即生效。
     */
    private val browserRuntimeProvider: (suspend () -> xyz.mederi.browser.BrowserRuntimeDirs?)? = null,
    /** LLM 传输实现可注入；运行时调度与测试共用同一真实 Koog 工具循环。 */
    private val llmClientFactory: (xyz.mederi.provider.domain.model.Provider, String) -> ai.koog.prompt.executor.clients.LLMClient = KoogClientFactory::create,
) {

    /**
     * API Key 统一解析器（唯一真理源）。记忆为进程级静态共享（见 ApiKeyResolver），
     * 故本实例与子代理 TurnExecutor / BrowserLLMHelper 等实例记忆互通。
     * 内部消息轮构造 SendMessageRequest 时用 [ApiKeyResolver.currentKeyId] 填当前选定 key。
     */
    private val apiKeyResolver = ApiKeyResolver(providerManager)

    private val subagentRunner = SubagentRunnerImpl(
        providerManager = providerManager,
        projectManager = projectManager,
        mcpConnector = mcpConnector,
        skills = skills,
        compactionThresholdPercent = compactionThresholdPercent  // 新增
    )

    private val subagentManager = SubagentManager(
        subagentRunner = subagentRunner,
        scope = scope,
        // 生命周期事件总线：SUBAGENT_STARTED/COMPLETED/ERROR/STOPPED（UI 子代理面板消费）
        eventBus = eventBus,
        // 单会话并发上限（设置页 Agents 面板配置，默认 2）：每次 spawn 现读设置存储，改设置即时生效
        maxConcurrentProvider = subagentConfigManager?.let { cfg -> { cfg.getMaxConcurrentSubagents() } }
    )

    // 后台任务管理（Command / Timer）：照搬 subagentManager 旁——挂在全局 scope 上，
    // 生命周期事件（BACKGROUND_TASK_STARTED/COMPLETED/ERROR/STOPPED）经 eventBus 发射，
    // 终态时本类组装 <event_message> 唤醒父 turn；abort/rollback 级联收割（stopAllForSession）。
    private val backgroundTaskManager = xyz.mederi.tools.background.BackgroundTaskManager(
        scope = scope,
        eventBus = eventBus
    )

    /** 事件消息等待队列（按 parentSessionId 分组）：子代理终态时暂存，父 turn 空闲时冲刷唤醒新 turn。 */
    private val pendingEventMessages = ConcurrentHashMap<String, MutableList<String>>()
    private val eventMessageLock = Mutex()
    // 子任务状态先变终态、事件稍后才到达；确认事件已入队前不可提前交付。
    private val observedSubagentTerminals = ConcurrentHashMap.newKeySet<String>()
    private val suppressedSubagentTerminals = ConcurrentHashMap.newKeySet<String>()

    private fun hasPendingSubagentWork(sessionId: String): Boolean =
        subagentManager.listForSession(sessionId).any { agent ->
            !agent.isDiscarded && agent.agentId !in suppressedSubagentTerminals &&
                (agent.status == SubagentManager.SubagentStatus.RUNNING || agent.agentId !in observedSubagentTerminals)
        } || !pendingEventMessages[sessionId].isNullOrEmpty()


    /** 引导消息等待队列（按 sessionId 分组）：用户在 turn 运行时插话，工具边界时注入 prompt。 */
    private val inputLocks = ConcurrentHashMap<String, Mutex>()
    private val activeTurnIds = ConcurrentHashMap<String, String>()
    private data class WorkClock(val turnId: String, val startedAt: Long, val completedAt: Long? = null)
    private val workClocks = ConcurrentHashMap<String, WorkClock>()

    private val pendingSteerings = ConcurrentHashMap<String, java.util.concurrent.ConcurrentLinkedQueue<SteeringItem>>()

    /** 当前正在进行上下文压缩的会话集合（排他互斥，防止压缩被新轮次/事件抢占打断）。 */
    private val compactingSessions = ConcurrentHashMap.newKeySet<String>()

    fun isCompacting(sessionId: String): Boolean = compactingSessions.contains(sessionId)

    /** runTurn catch 块对 PREMATURE_CLOSE 的重抛信号——携带已收集的 errorPayload，供 auto-continue 循环决策。 */
    private class PrematureCloseSignal(val errorPayload: Map<String, String>, cause: Throwable) : RuntimeException(cause)

    companion object {
        /** 断流自动续写最大重试次数（主代理与子代理共享此唯一真理源）。 */
        private const val AUTO_CONTINUE_MAX_RETRIES = 1
    }

    init {
        // 监听系统事件总线：当子代理（或未来其他异步任务）产生终态事件时，组装 <event_message> 唤醒父 turn
        if (eventBus != null) {
            scope.launch {
                eventBus.collect { event ->
                    when (event.type) {
                        EventType.QUESTION_REQUESTED,
                        EventType.PLAN_APPROVAL_REQUESTED -> {
                            // 输入可能早于挂起到达，不能等用户再发一次才解除等待。
                            pendingSteerings[event.sessionId]?.peek()?.let { pending ->
                                scope.launch { sendMessage(event.sessionId, pending.request) }
                            }
                        }
                        EventType.SUBAGENT_COMPLETED,
                        EventType.SUBAGENT_ERROR,
                        EventType.SUBAGENT_STOPPED -> {
                            handleSubagentTerminalEvent(event)
                        }
                        EventType.BACKGROUND_TASK_COMPLETED,
                        EventType.BACKGROUND_TASK_ERROR -> {
                            handleBackgroundTaskTerminalEvent(event)
                        }
                        EventType.BROWSER_TASK_COMPLETED,
                        EventType.BROWSER_TASK_ERROR,
                        EventType.BROWSER_TASK_STOPPED -> {
                            handleBrowserTaskTerminalEvent(event)
                        }
                        // BACKGROUND_TASK_STOPPED 不唤醒 AI：用户手动停止任务后
                        // 通常想自己说话，无需 AI 被推过来。停止信息经
                        // EnvironmentInfoProvider 注入下轮 Runtime Environment 段，
                        // AI 在用户下次发消息时自然得知。
                        else -> { /* 其余事件暂不转化为 event_message */ }
                    }
                }
            }
        }
    }

    // 浏览器任务管理：持有 BrowserRegistry（UI 注册 JCEF、core 注册 Camoufox，启动装配时填充）。
    // 主代理获得 run_browser_task / browser_task_status / stop_browser_task 三个工具；
    // 注册表为空时 runTask 返回明确错误引导用户配置。
    // ST3 已接线：browserRuntimeProvider 由 Mederi 装配注入（browserHome 懒解析 → reports/skills），
    // BrowserTaskManager.runTask 逐任务解析 workingDir/recipeStore 供 DrillExecutor/Brain/RecipeStore。
    private val browserTaskManager: xyz.mederi.browser.BrowserTaskService? =
        xyz.mederi.browser.BrowserTaskManager(
            providerManager = providerManager,
            scope = scope,
            eventBus = eventBus,
            subagentConfigManager = subagentConfigManager,
            runtimeProvider = browserRuntimeProvider
        )

    private val activeJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    // AGENTS.md 加载器：指令链注入（每轮 turn）+ 子树懒发现（工具访问触发，经 ToolFactory 传入 registry）
    private val agentsFileLoader = xyz.mederi.project.AgentsFileLoader()

    // 会话级懒发现去重：sessionId → 已注入过的 AGENTS.md 绝对路径。v1 内存态，不持久化。
    private val agentsDiscovered = ConcurrentHashMap<String, MutableSet<String>>()

    private val questionRequesters = ConcurrentHashMap<String, xyz.mederi.question.QuestionRequester>()
    private val planApprovalRequesters = ConcurrentHashMap<String, xyz.mederi.plan.PlanApprovalRequester>()

    /**
     * 包装 [HistoryStoreChatHistoryProvider]，记录 turn 期间 [ChatHistoryProvider.store] 是否被调用。
     *
     * durable-first 后用户消息在 turn 启动前已由 [runTurn] 落库，
     * 此包装仅剩观测用途（日志判断回写时机）。
     */
    private class TrackingHistoryProvider(
        private val delegate: HistoryStoreChatHistoryProvider
    ) : ChatHistoryProvider {
        @Volatile
        var storeCalled = false
            private set

        override suspend fun load(conversationId: String): List<ai.koog.prompt.message.Message> =
            delegate.load(conversationId)

        override suspend fun store(conversationId: String, messages: List<ai.koog.prompt.message.Message>) {
            DebugLog.event("TurnExec", "ChatMemory.store: conversationId=$conversationId, messages=${messages.size}")
            delegate.store(conversationId, messages)
            storeCalled = true
        }
    }
    /**
     * 发送消息并触发 Agent 运行。
     *
     * durable-first：用户消息在 Agent 启动前先落库（[runTurn] 内），乐观更新的
     * 持久化兜底——网络/模型失败时用户输入已在 HistoryStore，不会丢失。
     * ChatMemory 回写按内容指纹 reconcile，已落库的消息不会被重复追加。
     * AI 的回复文本/推理通过 [MESSAGE_DELTA] 事件流式输出，
     * 完成后通过 [MESSAGE_COMPLETED] 事件告知。调用方可通过 HistoryStore / listMessages 读取完整历史。
     */
    private class InteractionChanged : RuntimeException()

    /** 唯一输入入口：接收与调度由 Core 决定，客户端无需判断是否正在运行。 */
    suspend fun sendMessage(sessionId: String, request: SendMessageRequest, subagentRole: SubagentRole? = null) {
        val stable = request.copy(messageId = request.messageId ?: "msg_${UUID.randomUUID()}")
        var continuingClock: WorkClock? = null
        while (true) {
            // 已消费的同 ID 重试不应解除正在等待的其他问题或审批。
            if (historyStore.load(sessionId).any { it.id == stable.messageId && !it.pendingSteering }) return
            val waitingQuestion = questionRequesters[sessionId]?.hasPending() == true
            val waitingPlan = planApprovalRequesters[sessionId]?.hasPending() == true
            if (waitingQuestion || waitingPlan) {
                continuingClock = continuingClock ?: workClocks[sessionId]?.takeIf { it.completedAt == null }
                if (waitingPlan) injectNeutralPlanToolResult(sessionId)
                if (waitingQuestion) injectAskUserDeclinedToolResult(sessionId)
                abortAndJoin(sessionId)
            }
            try {
                inputLocks.computeIfAbsent(sessionId) { Mutex() }.withLock {
                    val existing = historyStore.load(sessionId).firstOrNull { it.id == stable.messageId }
                    if (existing != null && !existing.pendingSteering) return@withLock
                    val session = sessionStore.get(sessionId)
                        ?: throw NoSuchElementException("Session not found: $sessionId")
                    val waiting = questionRequesters[sessionId]?.hasPending() == true ||
                        planApprovalRequesters[sessionId]?.hasPending() == true
                    if (waiting) throw InteractionChanged()
                    val running = activeJobs[sessionId]?.isActive == true || session.status == SessionStatus.RUNNING
                    if (running || compactingSessions.contains(sessionId)) {
                        if (existing != null) return@withLock
                        val message = buildUserMessage(sessionId, stable.parts).copy(
                            id = stable.messageId, pendingSteering = true, turnId = activeTurnIds[sessionId],
                            workStartedAt = workClocks[sessionId]?.startedAt
                        )
                        historyStore.append(sessionId, message)
                        pendingSteerings.computeIfAbsent(sessionId) { java.util.concurrent.ConcurrentLinkedQueue() }
                            .offer(SteeringItem(sessionId,
                                stable.parts.filterIsInstance<MessagePart.Text>().joinToString("\n") { it.text },
                                stable, System.currentTimeMillis(), stable.messageId, message.parts))
                        emit(sessionId, EventType.SESSION_UPDATED, payload = mapOf("inputAccepted" to "true"))
                        return@withLock
                    }
                    // 新输入中性解除挂起属于原工作，不能把墙钟起点重置成“再次发请求”。
                    continuingClock?.takeIf { activeTurnIds[sessionId] == null }?.let { clock ->
                        activeTurnIds[sessionId] = clock.turnId
                        workClocks[sessionId] = clock
                        historyStore.updateHistory(sessionId) { messages ->
                            messages.map { if (it.turnId == clock.turnId) it.copy(workCompletedAt = null) else it }
                        }
                    }
                    // 重启/异常后仍保留已接收的输入，下次工作一并读取。
                    historyStore.load(sessionId).filter { it.pendingSteering }.forEach { pending ->
                        historyStore.updateMessage(sessionId, pending.id!!) { it.copy(pendingSteering = false) }
                    }
                    pendingSteerings.remove(sessionId)
                    try {
                        sendMessageInternal(sessionId, stable, subagentRole)
                    } catch (e: Throwable) {
                        if (e is InteractionChanged || e is kotlinx.coroutines.CancellationException) throw e
                        val record = ErrorCollector.collect(e, ErrorContext(phase = "send_message", sessionId = sessionId))
                        emit(sessionId, EventType.MESSAGE_ERROR, payload = record.toPayload())
                        throw e
                    }
                }
                return
            } catch (_: InteractionChanged) {
                // 挂起在检查与加锁之间发生：释放接收锁后解除，禁止持锁 join。
            }
        }
    }

    /** 兼容旧客户端；没有第二种发送/排队模式。 */
    suspend fun steerMessage(sessionId: String, request: SendMessageRequest) = sendMessage(sessionId, request)

    fun pollSteering(sessionId: String): SteeringItem? = pendingSteerings[sessionId]?.poll()

    private suspend fun consumeSteering(sessionId: String, item: SteeringItem) {
        item.messageId?.let { id ->
            historyStore.updateMessage(sessionId, id) { it.copy(pendingSteering = false) }
        }
        emit(sessionId, EventType.SESSION_UPDATED, payload = mapOf("inputAccepted" to "true"))
    }

    private suspend fun sendMessageInternal(sessionId: String, request: SendMessageRequest, subagentRole: SubagentRole? = null) {
        val session = sessionStore.get(sessionId)
            ?: throw NoSuchElementException("Session not found: $sessionId")

        DebugLog.section("TurnExec", "TurnExecutor.sendMessageInternal")
        DebugLog.data("TurnExec", "sessionId", sessionId)
        DebugLog.data("TurnExec", "session.status", session.status)
        DebugLog.data("TurnExec", "session.agentMode", session.agentMode)
        DebugLog.data("TurnExec", "session.aiModel", "${session.aiModel?.id} (${session.aiModel?.name}), providerModelId=${session.aiModel?.providerModelId}")
        DebugLog.data("TurnExec", "session.reasoningLevel", session.reasoningLevel)

        if (compactingSessions.contains(sessionId)) {
            throw IllegalStateException(
                "Session $sessionId is currently compacting. Wait for it to complete or abort it first."
            )
        }

        if (questionRequesters[sessionId]?.hasPending() == true || planApprovalRequesters[sessionId]?.hasPending() == true) {
            throw InteractionChanged()
        }
        check(activeJobs[sessionId]?.isActive != true) { "Session $sessionId has an active turn in progress." }
        check(session.status != SessionStatus.RUNNING) { "Session $sessionId is already running a turn." }

        val agentMode = request.agentConfig.agentMode
        // 唯一真理源 = 本次发送携带的 apiKeyId（null = 用该供应商默认 key）；本 turn 全链路继承
        val activeApiKeyId = request.apiKeyId
        val project = projectManager.get(session.projectId)
            ?: throw IllegalStateException("Project not found: ${session.projectId}")
        val projectDirs = listOf(project.directory)
        val planStore = xyz.mederi.plan.PlanStore(projectDirs)
        val notebook = xyz.mederi.plan.Notebook(projectDirs)
        val activePlan = planStore.loadBySession(sessionId)
        val activePlanContent = activePlan?.let { p ->
            // 方案A：activePlan 段只挂"动态状态"——主 agent 每轮编排决策（spawn 下一个/verify/converge）
            // 真正需要的实时信息：进度计数、当前指针、最近一次验证结果。子任务的静态详情
            // （brief/targetFiles/verification 命令/decisions）随 plan.md 落盘在 .mederi/plans/{planId}/plan.md，
            // 主 agent 需要时 read_file 即可——避免每轮把全量静态骨架重发进 system prompt。
            // 活跃子任务的 spec 仍挂（spawn 前主 agent 确认要用）；其余子任务的 spec 留磁盘。
            buildString {
                appendLine("Title: ${p.title}")
                appendLine("Status: ${p.status}")
                if (p.businessLogic.isNotBlank()) {
                    appendLine("Business Logic: ${p.businessLogic}")
                }
                val passed = p.subtasks.count { it.status == xyz.mederi.plan.SubtaskStatus.COMPLETED }
                val failed = p.subtasks.count { it.status == xyz.mederi.plan.SubtaskStatus.FAILED }
                val pending = p.subtasks.count { it.status == xyz.mederi.plan.SubtaskStatus.PENDING }
                val inProgress = p.subtasks.count { it.status == xyz.mederi.plan.SubtaskStatus.IN_PROGRESS }
                appendLine("Progress: $passed passed, $failed failed, $pending pending, $inProgress in-progress")
                // 指针行：给模型 todo 式的焦点（"正在做哪个/下一个做哪个"）
                val activeSubtask = p.currentSubtask ?: p.nextPending
                activeSubtask?.let { appendLine("Current: Subtask ${it.index + 1} [${it.status}]: ${it.name}") }
                // 子任务状态速览：一行一个，只挂 status + name（静态详情见 plan.md）
                p.subtasks.forEach { st ->
                    appendLine("- [${st.status}] Subtask ${st.index + 1}: ${st.name}")
                    // 活跃子任务的 spec 单独挂（spawn 前主 agent 确认要用）；其余子任务 spec 留磁盘
                    when {
                        st.index == activeSubtask?.index && !st.spec.isNullOrBlank() ->
                            appendLine("  Spec (active): ${st.spec}")
                        st.spec.isNullOrBlank() ->
                            appendLine("  Spec: (not generated yet — call generate_spec before spawning)")
                        else ->
                            appendLine("  Spec: (stored on disk; mounted only for the active subtask)")
                    }
                    // 最近一次验证结果：主 agent converge / 追加修订 决策的直接依据
                    st.verificationResult?.let { r ->
                        appendLine("  Last result: ${r.status}" +
                            (r.rootCause?.let { c -> " (root cause: $c)" } ?: "") +
                            (if (r.machineMismatch)
                                " [MACHINE/MODEL CONTRADICTION — abnormal; you must tell the user]"
                            else ""))
                        appendLine("  Evidence: ${r.evidence}")
                    }
                }
                // 静态详情路径指针（read_file 按需取 brief/targetFiles/verification/decisions/spec 全文）
                val planMdPath = planStore.getPlanRelativePath(p.id)
                if (planMdPath != null) {
                    appendLine("Static details (brief/files/verification/decisions): read_file $planMdPath")
                    appendLine("Research notes (if researcher ran): read_file ${planMdPath.removeSuffix("plan.md")}research.md")
                }
            }
        }
        // 无活跃计划且模型维护过 todo 时挂载轻量进度段（有 Plan 时 Plan 即 tracker，不挂第二份）。
        // session 实例在 turn 开始时读取，本段按 turn 边界刷新；turn 内模型调 update_todo 后
        // 状态就在对话历史里，无需重建 prompt
        val activeTodoContent = if (activePlan == null && session.todos.isNotEmpty()) buildString {
            session.todos.forEach { appendLine("- [${it.status.name}] ${it.content}") }
            val done = session.todos.count { it.status == xyz.mederi.domain.model.TodoStatus.COMPLETED }
            appendLine("Progress: $done/${session.todos.size} completed.")
        }.trimEnd() else null
        // 子代理用专用执行者/研究者提示词（无 plan/spawn/verify 工具，主代理工作流指令对它全是误导）；
        // 主代理用完整提示词（含工作流段）。
        // 是否注入已安装 skills：一律读中心化 AgentCapabilities 表（主代理 + EXECUTOR 注入，RESEARCHER 不注入）。
        val basePrompt = if (subagentRole != null) SystemPrompts.forSubagent(subagentRole)
        else SystemPrompts.build(agentMode)
        val systemPromptWithSkills = if (AgentCapabilities.of(subagentRole).inheritSkills) {
            val skillList = runCatching { skills?.list() }.getOrElse { e ->
                DebugLog.error("TurnExec", "加载 skills 列表失败（不阻塞 turn）: ${e.message}", e)
                null
            }
            // 读会话已激活 skill 全文，注入系统提示词（不进对话历史，不参与压缩）
            val activeFullTexts = session.activeSkills.mapNotNull { name ->
                runCatching { skills?.loadFullText(name) }
                    .onFailure { e -> DebugLog.error("TurnExec", "加载 skill 全文失败: name=$name, ${e.message}", e) }
                    .getOrNull()
                    ?.let { name to it }
            }
            SystemPrompts.withSkillsAndActive(basePrompt, skillList.orEmpty(), activeFullTexts)
        } else basePrompt
        // AGENTS.md 指令链注入（代码级自动读取）：git 根 → 项目目录浅→深。
        // 每轮重建（文件可能被用户/模型改过）；读取失败不阻塞 turn。
        // 仅主代理（subagentRole == null）注入；子代理（executor/researcher）不注入——
        // 项目规则由主代理在 spec 中编入。
        val systemPromptBase = if (subagentRole == null) {
            runCatching {
                SystemPrompts.withProjectRules(
                    systemPromptWithSkills,
                    agentsFileLoader.loadInstructionChain(project.directory)
                )
            }.getOrElse { e ->
                DebugLog.error("TurnExec", "加载 AGENTS.md 失败（不阻塞 turn）: ${e.message}", e)
                systemPromptWithSkills
            }
        } else {
            systemPromptWithSkills
        }
        // 装配顺序不变量 = 静态骨架 → skills → AGENTS.md → 动态 plan/todo + 环境信息后缀。
        // 顺序被 TurnExecutorPromptOrderTest 锁定（OpenAI 按最长前缀缓存，动态内容必须永远在最后）。
        val commandSandbox = xyz.mederi.tools.sandbox.CommandSandbox(projectDirs)
        val planTodoSuffix = if (subagentRole == null) SystemPrompts.dynamicSuffix(activePlanContent, activeTodoContent) else ""
        val stoppedTasks = backgroundTaskManager.consumeStoppedForSession(sessionId)
        val envSuffix = EnvironmentInfoProvider.build(commandSandbox, projectDirs, stoppedTasks)
        val dynamicSuffix = planTodoSuffix + envSuffix
        val systemPrompt = systemPromptBase + dynamicSuffix
        DebugLog.data("TurnExec", "agentMode", agentMode)
        DebugLog.data("TurnExec", "activePlan", activePlan?.id ?: "none")
        DebugLog.data("TurnExec", "systemPrompt (first 100)", "${systemPrompt.take(100)}...")

        // 解析最终模型和推理等级
        val effectiveModel = request.agentConfig.aiModel ?: session.aiModel
            ?: throw NoSuchElementException("No AI model selected for session $sessionId")
        val effectiveReasoningLevel = request.agentConfig.reasoningLevel
            ?: session.reasoningLevel
            ?: ReasoningLevel.NONE

        DebugLog.data("TurnExec", "effectiveModel", "${effectiveModel.id} (${effectiveModel.name}), providerModelId=${effectiveModel.providerModelId}")
        DebugLog.data("TurnExec", "effectiveModel.supportsReasoning", effectiveModel.supportsReasoning)
        DebugLog.data("TurnExec", "effectiveModel.contextWindow", "${effectiveModel.contextWindow}, maxTokens = ${effectiveModel.maxTokens}")
        DebugLog.data("TurnExec", "effectiveReasoningLevel", effectiveReasoningLevel)

        val provider = providerManager.listWithoutKeys()
            .firstOrNull { p -> p.models.any { it.id == effectiveModel.id } }
            ?: throw NoSuchElementException("Provider for model ${effectiveModel.id} not found")
        val model = provider.getModel(effectiveModel.id)
            ?: throw NoSuchElementException("Model not found: ${effectiveModel.id}")

        DebugLog.data("TurnExec", "provider", "${provider.id} (${provider.name}), type = ${provider.type}")
        DebugLog.data("TurnExec", "provider.baseUrl", provider.baseUrl)
        DebugLog.data("TurnExec", "provider.responseSanitization", provider.responseSanitization)
        DebugLog.data("TurnExec", "provider.reasoningParameter", provider.reasoningParameter)
        DebugLog.data("TurnExec", "model (from provider)", "${model.id} (${model.name}), providerModelId=${model.providerModelId}")

        // 回写 Session 的 Agent 配置（记住用户最后一次选择）：
        // apiKeyId 是 UI 层数据，由 updateComposeState 唯一写入；TurnExecutor 不回写，
        // 避免内部消息轮（计划批准/事件唤醒）用 currentKeyId() 取到的残留值覆盖用户选择。
        sessionStore.updateAgentConfig(
            sessionId,
            agentMode = agentMode,
            aiModel = effectiveModel,
            reasoningLevel = effectiveReasoningLevel,
        )

        val inputText = request.parts.filterIsInstance<MessagePart.Text>()
            .joinToString("") { it.text }

        // ── durable-first：用户消息先落库，再启动 Agent ──
        // 乐观更新的持久化兜底：网络/模型/工具在 turn 任何阶段失败，用户输入都已在
        // HistoryStore，不会丢失。消息带 UI 隐藏时间戳——存的就是发的，
        // ChatMemory load 时已落库的消息自然进入 prompt；回写 reconcile 按内容指纹对齐，
        // 不会重复追加。append 同步执行且先于 SESSION_UPDATED 事件，
        // 发送时机的监听者（如自动改名）回查快照必然能看到本条消息。
        // 环境元数据（OS/Shell/沙箱等）已移至系统提示词，不在此落库。
        val turnId = activeTurnIds.computeIfAbsent(sessionId) { "turn_${UUID.randomUUID()}" }
        val workClock = workClocks.compute(sessionId) { _, existing ->
            if (existing?.turnId == turnId) existing else WorkClock(turnId, System.currentTimeMillis())
        }!!
        val existingInput = historyStore.load(sessionId).firstOrNull { it.id == request.messageId }
        val userMessage = existingInput ?: buildUserMessage(sessionId, request.parts).copy(
            id = request.messageId, turnId = turnId, workStartedAt = workClock.startedAt)
        if (existingInput == null) historyStore.append(sessionId, userMessage)
        else if (existingInput.workStartedAt == null || existingInput.turnId != turnId) {
            historyStore.updateMessage(sessionId, existingInput.id!!) {
                it.copy(turnId = turnId, workStartedAt = workClock.startedAt, workCompletedAt = null)
            }
        }

        sessionStore.update(sessionId, SessionStatus.RUNNING)
        emit(sessionId, EventType.SESSION_UPDATED)

        val planApprovalRequester = xyz.mederi.plan.PlanApprovalRequester(sessionId, eventBus)
        planApprovalRequesters[sessionId] = planApprovalRequester

        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            var autoContinueAttempt = 0
            var finalized = false
            try {
            while (true) {
                try {
                    val outcome = runTurn(
                        sessionId = sessionId,
                        inputText = inputText,
                        userMessage = userMessage,
                        commandSandbox = commandSandbox,
                        provider = provider,
                        model = model,
                        systemPrompt = systemPrompt,
                        reasoningLevel = effectiveReasoningLevel,
                        agentMode = agentMode,
                        directories = projectDirs,
                        planStore = planStore,
                        notebook = notebook,
                        planApprovalRequester = planApprovalRequester,
                        subagentRole = subagentRole,
                        apiKeyId = activeApiKeyId
                    )
                    if (outcome == null) break
                    val shouldContinue = inputLocks.getValue(sessionId).withLock {
                        val pending = historyStore.load(sessionId).filter { it.pendingSteering }
                        if (pending.isNotEmpty()) {
                            pending.forEach { input ->
                                historyStore.updateMessage(sessionId, input.id!!) { it.copy(pendingSteering = false) }
                            }
                            pendingSteerings.remove(sessionId)
                            true
                        } else {
                            // 正常结束的一轮可正式交付阶段回复；子代理仍在后台工作时，
                            // 只保留工作时钟与工作 ID，不隐藏本轮已完成的整条回复。
                            val finalId = outcome.candidateMessageId
                            if (finalId != null) {
                                historyStore.updateMessage(sessionId, finalId) { it.copy(isFinalAnswer = true) }
                            }
                            sessionStore.update(sessionId, SessionStatus.IDLE)
                            val payload = outcome.payload.toMutableMap()
                            finalId?.let { payload["finalMessageId"] = it }
                            emit(sessionId, EventType.MESSAGE_COMPLETED, payload = payload)
                            activeJobs.remove(sessionId)
                            if (!hasPendingSubagentWork(sessionId)) activeTurnIds.remove(sessionId)
                            finalized = true
                            false
                        }
                    }
                    if (!shouldContinue) break
                } catch (e: PrematureCloseSignal) {
                    // 断流自愈决策
                    if (autoContinueAttempt < AUTO_CONTINUE_MAX_RETRIES && hasEmittedTokens(sessionId)) {
                        autoContinueAttempt++
                        DebugLog.info("TurnExec", "auto-continue: attempt $autoContinueAttempt/$AUTO_CONTINUE_MAX_RETRIES for session $sessionId (premature close + emitted tokens)")
                        // 追加 "Continue" user 消息到 historyStore——agent 通过 ChatMemory 自然读到
                        val continueMsg = buildUserMessage(sessionId, listOf(MessagePart.Text("Continue")))
                        historyStore.append(sessionId, continueMsg)
                        sessionStore.update(sessionId, SessionStatus.RUNNING)
                        continue // 重跑 runTurn（重建 agent + agent.run，从 history 继续）
                    } else {
                        // 重试耗尽或未吐 token：emit MESSAGE_ERROR(PREMATURE_CLOSE)，UI 手动"继续"兜底
                        DebugLog.info("TurnExec", "auto-continue exhausted for session $sessionId: attempt=$autoContinueAttempt, hasTokens=${hasEmittedTokens(sessionId)}")
                        sessionStore.update(sessionId, SessionStatus.IDLE)
                        emit(sessionId, EventType.MESSAGE_ERROR, payload = e.errorPayload)
                        break
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e // 协程规范：取消必须重抛
                } catch (e: Throwable) {
                    // 收尾持久化同样可能失败，不能只留后台日志、让 UI 卡在工作中。
                    sessionStore.update(sessionId, SessionStatus.ERROR)
                    val record = ErrorCollector.collect(e, ErrorContext(phase = "turn_completion", sessionId = sessionId))
                    emit(sessionId, EventType.MESSAGE_ERROR, payload = record.toPayload())
                    break
                }
                // 错误由 runTurn 发出；只有正常结果才进入最终交付提交。
            }
            } finally {
                val wasCancelled = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]?.isCancelled == true
                var resumeInput: SendMessageRequest? = null
                if (!finalized) withContext(NonCancellable) {
                    inputLocks.getValue(sessionId).withLock {
                        activeJobs.remove(sessionId)
                        activeTurnIds.remove(sessionId)
                        // 错误收尾窗口中成功接收的新输入仍须处理；用户显式停止则保留但不自动续跑。
                        if (!wasCancelled) resumeInput = pendingSteerings[sessionId]?.peek()?.request
                    }
                }
                scope.launch {
                    resumeInput?.let { sendMessage(sessionId, it) }
                    dispatchPendingEventMessages(sessionId)
                }
            }
        }
        activeJobs[sessionId] = job
        job.start()
    }

    /**
     * 构建待落库的用户消息：文本末尾附 UI 隐藏标记 + 时间戳。
     * 标记后内容存进消息本体但不渲染到 UI（映射层在标记处截断），AI 全量可见。
     * 环境元数据（OS/Shell/沙箱/项目目录/Java）已移至系统提示词动态后缀（EnvironmentInfoProvider），
     * 不再随每条消息落库——避免历史膨胀 + 时间戳渐旧。
     */
    private fun buildUserMessage(
        sessionId: String,
        parts: List<MessagePart>
    ): Message {
        val now = Instant.now()
        val tz = java.time.ZoneId.systemDefault()
        val utc = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(java.time.ZoneOffset.UTC).format(now)
        val utcDow = now.atZone(java.time.ZoneOffset.UTC).dayOfWeek
            .getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)
        val zoned = now.atZone(tz)
        val local = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX").format(zoned)
        val localDow = zoned.dayOfWeek
            .getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)
        val metaNote = "\n$UI_HIDDEN_MARKER\n" +
            "NOTE FOR AI (this section is hidden from the user in the UI):\n" +
            "UTC: $utc ($utcDow)\n" +
            "Local: $local (${tz.id}, $localDow)"
        val lastTextIndex = parts.indexOfLast { it is MessagePart.Text }
        val partsWithMeta = if (lastTextIndex >= 0) {
            parts.mapIndexed { index, part ->
                if (index == lastTextIndex) (part as MessagePart.Text).copy(text = part.text + metaNote) else part
            }
        } else {
            parts + MessagePart.Text(metaNote.trimStart())
        }
        return Message(
            id = "msg_${UUID.randomUUID().toString().take(8)}",
            sessionId = sessionId,
            role = MessageRole.USER,
            parts = partsWithMeta,
            status = MessageStatus.COMPLETED,
            createdAt = Instant.now().toString()
        )
    }

    /** 断流前是否已输出内容（最后一条 assistant 消息有非空 Text part）。 */
    private suspend fun hasEmittedTokens(sessionId: String): Boolean {
        return historyStore.load(sessionId)
            .lastOrNull { it.role == MessageRole.ASSISTANT }
            ?.parts
            ?.filterIsInstance<MessagePart.Text>()
            ?.any { it.text.isNotBlank() }
            ?: false
    }

    /**
     * 中止指定 Session 的当前 turn。
     */
    fun abort(sessionId: String) {
        // peek 而非 remove：entry 由 runTurn 的 finally 自清理。若在这里移除，
        // 紧随其后的 abortAndJoin（rollback 流程）将找不到 dying job 而无法 join。
        val job = activeJobs[sessionId]
        val hasMainTurn = job?.isActive == true
        val hasChildren = subagentManager.runningCountForSession(sessionId) > 0 ||
            backgroundTaskManager.runningCountForSession(sessionId) > 0
        if (!hasMainTurn && !hasChildren) return
        val stoppedTurnId = activeTurnIds[sessionId]
        suppressedSubagentTerminals.addAll(subagentManager.listForSession(sessionId).map { it.agentId })
        activeTurnIds.remove(sessionId)
        job?.cancel()
        // 级联收割本会话的后台子代理：它们挂在全局 scope 上，不随父 turn job 取消而亡，
        // 不收割就成了继续写文件的孤儿（换模型"继续"后与重 spawn 的新代理并发写同一批文件）
        val stoppedSubagents = subagentManager.stopAllForSession(sessionId)
        if (stoppedSubagents > 0) {
            DebugLog.event("TurnExec", "abort: cascade-stopped $stoppedSubagents subagent(s) of session $sessionId")
        }
        // 后台任务（Command / Timer）同样挂在全局 scope 上，不随父 turn job 取消而亡——一并级联收割
        backgroundTaskManager.stopAllForSession(sessionId)
        questionRequesters[sessionId]?.cancelAll()
        planApprovalRequesters[sessionId]?.cancelAll()
        pendingEventMessages.remove(sessionId)
        if (!hasMainTurn) scope.launch {
            sessionStore.update(sessionId, SessionStatus.IDLE)
            val record = ErrorCollector.collect(kotlinx.coroutines.CancellationException("用户中止了工作"),
                ErrorContext(phase = "abort", sessionId = sessionId))
            emit(sessionId, EventType.MESSAGE_ERROR, payload = record.toPayload() +
                (stoppedTurnId?.let { mapOf("turnId" to it) } ?: emptyMap()))
        }
    }

    /**
     * 中止当前 turn 并**等待其完全终止**（cancel + join）。
     *
     * 回滚（rollbackToMessage）必须用本方法而非 [abort]：abort 只发取消信号不等死，
     * 旧 turn 的收尾落库（ChatMemory store 回写 / 增量持久化）可能在调用方截断历史
     * 之后才执行——被删的旧消息经 reconcile 对齐失败整体"复活"，与重发消息叠加
     * 造成整个会话重复。join 确保历史截断发生时不可能再有旧 turn 的写入。
     */
    suspend fun abortAndJoin(sessionId: String) {
        suppressedSubagentTerminals.addAll(subagentManager.listForSession(sessionId).map { it.agentId })
        activeTurnIds.remove(sessionId)
        activeJobs[sessionId]?.cancelAndJoin()
        // 与 abort 同语义：回滚 = 撤销到目标消息，其后发生的一切都不该继续——
        // 后台子代理一并收割（幂等，abort 路径已收过的这里是 0）
        subagentManager.stopAllForSession(sessionId)
        // 后台任务一并收割（幂等，abort 路径已收过的这里是 0）
        backgroundTaskManager.stopAllForSession(sessionId)
        questionRequesters[sessionId]?.cancelAll()
        planApprovalRequesters[sessionId]?.cancelAll()
        pendingEventMessages.remove(sessionId)
        pendingSteerings.remove(sessionId)
        // 正常取消路径 runTurn 收尾已写 IDLE；此处对陈旧 RUNNING（进程重启残留等）兜底复位，
        // 避免紧随的 sendMessage 被 RUNNING 检查误拒
        runCatching {
            sessionStore.update(sessionId, SessionStatus.IDLE)
        }.onFailure {
            DebugLog.error("TurnExec", "abortAndJoin: failed to reset session status: ${it.message}", it)
        }
    }

    /**
     * 回滚时中止当前 turn，等待其完全终止，并对在目标回滚点时尚未创立的子代理做无感丢弃。
     *
     * @param sessionId 会话 ID
     * @param keptAgentIds 回滚点之前已经创立的子代理 ID 集合
     */
    suspend fun rollbackAndJoin(sessionId: String, keptAgentIds: Set<String>) {
        suppressedSubagentTerminals.addAll(subagentManager.listForSession(sessionId).map { it.agentId })
        activeTurnIds.remove(sessionId)
        activeJobs[sessionId]?.cancelAndJoin()
        // 丢弃在回滚目标消息时尚未创立的子 Agent（不发射 SUBAGENT_STOPPED，不进 pendingEventMessages）
        val discarded = subagentManager.rollbackSubagents(sessionId, keptAgentIds)
        if (discarded.isNotEmpty()) {
            DebugLog.event("TurnExec", "rollbackAndJoin: silently discarded ${discarded.size} subagent(s) of session $sessionId: $discarded")
        }
        // 后台任务一并收割（回滚点之后启动的后台命令/定时器不再继续）
        backgroundTaskManager.stopAllForSession(sessionId)
        questionRequesters[sessionId]?.cancelAll()
        planApprovalRequesters[sessionId]?.cancelAll()
        pendingEventMessages.remove(sessionId)
        pendingSteerings.remove(sessionId)
        runCatching {
            sessionStore.update(sessionId, SessionStatus.IDLE)
        }.onFailure {
            DebugLog.error("TurnExec", "rollbackAndJoin: failed to reset session status: ${it.message}", it)
        }
    }

    suspend fun resolveQuestion(sessionId: String, questionId: String, answers: List<List<String>>): Boolean {
        val requester = questionRequesters[sessionId] ?: return false
        return requester.resolve(questionId, answers)
    }

    fun getSubagentReport(agentId: String): xyz.mederi.tools.subagent.SubagentManager.SubagentReportData? =
        subagentManager.getReport(agentId)

    fun listSubagentsForSession(sessionId: String): List<xyz.mederi.tools.subagent.SubagentManager.BackgroundAgent> =
        subagentManager.listForSession(sessionId)

    fun stopSubagent(agentId: String, reason: String = SubagentManager.REASON_STOPPED_BY_USER): String =
        subagentManager.stop(agentId, reason = reason)

    fun listBackgroundTasksForSession(sessionId: String): List<xyz.mederi.tools.background.BackgroundTaskManager.BackgroundTaskEntry> =
        backgroundTaskManager.listForSession(sessionId)

    fun stopBackgroundTask(taskId: String): String? =
        backgroundTaskManager.stop(taskId)

    /** 停止浏览器任务（UI/契约入口）；返回停止后状态名，taskId 不存在返回 null。 */
    fun stopBrowserTask(taskId: String): String? = browserTaskManager?.stopTask(taskId)

    suspend fun resolvePlanApproval(
        sessionId: String,
        planId: String,
        approved: Boolean
    ): Boolean {
        DebugLog.info("TurnExec", "resolvePlanApproval: sessionId=$sessionId, planId=$planId, approved=$approved")
        // 同 turn 批准：requester 存活时走内存 CompletableDeferred 唤醒挂起的 write_plan 工具协程，
        // AI 在同一 turn 内立即收到"已批准、去执行"，不落内部消息。
        val requester = planApprovalRequesters[sessionId]
        if (requester != null && requester.hasPending()) {
            return requester.resolve(planId, approved)
        }

        // 跨 turn / 重启后批准（"批准与 turn 解耦"）：requester 已随旧 turn 消亡（进程重启后压根不存在），
        // 无法唤醒任何挂起协程。此时把目标计划改 APPROVED 落盘，再以一条内部消息（UI 隐藏、AI 可见）
        // 启动新 turn，让 AI 读到 Active Plan 已是 APPROVED 后自行走执行链（generate_spec → spawn → verify）。
        // 拒绝（approved=false）无存活 turn 可唤醒、无状态无需变更，直接返回 false（计划保持 PENDING_APPROVAL，
        // 由用户继续对话或下个 write_plan 处理）。批准模型写入逻辑在上面已统一执行。
        if (!approved) return false

        val session = sessionStore.get(sessionId) ?: return false
        val project = projectManager.get(session.projectId) ?: return false
        val planStore = xyz.mederi.plan.PlanStore(listOf(project.directory))
        val plan = planStore.load(planId) ?: return false
        if (plan.sessionId != sessionId || plan.isTerminal ||
            plan.status != xyz.mederi.plan.PlanStatus.PENDING_APPROVAL
        ) {
            DebugLog.event(
                "TurnExec",
                "cross-turn plan approval skipped: not a pending plan (planId=$planId, status=${plan.status}, sessionMisMatch=${plan.sessionId != sessionId})"
            )
            return false
        }
        val approvedPlan = planStore.updatePlan(planId) {
            it.copy(status = xyz.mederi.plan.PlanStatus.APPROVED)
        } ?: return false

        eventBus.emit(MederiEvent(
            type = EventType.PLAN_APPROVAL_RESOLVED,
            sessionId = sessionId,
            payload = mapOf("planId" to planId, "approved" to "true"),
            timestamp = java.time.Instant.now().toString()
        ))

        DebugLog.event("TurnExec", "cross-turn plan approval (planId=$planId, sessionId=$sessionId)")

        // 内部消息：整条文本以 UI_HIDDEN_MARKER 开头 → UI 渲染 substringBefore 得空串（用户不可见），
        // AI 视图看到完整执行指令 + 上下文 Active Plan（APPROVED），据此启动执行。经 sendMessageInternal
        // 落库并启动新 turn（durable-first 由它保证）。
        val internalPrompt =
            "$UI_HIDDEN_MARKER\n" +
                "Plan '$approvedPlan.title' (id=$planId) has been approved by the user. " +
                "Execute it now: for each pending subtask, call generate_spec then subagent(SPAWN, planId=$planId, ...), " +
                "then verify_subtask. Do not re-create or re-ask for approval."
        val effectiveModel = session.aiModel
        val providerId = effectiveModel?.let { model ->
            providerManager.listWithoutKeys().firstOrNull { p -> p.models.any { it.id == model.id } }?.id
        }
        sendMessageInternal(
            sessionId,
            SendMessageRequest(
                agentConfig = xyz.mederi.api.AgentConfig(
                    agentMode = session.agentMode,
                    aiModel = effectiveModel,
                    reasoningLevel = session.reasoningLevel
                ),
                parts = listOf(MessagePart.Text(internalPrompt)),
                // 内部消息轮沿用会话当前模型所属供应商记忆中的选定 key（无记忆则 null → 回落默认 key）
                apiKeyId = providerId?.let { apiKeyResolver.currentKeyId(it) }
            )
        )
        return true
    }

    /**
     * 计划待批准时用户直接继续对话：给挂起的 write_plan 工具调用补写一条中性
     * [xyz.mederi.tools.PlanTools.USER_REPLIED_NEUTRAL] ToolResult 落库。
     *
     * 背景：plan 工具悬在 approval requester 时，其 ToolCall 已由增量持久化落库，但结果未写。
     * 若直接 abort，下一轮 Koog 加载历史会看到"无结果的 tool call"而催模型补答；更糟的是
     * 旧路径把批准预置为 false，PlanTools 走"rejected → 问为什么"分支——两者都不是想要的。
     * 这里主动补写中性结果，让下一轮 AI 视图有完整工具循环且语义中立，确定性由主动落库保证
     * （abort 取消 plan 工具协程，其真实返回不会落盘）。
     */
    private suspend fun injectNeutralPlanToolResult(sessionId: String) {
        val history = historyStore.load(sessionId)
        val pendingCall = history.asReversed().firstNotNullOfOrNull { msg ->
            msg.parts.filterIsInstance<xyz.mederi.domain.model.MessagePart.ToolCall>()
                .firstOrNull { it.tool == xyz.mederi.tools.PlanTools.PLAN_TOOL }
        } ?: return
        historyStore.append(sessionId, xyz.mederi.domain.model.Message(
            id = "msg_${java.util.UUID.randomUUID().toString().take(8)}",
            sessionId = sessionId,
            role = xyz.mederi.domain.model.MessageRole.USER,
            parts = listOf(xyz.mederi.domain.model.MessagePart.ToolResult(
                id = pendingCall.id,
                tool = pendingCall.tool,
                output = xyz.mederi.tools.PlanTools.USER_REPLIED_NEUTRAL
            )),
            status = xyz.mederi.domain.model.MessageStatus.COMPLETED,
            createdAt = java.time.Instant.now().toString()
        ))
        DebugLog.event("TurnExec", "injected neutral ToolResult for pending write_plan call id=${pendingCall.id}")
    }

    /**
     * ask_user 挂起时用户直接继续对话：给挂起的 ask_user 工具调用补写一条 declined
     * ToolResult 落库（与 [injectNeutralPlanToolResult] 对称）。
     *
     * 背景：ask_user 工具悬在 QuestionRequester 时，其 ToolCall 已落库但结果未写。
     * 若直接 abort，下一轮 Koog 加载历史会看到"无结果的 tool call"，UI 也会误显"已回答"
     * （Pending 状态被 ToolCallsBlock 当作已回答）。这里主动补写 declined 结果，
     * 让下一轮 AI 视图有完整工具循环，UI 工具行正确显示"已拒绝回答"。
     */
    private suspend fun injectAskUserDeclinedToolResult(sessionId: String) {
        val history = historyStore.load(sessionId)
        val pendingCall = history.asReversed().firstNotNullOfOrNull { msg ->
            msg.parts.filterIsInstance<xyz.mederi.domain.model.MessagePart.ToolCall>()
                .firstOrNull { it.tool == "ask_user" }
        } ?: return
        historyStore.append(sessionId, xyz.mederi.domain.model.Message(
            id = "msg_${java.util.UUID.randomUUID().toString().take(8)}",
            sessionId = sessionId,
            role = xyz.mederi.domain.model.MessageRole.USER,
            parts = listOf(xyz.mederi.domain.model.MessagePart.ToolResult(
                id = pendingCall.id,
                tool = pendingCall.tool,
                output = "User declined to answer the questions."
            )),
            status = xyz.mederi.domain.model.MessageStatus.COMPLETED,
            createdAt = java.time.Instant.now().toString()
        ))
        DebugLog.event("TurnExec", "injected declined ToolResult for pending ask_user call id=${pendingCall.id}")
    }

    /**
     * 手动压缩对话历史。
     *
     * 创建一个 mini agent，只跑一个压缩节点：
     * 1. ChatMemory 从 HistoryStore 加载完整历史
     * 2. replaceHistoryWithTLDR 用 MederiCompressionStrategy 压缩
     * 3. ChatMemory 把压缩后的历史存回 HistoryStore（reconcile：插标记、不删历史）
     */
    suspend fun compressHistory(sessionId: String) {
        val session = sessionStore.get(sessionId)
            ?: throw NoSuchElementException("Session not found: $sessionId")

        if (session.status == SessionStatus.RUNNING) {
            throw IllegalStateException("Session $sessionId is running. Wait for it to complete or abort it first.")
        }

        val effectiveModel = session.aiModel
            ?: throw NoSuchElementException("No AI model selected for session $sessionId")
        val effectiveReasoningLevel = session.reasoningLevel
            ?: ReasoningLevel.NONE

        val provider = providerManager.listWithoutKeys()
            .firstOrNull { p -> p.models.any { it.id == effectiveModel.id } }
            ?: throw NoSuchElementException("Provider for model ${effectiveModel.id} not found")
        val model = provider.getModel(effectiveModel.id)
            ?: throw NoSuchElementException("Model not found: ${effectiveModel.id}")

        // 空转预检：在翻状态机（Working→Idle）之前判定「根本没有值得压的旧消息」。
        // 命中则直接返回——不发 Working 不再回 Idle，UI 不会闪一下，只收到一条 STATUS 事实。
        // 窗口口径与实际压缩一致（图片按当前模型能力剔除），判定逻辑与策略共用 planCompression。
        val window = HistoryStoreChatHistoryProvider.aiViewWindow(historyStore, session.id)
        val koogWindow = KoogMessageMapper.toKoogMessages(window, includeImages = model.supportsImages)
        val skipReason = manualCompactionSkipReason(koogWindow, model.contextWindow)
        if (skipReason != null) {
            DebugLog.event("TurnExec", "manual compaction skipped: reason=$skipReason")
            emit(sessionId, EventType.STATUS, payload = mapOf(
                "scope" to "compaction",
                "code" to "SKIPPED",
                "reason" to skipReason
            ))
            return
        }

        sessionStore.update(sessionId, SessionStatus.RUNNING)
        emit(sessionId, EventType.SESSION_UPDATED)

        var compressionFailure: Throwable? = null
        try {
            compressOnce(
                session = session,
                provider = provider,
                model = model,
                reasoningLevel = effectiveReasoningLevel,
                systemPrompt = SystemPrompts.build(session.agentMode),
                keepLastMessages = MANUAL_KEEP_LAST_MESSAGES
            )
        } catch (e: Throwable) {
            val record = ErrorCollector.collect(e, ErrorContext(
                phase = "compression",
                sessionId = sessionId
            ))
            // 压缩失败是 transient：emit 前更新 sessionStore，保证 emit() 内读到权威终态 IDLE
            sessionStore.update(sessionId, SessionStatus.IDLE)
            emit(sessionId, EventType.MESSAGE_ERROR, payload = record.toPayload())
            compressionFailure = e
        } finally {
            sessionStore.update(sessionId, SessionStatus.IDLE)
        }

        // 通知 UI 刷新消息列表
        if (compressionFailure == null) emit(sessionId, EventType.MESSAGE_COMPLETED, null)
        pendingSteerings[sessionId]?.peek()?.let { pending ->
            scope.launch { sendMessage(sessionId, pending.request) }
        }
        compressionFailure?.let { throw it }
    }

    /**
     * 压缩执行体：mini agent 跑压缩节点，TLDR 以 SUMMARY 标记写回 HistoryStore。
     * 不做 session 状态/事件管理（手动压缩与发送前压缩共用）。
     */
    private suspend fun compressOnce(
        session: xyz.mederi.domain.model.Session,
        provider: xyz.mederi.provider.domain.model.Provider,
        model: AIModel,
        reasoningLevel: ReasoningLevel,
        systemPrompt: String,
        apiKeyId: String? = null,
        keepLastMessages: Int? = null
    ) {
        compactingSessions.add(session.id)
        try {
            val resolvedKey = apiKeyResolver.resolve(provider.id, apiKeyId)
            val apiKey = resolvedKey.value

            // 轮询：resolved value 为 __rotate__ 时走重试换 key（与 runTurn 共用同一计数器）
            val client: ai.koog.prompt.executor.clients.LLMClient =
                if (apiKey == API_KEY_ROTATION_ID) {
                    val keys = providerManager.listKeys(provider.id)
                        .filter { it.id != API_KEY_ROTATION_ID }
                        .mapNotNull { k -> providerManager.getKeyValue(provider.id, k.id) }
                        .filter { it.isNotBlank() }
                    if (keys.isNotEmpty()) {
                        val counter = rotationCounters.computeIfAbsent(session.id) {
                            java.util.concurrent.atomic.AtomicInteger()
                        }
                        retryWrapped(
                            llmClientFactory(provider, keys[counter.getAndIncrement().mod(keys.size)]),
                            session.id,
                            retryDelegateProvider = {
                                llmClientFactory(provider, keys[counter.getAndIncrement().mod(keys.size)])
                            }
                        )
                    } else {
                        retryWrapped(llmClientFactory(provider, apiKey), session.id)
                    }
                } else {
                    retryWrapped(llmClientFactory(provider, apiKey), session.id)
                }
            val executor = PromptExecutorBuilder()
                .addClient(client)
                .build()

            val koogModel = KoogModelBuilder.build(model, provider.type)
            val params = KoogParamsBuilder.build(
                type = provider.type,
                reasoningLevel = reasoningLevel,
                reasoningParameter = provider.reasoningParameter,
                maxTokens = model.maxTokens
            )

            val koogPrompt = prompt(session.id, params = params) {
                system(systemPrompt)
            }

            // Koog planner 每执行一个节点 iterations+1，超过 maxAgentIterations 抛
            // AIAgentMaxNumberOfIterationsReachedException。compressOnlyStrategy 要跑
            // nodeStart → nodeCompress → nodeFinish 共 3 个节点，1 必然抛错（压缩根本不会执行）。
            // 给足余量：10。
            val agentConfig = AIAgentConfig.builder()
                .model(koogModel)
                .prompt(koogPrompt)
                .maxAgentIterations(10)
                .serializer(mederiToolSerializer)
                .build()

            // 压缩与实际发送一致：图片按当前模型能力剔除
            val historyProvider = HistoryStoreChatHistoryProvider(historyStore, includeImages = model.supportsImages)

            val agent = AIAgent.builder()
                .promptExecutor(executor)
                .agentConfig(agentConfig)
                .graphStrategy(compressOnlyStrategy(MederiCompressionStrategy(
                    contextWindow = model.contextWindow,
                    keepLastMessages = keepLastMessages,
                    eventBus = eventBus,
                    sessionId = session.id
                )))
                .install(ChatMemory.Feature) { config ->
                    config.chatHistoryProvider = historyProvider
                }
                .build()

            agent.run("", sessionId = session.id)
        } finally {
            compactingSessions.remove(session.id)
            scope.launch {
                dispatchPendingEventMessages(session.id)
            }
        }
    }

    private data class TurnOutcome(val payload: Map<String, String>, val candidateMessageId: String?)
    private data class ResponseStreamItem(val messageId: String, val frame: StreamFrame? = null, val started: Boolean? = null,
        val eventType: EventType? = null, val payload: Map<String, String> = emptyMap())

    private suspend fun runTurn(
        sessionId: String,
        inputText: String,
        userMessage: Message,
        commandSandbox: xyz.mederi.tools.sandbox.CommandSandbox,
        provider: xyz.mederi.provider.domain.model.Provider,
        model: AIModel,
        systemPrompt: String,
        reasoningLevel: ReasoningLevel,
        agentMode: AgentMode,
        directories: List<String>,
        planStore: xyz.mederi.plan.PlanStore,
        notebook: xyz.mederi.plan.Notebook,
        planApprovalRequester: xyz.mederi.plan.PlanApprovalRequester,
        subagentRole: SubagentRole? = null,
        apiKeyId: String? = null
    ): TurnOutcome? {
        val newContextWindowFlag = AtomicBoolean(false)
        val turnId = activeTurnIds[sessionId] ?: "turn_${UUID.randomUUID()}"
        val frameChannel = Channel<ResponseStreamItem>(Channel.UNLIMITED)
        // 流式传输异常（静默断流/消费异常）：流消费者写入，turn 收尾时随 MESSAGE_COMPLETED
        // 的 payload 传给 UI。内容不回滚（半截文本已生成/落库），只提示不完整。
        // streamWarning = 人类提示文本；streamWarningRecord = 结构化警告记录（ErrorCollector，走统一错误出口）
        val streamWarning = AtomicReference<String?>(null)
        val streamWarningRecord = AtomicReference<xyz.mederi.debug.ErrorRecord?>(null)

        val session = sessionStore.get(sessionId)
            ?: throw IllegalStateException("Session not found: $sessionId")
        val project = projectManager.get(session.projectId)
            ?: throw IllegalStateException("Project not found: ${session.projectId}")
        val directories = listOf(project.directory)

        val diffTracker = TurnDiffTracker(sessionId, directories)
        // 注册进会话级注册表：本会话内子代理 turn 结束时把其文件改动合并进本 tracker
        // （子代理 diffStore 为 null，唯一出口就是这里；见 ParentDiffRegistry）
        xyz.mederi.tools.diff.ParentDiffRegistry.register(sessionId, diffTracker)

        // clientRef 在 buildTurnAgent 创建 client 后写入，streamConsumer 读 End 帧时取诊断
        val clientRef = AtomicReference<ai.koog.prompt.executor.clients.LLMClient?>(null)
        val diagnosticsProvider: () -> StreamCloseDiagnostics? = {
            val c = clientRef.get()
            when (c) {
                is RetryableLLMClient -> (c.delegate() as? MederiOpenAILLMClient)?.lastStreamDiagnostics
                is MederiOpenAILLMClient -> c.lastStreamDiagnostics
                else -> null
            }
        }

        val streamConsumer = launchStreamConsumer(
            sessionId, frameChannel, streamWarning, streamWarningRecord,
            providerId = provider.id,
            providerName = provider.name,
            modelId = model.id,
            modelName = model.name,
            turnId = turnId,
            diagnosticsProvider = diagnosticsProvider
        )

        val diagnostics = MessageDiagnostics(
            providerId = provider.id,
            modelId = model.id,
            modelName = model.name,
            reasoningLevel = reasoningLevel.name,
            agentMode = agentMode.name,
            projectId = session.projectId,
            turnId = turnId,
            workStartedAt = workClocks[sessionId]?.startedAt
        )
        val toolTimings = TurnToolTimings()
        // 发送链路与实际一致：图片按当前模型能力剔除（与压缩/预检估算一致）
        val historyProvider = TrackingHistoryProvider(
            HistoryStoreChatHistoryProvider(historyStore, diagnostics, toolTimings, includeImages = model.supportsImages)
        )

        // MCP：连接已启用的 MCP server，把工具合并进 agent。是否开启由中心化
        // AgentCapabilities 表决定（主代理 + 两种子代理都继承 MCP）。失败只记日志不拖垮
        // turn；turn 结束统一 close。声明在 try 外，finally 里才能访问。
        val mcpSession: McpSession? = if (AgentCapabilities.of(subagentRole).inheritMcp) {
            try {
                mcpConnector?.openSession()
            } catch (e: Exception) {
                DebugLog.error("TurnExec", "openSession MCP 失败: ${e.message}", e)
                null
            }
        } else null

        try {
            // 统一经 ApiKeyResolver 解析（显式归属命中→写记忆 / 记忆 / 默认 key 三级兜底；
            // 全无则抛 IllegalStateException，不静默伪装成功）
            val resolvedKey = apiKeyResolver.resolve(provider.id, apiKeyId)
            val apiKey = resolvedKey.value

            // 轮询：resolved value 为 __rotate__ 时走重试换 key（首次用第一个，重试轮换下一个）
            val llmClient: ai.koog.prompt.executor.clients.LLMClient =
                if (apiKey == API_KEY_ROTATION_ID) {
                    val keys = providerManager.listKeys(provider.id)
                        .filter { it.id != API_KEY_ROTATION_ID }
                        .mapNotNull { k -> providerManager.getKeyValue(provider.id, k.id) }
                        .filter { it.isNotBlank() }
                    if (keys.isNotEmpty()) {
                        val counter = rotationCounters.computeIfAbsent(sessionId) {
                            java.util.concurrent.atomic.AtomicInteger()
                        }
                        retryWrapped(
                            llmClientFactory(provider, keys[counter.getAndIncrement().mod(keys.size)]),
                            sessionId,
                            retryDelegateProvider = {
                                llmClientFactory(provider, keys[counter.getAndIncrement().mod(keys.size)])
                            }
                        )
                    } else {
                        retryWrapped(llmClientFactory(provider, apiKey), sessionId)
                    }
                } else {
                    retryWrapped(llmClientFactory(provider, apiKey), sessionId)
                }

            DebugLog.section("TurnExec", "TurnExecutor.runTurn")
            DebugLog.data("TurnExec", "apiKey", "${apiKey.take(4)}****${apiKey.takeLast(4)}")
            DebugLog.data("TurnExec", "apiKeyId", apiKeyId)
            DebugLog.data("TurnExec", "inputText", "'$inputText'")
            DebugLog.data("TurnExec", "provider.type", provider.type)
            DebugLog.data("TurnExec", "provider.baseUrl", provider.baseUrl)

            preflightCompressionIfNeeded(session, provider, model, reasoningLevel, systemPrompt, apiKeyId)

            val questionRequester = xyz.mederi.question.QuestionRequester(sessionId, eventBus)
            questionRequesters[sessionId] = questionRequester

            // AGENTS.md 子树懒发现：loader 发现 + 会话级去重 registry（v1 内存态，
            // 重启后首个访问会重复注入一次，可接受）。read/list 工具访问触发。
            val agentsDiscovery = xyz.mederi.tools.AgentsSubtreeDiscovery { accessedPath ->
                val found = agentsFileLoader.discoverSubtree(directories.first(), accessedPath)
                    ?: return@AgentsSubtreeDiscovery null
                val seen = agentsDiscovered.computeIfAbsent(sessionId) { ConcurrentHashMap.newKeySet() }
                if (seen.add(found.path)) found else null
            }

            // 自定义工具：每轮 turn 从全局注册表收集新实例（UI 层启动装配阶段 register 工厂闭包）
            val customTools = CustomToolRegistry.buildAll()

            val toolRegistry = ToolFactory.build(
                toolNames = ToolFactory.ALL_TOOL_NAMES,
                directories = directories,
                sessionId = sessionId,
                historyStore = historyStore,
                eventBus = eventBus,
                modelContextWindow = model.contextWindow,
                newContextWindowFlag = newContextWindowFlag,
                diffTracker = diffTracker,
                subagentManager = subagentManager,
                browserTaskService = browserTaskManager,
                aiModel = model,
                reasoningLevel = reasoningLevel,
                projectId = session.projectId,
                questionRequester = questionRequester,
                agentMode = agentMode,
                subagentRole = subagentRole,
                planApprovalRequester = planApprovalRequester,
                planStore = planStore,
                notebook = notebook,
                commandSandbox = commandSandbox,
                sessionStore = sessionStore,
                mcpTools = mcpSession?.tools ?: emptyList(),
                customTools = customTools,
                agentsDiscovery = agentsDiscovery,
                onFileTouched = onFileTouched,
                apiKeyId = apiKeyId,
                subagentConfigManager = subagentConfigManager,
                skills = skills,
                backgroundTaskManager = backgroundTaskManager
            )

            val incrementalPersister = TurnIncrementalPersister(sessionId, historyStore, diagnostics, eventBus,
                turnId = turnId,
                includeImages = model.supportsImages,
                workStartedAt = workClocks[sessionId]?.startedAt,
                responseBoundary = { id, started -> frameChannel.trySend(ResponseStreamItem(id, started = started)) },
                consumeInput = { item -> consumeSteering(sessionId, item) }
            )

            val agent = buildTurnAgent(
                sessionId = sessionId,
                provider = provider,
                llmClient = llmClient,
                model = model,
                reasoningLevel = reasoningLevel,
                systemPrompt = systemPrompt,
                toolRegistry = toolRegistry,
                historyProvider = historyProvider,
                toolTimings = toolTimings,
                frameChannel = frameChannel,
                streamWarning = streamWarning,
                incrementalPersister = incrementalPersister,
                clientRef = clientRef,
                diagnostics = diagnostics
            )

            DebugLog.event("TurnExec", "agent.run() starting...")
            agent.run(MEDERI_INPUT_PERSISTED, sessionId = sessionId)
            DebugLog.event("TurnExec", "agent.run() completed")
            frameChannel.close()
            streamConsumer.join()

            if (newContextWindowFlag.get()) {
                newContextWindowFlag.set(false)
                // new_context 只截断 AI 视图：插入压缩标记，标记前的历史保留在库里、UI 可见
                HistoryStoreChatHistoryProvider.insertMarker(
                    historyStore,
                    sessionId,
                    "TLDR: 用户开启了新上下文。\n\n此前的对话内容已归档，不再发送给 AI。"
                )
                emit(sessionId, EventType.SESSION_UPDATED)
            }

            // 计算本轮 diff 追踪与变更摘要（严格针对本轮 Turn）
            diffTracker.captureSnapshot()
            DebugLog.event("TurnExec", "diffTracker snapshot captured")
            val lastMsgId = incrementalPersister.lastAssistantMessageId.get()
                ?: historyStore.load(sessionId).lastOrNull { it.role == MessageRole.ASSISTANT }?.id
            val turnDiff = diffTracker.buildDiff(lastMsgId, Instant.now().toString())
            diffStore?.save(turnDiff)
            DebugLog.event("TurnExec", "diffStore saved with messageId=$lastMsgId, changes=${turnDiff.changes.size}")
            // 子代理 turn：diffStore 为 null，TurnDiff 唯一出口——回传给父级合并进父 turn 的 tracker
            onTurnDiff?.invoke(turnDiff)

            val turnDiffSummary: TurnDiffSummary? = if (turnDiff.changes.isNotEmpty()) {
                val fileSummaries = turnDiff.changes.map { change ->
                    val (add, del) = countChanges(change.before, change.after)
                    FileDiffSummary(
                        path = change.path,
                        status = change.status,
                        additions = add,
                        deletions = del
                    )
                }
                TurnDiffSummary(
                    files = fileSummaries,
                    totalAdditions = fileSummaries.sumOf { it.additions },
                    totalDeletions = fileSummaries.sumOf { it.deletions }
                )
            } else null

            if (turnDiffSummary != null && lastMsgId != null) {
                runCatching {
                    historyStore.updateMessage(sessionId, lastMsgId) { it.copy(turnDiffSummary = turnDiffSummary) }
                    DebugLog.event("TurnExec", "updated last assistant message with turnDiffSummary: ${turnDiffSummary.files.size} files")
                }.onFailure { e ->
                    DebugLog.error("TurnExec", "failed to update last assistant message with turnDiffSummary: ${e.message}", e)
                }
            }

            val warning = streamWarning.get()
            val warningRecord = streamWarningRecord.get()
            val payload = mutableMapOf("turnId" to turnId)
            if (warningRecord != null) {
                val record = warningRecord as xyz.mederi.debug.ErrorRecord
                payload.putAll(record.toPayload())
                payload["warning"] = warning ?: ""
            } else if (warning != null) {
                payload["warning"] = warning
            }
            if (turnDiffSummary != null) {
                payload["turnDiffSummary"] = Json.Default.encodeToString(TurnDiffSummary.serializer(), turnDiffSummary)
                if (lastMsgId != null) {
                    payload["diffMessageId"] = lastMsgId
                }
            }

            val lastResponse = lastMsgId?.let { id -> historyStore.load(sessionId).firstOrNull { it.id == id } }
            val candidate = lastResponse?.takeIf { streamWarning.get() == null && it.isCompleteDeliverable() }?.id
            if (candidate == null && streamWarning.get() == null &&
                lastResponse?.parts?.none { it is MessagePart.ToolCall } == true
            ) {
                // 长度截断/空响应等既不交付，也必须给用户一个可见原因。
                val reason = lastResponse.finishReason ?: "missing finish reason"
                val record = ErrorCollector.collect(
                    IllegalStateException("模型未生成完整回复（finishReason=$reason），请补充指令或继续。"),
                    ErrorContext(phase = "response_completion", sessionId = sessionId,
                        providerId = provider.id, providerName = provider.name, modelId = model.id, modelName = model.name)
                )
                payload.putAll(record.toPayload())
            }
            return TurnOutcome(payload, candidate)

        } catch (e: kotlinx.coroutines.CancellationException) {
            DebugLog.event("TurnExec", "runTurn cancelled: ${e.message}")
            withContext(NonCancellable) {
                frameChannel.close(e)
                streamConsumer.join()
                sessionStore.update(sessionId, SessionStatus.IDLE)
                val record = ErrorCollector.collect(
                    kotlinx.coroutines.CancellationException("用户中止了对话"),
                    ErrorContext(phase = "abort", sessionId = sessionId)
                )
                emit(sessionId, EventType.MESSAGE_ERROR, payload = record.toPayload())
            }
            throw e
        } catch (e: Throwable) {
            DebugLog.event("TurnExec", "runTurn error: ${e::class.simpleName}: ${e.message}")
            frameChannel.close(e)
            streamConsumer.join()
            val errorContext = ErrorContext(
                phase = "turn_execution",
                sessionId = sessionId,
                providerId = provider.id,
                providerName = provider.name,
                modelId = model.id,
                modelName = model.name
            )
            val record = ErrorCollector.collect(e, errorContext)
            val errorPayload = record.toPayload().toMutableMap().apply {
                streamWarning.get()?.let { put("warning", it) }
            }
            // 断流自愈：PREMATURE_CLOSE 不在此 emit MESSAGE_ERROR，重抛给 auto-continue 循环决策
            // （已有半截 token 则追加 "Continue" 续写；清理已在重抛前完成——frameChannel.close + streamConsumer.join）
            if (record.failureMode == ErrorCollector.FailureMode.PREMATURE_CLOSE) {
                throw PrematureCloseSignal(errorPayload, e)
            }
            if (RetryableLLMClient.isTransientError(e) || record.severity == ErrorSeverity.RECOVERABLE) {
                // 限流/网关过载/网络中断 = 环境态：重试已耗尽或流中途中断，属于可恢复状态，session 保持 IDLE
                // （用户稍后重发即可），不把会话标成 ERROR
                sessionStore.update(sessionId, SessionStatus.IDLE)
                emit(sessionId, EventType.MESSAGE_ERROR, payload = errorPayload)
            } else {
                sessionStore.update(sessionId, SessionStatus.ERROR)
                emit(sessionId, EventType.MESSAGE_ERROR, payload = errorPayload)
            }
        } finally {
            questionRequesters.remove(sessionId)?.cancelAll()
            planApprovalRequesters.remove(sessionId)?.cancelAll()
            // 注销会话级 diff 注册（子代理改动合并入口），防止泄漏与跨 turn 误归并
            xyz.mederi.tools.diff.ParentDiffRegistry.unregister(sessionId)
            if (mcpSession != null) {
                try {
                    mcpSession.close()
                } catch (e: Exception) {
                    DebugLog.error("TurnExec", "关闭 MCP 连接失败: ${e.message}", e)
                }
            }

        }
        return null
    }


    /**
     * 流式帧消费循环：把 Koog StreamFrame 转成 MESSAGE_DELTA 事件推给 UI。
     * 逐帧只计数（StreamTimingLog），每秒打一条 progress、结束时打汇总，
     * 用于判别"渐进到达"还是"瞬间爆发"（duration≈0 即爆发）。
     * 同时把 delta 粒度/类型交错记入 [StreamTrace]（内存 ring，导出即排查，不刷终端）：
     * "一字母一行" = delta 逐字符分片或 reasoning/text 交替，close 时自动给出 suspicion 结论。
     */
    private fun launchStreamConsumer(
        sessionId: String,
        frameChannel: Channel<ResponseStreamItem>,
        streamWarning: AtomicReference<String?>,
        streamWarningRecord: AtomicReference<xyz.mederi.debug.ErrorRecord?>,
        providerId: String,
        providerName: String,
        modelId: String,
        modelName: String,
        turnId: String,
        diagnosticsProvider: () -> StreamCloseDiagnostics? = { null }
    ) = scope.launch {
        val timing = StreamTimingLog("SSE-Timing", "delta frames")
        val traceSession = StreamTrace.beginSession(sessionId)
        var exitNote = "drained"
        try {
            for (item in frameChannel) {
                if (item.eventType != null) {
                    emit(sessionId, item.eventType, messageId = item.messageId,
                        payload = item.payload + ("turnId" to turnId))
                    continue
                }
                if (item.started != null) {
                    emit(sessionId, EventType.MESSAGE_DELTA, messageId = item.messageId,
                        payload = mapOf("type" to if (item.started) "response_started" else "response_completed", "turnId" to turnId))
                    continue
                }
                val frame = item.frame ?: continue
                when (frame) {
                    is StreamFrame.TextDelta -> {
                        timing.sample(frame.text.length)
                        frame.text.takeIf { it.isNotEmpty() }?.let {
                            StreamTrace.delta(traceSession, "text", it.length)
                            emitDelta(sessionId, "text", it, item.messageId, turnId)
                        }
                    }
                    is StreamFrame.ReasoningDelta -> {
                        timing.sample(frame.text?.length ?: 0)
                        // summary-only delta（Responses API 的 reasoning summary）以前被整体丢弃，
                        // 现在与 text 一样作为 reasoning 内容转发（UI 只关心推理文本流）
                        val reasoning = frame.text?.takeIf { it.isNotEmpty() }
                            ?: frame.summary?.takeIf { it.isNotEmpty() }
                        reasoning?.let {
                            StreamTrace.delta(traceSession, "reasoning", it.length)
                            emitDelta(sessionId, "reasoning", it, item.messageId, turnId)
                        }
                    }
                    is StreamFrame.ToolCallDelta -> {
                        // 续片 delta（deepseek 分片：id/name 空只有 args 增量）不发事件，
                        // 避免空壳 TOOL_CALL 污染 UI 流式视图。完整调用由 onToolCallStarting 带 real args 发。
                        if (frame.name.isNullOrBlank()) continue
                        timing.sample(frame.content?.length ?: 0)
                        DebugLog.debug("Frame", "ToolCallDelta: name=${frame.name}, contentLen=${frame.content?.length ?: 0}")
                        emitToolDelta(sessionId, name = frame.name!!, args = frame.content ?: "", complete = false, messageId = item.messageId, turnId = turnId)
                    }
                    is StreamFrame.ToolCallComplete -> {
                        // 碎片续片（id/name 空）是 Koog builder 挤兑 pending 槽的副产品，
                        // 不是真实调用——toAssistantMessageSafe 会合并续片 args 到首个命名调用。
                        if (frame.name.isNullOrBlank()) continue
                        timing.sample()
                        DebugLog.event(
                            "Frame",
                            "ToolCallComplete: name=${frame.name}, argsLen=${frame.content?.length ?: 0}, " +
                                "argsPreview=${frame.content?.take(120)}"
                        )
                        emitToolDelta(sessionId, name = frame.name!!, args = frame.content, complete = true, messageId = item.messageId, turnId = turnId)
                    }
                    is StreamFrame.End -> {
                        // End 也是轮次边界（tool loop 每轮一个 End）。finishReason 为空/空白的 End
                        // = 上游没给收尾标记（SSE 连接断开时 lines() flow 正常结束、emitEnd
                        // 无有效 finishReason）→ turn 会"成功"收尾但回复被静默截断。
                        // 生成 WARNING 级 ErrorRecord 走统一错误出口（ErrorBoard），并留人类提示文本。
                        if (frame.finishReason.isNullOrBlank()) {
                            val diag = diagnosticsProvider()
                            val summary = "流式连接提前中断：服务器关闭连接但未发送结束标记" +
                                "（已接收 ${traceSession.deltaCount} 帧 / ${traceSession.charCount} 字符）"
                            streamWarning.compareAndSet(null, summary)
                            streamWarningRecord.compareAndSet(
                                null,
                                ErrorCollector.collectWarning(
                                    message = summary,
                                    detail = diag?.summary(),
                                    context = ErrorContext(
                                        phase = "streaming",
                                        sessionId = sessionId,
                                        providerId = providerId,
                                        providerName = providerName,
                                        modelId = modelId,
                                        modelName = modelName
                                    )
                                )
                            )
                            StreamTrace.record(
                                "Stream.Summary",
                                mapOf("event" to "end-no-finish-reason", "id" to sessionId,
                                    "deltas" to traceSession.deltaCount.toString(),
                                    "chars" to traceSession.charCount.toString())
                            )
                        }
                        DebugLog.event("Frame", "End frame: finishReason=${frame.finishReason}")
                    }
                    else -> {}
                }
            }
            DebugLog.event("Frame", "frameChannel drained (consumer loop exit)")
        } catch (e: kotlinx.coroutines.CancellationException) {
            exitNote = "cancelled: ${e.message}"
            throw e
        } catch (e: Throwable) {
            exitNote = "error: ${e::class.simpleName}: ${e.message}"
            val record = ErrorCollector.collect(e, ErrorContext(
                phase = "streaming",
                sessionId = sessionId,
                providerId = providerId,
                providerName = providerName,
                modelId = modelId,
                modelName = modelName
            ))
            val warning = "流式中断：${record.formatShortMessage()}" +
                "（已接收 ${traceSession.deltaCount} 帧 / ${traceSession.charCount} 字符）"
            streamWarning.compareAndSet(null, warning)
        } finally {
            StreamTrace.closeSession(traceSession, note = exitNote)
            timing.close()
        }
    }

    /**
     * 发送前压缩（pre-flight）：已占用上下文 > 阈值（默认 70%）窗口时，
     * 先压缩（生成 TLDR、插标记）再发送。保证到达 AI 的第一个请求就不携带
     * 臃肿历史——压缩发生在请求之前，而不是超限请求发出之后。
     * durable-first 后用户消息已在 [HistoryStoreChatHistoryProvider.aiViewWindow] 窗口内，
     * 直接按窗口估算即可，不再单独累加新消息 token。
     * 自动压缩走 token 预算规划（keepLastMessages = null）。
     * 若压缩失败或压缩后仍超出窗口硬上限，直接抛出异常阻断本轮，由 runTurn 统一上报并停止对话，
     * 严禁带着超限上下文继续发给 LLM。
     */
    private suspend fun preflightCompressionIfNeeded(
        session: xyz.mederi.domain.model.Session,
        provider: xyz.mederi.provider.domain.model.Provider,
        model: AIModel,
        reasoningLevel: ReasoningLevel,
        systemPrompt: String,
        apiKeyId: String? = null
    ) {
        val contextWindow = model.contextWindow ?: return
        // 上下文占用口径（空窗口 / SUMMARY 分支等）统一由唯一真理源给出
        val usedTokens = aiViewContextUsedTokens(historyStore, session.id, model.supportsImages)
        val budget = (contextWindow * (compactionThresholdPercent() / 100.0)).toInt()
        if (usedTokens <= budget) return

        DebugLog.event(
            "TurnExec",
            "pre-flight compression triggered: used=$usedTokens > budget=$budget"
        )
        compressOnce(
            session = session,
            provider = provider,
            model = model,
            reasoningLevel = reasoningLevel,
            systemPrompt = systemPrompt,
            apiKeyId = apiKeyId,
            keepLastMessages = null
        )

        val remainingTokens = aiViewContextUsedTokens(historyStore, session.id, model.supportsImages)
        if (remainingTokens > contextWindow) {
            throw IllegalStateException("Context window exceeded after compression: $remainingTokens > $contextWindow tokens")
        }
    }

    /**
     * 限流/临时故障重试包装。maxRetries=0 时直通原 client。
     * [retryDelegateProvider] 非 null 时，重试换 key（用于 API Key 轮询）。
     */
    private fun retryWrapped(
        client: ai.koog.prompt.executor.clients.LLMClient,
        sessionId: String,
        retryDelegateProvider: (() -> ai.koog.prompt.executor.clients.LLMClient?)? = null
    ): ai.koog.prompt.executor.clients.LLMClient {
        if (LlmRetryConfig.maxRetries <= 0) return client
        return RetryableLLMClient(
            delegate = client,
            maxRetries = LlmRetryConfig.maxRetries,
            minDelayMs = LlmRetryConfig.minDelayMs,
            maxDelayMs = LlmRetryConfig.maxDelayMs,
            statusBus = eventBus,
            sessionIdProvider = { sessionId },
            retryDelegateProvider = retryDelegateProvider
        )
    }

    /** 会话级轮询计数器（内存态，重启归零） */
    private val rotationCounters = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()

    /** 组装本轮对话 agent：client/executor/model/params/prompt/config + 工具注册表 + 压缩/流式安装 */
    private fun buildTurnAgent(
        sessionId: String,
        provider: xyz.mederi.provider.domain.model.Provider,
        llmClient: ai.koog.prompt.executor.clients.LLMClient,
        model: AIModel,
        reasoningLevel: ReasoningLevel,
        systemPrompt: String,
        toolRegistry: ToolRegistry,
        historyProvider: TrackingHistoryProvider,
        toolTimings: TurnToolTimings,
        frameChannel: Channel<ResponseStreamItem>,
        streamWarning: AtomicReference<String?>,
        incrementalPersister: TurnIncrementalPersister,
        clientRef: AtomicReference<ai.koog.prompt.executor.clients.LLMClient?> = AtomicReference(null),
        diagnostics: MessageDiagnostics = MessageDiagnostics()
    ): AIAgent<String, String> {
        val client = llmClient
        clientRef.set(client)
        DebugLog.data("TurnExec", "client", client::class.simpleName)

        val executor: PromptExecutor = PromptExecutorBuilder()
            .addClient(client)
            .build()

        val koogModel = KoogModelBuilder.build(model, provider.type)
        DebugLog.data("TurnExec", "koogModel.id", koogModel.id)
        DebugLog.data("TurnExec", "koogModel.provider", koogModel.provider)
        DebugLog.data("TurnExec", "koogModel.capabilities", koogModel.capabilities)

        val params = KoogParamsBuilder.build(
            type = provider.type,
            reasoningLevel = reasoningLevel,
            reasoningParameter = provider.reasoningParameter,
            maxTokens = model.maxTokens
        )
        DebugLog.data("TurnExec", "params", params::class.simpleName)
        DebugLog.data("TurnExec", "params.temperature", (params as? ai.koog.prompt.params.LLMParams)?.temperature)
        DebugLog.data("TurnExec", "params.maxTokens", params.maxTokens)
        when (params) {
            is ai.koog.prompt.executor.clients.openai.OpenAIChatParams -> {
                DebugLog.data("TurnExec", "params.reasoningEffort", params.reasoningEffort)
                DebugLog.data("TurnExec", "params.additionalProperties", params.additionalProperties)
            }
            is ai.koog.prompt.executor.clients.openai.OpenAIResponsesParams -> {
                DebugLog.data("TurnExec", "params.reasoning", params.reasoning)
                DebugLog.data("TurnExec", "params.additionalProperties", params.additionalProperties)
            }
            is ai.koog.prompt.executor.clients.google.GoogleParams -> {
                DebugLog.data("TurnExec", "params.thinkingConfig", params.thinkingConfig)
            }
        }

        val koogPrompt = prompt(sessionId, params = params) {
            system(systemPrompt)
        }
        DebugLog.data("TurnExec", "koogPrompt messages count", koogPrompt.messages.size)

        val agentConfig = AIAgentConfig.builder()
            .model(koogModel)
            .prompt(koogPrompt)
            .maxAgentIterations(3000)
            .serializer(mederiToolSerializer)
            .build()
        DebugLog.data("TurnExec", "agentConfig.maxAgentIterations", 3000)
        DebugLog.data("TurnExec", "agentConfig.serializer", mederiToolSerializer::class.simpleName)

        // 构建压缩配置（仅当模型有 contextWindow 时启用）
        val compressionConfig = model.contextWindow?.let { contextWindow ->
            HistoryCompressionConfig(
                isHistoryTooBig = { prompt: ai.koog.prompt.Prompt ->
                    // 与 UI 上下文百分比同源：API 报告的真实 prompt tokens 优先，
                    // 端点不报告 inputTokens 时才回退加权估算（统一在 TokenEstimator）
                    val usedTokens = contextUsedTokens(prompt.messages)
                    // 70%：上下文越满注意力越分散，且留出发送前压缩的判定余量
                    usedTokens > (contextWindow * (compactionThresholdPercent() / 100.0)).toInt()
                },
                compressionStrategy = MederiCompressionStrategy(
                    contextWindow = contextWindow,
                    eventBus = eventBus,
                    sessionId = sessionId,
                    onCompactionStart = { compactingSessions.add(sessionId) },
                    onCompactionEnd = { compactingSessions.remove(sessionId) }
                ),
                retrievalModel = null
            )
        }

        return AIAgent.builder()
            .promptExecutor(executor)
            .agentConfig(agentConfig)
            .toolRegistry(toolRegistry)
            .graphStrategy(
                if (compressionConfig != null) {
                    mederiSingleRunStrategyWithCompression(compressionConfig, incrementalPersister, pollSteering = { pollSteering(sessionId) })
                } else {
                    mederiSingleRunStrategy(incrementalPersister, pollSteering = { pollSteering(sessionId) })
                }
            )
            .install(ChatMemory.Feature) { config ->
                config.chatHistoryProvider = historyProvider
                // system 提示词不持久化（它是 Prompt 配置，非对话历史），
                // 但每次请求必须把它拼到 messages 最前，否则 ChatMemory 用历史覆盖后会丢失 system，
                // 导致 OpenAI 前缀缓存失效（命中率掉到 0）。
                config.addPreProcessor(object : ChatMemoryPreProcessor {
                    override fun preprocess(messages: List<ai.koog.prompt.message.Message>): List<ai.koog.prompt.message.Message> =
                        if (messages.firstOrNull() is ai.koog.prompt.message.Message.System) messages
                        else listOf(ai.koog.prompt.message.Message.System(systemPrompt, RequestMetaInfo.create(KoogClock.System))) + messages
                })
            }
            .install(EventHandler.Feature) { config ->
                config.onLLMStreamingFrameReceived { eventContext ->
                    // 逐帧只转发给 frameChannel，不逐帧打日志（速率/时间戳由 StreamTimingLog 汇总）
                    incrementalPersister.currentResponseId.get()?.let { id ->
                        frameChannel.trySend(ResponseStreamItem(id, frame = eventContext.streamFrame))
                    }
                    // End 帧是流式请求的自然完成点：携带 metaInfo（含 inputTokensCount 等）时发射用量事件
                    val frame = eventContext.streamFrame
                    if (frame is StreamFrame.End) {
                        extractUsagePayload(frame.metaInfo)?.let { usage ->
                            scope.launch {
                                emit(sessionId, EventType.LLM_REQUEST_COMPLETED, payload = usage)
                            }
                        }
                    }
                }
                config.onLLMCallCompleted { eventContext ->
                    // 非流式 execute 路径：Koog 中 execute 与 executeStreaming 互斥（流式只发 Streaming 事件、
                    // 非流式只发 CallCompleted），不会与上面的 End 帧双计。metaInfo 为空帧时 extractUsagePayload
                    // 返回 null（无用量数据），不发射。
                    eventContext.response?.metaInfo?.let { meta ->
                        extractUsagePayload(meta)?.let { usage ->
                            scope.launch {
                                emit(sessionId, EventType.LLM_REQUEST_COMPLETED, payload = usage)
                            }
                        }
                    }
                }
                config.onLLMStreamingFailed { eventContext ->
                    // ErrorCollector 内部完成日志 + 历史记录；agent 会抛错走 runTurn 的 catch（MESSAGE_ERROR）
                    ErrorCollector.collect(eventContext.error, ErrorContext(
                        phase = "streaming_failed",
                        sessionId = sessionId
                    ))
                    frameChannel.close(eventContext.error)
                }
                config.onToolCallStarting { eventContext ->
                    toolTimings.onStarting(eventContext.toolCallId)
                    DebugLog.event("EventHandler", "onToolCallStarting: tool=${eventContext.toolName}, toolCallId=${eventContext.toolCallId}")
                    val responseId = incrementalPersister.currentResponseId.get()
                    if (responseId != null) frameChannel.trySend(ResponseStreamItem(responseId,
                        eventType = EventType.TOOL_CALLED, payload = mapOf(
                            "tool" to eventContext.toolName,
                            "toolCallId" to (eventContext.toolCallId ?: ""),
                            // 必须走标准 JSON 序列化：Koog JSONObject.toString() 是手拼的
                            // （字符串值原样包引号、不转义内部引号/反斜杠），命令里带 " 或 \ 会产出
                            // 非法 JSON，导致 UI 端 ToolArgParser 解析失败、工具目标显示丢失。
                            "args" to Json.Default.encodeToString(eventContext.toolArgs.toKotlinxJsonElement())
                        )))
                }
                config.onToolCallCompleted { eventContext ->
                    toolTimings.onCompleted(eventContext.toolCallId)
                    DebugLog.event("EventHandler", "onToolCallCompleted: tool=${eventContext.toolName}, toolCallId=${eventContext.toolCallId}")
                    val responseId = incrementalPersister.currentResponseId.get()
                    if (responseId != null) frameChannel.trySend(ResponseStreamItem(responseId,
                        eventType = EventType.TOOL_RESULT, payload = mapOf(
                            "tool" to eventContext.toolName,
                            "toolCallId" to (eventContext.toolCallId ?: ""),
                            // 必须走 formatToolResultOutput（与上方 args 的修复同源）：Koog
                            // JSONLiteral.toString() 对字符串字面量包引号且不转义内部引号，
                            // ask_user 返回的 JSON 字符串会产出非法 JSON，UI 端 parseAskItems
                            // 解析失败后回退把整串塞进 Q1（已回答显示错乱）。
                            "output" to formatToolResultOutput(eventContext.toolResult),
                            "isError" to "false"
                        )))
                }
                config.onToolCallFailed { eventContext ->
                    toolTimings.onFailed(eventContext.toolCallId, eventContext.message)
                    ErrorCollector.collect(
                        RuntimeException(eventContext.message),
                        ErrorContext(
                            phase = "tool_call",
                            sessionId = sessionId,
                            toolName = eventContext.toolName
                        )
                    )
                    val responseId = incrementalPersister.currentResponseId.get()
                    if (responseId != null) frameChannel.trySend(ResponseStreamItem(responseId,
                        eventType = EventType.TOOL_RESULT, payload = mapOf(
                            "tool" to eventContext.toolName,
                            "toolCallId" to (eventContext.toolCallId ?: ""),
                            "output" to eventContext.message,
                            "isError" to "true"
                        )))
                }
            }
            .build()
    }

    private suspend fun emitDelta(sessionId: String, type: String, content: String, messageId: String, turnId: String) {
        emit(
            sessionId = sessionId,
            type = EventType.MESSAGE_DELTA,
            messageId = messageId,
            payload = mapOf("type" to type, "content" to content, "turnId" to turnId)
        )
    }

    private suspend fun emitToolDelta(sessionId: String, name: String, args: String, complete: Boolean, messageId: String, turnId: String) {
        emit(
            sessionId = sessionId,
            type = EventType.MESSAGE_DELTA,
            messageId = messageId,
            payload = mapOf(
                "type" to "tool_call",
                "turnId" to turnId,
                "name" to name,
                "content" to args,
                "state" to if (complete) "completed" else "running"
            )
        )
    }

    private suspend fun emit(
        sessionId: String,
        type: EventType,
        messageId: String? = null,
        payload: Map<String, String> = emptyMap()
    ) {
        try {
            val clock = workClocks[sessionId]?.takeIf {
                val requestedTurn = payload["turnId"]
                if (requestedTurn != null) requestedTurn == it.turnId
                else activeTurnIds[sessionId] == it.turnId || activeJobs.containsKey(sessionId)
            }
            val endsWork = clock != null && (type == EventType.MESSAGE_ERROR ||
                type == EventType.MESSAGE_COMPLETED && !hasPendingSubagentWork(sessionId))
            val effectiveClock = if (endsWork) {
                workClocks.computeIfPresent(sessionId) { _, current ->
                    if (current.turnId == clock!!.turnId && current.completedAt == null)
                        current.copy(completedAt = System.currentTimeMillis()) else current
                }
            } else clock
            val clockPayload = if (effectiveClock != null) {
                if (endsWork) historyStore.updateHistory(sessionId) { messages ->
                    messages.map { message ->
                        if (message.turnId == effectiveClock.turnId) message.copy(
                            workStartedAt = effectiveClock.startedAt, workCompletedAt = effectiveClock.completedAt
                        ) else message
                    }
                }
                buildMap {
                    put("turnId", effectiveClock.turnId)
                    put("workStartedAt", effectiveClock.startedAt.toString())
                    effectiveClock.completedAt?.let { put("workCompletedAt", it.toString()) }
                }
            } else emptyMap()
            val timedPayload = payload + clockPayload
            // MESSAGE_ERROR：读 sessionStore 权威终态注入 payload，消费端（MederiAiCore / ServerAiCore /
            // SnapshotReducer）统一读 payload["finalStatus"] 映射，不再各自读 sessionStore 或硬编码。
            val enrichedPayload = if (type == EventType.MESSAGE_ERROR) {
                val ss = try { sessionStore.get(sessionId)?.status } catch (_: Exception) { null }
                if (ss != null) timedPayload + ("finalStatus" to ss.name) else timedPayload
            } else {
                timedPayload
            }
            DebugLog.debug("EventBus", "emit: type=$type, sessionId=$sessionId, messageId=$messageId, payload keys=${enrichedPayload.keys}")
            eventBus.emit(
                MederiEvent(
                    type = type,
                    sessionId = sessionId,
                    messageId = messageId,
                    payload = enrichedPayload,
                    timestamp = Instant.now().toString()
                )
            )
        } catch (e: Throwable) {
            DebugLog.error("EventBus", "Failed to emit event $type for session $sessionId: ${e.message}", e)
        }
    }

    private fun handleSubagentTerminalEvent(event: MederiEvent) {
        val parentSessionId = event.sessionId
        if (parentSessionId.isBlank()) return

        val agentId = event.payload["agentId"]
        if (agentId in suppressedSubagentTerminals) return
        val msgText = formatSubagentEventMessage(event)
        DebugLog.event("TurnExec", "handleSubagentTerminalEvent: session=$parentSessionId, agentId=${event.payload["agentId"]}, type=${event.type}")

        val list = pendingEventMessages.computeIfAbsent(parentSessionId) {
            java.util.Collections.synchronizedList(mutableListOf())
        }
        list.add(msgText)
        agentId?.let(observedSubagentTerminals::add)

        // 异步尝试调度：dispatchPendingEventMessages 内部持锁检查会话是否空闲
        scope.launch {
            dispatchPendingEventMessages(parentSessionId)
        }
    }

    private fun handleBackgroundTaskTerminalEvent(event: MederiEvent) {
        val parentSessionId = event.sessionId
        if (parentSessionId.isBlank()) return

        val msgText = formatBackgroundTaskEventMessage(event)
        DebugLog.event("TurnExec", "handleBackgroundTaskTerminalEvent: session=$parentSessionId, taskId=${event.payload["taskId"]}, type=${event.type}")

        val list = pendingEventMessages.computeIfAbsent(parentSessionId) {
            java.util.Collections.synchronizedList(mutableListOf())
        }
        list.add(msgText)

        scope.launch {
            dispatchPendingEventMessages(parentSessionId)
        }
    }

    private fun handleBrowserTaskTerminalEvent(event: MederiEvent) {
        val parentSessionId = event.sessionId
        if (parentSessionId.isBlank()) return

        val msgText = formatBrowserTaskEventMessage(event)
        DebugLog.event("TurnExec", "handleBrowserTaskTerminalEvent: session=$parentSessionId, taskId=${event.payload["taskId"]}, type=${event.type}")

        val list = pendingEventMessages.computeIfAbsent(parentSessionId) {
            java.util.Collections.synchronizedList(mutableListOf())
        }
        list.add(msgText)

        scope.launch {
            dispatchPendingEventMessages(parentSessionId)
        }
    }

    private fun formatBrowserTaskEventMessage(event: MederiEvent): String {
        val eventTypeStr = when (event.type) {
            EventType.BROWSER_TASK_COMPLETED -> "browser_task_completed"
            EventType.BROWSER_TASK_ERROR -> "browser_task_error"
            EventType.BROWSER_TASK_STOPPED -> "browser_task_stopped"
            else -> "browser_task_event"
        }
        val taskId = event.payload["taskId"] ?: "unknown"
        val status = event.payload["status"] ?: "UNKNOWN"
        val role = event.payload["role"] ?: SubagentRole.BROWSER_OPERATOR.name
        val reportPath = event.payload["reportPath"]
        val summary = event.payload["message"] ?: event.payload["summary"].orEmpty()

        return buildString {
            appendLine("""<event_message type="$eventTypeStr" agentId="$taskId" status="$status">""")
            appendLine("Role: $role")
            if (!reportPath.isNullOrBlank()) {
                appendLine("ReportPath: $reportPath")
            }
            if (summary.isNotBlank()) {
                appendLine("Summary:")
                appendLine(summary)
            }
            append("</event_message>")
        }
    }

    private fun formatSubagentEventMessage(event: MederiEvent): String {
        val eventTypeStr = when (event.type) {
            EventType.SUBAGENT_COMPLETED -> "subagent_completed"
            EventType.SUBAGENT_ERROR -> "subagent_error"
            EventType.SUBAGENT_STOPPED -> "subagent_stopped"
            else -> "subagent_event"
        }
        val agentId = event.payload["agentId"] ?: "unknown"
        val status = event.payload["status"] ?: "UNKNOWN"
        val role = event.payload["role"] ?: "AGENT"
        val reportPath = event.payload["reportPath"]
        val planId = event.payload["planId"]
        val subtaskIndex = event.payload["subtaskIndex"]
        val result = event.payload["result"].orEmpty()

        return buildString {
            appendLine("""<event_message type="$eventTypeStr" agentId="$agentId" status="$status">""")
            appendLine("Role: $role")
            if (planId != null && subtaskIndex != null) {
                appendLine("Subtask: Subtask $subtaskIndex (planId: $planId, index: $subtaskIndex)")
            }
            if (!reportPath.isNullOrBlank()) {
                appendLine("ReportPath: $reportPath")
            }
            if (result.isNotBlank()) {
                appendLine("Summary:")
                appendLine(result)
            }
            // 注意：这里只写状态事实（role/reportPath/summary），不得追加任何指令/警告行——
            // 指令统一由静态系统提示词承载（PromptGuides.SUBAGENT_REPORT_VERIFICATION），
            // 动态注入只挂状态（AGENTS §5.6）。
            append("</event_message>")
        }
    }

    private fun formatBackgroundTaskEventMessage(event: MederiEvent): String {
        val eventTypeStr = when (event.type) {
            EventType.BACKGROUND_TASK_COMPLETED -> "background_task_completed"
            EventType.BACKGROUND_TASK_ERROR -> "background_task_error"
            EventType.BACKGROUND_TASK_STOPPED -> "background_task_stopped"
            else -> "background_task_event"
        }
        val taskId = event.payload["taskId"] ?: "unknown"
        val taskType = event.payload["taskType"] ?: "unknown"
        val status = event.payload["status"] ?: "UNKNOWN"
        val command = event.payload["command"]
        val description = event.payload["description"]
        val exitCode = event.payload["exitCode"]
        val output = event.payload["output"].orEmpty()

        return buildString {
            appendLine("""<event_message type="$eventTypeStr" taskId="$taskId" taskType="$taskType" status="$status">""")
            if (taskType == "timer") {
                appendLine("⏰ Timer fired: ${description ?: ""}")
            } else {
                appendLine("Background command completed: ${command ?: ""}")
                if (exitCode != null) appendLine("Exit code: $exitCode")
                if (output.isNotBlank()) {
                    appendLine("Output:")
                    appendLine(output)
                }
            }
            append("</event_message>")
        }
    }

    private suspend fun dispatchPendingEventMessages(sessionId: String) {
        eventMessageLock.withLock {
            val list = pendingEventMessages[sessionId]
            if (list.isNullOrEmpty()) return

            val session = sessionStore.get(sessionId) ?: return
            if (compactingSessions.contains(sessionId)) {
                DebugLog.debug("TurnExec", "dispatchPendingEventMessages: session $sessionId is compacting, deferring dispatch")
                return
            }
            if (session.status == SessionStatus.RUNNING || activeJobs[sessionId]?.isActive == true) {
                DebugLog.debug("TurnExec", "dispatchPendingEventMessages: session $sessionId is RUNNING, deferring dispatch")
                return
            }

            // 仍有 RUNNING 子代理时延后派发：等全部终态事件聚合（多个子代理并发完成时，
            // 前面的终态事件会先入队；若此时就冲刷，父代理会被中间状态唤醒多次）。
            // 不加定时器、不轮询——最后一个子代理完成发终态事件 → handleSubagentTerminalEvent
            // → 再次调本方法，此时 runningCount 为 0 才真正派发。
            val runningSubagents = subagentManager.runningCountForSession(sessionId)
            if (runningSubagents > 0) {
                DebugLog.debug("TurnExec", "dispatchPendingEventMessages: $sessionId has $runningSubagents running subagent(s), deferring dispatch")
                return
            }

            // 仍有 RUNNING 后台任务时延后派发：等其终态事件一起聚合（与子代理同语义）
            val runningBackgroundTasks = backgroundTaskManager.runningCountForSession(sessionId)
            if (runningBackgroundTasks > 0) {
                DebugLog.debug("TurnExec", "dispatchPendingEventMessages: $sessionId has $runningBackgroundTasks running background task(s), deferring dispatch")
                return
            }

            val messagesToDispatch = synchronized(list) {
                val copy = list.toList()
                list.clear()
                copy
            }
            if (messagesToDispatch.isEmpty()) return

            val combinedText = messagesToDispatch.joinToString("\n\n")
            val effectiveModel = session.aiModel
            if (effectiveModel == null) {
                DebugLog.error("TurnExec", "dispatchPendingEventMessages failed: no aiModel configured for session $sessionId")
                return
            }
            val providerId = providerManager.listWithoutKeys()
                .firstOrNull { p -> p.models.any { it.id == effectiveModel.id } }?.id

            val request = SendMessageRequest(
                agentConfig = AgentConfig(
                    agentMode = session.agentMode,
                    aiModel = effectiveModel,
                    reasoningLevel = session.reasoningLevel
                ),
                parts = listOf(MessagePart.Text(combinedText)),
                // 事件唤醒续轮沿用会话当前模型所属供应商记忆中的选定 key（无记忆则 null → 回落默认 key）
                apiKeyId = providerId?.let { apiKeyResolver.currentKeyId(it) }
            )

            try {
                DebugLog.event("TurnExec", "dispatchPendingEventMessages: dispatching ${messagesToDispatch.size} event message(s) to session $sessionId")
                sendMessage(sessionId, request)
            } catch (e: IllegalStateException) {
                DebugLog.info("TurnExec", "dispatchPendingEventMessages busy ($sessionId), requeueing: ${e.message}")
                synchronized(list) {
                    list.addAll(0, messagesToDispatch)
                }
            } catch (e: Throwable) {
                DebugLog.error("TurnExec", "dispatchPendingEventMessages failed for $sessionId: ${e.message}", e)
            }
        }
    }
}

/**
 * 从 LLM 响应的 [ResponseMetaInfo] 提取单次请求用量 payload（供 LLM_REQUEST_COMPLETED 事件发射）。
 *
 * 门禁：inputTokensCount 为 null 视为无用量数据 → 返回 null（不发射），符合"没有数据就是没有"的不兜底原则。
 * 结构化数据仍走 kotlinx.serialization：cachedTokens 从响应 metadata（JsonObject）里按 key 取，
 * 命中 JsonPrimitive 才写入 payload，缺省即省略该 key。
 *
 * @return 非空时 = {"inputTokens": "...", "outputTokens"?, "cachedTokens"?}；无用量数据时为 null
 */
internal fun extractUsagePayload(meta: ai.koog.prompt.message.ResponseMetaInfo): Map<String, String>? {
    val input = meta.inputTokensCount ?: return null
    return buildMap {
        put("inputTokens", input.toString())
        meta.outputTokensCount?.let { put("outputTokens", it.toString()) }
        (meta.metadata?.get("cachedTokens") as? JsonPrimitive)?.content?.let { put("cachedTokens", it) }
    }
}

/** 完整交付以统一完成语义判断；截断/过滤/未知结束不能作为最终答案。 */
internal fun Message.isCompleteDeliverable(): Boolean =
    finishReason?.lowercase() in setOf("stop", "completed", "end_turn") &&
        parts.any { it is MessagePart.Text && it.text.isNotBlank() } &&
        parts.none { it is MessagePart.ToolCall }
