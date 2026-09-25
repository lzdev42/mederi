package xyz.mederi.tools.subagent

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.mederi.domain.model.AIModel
import xyz.mederi.domain.model.EventType
import xyz.mederi.domain.model.MederiEvent
import xyz.mederi.domain.model.SubagentRole
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
    @LLMDescription("Task description for the sub-agent; clear, specific, independently executable.")
    val task: String = "",
    @LLMDescription("Extra context from the parent: key constraints and relevant file paths.")
    val briefing: String = "",
    @LLMDescription("Optional. Approved plan ID — with subtaskIndex, the sub-agent executes the exact spec stored by generate_spec. Omit for ad-hoc execution (no plan needed).")
    val planId: String = "",
    @LLMDescription("Optional. Subtask index to execute (0-based). Requires planId.")
    val subtaskIndex: Int = -1
)

/**
 * spawn_agent 工具。
 *
 * **异步派工**：调用后立即返回 `{"agentId":"sub_xxx","status":"RUNNING"}`，不阻塞父 Agent 的 turn。
 * 子 Agent 在后台运行（内存中，不创建持久化 Session）。完成时经 eventBus 终态事件自动唤醒
 * 父 turn（TurnExecutor 合成 `<event_message>` 唤起新 turn），父代理用 `agent_status` 查询、
 * `stop_agent` 主动停止。子 Agent 继承父 Agent 的所有配置。
 *
 * **两条路径**：
 * - 有 planId：planId + subtaskIndex + spec 硬校验（spec 必须已由 generate_spec 写入），走 plan 流程。
 * - 无 planId：ad-hoc 执行，task+briefing 直接派 executor（小改动、多文件编辑等自包含任务）。
 *
 * **模型动态读取**：spawn 用的模型/推理档位以 session 现值为准（构造时捕获的 turn 模型仅作
 * session 缺失的 fallback）——计划批准时用户可能已切换模型（resolvePlanApproval 把批准时刻
 * 的选择写入 session），同 turn 后续 spawn 必须用新模型，否则出现"批准了模型B、干活的还是A"。
 *
 * @param subagentManager 子 Agent 生命周期管理器（异步）。
 * @param directories 项目目录列表，子 Agent 工具的作用域。
 * @param aiModel turn 开始时的模型（fallback，正常路径读 session 现值）。
 * @param reasoningLevel turn 开始时的推理等级（fallback）。
 * @param projectId 当前项目 ID，子 Agent 必须关联到已存在的 Project。
 * @param parentSessionId 父 Session ID，用于追踪与 session 模型动态读取。
 * @param planStore 计划存储，门禁查询活跃计划与读取 spec 用。
 * @param eventBus 事件总线（派工后发 PLAN_PROGRESS 携带子任务投影，驱动 UI todo 面板）。
 * @param sessionStore 会话存储：spawn 时读 session 的最新模型/推理档位。
 */
