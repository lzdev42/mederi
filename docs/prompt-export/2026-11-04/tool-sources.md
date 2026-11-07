# 代码内工具描述、参数约束与返回文本

以下是含 LLMDescription / ToolDescriptor / SimpleTool 的生产源码完整快照，包含工具执行实现，避免截断字符串拼接或漏掉工具结果内的指令。这里是源码，不宣称是某个会话序列化后的完整工具 JSON Schema。MCP 外部工具与已安装 skills 的实际内容取决于运行环境，不伪造。

## `core/src/commonMain/kotlin/xyz/mederi/browser/BrowserOperatorInternalTools.kt`

````kotlin
package xyz.mederi.browser

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import xyz.mederi.browser.drill.DrillExecutor

/**
 * 浏览器操作员 Core 内部工具集（非页面物理操作）：
 * - [DoneTool]：完成任务并汇报最终简要结果
 * - [UpdateMemoryTool]：BrowserPilot 风格的操作员记忆自管理更新
 * - [CollectInfoTool]：多网站信息收集暂存器（URL、网站名、Markdown 内容）
 * - [JudgeTool]：调用 BrowserBrain 判定页面内容
 * - [ExecuteDrillTool]：调用 DrillExecutor 执行确定性脚本
 */

@Serializable
data class DoneArgs(
    @LLMDescription("Final summary message when task is complete")
    val message: String = ""
)

class DoneTool(
    private val onDone: ((String) -> Unit)? = null
) : SimpleTool<DoneArgs>(
    argsType = typeToken<DoneArgs>(),
    name = BrowserConstants.ToolNames.DONE,
    description = "Signal that the task is complete, with a final summary message."
) {
    override suspend fun execute(args: DoneArgs): String {
        onDone?.invoke(args.message)
        return "DONE: ${args.message}"
    }
}

@Serializable
data class MemoryArgs(
    @LLMDescription("Summary of progress and remaining sub-tasks to remember for next steps")
    val memory: String = ""
)

class UpdateMemoryTool(
    private val onMemoryUpdate: (String) -> Unit
) : SimpleTool<MemoryArgs>(
    argsType = typeToken<MemoryArgs>(),
    name = BrowserConstants.ToolNames.UPDATE_MEMORY,
    description = "Store an updated summary of progress and remaining sub-tasks into task memory."
) {
    override suspend fun execute(args: MemoryArgs): String {
        onMemoryUpdate(args.memory)
        return "memory=ok: updated"
    }
}

@Serializable
data class CollectInfoArgs(
    @LLMDescription("The URL of the webpage where this info was found. If empty, the current page URL is used automatically.")
    val url: String = "",
    @LLMDescription("The site name or page title. If empty, the current page title is used automatically.")
    val site_name: String = "",
    @LLMDescription("Specific research findings, data, or notes in Markdown format. May include image links in ![alt](url) format.")
    val content: String = ""
)

class CollectInfoTool(
    private val collector: BrowserCollector,
    private val control: BrowserControl
) : SimpleTool<CollectInfoArgs>(
    argsType = typeToken<CollectInfoArgs>(),
    name = BrowserConstants.ToolNames.COLLECT_INFO,
    description = "Store a research finding, snippet, or data point (in Markdown format, optionally including image URLs) into the temporary research scratchpad for multi-site information gathering. Saved items are preserved and summarized upon task completion."
) {
    override suspend fun execute(args: CollectInfoArgs): String {
        if (args.content.isBlank()) return "collect=FAIL: content must not be blank"
        val effectiveUrl = args.url.ifBlank {
            runCatching { control.getCurrentUrl() }.getOrNull().orEmpty()
        }
        val effectiveTitle = args.site_name.ifBlank {
            runCatching { control.getTitle() }.getOrNull().orEmpty()
        }
        val item = BrowserCollectedItem(
            url = effectiveUrl,
            siteName = effectiveTitle,
            content = args.content
        )
        val count = collector.add(item)
        return "collect=ok: total $count items collected"
    }
}

@Serializable
data class JudgeArgs(
    @LLMDescription("Page content to evaluate")
    val content: String = "",
    @LLMDescription("Instruction for judging the content")
    val instruction: String = ""
)

class JudgeTool(
    private val brain: BrowserBrain?,
    private val recipe: BrowserRecipe?,
    private val collector: BrowserCollector? = null
) : SimpleTool<JudgeArgs>(
    argsType = typeToken<JudgeArgs>(),
    name = BrowserConstants.ToolNames.JUDGE,
    description = "Evaluate page content against an instruction using the browser brain, returning structured fields or a boolean verdict."
) {
    override suspend fun execute(args: JudgeArgs): String {
        val b = brain ?: return "judge=FAIL: BrowserBrain not configured"
        return try {
            val res = b.judge(
                content = args.content,
                instruction = args.instruction,
                recipeRules = recipe?.judgeRules.orEmpty(),
                collectedItems = collector?.list().orEmpty()
            )
            "judge=${if (res.success) "ok" else "FAIL"}: ${res.toCompactString()}"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "judge=FAIL: ${e.message}"
        }
    }
}

object LenientJsonStringSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientJsonString", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: String) {
        encoder.encodeString(value)
    }

    override fun deserialize(decoder: Decoder): String {
        return if (decoder is JsonDecoder) {
            val element = decoder.decodeJsonElement()
            if (element is JsonPrimitive && element.isString) {
                element.content
            } else {
                element.toString()
            }
        } else {
            decoder.decodeString()
        }
    }
}

@Serializable
data class DrillArgs(
    @Serializable(with = LenientJsonStringSerializer::class)
    @LLMDescription("Drill script JSON string or task name")
    val script: String = ""
)

class ExecuteDrillTool(
    private val drillExecutor: DrillExecutor?,
    private val brain: BrowserBrain?,
    private val recipe: BrowserRecipe?,
    private val mountedScript: String? = null
) : SimpleTool<DrillArgs>(
    argsType = typeToken<DrillArgs>(),
    name = BrowserConstants.ToolNames.DRILL,
    description = buildDescription(mountedScript)
) {
    override suspend fun execute(args: DrillArgs): String {
        val executor = drillExecutor ?: return "drill=FAIL: DrillExecutor not configured"
        val onJudgeContent: (suspend (String, String) -> BrainResult)? = brain?.let { b ->
            { c: String, i: String -> b.judge(c, i, recipe?.judgeRules.orEmpty()) }
        }
        val rawScript = args.script.takeIf { it.isNotBlank() }
            ?: mountedScript?.takeIf { it.isNotBlank() }
            ?: return "drill=FAIL: no drill script provided and none mounted"
        val scriptElement = runCatching { Json.parseToJsonElement(rawScript) }
            .getOrElse { JsonPrimitive(rawScript) }
        return try {
            val r = executor.executeScript(scriptElement, onJudgeContent)
            "drill=${if (r.success) "ok" else "FAIL"}: ${r.drill_results.joinToString(";") { it.toString() }.take(200)}"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "drill=FAIL: ${e.message}"
        }
    }

    companion object {
        /**
         * 动态生成工具描述：存在挂载脚本时，提示 LLM 可用空 script 参数执行挂载脚本。
         */
        fun buildDescription(mountedScript: String?): String = buildString {
            append("Execute a deterministic browser automation drill script (DrillScript JSON string). ")
            if (!mountedScript.isNullOrBlank()) {
                append("A drill script is MOUNTED for the current task: call this tool with an EMPTY 'script' argument to run the mounted script. ")
            }
            append("Pass a JSON drill script in 'script' to override the mounted script.")
        }
    }
}

````

## `core/src/commonMain/kotlin/xyz/mederi/browser/BrowserOperatorTools.kt`

````kotlin
package xyz.mederi.browser

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import xyz.mederi.browser.BrowserConstants
import xyz.mederi.browser.BrowserControl
import xyz.mederi.browser.bidi.OperationResult

/**
 * 通用浏览器操作工具集（基于 BrowserControl 抽象，jcef/camoufox 后端自动适配），
 * 由 BrowserOperator 直接装配。
 */

private fun OperationResult.toText(action: String): String = when (this) {
    is OperationResult.Success -> "$action=ok${if (detail.isNotBlank()) ": $detail" else ""}"
    is OperationResult.Failure -> "$action=FAIL: $reason"
    is OperationResult.Acknowledged -> "$action=ok"
}

private suspend fun safeCall(action: String, block: suspend () -> OperationResult): String =
    try {
        block().toText(action)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        "$action=FAIL: ${e.message}"
    }

@Serializable
data class NavigateArgs(
    @LLMDescription("URL to navigate to")
    val url: String = ""
)

class NavigateTool(private val control: BrowserControl) : SimpleTool<NavigateArgs>(
    argsType = typeToken<NavigateArgs>(),
    name = BrowserConstants.ToolNames.NAVIGATE,
    description = "Navigate the browser to a URL."
) {
    override suspend fun execute(args: NavigateArgs): String = safeCall(name) { control.navigate(args.url) }
}

@Serializable
data class ClickArgs(
    @LLMDescription("Element refid from a11y snapshot")
    val elementRef: String = ""
)

class ClickTool(private val control: BrowserControl) : SimpleTool<ClickArgs>(
    argsType = typeToken<ClickArgs>(),
    name = BrowserConstants.ToolNames.CLICK,
    description = "Click an element identified by its refid from the a11y snapshot."
) {
    override suspend fun execute(args: ClickArgs): String = safeCall(name) { control.click(args.elementRef) }
}

@Serializable
data class TypeArgs(
    @LLMDescription("Element refid")
    val elementRef: String = "",
    @LLMDescription("Text to type")
    val text: String = ""
)

class TypeTool(private val control: BrowserControl) : SimpleTool<TypeArgs>(
    argsType = typeToken<TypeArgs>(),
    name = BrowserConstants.ToolNames.TYPE,
    description = "Type text into an element identified by its refid."
) {
    override suspend fun execute(args: TypeArgs): String = safeCall(name) { control.type(args.elementRef, args.text) }
}

@Serializable
data class ScrollArgs(
    @LLMDescription("Optional element refid. Leave EMPTY to scroll the whole page/viewport; provide a refid only to scroll a specific scrollable element.")
    val elementRef: String = "",
    @LLMDescription("Horizontal scroll delta")
    val deltaX: Int = 0,
    @LLMDescription("Vertical scroll delta")
    val deltaY: Int = 0
)

class ScrollTool(private val control: BrowserControl) : SimpleTool<ScrollArgs>(
    argsType = typeToken<ScrollArgs>(),
    name = BrowserConstants.ToolNames.SCROLL,
    description = "Scroll the page (leave elementRef empty) or a specific element (provide its refid) by the given horizontal/vertical deltas."
) {
    override suspend fun execute(args: ScrollArgs): String =
        safeCall(name) {
            // 空 refid 走页面级 scrollPage，非空走元素级 scroll——空串永不再进入 refid 查找
            if (args.elementRef.isBlank()) control.scrollPage(args.deltaX, args.deltaY)
            else control.scroll(args.elementRef, args.deltaX, args.deltaY)
        }
}

@Serializable
data class WaitForArgs(
    @LLMDescription("CSS selector to wait for")
    val selector: String = ""
)

class WaitForTool(private val control: BrowserControl) : SimpleTool<WaitForArgs>(
    argsType = typeToken<WaitForArgs>(),
    name = BrowserConstants.ToolNames.WAIT_FOR,
    description = "Wait until the element matching the CSS selector appears or becomes visible."
) {
    override suspend fun execute(args: WaitForArgs): String = safeCall(name) { control.waitFor(args.selector) }
}

@Serializable
data class TabsArgs(
    @LLMDescription("Tab action: NEW/CLOSE/LIST/SELECT")
    val action: String = "",
    @LLMDescription("Tab id for CLOSE/SELECT")
    val tabId: String = ""
)

class TabsTool(private val control: BrowserControl) : SimpleTool<TabsArgs>(
    argsType = typeToken<TabsArgs>(),
    name = BrowserConstants.ToolNames.TABS,
    description = "Manage browser tabs: NEW opens a tab, CLOSE closes the given tab id, LIST lists all tab ids, SELECT switches to the given tab id."
) {
    override suspend fun execute(args: TabsArgs): String = safeCall(name) {
        when (args.action.uppercase()) {
            "NEW" -> control.openTab()
            "CLOSE" -> control.closeTab(args.tabId)
            "LIST" -> {
                val tabIds = control.listTabs()
                OperationResult.Success(name, detail = tabIds.joinToString(","))
            }
            "SELECT" -> control.selectTab(args.tabId)
            else -> OperationResult.Failure(name, "unknown tabAction: ${args.action}")
        }
    }
}

/**
 * 装配浏览器物理操作工具集。
 */
fun buildBrowserOperatorTools(control: BrowserControl): List<ToolBase<*, *>> = listOf(
    NavigateTool(control),
    ClickTool(control),
    TypeTool(control),
    ScrollTool(control),
    WaitForTool(control),
    TabsTool(control)
)
````

## `core/src/commonMain/kotlin/xyz/mederi/browser/BrowserTaskTools.kt`

````kotlin
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

````

## `core/src/commonMain/kotlin/xyz/mederi/office/OfficeTools.kt`

````kotlin
package xyz.mederi.office

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable
import xyz.mederi.tools.ToolName
import java.io.File

/**
 * Office 文档工具集（主代理专用）。
 *
 * 两个工具：
 * - [OfficeReadTool]：读 docx/xlsx/pptx → markdown 文本（AI 可读可改）。
 * - [OfficeWriteTool]：从 markdown 生成/覆盖 docx/xlsx（AI 改完后写回）。
 *
 * 路径校验与 FileSystemTools 共用 SandboxPaths / ExecutionPermissions；读全盘，越界写先批准。
 *
 * @param allowedDirectories 项目目录列表（绝对路径），写操作的作用域。
 */
class OfficeTools(
    private val allowedDirectories: List<String>,
    private val executionPermissions: xyz.mederi.tools.sandbox.ExecutionPermissions? = null,
) {

    @Serializable
    data class OfficeReadArgs(
        @LLMDescription("Office file path (.docx/.xlsx/.pptx), absolute or relative.")
        val path: String = ""
    )

    @Serializable
    data class OfficeWriteArgs(
        @LLMDescription("Office file path to write (.docx or .xlsx). Outside or protected writes request one-time user approval in Needs Approval mode.")
        val path: String = "",
        @LLMDescription(
            "Markdown content to write. Format:\n" +
            "- docx: #/##/### headings, - lists, |...| tables, plain paragraphs.\n" +
            "- xlsx: ## SheetName starts a sheet, |...| rows (first row = header).\n" +
            "The content fully replaces the file."
        )
        val content: String = ""
    )

    /** 读取 Office 文档，返回 markdown 文本。 */
    inner class OfficeReadTool : SimpleTool<OfficeReadArgs>(
        argsType = typeToken<OfficeReadArgs>(),
        name = ToolName.OFFICE_READ.value,
        description = "Read an Office document (.docx/.xlsx/.pptx) and convert it to markdown text."
    ) {
        override suspend fun execute(args: OfficeReadArgs): String {
            if (args.path.isBlank()) return "Error: path must not be empty."
            return try {
                val file = resolveForRead(args.path)
                if (!OfficeConverter.isSupported(file.absolutePath)) {
                    return "Error: unsupported format. Only .docx/.xlsx/.pptx are supported."
                }
                val markdown = OfficeConverter.toMarkdown(file.absolutePath)
                if (markdown.isBlank()) "(empty document)" else markdown
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                "Error reading office file: ${e.message}"
            }
        }
    }

    /** 从 markdown 内容生成/覆盖 Office 文档。 */
    inner class OfficeWriteTool : SimpleTool<OfficeWriteArgs>(
        argsType = typeToken<OfficeWriteArgs>(),
        name = ToolName.OFFICE_WRITE.value,
        description = "Generate or overwrite an Office document (.docx/.xlsx) from markdown content. docx: #/##/### headings, - lists, | tables, paragraphs; xlsx: ## sheet names, | rows. The content fully replaces the file; outside or protected writes request one-time user approval in Needs Approval mode."
    ) {
        override suspend fun execute(args: OfficeWriteArgs): String {
            if (args.path.isBlank()) return "Error: path must not be empty."
            if (args.content.isBlank()) return "Error: content must not be empty."
            return try {
                val file = resolvePath(args.path).canonicalFile
                val ext = file.name.substringAfterLast('.', "").lowercase()
                if (ext !in listOf("docx", "xlsx")) return "Error: unsupported format for writing: .$ext (only .docx/.xlsx)"
                val grant = executionPermissions?.authorizeWrite("office_write", listOf(file.toPath()))
                if (grant == null) require(xyz.mederi.tools.sandbox.SandboxPaths(allowedDirectories,
                    emptyList()).allowsWrite(file.toPath())) {
                    "Writes outside sandbox require execution approval."
                }
                val conflict = xyz.mederi.tools.FileWriteRegistry.tryAcquire(listOf(file))
                if (conflict != null) return "Error: file is being modified by another tool: $file"
                try {
                    grant?.let { executionPermissions!!.checkGrant(it) }
                    if (file.exists()) {
                        val links = runCatching { java.nio.file.Files.getAttribute(file.toPath(), "unix:nlink") as Number }.getOrNull()
                        require(links == null || links.toInt() <= 1) { "Refusing to overwrite a hard-linked file: $file" }
                    }
                    file.parentFile?.mkdirs()
                    when (ext) {
                        "docx" -> OfficeConverter.writeDocx(file.absolutePath, args.content)
                        "xlsx" -> OfficeConverter.writeXlsx(file.absolutePath, args.content)
                    }
                } finally {
                    xyz.mederi.tools.FileWriteRegistry.release(listOf(file))
                }
                "Wrote ${file.name} (${args.content.length} chars markdown → $ext)"
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                "Error writing office file: ${e.message}"
            }
        }
    }

    // ==================== 路径解析（与 FileSystemTools 同逻辑） ====================

    private suspend fun resolveForRead(rawPath: String): File {
        val file = resolvePath(rawPath).canonicalFile
        executionPermissions?.checkRead(file.toPath())
        if (!file.exists()) throw IllegalArgumentException("Path does not exist: $rawPath")
        if (!file.isFile) throw IllegalArgumentException("Path is not a file: $rawPath")
        return file
    }

    private fun resolvePath(rawPath: String): File {
        val file = File(rawPath)
        if (file.isAbsolute) return file
        // 相对路径：在允许目录下查找
        for (dir in allowedDirectories) {
            val candidate = File(dir, rawPath)
            if (candidate.exists()) return candidate
        }
        // 都不存在时，相对于第一个允许目录
        return File(allowedDirectories.first(), rawPath)
    }
}

````

## `core/src/commonMain/kotlin/xyz/mederi/tools/AgentTools.kt`

````kotlin
package xyz.mederi.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import xyz.mederi.api.exception.MederiNotFoundException
import xyz.mederi.domain.model.EventType
import xyz.mederi.domain.model.MederiEvent
import xyz.mederi.domain.model.Message
import xyz.mederi.domain.model.MessageRole
import xyz.mederi.domain.model.TodoItem
import xyz.mederi.domain.model.TodoStatus
import xyz.mederi.domain.model.encodeTodos
import xyz.mederi.infrastructure.koog.aiViewContextUsedTokens
import xyz.mederi.skills.SkillManager
import xyz.mederi.store.HistoryStore
import xyz.mederi.store.SessionStore
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Agent 自管理工具集。
 *
 * 包含轻量 todo 跟踪、上下文余量查询、开新上下文窗口、当前时间、ask_user。
 * 这些工具不涉及文件系统操作，不需要目录限制。
 *
 * @param sessionId 当前 Session ID。
 * @param historyStore 对话历史存储（get_context_remaining 读取、new_context_window 清空）。
 * @param sessionStore 会话存储（update_todo 把 todo 列表持久化到 sessions.todos 列——唯一真理源）；null 时 todo 工具不可用。
 * @param eventBus 事件总线（update_todo 发 TODO_UPDATED；ask_user 发 QUESTION_REQUESTED）。
 * @param modelContextWindow 模型上下文窗口大小（token 数），用于计算剩余量。
 * @param newContextWindowFlag 开新上下文窗口请求标志（execute 只置标志，TurnExecutor 在 agent.run 结束后消费）。
 * @param questionRequester 问题请求器（ask_user 工具需要）。
 * @param skills Skill 管理器（load_skill/unload_skill 工具需要）；null 时 load_skill/unload_skill 工具不可用。
 */
