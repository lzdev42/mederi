package xyz.mederi.browser

import ai.koog.agents.core.tools.ToolBase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import xyz.mederi.domain.model.AIModel
import xyz.mederi.domain.model.EventType
import xyz.mederi.domain.model.MederiEvent
import xyz.mederi.domain.model.SubagentRole
import xyz.mederi.provider.domain.model.ReasoningLevel
import xyz.mederi.tools.subagent.FakeProviderManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowserReportingAndLifecycleTest {

    private val fakeModel = AIModel(id = "test-model", providerModelId = "test-model", name = "Test Model")

    private class TestLLMCaller(
        private val onBrainCall: (() -> Unit)? = null,
        private val brainSummary: String = "大脑判定报告",
        private val operatorDoneMessage: String = "操作员已完成"
    ) : BrowserLLMCaller {
        override suspend fun call(systemPrompt: String, userPrompt: String, image: ByteArray?): String {
            if ("summarizing" in systemPrompt || "brain" in systemPrompt.lowercase()) {
                onBrainCall?.invoke()
                return """{"summary":"$brainSummary","goal_achieved":true}"""
            }
            return "{}"
        }

        override suspend fun callWithTools(
            systemPrompt: String,
            userPrompt: String,
            tools: List<ToolBase<*, *>>,
            image: ByteArray?
        ): BrowserLLMResponse {
            return BrowserLLMResponse(
                text = """{"thought":"任务完成","actions":[{"type":"done","message":"$operatorDoneMessage"}],"is_done":true}"""
            )
        }
    }

    @Test
    fun testNoJudgmentTaskReportsDirectlyAndClosesBrowser() = runBlocking {
        val fakeControl = FakeBrowserControl()
        BrowserRegistry.register("fake-direct", BrowserKind.JCEF, factory = { _, _ -> fakeControl })

        val eventBus = MutableSharedFlow<MederiEvent>(replay = 10)
        var brainCallCount = 0

        val fakeCaller = TestLLMCaller(
            onBrainCall = { brainCallCount++ },
            operatorDoneMessage = "搜索完成，找到了3条记录"
        )

        val manager = BrowserTaskManager(
            providerManager = FakeProviderManager(emptyMap()),
            scope = this,
            eventBus = eventBus,
            llmCallerProvider = { _, _, _ -> fakeCaller }
        )

        val completedEventDeferred = asyncEvent(eventBus, EventType.BROWSER_TASK_COMPLETED)

        val taskId = manager.runTask(
            task = "搜索热点资讯",
            aiModel = fakeModel,
            reasoningLevel = ReasoningLevel.NONE,
            projectId = "p1",
            parentSessionId = "sess_1",
            browser = "fake-direct",
            recipe = null,
            isTest = false
        )

        val event = completedEventDeferred.await()
        assertEquals(taskId, event.payload["taskId"])
        assertEquals("COMPLETED", event.payload["status"])
        assertEquals(SubagentRole.BROWSER_OPERATOR.name, event.payload["role"])
        assertEquals("false", event.payload["isTestTask"])
        // 无需判定：直接采用操作员的 message
        assertEquals("搜索完成，找到了3条记录", event.payload["message"])
        assertNull(event.payload["reportPath"])
        assertEquals(0, brainCallCount, "无需判定时不得调用 BrowserBrain")

        // 非自动化测试任务：执行完成后自动调用 control.close()
        assertTrue(fakeControl.log.contains("close"), "非测试任务结束应关闭浏览器")
    }

    @Test
    fun testJudgmentTaskCallsBrainForReport() = runBlocking {
        val fakeControl = FakeBrowserControl()
        BrowserRegistry.register("fake-judge", BrowserKind.JCEF, factory = { _, _ -> fakeControl })

        val eventBus = MutableSharedFlow<MederiEvent>(replay = 10)
        var brainCalled = false

        val fakeCaller = TestLLMCaller(
            onBrainCall = { brainCalled = true },
            brainSummary = "大脑判定：已成功抓取招聘列表",
            operatorDoneMessage = "提取了页面信息"
        )

        val manager = BrowserTaskManager(
            providerManager = FakeProviderManager(emptyMap()),
            scope = this,
            eventBus = eventBus,
            llmCallerProvider = { _, _, _ -> fakeCaller }
        )

        val completedEventDeferred = asyncEvent(eventBus, EventType.BROWSER_TASK_COMPLETED)

        val recipeWithRules = BrowserRecipe(
            name = "job_filter",
            description = "岗位筛选",
            judgeRules = "检查薪资是否大于20k"
        )

        val taskId = manager.runTask(
            task = "抓取并筛选岗位",
            aiModel = fakeModel,
            reasoningLevel = ReasoningLevel.NONE,
            projectId = "p1",
            parentSessionId = "sess_1",
            browser = "fake-judge",
            recipe = recipeWithRules,
            isTest = false
        )

        val event = completedEventDeferred.await()
        assertTrue(brainCalled, "存在判定规则时应调用 BrowserBrain 进行判定")
        assertEquals(taskId, event.payload["taskId"])
        assertEquals("大脑判定：已成功抓取招聘列表", event.payload["message"])
    }

    @Test
    fun testAutomatedTestTaskDoesNotCloseBrowser() = runBlocking {
        val fakeControl = FakeBrowserControl()
        BrowserRegistry.register("fake-test", BrowserKind.JCEF, factory = { _, _ -> fakeControl })

        val eventBus = MutableSharedFlow<MederiEvent>(replay = 10)

        val fakeCaller = TestLLMCaller(operatorDoneMessage = "断言通过")

        val manager = BrowserTaskManager(
            providerManager = FakeProviderManager(emptyMap()),
            scope = this,
            eventBus = eventBus,
            llmCallerProvider = { _, _, _ -> fakeCaller }
        )

        val completedEventDeferred = asyncEvent(eventBus, EventType.BROWSER_TASK_COMPLETED)

        manager.runTask(
            task = "对登录页进行自动化测试",
            aiModel = fakeModel,
            reasoningLevel = ReasoningLevel.NONE,
            projectId = "p1",
            parentSessionId = "sess_1",
            browser = "fake-test",
            recipe = null,
            isTest = true
        )

        val event = completedEventDeferred.await()
        assertEquals("true", event.payload["isTestTask"])
        // 自动化测试任务不应调用 control.close()，保留浏览器现场
        assertFalse(fakeControl.log.contains("close"), "自动化测试任务结束不应关闭浏览器")
    }

    @Test
    fun testCollectInfoPopulatesCollectorAndReachesBrain() = runBlocking {
        val fakeControl = FakeBrowserControl(
            currentUrl = "https://example.com/item",
            title = "Example Shop"
        )
        BrowserRegistry.register("fake-collect", BrowserKind.JCEF, factory = { _, _ -> fakeControl })

        val eventBus = MutableSharedFlow<MederiEvent>(replay = 10)
        var receivedBrainPrompt = ""

        var stepCount = 0
        val fakeCaller = object : BrowserLLMCaller {
            override suspend fun call(systemPrompt: String, userPrompt: String, image: ByteArray?): String {
                receivedBrainPrompt = userPrompt
                return """{"summary":"汇总报告：收集了商品","goal_achieved":true}"""
            }

            override suspend fun callWithTools(
                systemPrompt: String,
                userPrompt: String,
                tools: List<ToolBase<*, *>>,
                image: ByteArray?
            ): BrowserLLMResponse {
                stepCount++
                if (stepCount == 1) {
                    // 第一步：调用 collect_info 工具
                    return BrowserLLMResponse(
                        text = "Collecting info",
                        toolCalls = listOf(
                            BrowserToolCall(
                                id = "c1",
                                name = "collect_info",
                                argsJson = """{"content":"商品价格：¥99"}"""
                            )
                        )
                    )
                } else {
                    // 第二步：完成任务
                    return BrowserLLMResponse(
                        text = "Done",
                        toolCalls = listOf(
                            BrowserToolCall(
                                id = "c2",
                                name = "done",
                                argsJson = """{"message":"抓取完毕"}"""
                            )
                        )
                    )
                }
            }
        }

        val manager = BrowserTaskManager(
            providerManager = FakeProviderManager(emptyMap()),
            scope = this,
            eventBus = eventBus,
            llmCallerProvider = { _, _, _ -> fakeCaller }
        )

        val completedEventDeferred = asyncEvent(eventBus, EventType.BROWSER_TASK_COMPLETED)

        val recipeWithRules = BrowserRecipe(
            name = "shop_filter",
            judgeRules = "检查商品"
        )

        val taskId = manager.runTask(
            task = "搜索商品",
            aiModel = fakeModel,
            reasoningLevel = ReasoningLevel.NONE,
            projectId = "p1",
            parentSessionId = "sess_1",
            browser = "fake-collect",
            recipe = recipeWithRules
        )

        val event = completedEventDeferred.await()
        assertEquals(taskId, event.payload["taskId"])
        assertEquals("1", event.payload["collectedCount"])

        val details = manager.getTaskDetails(taskId)
        assertEquals(1, details?.collectedItems?.size)
        assertEquals("商品价格：¥99", details?.collectedItems?.first()?.content)
        assertEquals("https://example.com/item", details?.collectedItems?.first()?.url)
        assertEquals("Example Shop", details?.collectedItems?.first()?.siteName)

        assertTrue(receivedBrainPrompt.contains("Collected Research Items"), "Brain 汇报用户提示词应包含已收集条目")
        assertTrue(receivedBrainPrompt.contains("商品价格：¥99"))
    }

    private fun CoroutineScope.asyncEvent(bus: MutableSharedFlow<MederiEvent>, type: EventType) =
        async {
            bus.filter { it.type == type }.first()
        }
}
