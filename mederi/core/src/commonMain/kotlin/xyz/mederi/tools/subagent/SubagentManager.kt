package xyz.mederi.tools.subagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.mederi.domain.model.AIModel
import xyz.mederi.domain.model.EventType
import xyz.mederi.domain.model.MederiEvent
import xyz.mederi.domain.model.SubagentRole
import xyz.mederi.plan.PlanStore
import xyz.mederi.provider.domain.model.ReasoningLevel
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableSharedFlow

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
 * - [stopAllForSession]：abort 级联收割（TurnExecutor.abort/abortAndJoin 调用）。
 *
 * 生命周期事件（可选注入 [eventBus] 时发射，与 BROWSER_TASK_* 同模式）：
 * SUBAGENT_STARTED（spawn 注册后，带 task/模型/推理档位全量元数据——UI 据此显示
 * "主代理派了什么命令、用什么模型在跑"）→ COMPLETED / ERROR / STOPPED（终态）。
 * sessionId = 父会话 ID；不注入 eventBus 时静默跳过（测试/无事件场景）。
 *
 * 所有复杂状态都收敛在 [agents] 表里，外部（工具/UI）只通过这里的方法交互。
 */
class SubagentManager(
    private val subagentRunner: SubagentRunner,
    private val scope: CoroutineScope,
    private val eventBus: MutableSharedFlow<MederiEvent>? = null
) {

    enum class SubagentStatus { RUNNING, COMPLETED, ERROR, STOPPED }

    data class BackgroundAgent(
        val agentId: String,
        val role: SubagentRole,
        /** 父会话 ID：abort 级联收割（stopAllForSession）的索引键。 */
        val parentSessionId: String,
        @Volatile var job: kotlinx.coroutines.Job,
        @Volatile var status: SubagentStatus,
        @Volatile var result: String?,
        @Volatile var reportPath: String? = null,
        @Volatile var progress: String,
        /** spawn 实际使用的模型/推理档位（动态读 session 后的值）——事件与 status() 的元数据来源。 */
        val aiModel: AIModel? = null,
        val reasoningLevel: ReasoningLevel? = null,
        /** 主代理派发的命令（task）与补充说明（briefing）——SUBAGENT_STARTED payload。 */
        val task: String = "",
        val briefing: String? = null,
        /** 子代理的 plan 归属（EXECUTOR 与有活跃 plan 的 RESEARCHER 都传入）。
         *  用于丢失后恢复：executor 据此查 touchedFiles，researcher 据此恢复报告路径认知。 */
        val planId: String? = null,
        val executorSubtaskIndex: Int? = null,
        val planStore: PlanStore? = null
    )

    @Serializable
    data class SubagentReportData(
        val agentId: String,
        val role: SubagentRole,
        val status: SubagentStatus,
        val reportPath: String?,
        val content: String?
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
        val role: String? = null,
        val reportPath: String? = null,
        val touchedFiles: List<String>,
        val subtaskIndex: Int?,
        val atMs: Long
    )

    private val retired = ConcurrentHashMap<String, AgentRecoveryInfo>()

    /**
     * 生命周期事件发射（eventBus 未注入时静默跳过）。
     * NonCancellable：终态发射常处于协程取消路径（STOPPED / abort 级联收割），
     * 裸 emit 会在挂起点抛 CancellationException 把事件吞掉。
     */
    private suspend fun emitEvent(sessionId: String, type: EventType, payload: Map<String, String>) {
        val bus = eventBus ?: return
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            bus.emit(
                MederiEvent(
                    type = type,
                    sessionId = sessionId,
                    payload = payload,
                    timestamp = java.time.Instant.now().toString()
                )
            )
        }
    }

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
        directories: List<String>,
        aiModel: AIModel,
        reasoningLevel: ReasoningLevel,
        projectId: String,
        parentSessionId: String,
        apiKeyId: String? = null,
        /** 子代理的 plan 归属（EXECUTOR 由 SpawnAgentTool 传、RESEARCHER 由 SpawnResearcherTool 在有活跃 plan 时传）。 */
        planId: String? = null,
        executorSubtaskIndex: Int? = null,
        planStore: PlanStore? = null
    ): String {
        val agentId = "sub_${UUID.randomUUID().toString().take(8)}"
        // 先占位注册，保证 spawn 返回后立即可以 query 到（RUNNING）
        val bg = BackgroundAgent(
            agentId = agentId,
            role = role,
            parentSessionId = parentSessionId,
            job = kotlinx.coroutines.Job(),
            status = SubagentStatus.RUNNING,
            result = null,
            progress = "starting",
            aiModel = aiModel,
            reasoningLevel = reasoningLevel,
            task = task,
            briefing = briefing,
            planId = planId,
            executorSubtaskIndex = executorSubtaskIndex,
            planStore = planStore
        )
        agents[agentId] = bg

        val job = scope.launch {
            bg.progress = "running"
            emitEvent(parentSessionId, EventType.SUBAGENT_STARTED, mapOf(
                "agentId" to agentId,
                "role" to role.name,
                "modelId" to aiModel.id,
                "modelName" to aiModel.name,
                "reasoningLevel" to reasoningLevel.name,
                "task" to task
            ) + (briefing?.takeIf { it.isNotBlank() }?.let { mapOf("briefing" to it) } ?: emptyMap()))
            try {
                val result = subagentRunner.run(
                    task = task,
                    briefing = briefing,
                    plan = plan,
                    role = role,
                    directories = directories,
                    aiModel = aiModel,
                    reasoningLevel = reasoningLevel,
                    projectId = projectId,
                    parentSessionId = parentSessionId,
                    apiKeyId = apiKeyId,
                    planId = planId,
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
                val reportPath = bg.result?.let { extractReportPath(it) }
                bg.reportPath = reportPath

                val payload = buildMap {
                    put("agentId", agentId)
                    put("role", role.name)
                    put("status", bg.status.name)
                    reportPath?.let { put("reportPath", it) }
                    bg.result?.let { put("result", it) }
                    bg.planId?.let { put("planId", it) }
                    bg.executorSubtaskIndex?.let { put("subtaskIndex", it.toString()) }
                }

                // 终态事件（与状态机同分支：runner 报错文本也算 ERROR）
                emitEvent(parentSessionId, when (bg.status) {
                    SubagentStatus.COMPLETED -> EventType.SUBAGENT_COMPLETED
                    SubagentStatus.STOPPED -> EventType.SUBAGENT_STOPPED
                    else -> EventType.SUBAGENT_ERROR
                }, payload)

                // 归档恢复记录：agent 丢失/归档后，agent_status 的 NOT_FOUND 也能返回部分认知
                val touched = bg.planId?.let { pid ->
                    bg.planStore?.load(pid)?.subtasks
                        ?.getOrNull(bg.executorSubtaskIndex ?: -1)
                        ?.executorTouchedFiles
                        .orEmpty()
                }.orEmpty()
                retired[agentId] = AgentRecoveryInfo(
                    status = bg.status.name,
                    progress = bg.progress,
                    result = bg.result,
                    role = role.name,
                    reportPath = reportPath,
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
                    result = if (bg.status == SubagentStatus.COMPLETED) bg.result else null,
                    reportPath = bg.reportPath,
                    modelId = bg.aiModel?.id,
                    modelName = bg.aiModel?.name,
                    reasoningLevel = bg.reasoningLevel?.name
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
                    reportPath = rec.reportPath,
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
     * 级联停止一个父会话的所有 RUNNING 子代理（abort / abortAndJoin 调用）。
     *
     * 子代理跑在本 Manager 的全局 scope 上，不随父 turn job 取消而亡——
     * 用户点"停止"若只 cancel 父 turn，旧模型的后台子代理会成为孤儿：
     * 继续写文件、与"继续"后重 spawn 的新代理并发写同一批 targetFiles。
     * 调用方应先 cancel 父 turn job（阻断新 spawn），再调本方法收割存量。
     * 已终态（COMPLETED/ERROR/STOPPED）的 agent 不受影响；方法幂等。
     *
     * @return 实际取消的 RUNNING 子代理数量
     */
    fun stopAllForSession(parentSessionId: String): Int {
        var stopped = 0
        for (bg in agents.values) {
            if (bg.parentSessionId == parentSessionId && bg.status == SubagentStatus.RUNNING) {
                bg.job.cancel()
                stopped++
            }
        }
        return stopped
    }

    /**
     * 获取指定子 Agent 的任务汇报详情（供 Server / UI 读取）。
     * 若已落盘则读取磁盘文件内容；若未落盘则返回内存中的完整 result。
     */
    fun getReport(agentId: String): SubagentReportData? {
        val bg = agents[agentId]
        if (bg != null) {
            val path = bg.reportPath
            val content = path?.let { p ->
                runCatching { java.io.File(p).takeIf { it.exists() }?.readText() }.getOrNull()
            } ?: bg.result
            return SubagentReportData(
                agentId = agentId,
                role = bg.role,
                status = bg.status,
                reportPath = path,
                content = content
            )
        }
        val rec = retired[agentId] ?: return null
        val path = rec.reportPath
        val content = path?.let { p ->
            runCatching { java.io.File(p).takeIf { it.exists() }?.readText() }.getOrNull()
        } ?: rec.result
        return SubagentReportData(
            agentId = agentId,
            role = rec.role?.let { runCatching { SubagentRole.valueOf(it) }.getOrNull() } ?: SubagentRole.EXECUTOR,
            status = runCatching { SubagentStatus.valueOf(rec.status) }.getOrDefault(SubagentStatus.COMPLETED),
            reportPath = path,
            content = content
        )
    }

    @Serializable
    data class StatusResult(
        val agentId: String,
        val status: String,
        val progress: String? = null,
        val result: String? = null,
        val reportPath: String? = null,
        /** spawn 实际使用的模型元数据（动态读 session 后的值）——父代理/用户可查"哪个模型在干活"。 */
        val modelId: String? = null,
        val modelName: String? = null,
        val reasoningLevel: String? = null,
        /** agent 已从内存表移除（丢失/归档）时，携带可恢复的部分认知（touched files 等）。 */
        val recovery: String? = null
    )

    companion object {
        /** 从报告文本中提取报告落盘路径。格式形如：`[executor report saved to /path/to/file]` */
        fun extractReportPath(text: String): String? {
            val regex = Regex("""\[(?:executor|research)\s+report\s+saved\s+to\s+([^]]+)]""")
            return regex.find(text)?.groupValues?.get(1)?.trim()
        }
    }
}
