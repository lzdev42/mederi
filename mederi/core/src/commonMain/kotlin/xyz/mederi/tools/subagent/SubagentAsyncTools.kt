package xyz.mederi.tools.subagent

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable

/**
 * 子 Agent 异步生命周期管理工具集。
 *
 * spawn_agent / spawn_researcher 改为异步后返回 agentId，父 Agent 用这套工具
 * 查询状态、等待结果、主动停止。父 Agent 的 turn 不再被子 Agent 阻塞——
 * 子 Agent 运行期间父 Agent 可以继续对话、派发其他任务。
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
        return subagentManager.stop(args.agentId)
    }
}

/** wait_agent 工具参数。 */
@Serializable
data class WaitAgentArgs(
    @LLMDescription("Sub-agent ID to wait on (from SPAWN).")
    val agentId: String = "",
    @LLMDescription("Timeout in ms. Default 120000 (2 min). On timeout (TIMEOUT) the sub-agent keeps running.")
    val timeoutMs: Long = 120_000
)

/**
 * 带超时地等待子代理完成。阻塞当前 turn 直到子代理结束或超时——
 * 等价于旧版 spawn_agent 的同步行为，但可中断、可指定超时。
 */
class WaitAgentTool(
    private val subagentManager: SubagentManager
) : SimpleTool<WaitAgentArgs>(
    argsType = typeToken<WaitAgentArgs>(),
    name = "wait_agent",
    description = "Blocks (with timeout) until an asynchronously spawned subagent finishes. Returns the " +
        "final status and result. On TIMEOUT the subagent keeps running in the background — check " +
        "agent_status later or stop it with stop_agent. Use when you MUST have the subagent's result " +
        "before proceeding (e.g. plan workflow)."
) {
    override suspend fun execute(args: WaitAgentArgs): String {
        if (args.agentId.isBlank()) return "Error: agentId must not be empty."
        return subagentManager.wait(args.agentId, args.timeoutMs)
    }
}
