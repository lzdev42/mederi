package xyz.mederi.browser

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
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
 * 浏览器选择走 [BrowserRegistry]。主代理通过 browser(RUN) 的 browser 参数选择宿主已注册的浏览器。
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
 * @param runtimeProvider 每任务可选的运行目录解析；null = 用构造参数 workingDir/recipeStore。
 */
class BrowserTaskManager(
    private val providerManager: ProviderManager,
    private val scope: CoroutineScope,
    private val eventBus: MutableSharedFlow<MederiEvent>,
    private val workingDir: File? = null,
    private val recipeStore: RecipeStore? = null,
    private val llmCallerProvider: ((aiModel: AIModel, reasoningLevel: ReasoningLevel, apiKeyId: String?) -> BrowserLLMCaller)? = null,
    private val subagentConfigManager: SubagentConfigManager? = null,
    /**
     * 每任务运行目录解析（每次 runTask 调用）；
     * null = 用构造参数 workingDir/recipeStore。
     */
    private val runtimeProvider: (suspend () -> BrowserRuntimeDirs?)? = null
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
        @Volatile var memory: String = "",
        @Volatile var brainResult: BrainResult? = null,
        val collector: BrowserCollector = BrowserCollector(),
        val steps: MutableList<String> = mutableListOf(),
        val createdAt: String = Instant.now().toString()
    )

    private val tasks = ConcurrentHashMap<String, BrowserTask>()

    /**
     * 判定当前任务是否需要大脑介入进行内容/规则判定。
     * 留口设计：后续可在此扩展挂载规则、显式声明或动态判定的接入方式。
     */
    private fun needsBrainJudgment(recipe: BrowserRecipe?, taskObj: BrowserTask): Boolean {
        // 1. 如果配方包含判定规则，进入大脑判定
        if (!recipe?.judgeRules.isNullOrBlank()) return true
        // 2. 留口：后续可扩展任务挂载的结构化判定信息、显式要求或操作员中间触发等
        return false
    }

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
                    message = "没有可用的浏览器：宿主尚未注册浏览器后端"
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
        emit(
            taskId = taskId,
            type = EventType.BROWSER_TASK_STARTED,
            status = TaskStatus.STARTED,
            parentSessionId = taskObj.parentSessionId,
            browser = resolved.name,
            message = "任务已派发"
        )

        val job = scope.launch {
            val control = resolved.factory(taskId, taskObj.parentSessionId)
            taskObj.control = control
            taskObj.status = TaskStatus.RUNNING
            emit(
                taskId = taskId,
                type = EventType.BROWSER_TASK_STEP,
                status = TaskStatus.RUNNING,
                parentSessionId = taskObj.parentSessionId,
                browser = resolved.name,
                message = "启动浏览器"
            )

            // 宿主可为每项任务提供报告与配方目录，解析失败时回退到构造参数。
            val runtime = runtimeProvider?.let { provider -> runCatching { provider() }.getOrNull() }
            val effectiveWorkingDir = runtime?.workingDir ?: workingDir
            val effectiveRecipeStore = runtime?.skillsDir?.let { RecipeStore(it) } ?: recipeStore
            val effectiveRecipe = resolveRecipeFrom(recipe, effectiveRecipeStore)

            // 浏览器子代理独立模型解析（2026-09-23）：操作者（BROWSER_OPERATOR）与大脑（BROWSER_BRAIN）
            // 在设置页各自可配模型/推理档；配置了独立模型则覆盖，否则继承父会话模型。
            // 浏览器实现由主代理在 browser(RUN) 的 browser 参数选择，与角色配置无关。
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

            val drillExecutor = DrillExecutor(control = control, workingDir = effectiveWorkingDir)

            val brain = BrowserBrain(
                llmCaller = brainLlmCaller,
                workingDir = effectiveWorkingDir
            )

            val runner = BrowserOperator(
                control = control,
                aiModel = operatorModel,
                llmCaller = operatorLlmCaller,
                brain = brain,
                drillExecutor = drillExecutor,
                recipe = effectiveRecipe,
                maxSteps = BrowserConstants.DEFAULT_MAX_STEPS,
                collector = taskObj.collector,
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
                        taskId = taskId,
                        type = EventType.BROWSER_TASK_STEP,
                        status = TaskStatus.RUNNING,
                        parentSessionId = taskObj.parentSessionId,
                        step = step,
                        thought = thought,
                        memory = taskObj.memory,
                        results = results,
                        collectedCount = taskObj.collector.size()
                    )
                },
                onStream = { step, kind, delta ->
                    // decide 阶段 reasoning/text 增量：只 emit，不更新 lastStep/lastThought（那是 STEP 的事）
                    emit(
                        taskId = taskId,
                        type = EventType.BROWSER_TASK_REASONING_DELTA,
                        status = TaskStatus.RUNNING,
                        parentSessionId = taskObj.parentSessionId,
                        step = step,
                        kind = kind,
                        content = delta
                    )
                },
                onMemoryUpdate = { text -> taskObj.memory = text }
            )
            try {
                val opResult = runner.run(task)

                // 两种汇报情况：
                // 1. 需要判定：先把信息给大脑，大脑反馈给它以后，再给主 agent；
                // 2. 无需判定：操作员直接汇报给主 agent。
                val needsJudgment = needsBrainJudgment(effectiveRecipe, taskObj)
                val (executiveSummary, reportPath) = if (needsJudgment) {
                    val reportResult = runCatching {
                        brain.generateFinalReport(
                            taskHistory = taskObj.steps.joinToString("\n"),
                            goal = task,
                            rawOperatorMessage = opResult.message,
                            recipeRules = effectiveRecipe?.judgeRules.orEmpty(),
                            collectedItems = taskObj.collector.list()
                        )
                    }.getOrNull()
                    taskObj.brainResult = reportResult
                    val summary = if (reportResult != null && reportResult.success) {
                        val s = reportResult.data["summary"]
                        val fileNotice = if (reportResult.filesWritten.isNotEmpty()) {
                            " (报告已保存: ${reportResult.filesWritten.joinToString(", ")})"
                        } else ""
                        if (!s.isNullOrBlank()) "$s$fileNotice" else opResult.message
                    } else {
                        opResult.message
                    }
                    summary to reportResult?.filesWritten?.firstOrNull()
                } else {
                    opResult.message to null
                }

                val finalResult = opResult.copy(message = executiveSummary)
                taskObj.result = finalResult
                taskObj.status = if (opResult.success) TaskStatus.COMPLETED else TaskStatus.ERROR
                taskObj.error = if (opResult.success) null else opResult.message
                emit(
                    taskId = taskId,
                    type = if (opResult.success) EventType.BROWSER_TASK_COMPLETED else EventType.BROWSER_TASK_ERROR,
                    status = taskObj.status,
                    parentSessionId = taskObj.parentSessionId,
                    message = executiveSummary,
                    role = SubagentRole.BROWSER_OPERATOR.name,
                    reportPath = reportPath,
                    collectedCount = taskObj.collector.size()
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 幂等：stopTask 已先置状态并 emit 过 STOPPED，这里不再重复改写/重复通知；直接重抛
                if (taskObj.status != TaskStatus.STOPPED) {
                    taskObj.status = TaskStatus.STOPPED
                    taskObj.error = "已停止"
                    emit(
                        taskId = taskId,
                        type = EventType.BROWSER_TASK_STOPPED,
                        status = TaskStatus.STOPPED,
                        parentSessionId = taskObj.parentSessionId,
                        message = "任务已停止",
                        role = SubagentRole.BROWSER_OPERATOR.name
                    )
                }
                throw e
            } catch (e: Throwable) {
                taskObj.status = TaskStatus.ERROR
                taskObj.error = e.message ?: e::class.simpleName.orEmpty()
                emit(
                    taskId = taskId,
                    type = EventType.BROWSER_TASK_ERROR,
                    status = TaskStatus.ERROR,
                    parentSessionId = taskObj.parentSessionId,
                    message = taskObj.error,
                    role = SubagentRole.BROWSER_OPERATOR.name
                )
            } finally {
                val ctl = taskObj.control
                taskObj.control = null
                if (ctl != null) {
                    // 取消路径下普通挂起调用会立即抛 CancellationException，必须用 NonCancellable 保证真正关闭
                    withContext(NonCancellable) {
                        try {
                            ctl.close()
                        } catch (_: Throwable) {
                            DebugLog.error("BrowserTask", "control.close failed in finally")
                        }
                    }
                }
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
                message = if (t.status == TaskStatus.COMPLETED) t.result?.message else t.error,
                collectedCount = t.collector.size()
            )
        )
    }

    /**
     * 停止任务并返回停止后的状态名（STOPPED；任务已终态时返回其真实状态 COMPLETED/ERROR/STOPPED）；
     * taskId 不存在返回 null。停止会真正关闭该任务的浏览器。
     */
    override fun stopTask(taskId: String): String? {
        val t = tasks[taskId] ?: return null
        // 终态任务如实返回真实状态，不再伪装成 STOPPED
        if (t.status == TaskStatus.COMPLETED || t.status == TaskStatus.ERROR || t.status == TaskStatus.STOPPED) {
            return t.status.name
        }
        // 先标记 + 通知（UI 立即响应），再取消协程；浏览器由协程 finally 在 NonCancellable 上下文真正关闭
        t.status = TaskStatus.STOPPED
        t.error = "已停止"
        emit(
            taskId = taskId,
            type = EventType.BROWSER_TASK_STOPPED,
            status = TaskStatus.STOPPED,
            parentSessionId = t.parentSessionId,
            message = "任务已停止",
            role = SubagentRole.BROWSER_OPERATOR.name
        )
        t.job.cancel()
        return TaskStatus.STOPPED.name
    }

    override fun availableBrowsers(): List<String> = BrowserRegistry.availableNames()

    override fun defaultBrowser(): String? = BrowserRegistry.default()?.name

    // ── 浏览器运行状态查询（browser_info，只读，与任务操作区分）──

    /** browser_info 工具返回 JSON（主代理用）。 */
    override suspend fun browserStatus(currentSessionId: String): String =
        Json.encodeToString(BrowserStatusInfo.serializer(), browserStatusInfo(currentSessionId))

    /**
     * 聚合当前所有已注册浏览器的运行状态。
     *
     * - JCEF：实例 = UI host 上报的所有 tab（含用户手动开的 + 已完成任务留下的）；
     *   每个 tab 的 taskId 决定 isCurrentSession（对照任务表的 parentSessionId）。
     * - 未提供状态源：实例取活跃任务（STARTED/RUNNING），URL 从 control 读取。
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
                // 无状态源时从活跃任务推导实例
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
            brainResult = t.brainResult,
            collectedItems = t.collector.list()
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
        brainResult = brainResult,
        collectedItems = collector.list()
    )

    // ── 内部 ──

    private fun emit(
        taskId: String,
        type: EventType,
        status: TaskStatus,
        parentSessionId: String,
        browser: String? = null,
        role: String = SubagentRole.BROWSER_OPERATOR.name,
        step: Int? = null,
        thought: String? = null,
        memory: String? = null,
        results: List<ActionResult>? = null,
        message: String? = null,
        reportPath: String? = null,
        collectedCount: Int? = null,
        kind: String? = null,
        content: String? = null
    ) {
        runCatching {
            eventBus.tryEmit(MederiEvent(
                type = type,
                sessionId = parentSessionId,
                payload = buildMap {
                    put("taskId", taskId)
                    put("status", status.name)
                    put("role", role)
                    browser?.let { put("browser", it) }
                    reportPath?.let { put("reportPath", it) }
                    step?.let { put("step", it.toString()) }
                    thought?.let { put("thought", it) }
                    memory?.let { put("memory", it) }
                    results?.let {
                        put("results", it.joinToString(";") { "${it.action}:${it.success}" })
                        put("resultsJson", Json.encodeToString(ListSerializer(ActionResult.serializer()), it))
                    }
                    message?.let { put("message", it) }
                    collectedCount?.let { put("collectedCount", it.toString()) }
                    kind?.let { put("kind", it) }
                    content?.let { put("content", it) }
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
    val message: String? = null,
    val collectedCount: Int = 0
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
    val brainResult: BrainResult? = null,
    val collectedItems: List<BrowserCollectedItem> = emptyList()
)

/** 宿主注入的浏览器任务报告与配方目录。 */
data class BrowserRuntimeDirs(val workingDir: File?, val skillsDir: File?)

/**
 * ST3：配方解析——非 inline recipe（未带 drillScript）且配了 store 时，
 * 按 recipe.name 从 skills 目录加载 skill 增强配方（operator 规则/判定规则/drill 脚本）。
 * 加载失败（文件不存在/解析异常）安全回退原 recipe（不兜底不伪造，直接按原名挂载）。
 */
internal fun resolveRecipeFrom(recipe: BrowserRecipe?, store: RecipeStore?): BrowserRecipe? {
    if (recipe == null || store == null || recipe.drillScript != null) return recipe
    val loaded = store.load(recipe.name) ?: return recipe
    return BrowserRecipe(
        name = loaded.id,
        description = loaded.description,
        operatorContent = loaded.operatorContent,
        judgeRules = loaded.judgeRules,
        drillScript = loaded.drillScriptJson
    )
}