class AgentTools(
    private val sessionId: String,
    private val historyStore: HistoryStore,
    private val sessionStore: SessionStore?,
    private val eventBus: MutableSharedFlow<MederiEvent>,
    private val modelContextWindow: Int?,
    private val newContextWindowFlag: AtomicBoolean,
    private val questionRequester: xyz.mederi.question.QuestionRequester? = null,
    private val skills: SkillManager? = null
) {

    // ==================== update_todo ====================

    @Serializable
    data class TodoItemArg(
        @LLMDescription("Todo content: one concrete, actionable step.")
        val content: String,
        @LLMDescription("Status: pending, in_progress, completed, or cancelled.")
        val status: TodoStatus
    )

    @Serializable
    data class UpdateTodoArgs(
        @LLMDescription("Optional one-line explanation for this todo update.")
        val explanation: String? = null,
        @LLMDescription("The FULL todo list. One call REPLACES the entire list. An empty list clears it.")
        val todos: List<TodoItemArg>
    )

    inner class UpdateTodoTool : SimpleTool<UpdateTodoArgs>(
        argsType = typeToken<UpdateTodoArgs>(),
        name = ToolName.UPDATE_TODO.value,
        description = "Replace the entire current todo list. One call sets the whole list (passing an empty " +
            "list clears it); at most one item may be in_progress."
    ) {
        override suspend fun execute(args: UpdateTodoArgs): String {
            if (sessionStore == null) {
                return "Error: todo persistence is not available in this context."
            }
            // 聚合校验：一轮列出全部问题（逐条报错实测爬不完）
            val errors = mutableListOf<String>()
            args.todos.forEachIndexed { i, item ->
                if (item.content.isBlank()) errors.add("Todo $i: content must not be blank.")
                if (item.status == TodoStatus.FAILED)
                    errors.add("Todo $i: status 'failed' is reserved for plan verification results — " +
                        "use pending / in_progress / completed / cancelled.")
            }
            val inProgressCount = args.todos.count { it.status == TodoStatus.IN_PROGRESS }
            if (inProgressCount > 1)
                errors.add("At most ONE todo may be in_progress (got $inProgressCount).")
            if (errors.isNotEmpty())
                return "Error: todo validation failed — fix ALL of the following, then retry once:\n" +
                    errors.mapIndexed { i, e -> "${i + 1}. $e" }.joinToString("\n")

            // 唯一真理源落库；事件与提示词挂载都只是这份持久化状态的投影
            val items = args.todos.map { TodoItem(content = it.content, status = it.status) }
            sessionStore.updateTodos(sessionId, items)

            eventBus.emit(
                MederiEvent(
                    type = EventType.TODO_UPDATED,
                    sessionId = sessionId,
                    payload = buildMap {
                        put("todos", items.encodeTodos())
                        args.explanation?.takeIf { it.isNotBlank() }?.let { put("explanation", it) }
                    },
                    timestamp = Instant.now().toString()
                )
            )

            return if (items.isEmpty()) {
                "Todo list cleared."
            } else {
                val done = items.count { it.status == TodoStatus.COMPLETED }
                "Todo list updated (${items.size} items, $done completed)."
            }
        }
    }

    // ==================== load_skill / unload_skill ====================

    @Serializable
    data class SkillNameArgs(
        @LLMDescription("Skill name (the directory name under skills root, = SkillInfo.name).")
        val name: String
    )

    inner class LoadSkillTool : SimpleTool<SkillNameArgs>(
        argsType = typeToken<SkillNameArgs>(),
        name = ToolName.LOAD_SKILL.value,
        description = "Activate an installed skill: its full SKILL.md workflow is mounted into your " +
            "system prompt under '# Active Skill Workflows' on the next turn (NOT into your " +
            "conversation history). Use this when the task matches a skill's trigger description. " +
            "The skill stays active for the rest of the session until unload_skill. Idempotent " +
            "(activating an already-active skill is a no-op)."
    ) {
        override suspend fun execute(args: SkillNameArgs): String {
            if (sessionStore == null) {
                return "Error: session persistence is not available in this context."
            }
            if (skills == null) {
                return "Error: skill manager is not available in this context."
            }
            val name = args.name.trim()
            // 校验 skill 存在
            val allSkills = runCatching { skills.list() }.getOrElse {
                return "Error: failed to list skills: ${it.message}"
            }
            val info = allSkills.find { it.name == name }
                ?: return "Error: skill not found: '$name'. Available: ${allSkills.map { it.name }.sorted()}"
            // 读当前激活列表
            val session = sessionStore.get(sessionId)
                ?: return "Error: session not found: $sessionId"
            val current = session.activeSkills.toMutableList()
            if (name in current) {
                return "Skill '$name' is already active. Active skills: $current"
            }
            current.add(name)
            sessionStore.updateActiveSkills(sessionId, current)
            eventBus.emit(
                MederiEvent(
                    type = EventType.SKILL_ACTIVATED,
                    sessionId = sessionId,
                    payload = mapOf(
                        "skillName" to name,
                        "activeSkills" to kotlinx.serialization.json.Json.encodeToString(current)
                    ),
                    timestamp = java.time.Instant.now().toString()
                )
            )
            return "Skill '${info.name}' activated. Its full workflow will be in your system prompt " +
                "under '# Active Skill Workflows' on the next turn. Active skills: $current"
        }
    }

    inner class UnloadSkillTool : SimpleTool<SkillNameArgs>(
        argsType = typeToken<SkillNameArgs>(),
        name = ToolName.UNLOAD_SKILL.value,
        description = "Deactivate a previously activated skill: its workflow is removed from your " +
            "system prompt on the next turn. Idempotent (unloading a non-active skill is a no-op)."
    ) {
        override suspend fun execute(args: SkillNameArgs): String {
            if (sessionStore == null) {
                return "Error: session persistence is not available in this context."
            }
            val name = args.name.trim()
            val session = sessionStore.get(sessionId)
                ?: return "Error: session not found: $sessionId"
            val current = session.activeSkills.toMutableList()
            if (name !in current) {
                return "Skill '$name' is not active. Active skills: $current"
            }
            current.remove(name)
            sessionStore.updateActiveSkills(sessionId, current)
            eventBus.emit(
                MederiEvent(
                    type = EventType.SKILL_DEACTIVATED,
                    sessionId = sessionId,
                    payload = mapOf(
                        "skillName" to name,
                        "activeSkills" to kotlinx.serialization.json.Json.encodeToString(current)
                    ),
                    timestamp = java.time.Instant.now().toString()
                )
            )
            return "Skill '$name' deactivated. Active skills: $current"
        }
    }

    // ==================== get_context_remaining ====================

    @Serializable
    data class GetContextRemainingArgs(
        @LLMDescription("Leave empty; reserved for future use.")
        val placeholder: String = ""
    )

    inner class GetContextRemainingTool : SimpleTool<GetContextRemainingArgs>(
        argsType = typeToken<GetContextRemainingArgs>(),
        name = ToolName.GET_CONTEXT_REMAINING.value,
        description = "Get the remaining tokens in the current context window."
    ) {
        override suspend fun execute(args: GetContextRemainingArgs): String {
            // 与 AI 实际收到的视图一致（压缩判定同源唯一真理源：AI 视图窗口，
            // 压缩后走估算，否则用 API 报告的真实 inputTokens）
            val estimatedTokens = aiViewContextUsedTokens(
                historyStore,
                sessionId,
                sessionStore?.get(sessionId)?.aiModel?.supportsImages ?: true
            )
            val remaining = modelContextWindow?.let { limit ->
                (limit - estimatedTokens).coerceAtLeast(0)
            }
            return if (remaining != null) {
                "You have $remaining tokens left in this context window."
            } else {
                "You have unknown tokens left in this context window."
            }
        }
    }

    // ==================== new_context_window ====================

    @Serializable
    data class NewContextWindowArgs(
        @LLMDescription("Leave empty; reserved for future use.")
        val placeholder: String = ""
    )

    inner class NewContextWindowTool : SimpleTool<NewContextWindowArgs>(
        argsType = typeToken<NewContextWindowArgs>(),
        name = ToolName.NEW_CONTEXT.value,
        description = "Start a new context window. Does not clear, reset, or otherwise affect environment state."
    ) {
        override suspend fun execute(args: NewContextWindowArgs): String {
            // 只设置请求标志，TurnExecutor 在 agent.run() 结束后消费
            newContextWindowFlag.set(true)
            return "A new context window will start without summarizing conversation history."
        }
    }

    // ==================== ask_user ====================

    @Serializable
    data class AskUserAnswer(
        @LLMDescription("Question ID, matching an id from the ask_user call.")
        val questionId: String,
        @LLMDescription("Answers selected by the user; supports multi-select.")
        val answers: List<String>
    )

    @Serializable
    data class AskUserResult(
        @LLMDescription("All of the answers, one entry per question asked.")
        val answers: List<AskUserAnswer>
    )

    @Serializable
    data class AskUserQuestionArg(
        @LLMDescription("Question ID, unique.")
        val id: String,
        @LLMDescription("Question text.")
        val prompt: String,
        @LLMDescription("List of options. Empty = free-text input.")
        val options: List<String> = emptyList(),
        @LLMDescription("true = allow selecting multiple answers.")
        val multiSelect: Boolean = false
    )

    @Serializable
    data class AskUserArgs(
        @LLMDescription("Questions to ask the user. May contain multiple.")
        val questions: List<AskUserQuestionArg>
    )

    inner class AskUserTool : SimpleTool<AskUserArgs>(
        argsType = typeToken<AskUserArgs>(),
        name = ToolName.ASK_USER.value,
        description = "Ask the user one or more questions. Use this when you need clarification, " +
            "confirmation, or a decision from the user. Supports multiple questions, " +
            "multiple choice, and multi-select."
    ) {
        override suspend fun execute(args: AskUserArgs): String {
            if (questionRequester == null) {
                return "Error: question requesting is not available in this context."
            }

            val questions = args.questions.map { q ->
                xyz.mederi.question.Question(
                    id = q.id,
                    prompt = q.prompt,
                    options = q.options,
                    multiSelect = q.multiSelect
                )
            }

            val result = questionRequester.request(questions)

            return if (result.rejected) {
                "User declined to answer the questions."
            } else {
                val askUserResult = AskUserResult(
                    answers = result.answers.mapIndexed { i, ans ->
                        AskUserAnswer(questionId = args.questions[i].id, answers = ans)
                    }
                )
                Json.encodeToString(AskUserResult.serializer(), askUserResult)
            }
        }
    }
}

````

## `core/src/commonMain/kotlin/xyz/mederi/tools/FileSystemTools.kt`

````kotlin
package xyz.mederi.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.validate
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable
import kotlinx.coroutines.ensureActive
import xyz.mederi.tools.diff.TurnDiffTracker
import xyz.mederi.tools.patch.PatchParseException
import xyz.mederi.tools.patch.PatchParser
import xyz.mederi.tools.patch.ApplyPatchProtocol

import java.io.File
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.absolute
import kotlin.io.path.relativeTo
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * AGENTS.md 子树懒发现回调。
 *
 * 文件工具（read_file / list_directory）成功访问某路径后调用 [onAccessed]；
 * 返回本次**新发现**（调用方 registry 未登记过）的 AGENTS.md，无新发现返回 null。
 * 由 TurnExecutor 组合 AgentsFileLoader + 会话级 registry 提供。
 */
fun interface AgentsSubtreeDiscovery {
    suspend fun onAccessed(accessedPath: String): xyz.mederi.project.AgentsFileLoader.AgentsFile?
}

/**
 * 文件系统工具集。
 *
 * 所有文件操作都限制在 [allowedDirectories] 范围内。
 * 路径校验：解析后的绝对路径必须在某个允许目录内，否则拒绝操作。
 *
 * @param allowedDirectories 项目目录列表（绝对路径），工具操作的作用域。
 * @param diffTracker 变更追踪器。
 * @param agentsDiscovery AGENTS.md 子树懒发现回调；read/list 成功后触发，新发现的内容追加到返回文本。
 */
class FileSystemTools(
    private val allowedDirectories: List<String>,
    private val diffTracker: TurnDiffTracker? = null,
    private val agentsDiscovery: AgentsSubtreeDiscovery? = null,
    /**
     * 文件写入回调：write/edit/apply_patch 成功写入后上报路径。
     * 执行器子代理经 SubagentRunnerImpl 挂回调收集 touched files（flush 到 Subtask.executorTouchedFiles）。
     */
    private val onFileTouched: ((String) -> Unit)? = null,
    private val executionPermissions: xyz.mederi.tools.sandbox.ExecutionPermissions? = null,
    private val extraWritablePaths: List<String> = emptyList(),
) {

    // ==================== 参数定义 ====================

    @Serializable
    data class ReadFileArgs(
        @LLMDescription("File path, absolute or relative to the project root.")
        val path: String = "",
        @LLMDescription("Line number to start reading from (1-based). Default 1.")
        val offset: Int = 1,
        @LLMDescription("Max lines to return; 0 = no limit.")
        @kotlinx.serialization.SerialName("max_lines")
        val maxLines: Int = 2000
    )

    @Serializable
    data class WriteFileArgs(
        @LLMDescription("File path, relative to the project root (e.g. src/Main.kt). Absolute paths are accepted; outside or protected writes request one-time user approval in Needs Approval mode.")
        val path: String = "",
        @LLMDescription("Full content to write to the file.")
        val content: String = ""
    )

    @Serializable
    data class EditFileArgs(
        @LLMDescription("File path, relative to the project root (e.g. src/Main.kt). Absolute paths are accepted; outside or protected writes request one-time user approval in Needs Approval mode.")
        val path: String = "",
        @LLMDescription("Exact original text to replace. Must match the file exactly and be UNIQUE in it — multiple matches are rejected; provide more surrounding context.")
        val original: String = "",
        @LLMDescription("Replacement text.")
        val replacement: String = "",
        @LLMDescription("true = replace every occurrence of original; false (default) = require uniqueness and replace once.")
        @kotlinx.serialization.SerialName("replace_all")
        val replaceAll: Boolean = false
    )

    @Serializable
    data class ListDirectoryArgs(
        @LLMDescription("Directory path, absolute or relative to the project root. Empty = project root.")
        val path: String = ""
    )

    @Serializable
    data class ApplyPatchArgs(
        @LLMDescription(ApplyPatchProtocol.PATCH_DESCRIPTION)
        val patch: String
    )

    // ==================== 工具实现 ====================

    inner class ReadFileTool : SimpleTool<ReadFileArgs>(
        argsType = typeToken<ReadFileArgs>(),
        name = ToolName.READ_FILE.value,
        description = "Read a local file and return its text content, starting at offset (1-based) up to max_lines lines. Each line is prefixed by its 1-based line number as `<line>: <content>`. The prefix is for reference and is not part of file content. The result reports the returned line range and the next offset when more remain."
    ) {
        override suspend fun execute(args: ReadFileArgs): String {
            val file = resolveForRead(args.path, mustExist = true, mustBeFile = true)
            val content = file.readText()
            // 空文件特判："" 的 lines() 在 Kotlin 返回 [" "]（非空），会误报 total=1
            val allLines = if (content.isEmpty()) emptyList() else content.lines()
            val total = allLines.size

            // offset 1-based（对齐 opencode `page.offset || 1`，0 或负都当 1）。
            val effectiveOffset = if (args.offset <= 0) 1 else args.offset
            val limit = if (args.maxLines <= 0) Int.MAX_VALUE else args.maxLines
            val start = (effectiveOffset - 1).coerceAtMost(total)
            val end = (start.toLong() + limit).coerceAtMost(total.toLong()).toInt()
            val slice = allLines.subList(start, end)

            val hasMore = end < total
            // header 照抄 opencode read.ts toModelContent 文案（path 用 displayPath）。
            val header = when {
                slice.isEmpty() && total == 0 -> "Read file ${displayPath(file)}, 0 lines"
                slice.isEmpty() -> "Offset $effectiveOffset is out of range for this file ($total lines)"
                else -> "Read file ${displayPath(file)}, lines $effectiveOffset-${effectiveOffset + slice.size - 1}"
            }
            // 行体每行加 1-based 行号前缀 `${lineNumber}: ${content}`（照抄 opencode）。
            val body = if (slice.isEmpty()) "" else "\n" + slice.mapIndexed { i, line ->
                "${effectiveOffset + i}: $line"
            }.joinToString("\n")
            // truncated 提示照抄 opencode：next = effectiveOffset + slice.size（1-based 续读）。
            val truncatedNote = if (hasMore) {
                "\n[Output truncated. Continue reading with offset: ${effectiveOffset + slice.size}]"
            } else ""

            val result = header + body + truncatedNote
            return appendDiscoveredAgents(result, file)
        }
    }

    inner class WriteFileTool : SimpleTool<WriteFileArgs>(
        argsType = typeToken<WriteFileArgs>(),
        name = ToolName.WRITE_FILE.value,
        description = "Write content to a file, creating it (and parent directories) if absent, overwriting it if present."
    ) {
        override suspend fun execute(args: WriteFileArgs): String {
            val (file, grant) = resolveForWrite(args.path, mustExist = false, mustBeFile = true, tool = "write_file")
            val conflict = FileWriteRegistry.tryAcquire(listOf(file))
            if (conflict != null) {
                return "Error: ${displayPath(file)} is currently being modified by another concurrent tool call. " +
                    "Retry after the other edit completes, or send this write in a separate message."
            }
            try {
                grant?.let { executionPermissions!!.checkGrant(it) }
                file.parentFile?.mkdirs()
                val oldContent = file.takeIf { it.exists() }?.readText()
                safeWriteText(file, args.content)
                diffTracker?.recordWrite(displayPath(file), oldContent, args.content)
                onFileTouched?.invoke(displayPath(file))
                return "Written ${args.content.length} chars to ${displayPath(file)}"
            } finally {
                FileWriteRegistry.release(listOf(file))
            }
        }
    }

    inner class EditFileTool : SimpleTool<EditFileArgs>(
        argsType = typeToken<EditFileArgs>(),
        name = ToolName.EDIT_FILE.value,
        description = "Replace original text in an existing file. original must be UNIQUE in the file — multiple matches are rejected (provide more context). replace_all=true replaces every occurrence."
    ) {
        override suspend fun execute(args: EditFileArgs): String {
            validate(args.original.isNotEmpty()) { "original must not be empty" }
            val (file, grant) = resolveForWrite(args.path, mustExist = true, mustBeFile = true, tool = "edit_file")
            val conflict = FileWriteRegistry.tryAcquire(listOf(file))
            if (conflict != null) {
                return "Error: ${displayPath(file)} is currently being modified by another concurrent tool call. " +
                    "Retry after the other edit completes, or send this edit in a separate message."
            }
            try {
                grant?.let { executionPermissions!!.checkGrant(it) }
                // no-op 拦截（照抄 opencode：oldString == newString 直接拒绝，不写入）
                if (args.original == args.replacement) {
                    return "Error: original and replacement are identical — no changes to apply."
                }

                val content = file.readText()

                // CRLF 归一化（照抄 opencode edit.ts）：检测文件原行尾，把 original/replacement
                // 的行尾统一成文件原风格，避免 LF/CRLF 不一致导致匹配失败。
                val crlf = "\r\n"
                val ending = if (content.contains(crlf)) crlf else "\n"
                val oldString = args.original.replace(crlf, "\n").replace("\n", ending)
                val newString = args.replacement.replace(crlf, "\n").replace("\n", ending)

                // 三级匹配降级（照抄 opencode edit.ts）：
                // 1) exact 精确匹配；2) exact 0 命中 → unicode 归一化后精确匹配；
                // 3) 仍 0 命中 → 行级容差匹配（逐行 trimEnd + 归一化比较）。
                val exact = findOccurrences(content, oldString)
                val unicode = if (exact.isNotEmpty()) emptyList() else
                    findOccurrences(normalizeForMatch(content), normalizeForMatch(oldString))
                val lineLevel = if (exact.isNotEmpty() || unicode.isNotEmpty()) emptyList() else
                    findLineOccurrences(content, oldString)
                val matches = when {
                    exact.isNotEmpty() -> exact
                    unicode.isNotEmpty() -> unicode
                    else -> lineLevel
                }
                val matchType = when {
                    exact.isNotEmpty() -> "exact"
                    unicode.isNotEmpty() -> "fuzzy"
                    lineLevel.isNotEmpty() -> "line-level"
                    else -> null
                }

                val replacements = matches.size
                if (replacements == 0) {
                    // 三级全败（照抄 opencode 通用错误文案）
                    return "Could not find oldString in ${displayPath(file)}. It must match exactly, " +
                        "including whitespace and indentation."
                }
                if (replacements > 1 && !args.replaceAll) {
                    return "Found $replacements matches for oldString in ${displayPath(file)}, but expected " +
                        "exactly one. Add more surrounding context to make original unique, or set replace_all=true."
                }

                // 反向 splice 替换（照抄 opencode：从后往前替换，保证后续 match 的 offset 不失效）
                val toApply = if (args.replaceAll) matches else matches.take(1)
                val updated = StringBuilder(content)
                for ((start, end) in toApply.sortedByDescending { it.first }) {
                    updated.replace(start, end, newString)
                }

                safeWriteText(file, updated.toString())
                diffTracker?.recordWrite(displayPath(file), content, updated.toString())
                onFileTouched?.invoke(displayPath(file))
                val replaced = if (args.replaceAll) replacements else 1
                val fallbackNote = if (matchType != null && matchType != "exact") " (matched via $matchType fallback)" else ""
                return "Edited ${displayPath(file)}: replaced $replaced occurrence(s)" + fallbackNote
            } finally {
                FileWriteRegistry.release(listOf(file))
            }
        }
    }

    /**
     * unicode 归一化（照抄 opencode edit.ts normalizeForMatch）：智能引号→直引号、
     * 各类破折号→连字符、各种 unicode 空格→普通空格。用于精确匹配失败后的容差匹配。
     */
    private fun normalizeForMatch(value: String): String = value
        .replace("‘", "'").replace("’", "'").replace("‚", "'").replace("‛", "'")
        .replace("“", "\"").replace("”", "\"").replace("„", "\"").replace("‟", "\"")
        .replace("‐", "-").replace("‑", "-").replace("‒", "-").replace("–", "-")
        .replace("—", "-").replace("―", "-").replace("−", "-")
        .replace("\u00A0", " ").replace("\u2002", " ").replace("\u2003", " ")
        .replace("\u2004", " ").replace("\u2005", " ").replace("\u2006", " ")
        .replace("\u2007", " ").replace("\u2008", " ").replace("\u2009", " ")
        .replace("\u200A", " ").replace("\u202F", " ").replace("\u205F", " ")
        .replace("\u3000", " ")

    /**
     * 非重叠精确出现次数与位置（照抄 opencode edit.ts findOccurrences）。
     * 返回 (start, end) 列表，end = start + search.length。
     */
    private fun findOccurrences(content: String, search: String): List<Pair<Int, Int>> {
        if (search.isEmpty()) return emptyList()
        val result = mutableListOf<Pair<Int, Int>>()
        var offset = 0
        while (true) {
            val idx = content.indexOf(search, offset)
            if (idx < 0) break
            result.add(idx to idx + search.length)
            offset = idx + search.length
        }
        return result
    }

    /**
     * 行级容差匹配（照抄 opencode edit.ts findLineOccurrences）：
     * 按 \n 把 search 切成期望行，逐行比较（trimEnd + normalizeForMatch），
     * 命中的行区间合并重叠后返回。trailing newline 对齐（search 末尾 \n 要求最后一行有换行）。
     */
    private fun findLineOccurrences(content: String, search: String): List<Pair<Int, Int>> {
        val trailingNewline = search.endsWith("\n")
        val expected = search.split("\n").let { if (trailingNewline && it.last().isEmpty()) it.dropLast(1) else it }
        if (expected.isEmpty()) return emptyList()

        // 按 [^\n]*(?:\n|$) 切行，记录每行的 start/end/text/contentEnd/newline
        data class Line(val start: Int, val end: Int, val text: String, val contentEnd: Int, val newline: Boolean)
        val lines = mutableListOf<Line>()
        val regex = Regex("[^\\n]*(?:\\n|$)")
        for (m in regex.findAll(content)) {
            val raw = m.value
            if (raw.isEmpty()) continue
            val newline = raw.endsWith("\n")
            val text = if (newline) raw.dropLast(1) else raw
            val contentEnd = m.range.first + text.length - (if (text.endsWith("\r")) 1 else 0)
            lines.add(Line(m.range.first, m.range.last + 1, text, contentEnd, newline))
        }

        val candidates = mutableListOf<Pair<Int, Int>>()
        for (i in lines.indices) {
            val actual = lines.subList(i, minOf(i + expected.size, lines.size))
            if (actual.size != expected.size) continue
            val allMatch = actual.indices.all { j ->
                normalizeForMatch(actual[j].text.trimEnd()) == normalizeForMatch(expected[j].trimEnd())
            }
            if (!allMatch) continue
            val last = actual.last()
            if (trailingNewline && !last.newline) continue
            candidates.add(lines[i].start to if (trailingNewline) last.end else last.contentEnd)
        }
        // 合并重叠区间（照抄 opencode reduce）
        val merged = mutableListOf<Pair<Int, Int>>()
        for (c in candidates) {
            if (merged.any { it.second > c.first && it.first < c.second }) continue
            merged.add(c)
        }
        return merged
    }

    inner class ListDirectoryTool : SimpleTool<ListDirectoryArgs>(
        argsType = typeToken<ListDirectoryArgs>(),
        name = ToolName.LIST_DIRECTORY.value,
        description = "List files and subdirectories in a directory. Empty path = project root."
    ) {
        override suspend fun execute(args: ListDirectoryArgs): String {
            val dirPath = if (args.path.isBlank()) allowedDirectories.first() else args.path
            val dir = resolveForRead(dirPath, mustExist = true, mustBeFile = false)
            val readPolicy = executionPermissions?.readPolicy()
            val entries = dir.listFiles()?.filter { readPolicy?.allowsRead(it.canonicalFile.toPath()) != false }
                ?.sortedBy { it.name } ?: return "Directory is empty or inaccessible"
            if (entries.isEmpty()) return "Directory is empty"
            val listing = entries.joinToString("\n") { entry ->
                val prefix = if (entry.isDirectory) "[DIR]  " else "[FILE] "
                "$prefix${entry.name}${if (entry.isFile && entry.length() > 0) " (${entry.length()} bytes)" else ""}"
            }
            return appendDiscoveredAgents(listing, dir)
        }
    }

    /** 一份补丁整体预检并加锁，落盘失败尝试恢复；执行细节见 [PatchFileTransaction]。 */
    inner class ApplyPatchTool : SimpleTool<ApplyPatchArgs>(
        argsType = typeToken<ApplyPatchArgs>(),
        name = ToolName.APPLY_PATCH.value,
        description = ApplyPatchProtocol.TOOL_DESCRIPTION,
    ) {
        override suspend fun execute(args: ApplyPatchArgs): String {
            val hunks = try {
                PatchParser.parse(args.patch)
            } catch (e: PatchParseException) {
                return "Error: apply_patch verification failed: ${e.message}. No files were modified."
            }
            return try {
                val targetPaths = hunks.flatMap { hunk -> listOfNotNull(hunk.path, (hunk as? xyz.mederi.tools.patch.PatchHunk.UpdateFile)?.movePath) }
                    .map { raw -> executionPermissions?.resolvePath(raw) ?: resolvePath(raw).toFile().canonicalFile.toPath() }
                val grant = executionPermissions?.authorizeWrite("apply_patch", targetPaths)
                if (grant == null) {
                    val policy = xyz.mederi.tools.sandbox.SandboxPaths(allowedDirectories, extraWritablePaths)
                    require(targetPaths.all(policy::allowsWrite)) { "Patch writes outside sandbox require execution approval." }
                }
                val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
                grant?.let { executionPermissions!!.checkGrant(it) }
                PatchFileTransaction(allowedDirectories, reportChanges = { changes ->
                    diffTracker?.trackPatch(changes)
                    // 上报绝对路径，move 的源与目标都在 changes 中；不能只记录目标。
                    changes.forEach { onFileTouched?.invoke(it.path) }
                }, extraWritablePaths = targetPaths.mapNotNull { path ->
                    generateSequence(path.parent) { it.parent }.firstOrNull { java.nio.file.Files.isDirectory(it) }?.toString()
                }.distinct(), beforeMutation = { path, rollback ->
                    if (!rollback) {
                        job?.ensureActive()
                        require(path.toFile().canonicalFile.toPath() in targetPaths) {
                            "Patch target changed after authorization: $path"
                        }
                        grant?.let { executionPermissions!!.validateGrant(it) }
                    }
                }).apply(hunks)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { "Error: apply_patch verification failed: ${failure.message}. No files were modified." }
        }
    }

    // ==================== 路径安全校验 ====================

    /**
     * AGENTS.md 子树懒发现：read/list 成功访问后，若访问路径上（项目目录内、向上）
     * 有本会话未注入过的 AGENTS.md，将其内容追加到工具返回文本末尾。
     * 访问对象自身就是 AGENTS.md 时跳过（内容已在返回文本里，追加即重复）。
     */
    private suspend fun appendDiscoveredAgents(result: String, accessed: File): String {
        if (accessed.isFile && accessed.name.equals(xyz.mederi.project.AgentsFileLoader.FILE_NAME, ignoreCase = true)) {
            return result
        }
        val discovered = agentsDiscovery?.onAccessed(
            accessed.toPath().absolute().normalize().toString()
        ) ?: return result
        if (executionPermissions?.readPolicy()?.allowsRead(File(discovered.path).canonicalFile.toPath()) == false) return result
        return result + "\n\n--- AGENTS.md (${discovered.relativePath}) ---\n${discovered.content}"
    }

    /**
     * 读路径解析：**全盘可读**，不做目录包含校验。
     * 相对路径仍优先在项目目录下解析（找不到时相对主目录）。
     *
     * @param rawPath 用户输入的路径（绝对或相对）。
     * @param mustExist 是否必须已存在。
     * @param mustBeFile true=必须是文件，false=必须是目录。
     * @return 解析后的 File。
     * @throws IllegalArgumentException 存在性/类型校验失败。
     */
    private suspend fun resolveForRead(rawPath: String, mustExist: Boolean, mustBeFile: Boolean): File {
        val absolute = resolvePath(rawPath).absolute()
        executionPermissions?.checkRead(absolute.toFile().canonicalFile.toPath())
        return validateFile(absolute.toFile(), rawPath, mustExist, mustBeFile)
    }

    /**
     * 写路径解析：**必须在项目目录内**（containment 白名单，代码强制不变量）。
     *
     * @throws IllegalArgumentException 路径不在允许目录内或校验失败。
     */
    private suspend fun resolveForWrite(rawPath: String, mustExist: Boolean, mustBeFile: Boolean, tool: String): Pair<File, xyz.mederi.tools.sandbox.ExecutionPermissions.Grant?> {
        val resolved = resolvePath(rawPath)
        val absolute = resolved.toFile().canonicalFile.toPath()
        val grant = executionPermissions?.authorizeWrite(tool, listOf(absolute))
        if (grant == null && !xyz.mederi.tools.sandbox.SandboxPaths(allowedDirectories,
                extraWritablePaths).allowsWrite(absolute)) {
            throw IllegalArgumentException(
                "Path '$rawPath' is outside the writable sandbox or is protected. " +
                    "This tool assembly has no execution approval channel; configure an explicit writable root or supply ExecutionPermissions."
            )
        }

        return validateFile(absolute.toFile(), rawPath, mustExist, mustBeFile) to grant
    }

    /** 禁止通过硬链接修改白名单之外的同一 inode；落盘时不跟随叶子符号链接。 */
    private fun safeWriteText(file: File, content: String) {
        val path = file.toPath()
        if (java.nio.file.Files.exists(path)) {
            val links = runCatching { java.nio.file.Files.getAttribute(path, "unix:nlink") as Number }.getOrNull()
            require(links == null || links.toInt() <= 1) { "Refusing to overwrite a hard-linked file: $file" }
        }
        java.nio.file.Files.writeString(path, content, java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.TRUNCATE_EXISTING, java.nio.file.LinkOption.NOFOLLOW_LINKS)
    }

    /** 存在性/类型校验（读写的公共尾部）。 */
    private fun validateFile(file: File, rawPath: String, mustExist: Boolean, mustBeFile: Boolean): File {
        if (mustExist && !file.exists()) {
            throw IllegalArgumentException("Path does not exist: ${displayPath(file)}")
        }
        if (mustExist && mustBeFile && !file.isFile) {
            throw IllegalArgumentException("Path is not a file: ${displayPath(file)}")
        }
        if (mustExist && !mustBeFile && !file.isDirectory) {
            throw IllegalArgumentException("Path is not a directory: ${displayPath(file)}")
        }
        return file
    }

    /**
     * 解析相对路径：依次尝试在各个允许目录下解析，取第一个存在的。
     * 如果是绝对路径直接返回。
     */
    private fun resolvePath(rawPath: String): Path {
        val p = Path(rawPath)
        if (p.isAbsolute) return p
        // 相对路径：在允许目录下查找
        for (dir in allowedDirectories) {
            val candidate = Path(dir).resolve(rawPath)
            if (candidate.toFile().exists()) return candidate
        }
        // 都不存在时，相对于第一个允许目录
        return Path(allowedDirectories.first()).resolve(rawPath)
    }

    /**
     * 生成给用户看的路径：优先显示相对于允许目录的相对路径。
     */
    private fun displayPath(file: File): String {
        val abs = file.toPath().absolute().normalize()
        for (dir in allowedDirectories) {
            val dirPath = Path(dir).absolute().normalize()
            if (abs.startsWith(dirPath)) {
                return abs.relativeTo(dirPath).toString()
            }
        }
        return abs.toString()
    }
}

````

## `core/src/commonMain/kotlin/xyz/mederi/tools/PlanTools.kt`

````kotlin
package xyz.mederi.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonTransformingSerializer
import xyz.mederi.domain.model.AgentMode
import xyz.mederi.domain.model.EventType
import xyz.mederi.domain.model.MederiEvent
import xyz.mederi.plan.Decision
import xyz.mederi.plan.Notebook
import xyz.mederi.plan.Plan
import xyz.mederi.plan.PlanApprovalRequester
import xyz.mederi.plan.PlanStatus
import xyz.mederi.plan.PlanStore
import xyz.mederi.plan.PlannedChange
import xyz.mederi.plan.SpecChange
import xyz.mederi.plan.Subtask
import xyz.mederi.plan.VerificationSpec
import xyz.mederi.plan.SubtaskStatus
import xyz.mederi.plan.VerificationChange
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import xyz.mederi.debug.DebugLog

private val json = Json { ignoreUnknownKeys = true }

/**
 * 宽松列表反序列化（List<String> 字段容错，实测两种失败形态）：
 * - 模型把列表写成单个字符串（`"outScope": "xxx"`）→ 包成单元素数组；
 * - 模型给"备选项"这类天然列表语义的字段传 JsonArray，而字段声明为 String → 改为 List<String> 后原样收下。
 * 映射进领域模型处统一 joinToString，领域类型不动。
 */
private object LenientStringList :
    JsonTransformingSerializer<List<String>>(ListSerializer(String.serializer())) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonArray) element else JsonArray(listOf(element))
}

