package xyz.mederi.browser

import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.core.tools.ToolCallMetadata
import ai.koog.serialization.JSONObject
import ai.koog.serialization.kotlinx.KotlinxSerializer
import ai.koog.serialization.kotlinx.toKoogJSONElement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import xyz.mederi.browser.drill.DrillExecutor
import xyz.mederi.debug.DebugLog
import xyz.mederi.domain.model.AIModel

/**
 * 浏览器操作员（BrowserOperator，对应 BrowserPilot 的 Operator 角色）。
 *
 * 核心定位：系统的"手和眼"，严格执行页面物理操作与感知。
 * - 遵循 4-phase 循环（perceive → decide → execute → postprocess）。
 * - 简化记忆机制：滑动 StepHistory + AI 自管理 memory（无长上下文堆积，防止快照爆炸）。
 * - 判定外包：遇到复杂判定、准则校验或需要动脑的场景，通过 [JudgeTool] 交由 [BrowserBrain] 判定。
 * - 确定性脚本：通过 [ExecuteDrillTool] 把脚本交给 [DrillExecutor] 批量执行。
 * - 页面物理操作经通用工具集 [buildBrowserOperatorTools] 装配，原生 Koog tool-calling 为唯一执行路径。
 */
class BrowserOperator(
    private val control: BrowserControl,
    private val aiModel: AIModel,
    private val llmCaller: BrowserLLMCaller,
    private val brain: BrowserBrain? = null,
    private val drillExecutor: DrillExecutor? = null,
    private val recipe: BrowserRecipe? = null,
    private val maxSteps: Int = BrowserConstants.DEFAULT_MAX_STEPS,
    val collector: BrowserCollector = BrowserCollector(),
    private val onStep: (suspend (step: Int, thought: String, results: List<ActionResult>) -> Unit)? = null,
    private val onStream: (suspend (step: Int, kind: String, delta: String) -> Unit)? = null,
    private val onMemoryUpdate: ((String) -> Unit)? = null
) {

    /** 系统维护的紧凑 step history（最后 [maxItems] 条）。 */
    private class StepHistory(private val maxItems: Int = BrowserConstants.MAX_HISTORY_ITEMS) {
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
    private val serializer = KotlinxSerializer()

    fun getMemory(): String = memory

    /**
     * 运行浏览器任务，返回最终结果。
     */
    suspend fun run(task: String): BrowserTaskResult {
        return try {
            DebugLog.info("BrowserOperator", "Starting task: $task")
            control.start()
            memory = task
            onMemoryUpdate?.invoke(memory)

            var doneMessage: String? = null
            var isDone = false

            // 装配 Core 内部工具集
            val internalTools = mutableListOf<ToolBase<*, *>>()
            internalTools.add(DoneTool(onDone = { msg ->
                doneMessage = msg
                isDone = true
            }))
            internalTools.add(UpdateMemoryTool(onMemoryUpdate = { mem ->
                memory = mem
                onMemoryUpdate?.invoke(mem)
            }))
            internalTools.add(CollectInfoTool(collector = collector, control = control))
            if (brain != null) {
                internalTools.add(JudgeTool(brain = brain, recipe = recipe, collector = collector))
            }
            if (drillExecutor != null) {
                internalTools.add(
                    ExecuteDrillTool(
                        drillExecutor = drillExecutor,
                        brain = brain,
                        recipe = recipe,
                        mountedScript = recipe?.drillScript
                    )
                )
            }

            // 装配通用浏览器物理操作工具集（基于 BrowserControl 抽象，后端自动适配）
            val externalTools = buildBrowserOperatorTools(control)
            val allTools: List<ToolBase<*, *>> = externalTools + internalTools

            repeat(maxSteps) { step ->
                currentCoroutineContext().ensureActive()
                DebugLog.info("BrowserOperator", "step ${step + 1}/$maxSteps")

                // ── Phase 1: PERCEIVE ──
                val snapshot = try {
                    control.snapshot()
                } catch (e: Exception) {
                    DebugLog.error("BrowserOperator", "perceive failed: ${e.message}")
                    return BrowserTaskResult(success = false, message = "浏览器快照失败: ${e.message}")
                }

                // ── Phase 2: DECIDE ──
                val userPrompt = buildUserPrompt(task, snapshot)
                val response = try {
                    // 流式：reasoning/text 增量实时推给 onStream，Completed 帧承载最终结构化回复
                    var streamed: BrowserLLMResponse? = null
                    llmCaller.callWithToolsStreaming(
                        systemPrompt = BrowserPrompt.build(maxSteps),
                        userPrompt = userPrompt,
                        tools = allTools,
                        image = null
                    ).collect { ev ->
                        when (ev) {
                            is BrowserLLMStreamEvent.ReasoningDelta -> onStream?.invoke(step + 1, "reasoning", ev.text)
                            is BrowserLLMStreamEvent.TextDelta -> onStream?.invoke(step + 1, "text", ev.text)
                            is BrowserLLMStreamEvent.Completed -> streamed = ev.response
                        }
                    }
                    streamed ?: BrowserLLMResponse(text = "Failed to resolve LLM client")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DebugLog.error("BrowserOperator", "decide failed (step ${step + 1}): ${e.message}")
                    val err = ActionResult("llm", false, "决策调用失败: ${e.message}")
                    lastResults = listOf(err)
                    stepHistory.add(step + 1, "LLM 决策失败", lastResults)
                    onStep?.invoke(step + 1, "决策失败", lastResults)
                    return BrowserTaskResult(success = false, message = "决策失败: ${e.message}")
                }

                // ── Phase 3: EXECUTE ──
                val results = mutableListOf<ActionResult>()
                val thought = response.reasoning ?: response.text.substringBefore("\n{").trim()

                if (response.toolCalls.isNotEmpty()) {
                    // 原生 Koog tool call 路径（唯一执行路径）
                    for (toolCall in response.toolCalls) {
                        val tool = allTools.firstOrNull { it.name == toolCall.name }
                        if (tool != null) {
                            val res = executeTool(tool, toolCall.argsJson)
                            results.add(res)
                            if (tool.name == BrowserConstants.ToolNames.DONE && doneMessage == null) {
                                doneMessage = res.detail
                                isDone = true
                            }
                        } else {
                            results.add(ActionResult(action = toolCall.name, success = false, detail = "Tool '${toolCall.name}' not found"))
                        }
                    }
                } else if (response.text.isNotBlank()) {
                    results.add(ActionResult("none", false, "no tool calls in response"))
                } else {
                    results.add(ActionResult("none", false, "no response"))
                }

                lastResults = results

                // ── Phase 4: POSTPROCESS ──
                val effectiveThought = thought.ifBlank { response.text.take(100) }
                stepHistory.add(step + 1, effectiveThought, results)
                onStep?.invoke(step + 1, effectiveThought, results)

                if (isDone) {
                    return BrowserTaskResult(
                        success = true,
                        message = doneMessage ?: effectiveThought.ifBlank { "任务完成" },
                        collectedItems = collector.list()
                    )
                }
            }

            BrowserTaskResult(
                success = false,
                message = "已达到最大步数 ($maxSteps)，任务未完成。",
                collectedItems = collector.list()
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            DebugLog.error("BrowserOperator", "run failed: ${e.message}")
            BrowserTaskResult(
                success = false,
                message = "浏览器任务异常: ${e.message ?: e::class.simpleName}",
                collectedItems = collector.list()
            )
        }
    }

    private suspend fun executeTool(tool: ToolBase<*, *>, argsJson: String): ActionResult {
        return try {
            val jsonElement = if (argsJson.isBlank()) {
                JsonObject(emptyMap())
            } else {
                Json.parseToJsonElement(argsJson)
            }
            val koogJson = jsonElement.toKoogJSONElement() as? JSONObject
                ?: throw IllegalArgumentException("tool args must be a JSON object, got: $argsJson")
            val decodedArgs = tool.decodeArgs(koogJson, serializer)
            @Suppress("UNCHECKED_CAST")
            val result = (tool as ToolBase<Any?, Any?>).execute(decodedArgs, ToolCallMetadata.EMPTY)
            val resultString = tool.encodeResultToStringUnsafe(result, serializer)
            val isSuccess = !resultString.startsWith("FAIL") && !resultString.contains("=FAIL:")
            ActionResult(action = tool.name, success = isSuccess, detail = resultString)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.error("BrowserOperator", "Tool '${tool.name}' execution failed: ${e.message}")
            ActionResult(action = tool.name, success = false, detail = e.message ?: e::class.simpleName ?: "unknown error")
        }
    }

    private fun buildUserPrompt(task: String, snapshot: PageSnapshot): String = buildString {
        append("<${BrowserConstants.ProtocolTags.AGENT_HISTORY}>\n")
        append(stepHistory.buildDescription())
        append("\n</${BrowserConstants.ProtocolTags.AGENT_HISTORY}>\n\n")

        recipe?.let { r ->
            if (r.name.isNotBlank() || r.description.isNotBlank() || r.operatorContent.isNotBlank()) {
                append("<mounted_recipe>\n")
                if (r.name.isNotBlank()) append("Name: ${r.name}\n")
                if (r.description.isNotBlank()) append("Description: ${r.description}\n")
                if (r.operatorContent.isNotBlank()) append("OperatorRules:\n${r.operatorContent.trim()}\n")
                append("</recipe>\n\n")
            }
        }

        append("<${BrowserConstants.ProtocolTags.USER_REQUEST}>\n")
        append(task)
        append("\n</${BrowserConstants.ProtocolTags.USER_REQUEST}>\n\n")

        append("<${BrowserConstants.ProtocolTags.AGENT_STATE}>\n")
        append("  <${BrowserConstants.ProtocolTags.MEMORY}>\n")
        append(if (memory.isBlank()) "No memory yet." else memory)
        append("\n  </${BrowserConstants.ProtocolTags.MEMORY}>\n")
        append("</${BrowserConstants.ProtocolTags.AGENT_STATE}>\n\n")

        append("<${BrowserConstants.ProtocolTags.LAST_ACTION_RESULTS}>\n")
        append(
            if (lastResults.isEmpty()) "No results."
            else lastResults.joinToString("\n") { "${it.action}: ${if (it.success) "ok" else "FAIL - ${it.detail}"}" }
        )
        append("\n</${BrowserConstants.ProtocolTags.LAST_ACTION_RESULTS}>\n\n")

        append("<${BrowserConstants.ProtocolTags.BROWSER_STATE}>\n")
        append("<${BrowserConstants.ProtocolTags.URL}>").append(snapshot.url).append("</${BrowserConstants.ProtocolTags.URL}>\n")
        append("<${BrowserConstants.ProtocolTags.TITLE}>").append(snapshot.title).append("</${BrowserConstants.ProtocolTags.TITLE}>\n")
        append("<${BrowserConstants.ProtocolTags.ACTIVE_TAB_SNAPSHOT}>\n")
        append(snapshot.yaml)
        append("\n</${BrowserConstants.ProtocolTags.ACTIVE_TAB_SNAPSHOT}>\n")
        append("</${BrowserConstants.ProtocolTags.BROWSER_STATE}>\n")
    }
}

/** 浏览器任务最终结果。 */
data class BrowserTaskResult(
    val success: Boolean,
    val message: String,
    val collectedItems: List<BrowserCollectedItem> = emptyList()
)

/** 向后兼容别名。 */
@Deprecated("Use BrowserOperator instead", ReplaceWith("BrowserOperator"))
typealias BrowserAgentRunner = BrowserOperator
