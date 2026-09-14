package xyz.mederi.tools.subagent

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
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
import xyz.mederi.domain.model.WorkType
import xyz.mederi.infrastructure.koog.TurnExecutor
import xyz.mederi.mcp.engine.McpConnector
import xyz.mederi.project.ProjectManager
import xyz.mederi.provider.ProviderManager
import xyz.mederi.provider.domain.model.ReasoningLevel
import xyz.mederi.skills.SkillManager
import xyz.mederi.store.InMemoryHistoryStore
import xyz.mederi.store.InMemorySessionStore
import java.time.Instant
import java.util.UUID

/**
 * [SubagentRunner] 的默认实现。
 *
 * 在内存中创建临时 Session 和 HistoryStore，使用独立的 [TurnExecutor] 运行单 turn。
 * 子 Agent 继承父 Agent 的 workType，使用 AUTONOMOUS agentMode。
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
        workType: WorkType,
        directories: List<String>,
        aiModel: AIModel,
        reasoningLevel: ReasoningLevel,
        projectId: String,
        parentSessionId: String
    ): String {
        val sessionId = "sub_${UUID.randomUUID().toString().take(8)}"
        val now = Instant.now().toString()

        val sessionStore = InMemorySessionStore()
        val historyStore = InMemoryHistoryStore()
        val eventBus = MutableSharedFlow<MederiEvent>(replay = 64)

        val session = Session(
            id = sessionId,
            projectId = projectId,
            title = "Subagent",
            status = SessionStatus.IDLE,
            agentMode = AgentMode.AUTONOMOUS,
            workType = workType,
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
            skills = skills
        )

        val inputText = when (role) {
            SubagentRole.EXECUTOR -> buildString {
                append("You are a subagent executor: execute the task directly with your tools. ")
                append("Do not create plans or spawn agents.\n\n")
                append("Work through the spec checklist TOP-DOWN, item by item, in order. ")
                append("Do not skip items and do not improvise additions. If an item proves impossible ")
                append("as written, stop rather than improvise, and say so explicitly in your final answer ")
                append("under a line 'SPEC_FEEDBACK: <what in the spec contradicts reality>'.\n\n")
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
        }

        return try {
            turnExecutor.sendMessage(
                sessionId = sessionId,
                request = SendMessageRequest(
                    agentConfig = AgentConfig(
                        agentMode = AgentMode.AUTONOMOUS,
                        workType = workType,
                        aiModel = aiModel,
                        reasoningLevel = reasoningLevel
                    ),
                    parts = listOf(MessagePart.Text(inputText))
                ),
                // 子代理标记：豁免计划门禁（spawn 入口已校验存在批准计划），工具集按角色裁剪
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
            assistantMessage?.parts
                ?.filterIsInstance<MessagePart.Text>()
                ?.joinToString("") { it.text }
                ?: "[subagent completed with no response]"
        } catch (e: Throwable) {
            "[subagent error] ${e.message ?: e.javaClass.simpleName}"
        }
    }
}
