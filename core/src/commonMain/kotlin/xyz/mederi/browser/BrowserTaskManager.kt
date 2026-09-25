package xyz.mederi.browser

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.mederi.browser.drill.DrillExecutor
import xyz.mederi.debug.DebugLog
import xyz.mederi.domain.model.AIModel
import xyz.mederi.domain.model.EventType
import xyz.mederi.domain.model.MederiEvent
import xyz.mederi.domain.model.SubagentRole
import xyz.mederi.provider.ProviderManager
import xyz.mederi.provider.domain.model.ReasoningLevel
import xyz.mederi.tools.subagent.SubagentConfigManager
import java.io.File
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
 * @param providerManager 供应商管理（仅 llmCallerProvider == null 时触达，走 BrowserLLMHelper 真路径）。
 * @param scope 后台协程作用域。
 * @param eventBus 全局事件总线（浏览器任务事件供 UI 消费）。
 * @param workingDir Brain 报告落盘目录（reports/）与 Drill 文件相对路径的解析根；null = 不落盘。
 * @param recipeStore 配方存储；非 null 且任务 recipe 非 inline（无 drillScript）时，按 recipe.name 解析 skill 增强配方。
 * @param llmCallerProvider LLM caller 工厂注入（测试/自定义 LLM 路径）；null = 走 [BrowserLLMHelper] 真路径。
 * @param subagentConfigManager 子代理模型配置（操作者/大脑独立模型解析）；null = 全部继承父会话模型。
 */