/**
 * 复合对象列表字段形状矫正（List<DecisionArg>/List<PlannedChangeArg> 容错）。
 *
 * 实测两种崩溃形态（sess_f01ec14e）：
 * - 模型把对象列表序列化成 JSON 字符串（`"keyDecisions": "[{...}]"`）→ Expected JsonArray but had JsonLiteral
 * - 模型把单个对象直接当列表（`"decisions": {...}`）→ Expected JsonArray but had JsonObject
 *
 * 做成按字段名单变换的顶层工具函数，供 args 类级别的 JsonTransformingSerializer 调用——
 * 模型错形出现在哪个字段不可预测，类级变换一次覆盖全部复合列表字段。
 */
private fun coerceObjectListField(element: JsonElement, fieldName: String): JsonElement {
    if (element !is kotlinx.serialization.json.JsonObject) return element
    val value = element[fieldName] ?: return element
    val fixed: JsonElement = when (value) {
        is JsonArray -> value
        is kotlinx.serialization.json.JsonObject -> JsonArray(listOf(value))
        is kotlinx.serialization.json.JsonPrimitive -> runCatching {
            when (val parsed = Json.parseToJsonElement(value.content)) {
                is JsonArray -> parsed
                is kotlinx.serialization.json.JsonObject -> JsonArray(listOf(parsed))
                else -> JsonArray(emptyList())
            }
        }.getOrDefault(JsonArray(emptyList()))
        else -> JsonArray(emptyList())
    }
    return kotlinx.serialization.json.JsonObject(element.toMutableMap().apply { put(fieldName, fixed) })
}

/** 复合列表字段名（SubtaskArg + WritePlanArgs，形状矫正目标） */
private val OBJECT_LIST_FIELDS = listOf("decisions", "keyDecisions", "changes")

/** write_plan 参数整体形状矫正：先矫正子任务列表里每个 subtask，再矫正顶层字段 */
private object LenientWritePlanArgs :
    JsonTransformingSerializer<PlanTools.WritePlanArgs>(PlanTools.WritePlanArgs.generatedSerializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        var result = element
        // subtasks[*].decisions / targetFiles 内层矫正
        val subtasks = result.subelement("subtasks")
        if (subtasks is JsonArray) {
            val fixed = JsonArray(subtasks.map { sub ->
                OBJECT_LIST_FIELDS.fold(sub) { acc, f -> coerceObjectListField(acc, f) }
            })
            result = kotlinx.serialization.json.JsonObject(
                (result as? kotlinx.serialization.json.JsonObject)?.toMutableMap()?.apply { put("subtasks", fixed) }
                    ?: mutableMapOf("subtasks" to fixed)
            )
        }
        // 顶层 keyDecisions / changes 矫正
        return OBJECT_LIST_FIELDS.fold(result) { acc, f -> coerceObjectListField(acc, f) }
    }
}

/** generate_spec/子任务级参数形状矫正 */
private object LenientSubtaskArg :
    JsonTransformingSerializer<PlanTools.SubtaskArg>(PlanTools.SubtaskArg.generatedSerializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        OBJECT_LIST_FIELDS.fold(element) { acc, f -> coerceObjectListField(acc, f) }
}

/** generate_spec 参数形状矫正（appendix 已删除，目前为 no-op fold，保留为扩展预留） */
private object LenientGenerateSpecArgs :
    JsonTransformingSerializer<PlanTools.GenerateSpecArgs>(PlanTools.GenerateSpecArgs.generatedSerializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        OBJECT_LIST_FIELDS.fold(element) { acc, f -> coerceObjectListField(acc, f) }
}

private fun JsonElement.subelement(name: String): JsonElement? =
    (this as? kotlinx.serialization.json.JsonObject)?.get(name)

