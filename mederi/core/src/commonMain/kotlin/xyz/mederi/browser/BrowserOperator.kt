package xyz.mederi.browser

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import xyz.mederi.browser.drill.DrillExecutor
import xyz.mederi.debug.DebugLog
import xyz.mederi.domain.model.AIModel

/**
 * 浏览器操作员（BrowserOperator，对应 BrowserPilot 的 Operator 角色）。
 *
 * 核心定位：系统的"手和眼"，严格执行页面物理操作与感知。
 * - 遵循 4-phase 循环（perceive → decide → execute → postprocess）。
 * - 简化记忆机制：10 步滑动 StepHistory + AI 自总结 memory（无长上下文堆积，防止快照爆炸）。
 * - 判定外包：遇到复杂判定、准则校验或需要动脑的场景，通过 [BrowserAction.Judge] 交由 [BrowserBrain] 判定。
 * - 确定性脚本：通过 [BrowserAction.ExecuteDrill] 把脚本交给 [DrillExecutor] 批量执行。
 * - 挂载配方：如果挂载了 [BrowserRecipe]，将配方上下文注入提示词，并在需要时传给 Brain。
 *
 * LLM 调用全部走 [BrowserLLMCaller]（由 [BrowserLLMHelper] 默认实现）——Operator 不持有
 * provider/client，vision 门控（截图是否真正发给模型）由 caller 内部按 aiModel.supportsImages 决定。
 */
