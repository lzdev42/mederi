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
 * **无 WAIT（异步唤醒设计）**：子代理完成时经 eventBus 终态事件
 * （SUBAGENT_COMPLETED/ERROR/STOPPED）唤醒父 turn——TurnExecutor 收集事件后
 * 合成 `<event_message>` 唤起新 turn。父代理 SPAWN 后正常结束 turn，期间可继续对话。
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
    @LLMDescription("SPAWN: optional approved plan ID. With subtaskIndex, the sub-agent executes the exact spec stored by generate_spec. Omit for ad-hoc execution (no plan needed).")
    val planId: String = "",
    @LLMDescription("SPAWN: optional subtask index to execute (0-based). Requires planId.")
    val subtaskIndex: Int = -1,
    @LLMDescription("STATUS / STOP: sub-agent ID from a previous SPAWN.")
    val agentId: String = ""
)

/**
 * 子代理单一入口工具（2026-09 合并精简）。
 *
 * 原 6 个工具（spawn_agent / spawn_researcher / agent_status / stop_agent / wait_agent）
 * 封装为一个 `subagent`，用 [SubagentAction] 分流：
 * - [SubagentAction.SPAWN]：委托 [SpawnAgentTool]（保留全部门禁：spec 必填、原子 IN_PROGRESS、
 *   PLAN_PROGRESS、session 动态模型读）。
 * - [SubagentAction.SPAWN_RESEARCHER]：委托 [SpawnResearcherTool]。
 * - [SubagentAction.STATUS] / [SubagentAction.STOP]：委托 [SubagentManager.status] / stop。
 *
 * **无 WAIT**：子代理完成时经 eventBus 终态事件唤醒父 turn（TurnExecutor 合成
 * `<event_message>` 唤起新 turn）。SPAWN 后父 turn 正常结束，用户可继续对话；
 * 子代理完成时自动唤醒，结果摘要随 `<event_message>` 回到父上下文。
 *
 * 仅主代理注册（canSpawn）。返回值：SPAWN/SPAWN_RESEARCHER → {agentId,status,modelName}；
 * STATUS/STOP → {agentId,status,progress,result,modelId,modelName,reasoningLevel}。
 */
class SubagentTool(
    private val spawnExecutor: SpawnAgentTool,
    private val spawnResearcher: SpawnResearcherTool,
    private val manager: SubagentManager,
    /** 父会话 ID：STATUS 拦截轮询与 SPAWN 返回值 runningCount 都按本会话统计。 */
    private val parentSessionId: String
) : SimpleTool<SubagentArgs>(
    argsType = typeToken<SubagentArgs>(),
    name = "subagent",
    description = "Single tool to delegate and manage asynchronous sub-agents: SPAWN executes a task " +
        "(planned spec or ad-hoc), SPAWN_RESEARCHER runs a read-only investigation, " +
        "and STATUS/STOP query or cancel a spawned sub-agent. SPAWN / SPAWN_RESEARCHER are non-blocking " +
        "and return an agentId immediately while the sub-agent runs in the background. After calling " +
        "SPAWN or SPAWN_RESEARCHER, your NEXT response MUST be a text-only final message with ZERO " +
        "tool calls — state you have dispatched the sub-agent and are waiting. You will be " +
        "automatically woken when all dispatched sub-agents complete. Do NOT call subagent(STATUS) " +
        "to poll. Use STOP to cancel a sub-agent if it seems stuck."
) {
    override suspend fun execute(args: SubagentArgs): String = when (args.action) {
        SubagentAction.SPAWN -> appendSpawnGuidance(
            spawnExecutor.execute(
                SpawnAgentArgs(task = args.task, briefing = args.briefing, planId = args.planId, subtaskIndex = args.subtaskIndex)
            )
        )
        SubagentAction.SPAWN_RESEARCHER -> appendSpawnGuidance(
            spawnResearcher.execute(
                SpawnResearcherArgs(task = args.task, briefing = args.briefing)
            )
        )
        SubagentAction.STATUS ->
            if (args.agentId.isBlank()) "Error: STATUS requires agentId (from a previous SPAWN)."
            // 轮询拦截：本会话仍有 RUNNING 子代理时，不返回实际状态——引导父代理结束 turn 等自动唤醒
            else if (manager.runningCountForSession(parentSessionId) > 0)
                "Subagents are still running. Do NOT poll — end your turn now. You will be automatically woken when all results are in."
            else manager.status(args.agentId)
        SubagentAction.STOP ->
            if (args.agentId.isBlank()) "Error: STOP requires agentId (from a previous SPAWN)."
            else manager.stop(args.agentId, operator = SubagentManager.OPERATOR_MAIN_AGENT)
    }

    /**
     * SPAWN / SPAWN_RESEARCHER 返回值增强：JSON 之后追加人类可读指令文案。
     *
     * Error 文本（含并发上限拒绝）原样透传——没有派工成功就不能说 "dispatched"。
     * 成功路径才追加 runningCount（spawn 已注册，计数包含刚派出的这个）与 end-turn 硬指令。
     */
    private fun appendSpawnGuidance(result: String): String {
        if (result.startsWith("Error:")) return result
        val running = manager.runningCountForSession(parentSessionId)
        return "$result\nSubagent dispatched. $running subagent(s) running. " +
            "End your turn — you will be automatically woken when all complete. " +
            "Do NOT call subagent(STATUS) to poll."
    }
}
