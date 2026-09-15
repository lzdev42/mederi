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
        @Volatile var progress: String
    )

    private val agents = ConcurrentHashMap<String, BackgroundAgent>()

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
        parentSessionId: String
    ): String {
        val agentId = "sub_${UUID.randomUUID().toString().take(8)}"
        // 先占位注册，保证 spawn 返回后立即可以 query 到（RUNNING）
        val bg = BackgroundAgent(
            agentId = agentId,
            role = role,
            job = kotlinx.coroutines.Job(),
            status = SubagentStatus.RUNNING,
            result = null,
            progress = "starting"
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
                    parentSessionId = parentSessionId
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
            }
        }
        bg.job = job
        return agentId
    }

    /** 查询子 Agent 状态，返回 JSON 字符串。 */
    fun status(agentId: String): String {
        val bg = agents[agentId]
            ?: return Json.encodeToString(StatusResult(agentId = agentId, status = "NOT_FOUND"))
        return Json.encodeToString(
            StatusResult(
                agentId = agentId,
                status = bg.status.name,
                progress = bg.progress,
                result = if (bg.status == SubagentStatus.COMPLETED) bg.result else null
            )
        )
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
        val result: String? = null
    )
}
