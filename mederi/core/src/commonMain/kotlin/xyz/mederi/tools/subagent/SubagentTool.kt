package xyz.mederi.tools.subagent

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable
import xyz.mederi.tools.subagent.SpawnAgentArgs
import xyz.mederi.tools.subagent.SpawnResearcherArgs

/**
 * subagent 工具的分派键。
 *
 * **WAIT 已删除（2026-09-25）**：子代理完成时自动经 [SubagentManager.onTerminal] 回调
 * 唤起父 turn——父代理不再需要阻塞等待。历史消息中的旧 WAIT 调用不会反序列化失败
 * （[SubagentArgs] 的字段都是带默认值的，未知 action 值会被 [SubagentArgs.action]
 * 反序列化丢弃/默认），模型收到错误后自纠。卡死检测由 watchdog 超时通知替代。
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
    STOP
}

/**
 * subagent 工具参数。不同 [SubagentAction] 使用其中不同的字段组合（见各字段说明）。
 */
@Serializable
data class SubagentArgs(
    @LLMDescription("Operation to perform: SPAWN (delegate a planned subtask), " +
        "SPAWN_RESEARCHER (delegate a read-only investigation), STATUS/STOP (manage a " +
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
    @LLMDescription("STATUS / STOP: sub-agent ID from a previous SPAWN.")
    val agentId: String = ""
)

/**
 * 子代理单一入口工具（2026-09 合并精简，2026-09-25 删 WAIT）。
 *
 * 封装为一个 `subagent`，用 [SubagentAction] 分流：
 * - [SubagentAction.SPAWN]：委托 [SpawnAgentTool]（保留全部门禁：spec 必填、原子 IN_PROGRESS、
 *   PLAN_PROGRESS、session 动态模型读）。
 * - [SubagentAction.SPAWN_RESEARCHER]：委托 [SpawnResearcherTool]。
 * - [SubagentAction.STATUS] / [SubagentAction.STOP]：委托 [SubagentManager.status] / stop。
 *
 * **不再有 WAIT**：子代理完成时自动唤醒父 turn（[SubagentManager.onTerminal] 回调 →
 * TurnExecutor 合成内部消息）。spawn 后父 turn 正常结束，用户可继续对话。
 * 卡死检测由 watchdog 超时通知替代（默认 10 分钟，全局可配）。
 *
 * 仅主代理注册（canSpawn）。返回值：SPAWN/SPAWN_RESEARCHER → {agentId,status,modelName}；
 * STATUS/STOP → {agentId,status,progress,result,...}。
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
        "and STATUS/STOP query or cancel a spawned sub-agent. SPAWN / SPAWN_RESEARCHER return an " +
        "agentId immediately (the sub-agent runs in the background). You will be AUTOMATICALLY woken " +
        "up when a sub-agent finishes — do NOT poll or wait; just SPAWN, end your turn, and you will " +
        "be resumed with the result. If a sub-agent seems stuck, a stall notice will wake you after " +
        "the timeout (default 10 min) — use STATUS to check or STOP to cancel."
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
    }
}
