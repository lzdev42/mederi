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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import xyz.mederi.api.SendMessageRequest
import xyz.mederi.debug.DebugLog
import xyz.mederi.debug.ErrorCollector
import xyz.mederi.debug.ErrorContext
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
import xyz.mederi.tools.estimateTokens
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
import xyz.mederi.domain.model.WorkType
import xyz.mederi.project.ProjectManager
import xyz.mederi.prompt.SystemPrompts
import xyz.mederi.provider.ProviderManager
import xyz.mederi.provider.domain.model.ReasoningLevel
import xyz.mederi.provider.infrastructure.koog.KoogClientFactory
import xyz.mederi.provider.infrastructure.koog.KoogModelBuilder
import xyz.mederi.provider.infrastructure.koog.KoogParamsBuilder
import xyz.mederi.store.DiffStore
import xyz.mederi.store.HistoryStore
import xyz.mederi.store.SessionStore
import xyz.mederi.skills.SkillManager
import xyz.mederi.tools.ToolFactory
import xyz.mederi.tools.diff.TurnDiffTracker
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
 * constructor, exist"，所有带嵌套参数的工具调用必炸，如 create_plan）。
 * 显式换 KotlinxSerializer：与工具 Args 的 kotlinx @Serializable 同源，且
 * ignoreUnknownKeys/coerceInputValues/explicitNulls=false 对 LLM 生成的参数健壮
 * （多传字段忽略、null 当缺省、非法枚举回退默认）。
 */