class BrowserOperator(
    private val control: BrowserControl,
    private val aiModel: AIModel,
    private val llmCaller: BrowserLLMCaller,
    private val brain: BrowserBrain? = null,
    private val drillExecutor: DrillExecutor? = null,
    private val recipe: BrowserRecipe? = null,
    private val maxSteps: Int = 50,
    private val onStep: (suspend (step: Int, thought: String, results: List<ActionResult>) -> Unit)? = null
) {

    /** 系统维护的紧凑 step history（最后 [maxItems] 条）。 */
    private class StepHistory(private val maxItems: Int = 10) {
        private val items = mutableListOf<String>()
        fun add(step: Int, thought: String?, results: List<ActionResult>) {
            val resultStr = results.joinToString(", ") { "${it.action}(${if (it.success) "ok" else "fail"})" }
            items.add("Step $step: Thought: ${thought ?: "-"} | Results: $resultStr")
            while (items.size > maxItems) items.removeAt(0)
        }
        fun buildDescription(): String = if (items.isEmpty()) "No history yet." else items.joinToString("\n")
    }

    private val stepHistory = StepHistory()
    private var memory: String = ""
    private var lastResults: List<ActionResult> = emptyList()

    /**
     * 运行浏览器任务，返回最终结果。
     */
    suspend fun run(task: String): BrowserTaskResult {
        return try {
            control.start()
            memory = task

            repeat(maxSteps) { step ->
                // 取消协作点：外部 stop 时及时退出
                currentCoroutineContext().ensureActive()
                DebugLog.info("BrowserOperator", "step ${step + 1}/$maxSteps")

                // ── Phase 1: PERCEIVE ──
                val snapshot = try {
                    control.snapshot()
                } catch (e: Exception) {
                    DebugLog.error("BrowserOperator", "perceive failed: ${e.message}")
                    return BrowserTaskResult(success = false, message = "浏览器快照失败: ${e.message}")
                }
                // 截图尽力而为：视觉后端不可用/失败只影响视觉模型，不阻断整体流程
                val screenshot = runCatching { control.screenshot() }.getOrNull()

                // ── Phase 2: DECIDE ──
                val userPrompt = buildUserPrompt(task, snapshot)
                val decision = try {
                    val decisionText = llmCaller.call(
                        systemPrompt = BrowserPrompt.build(maxSteps),
                        userPrompt = userPrompt,
                        // vision 门控（是否真正发送图片）在 llmCaller 内部按 aiModel.supportsImages 决定
                        image = screenshot
                    )
                    BrowserDecisionParser.parse(decisionText)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DebugLog.error("BrowserOperator", "decide failed (step ${step + 1}): ${e.message}")
                    val err = ActionResult("llm", false, "决策解析失败: ${e.message}")
                    lastResults = listOf(err)
                    stepHistory.add(step + 1, "LLM 决策失败", lastResults)
                    onStep?.invoke(step + 1, "决策失败", lastResults)
                    return BrowserTaskResult(success = false, message = "决策失败: ${e.message}")
                }

                // 记忆更新（LLM 自总结，无独立调用）
                if (decision.memory.isNotBlank()) memory = decision.memory

                // ── Phase 3: EXECUTE ──
                val results = executeActions(decision)
                lastResults = results

                // ── Phase 4: POSTPROCESS ──
                stepHistory.add(step + 1, decision.thought, results)
                onStep?.invoke(step + 1, decision.thought, results)

                // 终止条件
                if (decision.isDone) {
                    val done = results.lastOrNull() as? BrowserAction.Done
                    return BrowserTaskResult(
                        success = true,
                        message = done?.message ?: decision.thought.ifBlank { "任务完成" }
                    )
                }
            }

            BrowserTaskResult(success = false, message = "已达到最大步数 ($maxSteps)，任务未完成。")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            DebugLog.error("BrowserOperator", "run failed: ${e.message}")
            BrowserTaskResult(success = false, message = "浏览器任务异常: ${e.message ?: e::class.simpleName}")
        } finally {
            runCatching { control.close() }
        }
    }

    /** 执行 LLM 决策里的所有 action，返回每个 action 的结果。 */
    private suspend fun executeActions(decision: BrowserDecision): List<ActionResult> {
        val actions = BrowserDecisionParser.toActions(decision)
        if (actions.isEmpty()) {
            return listOf(ActionResult("none", false, "没有可执行的动作"))
        }
        return actions.map { action ->
            val result = when (action) {
                is BrowserAction.Navigate -> exec("navigate") { control.navigate(action.url) }
                is BrowserAction.Click -> exec("click") { control.click(action.elementRef) }
                is BrowserAction.Type -> exec("type") { control.type(action.elementRef, action.text) }
                is BrowserAction.Scroll -> exec("scroll") { control.scroll(action.elementRef, action.deltaX, action.deltaY) }
                is BrowserAction.Done -> ActionResult("done", true, action.message)
                is BrowserAction.Judge -> {
                    if (brain == null) {
                        ActionResult("judge", false, "BrowserBrain 未配置，无法进行判定")
                    } else {
                        val brainRes = brain.judge(
                            content = action.content,
                            instruction = action.instruction,
                            recipeRules = recipe?.judgeRules.orEmpty()
                        )
                        ActionResult("judge", brainRes.success, brainRes.toCompactString())
                    }
                }
                is BrowserAction.ExecuteDrill -> {
                    if (drillExecutor == null) {
                        ActionResult("drill", false, "DrillExecutor 未配置")
                    } else {
                        // 脚本内 ask_ai 判定交给 Brain（未配 Brain 时为 null，ask_ai 步骤会报错）
                        val onJudgeContent: (suspend (String, String) -> BrainResult)? = brain?.let { b ->
                            { c: String, i: String -> b.judge(c, i, recipe?.judgeRules.orEmpty()) }
                        }
                        val r = drillExecutor.executeScript(action.script, onJudgeContent)
                        ActionResult("drill", r.success, r.drill_results.joinToString(";") { it.toString() }.take(200))
                    }
                }
                is BrowserAction.WaitFor -> exec("wait_for") { control.waitFor(action.selector) }
                is BrowserAction.Tabs -> exec("tabs") {
                    when (action.action.uppercase()) {
                        "NEW" -> control.openTab()
                        "CLOSE" -> control.closeTab(action.tabId)
                        "LIST" -> {
                            control.listTabs()
                            xyz.mederi.browser.bidi.OperationResult.Success("tabs", detail = "listed")
                        }
                        "SELECT" -> control.selectTab(action.tabId)
                        else -> xyz.mederi.browser.bidi.OperationResult.Failure("tabs", "unknown tabAction: ${action.action}")
                    }
                }
                is BrowserAction.Screenshot -> {
                    control.screenshot()
                    ActionResult("screenshot", true)
                }
                is BrowserAction.NavigateBack -> {
                    // mederi BrowserControl 无 navigateBack；架子阶段 noop（navigate 当前 url 无意义，
                    // 简化为成功步不崩），ST3 接入后端能力后替换为真实后退。
                    DebugLog.info("BrowserOperator", "navigateBack not supported, noop")
                    ActionResult("back", true, "navigateBack not supported, noop")
                }
                is BrowserAction.Close -> exec("close") {
                    control.close()
                    xyz.mederi.browser.bidi.OperationResult.Success("close")
                }
                is BrowserAction.Sleep -> {
                    delay(action.ms)
                    ActionResult("sleep", true)
                }
                BrowserAction.NoOp -> ActionResult("noop", true)
            }
            DebugLog.info("BrowserOperator", "  action ${result.action} success=${result.success} ${result.detail.take(120)}")
            result
        }
    }

    private suspend fun exec(
        label: String,
        block: suspend () -> xyz.mederi.browser.bidi.OperationResult
    ): ActionResult {
        return try {
            val r = block()
            ActionResult(label, r.success, if (r is xyz.mederi.browser.bidi.OperationResult.Failure) r.reason else r.toString())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ActionResult(label, false, e.message ?: e::class.simpleName.orEmpty())
        }
    }

    private fun buildUserPrompt(task: String, snapshot: PageSnapshot): String = buildString {
        append("<agent_history>\n")
        append(stepHistory.buildDescription())
        append("\n</agent_history>\n\n")

        recipe?.let { r ->
            if (r.name.isNotBlank() || r.description.isNotBlank() || r.operatorContent.isNotBlank()) {
                append("<mounted_recipe>\n")
                if (r.name.isNotBlank()) append("Name: ${r.name}\n")
                if (r.description.isNotBlank()) append("Description: ${r.description}\n")
                if (r.operatorContent.isNotBlank()) append("OperatorRules:\n${r.operatorContent.trim()}\n")
                append("</recipe>\n\n")
            }
        }

        append("<user_request>\n")
        append(task)
        append("\n</user_request>\n\n")

        append("<memory>\n")
        append(if (memory.isBlank()) "No memory yet." else memory)
        append("\n</memory>\n\n")

        append("<last_action_results>\n")
        append(
            if (lastResults.isEmpty()) "No results."
            else lastResults.joinToString("\n") { "${it.action}: ${if (it.success) "ok" else "FAIL - ${it.detail}"}" }
        )
        append("\n</last_action_results>\n\n")

        append("<browser_state>\n")
        append("<url>").append(snapshot.url).append("</url>\n")
        append("<title>").append(snapshot.title).append("</title>\n")
        append("<active_tab_snapshot>\n")
        append(snapshot.yaml)
        append("\n</active_tab_snapshot>\n")
        append("</browser_state>\n")
    }
}

/** 浏览器任务最终结果。 */
data class BrowserTaskResult(
    val success: Boolean,
    val message: String
)

/** 向后兼容别名。 */
@Deprecated("Use BrowserOperator instead", ReplaceWith("BrowserOperator"))
typealias BrowserAgentRunner = BrowserOperator