class PlanTools(
    private val sessionId: String,
    private val agentMode: AgentMode,
    private val planStore: PlanStore,
    private val planApprovalRequester: PlanApprovalRequester,
    private val notebook: Notebook,
    private val eventBus: MutableSharedFlow<MederiEvent>
) {

    @Serializable
    data class DecisionArg(
        @LLMDescription("The question or uncertainty being decided.")
        val question: String = "",
        @LLMDescription("The choice that was made.")
        val choice: String = "",
        @LLMDescription("Why this choice was made.")
        val rationale: String = "",
        @LLMDescription("Other options that were considered (list them as an array).")
        @kotlinx.serialization.Serializable(with = LenientStringList::class)
        val alternatives: List<String> = emptyList()
    )

    @Serializable
    data class PlannedChangeArg(
        @LLMDescription("Module or component grouping label, e.g. 'core', 'cli', 'ui'. Required.")
        val module: String = "",
        @LLMDescription("Action type: MODIFY, NEW, or DELETE.")
        val action: String,
        @LLMDescription("File path to be created or modified.")
        val filePath: String,
        @LLMDescription("What specifically changes in this file — not just 'Add X' but 'Add X that does Y, called from Z'. For MODIFY: what exists now and what it becomes.")
        val description: String,
        @LLMDescription("Why this change is needed here, and why this approach.")
        val rationale: String
    )

    @KeepGeneratedSerializer
    @Serializable(with = LenientSubtaskArg::class)
    data class SubtaskArg(
        @LLMDescription("Subtask name. Concise action-noun.")
        val name: String,
        @LLMDescription(
            "Brief: what this subtask achieves, the approach (how), and which files are touched. " +
                "The user reviews this to understand HOW the subtask will be done before approving. " +
                "Do NOT write the detailed implementation spec here — that is generated AFTER approval " +
                "via generate_spec."
        )
        val planDetail: String = "",
        @LLMDescription("Exact file paths that will be created or modified (list them as an array).")
        @kotlinx.serialization.Serializable(with = LenientStringList::class)
        val targetFiles: List<String> = emptyList(),
        @LLMDescription("Design decisions resolved during planning this subtask.")
        val decisions: List<DecisionArg> = emptyList(),
        @LLMDescription("Verification: a single executable command (ASCII, assert-style). verify_subtask actually runs it; prose is rejected.")
        val verification: String,
        @LLMDescription("Optional working directory for the verification command (relative to project root). Default = project root.")
        val verificationCwd: String? = null,
        @LLMDescription("Optional timeout for the verification command, in seconds. Default = 30.")
        val verificationTimeoutSeconds: Int? = null,
        @LLMDescription(
            "REQUIRED. Expected result in plain words: what the command must output for this subtask to " +
                "count as passed (e.g. 'pytest reports 3 passed, 0 failed'). Rendered on the approval card " +
                "so the user can judge whether the verification method itself is right. Use the user's language."
        )
        val verificationExpected: String = "",
        @LLMDescription(
            "REQUIRED. Literal strings the command's output MUST contain — " +
                "MACHINE-CHECKED. Exiting 0 is not enough: if any literal here is missing from the real " +
                "output, verification is mechanically judged FAIL. Pin the actual expectation " +
                "(e.g. [\"3 passed\", \"BEHAVIOR_CHECK_PASS\"]) so a partially-passing command cannot slip through."
        )
        @kotlinx.serialization.Serializable(with = LenientStringList::class)
        val verificationExpectStdoutContains: List<String> = emptyList(),
        @LLMDescription(
            "Optional. Literal strings the command's output MUST NOT contain — MACHINE-CHECKED " +
                "(e.g. [\"FAILED\", \"Traceback\"])."
        )
        @kotlinx.serialization.Serializable(with = LenientStringList::class)
        val verificationExpectStdoutNotContains: List<String> = emptyList(),
        @LLMDescription("Indices of subtasks this depends on (0-based).")
        val dependsOn: List<Int> = emptyList(),
        @LLMDescription("Whether this can run in parallel with other subtasks.")
        val parallelizable: Boolean = false,
        @LLMDescription("Optional. Subtask-level technical appendix for reviewers/controllers: current logic, code evidence, root cause, fix preview (pseudo-code or real code), business logic. High information density — no full source dumps, only key code lines + core contradiction + key pseudocode. Leave empty for simple subtasks (new files, mechanical changes). This is the argument version (why), distinct from spec (how).")
        val appendix: String? = null,
    )

    @KeepGeneratedSerializer
    @Serializable(with = LenientWritePlanArgs::class)
    data class WritePlanArgs(
        @LLMDescription("Optional. Empty = create a new plan. Non-empty = update an existing PENDING_APPROVAL plan in place — only provide fields you want to change, blank/empty fields keep their existing values.")
        val planId: String = "",
        @LLMDescription("Plan title.")
        val title: String,
        @LLMDescription(
            "ONE sentence for the approval card: what will change and to what end. " +
                "Must NOT duplicate the Goal/Approach content; this is the single-line takeaway for the approval card."
        )
        val summary: String = "",
        @LLMDescription(
            "Project context: GREENFIELD (brand-new project built from zero) or BROWNFIELD " +
                "(iterating an existing codebase). GREENFIELD plans must make the FIRST subtask " +
                "a project bootstrap: module init + dependency manifest (go.mod / package.json / ...)."
        )
        val projectContext: String = "BROWNFIELD",
        @LLMDescription("Language and toolchain with version, e.g. 'Go 1.22', 'Kotlin 2.1 + Gradle 8.10'.")
        val languageStack: String = "",
        @LLMDescription(
            "Optional. Business logic: where the requirement enters (UI/API/command — which " +
                "function/call chain receives it), how behavior changes (before vs after), where new " +
                "logic hooks into the call chain. If included, also weave the key business logic into " +
                "the approach field so the user sees it at a glance."
        )
        val businessLogic: String = "",
        @LLMDescription("Optional. Background context; omit when the task has no distinct background. Empty fields are not rendered in plan.md.")
        val background: String = "",
        @LLMDescription("Optional. What is wrong or what is needed; omit when redundant with the Goal narrative.")
        val problemAnalysis: String = "",
        @LLMDescription("Required. Approach: how you will fix/implement it, including the business logic (rules, data flow, key algorithms). Not line-level implementation — the 'how it works' level.")
        val approach: String = "",
        @LLMDescription("Optional. What will be different after; omit when folded into the Goal.")
        val expectedOutcome: String = "",
        @LLMDescription("Optional. Verification approach; rendered as '验证计划（Verification Plan）'. Omit for tasks with no verification needs.")
        val testMethod: String = "",
        @LLMDescription("Optional. What this plan will do; omit when scope is obvious from the Goal.")
        @kotlinx.serialization.Serializable(with = LenientStringList::class)
        val inScope: List<String> = emptyList(),
        @LLMDescription("Out of scope: what this plan will NOT do.")
        @kotlinx.serialization.Serializable(with = LenientStringList::class)
        val outScope: List<String> = emptyList(),
        @LLMDescription("Legacy compatibility field. Leave empty: resolve all substantive decisions with the user via ask_user before writing a plan.")
        @kotlinx.serialization.Serializable(with = LenientStringList::class)
        val openQuestions: List<String> = emptyList(),
        @LLMDescription("Key decisions: trade-offs, breaking changes, and rationale.")
        val keyDecisions: List<DecisionArg> = emptyList(),
        @LLMDescription("Proposed changes per module with [MODIFY]/[NEW]/[DELETE] markers. For each file, describe what specifically changes — not just the file name.")
        val changes: List<PlannedChangeArg> = emptyList(),
        @LLMDescription(
            "Optional: data sources read or written (tables, files, endpoints, formats) and new or changed " +
                "parameters with their defaults. Omit entirely if the task touches no data or parameters."
        )
        @kotlinx.serialization.Serializable(with = LenientStringList::class)
        val dataAndParams: List<String> = emptyList(),
        @LLMDescription("Risks and rollback strategy.")
        @kotlinx.serialization.Serializable(with = LenientStringList::class)
        val risks: List<String> = emptyList(),
        @LLMDescription("Optional. How to know the whole task is done; completion criteria is primarily carried by Approach + per-subtask verification contracts.")
        @kotlinx.serialization.Serializable(with = LenientStringList::class)
        val successCriteria: List<String> = emptyList(),
        @LLMDescription("Optional: Mermaid diagram code (without fence) for architecture visualization. Mermaid is the only diagram format the UI renders; do not use PlantUML/DOT or other DSLs.")
        val architecture: String? = null,
        @LLMDescription(
            "Optional. Research conclusions from your investigation (or a researcher sub-agent's report) " +
                "— key findings, patterns, gotchas, architecture notes. Kept in plan.json as the parent's " +
                "memory aid. NOT injected into executor briefings — if executors need this info, put it " +
                "in the spec. Omit when no research was done."
        )
        val researchNotes: String = "",
        @LLMDescription("Optional. Plan-level technical context: cross-subtask shared background, plan-level key decisions/changes/data. Omit when there is no cross-subtask content.")
        val appendix: String? = null,
        @LLMDescription("Subtask list (the skeleton). Each must include name, brief intent, targetFiles, and verification. Detailed implementation specs are generated after approval via generate_spec.")
        val subtasks: List<SubtaskArg>
    )

    @KeepGeneratedSerializer
    @Serializable(with = LenientGenerateSpecArgs::class)
    data class GenerateSpecArgs(
        @LLMDescription("Plan ID from the approved plan.")
        val planId: String,
        @LLMDescription("Subtask index (0-based) to generate the spec for.")
        val subtaskIndex: Int,
        @LLMDescription(
            "The detailed implementation spec as an ORDERED CHECKLIST: numbered steps (1. 2. 3.), " +
                "each step concrete and independently verifiable, in execution order. The executor " +
                "has NO project context and does NOT guess — the spec is its sole source of " +
                "implementation requirements. Follow the Spec Writing Guide: state modification " +
                "scope (files, anchors), per-file steps (types, signatures, wiring), applicable build/check " +
                "command, execution boundary, and all behavior decisions (branches, edge cases, " +
                "error handling). Translate project rules into concrete actions. Do NOT leave " +
                "decisions as 'handle reasonably' or 'follow existing patterns'. Specs are " +
                "implementation only — do NOT include test-running steps. Supply an applicable compile/build " +
                "command, or state why compilation is inapplicable and give a non-test validation check."
        )
        val spec: String,
        @LLMDescription(
            "REQUIRED when the subtask ALREADY has a spec — i.e. you are correcting it (typically after " +
                "verification showed the spec contradicts reality), not generating it for the first time. " +
                "State why the old spec was wrong; it is kept in the append-only spec audit trail " +
                "(the full old spec text is preserved). Omit on first generation."
        )
        val reason: String = ""
    )

    inner class WritePlanTool : SimpleTool<WritePlanArgs>(
        argsType = typeToken<WritePlanArgs>(),
        name = ToolName.WRITE_PLAN.value,
        description = "Software-development workflow only; not for browser, research, office, or document tasks, " +
            "even when temporary scripts are used. Resolve substantive decisions via ask_user first; " +
            "leave openQuestions empty. Create or update a PENDING_APPROVAL plan. Without planId: " +
            "creates a new plan (voids prior active plans in this session). With planId: updates the existing " +
            "plan in place — only provide fields you want to change, blank/empty fields keep their existing " +
            "values. Only PENDING_APPROVAL plans can be updated. APPROVED/IN_PROGRESS plans: use converge_plan " +
            "to append subtasks. VOIDED/COMPLETED plans cannot be modified. In Approval mode, the plan is " +
            "saved to a file and the user is notified via event. In Autonomous mode, the plan is auto-approved."
    ) {
        override suspend fun execute(args: WritePlanArgs): String {
            // ===== UPDATE MODE =====
            if (args.planId.isNotBlank()) {
                val existing = planStore.load(args.planId)
                    ?: return "Error: Plan not found: ${args.planId}"
                if (existing.status != PlanStatus.PENDING_APPROVAL)
                    return "Error: Cannot update plan — status is ${existing.status}. " +
                        "Only PENDING_APPROVAL plans can be updated. " +
                        "APPROVED plans: use converge_plan to append subtasks. " +
                        "Spec corrections: use generate_spec with reason= (append-only)."

                // 局部校验：只校验提供的字段
                if (args.subtasks.isNotEmpty()) {
                    // 校验新提供的子任务（verification/expected/stdoutContains 规则）
                    args.subtasks.forEachIndexed { i, st ->
                        if (st.verification.isBlank())
                            return "Error: Subtask $i (${st.name}): verification is required."
                        if (st.verificationExpected.isBlank())
                            return "Error: Subtask $i (${st.name}): verificationExpected is required."
                    }
                }
                if (args.changes.isNotEmpty()) {
                    for (c in args.changes) {
                        if (!c.action.uppercase().matches(Regex("MODIFY|NEW|DELETE")))
                            return "Error: Change ${c.filePath}: action must be MODIFY, NEW, or DELETE."
                        if (c.action.equals("NEW", ignoreCase = true) && c.rationale.isBlank())
                            return "Error: Change ${c.filePath}: a [NEW] file must justify why an existing file cannot be modified instead."
                    }
                }

                // 应用非空字段，blank/empty = 保持原值
                val updated = existing.copy(
                    title = args.title.takeIf { it.isNotBlank() } ?: existing.title,
                    summary = args.summary.takeIf { it.isNotBlank() } ?: existing.summary,
                    projectContext = if (args.projectContext.isNotBlank() && args.projectContext.equals("GREENFIELD", ignoreCase = true))
                        xyz.mederi.plan.ProjectContextType.GREENFIELD else existing.projectContext,
                    languageStack = args.languageStack.takeIf { it.isNotBlank() } ?: existing.languageStack,
                    businessLogic = args.businessLogic.takeIf { it.isNotBlank() } ?: existing.businessLogic,
                    background = args.background.takeIf { it.isNotBlank() } ?: existing.background,
                    problemAnalysis = args.problemAnalysis.takeIf { it.isNotBlank() } ?: existing.problemAnalysis,
                    approach = args.approach.takeIf { it.isNotBlank() } ?: existing.approach,
                    expectedOutcome = args.expectedOutcome.takeIf { it.isNotBlank() } ?: existing.expectedOutcome,
                    testMethod = args.testMethod.takeIf { it.isNotBlank() } ?: existing.testMethod,
                    inScope = args.inScope.takeIf { it.isNotEmpty() } ?: existing.inScope,
                    outScope = args.outScope.takeIf { it.isNotEmpty() } ?: existing.outScope,
                    openQuestions = args.openQuestions.takeIf { it.isNotEmpty() } ?: existing.openQuestions,
                    keyDecisions = args.keyDecisions.takeIf { it.isNotEmpty() }?.map { Decision(it.question, it.choice, it.rationale, it.alternatives.joinToString("; ")) } ?: existing.keyDecisions,
                    changes = args.changes.takeIf { it.isNotEmpty() }?.map { PlannedChange(it.module.ifBlank { "core" }, it.action.uppercase(), it.filePath, it.description, it.rationale) } ?: existing.changes,
                    dataAndParams = args.dataAndParams.takeIf { it.isNotEmpty() } ?: existing.dataAndParams,
                    risks = args.risks.takeIf { it.isNotEmpty() } ?: existing.risks,
                    successCriteria = args.successCriteria.takeIf { it.isNotEmpty() } ?: existing.successCriteria,
                    architecture = args.architecture ?: existing.architecture,
                    researchNotes = args.researchNotes.takeIf { it.isNotBlank() } ?: existing.researchNotes,
                    appendix = args.appendix ?: existing.appendix,
                    subtasks = args.subtasks.takeIf { it.isNotEmpty() }?.mapIndexed { i, st ->
                        Subtask(
                            index = i,
                            name = st.name,
                            planDetail = st.planDetail,
                            targetFiles = st.targetFiles,
                            decisions = st.decisions.map { Decision(it.question, it.choice, it.rationale, it.alternatives.joinToString("; ")) },
                            verification = VerificationSpec(
                                command = st.verification,
                                cwd = st.verificationCwd,
                                timeoutSeconds = st.verificationTimeoutSeconds,
                                expected = st.verificationExpected,
                                expectStdoutContains = st.verificationExpectStdoutContains,
                                expectStdoutNotContains = st.verificationExpectStdoutNotContains,
                            ),
                            dependsOn = st.dependsOn,
                            parallelizable = st.parallelizable,
                            appendix = st.appendix
                        )
                    } ?: existing.subtasks
                )

                planStore.save(updated)

                val planPath = planStore.getPlanAbsolutePath(updated.id)
                    ?: return "Error: plan was updated but plan.md could not be read."
                val planContent = runCatching { java.io.File(planPath).readText() }.getOrDefault("")
                val subtasksJson = json.encodeToString(ListSerializer(Subtask.serializer()), updated.subtasks)

                return if (agentMode == AgentMode.APPROVAL) {
                    val result = planApprovalRequester.request(
                        planId = updated.id,
                        planPath = planPath,
                        title = updated.title,
                        summary = updated.summary,
                        planContent = planContent,
                        subtaskCount = updated.subtasks.size,
                        subtasksJson = subtasksJson
                    )
                    if (result.superseded) "Plan superseded by a newer plan request."
                    else if (result.approved) {
                        planStore.update(updated.copy(status = PlanStatus.APPROVED))
                        notebook.append("## ${Instant.now()} — Plan approved: ${updated.title}")
                        emitPlanProgress(updated.copy(status = PlanStatus.APPROVED), "approved")
                        "Plan updated and approved. Plan ID: ${updated.id}. Use subagent(SPAWN, planId=..., subtaskIndex=...) to execute subtasks."
                    } else {
                        "Plan updated; remains PENDING_APPROVAL. Respond to the user's latest message."
                    }
                } else {
                    "Plan updated. Plan ID: ${updated.id}."
                }
            }

            // ===== CREATE MODE（现有逻辑，原封不动）=====
            val error = validatePlan(args)
            if (error != null) return "Error: $error"

            // 三条硬规则之二：同一会话只有最新计划可执行——新 write_plan 作废全部非终态旧计划
            // （含执行中），作废旧计划移入 plans-voided/ 留痕。已完成的 COMPLETED 计划保留不受影响。
            val voidedIds = planStore.voidActivePlans(sessionId)
            if (voidedIds.isNotEmpty()) {
                voidedIds.forEach { oldPlanId ->
                    eventBus.emit(MederiEvent(
                        type = EventType.PLAN_PROGRESS,
                        sessionId = sessionId,
                        payload = mapOf(
                            "planId" to oldPlanId,
                            "action" to "voided"
                        ),
                        timestamp = Instant.now().toString()
                    ))
                }
                DebugLog.data("PlanTools", "voided prior non-terminal plans", "count=${voidedIds.size}, ids=$voidedIds")
            }

            val now = Instant.now().atZone(ZoneOffset.UTC)
            val timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").format(now)

            val plan = Plan(
                id = "plan_${UUID.randomUUID().toString().take(8)}",
                title = args.title,
                summary = args.summary,
                projectContext = if (args.projectContext.equals("GREENFIELD", ignoreCase = true))
                    xyz.mederi.plan.ProjectContextType.GREENFIELD
                else xyz.mederi.plan.ProjectContextType.BROWNFIELD,
                languageStack = args.languageStack,
                sessionId = sessionId,
                businessLogic = args.businessLogic,
                background = args.background,
                problemAnalysis = args.problemAnalysis,
                approach = args.approach,
                expectedOutcome = args.expectedOutcome,
                testMethod = args.testMethod,
                inScope = args.inScope,
                outScope = args.outScope,
                openQuestions = args.openQuestions,
                keyDecisions = args.keyDecisions.map { Decision(it.question, it.choice, it.rationale, it.alternatives.joinToString("; ")) },
                changes = args.changes.map { PlannedChange(it.module.ifBlank { "core" }, it.action.uppercase(), it.filePath, it.description, it.rationale) },
                dataAndParams = args.dataAndParams,
                risks = args.risks,
                successCriteria = args.successCriteria,
                architecture = args.architecture,
                researchNotes = args.researchNotes,
                appendix = args.appendix,
                subtasks = args.subtasks.mapIndexed { i, st ->
                    Subtask(
                        index = i,
                        name = st.name,
                        planDetail = st.planDetail,
                        targetFiles = st.targetFiles,
                        decisions = st.decisions.map { Decision(it.question, it.choice, it.rationale, it.alternatives.joinToString("; ")) },
                        verification = VerificationSpec(
                            command = st.verification,
                            cwd = st.verificationCwd,
                            timeoutSeconds = st.verificationTimeoutSeconds,
                            expected = st.verificationExpected,
                            expectStdoutContains = st.verificationExpectStdoutContains,
                            expectStdoutNotContains = st.verificationExpectStdoutNotContains,
                        ),
                        dependsOn = st.dependsOn,
                        parallelizable = st.parallelizable,
                        appendix = st.appendix
                    )
                },
                status = if (agentMode == AgentMode.AUTONOMOUS) PlanStatus.APPROVED else PlanStatus.PENDING_APPROVAL,
                createdAt = timestamp,
                agentMode = agentMode
            )

            planStore.save(plan)
            // save 已自愈创建 .mederi；仍拿不到路径说明项目目录异常——明确报错，
            // 绝不带着空 planPath 发批准事件（UI 会渲染成无内容的空壳卡片）
            val planPath = planStore.getPlanAbsolutePath(plan.id)
                ?: return "Error: plan was created but could not be persisted under .mederi/plans/ " +
                    "of the project directories. Check that the project's main directory is writable, " +
                    "then retry write_plan."

            val planContent = runCatching { java.io.File(planPath).readText() }.getOrDefault("")
            DebugLog.data(
                "PlanTools",
                "write_plan executed",
                "planId=${plan.id}, title='${plan.title}', summary='${plan.summary}', contentLen=${planContent.length}"
            )

            return if (agentMode == AgentMode.APPROVAL) {
                val subtasksJson = json.encodeToString(ListSerializer(Subtask.serializer()), plan.subtasks)
                val result = planApprovalRequester.request(
                    planId = plan.id,
                    planPath = planPath,
                    title = plan.title,
                    summary = plan.summary,
                    planContent = planContent,
                    subtaskCount = plan.subtasks.size,
                    subtasksJson = subtasksJson
                )
                if (result.superseded) {
                    "Plan superseded by a newer plan request."
                } else if (result.approved) {
                    planStore.update(plan.copy(status = PlanStatus.APPROVED))
                    notebook.append("## ${Instant.now()} — Plan approved: ${plan.title}")
                    emitPlanProgress(plan.copy(status = PlanStatus.APPROVED), "approved")
                    "Plan approved. Plan ID: ${plan.id}. Use subagent(SPAWN, planId=..., subtaskIndex=...) to execute subtasks."
                } else {
                    // 计划无"拒绝"态（只有批准/作废/被无视）。此分支是用户未批准也未作废时的
                    // 兜底：不预设意图、绝不反问"为什么"。让模型回去响应用户最新消息，计划保持
                    // PENDING_APPROVAL，等用户明确批准或下个 write_plan 作废它。
                    "Plan was not approved and not voided; it remains PENDING_APPROVAL. " +
                        "Do not retry write_plan now. Respond to the user's latest message; " +
                        "do not ask why they did not approve."
                }
            } else {
                notebook.append("## ${Instant.now()} — Plan created (auto-approved): ${plan.title}")
                emitPlanProgress(plan, "created")
                "Plan created and auto-approved. Plan ID: ${plan.id}. Use subagent(SPAWN, planId=..., subtaskIndex=...) to execute subtasks."
            }
        }

        /**
         * PLAN_PROGRESS 事件：驱动 UI planApprovals 投影表。
         * 四类发射点（create/spawn/verify/converge）经由本函数统一发出，不发各自手拼 payload。
         */
        private suspend fun emitPlanProgress(plan: Plan, action: String) {
            val subtasksJson = json.encodeToString(ListSerializer(Subtask.serializer()), plan.subtasks)
            eventBus.emit(MederiEvent(
                type = EventType.PLAN_PROGRESS,
                sessionId = sessionId,
                payload = mapOf(
                    "planId" to plan.id,
                    "action" to action,
                    "subtasks" to subtasksJson
                ),
                timestamp = Instant.now().toString()
            ))
        }
    }

    @Serializable
    data class ConvergePlanArgs(
        @LLMDescription("Plan ID to converge.")
        val planId: String,
        @LLMDescription("Remediation subtasks to append. Same structure as write_plan subtasks.")
        val remediationSubtasks: List<SubtaskArg>
    )

    inner class ConvergePlanTool : SimpleTool<ConvergePlanArgs>(
        argsType = typeToken<ConvergePlanArgs>(),
        name = ToolName.CONVERGE_PLAN.value,
        description = "When verify_subtask reports PARTIAL or FAIL, append remediation subtasks to the plan. " +
            "Append-only: never rewrites or renumbers existing subtasks."
    ) {
        override suspend fun execute(args: ConvergePlanArgs): String {
            val plan = planStore.load(args.planId)
                ?: return "Error: Plan not found: ${args.planId}"
            if (args.remediationSubtasks.isEmpty())
                return "Error: Must provide at least one remediation subtask."

            val nextIndex = plan.subtasks.size
            val newSubtasks = args.remediationSubtasks.mapIndexed { i, st ->
                Subtask(
                    index = nextIndex + i,
                    name = st.name,
                    planDetail = st.planDetail,
                    targetFiles = st.targetFiles,
                    decisions = st.decisions.map { Decision(it.question, it.choice, it.rationale, it.alternatives.joinToString("; ")) },
                    verification = VerificationSpec(
                        command = st.verification,
                        cwd = st.verificationCwd,
                        timeoutSeconds = st.verificationTimeoutSeconds,
                        expected = st.verificationExpected,
                        expectStdoutContains = st.verificationExpectStdoutContains,
                        expectStdoutNotContains = st.verificationExpectStdoutNotContains,
                    ),
                    dependsOn = st.dependsOn,
                    parallelizable = st.parallelizable,
                    appendix = st.appendix
                )
            }

            // 原子 RMW（AGENTS.md §5.5）：converge 与并行工具调度共存时，裸 load→copy→save 会覆盖他人写入
            val updatedPlan = planStore.updatePlan(args.planId) { p ->
                p.copy(subtasks = p.subtasks + newSubtasks)
            } ?: return "Error: Plan not found: ${args.planId}"

            val passed = updatedPlan.subtasks.count { it.status == SubtaskStatus.COMPLETED }
            val failed = updatedPlan.subtasks.count { it.status == SubtaskStatus.FAILED }
            val pending = updatedPlan.subtasks.count { it.status == SubtaskStatus.PENDING }
            eventBus.emit(MederiEvent(
                type = EventType.PLAN_PROGRESS,
                sessionId = sessionId,
                payload = mapOf(
                    "planId" to args.planId,
                    "action" to "converged",
                    "appendedCount" to args.remediationSubtasks.size.toString(),
                    "totalSubtasks" to updatedPlan.subtasks.size.toString(),
                    "passed" to passed.toString(),
                    "failed" to failed.toString(),
                    "pending" to pending.toString()
                ),
                timestamp = Instant.now().toString()
            ))

            return "Converged: ${args.remediationSubtasks.size} remediation subtasks appended " +
                "(indices $nextIndex..${nextIndex + newSubtasks.size - 1}). " +
                "Use subagent(SPAWN, planId=..., subtaskIndex=...) to execute."
        }
    }

    @Serializable
    data class WriteLogArgs(
        @LLMDescription("Log entry content.")
        val entry: String
    )

    inner class WriteLogTool : SimpleTool<WriteLogArgs>(
        argsType = typeToken<WriteLogArgs>(),
        name = ToolName.WRITE_LOG.value,
        description = "Append an entry to the work log (.mederi/notebook.md). Use this to record decisions, findings, and important events."
    ) {
        override suspend fun execute(args: WriteLogArgs): String {
            val timestamp = Instant.now().toString()
            val ok = notebook.append("## $timestamp — Log entry\n${args.entry}")
            return if (ok) "Logged." else "Error: no writable .mederi directory; log entry not persisted."
        }
    }

    /**
     * generate_spec：计划批准后，为单个子任务派生可执行 Spec。
     *
     * 分层语义（docs/sandbox-plan.md / AGENTS.md §5.5）：
     * - Plan（批准前）= WHAT：意图级骨架，用户审的是这个
     * - Spec（批准后）= HOW：对照真实代码写函数签名/数据结构/边界情况——
     *   此时计划已定、前序子任务已有产出，spec 天然贴地，不存在"写着写着过期"
     * - spawn_agent 只能按已存在的 Spec 执行（硬保证），发现 spec 与现实矛盾
     *   → 重新 generate_spec 修正（append-only：完整旧 spec 留存 reason 后写入 specChanges）→ 重执行
     */
    inner class GenerateSpecTool : SimpleTool<GenerateSpecArgs>(
        argsType = typeToken<GenerateSpecArgs>(),
        name = ToolName.GENERATE_SPEC.value,
        description = "Generate the detailed implementation spec for ONE subtask of an APPROVED plan, " +
            "grounded in the actual codebase (read the real files first: function names, signatures, " +
            "data structures must match reality). Call this before executing a subtask, directly or via an " +
            "optional subagent — and re-call it (with reason=) to CORRECT the spec whenever verification shows " +
            "the spec itself is wrong. Corrections are append-only: the full old spec text is preserved " +
            "in the audit trail (specChanges). The spec is stored per (planId, subtaskIndex); " +
            "subagent(SPAWN) will execute exactly this spec. The executor does NOT guess — the spec is " +
            "its sole source of implementation requirements. Include an applicable build/compile check, " +
            "or explain why compilation is inapplicable and specify a non-test check. " +
            "Do NOT include test-running steps; you test via verify_subtask."
    ) {
        override suspend fun execute(args: GenerateSpecArgs): String {
            val plan = planStore.load(args.planId)
                ?: return "Error: Plan not found: ${args.planId}"
            if (plan.status != PlanStatus.APPROVED && plan.status != PlanStatus.IN_PROGRESS)
                return "Error: Plan ${plan.id} is ${plan.status}. Specs can only be generated for an approved plan."
            val st = plan.subtasks.getOrNull(args.subtaskIndex)
                ?: return "Error: Subtask index ${args.subtaskIndex} out of range (0..${plan.subtasks.size - 1})."
            if (st.status == SubtaskStatus.COMPLETED)
                return "Error: Subtask ${args.subtaskIndex} is COMPLETED. Use converge_plan to append new work instead."
            if (args.spec.isBlank())
                return "Error: spec must not be empty."
            // 修正（覆盖既有 spec）必须给 reason：严格 append-only 要求每次修正留痕，
            // 且完整旧 spec 文本会被保存（SpecChange.oldSpec），信息零销毁。
            val isCorrection = !st.spec.isNullOrBlank()
            if (isCorrection && args.reason.isBlank())
                return "Error: subtask ${args.subtaskIndex} already has a spec — you are CORRECTING it, " +
                    "not generating it. Pass reason=<why the old spec was wrong>. " +
                    "The old spec text is preserved in the append-only audit trail (specChanges)."

            // Spec 生效值写 Subtask.spec（spawn_agent 读取的真理源）；brief（planDetail）保留不动，
            // 用户批准时看到的内容永不失真。修正 = 追加一条 SpecChange（含完整旧 spec）+ 更新生效值，
            // 不是覆盖销毁（严格 append-only）。
            // 用 updatePlan 原子写入：工具支持并行调度，同消息多次 generate_spec 时
            // 裸 load→copy→save 会互相覆盖（后写把前写的 spec 恢复成旧值）。
            val updated = planStore.updatePlan(args.planId) { p ->
                p.copy(
                    subtasks = p.subtasks.map { s ->
                        if (s.index == args.subtaskIndex) s.copy(
                            spec = args.spec,
                            specChanges = s.specChanges + SpecChange(
                                oldSpec = s.spec,
                                newSpec = args.spec,
                                reason = args.reason,
                                timestamp = Instant.now().toString()
                            )
                        ) else s
                    }
                )
            } ?: return "Error: Plan not found: ${args.planId}"
            notebook.append(
                "## ${Instant.now()} — Spec generated: Subtask ${args.subtaskIndex} (${st.name})\n"
            )
            val subtasksJson = json.encodeToString(ListSerializer(Subtask.serializer()), updated.subtasks)
            eventBus.emit(xyz.mederi.domain.model.MederiEvent(
                type = EventType.PLAN_PROGRESS,
                sessionId = sessionId,
                payload = mapOf(
                    "planId" to args.planId,
                    "action" to "spec-generated",
                    "subtaskIndex" to args.subtaskIndex.toString(),
                    "subtasks" to subtasksJson
                ),
                timestamp = Instant.now().toString()
            ))
            return "Spec saved for Subtask ${args.subtaskIndex} (${st.name}). " +
                "Execute it directly or optionally delegate with planId=${args.planId}, " +
                "subtaskIndex=${args.subtaskIndex}; choose based on total cost and context value. Then verify_subtask."
        }
    }

    /**
     * 计划校验（聚合报错）：一次列出全部问题，模型一轮补齐所有缺失项。
     * 逐条报错的台阶实测爬不完（弱模型 6+ 轮都到不了底），聚合后一轮收敛。
     */
    private fun validatePlan(args: WritePlanArgs): String? {
        val errors = mutableListOf<String>()
        if (args.subtasks.isEmpty())
            errors.add("Plan must contain at least one subtask.")
        if (args.approach.isBlank())
            errors.add("Approach is required: how you will fix/implement it, including business logic.")
        val isGreenfield = args.projectContext.equals("GREENFIELD", ignoreCase = true)
        // 二值不变量校验：只检查"有没有"，不限制"写多少"（篇幅交给提示词约束）
        if (args.keyDecisions.isEmpty())
            errors.add(
                "Key Decisions is required: surface your assumptions and choices explicitly " +
                    "(storage location, formats, algorithms, libraries) instead of burying them in prose."
            )
        if (args.changes.isEmpty())
            errors.add(
                "Changes is required: list every file with [MODIFY]/[NEW]/[DELETE] markers. " +
                    "Every [NEW] file must justify why an existing file cannot be modified instead."
            )
        if (args.languageStack.isBlank())
            errors.add("Language stack is required, e.g. 'Go 1.22', 'Kotlin 2.1 + Gradle 8.10'.")
        for (c in args.changes) {
            if (!c.action.uppercase().matches(Regex("MODIFY|NEW|DELETE")))
                errors.add(
                    "Change ${c.filePath}: action must be MODIFY, NEW, or DELETE (got \"${c.action}\"). " +
                        "Example: {module: \"core\", action: \"NEW\", filePath: \"main.go\", description: \"...\", rationale: \"...\"}."
                )
            if (c.action.equals("NEW", ignoreCase = true) && c.rationale.isBlank())
                errors.add("Change ${c.filePath}: a [NEW] file must justify why an existing file cannot be modified instead.")
        }
        args.subtasks.forEachIndexed { i, st ->
            if (st.verification.isBlank())
                errors.add("Subtask $i (${st.name}): verification is required.")
            val hasCJK = st.verification.any { it.code in 0x4E00..0x9FFF }
            if (hasCJK)
                errors.add(
                    "Subtask $i (${st.name}): verification 必须是单条可执行命令（ASCII），不能是散文描述。" +
                        "示例：`python3 -c 'assert 1+1==2'`。"
                )
            if (st.verificationExpected.isBlank())
                errors.add(
                    "Subtask $i (${st.name}): verificationExpected is required — state in plain words what the " +
                        "command must output for this subtask to count as passed (e.g. '3 passed, 0 failed'). " +
                        "It is rendered on the approval card so the user can judge whether the verification " +
                        "method itself is right."
                )
            if (st.targetFiles.isEmpty())
                errors.add("Subtask $i (${st.name}): targetFiles required (which files will be changed?).")
            // 规则 1：每个子任务的验证必须钉至少 1 个期望 stdout 字面量（裸 exit-code 验证抓不到"活没干成"）
            checkLiteralRequired(st)?.let { errors.add("Subtask $i (${st.name}): $it") }
            for (dep in st.dependsOn) {
                if (dep >= args.subtasks.size || dep < 0)
                    errors.add("Subtask $i (${st.name}): dependsOn $dep out of range (0..${args.subtasks.size - 1}).")
            }
        }
        if (isGreenfield) {
            // Greenfield 第一个子任务必须做项目骨架：否则后续子任务产出的代码根本无法编译
            args.subtasks.firstOrNull()?.let { first ->
                val bootstrapHint = first.name + " " + first.planDetail
                val isBootstrap = listOf("bootstrap", "init", "scaffold", "go.mod", "package.json",
                    "build.gradle", "cargo.toml", "pyproject", "module", "skeleton", "骨架", "初始化", "脚手架")
                    .any { bootstrapHint.contains(it, ignoreCase = true) }
                if (!isBootstrap)
                    errors.add(
                        "This plan is GREENFIELD: the FIRST subtask must be project bootstrap — " +
                            "module init + dependency manifest (go.mod / package.json / build.gradle.kts / ...), " +
                            "so later subtasks can build. Name it accordingly (e.g. 'Project bootstrap: go mod init + deps')."
                    )
            }
        }
        // 规则 4：两个 parallelizable 子任务若共享 gradle 模块，其验证编译会竞争——同批并行 spawn 直接拒绝
        errors.addAll(checkParallelizableModuleOverlap(args.subtasks))
        if (errors.isEmpty()) return null
        return "Plan validation failed — fix ALL of the following items, then retry write_plan once with the complete arguments:\n" +
            errors.mapIndexed { i, e -> "${i + 1}. $e" }.joinToString("\n")
    }
    @Serializable
    data class UpdateVerificationArgs(
        @LLMDescription("Plan ID.")
        val planId: String,
        @LLMDescription("Subtask index (0-based) to amend.")
        val subtaskIndex: Int,
        @LLMDescription("New verification command: single executable command (ASCII, assert-style). Prose is rejected.")
        val command: String = "",
        @LLMDescription("Optional: working directory for the verification command (relative to project root).")
        val cwd: String? = null,
        @LLMDescription("Optional: timeout seconds. Default 30.")
        val timeoutSeconds: Int? = null,
        @LLMDescription("Optional. New expected result in plain words. Omit to KEEP the existing one.")
        val expected: String? = null,
        @LLMDescription("Optional. New machine-checked literals the output MUST contain. Omit to KEEP the existing list.")
        val expectStdoutContains: List<String>? = null,
        @LLMDescription("Optional. New machine-checked literals the output MUST NOT contain. Omit to KEEP the existing list.")
        val expectStdoutNotContains: List<String>? = null,
        @LLMDescription("Required: why the verification contract is being changed (e.g. 'old command counted comments as false-positives'). Human-readable reason for the append-only audit trail.")
        val reason: String = "",
    )

    inner class UpdateVerificationTool : SimpleTool<UpdateVerificationArgs>(
        argsType = typeToken<UpdateVerificationArgs>(),
        name = ToolName.UPDATE_VERIFY.value,
        description = "Amend an existing subtask's verification CONTRACT (command/cwd/timeout/expected/machine-checked " +
            "literals) atomically via PlanStore.updatePlan. Allowed for non-COMPLETED subtasks " +
            "(PENDING/IN_PROGRESS/FAILED). Use when the verification method itself was written wrong and needs " +
            "fixing before re-verify. Append-only: the FULL old contract is preserved in the audit trail " +
            "(verificationChanges) — amendments never destroy history."
    ) {
        override suspend fun execute(args: UpdateVerificationArgs): String {
            if (args.command.isBlank()) return "Error: command must not be blank."
            val hasCJK = args.command.any { it.code in 0x4E00..0x9FFF }
            if (hasCJK) return "Error: command 必须是单条可执行命令（ASCII），不能是散文描述。"
            if (args.reason.isBlank()) return "Error: reason must not be blank — the audit trail requires a human-readable explanation of why the contract is being changed."
            var oldSpec: VerificationSpec? = null
            var downgradeRejected = false
            val updated = planStore.updatePlan(args.planId) { p ->
                val st = p.subtasks.getOrNull(args.subtaskIndex) ?: return@updatePlan null
                if (st.status == SubtaskStatus.COMPLETED) return@updatePlan null
                oldSpec = st.verification
                // null = 保留原值：避免"只想改命令"却把既有预期静默清空（数据零销毁原则）
                val newSpec = VerificationSpec(
                    command = args.command,
                    cwd = args.cwd,
                    timeoutSeconds = args.timeoutSeconds,
                    expected = args.expected ?: st.verification.expected,
                    expectStdoutContains = args.expectStdoutContains ?: st.verification.expectStdoutContains,
                    expectStdoutNotContains = args.expectStdoutNotContains ?: st.verification.expectStdoutNotContains,
                )
                // 规则 3 降级检测（引用的是修订前旧契约 st.verification）：
                // 丢命令段 / 减字面量 且 reason 无决策变更关键词 → 拒写（append-only 审计不受影响）
                if (isVerificationDowngrade(st.verification, newSpec) && !reasonIndicatesDecisionChange(args.reason)) {
                    downgradeRejected = true
                    return@updatePlan null
                }
                val change = VerificationChange(
                    oldSpec = st.verification,
                    newSpec = newSpec,
                    reason = args.reason,
                    timestamp = java.time.Instant.now().toString()
                )
                p.copy(subtasks = p.subtasks.map { s ->
                    if (s.index == args.subtaskIndex) s.copy(
                        verification = newSpec,
                        verificationChanges = s.verificationChanges + change
                    ) else s
                })
            }
            if (downgradeRejected)
                return "Error: verification DOWNGRADE rejected — the new contract drops a command clause " +
                    "(./gradlew/assert/test) or reduces expectStdoutContains count without a decision-change " +
                    "justification. Either re-run the original verification when the module is stable " +
                    "(do NOT drop checks to clear a false-fail), or fully restate the contract for a genuinely " +
                    "changed task disposition (and state that in reason with a 'decision/disposition/restated/" +
                    "changed' keyword)."
            if (updated == null)
                return "Error: Plan not found, or subtask ${args.subtaskIndex} missing/COMPLETED (cannot amend)."
            eventBus.emit(MederiEvent(
                type = EventType.PLAN_PROGRESS,
                sessionId = sessionId,
                payload = mapOf(
                    "planId" to args.planId,
                    "action" to "verification-updated",
                    "subtaskIndex" to args.subtaskIndex.toString(),
                    "oldCommand" to (oldSpec?.command ?: ""),
                    "newCommand" to args.command,
                    "reason" to args.reason
                ),
                timestamp = java.time.Instant.now().toString()
            ))
            return "Updated verification for subtask ${args.subtaskIndex}.\n" +
                "Old command: ${oldSpec?.command ?: ""}\n" +
                "New command: ${args.command}\n" +
                "Expected: ${updated.subtasks[args.subtaskIndex].verification.expected}\n" +
                "Reason: ${args.reason}\n" +
                "Change #${updated.subtasks[args.subtaskIndex].verificationChanges.size} recorded in plan audit trail (full old contract preserved)."
        }
    }

    companion object {
        /** write_plan 工具名（TurnExecutor 据此刻意写或找到挂起的计划工具调用）。 */
        val PLAN_TOOL = ToolName.WRITE_PLAN.value

        /** 规则 1：每个子任务的验证契约必须钉至少 1 个期望 stdout 字面量（返错误文本，null = 通过）。
         *  调用方（validatePlan）负责补 "Subtask $i (${st.name}): " 前缀。 */
        internal fun checkLiteralRequired(st: SubtaskArg): String? =
            if (st.verificationExpectStdoutContains.isEmpty())
                "verificationExpectStdoutContains is REQUIRED — pin >=1 literal the output MUST contain " +
                    "(e.g. [\"BEHAVIOR_CHECK_PASS\"]). Use behavior assertions for behavior changes; a compile-success " +
                    "literal alone is not evidence of changed behavior. An empty literal list is rejected: " +
                    "it cannot catch 'the work was not done' (a glitched executor that compiles would false-pass)."
            else null

        /** 文件路径 → gradle 模块名（如 core/src/... → core，src/ 前缀缺失时取第一级目录）。 */
        private fun moduleOf(path: String): String =
            path.substringBefore("/src/").ifBlank { path.substringBefore('/') }

        /** 规则 4：两个 parallelizable 子任务共享 gradle 模块时，同批并行 spawn 的验证编译会竞争——逐对报错。 */
        internal fun checkParallelizableModuleOverlap(subtasks: List<SubtaskArg>): List<String> {
            val errors = mutableListOf<String>()
            val parallelGroups = subtasks.mapIndexed { i, st -> i to st }.filter { it.second.parallelizable }
            for ((i, a) in parallelGroups) for ((j, b) in parallelGroups) {
                if (j <= i) continue
                val aMods = a.targetFiles.map(::moduleOf).toSet()
                val bMods = b.targetFiles.map(::moduleOf).toSet()
                val shared = aMods.intersect(bMods)
                if (shared.isNotEmpty())
                    errors.add(
                        "Subtasks $i (${a.name}) and $j (${b.name}) are both parallelizable but share gradle module(s) " +
                            "$shared — their verification compiles of that module will race. " +
                            "Set parallelizable=false on one, or split into separate batches."
                    )
            }
            return errors
        }

        /** 规则 3：新验证契约是否较旧契约"降级"——丢命令段（./gradlew/assert/test）或字面量数量减少。 */
        internal fun isVerificationDowngrade(old: VerificationSpec, new: VerificationSpec): Boolean {
            val droppedClause = (old.command.contains("./gradlew") && !new.command.contains("./gradlew")) ||
                (old.command.contains("assert") && !new.command.contains("assert")) ||
                (old.command.contains(" test ") && !new.command.contains(" test "))
            val fewerLiterals = new.expectStdoutContains.size < old.expectStdoutContains.size
            return droppedClause || fewerLiterals
        }

        /** 规则 3 放行词：reason 是否含决策变更关键词（decision/disposition/restated/changed，大小写不敏感）。 */
        internal fun reasonIndicatesDecisionChange(reason: String): Boolean =
            reason.contains(Regex("decision|disposition|restated|changed", RegexOption.IGNORE_CASE))

        /**
         * 用户在有计划待批准时直接继续对话的中性结果语料（非批准、非拒绝、非作废）。
         *
         * 这是三条硬规则之一"用户直接回复 ≠ 拒绝"的落库文本：TurnExecutor 在用户继续对话时
         * 把这条作为 write_plan 的 ToolResult 写入历史，让下一轮 AI 视图无悬空 tool call，
         * 并明确"计划仍 PENDING_APPROVAL、用户只是继续了对话"——绝不引导模型追问为什么没批准。
         */
        const val USER_REPLIED_NEUTRAL =
            "User did not approve or reject the plan; they continued the conversation. " +
            "The plan stays PENDING_APPROVAL — respond to their message, do not ask why."
    }

}
````

