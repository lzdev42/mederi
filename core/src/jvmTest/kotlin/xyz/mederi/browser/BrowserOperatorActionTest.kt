package xyz.mederi.browser

import ai.koog.agents.core.tools.ToolBase
import kotlinx.coroutines.runBlocking
import xyz.mederi.browser.drill.DrillExecutor
import xyz.mederi.domain.model.AIModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ST1 BrowserOperator 行为锁定：
 * - 原生 tool-calling 唯一路径（drill / judge / done 经对应工具触发）
 * - 纯 axtree 感知（不传截图）
 * - 记忆自管理
 */
class BrowserOperatorActionTest {

    /** 记录调用的 fake LLM caller（仅给 BrowserBrain 用：call() 返回文本判定 JSON）。 */
    private class FakeLLMCaller(
        private val decisions: MutableList<String> = mutableListOf(),
        private val supportsImages: Boolean = true
    ) : BrowserLLMCaller {
        data class Call(val systemPrompt: String, val userPrompt: String, val image: ByteArray?)

        val calls = mutableListOf<Call>()
        private var callIndex = 0

        fun addDecision(json: String): FakeLLMCaller {
            decisions.add(json)
            return this
        }

        override suspend fun call(systemPrompt: String, userPrompt: String, image: ByteArray?): String {
            // 与 BrowserLLMHelper 一致：模型不支持图片时，截图不传给模型（记录为 null）
            val effectiveImage = if (supportsImages) image else null
            calls += Call(systemPrompt, userPrompt, effectiveImage)
            val idx = callIndex++
            return decisions.getOrElse(idx) { """{"actions":[],"is_done":true}""" }
        }
    }

    /** 原生 tool-calling fake：按队列返回结构化工具调用，并记录每次调用收到的 image。 */
    private class NativeToolLLMCaller(private val responses: List<BrowserLLMResponse>) : BrowserLLMCaller {
        var callIndex = 0
        val calls = mutableListOf<ByteArray?>()

        override suspend fun call(systemPrompt: String, userPrompt: String, image: ByteArray?): String = ""

        override suspend fun callWithTools(
            systemPrompt: String,
            userPrompt: String,
            tools: List<ToolBase<*, *>>,
            image: ByteArray?
        ): BrowserLLMResponse {
            calls += image
            val idx = callIndex++
            return responses.getOrElse(idx) {
                BrowserLLMResponse(
                    toolCalls = listOf(
                        BrowserToolCall(id = "end", name = BrowserConstants.ToolNames.DONE, argsJson = """{"message":"fallback done"}""")
                    )
                )
            }
        }
    }

    private val visionModel = AIModel(
        id = "m-vision",
        providerModelId = "m-vision",
        name = "vision",
        supportsImages = true
    )

    // ── 1. execute_drill 委托真实 DrillExecutor ──

    @Test
    fun `execute drill delegates to real DrillExecutor`() = runBlocking {
        val control = FakeBrowserControl(
            elements = mapOf(".card" to FakeElement(count = 1)),
            jsResults = mapOf("document.querySelectorAll" to """[{"id":"1"}]""")
        )
        // 真实 DrillExecutor：legacy loop（steps 无 item_steps）只支持 locator 动作（navigate 只在
        // item 级分发），因此用两阶段脚本：extract_list 产出 1 项 → item 级 navigate https://x。
        val drillExecutor = DrillExecutor(control = control, workingDir = null)
        val caller = NativeToolLLMCaller(
            listOf(
                BrowserLLMResponse(
                    toolCalls = listOf(
                        BrowserToolCall(
                            id = "tc1",
                            name = BrowserConstants.ToolNames.DRILL,
                            argsJson = """{"script":{"task_name":"t","steps":[{"step_name":"extract","action":"extract_list","selector_value":".card","record_fields":{"title":".title"}}],"item_steps":[{"step_name":"go","action":"navigate","url":"https://x"}]}}"""
                        )
                    )
                ),
                BrowserLLMResponse(
                    toolCalls = listOf(
                        BrowserToolCall(id = "tc2", name = BrowserConstants.ToolNames.DONE, argsJson = """{"message":"ok"}""")
                    )
                )
            )
        )

        val operator = BrowserOperator(
            control = control,
            aiModel = visionModel,
            llmCaller = caller,
            drillExecutor = drillExecutor
        )

        val result = operator.run("run drill")

        assertTrue(result.success, "任务应成功完成: ${result.message}")
        assertEquals(2, caller.callIndex, "两步各应调用一次 LLM")
        assertTrue(
            control.log.any { it == "navigate https://x" },
            "DrillExecutor 应执行 item 级 navigate, log=${control.log}"
        )
        assertTrue(control.log.contains("start"))
    }

    // ── 2. judge 委托 BrowserBrain ──

