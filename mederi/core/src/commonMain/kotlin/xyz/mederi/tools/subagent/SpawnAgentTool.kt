package xyz.mederi.tools.subagent

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.Serializable
import xyz.mederi.domain.model.AIModel
import xyz.mederi.domain.model.EventType
import xyz.mederi.domain.model.MederiEvent
import xyz.mederi.domain.model.SubagentRole
import xyz.mederi.domain.model.WorkType
import xyz.mederi.domain.model.encodeTodos
import xyz.mederi.plan.PlanStore
import xyz.mederi.plan.toTodoProjection
import xyz.mederi.provider.domain.model.ReasoningLevel
import java.time.Instant

/**
 * spawn_agent 工具参数。
 *
 * @param task 交给子 Agent 完成的任务描述。
 * @param briefing 可选的补充信息（父 Agent 的关键结论、限制条件等）。
 * @param planId 已批准计划的 ID，与 [subtaskIndex] 一起必填。
 * @param subtaskIndex 要执行的子任务索引（0 基）。
 */
@Serializable
data class SpawnAgentArgs(
    @LLMDescription("交给子 Agent 完成的任务描述，应清晰、具体、可独立执行。")
    val task: String = "",
    @LLMDescription("补充信息：父 Agent 已掌握的关键结论、限制条件、相关文件路径等。")
    val briefing: String = "",
    @LLMDescription("已批准计划的 ID。与 subtaskIndex 一起指定要执行的子任务——子代理执行的是 generate_spec 存储的确切 spec，零漂移。")
    val planId: String = "",
    @LLMDescription("要执行的子任务索引（0 基）。需要 planId。")
    val subtaskIndex: Int = -1
)

/**
 * spawn_agent 工具。
 *
 * 在父 Agent 的 turn 中同步调用，阻塞等待子 Agent 单 turn 执行完毕后返回结果。
 * 子 Agent 运行在内存中，不创建持久化的 Session。
 * 子 Agent 继承父 Agent 的所有配置（模型、推理等级等）。
 *
 * 门禁（代码强制）：spec 必须已由 generate_spec 写入 Subtask.spec（非空才放行），
 * 杜绝"未生成 spec 直接派活"绕过；brief（planDetail）随 briefing 一并带给子代理作意图上下文。
 *
 * @param subagentRunner 子 Agent 执行器。
 * @param directories 项目目录列表，子 Agent 工具的作用域。
 * @param aiModel 使用的模型，子 Agent 继承父 Agent 的模型配置。
 * @param reasoningLevel 推理等级，子 Agent 继承父 Agent 的推理等级配置。
 * @param projectId 当前项目 ID，子 Agent 必须关联到已存在的 Project。
 * @param parentSessionId 父 Session ID，用于追踪。
 * @param planStore 计划存储，门禁查询活跃计划与读取 spec 用。
 * @param eventBus 事件总线（派工后发 PLAN_PROGRESS 携带子任务投影，驱动 UI todo 面板）。
 */
