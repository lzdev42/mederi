package xyz.mederi.browser

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.mederi.domain.model.AIModel
import xyz.mederi.provider.domain.model.ReasoningLevel
import xyz.mederi.tools.ToolName

/**
 * 主代理的浏览器任务工具集。
 *
 * 主代理只看到三个工具：派发、查状态、停止。浏览器内部怎么工作（BROWSER agent 操作、
 * BrowserBrain 判定、drill 执行）对主代理完全透明——主代理只需要知道任务是
 * 进行中、出错了、还是结束了，细节用户自己在浏览器任务面板看。
 */

/** run_browser_task 工具参数。 */
@Serializable
data class RunBrowserTaskArgs(
    @LLMDescription(
        "Task instruction for the browser sub-agent (natural language, outcome-oriented): the goal and the " +
            "result to produce. The sub-agent AUTONOMOUSLY plans HOW to achieve it — search method, which " +
            "search engine, navigation path, clicks, inputs are all its own decision. Do NOT prescribe how: " +
            "never specify a search engine (e.g. baidu/google/bing), concrete navigation sequences, or " +
            "low-level operation steps — hand down the goal only."
    )
    val task: String = "",
    @LLMDescription(
        "Browser to use (optional):\n" +
            "- LEAVE EMPTY by default: uses the user's preferred default browser configured in settings.\n" +
            "- ONLY specify a browser name if the user explicitly requested a specific browser engine by name in their prompt.\n" +
            "- Do NOT automatically pick a specific browser just because the target site is third-party."
    )
    val browser: String = "",
    @LLMDescription("Optional recipe name or rules configuration to mount onto the task for guiding execution and judgment.")
    val recipe: String = ""
)

@Serializable
data class RunBrowserTaskResult(
    val taskId: String,
    val status: String,
    val browser: String
)

/** 异步派发浏览器任务，立即返回 taskId。 */
class RunBrowserTaskTool(
    private val service: BrowserTaskService,
    private val aiModel: AIModel,
    private val reasoningLevel: ReasoningLevel,
    private val projectId: String,
    private val sessionId: String,
    private val apiKeyId: String? = null
) : SimpleTool<RunBrowserTaskArgs>(
    argsType = typeToken<RunBrowserTaskArgs>(),
    name = ToolName.RUN_BROWSER_TASK.value,
    description = buildDescription(service)
) {
    override suspend fun execute(args: RunBrowserTaskArgs): String {
        if (args.task.isBlank()) return "Error: task must not be empty."
        val defaultBrowser = service.defaultBrowser()
        val available = service.availableBrowsers()
        val browser = when {
            args.browser.isNotBlank() && available.contains(args.browser) -> args.browser
            args.browser.isNotBlank() && !available.contains(args.browser) -> {
                xyz.mederi.debug.DebugLog.info(
                    "RunBrowserTaskTool",
                    "Requested browser '${args.browser}' is not registered (available: $available); falling back to default '$defaultBrowser'"
                )
                defaultBrowser
            }
            else -> defaultBrowser
        }
        xyz.mederi.debug.DebugLog.info(
            "RunBrowserTaskTool",
            "execute: requested='${args.browser}', resolved='$browser', default='$defaultBrowser', available=$available"
        )

        val recipeObj = if (args.recipe.isNotBlank()) {
            runCatching {
                Json { ignoreUnknownKeys = true }.decodeFromString<BrowserRecipe>(args.recipe)
            }.getOrElse {
                BrowserRecipe(name = args.recipe, description = args.recipe)
            }
        } else null
        val taskId = service.runTask(
            task = args.task,
            aiModel = aiModel,
            reasoningLevel = reasoningLevel,
            projectId = projectId,
            parentSessionId = sessionId,
            browser = browser,
            apiKeyId = apiKeyId,
            recipe = recipeObj
        )
        return Json.encodeToString(
            RunBrowserTaskResult.serializer(),
            RunBrowserTaskResult(taskId = taskId, status = "RUNNING", browser = browser ?: "")
        )
    }

    companion object {
        /** 动态生成工具描述：列出当前已注册浏览器 + 选择指引。 */
        fun buildDescription(service: BrowserTaskService): String {
            val available = service.availableBrowsers()
            val default = service.defaultBrowser()
            val browserHint = if (available.isEmpty()) {
                "暂无已注册浏览器（需先安装并注册浏览器后才能执行浏览器任务）"
            } else {
                "Available browsers: ${available.joinToString(", ")} (default: ${default ?: "first registered"})"
            }
            return "Asynchronously dispatches a browser automation task to a DEDICATED BROWSER SUB-AGENT (not " +
                "run by you). The sub-agent navigates, clicks, types, and reports back; the task argument is " +
                "its instruction. Returns immediately with a taskId — the sub-agent runs in the background and " +
                "does not block your turn. Query it with browser_task_status(taskId) or stop it with " +
                "stop_browser_task(taskId). Task details are viewable in the browser panel. " +
                browserHint + ". IMPORTANT: Always leave 'browser' empty to use the user's default browser " +
                "preference, unless the user explicitly requested a specific browser engine by name in their prompt. " +
                "Do not pick a specific browser arbitrarily. " +
                "IMPORTANT: Give ONLY the goal and desired outcome in 'task'. Do NOT prescribe how — " +
                "no search engine, no navigation/click sequence, no low-level steps; the sub-agent autonomously " +
                "plans and executes the entire task itself."
        }
    }
}