class SpawnAgentTool(
    private val subagentManager: SubagentManager,
    private val directories: List<String>,
    private val aiModel: AIModel,
    private val reasoningLevel: ReasoningLevel,
    private val projectId: String,
    private val parentSessionId: String,
    private val planStore: PlanStore? = null,
    private val eventBus: MutableSharedFlow<MederiEvent>? = null,
    private val apiKeyId: String? = null,
    private val sessionStore: xyz.mederi.store.SessionStore? = null,
    private val subagentConfigManager: SubagentConfigManager? = null
) : SimpleTool<SpawnAgentArgs>(
    argsType = typeToken<SpawnAgentArgs>(),
    name = "spawn_agent",
    description = "Asynchronously creates a subagent to handle a sub-task. Returns immediately with an " +
        "agentId — the subagent runs in the background. With planId+subtaskIndex: executes the exact " +
        "spec stored by generate_spec (plan workflow). Without planId: ad-hoc execution from task+briefing " +
        "(small fixes, multi-file edits — no plan needed). Multiple spawn calls sent together in one " +
        "message run in parallel. You will be automatically woken up when the subagent finishes — end " +
        "your turn after spawning. Use agent_status to check progress or stop_agent to cancel."
) {

    @Serializable
    data class SpawnResult(
        val agentId: String,
        val status: String,
        /** spawn 实际使用的模型名（动态读 session 后的值）——派工返回即知"哪个模型在干活"。 */
        val modelName: String? = null
    )

    override suspend fun execute(args: SpawnAgentArgs): String {
        if (args.task.isBlank()) {
            return "Error: task must not be empty."
        }

        // 模型/推理档位动态读 session 现值（计划批准时用户可能已切换模型，见类注释）
        // 若配置了子代理独立模型，以子代理独立配置为准，否则继承父会话
        val sessionNow = sessionStore?.get(parentSessionId)
        val parentModel = sessionNow?.aiModel ?: aiModel
        val parentReasoning = sessionNow?.reasoningLevel ?: reasoningLevel
        val (effectiveAiModel, effectiveReasoningLevel) = subagentConfigManager?.resolve(
            role = SubagentRole.EXECUTOR,
            fallbackModel = parentModel,
            fallbackReasoning = parentReasoning
        ) ?: (parentModel to parentReasoning)

        // 无 plan 路径（ad-hoc 执行）：task+briefing 直接派 executor，跳过 plan/spec/IN_PROGRESS。
        // 用于小改动、多文件编辑等自包含任务——不需要 plan 流程，只省上下文。
        if (args.planId.isBlank()) {
            val agentId = subagentManager.spawn(
                task = args.task,
                briefing = args.briefing.takeIf { it.isNotBlank() },
                plan = null,
                role = SubagentRole.EXECUTOR,
                directories = directories,
                aiModel = effectiveAiModel,
                reasoningLevel = effectiveReasoningLevel,
                projectId = projectId,
                parentSessionId = parentSessionId,
                apiKeyId = apiKeyId,
                planId = null,
                executorSubtaskIndex = null,
                planStore = null
            )
            return Json.encodeToString(
                SpawnResult.serializer(),
                SpawnResult(agentId = agentId, status = "RUNNING", modelName = effectiveAiModel.name)
            )
        }

        // 有 plan 路径：planId + subtaskIndex + spec 硬校验（原有逻辑）
        if (args.subtaskIndex < 0) {
            return "Error: subtaskIndex is required when planId is provided."
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

        // 原子标记子任务与计划为 IN_PROGRESS（之前是死状态——从未被设置）。
        // verify_subtask 后续会设 COMPLETED/FAILED；如果 turn 崩溃，IN_PROGRESS
        // 准确反映"这个子任务正在执行中"，下一轮模型看到状态后能判断是否需要重试。
        // 必须用 planStore.updatePlan（原子 RMW）：工具支持并行调度，两个 spawn_agent
        // 同消息并行时，裸 load→update 会互相覆盖对方的 IN_PROGRESS 标记。
        val startedPlan = planStore?.updatePlan(args.planId) { p ->
            p.copy(
                status = if (p.status == xyz.mederi.plan.PlanStatus.APPROVED)
                    xyz.mederi.plan.PlanStatus.IN_PROGRESS else p.status,
                subtasks = p.subtasks.map { s ->
                    if (s.index == args.subtaskIndex) s.copy(status = xyz.mederi.plan.SubtaskStatus.IN_PROGRESS)
                    else s
                }
            )
        } ?: return "Error: Plan not found: ${args.planId}"

        // 派工后发子任务投影：UI todo 面板据此显示"正在做哪个"（真理源仍是 PlanStore）
        eventBus?.emit(MederiEvent(
            type = EventType.PLAN_PROGRESS,
            sessionId = parentSessionId,
            payload = mapOf(
                "planId" to args.planId,
                "action" to "subtask-started",
                "subtaskIndex" to args.subtaskIndex.toString(),
                "todos" to startedPlan.toTodoProjection().encodeTodos()
            ),
            timestamp = Instant.now().toString()
        ))

        val agentId = subagentManager.spawn(
            task = args.task,
            // briefing 注入执行所需的最低意图（executor 自己读盘获取详情）：
            // 1. 父 agent 调研结论（plan.researchNotes）——主 agent 备忘字段，不再注入 executor
            //    briefing。executor 需要调研结论时 read_file .mederi/plans/{planId}/research.md
            // 2. brief（用户批准的意图）——子任务在全局中的定位
            // spec 是主执行清单（作为 plan 参数单独传，不拼进 briefing）
            briefing = listOfNotNull(
                args.briefing.takeIf { it.isNotBlank() },
                st.planDetail.takeIf { it.isNotBlank() }?.let { "Brief: $it" }
            ).takeIf { it.isNotEmpty() }?.joinToString("\n\n"),
            plan = st.spec,
            role = SubagentRole.EXECUTOR,
            directories = directories,
            aiModel = effectiveAiModel,
            reasoningLevel = effectiveReasoningLevel,
            projectId = projectId,
            parentSessionId = parentSessionId,
            apiKeyId = apiKeyId,
            planId = args.planId,
            executorSubtaskIndex = args.subtaskIndex,
            planStore = planStore
        )
        return Json.encodeToString(
            SpawnResult.serializer(),
            SpawnResult(agentId = agentId, status = "RUNNING", modelName = effectiveAiModel.name)
        )
    }
}

/**
 * spawn_researcher 工具参数。
 */
@Serializable
data class SpawnResearcherArgs(
    @LLMDescription("Investigation task: what to resolve and what conclusion to produce.")
    val task: String = "",
    @LLMDescription("Extra context from the parent: key constraints and relevant file paths.")
    val briefing: String = ""
)

/**
 * spawn_researcher 工具：研究型子代理，只读文件、无写权限、无命令执行。
 *
 * **异步派工**：调用后立即返回 `{"agentId":"sub_xxx","status":"RUNNING"}`，不阻塞父 Agent 的 turn。
 * 完成时经 eventBus 终态事件自动唤醒父 turn。
 *
 * 不需要计划，不需要 spec——研究发生在计划之前（调研代码以支撑制定计划）。
 * 子代理的意识：自己是研究助手，不对用户发问，自主调研、汇总结果、返回给父 Agent。
 *
 * 模型动态读 session 现值（与 SpawnAgentTool 同策略），构造时捕获的 turn 模型仅作 fallback。
 */
class SpawnResearcherTool(
    private val subagentManager: SubagentManager,
    private val directories: List<String>,
    private val aiModel: AIModel,
    private val reasoningLevel: ReasoningLevel,
    private val projectId: String,
    private val parentSessionId: String,
    private val apiKeyId: String? = null,
    private val sessionStore: xyz.mederi.store.SessionStore? = null,
    private val subagentConfigManager: SubagentConfigManager? = null,
    private val planStore: PlanStore? = null
) : SimpleTool<SpawnResearcherArgs>(
    argsType = typeToken<SpawnResearcherArgs>(),
    name = "spawn_researcher",
    description = "Asynchronously creates a read-only subagent to investigate the codebase: read files, " +
        "explore the directory structure, and synthesize a structured summary for the parent. " +
        "Returns immediately with an agentId — you will be automatically woken up when it finishes. " +
        "No write access, no command execution. The subagent reports findings and terminates — " +
        "it does not ask the parent clarifying questions."
) {
    override suspend fun execute(args: SpawnResearcherArgs): String {
        if (args.task.isBlank()) {
            return "Error: task must not be empty."
        }
        // 模型/推理档位动态读 session 现值（与 SpawnAgentTool 同策略）
        // 若配置了子代理独立模型，以子代理独立配置为准，否则继承父会话
        val sessionNow = sessionStore?.get(parentSessionId)
        val parentModel = sessionNow?.aiModel ?: aiModel
        val parentReasoning = sessionNow?.reasoningLevel ?: reasoningLevel
        val (effectiveAiModel, effectiveReasoningLevel) = subagentConfigManager?.resolve(
            role = SubagentRole.RESEARCHER,
            fallbackModel = parentModel,
            fallbackReasoning = parentReasoning
        ) ?: (parentModel to parentReasoning)
        // 查活跃 plan：有 plan 时把 planId 传下去，researcher 完成时把报告落盘到 {planId}/research.md；
        // 无 plan（分诊阶段调研，plan 尚未建）时 planId=null，保持原有行为（全文回灌父上下文）。
        val activePlanId = planStore?.loadBySession(parentSessionId)?.id
        val agentId = subagentManager.spawn(
            task = args.task,
            briefing = args.briefing.takeIf { it.isNotBlank() },
            plan = null,
            role = SubagentRole.RESEARCHER,
            directories = directories,
            aiModel = effectiveAiModel,
            reasoningLevel = effectiveReasoningLevel,
            projectId = projectId,
            parentSessionId = parentSessionId,
            apiKeyId = apiKeyId,
            planId = activePlanId,
            planStore = planStore
        )
        return Json.encodeToString(
            SpawnAgentTool.SpawnResult.serializer(),
            SpawnAgentTool.SpawnResult(agentId = agentId, status = "RUNNING", modelName = effectiveAiModel.name)
        )
    }
}
