package xyz.mederi.browser

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.mederi.debug.DebugLog
import xyz.mederi.domain.model.AIModel
import xyz.mederi.domain.model.EventType
import xyz.mederi.domain.model.MederiEvent
import xyz.mederi.provider.ProviderManager
import xyz.mederi.provider.domain.model.ReasoningLevel
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 浏览器任务管理：主代理与浏览器自动化之间的唯一入口。
 *
 * 主代理只看到三个工具（run_browser_task / browser_task_status / stop_browser_task）——
 * 派发、查状态、停止。BROWSER agent（BrowserAgentRunner）在后台协程运行，
 * 主代理的 turn 不阻塞，可继续对话。任务细节通过事件流暴露给 UI（用户自己看），
 * 主代理不需要看。
 *
 * 浏览器选择走 [BrowserRegistry]：UI 注册 JCEF、core 注册 Camoufox，AI 通过
 * run_browser_task 的 browser 参数选择，工具描述动态列出可用浏览器。
 *
 * 所有复杂状态收敛在 [tasks] 表里。
 *
 * @param providerManager 供应商管理。
 * @param scope 后台协程作用域。
 * @param eventBus 全局事件总线（浏览器任务事件供 UI 消费）。
 */
class BrowserTaskManager(
    private val providerManager: ProviderManager,
    private val scope: CoroutineScope,
    private val eventBus: MutableSharedFlow<MederiEvent>
) : BrowserTaskService {

    enum class TaskStatus { STARTED, RUNNING, COMPLETED, ERROR, STOPPED }

    data class BrowserTask(
        val taskId: String,
        val task: String,
        val aiModel: AIModel,
        val reasoningLevel: ReasoningLevel,
        val projectId: String,
        val parentSessionId: String,
        @Volatile var browserName: String,
        @Volatile var job: Job,
        @Volatile var status: TaskStatus,
        @Volatile var result: BrowserTaskResult?,
        @Volatile var error: String?,
        @Volatile var lastStep: Int = 0,
        @Volatile var lastThought: String = "",
        @Volatile var control: BrowserControl? = null,
        @Volatile var lastUrl: String = "",
        @Volatile var lastTitle: String = "",
        val steps: MutableList<String> = mutableListOf(),
        val createdAt: String = Instant.now().toString()
    )

    private val tasks = ConcurrentHashMap<String, BrowserTask>()

    /**
     * 异步派发浏览器任务，立即返回 taskId（不阻塞）。
     * browser 为 null → 用注册表默认；注册表为空 → 返回错误 JSON。
     */
    override fun runTask(
        task: String,
        aiModel: AIModel,
        reasoningLevel: ReasoningLevel,
        projectId: String,
        parentSessionId: String,
        browser: String?
    ): String {
        val resolved = BrowserRegistry.resolve(browser)
            ?: return Json.encodeToString(
                TaskStatusResult(
                    taskId = "none",
                    status = "ERROR",
                    message = "没有可用的浏览器：请先在设置里配置 browserHome 并下载 Camoufox，或由 UI 注册内置 JCEF"
                )
            )
        val taskId = "bt_${UUID.randomUUID().toString().take(8)}"
        val taskObj = BrowserTask(
            taskId = taskId,
            task = task,
            aiModel = aiModel,
            reasoningLevel = reasoningLevel,
            projectId = projectId,
            parentSessionId = parentSessionId,
            browserName = resolved.name,
            job = Job(),
            status = TaskStatus.STARTED,
            result = null,
            error = null
        )
        tasks[taskId] = taskObj
        emit(taskId, EventType.BROWSER_TASK_STARTED, status = TaskStatus.STARTED, browser = resolved.name, message = "任务已派发")

        val job = scope.launch {
            val control = createBrowserControl(resolved, taskId)
            taskObj.control = control
            taskObj.status = TaskStatus.RUNNING
            emit(taskId, EventType.BROWSER_TASK_STEP, status = TaskStatus.RUNNING, browser = resolved.name, message = "启动浏览器")
            val runner = BrowserAgentRunner(
                providerManager = providerManager,
                browserControl = control,
                aiModel = aiModel,
                reasoningLevel = reasoningLevel,
                onStep = { step, thought, results ->
                    taskObj.lastStep = step
                    taskObj.lastThought = thought
                    taskObj.steps.add("Step $step: $thought | ${results.joinToString(", ") { it.action }}")
                    // 尽力刷新当前 URL/标题（后台，供 browser_info / taskStatus 展示）
                    scope.launch {
                        taskObj.lastUrl = runCatching { control.getCurrentUrl() }.getOrNull().orEmpty()
                        taskObj.lastTitle = runCatching { control.getTitle() }.getOrNull().orEmpty()
                    }
                    emit(
                        taskId,
                        EventType.BROWSER_TASK_STEP,
                        status = TaskStatus.RUNNING,
                        step = step,
                        thought = thought,
                        results = results
                    )
                }
            )
            try {
                val result = runner.run(task)
                taskObj.result = result
                taskObj.status = if (result.success) TaskStatus.COMPLETED else TaskStatus.ERROR
                taskObj.error = if (result.success) null else result.message
                emit(
                    taskId,
                    if (result.success) EventType.BROWSER_TASK_COMPLETED else EventType.BROWSER_TASK_ERROR,
                    status = taskObj.status,
                    message = result.message
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                taskObj.status = TaskStatus.STOPPED
                taskObj.error = "已停止"
                emit(taskId, EventType.BROWSER_TASK_STOPPED, status = TaskStatus.STOPPED, message = "任务已停止")
                throw e
            } catch (e: Throwable) {
                taskObj.status = TaskStatus.ERROR
                taskObj.error = e.message ?: e::class.simpleName.orEmpty()
                emit(taskId, EventType.BROWSER_TASK_ERROR, status = TaskStatus.ERROR, message = taskObj.error)
            }
        }
        taskObj.job = job
        return taskId
    }

    /**
     * 查询任务状态（主代理用，只给状态，不给细节）。
     */
    override fun taskStatus(taskId: String): String {
        val t = tasks[taskId]
            ?: return Json.encodeToString(TaskStatusResult(taskId = taskId, status = "NOT_FOUND"))
        return Json.encodeToString(
            TaskStatusResult(
                taskId = taskId,
                status = t.status.name,
                browser = t.browserName,
                message = if (t.status == TaskStatus.COMPLETED) t.result?.message else t.error
            )
        )
    }

    /**
     * 停止任务（主代理用），返回当前状态。
     */
    override fun stopTask(taskId: String): String {
        val t = tasks[taskId]
            ?: return Json.encodeToString(TaskStatusResult(taskId = taskId, status = "NOT_FOUND"))
        t.job.cancel()
        return Json.encodeToString(
            TaskStatusResult(taskId = taskId, status = TaskStatus.STOPPED.name, browser = t.browserName)
        )
    }

    override fun availableBrowsers(): List<String> = BrowserRegistry.availableNames()

    override fun defaultBrowser(): String? = BrowserRegistry.default()?.name

    // ── 浏览器运行状态查询（browser_info，只读，与任务操作区分）──

    /** browser_info 工具返回 JSON（主代理用）。 */
    override suspend fun browserStatus(currentSessionId: String): String =
        Json.encodeToString(BrowserStatusInfo.serializer(), browserStatusInfo(currentSessionId))

    /**
     * 聚合当前所有已注册浏览器的运行状态（内置 JCEF 与外置 Camoufox 可同时打开）。
     *
     * - JCEF：实例 = UI host 上报的所有 tab（含用户手动开的 + 已完成任务留下的）；
     *   每个 tab 的 taskId 决定 isCurrentSession（对照任务表的 parentSessionId）。
     * - Camoufox：实例 = 活跃任务（STARTED/RUNNING，即正在跑的无头进程），URL 实时从 control 读取。
     */
    suspend fun browserStatusInfo(currentSessionId: String): BrowserStatusInfo {
        val entries = BrowserRegistry.list().map { registered ->
            val taskIdToTask = tasks.values.associateBy { it.taskId }
            val instances = mutableListOf<BrowserInstanceInfo>()
            if (registered.statusSource != null) {
                // 有 UI 状态源的（JCEF）：以 host 上报为准（覆盖任务驱动 + 用户手动开的 tab）
                runCatching { registered.statusSource.instances() }.getOrElse { emptyList() }
                    .forEach { inst ->
                        val task = inst.taskId?.let { taskIdToTask[it] }
                        instances += BrowserInstanceInfo(
                            instanceId = inst.id,
                            origin = if (inst.taskId != null) "task" else "user",
                            url = inst.url,
                            title = inst.title,
                            isCurrentSession = inst.taskId != null &&
                                task?.parentSessionId == currentSessionId,
                            status = task?.status?.name ?: "OPEN"
                        )
                    }
            } else {
                // 无状态源（Camoufox）：活跃任务 = 打开的无头进程
                tasks.values
                    .filter {
                        it.browserName == registered.name &&
                            (it.status == TaskStatus.STARTED || it.status == TaskStatus.RUNNING)
                    }
                    .forEach { t ->
                        val url = t.lastUrl.ifEmpty {
                            runCatching { t.control?.getCurrentUrl() }.getOrNull().orEmpty()
                        }
                        instances += BrowserInstanceInfo(
                            instanceId = t.taskId,
                            origin = "task",
                            url = url,
                            title = t.lastTitle,
                            isCurrentSession = t.parentSessionId == currentSessionId,
                            status = t.status.name
                        )
                    }
            }
            BrowserStatusEntry(
                name = registered.name,
                kind = registered.kind.name,
                label = registered.kind.label,
                inUse = instances.isNotEmpty(),
                instances = instances
            )
        }
        return BrowserStatusInfo(browsers = entries)
    }

    // ── 用户详情 API（UI 层用，不经过主代理）──

    fun getTaskDetails(taskId: String): BrowserTaskDetails? {
        val t = tasks[taskId] ?: return null
        return BrowserTaskDetails(
            taskId = t.taskId,
            task = t.task,
            status = t.status.name,
            browser = t.browserName,
            result = t.result?.message,
            error = t.error,
            lastStep = t.lastStep,
            lastThought = t.lastThought,
            steps = t.steps.toList(),
            createdAt = t.createdAt
        )
    }

    fun listTasks(): List<BrowserTaskDetails> =
        tasks.values.sortedByDescending { it.createdAt }.map { it.toDetails() }

    private fun BrowserTask.toDetails(): BrowserTaskDetails = BrowserTaskDetails(
        taskId = taskId,
        task = task,
        status = status.name,
        browser = browserName,
        result = result?.message,
        error = error,
        lastStep = lastStep,
        lastThought = lastThought,
        steps = steps.toList(),
        createdAt = createdAt
    )

    // ── 内部 ──

    private suspend fun createBrowserControl(registered: RegisteredBrowser, taskId: String): BrowserControl =
        registered.factory(taskId)

    private fun emit(
        taskId: String,
        type: EventType,
        status: TaskStatus,
        browser: String? = null,
        step: Int? = null,
        thought: String? = null,
        results: List<ActionResult>? = null,
        message: String? = null
    ) {
        runCatching {
            eventBus.tryEmit(MederiEvent(
                type = type,
                sessionId = "",  // 浏览器任务不属于某个 session 的对话；UI 用 taskId 过滤
                payload = buildMap {
                    put("taskId", taskId)
                    put("status", status.name)
                    browser?.let { put("browser", it) }
                    step?.let { put("step", it.toString()) }
                    thought?.let { put("thought", it) }
                    results?.let { put("results", it.joinToString(";") { "${it.action}:${it.success}" }) }
                    message?.let { put("message", it) }
                },
                timestamp = Instant.now().toString()
            ))
        }.onFailure { DebugLog.error("BrowserTask", "emit failed: ${it.message}") }
    }
}

/** 任务状态结果（工具返回）。 */
@Serializable
data class TaskStatusResult(
    val taskId: String,
    val status: String,
    val browser: String? = null,
    val message: String? = null
)

/** 任务详情（用户 API）。 */
data class BrowserTaskDetails(
    val taskId: String,
    val task: String,
    val status: String,
    val browser: String,
    val result: String?,
    val error: String?,
    val lastStep: Int,
    val lastThought: String,
    val steps: List<String>,
    val createdAt: String
)
