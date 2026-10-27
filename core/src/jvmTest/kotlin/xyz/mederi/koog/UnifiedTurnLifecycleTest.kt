package xyz.mederi.koog

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.utils.time.KoogClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.mederi.api.SendMessageRequest
import xyz.mederi.domain.model.*
import xyz.mederi.infrastructure.koog.TurnExecutor
import xyz.mederi.infrastructure.koog.HistoryStoreChatHistoryProvider
import xyz.mederi.infrastructure.koog.isCompleteDeliverable
import xyz.mederi.project.ProjectManager
import xyz.mederi.provider.ProviderManager
import xyz.mederi.provider.domain.model.*
import xyz.mederi.provider.infrastructure.koog.KoogClientFactory
import xyz.mederi.store.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** 真实 Koog 图 + 可控传输，不联网、不启动宿主，验证统一输入到最终交付的行为。 */
class UnifiedTurnLifecycleTest {
    private class TestProviderManager(private val provider: xyz.mederi.provider.domain.model.Provider) : ProviderManager {
        private fun unused(): Nothing = throw UnsupportedOperationException("not used in this test")
        override suspend fun list() = listOf(provider)
        override suspend fun listWithoutKeys() = listOf(provider)
        override suspend fun get(id: String) = provider
        override suspend fun require(id: String) = provider
        override suspend fun create(name: String, type: xyz.mederi.provider.domain.model.ProviderType, baseUrl: String, reasoningParameter: xyz.mederi.provider.domain.model.ReasoningParameter?, responseSanitization: Boolean, modelsDevKey: String?) = unused()
        override suspend fun update(id: String, name: String?, baseUrl: String?, reasoningParameter: xyz.mederi.provider.domain.model.ReasoningParameter?, modelsDevKey: String?) = unused()
        override suspend fun delete(id: String) = unused()
        override suspend fun listKeys(providerId: String) = provider.apiKeys
        override suspend fun addKey(providerId: String, name: String, value: String, isDefault: Boolean) = unused()
        override suspend fun deleteKey(providerId: String, keyId: String) = unused()
        override suspend fun setDefaultKey(providerId: String, keyId: String) = unused()
        override suspend fun getDefaultKeyValue(providerId: String) = "test-key"
        override suspend fun getKeyValue(providerId: String, keyId: String) = "test-key"
        override suspend fun listModels(providerId: String) = unused()
        override suspend fun addModel(providerId: String, providerModelId: String, name: String, supportsReasoning: Boolean, reasoningLevel: ReasoningLevel, contextWindow: Int?, maxTokens: Int?, supportsImages: Boolean, reasoningLevels: List<ReasoningLevel>, isEnabled: Boolean, inputPricePerMillion: Double?, outputPricePerMillion: Double?) = unused()
        override suspend fun addFetchedModel(providerId: String, merged: AIModel, isEnabled: Boolean) = unused()
        override suspend fun applyRemoteMetadata(providerId: String, modelId: String, endpoint: xyz.mederi.provider.domain.model.RemoteModelInfo?, catalog: xyz.mederi.metadata.ModelMetadata?) = unused()
        override suspend fun updateUserModel(providerId: String, modelId: String, name: String?, supportsReasoning: Boolean?, reasoningLevel: ReasoningLevel?, contextWindow: Int?, maxTokens: Int?, supportsImages: Boolean?, reasoningLevels: List<ReasoningLevel>?, isEnabled: Boolean?) = unused()
        override suspend fun deleteModel(providerId: String, modelId: String) = unused()
        override suspend fun getModel(modelId: String) = unused()
        override suspend fun listAllModels() = unused()
        override suspend fun fetchRemoteModels(providerId: String) = unused()
    }

    private class StubProjectManager(val project: Project) : ProjectManager {
        override suspend fun list(): List<Project> = listOf(project)
        override suspend fun get(id: String): Project? = if (id == project.id) project else null
        override suspend fun require(id: String): Project = project
        override suspend fun create(name: String, directory: String): Project = project
        override suspend fun delete(id: String) = Unit
        override suspend fun rename(id: String, name: String): Project = project
    }