class SpawnAgentTool(
    private val subagentRunner: SubagentRunner,
    private val directories: List<String>,
    private val aiModel: AIModel,
    private val reasoningLevel: ReasoningLevel,
    private val projectId: String,
    private val parentSessionId: String,
    private val planStore: PlanStore? = null,
    private val eventBus: MutableSharedFlow<MederiEvent>? = null
) : SimpleTool<SpawnAgentArgs>(
    argsType = typeToken<SpawnAgentArgs>(),
    name = "spawn_agent",
    description = "Creates a subagent to handle a sub-task. The subagent inherits all configuration from the parent. " +
        "Requires planId + subtaskIndex; the subagent executes the exact spec stored by generate_spec. " +
        "Call generate_spec(planId, subtaskIndex) first, then spawn with both."
) {

    override suspend fun execute(args: SpawnAgentArgs): String {
        if (args.task.isBlank()) {
            return "Error: task must not be empty."
        }
        if (args.planId.isBlank() || args.subtaskIndex < 0) {
            return "Error: planId and subtaskIndex are required. " +
                "First call generate_spec(planId, subtaskIndex) to create the spec, then spawn with both."
        }

        // 从 PlanStore 取 generate_spec 存储的确切 spec（执行零漂移），并做存在性硬校验
        val plan = planStore?.load(args.planId)
            ?: return "Error: Plan not found: ${args.planId}"
        val st = plan.subtasks.getOrNull(args.subtaskIndex)
            ?: return "Error: Subtask index ${args.subtaskIndex} out of range (0..${plan.subtasks.size - 1})."
        if (st.status == xyz.mederi.plan.SubtaskStatus.COMPLETED)
            return "Error: Subtask ${args.subtaskIndex} is already COMPLETED."
        if (st.spec.isNullOrBlank())
            return "Error: Subtask ${args.subtaskIndex} has no generated spec. " +
                "Call generate_spec(planId=${args.planId}, subtaskIndex=${args.subtaskIndex}) first."

        // 标记子任务与计划为 IN_PROGRESS（之前是死状态——从未被设置）。
        // verify_subtask 后续会设 COMPLETED/FAILED；如果 turn 崩溃，IN_PROGRESS
        // 准确反映"这个子任务正在执行中"，下一轮模型看到状态后能判断是否需要重试。
        val updatedPlan = plan.copy(
            status = if (plan.status == xyz.mederi.plan.PlanStatus.APPROVED)
                xyz.mederi.plan.PlanStatus.IN_PROGRESS else plan.status,
            subtasks = plan.subtasks.mapIndexed { i, s ->
                if (i == args.subtaskIndex) s.copy(status = xyz.mederi.plan.SubtaskStatus.IN_PROGRESS)
                else s
            }
        )
        planStore?.update(updatedPlan)

        // 派工后发子任务投影：UI todo 面板据此显示"正在做哪个"（真理源仍是 PlanStore）
        eventBus?.emit(MederiEvent(
            type = EventType.PLAN_PROGRESS,
            sessionId = parentSessionId,
            payload = mapOf(
                "planId" to args.planId,
                "action" to "subtask-started",
                "subtaskIndex" to args.subtaskIndex.toString(),
                "todos" to updatedPlan.toTodoProjection().encodeTodos()
            ),
            timestamp = Instant.now().toString()
        ))

        return try {
            val result = subagentRunner.run(
                task = args.task,
                // brief（用户批准的意图）拼进 briefing 给子代理作上下文；spec 是主执行清单
                briefing = listOfNotNull(
                    args.briefing.takeIf { it.isNotBlank() },
                    st.planDetail.takeIf { it.isNotBlank() }?.let { "Brief: $it" }
                ).takeIf { it.isNotEmpty() }?.joinToString("\n\n"),
                plan = st.spec,
                role = SubagentRole.EXECUTOR,
                workType = plan.workType,
                directories = directories,
                aiModel = aiModel,
                reasoningLevel = reasoningLevel,
                projectId = projectId,
                parentSessionId = parentSessionId
            )
            result
        } catch (e: Throwable) {
            "Error: subagent failed - ${e.message ?: e.javaClass.simpleName}"
        }
    }
}

/**
 * spawn_researcher 工具参数。
 */
@Serializable
data class SpawnResearcherArgs(
    @LLMDescription("调研任务描述：要查清什么问题，要产出什么结论。")
    val task: String = "",
    @LLMDescription("补充信息：父 Agent 已掌握的关键结论、限制条件、相关文件路径等。")
    val briefing: String = ""
)

/**
 * spawn_researcher 工具：研究型子代理，只读文件、无写权限、无命令执行。
 *
 * 不需要计划，不需要 spec——研究发生在计划之前（调研代码以支撑制定计划）。
 * 子代理的意识：自己是研究助手，不对用户发问，自主调研、汇总结果、返回给父 Agent。
 *
 * 门禁：无计划门禁（研究是只读的，不涉及修改）。spawn_agent 的 full-tool 门禁
 * 由 planId+subtaskIndex 硬绑定控制，与此无关。
 */
class SpawnResearcherTool(
    private val subagentRunner: SubagentRunner,
    private val directories: List<String>,
    private val aiModel: AIModel,
    private val reasoningLevel: ReasoningLevel,
    private val projectId: String,
    private val parentSessionId: String
) : SimpleTool<SpawnResearcherArgs>(
    argsType = typeToken<SpawnResearcherArgs>(),
    name = "spawn_researcher",
    description = "Creates a read-only subagent to investigate the codebase: read files, explore " +
        "the directory structure, and synthesize a structured summary for the parent. " +
        "No write access, no command execution. The subagent reports findings and terminates — " +
        "it does not ask the parent clarifying questions."
) {
    override suspend fun execute(args: SpawnResearcherArgs): String {
        if (args.task.isBlank()) {
            return "Error: task must not be empty."
        }
        return try {
            val result = subagentRunner.run(
                task = args.task,
                briefing = args.briefing.takeIf { it.isNotBlank() },
                plan = null,
                role = SubagentRole.RESEARCHER,
                workType = WorkType.CODE,
                directories = directories,
                aiModel = aiModel,
                reasoningLevel = reasoningLevel,
                projectId = projectId,
                parentSessionId = parentSessionId
            )
            result
        } catch (e: Throwable) {
            "Error: subagent failed - ${e.message ?: e.javaClass.simpleName}"
        }
    }
}
