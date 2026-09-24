package xyz.mederi.browser

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import xyz.mederi.domain.model.AIModel
import xyz.mederi.domain.model.EventType
import xyz.mederi.domain.model.MederiEvent
import xyz.mederi.domain.model.SubagentModelConfig
import xyz.mederi.domain.model.SubagentRole
import xyz.mederi.metadata.ModelMetadata
import xyz.mederi.provider.ProviderManager
import xyz.mederi.provider.domain.model.Provider
import xyz.mederi.provider.domain.model.ProviderApiKey
import xyz.mederi.provider.domain.model.ProviderType
import xyz.mederi.provider.domain.model.ReasoningLevel
import xyz.mederi.provider.domain.model.ReasoningParameter
import xyz.mederi.provider.domain.model.RemoteModelInfo
import xyz.mederi.store.InMemorySettingsStore
import xyz.mederi.tools.subagent.FakeProviderManager
import xyz.mederi.tools.subagent.SubagentConfigManager
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ST3 架构验证 capstone：BrowserTaskManager 完整接线后的【通信回路】验证，而非仅编译。
 *
 * 回路断言：
 * - 主管 → Operator：runTask 派发返回 taskId（bt_ 前缀）
 * - Operator → (judge) → Brain → 回流：Brain.judge 调用发生在 decide#1 与 decide#2 之间，
 *   判定结果经 BROWSER_TASK_STEP 事件回流给主管；recipe 判定规则经 judge 传入
 * - Operator → (execute_drill) → Drill → 回流：FakeBrowserControl.log 含 drill 脚本的
 *   navigate url（证 execute_drill 委托真实 DrillExecutor 执行）
 * - Operator → (done) → Brain(report) → 主管：Brain.generateFinalReport 被调用，
 *   taskStatus.message 含 Brain 简报、getTaskDetails().brainResult 暴露 summary、
 *   workingDir 真传（报告落盘 workingDir/reports/）
 * - 事件流：BROWSER_TASK_STARTED → BROWSER_TASK_STEP(≥1) → BROWSER_TASK_COMPLETED
 * - vision fallback：aiModel.supportsImages 门控——true → decide 收到截图；
 *   false → decide 收到 null（截图不发给模型）
 *
 * LLM 全部走 [BrowserLLMCaller] 注入（llmCallerProvider），providerManager 用不触达 stub
 * （TaskManager 在 llmCallerProvider!=null 时不引用 providerManager）。
 */
class BrowserArchitectureFlowTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ── 全链路决策脚本（FakeLLMCaller 按队列返回给 Operator decide） ──
    private val JUDGE_DECISION = """
        {"thought":"需要判定内容","memory":"先判定","actions":[{"type":"judge","content":"页面原始文本","instruction":"检查X是否存在"}],"is_done":false}
    """.trimIndent()

    private val DRILL_DECISION = """
        {"thought":"执行确定性脚本","memory":"跑drill","actions":[{"type":"execute_drill","script":{"task_name":"skill1-drill","loop_count":1,"steps":[{"step_name":"extract","action":"extract_list","selector_value":".item","record_fields":{"title":".title"}}],"item_steps":[{"step_name":"go","action":"navigate","url":"https://drill-target.example"}]}}],"is_done":false}
    """.trimIndent()

    private val DONE_DECISION = """
        {"thought":"完成","memory":"完成","actions":[{"type":"done","message":"找到了X"}],"is_done":true}
    """.trimIndent()

    private val DONE_ONLY_DECISION = """
        {"actions":[{"type":"done","message":"ok"}],"is_done":true}
    """.trimIndent()

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

    // ── 脚本化假 LLM：按 systemPrompt 路由 + 镜像 BrowserLLMHelper 的 vision 门控 ──

    private class FakeLLMCaller(
        private val supportsImages: Boolean,
        decisions: List<String> = emptyList()
    ) : BrowserLLMCaller {
        data class Call(val systemPrompt: String, val userPrompt: String, val image: ByteArray?)

        val calls = mutableListOf<Call>()
        private val queue = ArrayDeque(decisions)

        /** Operator decide 调用（systemPrompt 含 agent 标记）收到的截图（已门控）。 */
        fun decideImages(): List<ByteArray?> =
            calls.filter { DECIDE_MARKER in it.systemPrompt }.map { it.image }

        override suspend fun call(systemPrompt: String, userPrompt: String, image: ByteArray?): String {
            // 镜像 BrowserLLMHelper：模型不支持图片时截图不发给模型（记录为 null）
            val effectiveImage = if (supportsImages) image else null
            calls += Call(systemPrompt, userPrompt, effectiveImage)
            return when {
                DECIDE_MARKER in systemPrompt ->
                    queue.removeFirstOrNull() ?: """{"actions":[],"is_done":true}"""
                REPORT_MARKER in systemPrompt ->
                    """{"summary":"任务完成简报","goal_achieved":true,"details":"已完成目标"}"""
                else ->
                    // BrowserBrain judge
                    """{"match":"true","reason":"ok"}"""
            }
        }

        companion object {
            const val DECIDE_MARKER = "browser automation agent"
            const val REPORT_MARKER = "summarizing"
        }
    }

    // ── providerManager stub（llmCallerProvider!=null 时不应被触达） ──

    private class UntouchedProviderManager : ProviderManager {
        private fun untouched(): Nothing =
            error("BrowserTaskManager 在 llmCallerProvider!=null 时不应触达 providerManager")

        override suspend fun list(): List<Provider> = untouched()
        override suspend fun listWithoutKeys(): List<Provider> = untouched()
        override suspend fun get(id: String): Provider? = untouched()
        override suspend fun require(id: String): Provider = untouched()
        override suspend fun create(
            name: String,
            type: ProviderType,
            baseUrl: String,
            reasoningParameter: ReasoningParameter?,
            responseSanitization: Boolean,
            modelsDevKey: String?
        ): Provider = untouched()

        override suspend fun update(
            id: String,
            name: String?,
            baseUrl: String?,
            reasoningParameter: ReasoningParameter?,
            modelsDevKey: String?
        ): Provider = untouched()

        override suspend fun delete(id: String): Unit = untouched()
        override suspend fun listKeys(providerId: String): List<ProviderApiKey> = untouched()
        override suspend fun addKey(providerId: String, name: String, value: String, isDefault: Boolean): ProviderApiKey = untouched()
        override suspend fun deleteKey(providerId: String, keyId: String): Unit = untouched()
        override suspend fun setDefaultKey(providerId: String, keyId: String): Unit = untouched()
        override suspend fun getDefaultKeyValue(providerId: String): String? = untouched()
        override suspend fun getKeyValue(providerId: String, keyId: String): String? = untouched()
        override suspend fun listModels(providerId: String): List<AIModel> = untouched()
        override suspend fun addModel(
            providerId: String,
            providerModelId: String,
            name: String,
            supportsReasoning: Boolean,
            reasoningLevel: ReasoningLevel,
            contextWindow: Int?,
            maxTokens: Int?,
            supportsImages: Boolean,
            reasoningLevels: List<ReasoningLevel>,
            isEnabled: Boolean,
            inputPricePerMillion: Double?,
            outputPricePerMillion: Double?
        ): AIModel = untouched()

        override suspend fun addFetchedModel(providerId: String, merged: AIModel, isEnabled: Boolean): AIModel = untouched()
        override suspend fun applyRemoteMetadata(
            providerId: String,
            modelId: String,
            endpoint: RemoteModelInfo?,
            catalog: ModelMetadata?
        ): AIModel = untouched()

        override suspend fun updateUserModel(
            providerId: String,
            modelId: String,
            name: String?,
            supportsReasoning: Boolean?,
            reasoningLevel: ReasoningLevel?,
            contextWindow: Int?,
            maxTokens: Int?,
            supportsImages: Boolean?,
            reasoningLevels: List<ReasoningLevel>?,
            isEnabled: Boolean?
        ): AIModel = untouched()

        override suspend fun deleteModel(providerId: String, modelId: String): Unit = untouched()
        override suspend fun getModel(modelId: String): AIModel? = untouched()
        override suspend fun listAllModels(): List<AIModel> = untouched()
        override suspend fun fetchRemoteModels(providerId: String): List<RemoteModelInfo> = untouched()
    }

    // ── 辅助 ──

    private fun writeSkill(skillsDir: File) {
        skillsDir.mkdirs()
        File(skillsDir, "skill1.md").writeText(
            """
            ---
            description: 技能1：找X
            ---
            ## 操作规则
            - 【skill1-op】按步骤执行：先判定，再跑确定性脚本
            ## 判定规则
            - 【skill1-judge】匹配标准：X 出现即匹配
            ## Drill
            ```json
            {"task_name":"skill1-drill","loop_count":1,"steps":[{"step_name":"extract","action":"extract_list","selector_value":".item","record_fields":{"title":".title"}}],"item_steps":[{"step_name":"go","action":"navigate","url":"https://drill-target.example"}]}
            ```
            """.trimIndent()
        )
    }

    private fun newManager(
        scope: CoroutineScope,
        eventBus: MutableSharedFlow<MederiEvent>,
        workDir: File,
        llm: FakeLLMCaller,
        skillsDir: File? = null
    ): BrowserTaskManager = BrowserTaskManager(
        providerManager = UntouchedProviderManager(),
        scope = scope,
        eventBus = eventBus,
        workingDir = workDir,
        recipeStore = skillsDir?.let { RecipeStore(it) },
        llmCallerProvider = { _, _, _ -> llm }
    )

    /** 轮询 taskStatus 直到任务进入终态（COMPLETED / ERROR / STOPPED），超时 fail。 */
    private suspend fun awaitTerminal(manager: BrowserTaskManager, taskId: String): TaskStatusResult {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            val st = json.decodeFromString<TaskStatusResult>(manager.taskStatus(taskId))
            when (st.status) {
                "COMPLETED" -> return st
                "ERROR", "STOPPED" -> fail("任务提前终止: $st")
            }
            delay(50)
        }
        fail("等待任务终态超时: taskId=$taskId")
    }

    private suspend fun subscribe(scope: CoroutineScope, eventBus: MutableSharedFlow<MederiEvent>): MutableList<MederiEvent> {
        val events = mutableListOf<MederiEvent>()
        scope.launch { eventBus.collect { events += it } }
        while (eventBus.subscriptionCount.value == 0) delay(5)
        return events
    }

    // ── 1. 全链路通信回路：主管 → Operator → (judge) → Brain → 回流 / → (execute_drill) → Drill → 回流 /
    //      → (done) → Brain report → 主管 ──

    @Test
    fun `supervisor operator judge brain drill done full loop`() = runBlocking {
        val skillsDir = Files.createTempDirectory("arch-skills").toFile()
        writeSkill(skillsDir)
        val workDir = Files.createTempDirectory("arch-work").toFile()
        val eventBus = MutableSharedFlow<MederiEvent>(extraBufferCapacity = 128)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val llm = FakeLLMCaller(
                supportsImages = true,
                decisions = listOf(JUDGE_DECISION, DRILL_DECISION, DONE_DECISION)
            )
            val control = FakeBrowserControl(
                elements = mapOf(".item" to FakeElement(count = 1)),
                jsResults = mapOf("document.querySelectorAll" to """[{"title":"X","link":"https://x"}]""")
            )
            BrowserRegistry.register("fake-arch", BrowserKind.JCEF, factory = { control })
            val manager = newManager(scope, eventBus, workDir, llm, skillsDir = skillsDir)
            val events = subscribe(scope, eventBus)

            // (a) 主管 → Operator：runTask 派发，立即返回 taskId
            val taskId = manager.runTask(
                task = "去 skill1 找 X",
                aiModel = visionModel,
                reasoningLevel = ReasoningLevel.NONE,
                projectId = "p1",
                parentSessionId = "s1",
                browser = "fake-arch",
                recipe = BrowserRecipe(name = "skill1")
            )
            assertTrue(taskId.startsWith("bt_"), "runTask 应返回 bt_ 前缀 taskId: $taskId")
            assertTrue(taskId != "none", "有注册浏览器时不得返回 none")

            // 等全链路完成
            val finalStatus = awaitTerminal(manager, taskId)
            assertTrue(finalStatus.status == "COMPLETED", "任务应 COMPLETED: $finalStatus")

            val decideCalls = llm.calls.filter { FakeLLMCaller.DECIDE_MARKER in it.systemPrompt }
            assertEquals(3, decideCalls.size, "Operator 应经历 3 步 decide：judge / execute_drill / done")

            // (b) Operator → Brain(judge) → 回流
            // 路由与 FakeLLMCaller 一致：非 decide（agent 标记）且非 report（summarizing 标记）即 judge
            // （Operator 的 systemPrompt 里也提到 BrowserBrain，不能拿 "BrowserBrain" 当 judge 判别）
            val judgeCalls = llm.calls.filter {
                FakeLLMCaller.DECIDE_MARKER !in it.systemPrompt && FakeLLMCaller.REPORT_MARKER !in it.systemPrompt
            }
            assertEquals(1, judgeCalls.size, "Brain.judge 应被触发一次")
            val judgeIdx = llm.calls.indexOf(judgeCalls[0])
            val decide0Idx = llm.calls.indexOf(decideCalls[0])
            val decide1Idx = llm.calls.indexOf(decideCalls[1])
            assertTrue(
                judgeIdx > decide0Idx && judgeIdx < decide1Idx,
                "judge 调用应在 decide#1 与 decide#2 之间（判定结果回流到下一决策）"
            )
            // recipe 判定规则（skill1 → RecipeStore）应传进 Brain.judge
            assertTrue(judgeCalls[0].userPrompt.contains("【skill1-judge】"), "judge userPrompt 应含配方判定规则")
            // judge 结果经 onStep 回流给主管（BROWSER_TASK_STEP payload results=judge:true）
            val stepEvents = events.filter { it.type == EventType.BROWSER_TASK_STEP }
            assertTrue(
                stepEvents.any { it.payload["results"]?.contains("judge:true") == true },
                "STEP 事件应含 judge:true 结果, events=${stepEvents.map { it.payload }}"
            )

            // (c) Operator → DrillExecutor(execute_drill) → 回流
            assertTrue(
                control.log.contains("navigate https://drill-target.example"),
                "DrillExecutor 应执行脚本里 navigate, log=${control.log}"
            )
            assertTrue(
                stepEvents.any { it.payload["results"]?.contains("drill:true") == true },
                "STEP 事件应含 drill:true 结果"
            )
            assertTrue(control.log.contains("start") && control.log.contains("close"), "Operator 应完成浏览器生命周期")

            // RecipeStore 解析证据：skill1 的 operatorContent 注入 Operator 提示词
            assertTrue(decideCalls[0].userPrompt.contains("【skill1-op】"), "decide userPrompt 应含配方操作规则（RecipeStore 解析后）")

            // (d) Operator → Brain(report) → 主管
            assertTrue(llm.calls.any { FakeLLMCaller.REPORT_MARKER in it.systemPrompt }, "Brain.generateFinalReport 应被调用")
            assertTrue(
                finalStatus.message?.contains("任务完成简报") == true,
                "taskStatus.message 应为 Brain 简报: ${finalStatus.message}"
            )
            val details = manager.getTaskDetails(taskId)
            assertNotNull(details, "getTaskDetails 应返回任务")
            assertNotNull(details.brainResult, "brainResult 应暴露给 getTaskDetails")
            assertEquals("任务完成简报", details.brainResult.data["summary"])
            // workingDir 真传：Brain 报告落盘 workingDir/reports/
            val reportsDir = File(workDir, "reports")
            assertTrue(
                reportsDir.exists() && reportsDir.listFiles()?.isNotEmpty() == true,
                "Brain 报告应写入 workingDir/reports (workingDir 接线)"
            )

            // (e) 事件流：STARTED → STEP(≥1) → COMPLETED
            val started = events.filter { it.type == EventType.BROWSER_TASK_STARTED }
            val completed = events.filter { it.type == EventType.BROWSER_TASK_COMPLETED }
            assertTrue(
                started.size >= 1 && stepEvents.size >= 1 && completed.size >= 1,
                "应收到 STARTED/STEP/COMPLETED: ${events.map { it.type }}"
            )
            val browserOrderEvents = events.filter {
                it.type == EventType.BROWSER_TASK_STARTED ||
                    it.type == EventType.BROWSER_TASK_STEP ||
                    it.type == EventType.BROWSER_TASK_COMPLETED
            }.map { it.type }
            assertTrue(
                browserOrderEvents.indexOf(EventType.BROWSER_TASK_STARTED) <
                    browserOrderEvents.indexOf(EventType.BROWSER_TASK_STEP),
                "事件顺序应 STARTED 先于 STEP"
            )
            assertTrue(
                browserOrderEvents.indexOf(EventType.BROWSER_TASK_STEP) <
                    browserOrderEvents.indexOf(EventType.BROWSER_TASK_COMPLETED),
                "事件顺序应 STEP 先于 COMPLETED"
            )
        } finally {
            BrowserRegistry.unregister("fake-arch")
            scope.cancel()
        }
    }

    // ── 2. vision fallback：supportsImages=true → decide 收到截图 ──

    @Test
    fun `vision gate passes screenshot when model supports images`() = runBlocking {
        val workDir = Files.createTempDirectory("arch-vision-on").toFile()
        val eventBus = MutableSharedFlow<MederiEvent>(extraBufferCapacity = 128)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val llm = FakeLLMCaller(supportsImages = true, decisions = listOf(DONE_ONLY_DECISION))
            val control = FakeBrowserControl()
            BrowserRegistry.register("fake-arch", BrowserKind.JCEF, factory = { control })
            val manager = newManager(scope, eventBus, workDir, llm)

            val taskId = manager.runTask(
                task = "vision task",
                aiModel = visionModel,
                reasoningLevel = ReasoningLevel.NONE,
                projectId = "p",
                parentSessionId = "s",
                browser = "fake-arch"
            )
            awaitTerminal(manager, taskId)

            val decideImages = llm.decideImages()
            assertEquals(1, decideImages.size, "Operator 应经历 1 步 decide")
            val image = assertNotNull(decideImages[0], "supportsImages=true 时 decide 应收到截图")
            assertContentEquals(FakeBrowserControl.FIXED_SCREENSHOT, image, "截图字节应原样传递")
        } finally {
            BrowserRegistry.unregister("fake-arch")
            scope.cancel()
        }
    }

    // ── 3. vision fallback：supportsImages=false → decide 收到 null（截图不发给模型） ──

    @Test
    fun `vision gate withholds screenshot when model does not support images`() = runBlocking {
        val workDir = Files.createTempDirectory("arch-vision-off").toFile()
        val eventBus = MutableSharedFlow<MederiEvent>(extraBufferCapacity = 128)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val llm = FakeLLMCaller(supportsImages = false, decisions = listOf(DONE_ONLY_DECISION))
            val control = FakeBrowserControl()
            BrowserRegistry.register("fake-arch", BrowserKind.JCEF, factory = { control })
            val manager = newManager(scope, eventBus, workDir, llm)

            val taskId = manager.runTask(
                task = "text task",
                aiModel = textOnlyModel,
                reasoningLevel = ReasoningLevel.NONE,
                projectId = "p",
                parentSessionId = "s",
                browser = "fake-arch"
            )
            awaitTerminal(manager, taskId)

            val decideImages = llm.decideImages()
            assertEquals(1, decideImages.size, "Operator 应经历 1 步 decide")
            assertNull(decideImages[0], "supportsImages=false 时 decide 不得收到截图（vision fallback 门控）")
        } finally {
            BrowserRegistry.unregister("fake-arch")
            scope.cancel()
        }
    }

    // ── 4. 双角色模型解析：operator/brain 各用配置的模型（BrowserTaskManager 按角色 resolve） ──

    @Test
    fun `operator and brain use their configured models`() = runBlocking {
        val workDir = Files.createTempDirectory("arch-models").toFile()
        val eventBus = MutableSharedFlow<MederiEvent>(extraBufferCapacity = 128)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val control = FakeBrowserControl()
            BrowserRegistry.register("fake-arch", BrowserKind.JCEF, factory = { control })

            val opModel = AIModel(id = "op", providerModelId = "op", name = "op", supportsImages = true)
            val brainModel = AIModel(id = "brain", providerModelId = "brain", name = "brain")
            val configManager = SubagentConfigManager(
                InMemorySettingsStore(),
                FakeProviderManager(mapOf("op" to opModel, "brain" to brainModel))
            )
            configManager.set(
                SubagentRole.BROWSER_OPERATOR,
                SubagentModelConfig(modelId = "op", reasoningLevel = ReasoningLevel.HIGH)
            )
            configManager.set(
                SubagentRole.BROWSER_BRAIN,
                SubagentModelConfig(modelId = "brain", reasoningLevel = ReasoningLevel.LOW)
            )

            // llmCallerProvider 按 operator→brain 顺序各调一次，记录收到的模型/推理档
            val received = mutableListOf<Pair<AIModel, ReasoningLevel>>()
            val manager = BrowserTaskManager(
                providerManager = UntouchedProviderManager(),
                scope = scope,
                eventBus = eventBus,
                workingDir = workDir,
                subagentConfigManager = configManager,
                llmCallerProvider = { model, reasoning, _ ->
                    received += model to reasoning
                    FakeLLMCaller(model.supportsImages, decisions = listOf(DONE_ONLY_DECISION))
                }
            )

            val taskId = manager.runTask(
                task = "model test",
                aiModel = visionModel,
                reasoningLevel = ReasoningLevel.NONE,
                projectId = "p",
                parentSessionId = "s",
                browser = "fake-arch"
            )
            awaitTerminal(manager, taskId)

            assertEquals(2, received.size, "llmCallerProvider 应按 operator/brain 各调一次: $received")
            assertEquals("op", received[0].first.id)
            assertEquals(ReasoningLevel.HIGH, received[0].second)
            assertEquals("brain", received[1].first.id)
            assertEquals(ReasoningLevel.LOW, received[1].second)
        } finally {
            BrowserRegistry.unregister("fake-arch")
            scope.cancel()
        }
    }

    @Test
    fun `unconfigured brain inherits parent session model`() = runBlocking {
        val workDir = Files.createTempDirectory("arch-models-inherit").toFile()
        val eventBus = MutableSharedFlow<MederiEvent>(extraBufferCapacity = 128)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val control = FakeBrowserControl()
            BrowserRegistry.register("fake-arch", BrowserKind.JCEF, factory = { control })

            val opModel = AIModel(id = "op", providerModelId = "op", name = "op", supportsImages = true)
            val configManager = SubagentConfigManager(
                InMemorySettingsStore(),
                FakeProviderManager(mapOf("op" to opModel))
            )
            configManager.set(
                SubagentRole.BROWSER_OPERATOR,
                SubagentModelConfig(modelId = "op", reasoningLevel = ReasoningLevel.HIGH)
            )
            // BROWSER_BRAIN 未配置 → 继承父会话模型/推理档

            val received = mutableListOf<Pair<AIModel, ReasoningLevel>>()
            val manager = BrowserTaskManager(
                providerManager = UntouchedProviderManager(),
                scope = scope,
                eventBus = eventBus,
                workingDir = workDir,
                subagentConfigManager = configManager,
                llmCallerProvider = { model, reasoning, _ ->
                    received += model to reasoning
                    FakeLLMCaller(model.supportsImages, decisions = listOf(DONE_ONLY_DECISION))
                }
            )

            val taskId = manager.runTask(
                task = "inherit test",
                aiModel = visionModel,
                reasoningLevel = ReasoningLevel.NONE,
                projectId = "p",
                parentSessionId = "s",
                browser = "fake-arch"
            )
            awaitTerminal(manager, taskId)

            assertEquals(2, received.size, "llmCallerProvider 应按 operator/brain 各调一次: $received")
            assertEquals("op", received[0].first.id)
            assertEquals(ReasoningLevel.HIGH, received[0].second)
            assertEquals("m-vision", received[1].first.id, "未配置的 brain 应继承父会话模型 visionModel")
            assertEquals(ReasoningLevel.NONE, received[1].second)
        } finally {
            BrowserRegistry.unregister("fake-arch")
            scope.cancel()
        }
    }
}