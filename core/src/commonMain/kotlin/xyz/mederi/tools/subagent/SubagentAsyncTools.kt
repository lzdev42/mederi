package xyz.mederi.tools.subagent

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable

/**
 * 子 Agent 异步生命周期管理工具集（内部实现，不向 AI 注册）。
 *
 * spawn_agent / spawn_researcher 改为异步后返回 agentId，父 Agent 用这套工具
 * 查询状态、主动停止。子 Agent 运行期间父 Agent 可以继续对话、派发其他任务。
 * 完成时经 eventBus 终态事件自动唤醒父 turn，无需阻塞等待。
 */

/** agent_status 工具参数。 */
@Serializable
data class AgentStatusArgs(
    @LLMDescription("Sub-agent ID (agentId returned by SPAWN).")
    val agentId: String = ""
)

/** 查询子代理状态：RUNNING / COMPLETED / ERROR / STOPPED / NOT_FOUND。 */
class AgentStatusTool(
    private val subagentManager: SubagentManager
) : SimpleTool<AgentStatusArgs>(
    argsType = typeToken<AgentStatusArgs>(),
    name = "agent_status",
    description = "Queries the status of an asynchronously running subagent (spawned by spawn_agent / " +
        "spawn_researcher). Returns RUNNING / COMPLETED / ERROR / STOPPED / NOT_FOUND plus the final " +
        "result when available. Does not block."
) {
    override suspend fun execute(args: AgentStatusArgs): String {
        if (args.agentId.isBlank()) return "Error: agentId must not be empty."
        return subagentManager.status(args.agentId)
    }
}

/** stop_agent 工具参数。 */
@Serializable
data class StopAgentArgs(
    @LLMDescription("Sub-agent ID to stop (from SPAWN).")
    val agentId: String = ""
)


/** 停止正在运行的子代理，返回部分结果（若有）。 */
class StopAgentTool(
    private val subagentManager: SubagentManager
) : SimpleTool<StopAgentArgs>(
    argsType = typeToken<StopAgentArgs>(),
    name = "stop_agent",
    description = "Cancels a running subagent and returns its partial result. Use when the subagent is " +
        "taking too long, is stuck, or the task is no longer needed. The subagent cannot be resumed."
) {
    override suspend fun execute(args: StopAgentArgs): String {
        if (args.agentId.isBlank()) return "Error: agentId must not be empty."
        return subagentManager.stop(args.agentId, operator = SubagentManager.OPERATOR_MAIN_AGENT)
    }
}
