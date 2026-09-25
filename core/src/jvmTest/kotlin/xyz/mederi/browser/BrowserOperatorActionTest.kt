package xyz.mederi.browser

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.mederi.browser.drill.DrillExecutor
import xyz.mederi.domain.model.AIModel
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ST2 BrowserOperator 行为锁定：
 * - DecisionParser 新 action 集（execute_drill / wait_for / tabs / screenshot / navigate_back / close / sleep）
 * - execute_drill 委托真实 DrillExecutor（FakeBrowserControl 日志佐证）
 * - judge 委托 BrowserBrain（结果进入 step history / last_action_results）
 * - vision 门控：llmCaller 按 aiModel.supportsImages 决定是否收到截图
 */
class BrowserOperatorActionTest {

    /** 记录调用的 fake LLM caller；[supportsImages] 模拟 BrowserLLMHelper 的 vision 门控语义。 */
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

    private val visionModel = AIModel(
        id = "m-vision",
        providerModelId = "m-vision",
        name = "vision",
        supportsImages = true
    )
    private val textOnlyModel = AIModel(
        id = "m-text",
        providerModelId = "m-text",
        name = "text",
        supportsImages = false
    )

    // ── 1. DecisionParser 新 action 集 ──

    @Test
    fun `action parser extracts new action types`() {
        val json = """
        {
            "thought": "先跑脚本再收尾",
            "memory": "running",
            "actions": [
                {"type": "execute_drill", "script": {"task_name": "t", "steps": [{"action": "navigate", "url": "https://x"}]}},
                {"type": "wait_for", "selector": ".loaded"},
                {"type": "tabs", "tabAction": "NEW"},
                {"type": "tabs", "tabAction": "SELECT", "tabId": "tab-2"},
                {"type": "screenshot"},
                {"type": "navigate_back"},
                {"type": "close"},
                {"type": "sleep", "sleepMs": 500}
            ],
            "is_done": false
        }
        """.trimIndent()

        val actions = BrowserDecisionParser.toActions(BrowserDecisionParser.parse(json))

        assertEquals(8, actions.size)

        val drill = assertIs<BrowserAction.ExecuteDrill>(actions[0])
        val drillObj = assertIs<JsonObject>(drill.script)
        assertEquals("t", assertIs<JsonPrimitive>(drillObj["task_name"]).content)

        val wait = assertIs<BrowserAction.WaitFor>(actions[1])
        assertEquals(".loaded", wait.selector)

        val tabsNew = assertIs<BrowserAction.Tabs>(actions[2])
        assertEquals("NEW", tabsNew.action)
        val tabsSelect = assertIs<BrowserAction.Tabs>(actions[3])
        assertEquals("SELECT", tabsSelect.action)
        assertEquals("tab-2", tabsSelect.tabId)

        assertIs<BrowserAction.Screenshot>(actions[4])
        assertIs<BrowserAction.NavigateBack>(actions[5])
        assertIs<BrowserAction.Close>(actions[6])

        val sleep = assertIs<BrowserAction.Sleep>(actions[7])
        assertEquals(500L, sleep.ms)
    }

    @Test
    fun `action parser keeps legacy action types`() {
        val json = """
        {
            "thought": "t",
            "actions": [
                {"type": "navigate", "url": "https://example.com"},
                {"type": "click", "elementRef": "12"},
                {"type": "done", "message": "finished"}
            ],
            "is_done": true
        }
        """.trimIndent()

        val actions = BrowserDecisionParser.toActions(BrowserDecisionParser.parse(json))
        assertEquals(3, actions.size)
        assertIs<BrowserAction.Navigate>(actions[0])
        assertIs<BrowserAction.Click>(actions[1])
        assertIs<BrowserAction.Done>(actions[2])
    }

    // ── 2. execute_drill 委托真实 DrillExecutor ──

    @Test
    fun `execute drill delegates to real DrillExecutor`() = runBlocking {
        val control = FakeBrowserControl(
            elements = mapOf(".card" to FakeElement(count = 1)),
            jsResults = mapOf("document.querySelectorAll" to """[{"id":"1"}]""")
        )
        // 真实 DrillExecutor：legacy loop（steps 无 item_steps）只支持 locator 动作（navigate 只在
        // item 级分发），因此用两阶段脚本：extract_list 产出 1 项 → item 级 navigate https://x。
        val drillExecutor = DrillExecutor(control = control, workingDir = null)
        val llm = FakeLLMCaller()
            .addDecision(
                """
                {
                  "actions": [{"type": "execute_drill", "script": {
                    "task_name": "t",
                    "steps": [{"step_name": "extract", "action": "extract_list", "selector_value": ".card", "record_fields": {"title": ".title"}}],
                    "item_steps": [{"step_name": "go", "action": "navigate", "url": "https://x"}]
                  }}],
                  "is_done": false
                }
                """.trimIndent()
            )
            .addDecision("""{"actions":[{"type":"done","message":"ok"}],"is_done":true}""")

        val operator = BrowserOperator(
            control = control,
            aiModel = visionModel,
            llmCaller = llm,
            drillExecutor = drillExecutor
        )

        val result = operator.run("run drill")

        assertTrue(result.success, "任务应成功完成: ${result.message}")
        assertEquals(2, llm.calls.size, "两步各应调用一次 LLM")
        assertTrue(
            control.log.any { it == "navigate https://x" },
            "DrillExecutor 应执行 item 级 navigate, log=${control.log}"
        )
        assertTrue(control.log.contains("start"))
        assertTrue(control.log.contains("close"))
    }

    // ── 3. judge 委托 BrowserBrain ──

    @Test
    fun `judge delegates to BrowserBrain and result feeds next step`() = runBlocking {
        val control = FakeBrowserControl()
        val brainCaller = FakeLLMCaller().addDecision("""{"match":"true","reason":"ok"}""")
        val brain = BrowserBrain(llmCaller = brainCaller)
        val operatorCaller = FakeLLMCaller()
            .addDecision(
                """{"actions":[{"type":"judge","content":"c","instruction":"i"}],"is_done":false}"""
            )
            .addDecision("""{"actions":[{"type":"done"}],"is_done":true}""")

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

    // ── 4. vision 门控 ──

    @Test
    fun `vision gate passes screenshot only when model supports images`() = runBlocking {
        // supportsImages = true：llmCaller 收到非 null 截图
        val visionControl = FakeBrowserControl()
        val visionCaller = FakeLLMCaller(supportsImages = true)
            .addDecision("""{"actions":[{"type":"done"}],"is_done":true}""")
        val visionOp = BrowserOperator(
            control = visionControl,
            aiModel = visionModel,
            llmCaller = visionCaller
        )
        val visionResult = visionOp.run("vision task")
        assertTrue(visionResult.success, "vision 任务应成功: ${visionResult.message}")
        val visionImage = visionCaller.calls[0].image
        assertNotNull(visionImage, "supportsImages=true 时应收到截图")
        assertContentEquals(FakeBrowserControl.FIXED_SCREENSHOT, visionImage, "截图字节应原样传递")

        // supportsImages = false：llmCaller 记录为 null
        val textControl = FakeBrowserControl()
        val textCaller = FakeLLMCaller(supportsImages = false)
            .addDecision("""{"actions":[{"type":"done"}],"is_done":true}""")
        val textOp = BrowserOperator(
            control = textControl,
            aiModel = textOnlyModel,
            llmCaller = textCaller
        )
        val textResult = textOp.run("text task")
        assertTrue(textResult.success, "text 任务应成功: ${textResult.message}")
        assertNull(textCaller.calls[0].image, "supportsImages=false 时不得收到截图")
    }
}