## `core/src/commonMain/kotlin/xyz/mederi/tools/ProcessTools.kt`

````kotlin
package xyz.mederi.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import xyz.mederi.tools.sandbox.ProcessRegistry

/**
 * 进程管理工具：管理 mederi 通过 bash 启动的进程组。
 *
 * 为什么需要（docs/sandbox-plan.md）：macOS Seatbelt 无 process-signal 操作，沙箱内命令
 * 无法 kill 任何进程（连自己 spawn 的都不行）。list_processes / stop_process 在宿主侧
 * （沙箱外）执行回收，安全边界 = 只能作用于 ProcessRegistry 里 mederi 自己启动的进程组；
 * 查不到的 pid 一律拒绝。沙箱内命令永远无法写入注册表。
 */
class ProcessTools {

    @Serializable
    data class ListProcessesArgs(
        @LLMDescription("Substring filter on the command text (optional, case-insensitive). Empty = list all.")
        val filter: String = ""
    )

    inner class ListProcessesTool : SimpleTool<ListProcessesArgs>(
        argsType = typeToken<ListProcessesArgs>(),
        name = ToolName.LIST_PROCESSES.value,
        description = "List process groups started by mederi via bash that are still running (pid / command / working dir / start time). The pid output is used by stop_process."
    ) {
        override suspend fun execute(args: ListProcessesArgs): String {
            val entries = ProcessRegistry.list()
            val filtered = if (args.filter.isBlank()) entries
                else entries.filter { it.command.contains(args.filter, ignoreCase = true) }
            if (filtered.isEmpty()) return "No mederi-managed processes running."
            return buildString {
                appendLine("PID\tCOMMAND\tWORKDIR\tSTARTED")
                filtered.forEach {
                    appendLine("${it.pid}\t${it.command.replace('\t', ' ')}\t${it.workDir}\t${it.startedAt}")
                }
            }
        }
    }

    @Serializable
    data class StopProcessArgs(
        @LLMDescription("Process pid (from list_processes output).")
        val pid: Long = 0,
        @LLMDescription("true = escalate to SIGKILL if SIGTERM does not stop it; false (default) = SIGTERM only.")
        val force: Boolean = false
    )

    inner class StopProcessTool : SimpleTool<StopProcessArgs>(
        argsType = typeToken<StopProcessArgs>(),
        name = ToolName.STOP_PROCESS.value,
        description = "Stop a process started by mederi via bash (and its whole child tree). Only processes started by mederi (listable via list_processes) can be stopped."
    ) {
        override suspend fun execute(args: StopProcessArgs): String {
            if (args.pid <= 0) return "Error: pid must be a positive number from list_processes."
            val entry = ProcessRegistry.get(args.pid)
                ?: return "Error: pid ${args.pid} is not a mederi-managed process (nothing to stop). " +
                    "Only processes started via bash and still running are manageable."
            if (!entry.isAlive()) {
                ProcessRegistry.remove(args.pid)
                return "Process ${args.pid} has already exited."
            }
            val action = ProcessRegistry.killGroup(entry.pid, entry.pgid, force = args.force)

            var alive = entry.isAlive()
            var waited = 0L
            while (alive && !args.force && waited < 4000) {
                delay(200)
                waited += 200
                alive = entry.isAlive()
            }
            if (alive && !args.force) {
                return "Warning: sent SIGTERM but the process group is still alive. " +
                    "Retry with force=true to SIGKILL it.\n($action)"
            }
            if (alive) {
                // force 已发 SIGKILL；再给 500ms 让内核回收
                delay(500)
                alive = entry.isAlive()
            }
            if (alive) return "Warning: process group still alive after kill. It may be unkillable (e.g. D state).\n($action)"
            ProcessRegistry.remove(args.pid)
            return "Stopped pid ${args.pid}.\n($action)"
        }
    }
}

````

## `core/src/commonMain/kotlin/xyz/mederi/tools/ShellTools.kt`

````kotlin
package xyz.mederi.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable
import java.io.File
import java.net.URLEncoder
import xyz.mederi.tools.background.BackgroundTaskManager
import xyz.mederi.tools.sandbox.CommandSandbox
import xyz.mederi.tools.sandbox.ProcessRegistry

/**
 * 浏览器 UA（桌面 Chrome 120 / macOS），curl 抓取时伪装常规浏览器，
 * 规避部分站点对无头/curl 默认 UA 的拦截或差异返回。
 */
private const val BROWSER_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

/**
 * Shell 命令执行工具集。
 *
 * 命令在项目主目录（第一个目录）下执行，经 [CommandSandbox] 包装：
 * - macOS Seatbelt / Linux bubblewrap+seccomp：受限模式白名单可写、默认禁止命令联网，读全盘
 * - 受限后端不可用：返回原始错误并阻止命令；完全访问或已批准off才裸跑
 * - shell 探测链：bash → sh（Windows: bash.exe → cmd /c）
 *
 * 宿主权限由 ExecutionPermissions 判断；前后台命令与验证共用一次性授权入口。
 *
 * @param allowedDirectories 项目目录列表（绝对路径），命令的工作目录为第一个目录。
 * @param sandbox 命令沙箱（每 turn 由 ToolFactory 新建）。
 */