/** browser_task_status 工具参数。 */
@Serializable
data class BrowserTaskStatusArgs(
    @LLMDescription("Browser task ID (taskId returned by browser RUN).")
    val taskId: String = ""
)

/** 查询浏览器任务状态（只给状态，不给细节）。 */
class BrowserTaskStatusTool(
    private val service: BrowserTaskService
) : SimpleTool<BrowserTaskStatusArgs>(
    argsType = typeToken<BrowserTaskStatusArgs>(),
    name = ToolName.BROWSER_TASK_STATUS.value,
    description = "Queries the current status of a browser task dispatched with run_browser_task: " +
        "STARTED / RUNNING / COMPLETED / ERROR / STOPPED."
) {
    override suspend fun execute(args: BrowserTaskStatusArgs): String {
        if (args.taskId.isBlank()) return "Error: taskId must not be empty."
        return service.taskStatus(args.taskId)
    }
}

/** stop_browser_task 工具参数。 */
@Serializable
data class StopBrowserTaskArgs(
    @LLMDescription("Browser task ID to stop.")
    val taskId: String = ""
)

/** 停止浏览器任务。 */
class StopBrowserTaskTool(
    private val service: BrowserTaskService
) : SimpleTool<StopBrowserTaskArgs>(
    argsType = typeToken<StopBrowserTaskArgs>(),
    name = ToolName.STOP_BROWSER_TASK.value,
    description = "Stops a running browser sub-agent task and closes its browser. The task cannot be " +
        "resumed."
) {
    override suspend fun execute(args: StopBrowserTaskArgs): String {
        if (args.taskId.isBlank()) return "Error: taskId must not be empty."
        val status = service.stopTask(args.taskId) ?: "NOT_FOUND"
        return Json.encodeToString(TaskStatusResult(taskId = args.taskId, status = status))
    }
}

/** browser_info 工具参数（无实际字段：直接返回当前浏览器运行状态）。 */
@Serializable
data class BrowserInfoArgs(
    @LLMDescription("Unused; the tool takes no parameters.")
    val unused: String = ""
)

/**
 * 浏览器运行状态查询（只读、无副作用）——与任务操作区分。
 * 返回当前所有浏览器的运行状态：各已注册浏览器是否在用、开了几个 tab/实例、
 * 各在哪个页面、是否由当前会话启动。
 */
class BrowserInfoTool(
    private val service: BrowserTaskService,
    private val sessionId: String
) : SimpleTool<BrowserInfoArgs>(
    argsType = typeToken<BrowserInfoArgs>(),
    name = ToolName.BROWSER_INFO.value,
    description = "Reads the current browser runtime status (no side effects, does NOT dispatch a task). " +
        "Returns per registered browser (multiple may be open at " +
        "once): inUse, count of open tabs/instances, each one's current page URL + title, and whether it " +
        "was started by the CURRENT session. Distinct from browser_task_status, which queries a specific " +
        "dispatched task."
) {
    override suspend fun execute(args: BrowserInfoArgs): String = service.browserStatus(sessionId)
}

// ==================== 合并入口（2026-09，4→1） ====================

/**
 * browser 工具的分派键（任务编排层；与 BrowserActions.kt 的页面操作原语 BrowserAction 区分）。
 */
@Serializable
enum class BrowserTaskAction {
    /** 派浏览器子代理执行任务（需要 task；可选 browser）。 */
    RUN,
    /** 查询已派发任务的状态（需要 taskId）。 */
    STATUS,
    /** 停止已派发的任务并关闭其浏览器（需要 taskId；不可恢复）。 */
    STOP,
    /** 读取浏览器运行时状态（无参数，只读无副作用）。 */
    INFO
}

