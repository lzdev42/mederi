package xyz.mederi.tools.subagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlin.coroutines.coroutineContext
import xyz.mederi.api.AgentConfig
import xyz.mederi.api.SendMessageRequest
import xyz.mederi.domain.model.AgentMode
import xyz.mederi.domain.model.AIModel
import xyz.mederi.domain.model.EventType
import xyz.mederi.domain.model.MederiEvent
import xyz.mederi.domain.model.MessagePart
import xyz.mederi.domain.model.MessageRole
import xyz.mederi.domain.model.Session
import xyz.mederi.domain.model.SessionStatus
import xyz.mederi.domain.model.SubagentRole
import xyz.mederi.infrastructure.koog.TurnExecutor
import xyz.mederi.mcp.engine.McpConnector
import xyz.mederi.project.ProjectManager
import xyz.mederi.provider.ProviderManager
import xyz.mederi.provider.domain.model.ReasoningLevel
import xyz.mederi.skills.SkillManager
import xyz.mederi.plan.PlanStore
import xyz.mederi.store.InMemoryHistoryStore
import xyz.mederi.store.InMemorySessionStore
import java.time.Instant
import java.util.UUID
/**
 * [SubagentRunner] 的默认实现。
 *
 * 在内存中创建临时 Session 和 HistoryStore，使用独立的 [TurnExecutor] 运行单 turn。
 * 子 Agent 使用 AUTONOMOUS agentMode。
 * 子 Agent 是否继承 MCP / skills 由中心化 [xyz.mederi.domain.model.AgentCapabilities] 表
 * 决定（runTurn 内部据此开关），本类只负责把父级注入的 [mcpConnector] / [skills] 传递下去。
 * 所有异常都在内部 catch 并转换为错误信息字符串返回，不会抛到父 Agent。
 */
