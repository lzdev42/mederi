package xyz.mederi.tools.background

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.mederi.domain.model.EventType
import xyz.mederi.domain.model.MederiEvent
import xyz.mederi.tools.sandbox.ProcessRegistry
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * 后台任务生命周期管理（Command / Timer 两种后台任务）。
 *
 * 照搬 SubagentManager 的模式：把「命令进程跟踪」「定时器」包进后台协程，
 * 返回 taskId 而非阻塞结果——调用方（工具层）立即拿到 taskId，任务在后台跑，
 * 完成/失败/停止经 [eventBus] 发射终态事件。
 *
 * 职责：
 * - [trackCommand]：接管一个已启动的 [Process]，后台读取输出 + 超时回收，
 *   终态事件携带输出摘要（截断 ~4000 字符，防事件 payload 膨胀）。
 * - [startTimer]：后台定时器，到时置 COMPLETED。
 * - [stop]：主动停止任务（取消协程 + COMMAND 型整组杀进程）。
 * - [stopAllForSession]：abort / rollback 级联收割（按会话，与 SubagentManager.stopAllForSession 同用途）。
 * - [runningCountForSession] / [listForSession]：状态查询。
 *
 * 生命周期事件（可选注入 [eventBus] 时发射，与 SUBAGENT_* / BROWSER_TASK_* 同模式）：
 * BACKGROUND_TASK_STARTED（注册后立即）→ COMPLETED / ERROR / STOPPED（终态）。
 * sessionId = 发起会话 ID；不注入 eventBus 时静默跳过（测试/无事件场景）。
 *
 * 所有复杂状态都收敛在 [tasks] 表里，外部（工具/UI）只通过这里的方法交互。
 */
