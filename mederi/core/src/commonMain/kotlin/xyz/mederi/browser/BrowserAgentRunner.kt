package xyz.mederi.browser

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import xyz.mederi.debug.DebugLog
import xyz.mederi.domain.model.AIModel
import xyz.mederi.provider.ProviderManager
import xyz.mederi.provider.domain.model.ReasoningLevel
import xyz.mederi.provider.infrastructure.koog.KoogClientFactory
import xyz.mederi.provider.infrastructure.koog.KoogModelBuilder
import xyz.mederi.provider.infrastructure.koog.KoogParamsBuilder

/**
 * BROWSER agent：4-phase 循环（perceive → decide → execute → postprocess）。
 *
 * 不使用 Koog 的 ChatMemory / mederiSingleRunStrategy。每步重建 prompt：
 * `[system] + [memory + stepHistory + lastActionResults + 新鲜页面快照]`，一次 LLM 调用，
 * 拿到 action 列表 + 更新后的 memory，执行，下一步从零重建。页面快照用完即丢，不累积。
 *
 * 记忆 = AI 自总结（decision.memory），无独立总结 LLM 调用；
 * stepHistory = 系统维护的紧凑文本（最后 10 步）。
 *
 * @param providerManager 供应商管理（解析 provider + apiKey + 模型）。
 * @param browserControl 浏览器控制抽象（Camoufox 或 JCEF）。
 * @param aiModel 使用的模型（由 BrowserTaskManager 决定）。
 * @param reasoningLevel 推理等级。
 * @param maxSteps 最大步数上限，达到即视为未完成。
 * @param onStep 每步完成的回调（进度上报用），参数 = 步号 / 思考 / 执行结果。
 */
class BrowserAgentRunner(
    private val providerManager: ProviderManager,
    private val browserControl: BrowserControl,
    private val aiModel: AIModel,
    private val reasoningLevel: ReasoningLevel,
    private val maxSteps: Int = 50,
    private val onStep: (suspend (step: Int, thought: String, results: List<ActionResult>) -> Unit)? = null
) {

    /** 系统维护的紧凑 step history（最后 [STEP_HISTORY_SIZE] 条）。 */
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

    // provider（解析自 aiModel），首次 resolve 时填充
    private var provider: xyz.mederi.provider.domain.model.Provider? = null

    /**
     * 运行浏览器任务，返回最终结果。
     */
    suspend fun run(task: String): BrowserTaskResult {
        val client = resolveClient() ?: return BrowserTaskResult(
            success = false,
            message = "Browser agent requires a configured provider + model with an API key."
        )
        return try {
            browserControl.start()
            memory = task

            repeat(maxSteps) { step ->
                // 取消协作点：外部 stop 时及时退出
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                DebugLog.info("BrowserAgent", "step ${step + 1}/$maxSteps")

                // ── Phase 1: PERCEIVE ──
                val snapshot = try {
                    browserControl.snapshot()
                } catch (e: Exception) {
                    DebugLog.error("BrowserAgent", "perceive failed: ${e.message}")
                    return BrowserTaskResult(success = false, message = "浏览器快照失败: ${e.message}")
                }

                // ── Phase 2: DECIDE ──
                val userPrompt = buildUserPrompt(task, snapshot)
                val decision = try {
                    val response = llmCall(client, userPrompt)
                    val text = response.parts.filterIsInstance<MessagePart.Text>()
                        .joinToString("") { it.text }
                    BrowserDecisionParser.parse(text)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DebugLog.error("BrowserAgent", "decide failed (step ${step + 1}): ${e.message}")
                    val err = ActionResult("llm", false, "决策解析失败: ${e.message}")
                    lastResults = listOf(err)
                    stepHistory.add(step + 1, "LLM 决策失败", lastResults)
                    onStep?.invoke(step + 1, "决策失败", lastResults)
                    return BrowserTaskResult(success = false, message = "决策失败: ${e.message}")
                }

                // 记忆更新（LLM 自总结，无独立调用）
                if (decision.memory.isNotBlank()) memory = decision.memory

                // ── Phase 3: EXECUTE ──
                val results = executeActions(client, decision, step + 1)
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
            DebugLog.error("BrowserAgent", "run failed: ${e.message}")
            BrowserTaskResult(success = false, message = "浏览器任务异常: ${e.message ?: e::class.simpleName}")
        } finally {
            runCatching { browserControl.close() }
            client.close()
        }
    }

    /** 执行 LLM 决策里的所有 action，返回每个 action 的结果。 */
    private suspend fun executeActions(
        client: LLMClient,
        decision: BrowserDecision,
        step: Int
    ): List<ActionResult> {
        val actions = BrowserDecisionParser.toActions(decision)
        if (actions.isEmpty()) {
            return listOf(ActionResult("none", false, "没有可执行的动作"))
        }
        return actions.map { action ->
            val result = when (action) {
                is BrowserAction.Navigate -> exec("navigate") { browserControl.navigate(action.url) }
                is BrowserAction.Click -> exec("click") { browserControl.click(action.elementRef) }
                is BrowserAction.Type -> exec("type") { browserControl.type(action.elementRef, action.text) }
                is BrowserAction.Scroll -> exec("scroll") { browserControl.scroll(action.elementRef, action.deltaX, action.deltaY) }
                is BrowserAction.Done -> ActionResult("done", true, action.message)
                BrowserAction.NoOp -> ActionResult("noop", true)
            }
            DebugLog.info("BrowserAgent", "  action ${result.action} success=${result.success} ${result.detail.take(120)}")
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

    /** 单次 LLM 调用（非流式；每步一次，prompt 完全重建）。 */
    private suspend fun llmCall(client: LLMClient, userPrompt: String): Message.Assistant {
        val p = provider ?: error("provider not resolved")
        val koogModel = KoogModelBuilder.build(aiModel, p.type)
        val params = KoogParamsBuilder.build(
            type = p.type,
            reasoningLevel = reasoningLevel,
            reasoningParameter = p.reasoningParameter,
            maxTokens = aiModel.maxTokens
        )
        val koogPrompt = prompt("browser_task", params = params) {
            system(BrowserPrompt.build(maxSteps))
            user(userPrompt)
        }
        return client.execute(koogPrompt, koogModel)
    }

    private fun buildUserPrompt(task: String, snapshot: PageSnapshot): String = buildString {
        append("<agent_history>\n")
        append(stepHistory.buildDescription())
        append("\n</agent_history>\n\n")
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

    // ── provider / client 解析 ──

    private suspend fun resolveClient(): LLMClient? {
        val p = providerManager.listWithoutKeys()
            .firstOrNull { it.models.any { m -> m.id == aiModel.id } }
            ?: return null
        val apiKey = providerManager.getDefaultKeyValue(p.id) ?: return null
        provider = p
        return KoogClientFactory.create(p, apiKey)
    }
}

/** 浏览器任务最终结果。 */
data class BrowserTaskResult(
    val success: Boolean,
    val message: String
)
