package xyz.mederi.tools.subagent

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable
import xyz.mederi.tools.subagent.SpawnAgentArgs
import xyz.mederi.tools.subagent.SpawnResearcherArgs

/**
 * subagent 工具的分派键。
 */
@Serializable
enum class SubagentAction {
    /** 派执行器子代理执行已生成 spec 的计划子任务（需要 planId + subtaskIndex）。 */
    SPAWN,
    /** 派只读调研子代理（需要 task）。 */
    SPAWN_RESEARCHER,
    /** 查询子代理状态（需要 agentId）。 */
    STATUS,
    /** 停止子代理（需要 agentId）。 */
    STOP,
    /** 带超时等待子代理结果（需要 agentId；可配 timeoutMs）。 */
    WAIT
}

/**
 * subagent 工具参数。不同 [SubagentAction] 使用其中不同的字段组合（见各字段说明）。
 */
@Serializable
data class SubagentArgs(
    @LLMDescription("Operation to perform: SPAWN (delegate a planned subtask), " +
        "SPAWN_RESEARCHER (delegate a read-only investigation), STATUS/STOP/WAIT (manage a " +
        "previously spawned sub-agent).")
    val action: SubagentAction,
    @LLMDescription("SPAWN / SPAWN_RESEARCHER: task description for the sub-agent.")
    val task: String = "",
    @LLMDescription("SPAWN / SPAWN_RESEARCHER: optional extra context (known constraints, file paths).")
    val briefing: String = "",
    @LLMDescription("SPAWN: approved plan ID of the subtask to execute.")
    val planId: String = "",
    @LLMDescription("SPAWN: subtask index to execute (0-based).")
    val subtaskIndex: Int = -1,
    @LLMDescription("STATUS / STOP / WAIT: sub-agent ID from a previous SPAWN.")
    val agentId: String = "",
    @LLMDescription("WAIT: timeout in milliseconds (default 120000). On timeout the sub-agent keeps running.")
    val timeoutMs: Long = 120_000
)

/**
 * 子代理单一入口工具（2026-09 合并精简）。
 *
 * 原 6 个工具（spawn_agent / spawn_researcher / agent_status / stop_agent / wait_agent）
 * 封装为一个 `subagent`，用 [SubagentAction] 分流：
 * - [SubagentAction.SPAWN]：委托 [SpawnAgentTool]（保留全部门禁：spec 必填、原子 IN_PROGRESS、
 *   PLAN_PROGRESS、session 动态模型读）。
 * - [SubagentAction.SPAWN_RESEARCHER]：委托 [SpawnResearcherTool]。
 * - [SubagentAction.STATUS] / [SubagentAction.STOP] / [SubagentAction.WAIT]：委托
 *   [SubagentManager.status] / stop / wait。
 *
 * 仅主代理注册（canSpawn）。返回值：SPAWN/SPAWN_RESEARCHER → {agentId,status,modelName}；
 * STATUS/STOP/WAIT → {agentId,status,progress,result,modelId,modelName,reasoningLevel}。
 */
class SubagentTool(
    private val spawnExecutor: SpawnAgentTool,
    private val spawnResearcher: SpawnResearcherTool,
    private val manager: SubagentManager
) : SimpleTool<SubagentArgs>(
    argsType = typeToken<SubagentArgs>(),
    name = "subagent",
    description = "Single tool to delegate and manage sub-agents: SPAWN executes a planned subtask " +
        "(the exact spec stored by generate_spec), SPAWN_RESEARCHER runs a read-only investigation, " +
        "and STATUS/STOP/WAIT query, cancel, or block on a spawned sub-agent. SPAWN / SPAWN_RESEARCHER " +
        "return an agentId immediately (the sub-agent runs in the background); WAIT blocks and " +
        "returns the final report."
) {
    override suspend fun execute(args: SubagentArgs): String = when (args.action) {
        SubagentAction.SPAWN -> spawnExecutor.execute(
            SpawnAgentArgs(task = args.task, briefing = args.briefing, planId = args.planId, subtaskIndex = args.subtaskIndex)
        )
        SubagentAction.SPAWN_RESEARCHER -> spawnResearcher.execute(
            SpawnResearcherArgs(task = args.task, briefing = args.briefing)
        )
        SubagentAction.STATUS ->
            if (args.agentId.isBlank()) "Error: STATUS requires agentId (from a previous SPAWN)."
            else manager.status(args.agentId)
        SubagentAction.STOP ->
            if (args.agentId.isBlank()) "Error: STOP requires agentId (from a previous SPAWN)."
            else manager.stop(args.agentId)
        SubagentAction.WAIT ->
            if (args.agentId.isBlank()) "Error: WAIT requires agentId (from a previous SPAWN)."
            else manager.wait(args.agentId, args.timeoutMs)
    }
}