class ShellTools(
    private val allowedDirectories: List<String>,
    private val sandbox: CommandSandbox? = null,
    private val backgroundTaskManager: BackgroundTaskManager? = null,
    private val sessionId: String = "",
    private val executionPermissions: xyz.mederi.tools.sandbox.ExecutionPermissions? = null,
    private val webReader: suspend (String, Int) -> String = { url, timeout ->
        xyz.mederi.http.WebHttpReader.get(url, timeout, BROWSER_UA)
    },
    /** RF-07/R11: 工作区执行租约管理器——后台命令在 turn 结束后仍会写盘，必须持有进程生命周期租约。 */
    private val leaseManager: xyz.mederi.workspace.WorkspaceLeaseManager? = null,
) {

    /**
     * 命令执行结果（输出 + exit code + 超时标志，供 BashTool 格式化和 VerifyTools 判定）。
     *
     * [timedOut] 为 true 表示进程未在 [ShellTools.runCommand] 的 timeoutSeconds 内完成被强制回收——
     * 与「进程正常退出但 exitCode 非零」的真实失败区分开（VerifyTools 三态判定依赖此标志）。
     */
    data class CommandResult(val output: String, val exitCode: Int, val timedOut: Boolean = false)

    /**
     * 执行一条 shell 命令，返回 [CommandResult]。
     *
     * BashTool 和 VerifyTools 的自动验证共用此方法——
     * 沙箱包装、工作目录、输出读取线程、超时处理全一致。
     *
     * @param cwd 命令工作目录（绝对路径）；null 或空白时回退到项目主目录（allowedDirectories.first()）。
     */
    private data class PreparedCommand(
        val wrapped: CommandSandbox.WrappedCommand,
        val workDir: File,
        val grant: xyz.mederi.tools.sandbox.ExecutionPermissions.Grant?,
    )

    private suspend fun prepareCommand(command: String, cwd: String?, sandboxMode: String, tool: String): PreparedCommand {
        val rawDir = cwd?.takeIf { it.isNotBlank() }?.let(::File)
        val project = File(allowedDirectories.first())
        val workDir = (if (rawDir == null) project else if (rawDir.isAbsolute) rawDir else File(project, rawDir.path)).canonicalFile
        require(workDir.isDirectory) { "Working directory does not exist: $workDir" }
        require(sandboxMode in listOf("default", "network", "off")) { "sandbox must be default, network, or off" }
        val grant = executionPermissions?.authorizeCommand(command, workDir.path, sandboxMode, tool)
        // 缺少授权上下文不能构成裸跑后门；仅显式FULL或已批准off能解除OS沙盒。
        if (executionPermissions == null && sandboxMode != "default") {
            throw xyz.mederi.api.exception.MederiStateException("An execution approval channel is required for elevated commands.")
        }
        // R02: 把经校验的工作区凭证传到实际启动后端——精确产物根和映射拒绝规则沿同一凭证应用。
        val wsRoot = executionPermissions?.verifiedWorkspaceRootProvider?.invoke()
        val runner = grant?.let { CommandSandbox(allowedDirectories, it.settings.extraWritablePaths,
            executionPermissions!!.manager.protectedDirectories, it.settings.deniedPaths, wsRoot) }
            ?: sandbox ?: CommandSandbox(allowedDirectories, emptyList())
        val full = grant?.settings?.mode == xyz.mederi.tools.sandbox.ExecutionPermissionMode.FULL_ACCESS
        val wrapped = runner.wrap(command, networkAccess = sandboxMode == "network", unsandboxed = full || sandboxMode == "off")
        grant?.let { executionPermissions!!.checkGrant(it) }
        return PreparedCommand(wrapped, workDir, grant)
    }

    suspend fun runCommand(
        command: String, timeoutSeconds: Int = 120, cwd: String? = null,
        sandboxMode: String = "default", toolName: String = "bash",
    ): CommandResult {
        if (command.isBlank()) return CommandResult("Error: command is empty", -1)
        require(timeoutSeconds > 0) { "timeout_seconds must be positive" }
        val prepared = try { prepareCommand(command, cwd, sandboxMode, toolName) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { return CommandResult("[sandbox] ${failure.message}", -1) }
        val wrapped = prepared.wrapped
        prepared.grant?.let { executionPermissions!!.checkGrant(it) }
        val process = ProcessBuilder(wrapped.argv).directory(prepared.workDir).redirectErrorStream(true).start()
        ProcessRegistry.register(process.pid(), if (wrapped.processGroupLeader) process.pid() else null, command, prepared.workDir.path)
        val output = java.util.concurrent.FutureTask {
            process.inputStream.bufferedReader().use { reader ->
                val captured = StringBuilder()
                val buffer = CharArray(4096)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (captured.length < 4 * 1024 * 1024) captured.append(buffer, 0, minOf(count, 4 * 1024 * 1024 - captured.length))
                }
                captured.toString()
            }
        }
        Thread(output, "mederi-command-output").apply { isDaemon = true; start() }
        try {
            val completed = kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) {
                process.waitFor(timeoutSeconds.toLong(), java.util.concurrent.TimeUnit.SECONDS)
            }
            if (!completed) {
                if (wrapped.processGroupLeader) ProcessRegistry.killGroup(process.pid(), process.pid(), true)
                process.destroyForcibly()
            }
            val text = kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { output.get(2, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault("[output collection did not finish]")
            }
            val hint = if (completed && process.exitValue() != 0) {
                "\nIf sandbox permissions are the cause, request sandbox=network for networking, or sandbox=off for this exact unsandboxed command. User approval is required."
            } else ""
            return CommandResult((wrapped.warning ?: "") + text + hint, if (completed) process.exitValue() else -1, !completed)
        } finally {
            // 取消同样收割命令树，不能留下继续写盘的后台孤儿。
            if (process.isAlive) {
                if (wrapped.processGroupLeader) ProcessRegistry.killGroup(process.pid(), process.pid(), true)
                process.destroyForcibly()
            }
            process.inputStream.close()
            output.cancel(true)
        }
    }

    /**
     * 非阻塞命令执行：用与 [runCommand] 相同的沙箱包装启动进程，但不等待——
     * 进程交给 [BackgroundTaskManager] 的后台协程跟踪（waitFor + 输出读取 + 终态事件），
     * 立即返回 task ID，AI 可继续与用户对话。
     */
    private suspend fun startBackgroundCommand(command: String, timeoutSeconds: Int, cwd: String?, sandboxMode: String): String {
        if (command.isBlank()) return "Error: command is empty"

        return runCatching {
            require(timeoutSeconds > 0) { "timeout_seconds must be positive" }
            val prepared = prepareCommand(command, cwd, sandboxMode, "bash")
            val wrapped = prepared.wrapped
            val workDir = prepared.workDir
            prepared.grant?.let { executionPermissions!!.checkGrant(it) }

            // R11: 后台命令在父 turn 结束后仍可能写盘——为进程生命周期持有执行租约，
            // 归档/配额的 withManagementIntent 因此会等待后台进程真正退出后才快照/强删 checkout。
            // 租约根取经校验的工作区 checkoutRoot（无权限上下文时回退到项目主目录）。
            // 若工作区正处独占管理意图（如归档启动），acquire 抛 WORKSPACE_BUSY——此时不应启动
            // 会写该目录的后台命令，错误原样上抛（runCatching 转成错误串，process 未启动）。
            val leaseRoot = executionPermissions?.verifiedWorkspaceRootProvider?.invoke()?.checkoutRoot?.toString()
                ?: allowedDirectories.firstOrNull()
            val lease = leaseManager?.let { lm ->
                leaseRoot?.let { root -> lm.acquireExecutionLease(root) }
            }

            var process: Process? = null
            var leaseReleased = false
            try {
                val builder = ProcessBuilder(wrapped.argv)
                builder.directory(workDir)
                builder.redirectErrorStream(true)

                process = builder.start()
                ProcessRegistry.register(
                    pid = process.pid(),
                    pgid = if (wrapped.processGroupLeader) process.pid() else null,
                    command = command,
                    workDir = workDir.absolutePath
                )

                val taskId = backgroundTaskManager!!.trackCommand(
                    sessionId = sessionId,
                    process = process,
                    command = command,
                    workDir = workDir.absolutePath,
                    timeoutSeconds = timeoutSeconds
                )
                if (lease != null) {
                    val hookOk = backgroundTaskManager.attachTerminalHook(taskId) { lease.close() }
                    if (hookOk) {
                        leaseReleased = true
                    } else {
                        // attach 失败是异常态（entry 应刚由 trackCommand 创建）；
                        // 立即释放 lease，接受进程可能仍在写的极短窗口。
                        lease.close()
                        leaseReleased = true
                    }
                }
                (wrapped.warning ?: "") +
                    "Background task started: $taskId\nCommand: $command\nYou will be notified with the output when it completes. You can continue working with the user."
            } catch (e: Exception) {
                // start 之后 track/register 失败：收割已启动进程，lease 由 finally 释放。
                process?.let { p ->
                    p.destroyForcibly()
                    runCatching { ProcessRegistry.remove(p.pid()) }
                }
                throw e
            } finally {
                // lease 在 attachTerminalHook 成功时由 hook 释放（leaseReleased=true）；
                // 其他路径（start 前失败 / start 后失败 / attach 失败已立即 close）在此统一释放。
                if (lease != null && !leaseReleased) {
                    lease.close()
                }
            }
        }.getOrElse { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            "Error starting background command: ${e.message}"
        }
    }

    @Serializable
    data class BashArgs(
        @LLMDescription("Shell command to execute.")
        val command: String = "",
        @LLMDescription("Working directory (absolute or project-relative path). Empty = project root.")
        val cwd: String = "",
        @LLMDescription("Timeout in seconds; default 120.")
        @kotlinx.serialization.SerialName("timeout_seconds")
        val timeoutSeconds: Int = 120,
        @LLMDescription("Set true for long-running commands (compilation, tests, servers). The command runs in the background; you get notified with the output when it completes and can continue working meanwhile. Default false blocks until the command finishes.")
        val background: Boolean = false,
        @LLMDescription("Set true for build/compile commands (./gradlew, mvn, make, cargo build, etc.). The tool holds a per-project lock so concurrent compile commands from parallel subagents queue and wait instead of colliding (one compile at a time per project). Waiting does not count against timeout_seconds. Only effective when background=false. Default false.")
        val compilation: Boolean = false,
        @LLMDescription("default: restricted filesystem and no command networking. network: request networking for this command, keeping filesystem restrictions. off: request unsandboxed execution for this exact command. Elevated requests require user approval unless Full Access is selected.")
        val sandbox: String = "default",
    )

    inner class BashTool : SimpleTool<BashArgs>(
        argsType = typeToken<BashArgs>(),
        name = ToolName.BASH.value,
        description = "Execute a shell command in the given working directory (empty cwd = project root) and return its stdout and stderr. In Needs Approval mode, writes are restricted to project/temp/explicit writable roots and command networking is disabled. Use sandbox=network or sandbox=off to request a one-time permission grant. Set background=true for long-running commands (compilation, tests, servers) — runs in background, you get notified with output when done, and can continue working meanwhile. Set compilation=true for build/compile commands — concurrent compiles from parallel subagents queue per-project (one at a time) instead of colliding; waiting does not count against timeout_seconds. Default background=false blocks until the command finishes."
    ) {
        override suspend fun execute(args: BashArgs): String {
            if (args.background && backgroundTaskManager != null) {
                return startBackgroundCommand(args.command, args.timeoutSeconds, args.cwd.takeIf { it.isNotBlank() }, args.sandbox)
            }
            val run: suspend () -> String = {
                val result = runCommand(args.command, args.timeoutSeconds, args.cwd.takeIf { it.isNotBlank() }, args.sandbox)
                val exitPrefix = if (result.exitCode != 0) "[exit code: ${result.exitCode}]\n" else ""
                exitPrefix + result.output
            }
            return if (args.compilation) {
                val projectKey = File(allowedDirectories.first()).canonicalFile.absolutePath
                CompilationCoordinator.acquire(projectKey).use { run() }
            } else run()
        }
    }

    // ===== web 工具共享基础 =====

    /** 调研专用只读 HTTP：不经过 shell；超时、重定向及响应字节上限由 WebHttpReader 执行。 */
    private suspend fun httpGet(url: String, timeoutSeconds: Int): CommandResult = try {
        CommandResult(webReader(url, timeoutSeconds), 0)
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (failure: Exception) { CommandResult("Web request failed: ${failure.message}", -1) }

    /** 按 maxChars 截断，超长时追加省略提示（不丢信息：提示写明省略了多少字符）。 */
    private fun truncate(raw: String, maxChars: Int): String =
        if (raw.length > maxChars) {
            raw.take(maxChars) + "\n... [truncated, ${raw.length - maxChars} chars omitted]"
        } else {
            raw
        }

    /**
     * 解码常用 HTML 实体：命名实体（&amp; &lt; &gt; &#39; &apos; &quot; &nbsp; &#0183; &ndash; &mdash;）
     * + 十进制数字实体（&#\d+;）。尽力而为，未知实体原样保留，解码失败安全降级。
     */
    private fun decodeHtmlEntities(text: String): String {
        var result = text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&quot;", "\"")
            .replace("&nbsp;", " ")
            .replace("&#0183;", "·")
            .replace("&ndash;", "-")
            .replace("&mdash;", "-")
        result = Regex("""&#(\d+);""").replace(result) { m ->
            val code = m.groupValues[1].toIntOrNull()
            if (code != null && code in 0..0xFFFF) code.toChar().toString() else m.value
        }
        return result
    }

    /** 清洗 HTML 片段：剥离标签 + 解码实体 + 去首尾空白（用于搜索结果的 title/URL/snippet 提取）。 */
    private fun cleanHtmlText(raw: String): String {
        val stripped = Regex("""<[^>]*>""").replace(raw, "")
        return decodeHtmlEntities(stripped).trim()
    }

    @Serializable
    data class WebSearchArgs(
        @LLMDescription("Search query keywords. The tool searches Bing and returns structured results (title, URL, snippet for each hit).")
        val query: String = "",
        @LLMDescription("Maximum characters to return; default 8000. Truncates response to fit context.")
        @kotlinx.serialization.SerialName("max_chars")
        val maxChars: Int = 8000,
        @LLMDescription("Timeout in seconds; default 30.")
        @kotlinx.serialization.SerialName("timeout_seconds")
        val timeoutSeconds: Int = 30
    )

    /**
     * 只读 Bing 搜索工具（仅注册给 RESEARCHER 子代理）。
     *
     * 底层经 HTTP GET 拉取 Bing 搜索结果页，正则结构化提取每条结果的
     * title / URL / snippet——提取是尽力而为：提取失败（0 条结果）时兜底返回原始 HTML 截断，不丢信息。
     * 只读、无写、无通用 shell。
     * 安全约束：
     * - query 不得为空
     * - query 不得包含单引号（防 shell 注入——单引号内 shell 不展开元字符）
     * - 不传 -o（不写文件）、不传 -d/-X（不发数据、不改方法）
     *
     * 复用 [ShellTools.httpGet]；独立于命令网络授权，不启动子进程。
     */
    inner class WebSearchTool : SimpleTool<WebSearchArgs>(
        argsType = typeToken<WebSearchArgs>(),
        name = ToolName.WEB_SEARCH.value,
        description = "Search the web via Bing. Returns structured results: title, URL, snippet for each hit. " +
            "Read-only: no POST/PUT/DELETE, no file writes. Use for finding documentation, API references, " +
            "release notes, and current information."
    ) {
        override suspend fun execute(args: WebSearchArgs): String {
            val query = args.query.trim()
            if (query.isEmpty()) return "Error: query must not be empty."


            // URL 编码 query 后拼 Bing 搜索 URL（q 参数 + count=20）
            val encoded = URLEncoder.encode(query, "UTF-8")
            val bingUrl = "https://www.bing.com/search?q=$encoded&count=20"

            val result = httpGet(bingUrl, args.timeoutSeconds)
            if (result.exitCode != 0) return "[exit code: ${result.exitCode}]\n${result.output}"
            if (result.output.isBlank()) {
                return "Error: Bing returned empty response. The search may have failed or Bing is unreachable."
            }

            val hits = extractBingResults(result.output)
            val raw = if (hits.isEmpty()) {
                // 正则提取失败兜底：返回原始 HTML 截断，不丢信息
                result.output
            } else {
                buildString {
                    append("Search results for \"$query\":\n")
                    for ((index, hit) in hits.withIndex()) {
                        append("\n${index + 1}. ${hit.title}\n")
                        append("   URL: ${hit.url}\n")
                        append("   Snippet: ${hit.snippet}\n")
                    }
                }
            }
            return truncate(raw, args.maxChars.coerceAtLeast(100))
        }
    }

    @Serializable
    data class WebFetchArgs(
        @LLMDescription("URL to fetch via HTTP GET (http or https only). The tool fetches page content and cleans HTML (strips scripts, styles, tags) to return readable text.")
        val url: String = "",
        @LLMDescription("Maximum characters to return; default 8000. Truncates response to fit context.")
        @kotlinx.serialization.SerialName("max_chars")
        val maxChars: Int = 8000,
        @LLMDescription("Timeout in seconds; default 30.")
        @kotlinx.serialization.SerialName("timeout_seconds")
        val timeoutSeconds: Int = 30
    )

    /**
     * 只读 URL 抓取工具（仅注册给 RESEARCHER 子代理）。
     *
     * 底层经 HTTP GET 拉取页面，清洗 HTML（移除 script/style/svg/noscript/head 块与自闭合标签、
     * 剥离剩余标签、解码实体、压缩空白）后返回可读文本。只读、无写、无通用 shell。
     * 安全约束：
     * - URL 必须为 http/https 协议（拒绝 file://、ftp://、data: 等）
     * - URL 不得包含单引号（防 shell 注入——单引号内 shell 不展开元字符）
     * - 不传 -o（不写文件）、不传 -d/-X（不发数据、不改方法）
     *
     * 复用 [ShellTools.httpGet]；独立于命令网络授权，不启动子进程。
     */
    inner class WebFetchTool : SimpleTool<WebFetchArgs>(
        argsType = typeToken<WebFetchArgs>(),
        name = ToolName.WEB_FETCH.value,
        description = "Fetch a URL via HTTP GET and return cleaned text content. Strips scripts, styles, SVG, " +
            "and HTML tags to return readable text. Read-only: no POST/PUT/DELETE, no file writes."
    ) {
        override suspend fun execute(args: WebFetchArgs): String {
            val url = args.url.trim()
            if (url.isEmpty()) return "Error: url must not be empty."

            // 协议白名单：只允许 http/https（拒绝 file://、ftp://、data: 等）
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return "Error: url must start with http:// or https://"
            }

            // 安全：拒绝含单引号的 URL（单引号是 shell 引用的唯一危险字符）

            val result = httpGet(url, args.timeoutSeconds)
            if (result.exitCode != 0) return "[exit code: ${result.exitCode}]\n${result.output}"
            if (result.output.isBlank()) return "Error: empty response from server."

            val cleaned = cleanHtml(result.output)
            return truncate(cleaned, args.maxChars.coerceAtLeast(100))
        }
    }

    /**
     * HTML 清理：移除 script/style/svg/noscript/head 整块、移除自闭合/空标签、
     * 剥离所有剩余 HTML 标签、解码 HTML 实体、压缩空白，返回可读文本。
     */
    private fun cleanHtml(raw: String): String {
        var text = raw
        // 移除整块标签（DOT_MATCHES_ALL 跨行 + IGNORE_CASE，防内容里出现同名标签干扰）
        for (tag in listOf("script", "style", "svg", "noscript", "head")) {
            text = Regex("""<\s*$tag\b[^>]*>.*?</\s*$tag\s*>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                .replace(text, "")
        }
        // 移除自闭合/无内容标签
        text = Regex("""<(?:link|meta|img|br|hr|source|input|col|area|base|param)[^>]*/?>""", RegexOption.IGNORE_CASE)
            .replace(text, "")
        // 剥离所有剩余 HTML 标签 → 空格
        text = Regex("""<[^>]+>""").replace(text, " ")
        // 解码 HTML 实体
        text = decodeHtmlEntities(text)
        // 压缩空白：连续空格/制表符 → 单空格；连续空行（含行首尾空白）→ 单换行；strip 首尾空白
        text = Regex("""[ \t]+""").replace(text, " ")
        text = Regex("""\s*\n\s*""").replace(text, "\n")
        return text.trim()
    }

    /** Bing 单条搜索结果（正则提取产物）。 */
    private data class SearchHit(val title: String, val url: String, val snippet: String)

    /**
     * 尽力而为地正则提取 Bing 搜索结果：先按 `<li class="b_algo">` 切块，
     * 每块再提取 title（h2>a）/ URL（cite）/ snippet（p.b_lineclamp2）。
     * 一个都提取不到时返回空列表（调用方兜底返回原始 HTML 截断）。
     */
    private fun extractBingResults(html: String): List<SearchHit> {
        val blockRegex = Regex("""<li class="b_algo".*?</li>""", RegexOption.DOT_MATCHES_ALL)
        val titleRegex = Regex("""<h2><a[^>]*>(.*?)</a></h2>""", RegexOption.DOT_MATCHES_ALL)
        val urlRegex = Regex("""<cite[^>]*>(.*?)</cite>""", RegexOption.DOT_MATCHES_ALL)
        val snippetRegex = Regex("""<p class="b_lineclamp2[^"]*"[^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)

        return blockRegex.findAll(html)
            .map { block ->
                val blockHtml = block.value
                SearchHit(
                    title = cleanHtmlText(titleRegex.find(blockHtml)?.groupValues?.get(1).orEmpty()),
                    url = cleanHtmlText(urlRegex.find(blockHtml)?.groupValues?.get(1).orEmpty()),
                    snippet = cleanHtmlText(snippetRegex.find(blockHtml)?.groupValues?.get(1).orEmpty())
                )
            }
            .filter { it.title.isNotEmpty() || it.url.isNotEmpty() || it.snippet.isNotEmpty() }
            .toList()
    }
}

````

## `core/src/commonMain/kotlin/xyz/mederi/tools/VerifyTools.kt`

````kotlin
package xyz.mederi.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.Serializable
import xyz.mederi.domain.model.EventType
import xyz.mederi.domain.model.MederiEvent
import xyz.mederi.plan.PlanStore
import xyz.mederi.plan.RootCause
import xyz.mederi.plan.Subtask
import xyz.mederi.plan.SubtaskStatus
import xyz.mederi.plan.VerifyStatus
import xyz.mederi.plan.VerificationResult
import xyz.mederi.plan.VerificationSpec
import xyz.mederi.tools.ShellTools.CommandResult
import java.time.Instant

/** 自动验证命令的三态判定结果：exit 0→PASS；超时→TIMEOUT；非零非超时→FAIL。 */
internal enum class VerifyCommandOutcome { PASS, FAIL, TIMEOUT }

/** 三态判定：exit 0→PASS；超时→TIMEOUT；非零非超时→FAIL。 */
internal fun verifyCommandOutcome(exitCode: Int, timedOut: Boolean): VerifyCommandOutcome = when {
    timedOut -> VerifyCommandOutcome.TIMEOUT
    exitCode == 0 -> VerifyCommandOutcome.PASS
    else -> VerifyCommandOutcome.FAIL
}

