package xyz.mederi.tools.subagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.mederi.domain.model.AIModel
import xyz.mederi.domain.model.SubagentRole
import xyz.mederi.domain.model.WorkType
import xyz.mederi.plan.PlanStore
import xyz.mederi.provider.domain.model.ReasoningLevel
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 子 Agent 生命周期管理。
 *
 * 把 [SubagentRunner] 的阻塞执行包进后台协程，返回 agentId 而非结果字符串，
 * 让父 Agent 的 turn 不被子 Agent 阻塞——子 Agent 运行期间父 Agent 可以继续对话、
 * 派发新任务、查询状态、主动停止。
 *
 * 职责：
 * - [spawn]：后台启动子 Agent，立即返回 agentId（不阻塞）。
 * - [status]：查询子 Agent 状态（RUNNING/COMPLETED/ERROR/STOPPED）。
 * - [stop]：取消子 Agent，返回部分结果。
 * - [wait]：带超时地等待子 Agent 完成，返回最终状态与结果。
 *
 * 所有复杂状态都收敛在 [agents] 表里，外部（工具/UI）只通过这里的方法交互。
 */
class SubagentManager(
    private val subagentRunner: SubagentRunner,
    private val scope: CoroutineScope
) {

    enum class SubagentStatus { RUNNING, COMPLETED, ERROR, STOPPED }

    data class BackgroundAgent(
        val agentId: String,
        val role: SubagentRole,
        @Volatile var job: kotlinx.coroutines.Job,
        @Volatile var status: SubagentStatus,
        @Volatile var result: String?,
        @Volatile var progress: String,
        /** 执行器子代理的 plan/subtask 归属（SpawnAgentTool 传入），用于丢失后恢复与 scope 检查。 */
        val executorPlanId: String? = null,
        val executorSubtaskIndex: Int? = null,
        val planStore: PlanStore? = null
    )

    private val agents = ConcurrentHashMap<String, BackgroundAgent>()

    /**
     * 已结束/丢失 agent 的恢复记录（内存驻留，供 agent_status 的 NOT_FOUND 兜底）。
     * 执行器崩溃后父代理凭此拿到「改过哪些文件」的部分认知，而非纯 NOT_FOUND。
     */
    data class AgentRecoveryInfo(
        val status: String,
        val progress: String,
        val result: String?,
        val touchedFiles: List<String>,
        val subtaskIndex: Int?,
        val atMs: Long
    )

    private val retired = ConcurrentHashMap<String, AgentRecoveryInfo>()

    /**
     * 后台启动子 Agent，返回 agentId。
     * 复用 [SubagentRunner.run] 的全部逻辑（内存 store + 独立 TurnExecutor + 阻塞等终态），
     * 只是把它放进后台协程，不阻塞调用方。
     */
    fun spawn(
        task: String,
        briefing: String?,
        plan: String?,
        role: SubagentRole,
        workType: WorkType,
        directories: List<String>,
        aiModel: AIModel,
        reasoningLevel: ReasoningLevel,
        projectId: String,
        parentSessionId: String,
        apiKeyId: String? = null,
        /** 执行器子代理的 plan/subtask 归属（SpawnAgentTool 传入，透传给 SubagentRunnerImpl flush touched files）。 */
        executorPlanId: String? = null,
        executorSubtaskIndex: Int? = null,
        planStore: PlanStore? = null
    ): String {
        val agentId = "sub_${UUID.randomUUID().toString().take(8)}"
        // 先占位注册，保证 spawn 返回后立即可以 query 到（RUNNING）
        val bg = BackgroundAgent(
            agentId = agentId,
            role = role,
            job = kotlinx.coroutines.Job(),
            status = SubagentStatus.RUNNING,
            result = null,
            progress = "starting",
            executorPlanId = executorPlanId,
            executorSubtaskIndex = executorSubtaskIndex,
            planStore = planStore
        )
        agents[agentId] = bg

        val job = scope.launch {
            bg.progress = "running"
            try {
                val result = subagentRunner.run(
                    task = task,
                    briefing = briefing,
                    plan = plan,
                    role = role,
                    workType = workType,
                    directories = directories,
                    aiModel = aiModel,
                    reasoningLevel = reasoningLevel,
                    projectId = projectId,
                    parentSessionId = parentSessionId,
                    apiKeyId = apiKeyId,
                    executorPlanId = executorPlanId,
                    executorSubtaskIndex = executorSubtaskIndex,
                    planStore = planStore
                )
                bg.result = result
                bg.status = if (result.startsWith("[subagent error]")) {
                    SubagentStatus.ERROR
                } else {
                    SubagentStatus.COMPLETED
                }
                bg.progress = "completed"
            } catch (e: CancellationException) {
                bg.result = "[stopped] ${e.message ?: "cancelled"}"
                bg.status = SubagentStatus.STOPPED
                bg.progress = "stopped"
                throw e
            } catch (e: Throwable) {
                bg.result = "[subagent error] ${e.message ?: e.javaClass.simpleName}"
                bg.status = SubagentStatus.ERROR
                bg.progress = "error"
            } finally {
                // 归档恢复记录：agent 丢失/归档后，agent_status 的 NOT_FOUND 也能返回部分认知
                val touched = bg.executorPlanId?.let { pid ->
                    bg.planStore?.load(pid)?.subtasks
                        ?.getOrNull(bg.executorSubtaskIndex ?: -1)
                        ?.executorTouchedFiles
                        .orEmpty()
                }.orEmpty()
                retired[agentId] = AgentRecoveryInfo(
                    status = bg.status.name,
                    progress = bg.progress,
                    result = bg.result,
                    touchedFiles = touched,
                    subtaskIndex = bg.executorSubtaskIndex,
                    atMs = System.currentTimeMillis()
                )
            }
        }
        bg.job = job
        return agentId
    }

    /** 查询子 Agent 状态，返回 JSON 字符串。 */
    fun status(agentId: String): String {
        val bg = agents[agentId]
        if (bg != null) {
            return Json.encodeToString(
                StatusResult(
                    agentId = agentId,
                    status = bg.status.name,
                    progress = bg.progress,
                    result = if (bg.status == SubagentStatus.COMPLETED) bg.result else null
                )
            )
        }
        // agent 已不在内存表（丢失/归档）：从 retired 恢复部分认知，而不是纯 NOT_FOUND
        val rec = retired[agentId]
        if (rec != null) {
            return Json.encodeToString(
                StatusResult(
                    agentId = agentId,
                    status = "NOT_FOUND",
                    progress = rec.progress,
                    result = rec.result,
                    recovery = buildString {
                        append("agent no longer active (last status ${rec.status}). ")
                        if (rec.subtaskIndex != null) append("subtaskIndex=${rec.subtaskIndex}. ")
                        if (rec.touchedFiles.isNotEmpty()) {
                            append("touchedFiles=[${rec.touchedFiles.joinToString(", ")}] ")
                        } else {
                            append("no touched files recorded. ")
                        }
                        append("恢复：父代理可据此判断该子任务已改动的文件（与 targetFiles 比对查越界），再决定重跑或接受部分成果。")
                    }
                )
            )
        }
        return Json.encodeToString(StatusResult(agentId = agentId, status = "NOT_FOUND"))
    }

    /** 取消子 Agent，返回 JSON（含部分结果）。 */
    fun stop(agentId: String): String {
        val bg = agents[agentId]
            ?: return Json.encodeToString(StatusResult(agentId = agentId, status = "NOT_FOUND"))
        bg.job.cancel()
        return Json.encodeToString(
            StatusResult(
                agentId = agentId,
                status = SubagentStatus.STOPPED.name,
                progress = "stopped",
                result = bg.result
            )
        )
    }

    /**
     * 带超时地等待子 Agent 完成。
     * 超时返回 TIMEOUT（子 Agent 继续在后台跑，可再 wait 或 stop）。
     */
    suspend fun wait(agentId: String, timeoutMs: Long): String {
        val bg = agents[agentId]
            ?: return Json.encodeToString(StatusResult(agentId = agentId, status = "NOT_FOUND"))
        return try {
            withTimeout(timeoutMs) {
                while (bg.status == SubagentStatus.RUNNING) {
                    delay(200)
                }
            }
            Json.encodeToString(
                StatusResult(
                    agentId = agentId,
                    status = bg.status.name,
                    progress = bg.progress,
                    result = bg.result
                )
            )
        } catch (e: TimeoutCancellationException) {
            Json.encodeToString(
                StatusResult(
                    agentId = agentId,
                    status = "TIMEOUT",
                    progress = bg.progress
                )
            )
        }
    }

    @Serializable
    data class StatusResult(
        val agentId: String,
        val status: String,
        val progress: String? = null,
        val result: String? = null,
        /** agent 已从内存表移除（丢失/归档）时，携带可恢复的部分认知（touched files 等）。 */
        val recovery: String? = null
    )
}