class SubagentRunnerImpl(
    private val sandboxManager: xyz.mederi.tools.sandbox.SandboxManager = xyz.mederi.tools.sandbox.SandboxManager(),
    private val providerManager: ProviderManager,
    private val projectManager: ProjectManager,
    private val mcpConnector: McpConnector? = null,
    private val skills: SkillManager? = null,
    private val compactionThresholdPercent: () -> Int = { 70 },
    private val workspaceResolver: xyz.mederi.workspace.ExecutionWorkspaceResolver? = null,
    /** RF-02：子代理继承父级 LLM 传输工厂（集成测试注入确定性 fake LLM）。 */
    private val llmClientFactory: (xyz.mederi.provider.domain.model.Provider, String) -> ai.koog.prompt.executor.clients.LLMClient = xyz.mederi.provider.infrastructure.koog.KoogClientFactory::create,
    /** RF-07：工作区执行租约（父级注入）。子代理 turn 同样持租约，归档等待窗口覆盖活跃子代理。 */
    private val leaseManager: xyz.mederi.workspace.WorkspaceLeaseManager? = null,
) : SubagentRunner {
    override suspend fun run(
        task: String,
        briefing: String?,
        plan: String?,
        role: SubagentRole,
        directories: List<String>,
        aiModel: AIModel,
        reasoningLevel: ReasoningLevel,
        projectId: String,
        parentSessionId: String,
        apiKeyId: String?,
        planId: String?,
        executorSubtaskIndex: Int?,
        planStore: PlanStore?,
        agentId: String?,
        onProgress: (suspend (MederiEvent) -> Unit)?
    ): String {
        val sessionId = agentId ?: "sub_${UUID.randomUUID().toString().take(8)}"
        val now = Instant.now().toString()

        val sessionStore = InMemorySessionStore()
        val historyStore = InMemoryHistoryStore()
        val eventBus = MutableSharedFlow<MederiEvent>(replay = 64)
        // 文件写入缓冲：子代理的文件工具经 onFileTouched 回调 append，turn 结束 finally 时 flush 到 PlanStore。
        val touchedFiles = java.util.concurrent.ConcurrentLinkedQueue<String>()

        val execWs = runCatching { workspaceResolver?.resolve(parentSessionId) }.getOrNull()
        val session = Session(
            id = sessionId,
            projectId = projectId,
            title = "Subagent",
            status = SessionStatus.IDLE,
            agentMode = AgentMode.AUTONOMOUS,
            aiModel = aiModel,
            reasoningLevel = reasoningLevel,
            workspaceId = execWs?.workspaceId,
            env = emptyMap(),
            createdAt = now,
            updatedAt = now
        )
        sessionStore.insert(session)

        val executionJob = SupervisorJob(coroutineContext[Job])
        val turnExecutor = TurnExecutor(
            sandboxManager = sandboxManager,
            approvalConversationId = parentSessionId,
            sessionStore = sessionStore,
            historyStore = historyStore,
            eventBus = eventBus,
            providerManager = providerManager,
            projectManager = projectManager,
            diffStore = null,
            mcpConnector = mcpConnector,
            skills = skills,
            // 继承调用方协程上下文：外部取消（SubagentManager.stop）能级联取消内部 turn，
            // 避免"外层 job 取消但内层 Koog turn 继续跑"的资源泄漏。
            scope = CoroutineScope(coroutineContext + executionJob),
            // 文件写入追踪：写工具成功后回调入队，turn 结束时 flush（见 flushTouchedFiles）
            onFileTouched = { path -> touchedFiles.add(path) },
            // 子 turn 的 TurnDiff 回传：合并进父会话当前活跃 turn 的 diff tracker——
            // 修复"turn 结束改动摘要不含子代理改动"（子代理 diffStore 为 null，这是唯一出口）。
            // 父 turn 已结束时 mergeInto 按注册表缺失丢弃并记日志（见 ParentDiffRegistry）。
            onTurnDiff = { diff ->
                xyz.mederi.tools.diff.ParentDiffRegistry.mergeInto(parentSessionId, diff.changes, originWorkspaceId = diff.workspaceId)
            },
            compactionThresholdPercent = compactionThresholdPercent,
            workspaceResolver = workspaceResolver,
            inheritedWorkspace = execWs,
            llmClientFactory = llmClientFactory,
            // RF-07：子代理 turn 持执行租约，归档/配额等待含子代理执行。
            leaseManager = leaseManager,
        )

        val inputText = when (role) {
            SubagentRole.EXECUTOR -> buildExecutorInput(task, briefing, plan)
            SubagentRole.RESEARCHER -> buildString {
                append("You are a research subagent: investigate the task below using read-only tools. ")
                append("Read files, list directories, cross-reference, and report findings. ")
                append("Do not modify files, do not run commands.\n\n")
                append(task)
                if (!briefing.isNullOrBlank()) {
                    append("\n\nBackground info from the parent:\n")
                    append(briefing)
                }
            }
            SubagentRole.BROWSER_OPERATOR,
            SubagentRole.BROWSER_BRAIN ->
                throw IllegalStateException("BROWSER_* roles are configuration-only: browser tasks run via BrowserTaskManager, not SubagentRunner")
        }

        // 一个消费者拥有聚合器与终态确认；终态必须在所有先前事件转发后才可返回。
        val terminal = CompletableDeferred<MederiEvent>()
        val progressBatch = SubagentProgressBatch()
        val forwardedTypes = setOf(EventType.SESSION_UPDATED, EventType.MESSAGE_DELTA,
            EventType.TOOL_CALLED, EventType.TOOL_RESULT, EventType.MESSAGE_COMPLETED,
            EventType.MESSAGE_ERROR, EventType.STATUS)
        val progressJob = CoroutineScope(coroutineContext).launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                eventBus.filter { it.sessionId == sessionId }.takeWhile { event ->
                    if (event.type in forwardedTypes) {
                        for (frame in progressBatch.add(event)) onProgress?.invoke(frame)
                    }
                    val isTerminal = event.type == EventType.MESSAGE_COMPLETED || event.type == EventType.MESSAGE_ERROR
                    if (isTerminal) terminal.complete(event)
                    !isTerminal
                }.collect { }
            } catch (e: Throwable) {
                terminal.completeExceptionally(e)
                throw e
            } finally {
                withContext(NonCancellable) {
                    progressBatch.flushNow()?.let { onProgress?.invoke(it) }
                }
            }
        }

        return try {
            turnExecutor.sendMessage(
                sessionId = sessionId,
                request = SendMessageRequest(
                    agentConfig = AgentConfig(
                        agentMode = AgentMode.AUTONOMOUS,
                        aiModel = aiModel,
                        reasoningLevel = reasoningLevel
                    ),
                    parts = listOf(MessagePart.Text(inputText)),
                    // 继承父 Agent 选定 key：子代理与父代理同供应商，用同一把 key
                    apiKeyId = apiKeyId
                ),
                // 子代理标记：切换系统提示词与能力表（AgentCapabilities），工具集按角色裁剪
                subagentRole = role
            )

            // 阻塞等待子 Agent turn 结束
            val terminalEvent = terminal.await()

            if (terminalEvent.type == EventType.MESSAGE_ERROR) return subagentErrorReport(terminalEvent.payload)
            if (terminalEvent.payload["finalMessageId"].isNullOrBlank()) {
                // 兼容旧完成事件：已有诊断必须保真传递，不能覆盖为无上下文的通用错误。
                if (!terminalEvent.payload["error"].isNullOrBlank()) {
                    onProgress?.invoke(terminalEvent.copy(type = EventType.MESSAGE_ERROR))
                    return subagentErrorReport(terminalEvent.payload)
                }
                // 没有完整交付也必须进入统一诊断出口，不能只返回一句无上下文的失败字符串。
                val record = xyz.mederi.debug.ErrorCollector.collect(
                    IllegalStateException("Subagent finished without a complete final response."),
                    xyz.mederi.debug.ErrorContext(phase = "subagent_completion", sessionId = sessionId,
                        modelId = aiModel.id, modelName = aiModel.name))
                onProgress?.invoke(MederiEvent(type = EventType.MESSAGE_ERROR, sessionId = sessionId,
                    payload = record.toPayload(), timestamp = record.timestamp))
                return subagentErrorReport(record.toPayload())
            }

            val messages = historyStore.load(sessionId)
            val assistantMessage = messages.lastOrNull { it.role == MessageRole.ASSISTANT }
            val fullText = assistantMessage?.parts
                ?.filterIsInstance<MessagePart.Text>()
                ?.joinToString("") { it.text }
                ?: "[subagent completed with no response]"
            // 落盘报告：有 planId+planStore 时写盘并返回摘要+路径（减少父上下文 token），
            // 否则全文回灌（无 plan 上下文的 researcher 保持原行为）。
            persistReportAndReturnSummary(fullText, role, planId, executorSubtaskIndex, planStore)
        } catch (e: CancellationException) {
            // 协程规范：取消必须重新抛出，不得吞掉（外部 SubagentManager 据此标记 STOPPED）
            throw e
        } catch (e: Throwable) {
            val record = xyz.mederi.debug.ErrorCollector.collect(e, xyz.mederi.debug.ErrorContext(
                phase = "subagent", sessionId = sessionId, modelId = aiModel.id, modelName = aiModel.name))
            onProgress?.invoke(MederiEvent(type = EventType.MESSAGE_ERROR, sessionId = sessionId,
                payload = record.toPayload(), timestamp = record.timestamp))
            subagentErrorReport(record.toPayload())
        } finally {
            // 聚合器仅由订阅协程操作，避免finally与collect并发冲刷造成乱序/重复。
            withContext(NonCancellable) {
                progressJob.cancelAndJoin()
                executionJob.cancelAndJoin()
            }
            // 无论成功/失败/取消，都把已写入的文件清单 flush 回 PlanStore——agent 丢失后父代理可据此恢复/查越界。
            if (planId != null && executorSubtaskIndex != null && planStore != null && touchedFiles.isNotEmpty()) {
                val files = touchedFiles.toList()
                planStore.updatePlan(planId) { p ->
                    p.copy(subtasks = p.subtasks.map { st ->
                        if (st.index == executorSubtaskIndex) st.copy(executorTouchedFiles = (st.executorTouchedFiles + files).distinct())
                        else st
                    })
                }
            }
        }
    }

    /**
     * 落盘子代理报告并返回摘要（父上下文用）。
     *
     * - EXECUTOR + planId：写 `{planId}/reports/NN-executor.md`，返回"已保存+尾部1500字符"。
     *   尾部捕获 SPEC_FEEDBACK（位于报告末尾，父 agent 据此决定 re-generate_spec / ask_user）。
     * - RESEARCHER + planId：写 `{planId}/research.md`，返回"已保存+头部800字符"。
     *   头部通常是核心结论。
     * - RESEARCHER 无 planId：写 `.mederi/research/{timestamp}.md`，返回"已保存+头部800字符"。
     *   研究报告始终落盘——不让全量报告撑大父上下文。
     * - EXECUTOR 无 planId / planStore 为 null / 写盘失败：回退全文回灌。
     */
    private fun persistReportAndReturnSummary(
        fullText: String,
        role: SubagentRole,
        planId: String?,
        executorSubtaskIndex: Int?,
        planStore: PlanStore?
    ): String {
        if (planStore == null) return fullText
        return when (role) {
            SubagentRole.EXECUTOR -> {
                if (planId == null) return fullText
                val idx = executorSubtaskIndex ?: return fullText
                val path = runCatching { planStore.writeExecutorReport(planId, idx, fullText) }
                    .getOrNull() ?: return fullText
                buildString {
                    appendLine("[executor report saved to $path]")
                    appendLine("Tail (last 1500 chars — SPEC_FEEDBACK lives here if any):")
                    append(fullText.takeLast(1500))
                }
            }
            SubagentRole.RESEARCHER -> {
                val path = if (planId != null) {
                    runCatching { planStore.writeResearchReport(planId, fullText) }.getOrNull()
                } else {
                    runCatching { planStore.writeStandaloneResearchReport(fullText) }.getOrNull()
                } ?: return fullText
                buildString {
                    appendLine("[research report saved to $path]")
                    appendLine("Summary (first 800 chars):")
                    append(fullText.take(800))
                }
            }
            SubagentRole.BROWSER_OPERATOR, SubagentRole.BROWSER_BRAIN -> fullText
        }
    }

    companion object {
        /**
         * 组装执行器输入文本（纯函数，供实际派工与测试共用）。
         *
         * - Plan 路径（spec 非空）：spec 是唯一实现指令来源。task 和 briefing 都不进输入——
         *   task 仅为标识（日志/UI），briefing（planDetail）可能含处理逻辑，与 spec 产生两套要求。
         *   执行器只看到 spec 清单，不需要参与判断其他文本与 spec 的关系。
         * - Ad-hoc 路径（spec 为空）：task + briefing 是执行器的完整指令。
         *
         * 纪律指令（SPEC_FEEDBACK 类别、不猜边界、编译命令由 spec 指定等）全部在执行器系统提示词
         * [xyz.mederi.prompt.SystemPrompts.forSubagent] 中，此函数不重复——避免两处漂移。
         */
        internal fun buildExecutorInput(task: String, briefing: String?, plan: String?): String {
            return if (!plan.isNullOrBlank()) {
                // Plan 路径：spec 唯一真理源，task/briefing 都不进输入
                "Spec checklist to execute (top-down):\n$plan"
            } else {
                // Ad-hoc 路径：task + briefing 是完整指令
                buildString {
                    append(task)
                    if (!briefing.isNullOrBlank()) {
                        append("\n\nAdditional info:\n")
                        append(briefing)
                    }
                }
            }
        }
    }
}