class BackgroundTaskManager(
    private val scope: CoroutineScope,
    private val eventBus: MutableSharedFlow<MederiEvent>? = null,
) {

    enum class BackgroundTaskStatus { RUNNING, COMPLETED, ERROR, STOPPED }

    enum class BackgroundTaskType { COMMAND, TIMER }

    data class BackgroundTaskEntry(
        val taskId: String,
        val type: BackgroundTaskType,
        /** 发起会话 ID：abort/rollback 级联收割（stopAllForSession）的索引键。 */
        val sessionId: String,
        val command: String? = null,
        val description: String? = null,
        val pid: Long? = null,
        val workDir: String? = null,
        val durationSeconds: Int? = null,
        @Volatile var status: BackgroundTaskStatus = BackgroundTaskStatus.RUNNING,
        @Volatile var job: Job? = null,
        @Volatile var exitCode: Int? = null,
        @Volatile var output: String? = null,
        val startedAt: String = java.time.Instant.now().toString(),
        @Volatile var completedAt: String? = null,
    )

    private val tasks = ConcurrentHashMap<String, BackgroundTaskEntry>()

    /**
     * 生命周期事件发射（eventBus 未注入时静默跳过）。
     * NonCancellable：终态发射常处于协程取消路径（STOPPED / abort 级联收割），
     * 裸 emit 会在挂起点抛 CancellationException 把事件吞掉。
     */
    private suspend fun emitEvent(sessionId: String, type: EventType, payload: Map<String, String>) {
        val bus = eventBus ?: return
        withContext(NonCancellable) {
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
     * 接管一个已启动的 [Process]，后台跟踪直到终态，返回 taskId。
     *
     * 进程启动/注册（ProcessRegistry.register）由调用方完成（与 ShellTools.runCommand 同模式）；
     * 本方法负责输出读取（FutureTask+Thread，同 ShellTools）、超时整组回收、
     * 终态判定与事件发射。终态时从 ProcessRegistry 移除该 pid（finally 兜底，幂等）。
     *
     * @param timeoutSeconds 超时秒数；超时未结束 = ERROR（exitCode=-1，output 带超时提示）。
     */
    fun trackCommand(
        sessionId: String,
        process: Process,
        command: String,
        workDir: String,
        timeoutSeconds: Int
    ): String {
        val taskId = "bg_${UUID.randomUUID().toString().take(8)}"
        val pid = process.pid()
        val entry = BackgroundTaskEntry(
            taskId = taskId,
            type = BackgroundTaskType.COMMAND,
            sessionId = sessionId,
            command = command,
            pid = pid,
            workDir = workDir
        )
        tasks[taskId] = entry

        val job = scope.launch {
            emitEvent(
                sessionId,
                EventType.BACKGROUND_TASK_STARTED,
                mapOf(
                    "taskId" to taskId,
                    "taskType" to BackgroundTaskType.COMMAND.name.lowercase(),
                    "status" to BackgroundTaskStatus.RUNNING.name.lowercase(),
                    "command" to command,
                    "pid" to pid.toString(),
                    "startedAt" to entry.startedAt
                )
            )
            try {
                val outputFuture = FutureTask {
                    process.inputStream.bufferedReader().use { it.readText() }
                }
                Thread(outputFuture).start()

                val completed = process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)

                if (!completed) {
                    // 超时整组回收：仅 destroyForcibly 只杀直接子进程，后台孙进程会变孤儿继续跑
                    ProcessRegistry.killGroup(pid, pid, force = true)
                    process.destroyForcibly()
                    outputFuture.get(1, TimeUnit.SECONDS)
                    val partialOutput = outputFuture.get()
                    entry.output = partialOutput
                    entry.exitCode = -1
                    // stop() 已抢先置 STOPPED 时不得覆盖（stop 先终止进程再让本协程自然醒来）
                    if (entry.status != BackgroundTaskStatus.STOPPED) {
                        entry.status = BackgroundTaskStatus.ERROR
                        entry.output = "Command timed out after ${timeoutSeconds}s\n$partialOutput"
                    }
                } else {
                    val output = outputFuture.get(1, TimeUnit.SECONDS)
                    val exitCode = process.exitValue()
                    entry.output = output
                    entry.exitCode = exitCode
                    if (entry.status != BackgroundTaskStatus.STOPPED) {
                        entry.status = if (exitCode == 0) {
                            BackgroundTaskStatus.COMPLETED
                        } else {
                            BackgroundTaskStatus.ERROR
                        }
                    }
                }
            } catch (e: CancellationException) {
                entry.status = BackgroundTaskStatus.STOPPED
                throw e
            } catch (e: Throwable) {
                entry.status = BackgroundTaskStatus.ERROR
                entry.output = entry.output?.let { "$it\n[background task error] ${e.message ?: e.javaClass.simpleName}" }
                    ?: "[background task error] ${e.message ?: e.javaClass.simpleName}"
            } finally {
                ProcessRegistry.remove(pid)
                entry.completedAt = java.time.Instant.now().toString()
                emitTerminalEvent(sessionId, entry)
                // STOPPED 任务保留在 map 中（供 EnvironmentInfoProvider 下轮注入环境上下文，
                // 由 consumeStoppedForSession 消费后移除）；COMPLETED/ERROR 立即移除。
                if (entry.status != BackgroundTaskStatus.STOPPED) {
                    tasks.remove(taskId)
                }
            }
        }
        entry.job = job
        return taskId
    }

    /**
     * 启动一个后台定时器（timer 型任务），到时置 COMPLETED，返回 taskId。
     *
     * @param durationSeconds 定时时长（秒）；负值/非法值由 delay 抛错 → ERROR 终态。
     */
    fun startTimer(sessionId: String, durationSeconds: Int, description: String): String {
        val taskId = "timer_${UUID.randomUUID().toString().take(8)}"
        val entry = BackgroundTaskEntry(
            taskId = taskId,
            type = BackgroundTaskType.TIMER,
            sessionId = sessionId,
            description = description,
            durationSeconds = durationSeconds
        )
        tasks[taskId] = entry

        val job = scope.launch {
            emitEvent(
                sessionId,
                EventType.BACKGROUND_TASK_STARTED,
                mapOf(
                    "taskId" to taskId,
                    "taskType" to BackgroundTaskType.TIMER.name.lowercase(),
                    "status" to BackgroundTaskStatus.RUNNING.name.lowercase(),
                    "description" to description,
                    "durationSeconds" to durationSeconds.toString(),
                    "startedAt" to entry.startedAt
                )
            )
            try {
                delay(durationSeconds * 1000L)
                // stop() 已抢先置 STOPPED 时不得覆盖（理论上 delay 被取消会走 CancellationException 分支，这里兜底）
                if (entry.status != BackgroundTaskStatus.STOPPED) {
                    entry.status = BackgroundTaskStatus.COMPLETED
                }
            } catch (e: CancellationException) {
                entry.status = BackgroundTaskStatus.STOPPED
                throw e
            } catch (e: Throwable) {
                entry.status = BackgroundTaskStatus.ERROR
            } finally {
                entry.completedAt = java.time.Instant.now().toString()
                emitTerminalEvent(sessionId, entry)
                // STOPPED 任务保留（同 trackCommand 的 finally 逻辑）
                if (entry.status != BackgroundTaskStatus.STOPPED) {
                    tasks.remove(taskId)
                }
            }
        }
        entry.job = job
        return taskId
    }

    /**
     * 主动停止任务，返回操作描述；taskId 不存在时返回 null。
     *
     * 先置 STOPPED 再取消协程/杀进程：COMMAND 型协程阻塞在 waitFor（不可取消），
     * 只有进程被杀后 waitFor 才返回——若后置状态，协程醒来可能把状态覆盖成
     * COMPLETED/ERROR 再发射错误终态事件。先置状态保证终态事件 = STOPPED。
     */
    fun stop(taskId: String): String? {
        val entry = tasks[taskId] ?: return null
        entry.status = BackgroundTaskStatus.STOPPED
        entry.job?.cancel()
        if (entry.type == BackgroundTaskType.COMMAND && entry.pid != null) {
            // 杀整个进程组（宿主侧执行，沙箱外可发信号）
            ProcessRegistry.terminate(entry.pid, force = true)
        }
        return "Stopped task $taskId"
    }

    /**
     * 级联停止一个会话的所有 RUNNING 后台任务（abort / rollback 级联收割，与
     * SubagentManager.stopAllForSession 同用途）。COMMAND 型同时整组杀进程——
     * 只 cancel 协程的话 waitFor 阻塞不会被打断，进程会跑到超时才算停。
     * 已终态的任务不受影响；方法幂等。
     *
     * @return 实际停止的 RUNNING 任务数量
     */
    fun stopAllForSession(sessionId: String): Int {
        var stopped = 0
        for (entry in tasks.values) {
            if (entry.sessionId == sessionId && entry.status == BackgroundTaskStatus.RUNNING) {
                entry.status = BackgroundTaskStatus.STOPPED
                entry.job?.cancel()
                if (entry.type == BackgroundTaskType.COMMAND && entry.pid != null) {
                    ProcessRegistry.terminate(entry.pid, force = true)
                }
                stopped++
            }
        }
        return stopped
    }

    /** 查询一个会话当前 RUNNING 后台任务数（纯读，tasks 为 ConcurrentHashMap，无需加锁）。 */
    fun runningCountForSession(sessionId: String): Int =
        tasks.values.count { it.sessionId == sessionId && it.status == BackgroundTaskStatus.RUNNING }

    /** 列出指定会话的全部后台任务（含终态未移除前的记录），按 [BackgroundTaskEntry.startedAt] 倒序。 */
    fun listForSession(sessionId: String): List<BackgroundTaskEntry> =
        tasks.values.filter { it.sessionId == sessionId }.sortedByDescending { it.startedAt }

    /**
     * 取出并移除指定会话的 STOPPED 后台任务（取出即消费，不重复注入）。
     *
     * 供 EnvironmentInfoProvider 在下轮构建环境上下文时调用：AI 在用户下次发消息时
     * 从 Runtime Environment 段看到"这些任务被用户停了"，无需单独唤醒（STOPPED 不发新 turn）。
     * 取出后从 map 移除，后续轮次不再重复显示。
     */
    fun consumeStoppedForSession(sessionId: String): List<BackgroundTaskEntry> {
        val stopped = tasks.values.filter {
            it.sessionId == sessionId && it.status == BackgroundTaskStatus.STOPPED
        }
        for (entry in stopped) {
            tasks.remove(entry.taskId)
        }
        return stopped.sortedByDescending { it.completedAt ?: it.startedAt }
    }

    /**
     * 终态事件发射：按 entry.status 映射 COMPLETED / ERROR / STOPPED，
     * payload 携带 taskId / taskType / status / command?/description? / pid? / exitCode? /
     * output?（截断 ~4000 字符，防事件 payload 膨胀）/ startedAt / completedAt? / durationSeconds?。
     */
    private suspend fun emitTerminalEvent(sessionId: String, entry: BackgroundTaskEntry) {
        val type = when (entry.status) {
            BackgroundTaskStatus.COMPLETED -> EventType.BACKGROUND_TASK_COMPLETED
            BackgroundTaskStatus.STOPPED -> EventType.BACKGROUND_TASK_STOPPED
            BackgroundTaskStatus.ERROR -> EventType.BACKGROUND_TASK_ERROR
            // 终态发射时仍 RUNNING 是异常态（不应发生），按 ERROR 兜底如实暴露
            BackgroundTaskStatus.RUNNING -> EventType.BACKGROUND_TASK_ERROR
        }
        val payload = buildMap {
            put("taskId", entry.taskId)
            put("taskType", entry.type.name.lowercase())
            put("status", entry.status.name.lowercase())
            entry.command?.let { put("command", it) }
            entry.description?.let { put("description", it) }
            entry.pid?.let { put("pid", it.toString()) }
            entry.exitCode?.let { put("exitCode", it.toString()) }
            entry.output?.let { put("output", truncateOutput(it)) }
            put("startedAt", entry.startedAt)
            entry.completedAt?.let { put("completedAt", it) }
            entry.durationSeconds?.let { put("durationSeconds", it.toString()) }
        }
        emitEvent(sessionId, type, payload)
    }

    /** 按 maxChars 截断，超长时追加省略提示（不丢信息：提示写明省略了多少字符）。 */
    private fun truncateOutput(raw: String, maxChars: Int = 4000): String =
        if (raw.length > maxChars) {
            raw.take(maxChars) + "\n... [truncated, ${raw.length - maxChars} chars omitted]"
        } else {
            raw
        }
}