/** 字面量预期核对结果（机器硬校验）。[ok] = 无缺失的必须项、也无意外出现的禁止项。 */
internal data class LiteralCheckResult(
    val missingContains: List<String>,
    val hitNotContains: List<String>
) {
    val ok: Boolean get() = missingContains.isEmpty() && hitNotContains.isEmpty()
}

/**
 * 字面量预期核对（机器硬校验，2026-09-24）。
 *
 * 抓的是"命令 exit 0 但计划预期没达到"——例：`pytest` 只跑通 1 条也 exit 0，
 * 但计划写明必须输出 "3 passed"，此时必须判 FAIL，不能靠模型自觉。
 */
internal fun checkOutputLiterals(
    output: String,
    mustContain: List<String>,
    mustNotContain: List<String>
): LiteralCheckResult = LiteralCheckResult(
    missingContains = mustContain.filterNot { output.contains(it) },
    hitNotContains = mustNotContain.filter { output.contains(it) }
)

/**
 * 规则 2（空产出门禁，2026-10）：executor 报零改动且子任务目标含真实文件 → 拒绝 PASS。
 *
 * 防 S15 事故：执行器 glitch 空报告、零改动、但验证命令（编译）仍然过——机器会放行，
 * 形成"假绿"。这里在机器门之前直接拒：没有产出物就不可能实现子任务。
 *
 * 豁免：targetFiles 全是目录路径（endsWith('/')）或 targetFiles 为空——
 * 这些是门禁/验证型子任务，不产出文件。
 */
internal fun shouldRejectEmptyOutputPass(subtask: Subtask): Boolean =
    subtask.executorTouchedFiles.isEmpty() &&
        subtask.targetFiles.any { !it.endsWith('/') && it.contains('.') }

/** 规则 2 拒绝消息：直接逼回 FAIL(IMPLEMENTATION) + converge_plan。 */
internal fun emptyOutputRejectionMessage(subtask: Subtask): String {
    val realFiles = subtask.targetFiles.filter { !it.endsWith('/') && it.contains('.') }
    return "Error: cannot declare PASS — the executor reported NO touched files " +
        "but this is an implementation subtask (targetFiles include real files: ${realFiles.joinToString(", ")}). " +
        "This is the signature of a glitched/empty-report executor that did nothing. " +
        "Re-examine: if the executor truly produced no changes, declare FAIL with rootCause=IMPLEMENTATION " +
        "and call converge_plan to re-run. " +
        "(Gate/verification-only subtasks with directory or empty targetFiles are exempt.)"
}

/**
 * 验证工具。主代理在子任务执行完成后调用 verify_subtask。
 *
 * **机器硬校验（2026-09-24 改造）**：只要子任务存了 verification 命令，**无条件**执行它
 * （不再只在模型声明 PASS 时才跑）——"验证被正确执行"不能依赖模型自觉：
 *
 * 1. exit 非零 → 机器 FAIL（模型声明 PASS 时拒绝存储，返回真实输出要求重判）
 * 2. exit 0 但缺 `expectStdoutContains` 字面量 → 机器 FAIL（命令通过但预期没达到）
 * 3. exit 0 但命中 `expectStdoutNotContains` 字面量 → 机器 FAIL
 * 4. 超时 → inconclusive（不存 PASS，也不硬拒——超时可能是缓存未热）
 * 5. 机器判定 FAIL 而模型坚持未通过（矛盾）→ 按未通过记录，标 `machineMismatch`，
 *    并要求主代理**如实告知用户**这是异常态（机器证明预期达成、模型判定不同）
 *
 * **归因两分支**：未通过时必须给 rootCause，且判定顺序是硬性的——
 * 先对照真实代码确认 executor 是否照 spec 执行（IMPLEMENTATION 错），
 * 只有在实现无误时才可归因于计划本身（PLAN 错）。
 * 验证方法对不对是计划的问题，不是验证器的问题。
 *
 * **真实证据落盘**：[VerificationResult.commandOutput]/[VerificationResult.commandExitCode]
 * 存机器真实输出与退出码，与模型自述的 evidence 分开——审计不经模型转述。
 */
class VerifyTools(
    private val sessionId: String,
    private val planStore: PlanStore,
    private val eventBus: MutableSharedFlow<MederiEvent>,
    private val shellTools: ShellTools? = null
) {

    @Serializable
    data class VerifySubtaskArgs(
        @LLMDescription("Plan ID.")
        val planId: String,
        @LLMDescription("Subtask index (0-based).")
        val subtaskIndex: Int,
        @LLMDescription("Verification result: PASS, PARTIAL, or FAIL.")
        val status: String,
        @LLMDescription("What you checked and what you found. Must reference real code or command output, not assumptions.")
        val evidence: String,
        @LLMDescription(
            "Required for PARTIAL/FAIL. Root cause — decide the order STRICTLY: first read the real code and " +
                "confirm whether the executor followed the spec (if it did not → IMPLEMENTATION); only if it " +
                "did → PLAN (the plan's own logic / verification method / expectation is wrong)."
        )
        val rootCause: String? = null,
        @LLMDescription("For FAIL: how to fix. Required when status is FAIL.")
        val remediation: String? = null,
        @LLMDescription("default runs the stored verification command in the sandbox. network/off requests one-time user permission for that exact verification command; it never changes the stored verification contract.")
        val sandbox: String = "default"
    )

    inner class VerifySubtaskTool : SimpleTool<VerifySubtaskArgs>(
        argsType = typeToken<VerifySubtaskArgs>(),
        name = ToolName.VERIFY_SUBTASK.value,
        description = "Verifies a subtask result against the plan's stored verification contract. The contract's " +
            "command is ALWAYS executed and its exit code + machine-checked output literals decide the machine " +
            "verdict; declaring PASS while the machine verdict fails is refused. Records PASS/PARTIAL/FAIL with " +
            "a required root cause (IMPLEMENTATION vs PLAN) when not passing."
    ) {
        override suspend fun execute(args: VerifySubtaskArgs): String {
            val plan = planStore.load(args.planId)
                ?: return "Error: Plan not found: ${args.planId}"
            val subtask = plan.subtasks.getOrNull(args.subtaskIndex)
                ?: return "Error: Subtask index ${args.subtaskIndex} not found (0..${plan.subtasks.size - 1})"

            val verifyStatus = try {
                VerifyStatus.valueOf(args.status)
            } catch (e: IllegalArgumentException) {
                return "Error: Invalid status '${args.status}'. Use PASS, PARTIAL, or FAIL."
            }

            val rootCause = args.rootCause?.let {
                try { RootCause.valueOf(it) } catch (e: IllegalArgumentException) { null }
            }

            if (verifyStatus != VerifyStatus.PASS && rootCause == null) {
                return "Error: rootCause is required when status is PARTIAL or FAIL. Use IMPLEMENTATION " +
                    "(the spec said it clearly but the execution did not deliver) or PLAN (the execution " +
                    "matched the spec, but the plan's own logic/verification/expectation is wrong). " +
                    "Decide in this order, strictly: first confirm against the real code that the executor " +
                    "followed the spec; only if it did may you attribute the failure to the plan."
            }

            if (verifyStatus == VerifyStatus.FAIL && args.remediation.isNullOrBlank()) {
                return "Error: remediation is required when status is FAIL."
            }

            // 规则 2（空产出门禁，2026-10）：executor 报零改动 + 子任务有真实文件目标 → 拒 PASS。
            // 防 S15 事故：空报告 + 编译验证通过 = 假绿。没有产出物就不可能实现子任务。
            if (verifyStatus == VerifyStatus.PASS && shouldRejectEmptyOutputPass(subtask)) {
                return emptyOutputRejectionMessage(subtask)
            }

            // 无条件执行验证命令（2026-09-24）：不再只在模型声明 PASS 时才跑——"验证被正确执行"
            // 不能依赖模型自觉。命令退出码 + 字面量预期是机器判据，真实输出一律摆到桌面。
            val spec = subtask.verification
            val verifyCmd = spec.command.takeIf { it.isNotBlank() }
            val autoVerify: CommandResult? = if (verifyCmd != null) {
                val shell = shellTools
                    ?: return "Error: this subtask has a verification command but the shell runner is not " +
                        "available in this assembly. That is a configuration fault, not a verification result — " +
                        "refusing to record anything. Do not declare PASS."
                try {
                    shell.runCommand(verifyCmd, spec.timeoutSeconds ?: 30, spec.cwd, args.sandbox, "verify_subtask")
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    return "Error: verification command could not execute: ${failure.message}. No verification result was recorded."
                }
            } else null

            val literalCheck = autoVerify?.let {
                checkOutputLiterals(it.output, spec.expectStdoutContains, spec.expectStdoutNotContains)
            }
            // 机器判定 = exit 码三态 + 字面量预期（只有 exit 0 才继续查字面量）
            val machineOutcome: VerifyCommandOutcome? = autoVerify?.let {
                val base = verifyCommandOutcome(it.exitCode, it.timedOut)
                if (base == VerifyCommandOutcome.PASS && literalCheck?.ok == false) VerifyCommandOutcome.FAIL
                else base
            }

            // 模型声明 PASS：机器判定必须放行（这是防"命令失败/预期未达成却盖章 PASS"的硬门）
            if (verifyStatus == VerifyStatus.PASS) {
                when (machineOutcome) {
                    VerifyCommandOutcome.TIMEOUT -> return "Verification command (auto-executed) TIMED OUT " +
                        "(inconclusive):\n  $verifyCmd\n" +
                        "Output:\n${autoVerify.output.take(2000)}\n\n" +
                        "The verification timed out instead of failing — PASS is not stored. " +
                        "Re-run verify_subtask after warming the cache, or accept manual evidence."
                    VerifyCommandOutcome.FAIL -> return "Verification command (auto-executed from plan):\n" +
                        "  $verifyCmd\n" + describeMachineFailure(autoVerify, literalCheck, spec) + "\n\n" +
                        "The result does NOT match the plan's criteria — do NOT declare PASS. Re-examine the " +
                        "real output and call verify_subtask again with the correct status (FAIL with " +
                        "remediation + rootCause, or PARTIAL with rootCause)."
                    VerifyCommandOutcome.PASS, null -> {} // 机器放行（或无命令）：继续正常存储
                }
            }

            // 机器判定通过、模型却坚持未通过 → 异常态。用户裁定：算未通过（不 PASS），
            // 但必须让主代理如实告知用户"机器证明预期已达成、模型判定不同"。
            val contradiction = verifyStatus != VerifyStatus.PASS &&
                machineOutcome == VerifyCommandOutcome.PASS

            // scope 越界检查（信任 AI 哲学：只报告、不硬拒 PASS/FAIL 判定）：
            // 执行器实际改动的文件（Subtask.executorTouchedFiles）vs 子任务声明的 targetFiles——
            // 越界文件追加进 evidence，供主代理判断（越界可能是合理的：用户并行工作、共享文件）。
            val outOfScopeFiles = subtask.executorTouchedFiles.filter { touched ->
                subtask.targetFiles.none { target ->
                    target.endsWith(touched) || touched.endsWith(target)
                }
            }
            val evidenceWithScope = if (outOfScopeFiles.isEmpty()) args.evidence else
                args.evidence + "\n\n[scope] 执行器改动超出 targetFiles 的文件: " + outOfScopeFiles.joinToString(", ")

            val result = VerificationResult(
                status = verifyStatus,
                evidence = evidenceWithScope,
                rootCause = rootCause,
                remediation = args.remediation,
                // 机器真实输出/退出码（截断存储，审计用；未经模型转述）
                commandOutput = autoVerify?.output?.take(4000),
                commandExitCode = autoVerify?.takeIf { !it.timedOut }?.exitCode,
                machineMismatch = contradiction
            )

            val newStatus = when (verifyStatus) {
                VerifyStatus.PASS -> SubtaskStatus.COMPLETED
                VerifyStatus.PARTIAL, VerifyStatus.FAIL -> SubtaskStatus.FAILED
            }

            // 原子写入验证结果与子任务状态：工具支持并行调度，同消息多次 verify_subtask 时，
            // 裸 load→copy→save 会让后写的 plan 覆盖先写的结果（验证结果静默丢失）。
            // 全部子任务 PASS 时顺带收尾：plan status → COMPLETED（随后归档）。
            val finalizedPlan = planStore.updatePlan(args.planId) { p ->
                if (p.subtasks.getOrNull(args.subtaskIndex) == null) return@updatePlan null
                val withResult = p.copy(
                    subtasks = p.subtasks.map { st ->
                        if (st.index == args.subtaskIndex) st.copy(status = newStatus, verificationResult = result)
                        else st
                    }
                )
                if (verifyStatus == VerifyStatus.PASS && withResult.isAllCompleted)
                    withResult.copy(status = xyz.mederi.plan.PlanStatus.COMPLETED)
                else withResult
            } ?: return "Error: Plan not found: ${args.planId}"
            if (finalizedPlan.status == xyz.mederi.plan.PlanStatus.COMPLETED) {
                // 先落 Walkthrough（结果总结，自动装配自 Plan 数据）再归档——归档只移整个计划目录
                runCatching { planStore.writeWalkthrough(finalizedPlan) }
                runCatching { planStore.archive(args.planId) }
            }

            val passed = finalizedPlan.subtasks.count { it.status == SubtaskStatus.COMPLETED }
            val failed = finalizedPlan.subtasks.count { it.status == SubtaskStatus.FAILED }
            val pending = finalizedPlan.subtasks.count { it.status == SubtaskStatus.PENDING }
            val subtasksJson = kotlinx.serialization.json.Json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(Subtask.serializer()),
                finalizedPlan.subtasks
            )

            eventBus.emit(MederiEvent(
                type = EventType.PLAN_PROGRESS,
                sessionId = sessionId,
                payload = mapOf(
                    "planId" to args.planId,
                    "action" to if (finalizedPlan.status == xyz.mederi.plan.PlanStatus.COMPLETED) "completed" else "verified",
                    "passed" to passed.toString(),
                    "failed" to failed.toString(),
                    "pending" to pending.toString(),
                    "lastSubtaskIndex" to args.subtaskIndex.toString(),
                    "lastSubtaskStatus" to args.status,
                    "lastSubtaskRootCause" to (args.rootCause ?: ""),
                    "subtasks" to subtasksJson
                ),
                timestamp = Instant.now().toString()
            ))

            val verifyNote = autoVerify?.let { vr ->
                "\n\nVerification command output (auto-executed, exit ${vr.exitCode}):\n${vr.output.take(2000)}"
            } ?: ""

            val contradictionNotice = if (contradiction) buildContradictionNotice(
                verifyCmd ?: "", spec, args.status
            ) else ""

            return if (finalizedPlan.status == xyz.mederi.plan.PlanStatus.COMPLETED) {
                "Subtask #${args.subtaskIndex} (0-based, of ${plan.subtasks.size}) verified: PASS. All subtasks completed — " +
                    "plan ${args.planId} archived to .mederi/plans-done/${args.planId}/, walkthrough at " +
                    ".mederi/plans-done/${args.planId}/walkthrough.md. Report completion to the user in their language " +
                    "(what changed, what was tested, results); optionally enrich the walkthrough's Notes section " +
                    "(key findings; screenshots for UI changes).$verifyNote$contradictionNotice"
            } else if (verifyStatus == VerifyStatus.PASS) {
                "Subtask #${args.subtaskIndex} (0-based, of ${plan.subtasks.size}) verified: PASS. ($passed passed, $failed failed, $pending pending)$verifyNote$contradictionNotice"
            } else {
                "Subtask #${args.subtaskIndex} (0-based, of ${plan.subtasks.size}) verified: ${args.status}" +
                    (args.rootCause?.let { " ($it)" } ?: "") +
                    ". ($passed passed, $failed failed, $pending pending) ${guidanceFor(rootCause)}$verifyNote$contradictionNotice"
            }
        }
    }

    /** 机器 FAIL 的具体原因（exit 非零 / 缺 must-contain / 命中 must-not-contain）。 */
    private fun describeMachineFailure(
        autoVerify: CommandResult,
        literalCheck: LiteralCheckResult?,
        spec: VerificationSpec
    ): String {
        val sb = StringBuilder()
        if (autoVerify.exitCode != 0) {
            sb.appendLine("Exit code: ${autoVerify.exitCode}")
        }
        literalCheck?.missingContains?.takeIf { it.isNotEmpty() }?.let {
            sb.appendLine("Expected output literals NOT found: ${it.joinToString(", ")}")
            sb.appendLine("(The command may have exited 0, but the plan's expected result was not met.)")
        }
        literalCheck?.hitNotContains?.takeIf { it.isNotEmpty() }?.let {
            sb.appendLine("Output contained FORBIDDEN literals: ${it.joinToString(", ")}")
        }
        if (spec.expected.isNotBlank()) sb.appendLine("Plan's stated expectation: ${spec.expected}")
        sb.appendLine("Output:")
        sb.append(autoVerify.output.take(2000))
        return sb.toString().trimEnd()
    }

    /** 机器判定通过、模型判定未通过 → 异常态告知模板（要求主代理向用户如实说明）。 */
    private fun buildContradictionNotice(
        command: String,
        spec: VerificationSpec,
        declaredStatus: String
    ): String = buildString {
        appendLine()
        appendLine()
        appendLine("⚠ MACHINE/MODEL CONTRADICTION — ABNORMAL STATE (machineMismatch=true)")
        appendLine("The plan's verification command was auto-executed and the MACHINE verdict is PASS:")
        appendLine("  command: $command")
        if (spec.expectStdoutContains.isNotEmpty())
            appendLine("  must-contain literals all matched: ${spec.expectStdoutContains.joinToString(", ")}")
        if (spec.expectStdoutNotContains.isNotEmpty())
            appendLine("  must-not-contain literals all absent: ${spec.expectStdoutNotContains.joinToString(", ")}")
        if (spec.expected.isNotBlank()) appendLine("  plan's stated expectation: ${spec.expected}")
        appendLine("You nonetheless declared $declaredStatus. When the machine-checked expectations hold this")
        appendLine("should not happen, so it was recorded as $declaredStatus (non-PASS, machineMismatch=true).")
        appendLine("You MUST tell the user about this anomaly explicitly and in their language: state that the machine")
        appendLine("verified the plan's expectations were met while your own assessment differed, and say what you")
        appendLine("believe the machine's check missed. Do not silently proceed and do not hide the disagreement.")
    }

    /** 归因两分支 → 下一步动作（IMPLEMENTATION=追加补救；PLAN=追加修订）。 */
    private fun guidanceFor(rootCause: RootCause?): String = when (rootCause) {
        RootCause.IMPLEMENTATION ->
            "Root cause = IMPLEMENTATION: the spec was clear but the execution did not deliver it. " +
                "Call converge_plan to append remediation subtasks, then re-execute."
        RootCause.PLAN ->
            "Root cause = PLAN: the execution matched the spec, so the plan itself is wrong (its logic, " +
                "verification method, or expectation). Amend append-only, never rewrite: if the verification " +
                "contract is wrong call update_verification (reason required); if the spec was wrong call " +
                "generate_spec with reason= to correct it. Do NOT use converge_plan for a plan error."
        null -> ""
    }
}

````

## `core/src/commonMain/kotlin/xyz/mederi/tools/background/BackgroundTaskTools.kt`

````kotlin
package xyz.mederi.tools.background

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

import xyz.mederi.tools.ToolName

class BackgroundTaskTools(
    private val backgroundTaskManager: BackgroundTaskManager,
    private val sessionId: String,
) {
    @Serializable
    data class SetTimerArgs(
        @LLMDescription("Duration in seconds until the timer fires.")
        @SerialName("duration_seconds")
        val durationSeconds: Int = 0,
        @LLMDescription("A description of what this timer is for. You will receive this text when the timer fires, so write it as a reminder to yourself.")
        val description: String = "",
    )

    inner class SetTimerTool : SimpleTool<SetTimerArgs>(
        argsType = typeToken<SetTimerArgs>(),
        name = ToolName.SET_TIMER.value,
        description = "Set a timer. After duration_seconds, you receive a message with the description you wrote. Use for reminders, periodic checks, or waiting before retrying. You can continue working while the timer runs."
    ) {
        override suspend fun execute(args: SetTimerArgs): String {
            if (args.durationSeconds <= 0) return "Error: duration_seconds must be positive."
            val taskId = backgroundTaskManager.startTimer(sessionId, args.durationSeconds, args.description)
            return "Timer set: will fire in ${args.durationSeconds}s. Task ID: $taskId. Description: ${args.description}"
        }
    }
}

````

## `core/src/commonMain/kotlin/xyz/mederi/tools/subagent/SpawnAgentTool.kt`

````kotlin
package xyz.mederi.tools.subagent

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.mederi.domain.model.AIModel
import xyz.mederi.domain.model.EventType
import xyz.mederi.domain.model.MederiEvent
import xyz.mederi.domain.model.SubagentRole
import xyz.mederi.plan.PlanStore
import xyz.mederi.provider.domain.model.ReasoningLevel
import xyz.mederi.tools.ToolName
import java.time.Instant

/**
 * spawn_agent 工具参数。
 *
 * @param task 交给子 Agent 完成的任务描述。
 * @param briefing 可选的补充信息（父 Agent 的关键结论、限制条件等）。
 * @param planId 已批准计划的 ID，与 [subtaskIndex] 一起必填。
 * @param subtaskIndex 要执行的子任务索引（0 基）。
 */
@Serializable
data class SpawnAgentArgs(
    @LLMDescription("Task description for the sub-agent. With planId: brief identifier only (e.g. 'Execute subtask N: <name>') — the stored spec is the implementation source, do NOT put implementation instructions here. Without planId: clear, specific, independently executable task description.")
    val task: String = "",
    @LLMDescription("Extra context from the parent: key constraints and relevant file paths. With planId: not used (spec is the sole implementation source). Without planId: supplementary context for the task.")
    val briefing: String = "",
    @LLMDescription("Optional. Approved plan ID — with subtaskIndex, the sub-agent executes the exact spec stored by generate_spec. Omit for ad-hoc execution (no plan needed).")
    val planId: String = "",
    @LLMDescription("Optional. Subtask index to execute (0-based). Requires planId.")
    val subtaskIndex: Int = -1
)

