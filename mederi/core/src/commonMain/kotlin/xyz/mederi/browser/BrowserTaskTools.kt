package xyz.mederi.browser

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.mederi.domain.model.AIModel
import xyz.mederi.provider.domain.model.ReasoningLevel

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
        "给浏览器子 agent 的任务指令（自然语言，面向结果）：要访问哪个网站、要达到什么结果。" +
            "低层导航/点击/输入由子 agent 自行决定。示例：'去 51job 搜 Java 开发岗位，列出前 10 条职位名和公司'。"
    )
    val task: String = "",
    @LLMDescription(
        "使用的浏览器（可选）：\n" +
            "- 不填 = 用默认浏览器。\n" +
            "- 可用列表来自注册表（jcef / camoufox 等）。\n" +
            "- jcef：内置可见浏览器，用户可观看运行过程。\n" +
            "- camoufox：无头反检测，适合第三方网站的自动化操作/抓取。"
    )
    val browser: String = ""
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
    name = "run_browser_task",
    description = buildDescription(service)
) {
    override suspend fun execute(args: RunBrowserTaskArgs): String {
        if (args.task.isBlank()) return "Error: task must not be empty."
        val browser = args.browser.ifBlank { service.defaultBrowser() }
        val taskId = service.runTask(
            task = args.task,
            aiModel = aiModel,
            reasoningLevel = reasoningLevel,
            projectId = projectId,
            parentSessionId = sessionId,
            browser = browser,
            apiKeyId = apiKeyId
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
                "暂无可用浏览器（设置 browserHome 下载 Camoufox，或由 UI 注册内置 JCEF）"
            } else {
                "Available browsers: ${available.joinToString(", ")} (default: ${default ?: "first registered"})"
            }
            return "Asynchronously dispatches a browser automation task to a DEDICATED BROWSER SUB-AGENT (not " +
                "run by you). The sub-agent navigates, clicks, types, and reports back; the task argument is " +
                "its instruction. Returns immediately with a taskId — the sub-agent runs in the background and " +
                "does not block your turn. Query it with browser_task_status(taskId) or stop it with " +
                "stop_browser_task(taskId). Task details are viewable in the browser panel. " +
                "Registered browser values: jcef (built-in, visible, user can watch); camoufox (headless, " +
                "anti-detection, for automation/scraping on third-party sites). " +
                browserHint
        }
    }
}

/** browser_task_status 工具参数。 */
@Serializable
data class BrowserTaskStatusArgs(
    @LLMDescription("浏览器任务 ID（run_browser_task 返回的 taskId）。")
    val taskId: String = ""
)

/** 查询浏览器任务状态（只给状态，不给细节）。 */
class BrowserTaskStatusTool(
    private val service: BrowserTaskService
) : SimpleTool<BrowserTaskStatusArgs>(
    argsType = typeToken<BrowserTaskStatusArgs>(),
    name = "browser_task_status",
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
    @LLMDescription("要停止的浏览器任务 ID。")
    val taskId: String = ""
)

/** 停止浏览器任务。 */
class StopBrowserTaskTool(
    private val service: BrowserTaskService
) : SimpleTool<StopBrowserTaskArgs>(
    argsType = typeToken<StopBrowserTaskArgs>(),
    name = "stop_browser_task",
    description = "Stops a running browser sub-agent task and closes its browser. The task cannot be " +
        "resumed."
) {
    override suspend fun execute(args: StopBrowserTaskArgs): String {
        if (args.taskId.isBlank()) return "Error: taskId must not be empty."
        return service.stopTask(args.taskId)
    }
}

/** browser_info 工具参数（无实际字段：直接返回当前浏览器运行状态）。 */
@Serializable
data class BrowserInfoArgs(
    @LLMDescription("留空即可，此工具无需参数。")
    val unused: String = ""
)

/**
 * 浏览器运行状态查询（只读、无副作用）——与任务操作区分。
 * 返回当前所有浏览器的运行状态：JCEF / Camoufox 是否在用、开了几个 tab/实例、
 * 各在哪个页面、是否由当前会话启动。
 */
class BrowserInfoTool(
    private val service: BrowserTaskService,
    private val sessionId: String
) : SimpleTool<BrowserInfoArgs>(
    argsType = typeToken<BrowserInfoArgs>(),
    name = "browser_info",
    description = "Reads the current browser runtime status (no side effects, does NOT dispatch a task). " +
        "Returns per registered browser (jcef built-in visible / camoufox headless — BOTH may be open at " +
        "once): inUse, count of open tabs/instances, each one's current page URL + title, and whether it " +
        "was started by the CURRENT session. Distinct from browser_task_status, which queries a specific " +
        "dispatched task."
) {
    override suspend fun execute(args: BrowserInfoArgs): String = service.browserStatus(sessionId)
}