private val mederiToolSerializer = ai.koog.serialization.kotlinx.KotlinxSerializer(
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
 * Session 只记录 agentMode/workType 和用户最后一次选择的 aiModel / reasoningLevel。
 * 系统提示词由 agentMode + workType 通过 [SystemPrompts.build] 现算。
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
    )
) {

    private val subagentRunner = SubagentRunnerImpl(
        providerManager = providerManager,
        projectManager = projectManager,
        mcpConnector = mcpConnector,
        skills = skills
    )

    private val subagentManager = SubagentManager(
        subagentRunner = subagentRunner,
        scope = scope
    )

    // 浏览器任务管理：持有 BrowserRegistry（UI 注册 JCEF、core 注册 Camoufox，启动装配时填充）。
    // 主代理获得 run_browser_task / browser_task_status / stop_browser_task 三个工具；
    // 注册表为空时 runTask 返回明确错误引导用户配置。
    private val browserTaskManager: xyz.mederi.browser.BrowserTaskService? =
        xyz.mederi.browser.BrowserTaskManager(
            providerManager = providerManager,
            scope = scope,
            eventBus = eventBus
        )

    private val activeJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()

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
    suspend fun sendMessage(sessionId: String, request: SendMessageRequest, subagentRole: SubagentRole? = null) {
        return try {
            sendMessageInternal(sessionId, request, subagentRole)
        } catch (e: Throwable) {
            runCatching {
                sessionStore.update(sessionId, SessionStatus.ERROR)
            }
            val record = ErrorCollector.collect(e, ErrorContext(
                phase = "send_message",
                sessionId = sessionId
            ))
            emit(sessionId, EventType.MESSAGE_ERROR, payload = record.toPayload())
            throw e
        }
    }

    private suspend fun sendMessageInternal(sessionId: String, request: SendMessageRequest, subagentRole: SubagentRole? = null) {
        val session = sessionStore.get(sessionId)
            ?: throw NoSuchElementException("Session not found: $sessionId")

        DebugLog.section("TurnExec", "TurnExecutor.sendMessageInternal")
        DebugLog.data("TurnExec", "sessionId", sessionId)
        DebugLog.data("TurnExec", "session.status", session.status)
        DebugLog.data("TurnExec", "session.agentMode", session.agentMode)
        DebugLog.data("TurnExec", "session.workType", session.workType)
        DebugLog.data("TurnExec", "session.aiModel", "${session.aiModel?.id} (${session.aiModel?.name}), providerModelId=${session.aiModel?.providerModelId}")
        DebugLog.data("TurnExec", "session.reasoningLevel", session.reasoningLevel)

        val hasPendingQuestion = questionRequesters[sessionId]?.hasPending() == true
        val hasPendingPlanApproval = planApprovalRequesters[sessionId]?.hasPending() == true
        if (hasPendingQuestion || hasPendingPlanApproval) {
            DebugLog.info("TurnExec", "User sent message while question/plan approval pending for session $sessionId; aborting previous turn cleanly")
            abortAndJoin(sessionId)
        } else {
            if (session.status == SessionStatus.RUNNING) {
                throw IllegalStateException(
                    "Session $sessionId is already running a turn. Wait for it to complete or abort it first."
                )
            }
            if (activeJobs[sessionId]?.isActive == true) {
                throw IllegalStateException("Session $sessionId has an active turn in progress.")
            }
        }

        val agentMode = request.agentConfig.agentMode
        val workType = request.agentConfig.workType
        val project = projectManager.get(session.projectId)
            ?: throw IllegalStateException("Project not found: ${session.projectId}")
        val projectDirs = listOf(project.directory)
        val planStore = xyz.mederi.plan.PlanStore(projectDirs)
        val notebook = xyz.mederi.plan.Notebook(projectDirs)
        val activePlan = planStore.loadBySession(sessionId)
        val activePlanContent = activePlan?.let { p ->
            buildString {
                appendLine("Title: ${p.title}")
                appendLine("Status: ${p.status}")
                if (p.businessLogic.isNotBlank()) {
                    appendLine("Business Logic: ${p.businessLogic}")
                }
                val passed = p.subtasks.count { it.status == xyz.mederi.plan.SubtaskStatus.COMPLETED }
                val failed = p.subtasks.count { it.status == xyz.mederi.plan.SubtaskStatus.FAILED }
                val pending = p.subtasks.count { it.status == xyz.mederi.plan.SubtaskStatus.PENDING }
                appendLine("Progress: $passed passed, $failed failed, $pending pending")
                // 指针行：给模型 todo 式的焦点（"正在做哪个/下一个做哪个"），与轻量 todo 的单 in_progress 同机制
                val activeSubtask = p.currentSubtask ?: p.nextPending
                activeSubtask?.let { appendLine("Current: Subtask ${it.index + 1} [${it.status}]: ${it.name}") }
                // 失败子任务的处置指令不在此挂（Plan Loop 静态规则已覆盖）；这里只挂状态事实，
                // 由模型对照 [FAILED] + Verification Result 自行走 converge_plan / re-generate_spec 分支
                // spec 只挂活跃子任务：历史 spec 留在磁盘（spawn_agent 自取、generate_spec 可重写），
                // 主代理的编排/验证/收敛决策只需要骨架（brief/verification/验证结果），避免 token 无界增长
                p.subtasks.forEach { st ->
                    appendLine("- [${st.status}] Subtask ${st.index + 1}: ${st.name}")
                    if (st.targetFiles.isNotEmpty()) appendLine("  Files: ${st.targetFiles.joinToString()}")
                    if (st.planDetail.isNotBlank()) appendLine("  Brief: ${st.planDetail}")
                    when {
                        st.index == activeSubtask?.index && !st.spec.isNullOrBlank() ->
                            appendLine("  Spec: ${st.spec}")
                        st.spec.isNullOrBlank() ->
                            appendLine("  Spec: (not generated yet — call generate_spec before spawning)")
                        else ->
                            appendLine("  Spec: (stored on disk; mounted only for the active subtask)")
                    }
                    st.decisions.forEach { d -> appendLine("  Decision: ${d.question} -> ${d.choice} (${d.rationale})") }
                    appendLine("  Verification: ${st.verification}")
                    st.verificationResult?.let { r ->
                        appendLine("  Result: ${r.status}" + (r.gapType?.let { g -> " ($g)" } ?: ""))
                        appendLine("  Evidence: ${r.evidence}")
                    }
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
        // 主代理用完整提示词（含工作流与活跃计划段）。
        // 是否注入已安装 skills：一律读中心化 AgentCapabilities 表（主代理 + EXECUTOR 注入，RESEARCHER 不注入）。
        val basePrompt = if (subagentRole != null) SystemPrompts.forSubagent(subagentRole, workType)
        else SystemPrompts.build(agentMode, workType, activePlanContent, activeTodoContent)
        val systemPrompt = if (AgentCapabilities.of(subagentRole).inheritSkills) {
            val skillList = runCatching { skills?.list() }.getOrElse { e ->
                DebugLog.error("TurnExec", "加载 skills 列表失败（不阻塞 turn）: ${e.message}", e)
                null
            }
            SystemPrompts.withSkills(basePrompt, skillList.orEmpty())
        } else basePrompt
        DebugLog.data("TurnExec", "agentMode", agentMode)
        DebugLog.data("TurnExec", "workType", workType)
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

        // 回写 Session 的 Agent 配置（记住用户最后一次选择）
        sessionStore.updateAgentConfig(
            sessionId,
            agentMode = agentMode,
            workType = workType,
            aiModel = effectiveModel,
            reasoningLevel = effectiveReasoningLevel
        )

        val inputText = request.parts.filterIsInstance<MessagePart.Text>()
            .joinToString("") { it.text }

        // ── durable-first：用户消息先落库，再启动 Agent ──
        // 乐观更新的持久化兜底：网络/模型/工具在 turn 任何阶段失败，用户输入都已在
        // HistoryStore，不会丢失。消息带 UI 隐藏元数据块（时间/系统环境）——存的就是发的，
        // ChatMemory load 时已落库的消息自然进入 prompt；回写 reconcile 按内容指纹对齐，
        // 不会重复追加。append 同步执行且先于 SESSION_UPDATED 事件，
        // 发送时机的监听者（如自动改名）回查快照必然能看到本条消息。
        val commandSandbox = xyz.mederi.tools.sandbox.CommandSandbox(projectDirs)
        val userMessage = buildUserMessage(sessionId, request.parts, projectDirs, commandSandbox)
        historyStore.append(sessionId, userMessage)

        sessionStore.update(sessionId, SessionStatus.RUNNING)
        emit(sessionId, EventType.SESSION_UPDATED)

        val planApprovalRequester = xyz.mederi.plan.PlanApprovalRequester(sessionId, eventBus)
        planApprovalRequesters[sessionId] = planApprovalRequester

        val job = scope.launch {
            runTurn(
                sessionId = sessionId,
                inputText = inputText,
                userMessage = userMessage,
                commandSandbox = commandSandbox,
                provider = provider,
                model = model,
                systemPrompt = systemPrompt,
                reasoningLevel = effectiveReasoningLevel,
                agentMode = agentMode,
                workType = workType,
                directories = projectDirs,
                planStore = planStore,
                notebook = notebook,
                planApprovalRequester = planApprovalRequester,
                subagentRole = subagentRole
            )
        }
        activeJobs[sessionId] = job
    }

    /**
     * 构建待落库的用户消息：文本末尾附 UI 隐藏标记 + 环境备注（时间 + 系统环境
     * OS/架构/shell/沙箱状态）。标记后内容存进消息本体但不渲染到 UI（映射层在标记处
     * 截断），AI 全量可见；存的就是发的，提示词前缀稳定（缓存安全）。
     * 环境块只含"AI 需要知道但不该让用户每次输入"的事实，采集全部来自进程内已有信息，零额外 IO。
     */
    private fun buildUserMessage(
        sessionId: String,
        parts: List<MessagePart>,
        directories: List<String>,
        commandSandbox: xyz.mederi.tools.sandbox.CommandSandbox
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
            "Local: $local (${tz.id}, $localDow)\n" +
            xyz.mederi.tools.sandbox.CommandSandbox.environmentNote(commandSandbox) +
            "Project dir: " + directories.first() + "\n" +
            (xyz.mederi.tools.sandbox.SandboxConfig.extraWritablePaths.takeIf { it.isNotEmpty() }
                ?.let { "Extra writable paths: ${it.joinToString(", ")}\n" } ?: "") +
            "Mederi workdir: ${java.io.File(directories.first(), ".mederi").absolutePath}"
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

    /**
     * 中止指定 Session 的当前 turn。
     */
    fun abort(sessionId: String) {
        // peek 而非 remove：entry 由 runTurn 的 finally 自清理。若在这里移除，
        // 紧随其后的 abortAndJoin（rollback 流程）将找不到 dying job 而无法 join。
        // 无活跃 turn 时整体 no-op：滞后的 abort 请求不能误杀新建的 turn。
        val job = activeJobs[sessionId] ?: return
        if (!job.isActive) return
        job.cancel()
        questionRequesters[sessionId]?.cancelAll()
        planApprovalRequesters[sessionId]?.cancelAll()
        scope.launch {
            runCatching {
                sessionStore.update(sessionId, SessionStatus.IDLE)
                // 用户中止 = 取消事件，走 ErrorCollector 统一记录（分类 CANCELLED / 严重级 WARNING）
                val record = ErrorCollector.collect(
                    kotlinx.coroutines.CancellationException("用户中止了对话"),
                    ErrorContext(phase = "abort", sessionId = sessionId)
                )
                emit(sessionId, EventType.MESSAGE_ERROR, payload = record.toPayload())
            }.onFailure {
                DebugLog.error("TurnExec", "Failed to update session status after abort: ${it.message}", it)
            }
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
        activeJobs[sessionId]?.cancelAndJoin()
        questionRequesters[sessionId]?.cancelAll()
        planApprovalRequesters[sessionId]?.cancelAll()
        // 正常取消路径 runTurn 收尾已写 IDLE；此处对陈旧 RUNNING（进程重启残留等）兜底复位，
        // 避免紧随的 sendMessage 被 RUNNING 检查误拒
        runCatching {
            sessionStore.update(sessionId, SessionStatus.IDLE)
        }.onFailure {
            DebugLog.error("TurnExec", "abortAndJoin: failed to reset session status: ${it.message}", it)
        }
    }

    suspend fun resolveQuestion(sessionId: String, questionId: String, answers: List<List<String>>): Boolean {
        val requester = questionRequesters[sessionId] ?: return false
        return requester.resolve(questionId, answers)
    }

    suspend fun resolvePlanApproval(sessionId: String, planId: String, approved: Boolean): Boolean {
        val requester = planApprovalRequesters[sessionId] ?: return false
        return requester.resolve(planId, approved)
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

        sessionStore.update(sessionId, SessionStatus.RUNNING)
        emit(sessionId, EventType.SESSION_UPDATED)

        try {
            compressOnce(session, provider, model, effectiveReasoningLevel, SystemPrompts.build(session.agentMode, session.workType))
        } catch (e: Throwable) {
            val record = ErrorCollector.collect(e, ErrorContext(
                phase = "compression",
                sessionId = sessionId
            ))
            emit(sessionId, EventType.MESSAGE_ERROR, payload = record.toPayload())
            throw e
        } finally {
            sessionStore.update(sessionId, SessionStatus.IDLE)
        }

        // 通知 UI 刷新消息列表
        emit(sessionId, EventType.MESSAGE_COMPLETED, null)
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
        systemPrompt: String
    ) {
        val apiKey = providerManager.getDefaultKeyValue(provider.id)
            ?: throw IllegalStateException("No API key available for provider: ${provider.id}")

        val client = retryWrapped(KoogClientFactory.create(provider, apiKey), session.id)
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

        val historyProvider = HistoryStoreChatHistoryProvider(historyStore)

        val agent = AIAgent.builder()
            .promptExecutor(executor)
            .agentConfig(agentConfig)
            .graphStrategy(compressOnlyStrategy(MederiCompressionStrategy()))
            .install(ChatMemory.Feature) { config ->
                config.chatHistoryProvider = historyProvider
            }
            .build()

        agent.run("", sessionId = session.id)
    }

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
        workType: WorkType,
        directories: List<String>,
        planStore: xyz.mederi.plan.PlanStore,
        notebook: xyz.mederi.plan.Notebook,
        planApprovalRequester: xyz.mederi.plan.PlanApprovalRequester,
        subagentRole: SubagentRole? = null
    ) {
        val newContextWindowFlag = AtomicBoolean(false)
        val frameChannel = Channel<StreamFrame>(Channel.UNLIMITED)
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
            diagnosticsProvider = diagnosticsProvider
        )

        val diagnostics = MessageDiagnostics(
            providerId = provider.id,
            modelId = model.id,
            modelName = model.name,
            reasoningLevel = reasoningLevel.name,
            agentMode = agentMode.name,
            workType = workType.name,
            projectId = session.projectId
        )
        val toolTimings = TurnToolTimings()
        val historyProvider = TrackingHistoryProvider(
            HistoryStoreChatHistoryProvider(historyStore, diagnostics, toolTimings)
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
            val apiKey = providerManager.getDefaultKeyValue(provider.id)
                ?: throw IllegalStateException("No API key available for provider: ${provider.id}")

            DebugLog.section("TurnExec", "TurnExecutor.runTurn")
            DebugLog.data("TurnExec", "apiKey", "${apiKey.take(4)}****${apiKey.takeLast(4)}")
            DebugLog.data("TurnExec", "inputText", "'$inputText'")
            DebugLog.data("TurnExec", "provider.type", provider.type)
            DebugLog.data("TurnExec", "provider.baseUrl", provider.baseUrl)

            preflightCompressionIfNeeded(session, provider, model, reasoningLevel, systemPrompt)

            val questionRequester = xyz.mederi.question.QuestionRequester(sessionId, eventBus)
            questionRequesters[sessionId] = questionRequester

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
                workType = workType,
                subagentRole = subagentRole,
                planApprovalRequester = planApprovalRequester,
                planStore = planStore,
                notebook = notebook,
                commandSandbox = commandSandbox,
                sessionStore = sessionStore,
                mcpTools = mcpSession?.tools ?: emptyList()
            )

            val agent = buildTurnAgent(
                sessionId = sessionId,
                provider = provider,
                apiKey = apiKey,
                model = model,
                reasoningLevel = reasoningLevel,
                systemPrompt = systemPrompt,
                toolRegistry = toolRegistry,
                historyProvider = historyProvider,
                toolTimings = toolTimings,
                frameChannel = frameChannel,
                streamWarning = streamWarning,
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

            // 先通知 UI 完成，再做后续清理（diff 追踪等）
            // 这样按钮立即从"停止"变回"发送"，不需要等 diff/history I/O
            sessionStore.update(sessionId, SessionStatus.IDLE)
            DebugLog.event("TurnExec", "runTurn success, session status = IDLE (streamWarning=${streamWarning.get() != null}, record=${streamWarningRecord.get()?.id})")
            // 流式期间有传输异常（静默断流/消费错误）→ 随完成事件带给 UI：
            // 内容不回滚（半截文本已落库，用户能看到已生成部分），只提示不完整。
            // 有结构化警告记录时合并其 payload（error/errorId/fullDiagnostic）→ ErrorBoard 展示 + 可展开详细报告；
            // 只留文本时退化为 warning key（兼容旧消费方）。
            val warning = streamWarning.get()
            val warningRecord = streamWarningRecord.get()
            val payload = when {
                warningRecord != null -> {
                    val record = warningRecord as xyz.mederi.debug.ErrorRecord
                    record.toPayload() + mapOf("warning" to (warning ?: ""))
                }
                warning != null -> mapOf("warning" to warning as String)
                else -> null
            }
            emit(
                sessionId,
                EventType.MESSAGE_COMPLETED,
                payload = payload ?: emptyMap()
            )

            // 后续清理：diff 追踪 + 保存（不影响 UI 响应）
            diffTracker.captureSnapshot()
            DebugLog.event("TurnExec", "diffTracker snapshot captured")
            diffStore?.save(diffTracker.buildDiff(null, Instant.now().toString()))
            DebugLog.event("TurnExec", "diffStore saved")

        } catch (e: kotlinx.coroutines.CancellationException) {
            DebugLog.event("TurnExec", "runTurn cancelled: ${e.message}")
            frameChannel.close(e)
            streamConsumer.join()
            sessionStore.update(sessionId, SessionStatus.IDLE)
            // 这里不再补发 MESSAGE_ERROR：所有主动取消都经由 abort()（已发"用户中止了对话"）
            // 或 abortAndJoin()（回滚场景，紧随的 rollback 事件接管 UI）。若在此补发，
            // 滞后到达的 ERROR 事件会污染回滚后新建 turn 的 UI 状态（错误横幅误亮）
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
            if (RetryableLLMClient.isTransientError(e)) {
                // 限流/网关过载 = 环境态：重试已耗尽但供应商是暂时不可用，session 保持 IDLE
                // （可恢复状态，用户稍后重发即可），不把会话标成 ERROR
                sessionStore.update(sessionId, SessionStatus.IDLE)
                emit(sessionId, EventType.MESSAGE_ERROR, payload = record.toPayload())
            } else {
                sessionStore.update(sessionId, SessionStatus.ERROR)
                emit(sessionId, EventType.MESSAGE_ERROR, payload = record.toPayload())
            }
        } finally {
            activeJobs.remove(sessionId)
            questionRequesters.remove(sessionId)?.cancelAll()
            planApprovalRequesters.remove(sessionId)?.cancelAll()
            if (mcpSession != null) {
                try {
                    mcpSession.close()
                } catch (e: Exception) {
                    DebugLog.error("TurnExec", "关闭 MCP 连接失败: ${e.message}", e)
                }
            }
        }
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
        frameChannel: Channel<StreamFrame>,
        streamWarning: AtomicReference<String?>,
        streamWarningRecord: AtomicReference<xyz.mederi.debug.ErrorRecord?>,
        providerId: String,
        providerName: String,
        modelId: String,
        modelName: String,
        diagnosticsProvider: () -> StreamCloseDiagnostics? = { null }
    ) = scope.launch {
        val timing = StreamTimingLog("SSE-Timing", "delta frames")
        val traceSession = StreamTrace.beginSession(sessionId)
        var exitNote = "drained"
        try {
            for (frame in frameChannel) {
                when (frame) {
                    is StreamFrame.TextDelta -> {
                        timing.sample(frame.text.length)
                        frame.text.takeIf { it.isNotEmpty() }?.let {
                            StreamTrace.delta(traceSession, "text", it.length)
                            emitDelta(sessionId, "text", it)
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
                            emitDelta(sessionId, "reasoning", it)
                        }
                    }
                    is StreamFrame.ToolCallDelta -> {
                        // 续片 delta（deepseek 分片：id/name 空只有 args 增量）不发事件，
                        // 避免空壳 TOOL_CALL 污染 UI 流式视图。完整调用由 onToolCallStarting 带 real args 发。
                        if (frame.name.isNullOrBlank()) continue
                        timing.sample(frame.content?.length ?: 0)
                        DebugLog.debug("Frame", "ToolCallDelta: name=${frame.name}, contentLen=${frame.content?.length ?: 0}")
                        emitToolDelta(sessionId, name = frame.name!!, args = frame.content ?: "", complete = false)
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
                        emitToolDelta(sessionId, name = frame.name!!, args = frame.content, complete = true)
                    }
                    is StreamFrame.End -> {
                        // End 也是轮次边界（tool loop 每轮一个 End）。finishReason=null 的 End
                        // = 上游没给收尾标记（SSE 连接断开时 lines() flow 正常结束、emitEnd
                        // 无 finishReason）→ turn 会"成功"收尾但回复被静默截断。
                        // 生成 WARNING 级 ErrorRecord 走统一错误出口（ErrorBoard），并留人类提示文本。
                        if (frame.finishReason == null) {
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
     * 发送前压缩（pre-flight）：已占用上下文 > 70% 窗口时，
     * 先压缩（生成 TLDR、插标记）再发送。保证到达 AI 的第一个请求就不携带
     * 臃肿历史——压缩发生在请求之前，而不是超限请求发出之后。
     * durable-first 后用户消息已在 [HistoryStoreChatHistoryProvider.aiViewWindow] 窗口内，
     * 直接按窗口估算即可，不再单独累加新消息 token。
     * 压缩失败不阻塞本轮对话（安全兜底）：照常发送，运行期检查仍会兜底。
     */
    private suspend fun preflightCompressionIfNeeded(
        session: xyz.mederi.domain.model.Session,
        provider: xyz.mederi.provider.domain.model.Provider,
        model: AIModel,
        reasoningLevel: ReasoningLevel,
        systemPrompt: String
    ) {
        val contextWindow = model.contextWindow ?: return
        val window = HistoryStoreChatHistoryProvider.aiViewWindow(historyStore, session.id)
        if (window.isEmpty()) return

        val koogWindow = KoogMessageMapper.toKoogMessages(window)
        // 窗口以 SUMMARY 标记开头时，窗口内消息的 inputTokens 反映的是压缩前的
        // 大上下文，不能当基线，改用估算；正常窗口用 API 报告的真实值
        val usedTokens = if (window.first().role == MessageRole.SUMMARY) {
            estimateTokens(koogWindow)
        } else {
            contextUsedTokens(koogWindow)
        }
        val budget = (contextWindow * 0.70).toInt()
        if (usedTokens <= budget) return

        DebugLog.event(
            "TurnExec",
            "pre-flight compression triggered: used=$usedTokens > budget=$budget"
        )
        runCatching {
            compressOnce(session, provider, model, reasoningLevel, systemPrompt)
        }.onFailure {
            DebugLog.error("TurnExec", "pre-flight compression failed: ${it.message}", it)
        }
    }

    /**
     * 限流/临时故障重试包装：LLM 请求层面的自动重试（默认 10 次，随机 1~10s 退避），
     * 重试进度经 STATUS 事件推给 UI（状态栏"重试中"）。core 业务流程零感知——
     * 对 agent 来说只是一次变慢的请求；maxRetries=0 时直通原 client，零开销。
     */
    private fun retryWrapped(client: ai.koog.prompt.executor.clients.LLMClient, sessionId: String): ai.koog.prompt.executor.clients.LLMClient {
        if (LlmRetryConfig.maxRetries <= 0) return client
        return RetryableLLMClient(
            delegate = client,
            maxRetries = LlmRetryConfig.maxRetries,
            minDelayMs = LlmRetryConfig.minDelayMs,
            maxDelayMs = LlmRetryConfig.maxDelayMs,
            statusBus = eventBus,
            sessionIdProvider = { sessionId }
        )
    }

    /** 组装本轮对话 agent：client/executor/model/params/prompt/config + 工具注册表 + 压缩/流式安装 */
    private fun buildTurnAgent(
        sessionId: String,
        provider: xyz.mederi.provider.domain.model.Provider,
        apiKey: String,
        model: AIModel,
        reasoningLevel: ReasoningLevel,
        systemPrompt: String,
        toolRegistry: ToolRegistry,
        historyProvider: TrackingHistoryProvider,
        toolTimings: TurnToolTimings,
        frameChannel: Channel<StreamFrame>,
        streamWarning: AtomicReference<String?>,
        clientRef: AtomicReference<ai.koog.prompt.executor.clients.LLMClient?> = AtomicReference(null),
        diagnostics: MessageDiagnostics = MessageDiagnostics()
    ): AIAgent<String, String> {
        val client = retryWrapped(KoogClientFactory.create(provider, apiKey), sessionId)
        clientRef.set(client)
        DebugLog.data("TurnExec", "client", client::class.simpleName)
        // 增量持久化器：LLM 响应/tool results 到达即落库，turn 中途崩溃不丢历史
        // 带 diagnostics：增量落库即打模型/模式/推理档/耗时元数据——否则 reconcile 时
        // assistant 消息被"已存在"识别、诊断字段永不补上，UI footer 只剩完成时间
        val incrementalPersister = TurnIncrementalPersister(sessionId, historyStore, diagnostics)

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
                    usedTokens > (contextWindow * 0.70).toInt()
                },
                compressionStrategy = MederiCompressionStrategy(),
                retrievalModel = null
            )
        }

        return AIAgent.builder()
            .promptExecutor(executor)
            .agentConfig(agentConfig)
            .toolRegistry(toolRegistry)
            .graphStrategy(
                if (compressionConfig != null) {
                    mederiSingleRunStrategyWithCompression(compressionConfig, incrementalPersister)
                } else {
                    mederiSingleRunStrategy(incrementalPersister)
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
                    frameChannel.trySend(eventContext.streamFrame)
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
                    scope.launch {
                        emit(sessionId, EventType.TOOL_CALLED, payload = mapOf(
                            "tool" to eventContext.toolName,
                            "toolCallId" to (eventContext.toolCallId ?: ""),
                            "args" to eventContext.toolArgs.toString()
                        ))
                    }
                }
                config.onToolCallCompleted { eventContext ->
                    toolTimings.onCompleted(eventContext.toolCallId)
                    DebugLog.event("EventHandler", "onToolCallCompleted: tool=${eventContext.toolName}, toolCallId=${eventContext.toolCallId}")
                    scope.launch {
                        emit(sessionId, EventType.TOOL_RESULT, payload = mapOf(
                            "tool" to eventContext.toolName,
                            "toolCallId" to (eventContext.toolCallId ?: ""),
                            "output" to (eventContext.toolResult?.toString() ?: ""),
                            "isError" to "false"
                        ))
                    }
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
                    scope.launch {
                        emit(sessionId, EventType.TOOL_RESULT, payload = mapOf(
                            "tool" to eventContext.toolName,
                            "toolCallId" to (eventContext.toolCallId ?: ""),
                            "output" to eventContext.message,
                            "isError" to "true"
                        ))
                    }
                }
            }
            .build()
    }

    private suspend fun emitDelta(sessionId: String, type: String, content: String) {        emit(
            sessionId = sessionId,
            type = EventType.MESSAGE_DELTA,
            payload = mapOf("type" to type, "content" to content)
        )
    }

    private suspend fun emitToolDelta(sessionId: String, name: String, args: String, complete: Boolean) {
        emit(
            sessionId = sessionId,
            type = EventType.MESSAGE_DELTA,
            payload = mapOf(
                "type" to "tool_call",
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
            DebugLog.debug("EventBus", "emit: type=$type, sessionId=$sessionId, messageId=$messageId, payload keys=${payload.keys}")
            eventBus.emit(
                MederiEvent(
                    type = type,
                    sessionId = sessionId,
                    messageId = messageId,
                    payload = payload,
                    timestamp = Instant.now().toString()
                )
            )
        } catch (e: Throwable) {
            DebugLog.error("EventBus", "Failed to emit event $type for session $sessionId: ${e.message}", e)
        }
    }
}