/**
 * spawn_agent 工具。
 *
 * **异步派工**：调用后立即返回 `{"agentId":"sub_xxx","status":"RUNNING"}`，不阻塞父 Agent 的 turn。
 * 子 Agent 在后台运行（内存中，不创建持久化 Session）。完成时经 eventBus 终态事件自动唤醒
 * 父 turn（TurnExecutor 合成 `<event_message>` 唤起新 turn），父代理用 `agent_status` 查询、
 * `stop_agent` 主动停止。子 Agent 继承父 Agent 的所有配置。
 *
 * **两条路径**：
 * - 有 planId：planId + subtaskIndex + spec 硬校验（spec 必须已由 generate_spec 写入），走 plan 流程。
 * - 无 planId：ad-hoc 执行，task+briefing 直接派 executor（小改动、多文件编辑等自包含任务）。
 *
 * **模型动态读取**：spawn 用的模型/推理档位以 session 现值为准（构造时捕获的 turn 模型仅作
 * session 缺失的 fallback）——严格读取 session 数据库中持久化的模型配置。
 *
 * @param subagentManager 子 Agent 生命周期管理器（异步）。
 * @param directories 项目目录列表，子 Agent 工具的作用域。
 * @param aiModel turn 开始时的模型（fallback，正常路径读 session 现值）。
 * @param reasoningLevel turn 开始时的推理等级（fallback）。
 * @param projectId 当前项目 ID，子 Agent 必须关联到已存在的 Project。
 * @param parentSessionId 父 Session ID，用于追踪与 session 模型动态读取。
 * @param planStore 计划存储，门禁查询活跃计划与读取 spec 用。
 * @param eventBus 事件总线（派工后发 PLAN_PROGRESS 驱动 UI planApprovals 投影表）。
 * @param sessionStore 会话存储：spawn 时读 session 的最新模型/推理档位。
 */
class SpawnAgentTool(
    private val subagentManager: SubagentManager,
    private val directories: List<String>,
    private val aiModel: AIModel,
    private val reasoningLevel: ReasoningLevel,
    private val projectId: String,
    private val parentSessionId: String,
    private val planStore: PlanStore? = null,
    private val eventBus: MutableSharedFlow<MederiEvent>? = null,
    private val apiKeyId: String? = null,
    private val sessionStore: xyz.mederi.store.SessionStore? = null,
    private val subagentConfigManager: SubagentConfigManager? = null
) : SimpleTool<SpawnAgentArgs>(
    argsType = typeToken<SpawnAgentArgs>(),
    name = ToolName.SPAWN_AGENT.value,
    description = "Asynchronously creates a subagent to handle a sub-task. Returns immediately with an " +
        "agentId — the subagent runs in the background. With planId+subtaskIndex: executes the exact " +
        "spec stored by generate_spec (plan workflow). Without planId: ad-hoc execution from task+briefing " +
        "(small fixes, multi-file edits — no plan needed). Multiple spawn calls sent together in one " +
        "message run in parallel. You will be automatically woken up when the subagent finishes — end " +
        "your turn after spawning. Use agent_status to check progress or stop_agent to cancel."
) {

    @Serializable
    data class SpawnResult(
        val agentId: String,
        val status: String,
        /** spawn 实际使用的模型名（动态读 session 后的值）——派工返回即知"哪个模型在干活"。 */
        val modelName: String? = null
    )

    override suspend fun execute(args: SpawnAgentArgs): String {
        if (args.task.isBlank()) {
            return "Error: task must not be empty."
        }

        // 模型/推理档位动态读 session 现值（计划批准时用户可能已切换模型，见类注释）
        // 若配置了子代理独立模型，以子代理独立配置为准，否则继承父会话
        val sessionNow = sessionStore?.get(parentSessionId)
        val parentModel = sessionNow?.aiModel ?: aiModel
        val parentReasoning = sessionNow?.reasoningLevel ?: reasoningLevel
        val (effectiveAiModel, effectiveReasoningLevel) = subagentConfigManager?.resolve(
            role = SubagentRole.EXECUTOR,
            fallbackModel = parentModel,
            fallbackReasoning = parentReasoning,
            sessionId = parentSessionId
        ) ?: (parentModel to parentReasoning)

        // apiKeyId：子代理独立配置指定了就覆盖父会话的 apiKeyId，否则继承父会话的
        val subagentConfig = subagentConfigManager?.get(SubagentRole.EXECUTOR, parentSessionId)
        val effectiveApiKeyId = subagentConfig?.apiKeyId ?: sessionNow?.apiKeyId ?: apiKeyId

        // 无 plan 路径（ad-hoc 执行）：task+briefing 直接派 executor，跳过 plan/spec/IN_PROGRESS。
        // 用于小改动、多文件编辑等自包含任务——不需要 plan 流程，只省上下文。
        if (args.planId.isBlank()) {
            val agentId = try {
                subagentManager.spawn(
                    task = args.task,
                    briefing = args.briefing.takeIf { it.isNotBlank() },
                    plan = null,
                    role = SubagentRole.EXECUTOR,
                    directories = directories,
                    aiModel = effectiveAiModel,
                    reasoningLevel = effectiveReasoningLevel,
                    projectId = projectId,
                    parentSessionId = parentSessionId,
                    apiKeyId = effectiveApiKeyId,
                    planId = null,
                    executorSubtaskIndex = null,
                    planStore = null
                )
            } catch (e: SubagentLimitReachedException) {
                return e.modelGuidance
            }
            return Json.encodeToString(
                SpawnResult.serializer(),
                SpawnResult(agentId = agentId, status = "RUNNING", modelName = effectiveAiModel.name)
            )
        }

        // 有 plan 路径：planId + subtaskIndex + spec 硬校验（原有逻辑）
        if (args.subtaskIndex < 0) {
            return "Error: subtaskIndex is required when planId is provided."
        }

        // 从 PlanStore 取 generate_spec 存储的确切 spec（执行零漂移），并做存在性硬校验
        val plan = planStore?.load(args.planId)
            ?: return "Error: Plan not found: ${args.planId}"
        val st = plan.subtasks.getOrNull(args.subtaskIndex)
            ?: return "Error: Subtask index ${args.subtaskIndex} out of range (0..${plan.subtasks.size - 1})."
        if (st.status == xyz.mederi.plan.SubtaskStatus.COMPLETED)
            return "Error: Subtask ${args.subtaskIndex} is already COMPLETED."
        if (st.spec.isNullOrBlank())
            return "Error: Subtask ${args.subtaskIndex} has no generated spec. " +
                "Call generate_spec(planId=${args.planId}, subtaskIndex=${args.subtaskIndex}) first."

        // 原子标记子任务与计划为 IN_PROGRESS（之前是死状态——从未被设置）。
        // verify_subtask 后续会设 COMPLETED/FAILED；如果 turn 崩溃，IN_PROGRESS
        // 准确反映"这个子任务正在执行中"，下一轮模型看到状态后能判断是否需要重试。
        // 必须用 planStore.updatePlan（原子 RMW）：工具支持并行调度，两个 spawn_agent
        // 同消息并行时，裸 load→update 会互相覆盖对方的 IN_PROGRESS 标记。
        val startedPlan = planStore?.updatePlan(args.planId) { p ->
            p.copy(
                status = if (p.status == xyz.mederi.plan.PlanStatus.APPROVED)
                    xyz.mederi.plan.PlanStatus.IN_PROGRESS else p.status,
                subtasks = p.subtasks.map { s ->
                    if (s.index == args.subtaskIndex) s.copy(status = xyz.mederi.plan.SubtaskStatus.IN_PROGRESS)
                    else s
                }
            )
        } ?: return "Error: Plan not found: ${args.planId}"

        // 派工后发 PLAN_PROGRESS 驱动 UI planApprovals 投影表（真理源仍是 PlanStore）
        val subtasksJson = kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(xyz.mederi.plan.Subtask.serializer()),
            startedPlan.subtasks
        )
        eventBus?.emit(MederiEvent(
            type = EventType.PLAN_PROGRESS,
            sessionId = parentSessionId,
            payload = mapOf(
                "planId" to args.planId,
                "action" to "subtask-started",
                "subtaskIndex" to args.subtaskIndex.toString(),
                "subtasks" to subtasksJson
            ),
            timestamp = Instant.now().toString()
        ))

        val agentId = try {
            subagentManager.spawn(
                task = args.task,
                // Plan 路径：spec 是唯一实现指令来源（作为 plan 参数单独传）。
                // briefing = null：planDetail（用户批准的意图）不进执行器输入——
                // planDetail 可能含处理逻辑，与后续修订的 spec 产生两套要求；
                // 主代理负责确保 spec 符合批准意图，执行器不需要参与判断两份文本的关系。
                // 调研结论（researchNotes）也不注入——executor 需要时 read_file research.md。
                briefing = null,
                plan = st.spec,
                role = SubagentRole.EXECUTOR,
                directories = directories,
                aiModel = effectiveAiModel,
                reasoningLevel = effectiveReasoningLevel,
                projectId = projectId,
                parentSessionId = parentSessionId,
                apiKeyId = effectiveApiKeyId,
                planId = args.planId,
                executorSubtaskIndex = args.subtaskIndex,
                planStore = planStore
            )
        } catch (e: SubagentLimitReachedException) {
            return e.modelGuidance
        }
        return Json.encodeToString(
            SpawnResult.serializer(),
            SpawnResult(agentId = agentId, status = "RUNNING", modelName = effectiveAiModel.name)
        )
    }
}

/**
 * spawn_researcher 工具参数。
 */
@Serializable
data class SpawnResearcherArgs(
    @LLMDescription("Investigation task: what to resolve and what conclusion to produce.")
    val task: String = "",
    @LLMDescription("Extra context from the parent: key constraints and relevant file paths.")
    val briefing: String = ""
)

/**
 * spawn_researcher 工具：研究型子代理，只读文件、无写权限、无命令执行。
 *
 * **异步派工**：调用后立即返回 `{"agentId":"sub_xxx","status":"RUNNING"}`，不阻塞父 Agent 的 turn。
 * 完成时经 eventBus 终态事件自动唤醒父 turn。
 *
 * 不需要计划，不需要 spec——研究发生在计划之前（调研代码以支撑制定计划）。
 * 子代理的意识：自己是研究助手，不对用户发问，自主调研、汇总结果、返回给父 Agent。
 *
 * 模型动态读 session 现值（与 SpawnAgentTool 同策略），构造时捕获的 turn 模型仅作 fallback。
 */
class SpawnResearcherTool(
    private val subagentManager: SubagentManager,
    private val directories: List<String>,
    private val aiModel: AIModel,
    private val reasoningLevel: ReasoningLevel,
    private val projectId: String,
    private val parentSessionId: String,
    private val apiKeyId: String? = null,
    private val sessionStore: xyz.mederi.store.SessionStore? = null,
    private val subagentConfigManager: SubagentConfigManager? = null,
    private val planStore: PlanStore? = null
) : SimpleTool<SpawnResearcherArgs>(
    argsType = typeToken<SpawnResearcherArgs>(),
    name = ToolName.SPAWN_RESEARCHER.value,
    description = "Optionally delegate read-only research across files and web sources; no plan is required. " +
        "Use when collection or cross-checking would add bulky intermediate context with little later value, " +
        "and briefing/review overhead is worthwhile. Return findings, sources, and reasoning for the parent. " +
        "Returns immediately with an agentId — you will be automatically woken up when it finishes. " +
        "No write access, no command execution. The subagent reports findings and terminates — " +
        "it does not ask the parent clarifying questions."
) {
    override suspend fun execute(args: SpawnResearcherArgs): String {
        if (args.task.isBlank()) {
            return "Error: task must not be empty."
        }
        // 模型/推理档位动态读 session 现值（与 SpawnAgentTool 同策略）
        // 若配置了子代理独立模型，以子代理独立配置为准，否则继承父会话
        val sessionNow = sessionStore?.get(parentSessionId)
        val parentModel = sessionNow?.aiModel ?: aiModel
        val parentReasoning = sessionNow?.reasoningLevel ?: reasoningLevel
        val (effectiveAiModel, effectiveReasoningLevel) = subagentConfigManager?.resolve(
            role = SubagentRole.RESEARCHER,
            fallbackModel = parentModel,
            fallbackReasoning = parentReasoning,
            sessionId = parentSessionId
        ) ?: (parentModel to parentReasoning)
        // apiKeyId：子代理独立配置指定了就覆盖父会话的 apiKeyId，否则继承父会话的
        val subagentConfig = subagentConfigManager?.get(SubagentRole.RESEARCHER, parentSessionId)
        val effectiveApiKeyId = subagentConfig?.apiKeyId ?: sessionNow?.apiKeyId ?: apiKeyId
        // 查活跃 plan：有 plan 时把 planId 传下去，researcher 完成时把报告落盘到 {planId}/research.md；
        // 无 plan（分诊阶段调研，plan 尚未建）时 planId=null，保持原有行为（全文回灌父上下文）。
        val activePlanId = planStore?.loadBySession(parentSessionId)?.id
        val agentId = try {
            subagentManager.spawn(
                task = args.task,
                briefing = args.briefing.takeIf { it.isNotBlank() },
                plan = null,
                role = SubagentRole.RESEARCHER,
                directories = directories,
                aiModel = effectiveAiModel,
                reasoningLevel = effectiveReasoningLevel,
                projectId = projectId,
                parentSessionId = parentSessionId,
                apiKeyId = effectiveApiKeyId,
                planId = activePlanId,
                planStore = planStore
            )
        } catch (e: SubagentLimitReachedException) {
            return e.modelGuidance
        }
        return Json.encodeToString(
            SpawnAgentTool.SpawnResult.serializer(),
            SpawnAgentTool.SpawnResult(agentId = agentId, status = "RUNNING", modelName = effectiveAiModel.name)
        )
    }
}

````

## `core/src/commonMain/kotlin/xyz/mederi/tools/subagent/SubagentAsyncTools.kt`

````kotlin
package xyz.mederi.tools.subagent

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable
import xyz.mederi.tools.ToolName

/**
 * 子 Agent 异步生命周期管理工具集（内部实现，不向 AI 注册）。
 *
 * spawn_agent / spawn_researcher 改为异步后返回 agentId，父 Agent 用这套工具
 * 查询状态、主动停止。子 Agent 运行期间父 Agent 可以继续对话、派发其他任务。
 * 完成时经 eventBus 终态事件自动唤醒父 turn，无需阻塞等待。
 */

/** agent_status 工具参数。 */
@Serializable
data class AgentStatusArgs(
    @LLMDescription("Sub-agent ID (agentId returned by SPAWN).")
    val agentId: String = ""
)

/** 查询子代理状态：RUNNING / COMPLETED / ERROR / STOPPED / NOT_FOUND。 */
class AgentStatusTool(
    private val subagentManager: SubagentManager
) : SimpleTool<AgentStatusArgs>(
    argsType = typeToken<AgentStatusArgs>(),
    name = ToolName.AGENT_STATUS.value,
    description = "Queries the status of an asynchronously running subagent (spawned by spawn_agent / " +
        "spawn_researcher). Returns RUNNING / COMPLETED / ERROR / STOPPED / NOT_FOUND plus the final " +
        "result when available. Does not block."
) {
    override suspend fun execute(args: AgentStatusArgs): String {
        if (args.agentId.isBlank()) return "Error: agentId must not be empty."
        return subagentManager.status(args.agentId)
    }
}

/** stop_agent 工具参数。 */
@Serializable
data class StopAgentArgs(
    @LLMDescription("Sub-agent ID to stop (from SPAWN).")
    val agentId: String = ""
)


/** 停止正在运行的子代理，返回部分结果（若有）。 */
class StopAgentTool(
    private val subagentManager: SubagentManager
) : SimpleTool<StopAgentArgs>(
    argsType = typeToken<StopAgentArgs>(),
    name = ToolName.STOP_AGENT.value,
    description = "Cancels a running subagent and returns its partial result. Use when the subagent is " +
        "taking too long, is stuck, or the task is no longer needed. The subagent cannot be resumed."
) {
    override suspend fun execute(args: StopAgentArgs): String {
        if (args.agentId.isBlank()) return "Error: agentId must not be empty."
        return subagentManager.stop(args.agentId, operator = SubagentManager.OPERATOR_MAIN_AGENT)
    }
}

````

## `core/src/commonMain/kotlin/xyz/mederi/tools/subagent/SubagentTool.kt`

````kotlin
package xyz.mederi.tools.subagent

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable
import xyz.mederi.tools.ToolName
import xyz.mederi.tools.subagent.SpawnAgentArgs
import xyz.mederi.tools.subagent.SpawnResearcherArgs

/**
 * subagent 工具的分派键。
 *
 * **无 WAIT（异步唤醒设计）**：子代理完成时经 eventBus 终态事件
 * （SUBAGENT_COMPLETED/ERROR/STOPPED）唤醒父 turn——TurnExecutor 收集事件后
 * 合成 `<event_message>` 唤起新 turn。父代理 SPAWN 后正常结束 turn，期间可继续对话。
 */
@Serializable
enum class SubagentAction {
    /** 派执行器子代理执行已生成 spec 的计划子任务（需要 planId + subtaskIndex）。 */
    SPAWN,
    /** 派只读调研子代理（需要 task）。 */
    SPAWN_RESEARCHER,
    /** 查询子代理状态（需要 agentId）。 */
    STATUS,
    /** 停止子代理（需要 agentId）。 */
    STOP
}

/**
 * subagent 工具参数。不同 [SubagentAction] 使用其中不同的字段组合（见各字段说明）。
 */
@Serializable
data class SubagentArgs(
    @LLMDescription("Operation to perform: SPAWN (delegate a planned subtask), " +
        "SPAWN_RESEARCHER (delegate a read-only investigation), STATUS/STOP (manage a " +
        "previously spawned sub-agent).")
    val action: SubagentAction,
    @LLMDescription("SPAWN / SPAWN_RESEARCHER: task description for the sub-agent.")
    val task: String = "",
    @LLMDescription("SPAWN / SPAWN_RESEARCHER: optional extra context (known constraints, file paths).")
    val briefing: String = "",
    @LLMDescription("SPAWN: optional approved plan ID. With subtaskIndex, the sub-agent executes the exact spec stored by generate_spec. Omit for ad-hoc execution (no plan needed).")
    val planId: String = "",
    @LLMDescription("SPAWN: optional subtask index to execute (0-based). Requires planId.")
    val subtaskIndex: Int = -1,
    @LLMDescription("STATUS / STOP: sub-agent ID from a previous SPAWN.")
    val agentId: String = ""
)

/**
 * 子代理单一入口工具（2026-09 合并精简）。
 *
 * 原 6 个工具（spawn_agent / spawn_researcher / agent_status / stop_agent / wait_agent）
 * 封装为一个 `subagent`，用 [SubagentAction] 分流：
 * - [SubagentAction.SPAWN]：委托 [SpawnAgentTool]（保留全部门禁：spec 必填、原子 IN_PROGRESS、
 *   PLAN_PROGRESS、session 动态模型读）。
 * - [SubagentAction.SPAWN_RESEARCHER]：委托 [SpawnResearcherTool]。
 * - [SubagentAction.STATUS] / [SubagentAction.STOP]：委托 [SubagentManager.status] / stop。
 *
 * **无 WAIT**：子代理完成时经 eventBus 终态事件唤醒父 turn（TurnExecutor 合成
 * `<event_message>` 唤起新 turn）。SPAWN 后父 turn 正常结束，用户可继续对话；
 * 子代理完成时自动唤醒，结果摘要随 `<event_message>` 回到父上下文。
 *
 * 仅主代理注册（canSpawn）。返回值：SPAWN/SPAWN_RESEARCHER → {agentId,status,modelName}；
 * STATUS/STOP → {agentId,status,progress,result,modelId,modelName,reasoningLevel}。
 */
class SubagentTool(
    private val spawnExecutor: SpawnAgentTool,
    private val spawnResearcher: SpawnResearcherTool,
    private val manager: SubagentManager,
    /** 父会话 ID：STATUS 拦截轮询与 SPAWN 返回值 runningCount 都按本会话统计。 */
    private val parentSessionId: String
) : SimpleTool<SubagentArgs>(
    argsType = typeToken<SubagentArgs>(),
    name = ToolName.SUBAGENT.value,
    description = "Single tool to delegate and manage asynchronous sub-agents: SPAWN executes a task " +
        "(planned spec or ad-hoc), SPAWN_RESEARCHER runs a read-only investigation, " +
        "and STATUS/STOP query or cancel a spawned sub-agent. SPAWN / SPAWN_RESEARCHER are non-blocking " +
        "and return an agentId immediately while the sub-agent runs in the background. After calling " +
        "SPAWN or SPAWN_RESEARCHER, your NEXT response MUST be a text-only final message with ZERO " +
        "tool calls — state you have dispatched the sub-agent and are waiting. You will be " +
        "automatically woken when all dispatched sub-agents complete. Do NOT call subagent(STATUS) " +
        "to poll. Use STOP to cancel a sub-agent if it seems stuck."
) {
    override suspend fun execute(args: SubagentArgs): String = when (args.action) {
        SubagentAction.SPAWN -> appendSpawnGuidance(
            spawnExecutor.execute(
                SpawnAgentArgs(task = args.task, briefing = args.briefing, planId = args.planId, subtaskIndex = args.subtaskIndex)
            )
        )
        SubagentAction.SPAWN_RESEARCHER -> appendSpawnGuidance(
            spawnResearcher.execute(
                SpawnResearcherArgs(task = args.task, briefing = args.briefing)
            )
        )
        SubagentAction.STATUS ->
            if (args.agentId.isBlank()) "Error: STATUS requires agentId (from a previous SPAWN)."
            // 轮询拦截：本会话仍有 RUNNING 子代理时，不返回实际状态——引导父代理结束 turn 等自动唤醒
            else if (manager.runningCountForSession(parentSessionId) > 0)
                "Subagents are still running. Do NOT poll — end your turn now. You will be automatically woken when all results are in."
            else manager.status(args.agentId)
        SubagentAction.STOP ->
            if (args.agentId.isBlank()) "Error: STOP requires agentId (from a previous SPAWN)."
            else manager.stop(args.agentId, operator = SubagentManager.OPERATOR_MAIN_AGENT)
    }

    /**
     * SPAWN / SPAWN_RESEARCHER 返回值增强：JSON 之后追加人类可读指令文案。
     *
     * Error 文本（含并发上限拒绝）原样透传——没有派工成功就不能说 "dispatched"。
     * 成功路径才追加 runningCount（spawn 已注册，计数包含刚派出的这个）与 end-turn 硬指令。
     */
    private fun appendSpawnGuidance(result: String): String {
        if (result.startsWith("Error:")) return result
        val running = manager.runningCountForSession(parentSessionId)
        return "$result\nSubagent dispatched. $running subagent(s) running. " +
            "End your turn — you will be automatically woken when all complete. " +
            "Do NOT call subagent(STATUS) to poll."
    }
}

````