class BrowserTaskManager(
    private val providerManager: ProviderManager,
    private val scope: CoroutineScope,
    private val eventBus: MutableSharedFlow<MederiEvent>,
    private val workingDir: File? = null,
    private val recipeStore: RecipeStore? = null,
    private val llmCallerProvider: ((aiModel: AIModel, reasoningLevel: ReasoningLevel, apiKeyId: String?) -> BrowserLLMCaller)? = null,
    private val subagentConfigManager: SubagentConfigManager? = null
) : BrowserTaskService {

    enum class TaskStatus { STARTED, RUNNING, COMPLETED, ERROR, STOPPED }

    data class BrowserTask(
        val taskId: String,
        val task: String,
        val aiModel: AIModel,
        val reasoningLevel: ReasoningLevel,
        val projectId: String,
        val parentSessionId: String,
        val apiKeyId: String? = null,
        val recipe: BrowserRecipe? = null,
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
        @Volatile var brainResult: BrainResult? = null,
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
        browser: String?,
        apiKeyId: String?,
        recipe: BrowserRecipe?
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
            apiKeyId = apiKeyId,
            recipe = recipe,
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

            // ST3 完整接线：recipe 解析（RecipeStore）+ LLM caller 注入（llmCallerProvider 优先，
            // 否则 BrowserLLMHelper 真路径）+ workingDir/drillExecutor 真传（Brain 报告落盘、
            // Drill 文件路径相对解析），修复 ST2 的 null。
            val effectiveRecipe = resolveRecipe(recipe)

            // 浏览器子代理独立模型解析（2026-09-23）：操作者（BROWSER_OPERATOR）与大脑（BROWSER_BRAIN）
            // 在设置页各自可配模型/推理档；配置了独立模型则覆盖，否则继承父会话模型。
            // 浏览器实现（JCEF/Camoufox）由主代理在 browser(RUN) 的 browser 参数选择，与角色配置无关。
            val (operatorModel, operatorReasoning) = subagentConfigManager?.resolve(
                role = SubagentRole.BROWSER_OPERATOR,
                fallbackModel = aiModel,
                fallbackReasoning = reasoningLevel
            ) ?: (aiModel to reasoningLevel)
            val (brainModel, brainReasoning) = subagentConfigManager?.resolve(
                role = SubagentRole.BROWSER_BRAIN,
                fallbackModel = aiModel,
                fallbackReasoning = reasoningLevel
            ) ?: (aiModel to reasoningLevel)

            val operatorLlmCaller = llmCallerProvider?.invoke(operatorModel, operatorReasoning, apiKeyId)
                ?: BrowserLLMHelper(
                    providerManager = providerManager,
                    aiModel = operatorModel,
                    reasoningLevel = operatorReasoning,
                    apiKeyId = apiKeyId
                )
            val brainLlmCaller = llmCallerProvider?.invoke(brainModel, brainReasoning, apiKeyId)
                ?: BrowserLLMHelper(
                    providerManager = providerManager,
                    aiModel = brainModel,
                    reasoningLevel = brainReasoning,
                    apiKeyId = apiKeyId
                )

            val drillExecutor = DrillExecutor(control = control, workingDir = workingDir)

            val brain = BrowserBrain(
                llmCaller = brainLlmCaller,
                workingDir = workingDir,
                continuousMemory = false
            )

            val runner = BrowserOperator(
                control = control,
                aiModel = operatorModel,
                llmCaller = operatorLlmCaller,
                brain = brain,
                drillExecutor = drillExecutor,
                recipe = effectiveRecipe,
                maxSteps = 50,
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
                val opResult = runner.run(task)

                // 任务终态：由 BrowserBrain 生成结构化简报与报告
                val reportResult = runCatching {
                    brain.generateFinalReport(
                        taskHistory = taskObj.steps.joinToString("\n"),
                        goal = task,
                        rawOperatorMessage = opResult.message,
                        recipeRules = effectiveRecipe?.judgeRules.orEmpty()
                    )
                }.getOrNull()
                taskObj.brainResult = reportResult

                // 优先使用 Brain 产出的一句话简报给主管，主管查状态即得精简结论
                val executiveSummary = if (reportResult != null && reportResult.success) {
                    val summary = reportResult.data["summary"]
                    val fileNotice = if (reportResult.filesWritten.isNotEmpty()) {
                        " (报告已保存: ${reportResult.filesWritten.joinToString(", ")})"
                    } else ""
                    if (!summary.isNullOrBlank()) "$summary$fileNotice" else opResult.message
                } else {
                    opResult.message
                }

                val finalResult = opResult.copy(message = executiveSummary)
                taskObj.result = finalResult
                taskObj.status = if (opResult.success) TaskStatus.COMPLETED else TaskStatus.ERROR
                taskObj.error = if (opResult.success) null else opResult.message
                emit(
                    taskId,
                    if (opResult.success) EventType.BROWSER_TASK_COMPLETED else EventType.BROWSER_TASK_ERROR,
                    status = taskObj.status,
                    message = executiveSummary
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
            } finally {
                // ST2：Brain 不再持有 client（llmCaller 自管），无 close
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
            createdAt = t.createdAt,
            brainResult = t.brainResult
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
        createdAt = createdAt,
        brainResult = brainResult
    )

    // ── 内部 ──

    /**
     * ST3：配方解析——非 inline recipe（未带 drillScript）且配了 [recipeStore] 时，
     * 按 recipe.name 从 skills 目录加载 skill 增强配方（operator 规则/判定规则/drill 脚本）。
     * 加载失败（文件不存在/解析异常）安全回退原 recipe（不兜底不伪造，直接按原名挂载）。
     */
    private fun resolveRecipe(recipe: BrowserRecipe?): BrowserRecipe? {
        if (recipe == null || recipeStore == null || recipe.drillScript != null) return recipe
        val loaded = recipeStore.load(recipe.name) ?: return recipe
        return BrowserRecipe(
            name = loaded.id,
            description = loaded.description,
            operatorContent = loaded.operatorContent,
            judgeRules = loaded.judgeRules,
            drillScript = loaded.drillScriptJson
        )
    }

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
    val createdAt: String,
    /** Brain 终态分析结果（任务完成时由 BrowserBrain.generateFinalReport 产出，未完成/失败为 null）。 */
    val brainResult: BrainResult? = null
)