/**
 * browser 工具参数。不同 [BrowserTaskAction] 使用其中不同的字段组合（见各字段说明）。
 */
@Serializable
data class BrowserArgs(
    @LLMDescription("Operation to perform: RUN (dispatch a task to the browser sub-agent), " +
        "STATUS (query a dispatched task), STOP (cancel a dispatched task), INFO (read browser " +
        "runtime state — no side effects).")
    val action: BrowserTaskAction,
    @LLMDescription("RUN: task instruction for the browser sub-agent (natural language, " +
        "outcome-oriented: which site, what result).")
    val task: String = "",
    @LLMDescription("RUN: browser to use. LEAVE EMPTY by default to use the user's preferred default browser configured in settings. ONLY specify a browser name if the user explicitly requested a specific browser engine by name in their prompt.")
    val browser: String = "",
    @LLMDescription("RUN: optional recipe name or rules configuration to mount onto the browser task.")
    val recipe: String = "",
    @LLMDescription("STATUS / STOP: task ID from a previous RUN.")
    val taskId: String = ""
)

/**
 * 浏览器任务单一入口工具（2026-09 合并精简，4→1）。
 *
 * 原四个工具（run_browser_task / browser_task_status / stop_browser_task / browser_info）
 * 封装为一个 `browser`，用 [BrowserTaskAction] 分流，全部委托原工具类（保留各自校验与返回格式）。
 * 架构：页面操作由专用 BrowserOperator 子代理执行（经
 * BrowserRegistry 抽象，RUN 的 browser 参数选择）与 BrowserBrain（大脑）协同工作——主代理只做编排：下命令、看状态、
 * 关任务、看运行时。无 WAIT：浏览器任务为长耗时操作，主代理不阻塞等待。
 *
 * 仅主代理注册。返回值：RUN → {taskId,status,browser}；STATUS/STOP → 任务状态/停止结果；
 * INFO → 浏览器运行时 JSON。
 */
class BrowserTool(
    private val run: RunBrowserTaskTool,
    private val status: BrowserTaskStatusTool,
    private val stop: StopBrowserTaskTool,
    private val info: BrowserInfoTool,
    private val service: BrowserTaskService
) : SimpleTool<BrowserArgs>(
    argsType = typeToken<BrowserArgs>(),
    name = ToolName.BROWSER.value,
    description = buildDescription(service)
) {
    override suspend fun execute(args: BrowserArgs): String = when (args.action) {
        BrowserTaskAction.RUN -> run.execute(RunBrowserTaskArgs(task = args.task, browser = args.browser, recipe = args.recipe))
        BrowserTaskAction.STATUS ->
            if (args.taskId.isBlank()) "Error: STATUS requires taskId (from a previous RUN)."
            else status.execute(BrowserTaskStatusArgs(taskId = args.taskId))
        BrowserTaskAction.STOP ->
            if (args.taskId.isBlank()) "Error: STOP requires taskId (from a previous RUN)."
            else stop.execute(StopBrowserTaskArgs(taskId = args.taskId))
        BrowserTaskAction.INFO -> info.execute(BrowserInfoArgs())
    }

    companion object {
        /** 动态生成工具描述：列出当前已注册浏览器。 */
        fun buildDescription(service: BrowserTaskService): String {
            val available = service.availableBrowsers()
            val default = service.defaultBrowser()
            val browserHint = if (available.isEmpty()) {
                "No browsers registered (a browser must be installed and registered before browser tasks can run)."
            } else {
                "Registered browsers: ${available.joinToString(", ")} (default: ${default ?: "first registered"})."
            }
            return "Single tool to dispatch and manage browser sub-agent tasks. The actual page operation " +
                "is done by a DEDICATED BROWSER SUB-AGENT — you never operate the page. " +
                "action=RUN(task[, browser]): dispatch a task, returns a taskId immediately, the sub-agent " +
                "runs in the background and does not block your turn. " +
                "action=STATUS(taskId): query a dispatched task (STARTED/RUNNING/COMPLETED/ERROR/STOPPED). " +
                "action=STOP(taskId): cancel a task and close its browser (cannot resume). " +
                "action=INFO: read the runtime browser state (per browser: inUse, open tabs, current URLs, " +
                "started by this session or not). Task details are viewable in the browser panel. " +
                browserHint + ". IMPORTANT: Always leave 'browser' empty to respect user's default browser setting, " +
                "unless the user explicitly requested a specific engine by name in their message."
        }
    }
}
