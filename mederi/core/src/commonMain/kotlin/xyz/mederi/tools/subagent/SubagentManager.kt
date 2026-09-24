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
 *
 * **主动上报（2026-09-25）**：子代理终态时经 [onTerminal] 回调通知父会话——
 * 父代理不再需要 WAIT 阻塞拉取，TurnExecutor 收到回调后合成内部消息唤起新 turn
 * （批量合并：同一会话多个通知合并成一条内部消息、一次 turn）。
 */
class SubagentManager(
    private val subagentRunner: SubagentRunner,
    private val scope: CoroutineScope,
    private val eventBus: MutableSharedFlow<MederiEvent>? = null,
    /**
     * 子代理终态回调（2026-09-25）。在 spawn 的 finally 里、emitEvent 旁调用。
     * 携带终态信息（agentId/role/parentSessionId/planId/subtaskIndex/status/result/stalled）。
     * 调用方（TurnExecutor）据此把通知入队，在父 turn 空闲时合并成一条内部消息唤起新 turn。
     * 不注入时静默跳过（测试/无唤醒需求场景）。
     */
    private val onTerminal: ((TerminalNotice) -> Unit)? = null,
    /**
     * 卡死检测超时（毫秒，默认 10 分钟）。spawn 时起一个 watchdog 协程，
     * 超时后若子代理仍 RUNNING → 发一条 stalled=true 的 [TerminalNotice] 通知父会话，
     * 由 AI 决定 stop 还是继续等。不强制 cancel——"让AI主动检查一下是不是卡死了"。
     * 全局可配：TurnExecutor 从设置注入，测试可传短超时。
     */
    private val stallTimeoutMs: Long = 10 * 60 * 1000L
) {

    enum class SubagentStatus { RUNNING, COMPLETED, ERROR, STOPPED }

    /**
     * 子代理终态通知（主动上报通道，2026-09-25）。
     *
     * TurnExecutor 收到此通知后：
     * - 入 pendingNotices 队列（按 parentSessionId 分组）
     * - 父 turn 空闲时 maybeFlush 合并成一条内部消息唤起新 turn
     * - 父 turn 活跃时等 turn 收尾再冲刷
     *
     * [stalled] = true 表示 watchdog 超时触发（子代理可能卡死），非正常终态。
     */
    data class TerminalNotice(
        val agentId: String,
        val role: SubagentRole,
        val parentSessionId: String,
        val planId: String?,
        val subtaskIndex: Int?,
        val status: SubagentStatus,
        val result: String?,
        val stalled: Boolean = false
    )

    data class BackgroundAgent(
        val agentId: String,
        val role: SubagentRole,
        /** 父会话 ID：abort 级联收割（stopAllForSession）的索引键。 */
        val parentSessionId: String,
        @Volatile var job: kotlinx.coroutines.Job,
        @Volatile var status: SubagentStatus,
        @Volatile var result: String?,
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
                // 终态事件（与状态机同分支：runner 报错文本也算 ERROR）
                emitEvent(parentSessionId, when (bg.status) {
                    SubagentStatus.COMPLETED -> EventType.SUBAGENT_COMPLETED
                    SubagentStatus.STOPPED -> EventType.SUBAGENT_STOPPED
                    else -> EventType.SUBAGENT_ERROR
                }, mapOf("agentId" to agentId))
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
                    touchedFiles = touched,
                    subtaskIndex = bg.executorSubtaskIndex,
                    atMs = System.currentTimeMillis()
                )
                // 主动上报（2026-09-25）：通知父会话——TurnExecutor 据此合成内部消息唤起新 turn。
                // STOPPED 也通知：否则父代理永远等不到那个子任务的音讯。
                // 去重：watchdog 超时已发过 stalled 通知时跳过（progress=="stalled"），避免重复唤醒。
                // NonCancellable：回调可能处于协程取消路径（STOPPED/abort），裸调用可能被吞。
                if (bg.progress != "stalled") {
                    runCatching {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                            onTerminal?.invoke(TerminalNotice(
                                agentId = agentId,
                                role = role,
                                parentSessionId = parentSessionId,
                                planId = planId,
                                subtaskIndex = executorSubtaskIndex,
                                status = bg.status,
                                result = bg.result,
                                stalled = false
                            ))
                        }
                    }
                }
            }
        }
        bg.job = job
        // watchdog（2026-09-25）：超时提醒——子代理可能卡死。
        // 超时后若仍 RUNNING → 发 stalled 通知（不 cancel，AI 决定 stop 还是等），
        // 并标记 progress="stalled" 让 finally 的 onTerminal 跳过（去重）。
        if (stallTimeoutMs > 0) {
            scope.launch {
                delay(stallTimeoutMs)
                if (bg.status == SubagentStatus.RUNNING) {
                    bg.progress = "stalled"
                    runCatching {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                            onTerminal?.invoke(TerminalNotice(
                                agentId = agentId,
                                role = role,
                                parentSessionId = parentSessionId,
                                planId = planId,
                                subtaskIndex = executorSubtaskIndex,
                                status = SubagentStatus.RUNNING,
                                result = null,
                                stalled = true
                            ))
                        }
                    }
                }
            }
        }
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
                    result = bg.result,
                    modelId = bg.aiModel?.id,
                    modelName = bg.aiModel?.name,
                    reasoningLevel = bg.reasoningLevel?.name
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
        /** spawn 实际使用的模型元数据（动态读 session 后的值）——父代理/用户可查"哪个模型在干活"。 */
        val modelId: String? = null,
        val modelName: String? = null,
        val reasoningLevel: String? = null,
        /** agent 已从内存表移除（丢失/归档）时，携带可恢复的部分认知（touched files 等）。 */
        val recovery: String? = null
    )
}