    private class ScriptClient(
        provider: Provider,
        private val script: suspend FlowCollector<StreamFrame>.(Prompt, Int) -> Unit,
    ) : LLMClient() {
        private val delegate = KoogClientFactory.create(provider, "test-key")
        val calls = AtomicInteger()
        val prompts = CopyOnWriteArrayList<Prompt>()
        override val clientName = "UnifiedTurnTest"
        override fun llmProvider() = delegate.llmProvider()
        override fun close() = delegate.close()
        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): ai.koog.prompt.message.Message.Assistant = error("expected true streaming")
        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> = flow {
            prompts.add(prompt)
            script(prompt, calls.incrementAndGet())
        }
        override suspend fun executeMultipleChoices(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): LLMChoice = error("unused")
        override suspend fun moderate(prompt: Prompt, model: LLModel) = delegate.moderate(prompt, model)
        override suspend fun models() = emptyList<LLModel>()
        override fun getStandardJsonSchemaGenerator() = delegate.getStandardJsonSchemaGenerator()
        override fun getBasicJsonSchemaGenerator() = delegate.getBasicJsonSchemaGenerator()
    }

    private class Fixture(script: suspend FlowCollector<StreamFrame>.(Prompt, Int) -> Unit) {
        private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }.first { File(it, "gradlew").exists() }
        val directory = File(root, "temp/unified-turn-${UUID.randomUUID()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val history = InMemoryHistoryStore()
        val sessions = InMemorySessionStore()
        val events = CopyOnWriteArrayList<MederiEvent>()
        val bus = MutableSharedFlow<MederiEvent>(extraBufferCapacity = 128)
        val model = AIModel(id = "test-model", providerModelId = "test-model", name = "Test", supportsImages = true)
        val provider = Provider("test-provider", "Test", ProviderType.OPENAI_CHAT, "http://unused.invalid/v1", models = listOf(model),
            apiKeys = listOf(ProviderApiKey("key", "Test", "***", true)))
        val client = ScriptClient(provider, script)
        val executor = TurnExecutor(sessions, history, bus, TestProviderManager(provider),
            StubProjectManager(Project("project", "Test", directory.absolutePath, "now", "now")),
            scope = scope, llmClientFactory = { _, _ -> client })
        init { scope.launch(start = CoroutineStart.UNDISPATCHED) { bus.collect { events.add(it) } } }
        suspend fun initialize() {
            val now = java.time.Instant.now().toString()
            sessions.insert(Session(id = "session", projectId = "project", title = "Test", status = SessionStatus.IDLE,
                agentMode = AgentMode.AUTONOMOUS, aiModel = model, reasoningLevel = ReasoningLevel.NONE,
                env = emptyMap(), createdAt = now, updatedAt = now))
        }
        suspend fun send(id: String, text: String, parts: List<MessagePart> = listOf(MessagePart.Text(text))) {
            executor.sendMessage("session", SendMessageRequest(xyz.mederi.api.AgentConfig(AgentMode.AUTONOMOUS, model, ReasoningLevel.NONE), parts, messageId = id))
        }
        suspend fun completed() = withTimeout(15_000) {
            while (events.none { it.type == EventType.MESSAGE_COMPLETED && it.payload["workCompletedAt"] != null }) delay(10)
        }
        suspend fun close() {
            scope.cancel()
            scope.coroutineContext[Job]?.join()
            client.close()
            directory.deleteRecursively()
        }
    }

    private suspend fun FlowCollector<StreamFrame>.answer(text: String, reason: String = "stop") {
        emit(StreamFrame.TextDelta(text))
        emit(StreamFrame.TextComplete(text))
        emit(StreamFrame.End(reason, ResponseMetaInfo.create(KoogClock.System)))
    }

    @Test
    fun toolLoopConfirmsWholeFinalResponseAndOrdersToolEvents() = runBlocking {
        val fixture = Fixture { _, call ->
            if (call == 1) {
                emit(StreamFrame.TextDelta("reading"))
                emit(StreamFrame.TextComplete("reading"))
                emit(StreamFrame.ToolCallComplete("read", "read_file", buildJsonObject { put("path", "source.txt") }.toString()))
                emit(StreamFrame.End("tool_calls", ResponseMetaInfo.create(KoogClock.System)))
            } else {
                emit(StreamFrame.TextDelta("complete analysis"))
                emit(StreamFrame.TextComplete("complete analysis"))
                emit(StreamFrame.ReasoningDelta(text = "check"))
                emit(StreamFrame.ReasoningComplete(null, listOf("check")))
                emit(StreamFrame.TextDelta("closing sentence"))
                emit(StreamFrame.TextComplete("closing sentence"))
                emit(StreamFrame.End("stop", ResponseMetaInfo.create(KoogClock.System)))
            }
        }
        try {
            fixture.initialize()
            File(fixture.directory, "source.txt").writeText("source evidence")
            fixture.send("initial", "analyze")
            fixture.completed()
            val assistants = fixture.history.load("session").filter { it.role == MessageRole.ASSISTANT }
            assertEquals(2, assistants.size)
            assertEquals(false, assistants.first().isFinalAnswer)
            val final = assistants.last()
            assertEquals(true, final.isFinalAnswer)
            assertEquals(listOf("complete analysis", "closing sentence"), final.parts.filterIsInstance<MessagePart.Text>().map { it.text })
            val responseStarts = fixture.events.filter { it.payload["type"] == "response_started" }.map { it.messageId }
            assertEquals(assistants.map { it.id }, responseStarts)
            val completedIndex = fixture.events.indexOfLast { it.type == EventType.MESSAGE_COMPLETED }
            assertTrue(fixture.events.indexOfLast { it.type == EventType.TOOL_RESULT } in 0 until completedIndex)
            assertEquals(1, assistants.mapNotNull { it.turnId }.distinct().size)
        } finally { fixture.close() }
    }

    @Test
    fun runningInputIsDurableDeduplicatedAndIncludesImages() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fixture = Fixture { _, call ->
            if (call == 1) { started.complete(Unit); release.await() }
            answer("answer $call")
        }
        try {
            fixture.initialize()
            fixture.send("initial", "analyze")
            withTimeout(10_000) { started.await() }
            val imageParts = listOf(MessagePart.Text("same text"), MessagePart.Image("data:image/png;base64,aGVsbG8=", "image/png"))
            fixture.send("steer-one", "same text", imageParts)
            fixture.send("steer-one", "same text", imageParts)
            fixture.send("steer-two", "same text")
            val pending = fixture.history.load("session").filter { it.pendingSteering }
            assertEquals(listOf("steer-one", "steer-two"), pending.map { it.id })
            assertTrue(pending.first().parts.any { it is MessagePart.Image })
            val clockStart = fixture.history.load("session").first { it.id == "initial" }.workStartedAt
            assertNotNull(clockStart)
            assertTrue(pending.all { it.workStartedAt == clockStart && it.workCompletedAt == null })
            assertTrue(HistoryStoreChatHistoryProvider.aiViewWindow(fixture.history, "session").none { it.pendingSteering })
            release.complete(Unit)
            fixture.completed()
            val history = fixture.history.load("session")
            assertEquals(1, history.count { it.id == "steer-one" })
            assertEquals(1, history.count { it.id == "steer-two" })
            assertTrue(history.none { it.pendingSteering })
            val firstResponse = history.indexOfFirst { it.role == MessageRole.ASSISTANT }
            assertTrue(firstResponse < history.indexOfFirst { it.id == "steer-one" },
                "接收时间早于响应落库，不代表模型已消费：历史必须保持响应在待消费插话之前")
            assertEquals(1, history.count { it.isFinalAnswer == true })
            assertTrue(fixture.client.prompts.drop(1).any { prompt -> prompt.messages.flatMap { it.parts }.any { it is ai.koog.prompt.message.MessagePart.Attachment } })
            val completedAt = history.first { it.isFinalAnswer == true }.workCompletedAt
            assertNotNull(completedAt)
            assertTrue(completedAt >= clockStart)
            assertTrue(history.filter { it.turnId == history.first().turnId }.all {
                it.workStartedAt == clockStart && it.workCompletedAt == completedAt
            }, "同工作所有响应、插话与重开历史使用相同墙钟端点")
            assertTrue(fixture.events.filter { it.payload["type"] == "response_started" }.all {
                it.payload["workStartedAt"] == clockStart.toString() && it.payload["workCompletedAt"] == null
            })
        } finally { release.complete(Unit); fixture.close() }
    }

    @Test
    fun inputWhileWaitingForQuestionReleasesSuspensionWithoutDeadlock() = runBlocking {
        val fixture = Fixture { _, call ->
            if (call == 1) {
                val args = xyz.mederi.tools.AgentTools.AskUserArgs(listOf(
                    xyz.mederi.tools.AgentTools.AskUserQuestionArg("question", "Which approach?")))
                emit(StreamFrame.ToolCallComplete("ask", "ask_user", kotlinx.serialization.json.Json.encodeToString(args)))
                emit(StreamFrame.End("tool_calls", ResponseMetaInfo.create(KoogClock.System)))
            } else answer("continued with the new instruction")
        }
        try {
            fixture.initialize()
            fixture.send("initial", "analyze")
            withTimeout(10_000) { while (fixture.events.none { it.type == EventType.QUESTION_REQUESTED }) delay(10) }
            val initialWork = fixture.history.load("session").first { it.id == "initial" }
            withTimeout(10_000) { fixture.send("followup", "Use the simple approach") }
            fixture.completed()
            val history = fixture.history.load("session")
            assertEquals(1, history.count { it.id == "followup" })
            assertTrue(history.flatMap { it.parts }.filterIsInstance<MessagePart.ToolResult>().any { it.tool == "ask_user" })
            assertEquals(1, history.count { it.isFinalAnswer == true })
            assertTrue(history.filter { it.role == MessageRole.ASSISTANT }.all {
                it.turnId == initialWork.turnId && it.workStartedAt == initialWork.workStartedAt
            }, "中性解除提问继续原任务，不能重置工作ID或墙钟起点")
            assertEquals(1, history.filter { it.turnId == initialWork.turnId }.map { it.workCompletedAt }.distinct().size)
        } finally { fixture.close() }
    }

    @Test
    fun queuedInputBeforeQuestionIsNotStranded() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fixture = Fixture { _, call ->
            if (call == 1) {
                started.complete(Unit)
                release.await()
                val args = xyz.mederi.tools.AgentTools.AskUserArgs(listOf(
                    xyz.mederi.tools.AgentTools.AskUserQuestionArg("question", "Which approach?")))
                emit(StreamFrame.ToolCallComplete("ask", "ask_user", kotlinx.serialization.json.Json.encodeToString(args)))
                emit(StreamFrame.End("tool_calls", ResponseMetaInfo.create(KoogClock.System)))
            } else answer("continued with the instruction already received")
        }
        try {
            fixture.initialize()
            fixture.send("initial", "analyze")
            withTimeout(10_000) { started.await() }
            fixture.send("followup", "Use the simple approach")
            release.complete(Unit)
            fixture.completed()
            assertEquals(1, fixture.history.load("session").count { it.id == "followup" })
            assertTrue(fixture.history.load("session").none { it.pendingSteering })
        } finally { release.complete(Unit); fixture.close() }
    }

    @Test
    fun childTerminalEventGapAllowsPhaseReplyWithoutFinishingWorkAndKeepsWorkId() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fixture = Fixture { _, call ->
            if (call == 1) { started.complete(Unit); release.await() }
            answer("answer $call")
        }
        try {
            fixture.initialize()
            fixture.send("initial", "research")
            withTimeout(10_000) { started.await() }
            val managerField = TurnExecutor::class.java.getDeclaredField("subagentManager").apply { isAccessible = true }
            val manager = managerField.get(fixture.executor) as xyz.mederi.tools.subagent.SubagentManager
            val agentsField = manager::class.java.getDeclaredField("agents").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val agents = agentsField.get(manager) as MutableMap<String, xyz.mederi.tools.subagent.SubagentManager.BackgroundAgent>
            agents["child"] = xyz.mederi.tools.subagent.SubagentManager.BackgroundAgent(
                agentId = "child", role = SubagentRole.RESEARCHER, parentSessionId = "session", job = Job().apply { complete() },
                status = xyz.mederi.tools.subagent.SubagentManager.SubagentStatus.COMPLETED, result = "evidence", progress = "done")
            release.complete(Unit)
            withTimeout(10_000) { while (fixture.events.none { it.type == EventType.MESSAGE_COMPLETED }) delay(10) }
            val phaseReply = fixture.history.load("session").single { it.isFinalAnswer == true }
            assertEquals("answer 1", phaseReply.parts.filterIsInstance<MessagePart.Text>().single().text,
                "主代理正常结束的一轮回复应外显，不能因等待子代理藏起来")
            assertNull(phaseReply.workCompletedAt, "阶段交付不等于整个工作完成")
            val initialStart = fixture.history.load("session").first { it.id == "initial" }.workStartedAt
            assertNotNull(initialStart)
            val waitingEnd = fixture.events.first { it.type == EventType.MESSAGE_COMPLETED }
            assertEquals(initialStart.toString(), waitingEnd.payload["workStartedAt"])
            assertEquals(phaseReply.id, waitingEnd.payload["finalMessageId"])
            assertNull(waitingEnd.payload["workCompletedAt"], "等待子代理不是工作结束，计时不能冻结")
            fixture.bus.emit(MederiEvent(EventType.SUBAGENT_COMPLETED, "session", payload = mapOf(
                "agentId" to "child", "status" to "COMPLETED", "role" to "RESEARCHER", "result" to "evidence"),
                timestamp = java.time.Instant.now().toString()))
            fixture.completed()
            val assistants = fixture.history.load("session").filter { it.role == MessageRole.ASSISTANT }
            assertEquals(2, assistants.size)
            assertEquals(1, assistants.map { it.turnId }.distinct().size, "自动唤醒仍属于原工作")
            assertTrue(assistants.all { it.isFinalAnswer == true }, "后一轮交付不得抹除先前阶段回复")
            assertTrue(assistants.all { it.workStartedAt == initialStart })
            assertNotNull(assistants.last().workCompletedAt)
            assertEquals(1, assistants.map { it.workCompletedAt }.distinct().size)
        } finally { release.complete(Unit); fixture.close() }
    }

    @Test
    fun interruptedOrLengthLimitedResponseNeverBecomesFinal() = runBlocking {
        val fixture = Fixture { _, _ -> answer("incomplete response", "length") }
        try {
            fixture.initialize()
            fixture.send("initial", "analyze")
            withTimeout(10_000) { while (fixture.events.none { it.type == EventType.MESSAGE_COMPLETED }) delay(10) }
            assertTrue(fixture.events.last { it.type == EventType.MESSAGE_COMPLETED }.payload["finalMessageId"] == null)
            assertTrue(fixture.history.load("session").none { it.isFinalAnswer == true })
        } finally { fixture.close() }
    }

    @Test
    fun abortKeepsAcceptedInputWithoutAutomaticExecution() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val fixture = Fixture { _, _ -> started.complete(Unit); awaitCancellation() }
        try {
            fixture.initialize()
            fixture.send("initial", "analyze")
            withTimeout(10_000) { started.await() }
            fixture.send("pending", "additional instruction")
            withTimeout(10_000) { fixture.executor.abortAndJoin("session") }
            assertTrue(fixture.history.load("session").single { it.id == "pending" }.pendingSteering)
            assertTrue(fixture.history.load("session").none { it.isFinalAnswer == true })
            assertEquals(1, fixture.client.calls.get())
            val initial = fixture.history.load("session").first { it.id == "initial" }
            assertNotNull(initial.workStartedAt)
            assertNotNull(initial.workCompletedAt, "中止后历史计时必须固定，不能重开继续增长")
            withTimeout(10_000) { while (fixture.events.none { it.type == EventType.MESSAGE_ERROR }) delay(10) }
            val cancellation = fixture.events.last { it.type == EventType.MESSAGE_ERROR }
            assertEquals(initial.workCompletedAt.toString(), cancellation.payload["workCompletedAt"])
        } finally { fixture.close() }
    }

    @Test
    fun deliverableRequiresNormalEndAndNonemptyText() {
        fun message(reason: String?, text: String) = Message(sessionId = "s", role = MessageRole.ASSISTANT,
            parts = listOf(MessagePart.Text(text)), createdAt = "now", finishReason = reason)
        assertTrue(message("stop", "answer").isCompleteDeliverable())
        for (reason in listOf(null, "length", "content_filter", "tool_calls", "unknown")) {
            assertFalse(message(reason, "partial").isCompleteDeliverable())
        }
        assertFalse(message("stop", "").isCompleteDeliverable())
    }
}