    @Test
    fun `judge delegates to BrowserBrain and result feeds next step`() = runBlocking {
        val control = FakeBrowserControl()
        val brainCaller = FakeLLMCaller().addDecision("""{"match":"true","reason":"ok"}""")
        val brain = BrowserBrain(llmCaller = brainCaller)
        val operatorCaller = NativeToolLLMCaller(
            listOf(
                BrowserLLMResponse(
                    toolCalls = listOf(
                        BrowserToolCall(id = "tc1", name = BrowserConstants.ToolNames.JUDGE, argsJson = """{"content":"c","instruction":"i"}""")
                    )
                ),
                BrowserLLMResponse(
                    toolCalls = listOf(
                        BrowserToolCall(id = "tc2", name = BrowserConstants.ToolNames.DONE, argsJson = """{"message":""}""")
                    )
                )
            )
        )

        val stepResults = mutableListOf<List<ActionResult>>()
        val operator = BrowserOperator(
            control = control,
            aiModel = visionModel,
            llmCaller = operatorCaller,
            brain = brain,
            onStep = { _, _, results -> stepResults += results }
        )

        val result = operator.run("verify")

        assertTrue(result.success, "任务应成功完成: ${result.message}")
        assertEquals(1, brainCaller.calls.size, "brain 应被调用一次")

        // 第 1 步的 judge 结果进入 step history / last_action_results
        val judgeResult = stepResults[0].firstOrNull { it.action == "judge" }
        assertNotNull(judgeResult, "第 1 步应产出 judge 结果, steps=${stepResults.map { it.map { r -> r.action } }}")
        assertTrue(judgeResult.success, "judge 应成功: ${judgeResult.detail}")
        assertTrue(judgeResult.detail.contains("match=true"), "detail 应含判定结果: ${judgeResult.detail}")
        assertTrue(judgeResult.detail.contains("ok"), "detail 应含 reason: ${judgeResult.detail}")
    }

    // ── 3. 纯 axtree 感知策略（暂时不用截图） ──

    @Test
    fun `axtree policy passes null image to llmCaller`() = runBlocking {
        // 当前统一走 axtree 感知，不调用截图，llmCaller 接收到的 image 恒为 null
        val control = FakeBrowserControl()
        val caller = NativeToolLLMCaller(
            listOf(
                BrowserLLMResponse(
                    toolCalls = listOf(
                        BrowserToolCall(id = "tc1", name = BrowserConstants.ToolNames.DONE, argsJson = """{"message":"done"}""")
                    )
                )
            )
        )
        val op = BrowserOperator(
            control = control,
            aiModel = visionModel,
            llmCaller = caller
        )
        val result = op.run("axtree task")
        assertTrue(result.success, "任务应成功: ${result.message}")
        assertNull(caller.calls[0], "当前纯 axtree 策略下不得传截图")
    }

    // ── 4. 原生 Koog tool-calling 与 BrowserPilot 风格自管理记忆 ──

    @Test
    fun `native tool calling executes external and internal tools and updates memory`() = runBlocking {
        val control = FakeBrowserControl()

        val memoryUpdates = mutableListOf<String>()
        val stepResults = mutableListOf<List<ActionResult>>()

        val caller = NativeToolLLMCaller(
            listOf(
                BrowserLLMResponse(
                    text = "Opening page and updating memory",
                    reasoning = "I need to open example.com first",
                    toolCalls = listOf(
                        BrowserToolCall(
                            id = "tc1",
                            name = BrowserConstants.ToolNames.UPDATE_MEMORY,
                            argsJson = """{"memory":"current page: example.com"}"""
                        ),
                        BrowserToolCall(
                            id = "tc2",
                            name = BrowserConstants.ToolNames.NAVIGATE,
                            argsJson = """{"url":"https://example.com"}"""
                        )
                    )
                ),
                BrowserLLMResponse(
                    text = "Task is completed",
                    toolCalls = listOf(
                        BrowserToolCall(
                            id = "tc3",
                            name = BrowserConstants.ToolNames.DONE,
                            argsJson = """{"message":"found target item"}"""
                        )
                    )
                )
            )
        )

        val operator = BrowserOperator(
            control = control,
            aiModel = visionModel,
            llmCaller = caller,
            onStep = { _, thought, results ->
                println("[TestLog] onStep: thought='$thought' results=${results.map { it.action }}")
                stepResults += results
            },
            onMemoryUpdate = { mem ->
                println("[TestLog] onMemoryUpdate: '$mem'")
                memoryUpdates += mem
            }
        )

        val result = operator.run("search target")

        assertTrue(result.success, "任务应成功: ${result.message}")
        assertEquals(2, caller.callIndex, "两步各应调用一次 LLM")
        assertEquals("current page: example.com", operator.getMemory(), "Operator memory 应自管理更新")
        assertTrue(memoryUpdates.contains("current page: example.com"), "onMemoryUpdate 回调应被触发")
        assertTrue(control.log.any { it == "navigate https://example.com" }, "NavigateTool 应真正调用 FakeBrowserControl")
        assertEquals(2, stepResults.size, "应产出 2 步的结果")
        assertEquals("navigate", stepResults[0].last().action)
        assertTrue(stepResults[0].last().success)
    }
}