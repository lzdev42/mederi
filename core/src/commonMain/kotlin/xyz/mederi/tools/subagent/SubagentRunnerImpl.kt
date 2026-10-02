package xyz.mederi.tools.subagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
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
    private val providerManager: ProviderManager,
    private val projectManager: ProjectManager,
    private val mcpConnector: McpConnector? = null,
    private val skills: SkillManager? = null
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
        onProgress: (suspend (activity: String, delta: String, toolName: String?, isMessage: Boolean) -> Unit)?
    ): String {
        val sessionId = agentId ?: "sub_${UUID.randomUUID().toString().take(8)}"
        val now = Instant.now().toString()

        val sessionStore = InMemorySessionStore()
        val historyStore = InMemoryHistoryStore()
        val eventBus = MutableSharedFlow<MederiEvent>(replay = 64)
        // 文件写入缓冲：子代理的文件工具经 onFileTouched 回调 append，turn 结束 finally 时 flush 到 PlanStore。
        val touchedFiles = java.util.concurrent.ConcurrentLinkedQueue<String>()

        val session = Session(
            id = sessionId,
            projectId = projectId,
            title = "Subagent",
            status = SessionStatus.IDLE,
            agentMode = AgentMode.AUTONOMOUS,
            aiModel = aiModel,
            reasoningLevel = reasoningLevel,
            env = emptyMap(),
            createdAt = now,
            updatedAt = now
        )
        sessionStore.insert(session)

        val turnExecutor = TurnExecutor(
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
            scope = CoroutineScope(coroutineContext + SupervisorJob()),
            // 文件写入追踪：写工具成功后回调入队，turn 结束时 flush（见 flushTouchedFiles）
            onFileTouched = { path -> touchedFiles.add(path) },
            // 子 turn 的 TurnDiff 回传：合并进父会话当前活跃 turn 的 diff tracker——
            // 修复"turn 结束改动摘要不含子代理改动"（子代理 diffStore 为 null，这是唯一出口）。
            // 父 turn 已结束时 mergeInto 按注册表缺失丢弃并记日志（见 ParentDiffRegistry）。
            onTurnDiff = { diff ->
                xyz.mederi.tools.diff.ParentDiffRegistry.mergeInto(parentSessionId, diff.changes)
            }
        )

        val inputText = when (role) {
            SubagentRole.EXECUTOR -> buildString {
                append("You are a subagent executor: execute the task directly with your tools. ")
                append("Do not create plans or spawn agents.\n\n")
                if (!plan.isNullOrBlank()) {
                    append("Work through the spec checklist TOP-DOWN, item by item, in order. ")
                    append("Do not skip items and do not improvise additions. If an item proves impossible ")
                    append("as written, stop rather than improvise, and say so explicitly in your final answer ")
                    append("under a line 'SPEC_FEEDBACK: <what in the spec contradicts reality>'.\n\n")
                } else {
                    append("Execute the task to completion. Read files first to understand the codebase ")
                    append("before making changes. If something proves impossible, stop and report it ")
                    append("in your final answer.\n\n")
                }
                append(task)
                if (!briefing.isNullOrBlank()) {
                    append("\n\nAdditional info:\n")
                    append(briefing)
                }
                if (!plan.isNullOrBlank()) {
                    append("\n\nSpec checklist to execute (top-down):\n")
                    append(plan)
                }
            }
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

        // 窗口聚合：高频 MESSAGE_DELTA → 低频 SUBAGENT_PROGRESS（≥throttle 才 emit 一帧），
        // tool_call 帧只带工具名、参数分片不进 delta（聚合器规则，见 SubagentProgressBatch）。
        val progressBatch = SubagentProgressBatch()
        val progressJob = CoroutineScope(coroutineContext + SupervisorJob()).launch {
            eventBus.filter { it.sessionId == sessionId }.collect { ev ->
                when (ev.type) {
                    EventType.MESSAGE_DELTA -> {
                        val deltaType = ev.payload["type"] ?: "text"
                        val content = ev.payload["content"].orEmpty()
                        val activity = when (deltaType) {
                            "reasoning" -> SubagentActivity.THINKING
                            "tool_call" -> SubagentActivity.TOOL_CALL
                            else -> SubagentActivity.OUTPUT
                        }
                        // 正文/推理帧都进输出视窗；工具参数分片（type=tool_call）不累积
                        progressBatch.add(
                            activity, content, ev.payload["name"],
                            isMessage = deltaType == "text",
                            accumulateDelta = deltaType != "tool_call"
                        )
                    }
                    EventType.TOOL_CALLED -> {
                        progressBatch.add(SubagentActivity.TOOL_CALL, "", ev.payload["tool"], false)
                    }
                    else -> {}
                }
                progressBatch.flushIfDue()?.let { frame ->
                    onProgress?.invoke(frame.activity, frame.delta, frame.toolName, frame.isMessage)
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
            val terminalEvent = eventBus
                .filter { it.sessionId == sessionId }
                .first {
                    it.type == EventType.MESSAGE_COMPLETED || it.type == EventType.MESSAGE_ERROR
                }

            if (terminalEvent.type == EventType.MESSAGE_ERROR) {
                val error = terminalEvent.payload["error"] ?: "Subagent encountered an error."
                return "[subagent error] $error"
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
            "[subagent error] ${e.message ?: e.javaClass.simpleName}"
        } finally {
            // 尾部冲刷：把窗口内残留的文本增量在取消前补发一次（不丢最后一段流式输出）
            progressBatch.flushNow()?.let { frame ->
                onProgress?.invoke(frame.activity, frame.delta, frame.toolName, frame.isMessage)
            }
            progressJob.cancel()
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
}
