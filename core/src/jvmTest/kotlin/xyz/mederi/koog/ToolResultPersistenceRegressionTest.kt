package xyz.mederi.koog

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message as KoogMessage
import ai.koog.prompt.message.MessagePart as KoogMessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.utils.time.KoogClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.mederi.api.AgentConfig
import xyz.mederi.api.SendMessageRequest
import xyz.mederi.domain.model.AIModel
import xyz.mederi.domain.model.AgentMode
import xyz.mederi.domain.model.EventType
import xyz.mederi.domain.model.MederiEvent
import xyz.mederi.domain.model.MessagePart
import xyz.mederi.domain.model.MessageRole
import xyz.mederi.domain.model.Project
import xyz.mederi.domain.model.Session
import xyz.mederi.domain.model.SessionStatus
import xyz.mederi.infrastructure.koog.TurnExecutor
import xyz.mederi.project.ProjectManager
import xyz.mederi.provider.ProviderManager
import xyz.mederi.provider.domain.model.Provider
import xyz.mederi.provider.domain.model.ProviderApiKey
import xyz.mederi.provider.domain.model.ProviderType
import xyz.mederi.provider.domain.model.ReasoningLevel
import xyz.mederi.provider.infrastructure.koog.KoogClientFactory
import xyz.mederi.store.InMemoryHistoryStore
import xyz.mederi.store.InMemorySessionStore
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 端到端回归：同轮内出现两次指纹相同（如重复 read_file({}) 失败）的 ToolResult 后，
 * Turn 结束回写不得在最终回复后追加重复 ToolResult，且 Turn 2 发往模型的 Prompt 结构合法。
 */
class ToolResultPersistenceRegressionTest {

    private class TestProviderManager(private val provider: Provider) : ProviderManager {
        private fun unused(): Nothing = throw UnsupportedOperationException("not used in this test")
        override suspend fun list() = listOf(provider)
        override suspend fun listWithoutKeys() = listOf(provider)
        override suspend fun get(id: String) = provider
        override suspend fun require(id: String) = provider
        override suspend fun create(name: String, type: ProviderType, baseUrl: String, reasoningParameter: xyz.mederi.provider.domain.model.ReasoningParameter?, responseSanitization: Boolean, modelsDevKey: String?) = unused()
        override suspend fun update(id: String, name: String?, baseUrl: String?, reasoningParameter: xyz.mederi.provider.domain.model.ReasoningParameter?, modelsDevKey: String?) = unused()
        override suspend fun updateRetrySettings(id: String, autoRetryEnabled: Boolean, retryMaxDelaySeconds: Int?) = unused()
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
        override suspend fun updateUserModel(providerId: String, modelId: String, name: String?, supportsReasoning: Boolean?, reasoningLevel: ReasoningLevel?, contextWindow: Int?, maxTokens: Int?, supportsImages: Boolean?, reasoningLevels: List<ReasoningLevel>?, isEnabled: Boolean?, optimizeForRequestCount: Boolean?) = unused()
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
        override val clientName = "ToolResultRegressionTest"
        override fun llmProvider() = delegate.llmProvider()
        override fun close() = delegate.close()
        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>) = error("expected streaming")
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

    @Test
    fun duplicateToolOutputsInTurnDoNotCorruptHistoryOnStoreOrNextTurn() = runBlocking {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "gradlew").exists() }
        val directory = File(root, "temp/tool-result-regression-${UUID.randomUUID()}").apply { mkdirs() }
        File(directory, "ok.txt").writeText("ok content")
        File(directory, "other.txt").writeText("other content")

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val history = InMemoryHistoryStore()
        val sessions = InMemorySessionStore()
        val events = CopyOnWriteArrayList<MederiEvent>()
        val bus = MutableSharedFlow<MederiEvent>(extraBufferCapacity = 128)
        val model = AIModel(id = "test-model", providerModelId = "test-model", name = "Test")
        val provider = Provider(
            "test-provider", "Test", ProviderType.OPENAI_CHAT, "http://unused.invalid/v1",
            models = listOf(model), apiKeys = listOf(ProviderApiKey("key", "Test", "***", true))
        )
        val client = ScriptClient(provider) { _, call ->
            when (call) {
                1 -> {
                    repeat(3) { i ->
                        emit(StreamFrame.ToolCallComplete("call_fail_a_$i", "read_file", "{}"))
                    }
                    emit(StreamFrame.End("tool_calls", ResponseMetaInfo.create(KoogClock.System)))
                }
                2 -> {
                    emit(StreamFrame.ToolCallComplete("call_ok_1", "read_file", buildJsonObject { put("path", "ok.txt") }.toString()))
                    emit(StreamFrame.End("tool_calls", ResponseMetaInfo.create(KoogClock.System)))
                }
                3 -> {
                    repeat(3) { i ->
                        emit(StreamFrame.ToolCallComplete("call_fail_b_$i", "read_file", "{}"))
                    }
                    emit(StreamFrame.End("tool_calls", ResponseMetaInfo.create(KoogClock.System)))
                }
                4 -> {
                    emit(StreamFrame.ToolCallComplete("call_ok_2", "read_file", buildJsonObject { put("path", "other.txt") }.toString()))
                    emit(StreamFrame.End("tool_calls", ResponseMetaInfo.create(KoogClock.System)))
                }
                5 -> {
                    emit(StreamFrame.TextDelta("Investigation complete"))
                    emit(StreamFrame.TextComplete("Investigation complete"))
                    emit(StreamFrame.End("stop", ResponseMetaInfo.create(KoogClock.System)))
                }
                6 -> {
                    emit(StreamFrame.TextDelta("Turn 2 ok"))
                    emit(StreamFrame.TextComplete("Turn 2 ok"))
                    emit(StreamFrame.End("stop", ResponseMetaInfo.create(KoogClock.System)))
                }
                else -> error("unexpected call $call")
            }
        }
        val collectJob = scope.launch(start = CoroutineStart.UNDISPATCHED) { bus.collect { events.add(it) } }
        val executor = TurnExecutor(
            sessions, history, bus, TestProviderManager(provider),
            StubProjectManager(Project("project", "Test", directory.absolutePath, "now", "now")),
            scope = scope, llmClientFactory = { _, _ -> client }
        )

        try {
            val now = java.time.Instant.now().toString()
            sessions.insert(
                Session(
                    id = "session", projectId = "project", title = "Test", status = SessionStatus.IDLE,
                    agentMode = AgentMode.AUTONOMOUS, aiModel = model, reasoningLevel = ReasoningLevel.NONE,
                    env = emptyMap(), createdAt = now, updatedAt = now
                )
            )

            // Turn 1
            executor.sendMessage(
                "session",
                SendMessageRequest(AgentConfig(AgentMode.AUTONOMOUS, model, ReasoningLevel.NONE), listOf(MessagePart.Text("Investigate")), messageId = "u1")
            )
            withTimeout(15_000) {
                while (events.none { it.type == EventType.MESSAGE_COMPLETED && it.payload["workCompletedAt"] != null }) delay(10)
            }

            val afterTurn1 = history.load("session")
            println("[LOG-VERIFY] afterTurn1 size=${afterTurn1.size}")
            afterTurn1.forEachIndexed { idx, msg ->
                val summary = msg.parts.joinToString { p ->
                    when (p) {
                        is MessagePart.Text -> "Text('${p.text.take(30)}')"
                        is MessagePart.ToolCall -> "ToolCall(id=${p.id},${p.tool})"
                        is MessagePart.ToolResult -> "ToolResult(id=${p.id},${p.tool},status=${p.status},dur=${p.durationMs})"
                        else -> p::class.simpleName.orEmpty()
                    }
                }
                println("[LOG-VERIFY]   seq=$idx id=${msg.id} role=${msg.role} finish=${msg.finishReason} parts=[$summary]")
            }

            // 期望：u1 + 4*(Assistant+ToolResult) + FinalAssistant = 10 条
            assertEquals(10, afterTurn1.size, "Turn 1 后不应多出重复 ToolResult（当前=${afterTurn1.size}）")
            assertEquals(MessageRole.ASSISTANT, afterTurn1.last().role, "最终一条必须是模型回复")
            assertEquals("stop", afterTurn1.last().finishReason)
            // 同时验证共用 ID 后，ToolResult 的 status/durationMs 仍然被正常回填
            val toolResultParts = afterTurn1.flatMap { it.parts }.filterIsInstance<MessagePart.ToolResult>()
            assertEquals(8, toolResultParts.size)
            toolResultParts.forEach { part ->
                assertNotNull(part.status, "ToolResult(${part.id}) 的 status 不应丢失")
                assertNotNull(part.durationMs, "ToolResult(${part.id}) 的 durationMs 不应丢失")
            }

            // Turn 2
            val prevCompletedCount = events.count { it.type == EventType.MESSAGE_COMPLETED && it.payload["workCompletedAt"] != null }
            executor.sendMessage(
                "session",
                SendMessageRequest(AgentConfig(AgentMode.AUTONOMOUS, model, ReasoningLevel.NONE), listOf(MessagePart.Text("Follow up")), messageId = "u2")
            )
            withTimeout(15_000) {
                while (events.count { it.type == EventType.MESSAGE_COMPLETED && it.payload["workCompletedAt"] != null } <= prevCompletedCount) {
                    delay(10)
                }
            }

            assertEquals(6, client.calls.get())
            val turn2Prompt = client.prompts.last()
            println("[LOG-VERIFY] turn2Prompt messages=${turn2Prompt.messages.size}")
            turn2Prompt.messages.forEachIndexed { idx, m ->
                println("[LOG-VERIFY]   prompt[$idx] id=${m.id} ${m::class.simpleName} parts=${m.parts.map { it::class.simpleName }}")
            }
            // 校验 OpenAI 协议约束：任何含 Tool.Result 的 User 消息，其前一条必须是带 Tool.Call 的 Assistant 消息
            for (idx in turn2Prompt.messages.indices) {
                val msg = turn2Prompt.messages[idx]
                if (msg is KoogMessage.User && msg.parts.any { it is KoogMessagePart.Tool.Result }) {
                    assertTrue(idx > 0, "Tool.Result 不能出现在第 0 条")
                    val prev = turn2Prompt.messages[idx - 1]
                    assertTrue(
                        prev is KoogMessage.Assistant && prev.parts.any { it is KoogMessagePart.Tool.Call },
                        "prompt[$idx] 含 Tool.Result，前一条 prompt[${idx - 1}] 必须是带 Tool.Call 的 Assistant，实际=$prev"
                    )
                }
            }
        } finally {
            collectJob.cancel()
            scope.cancel()
            scope.coroutineContext[Job]?.join()
            client.close()
            directory.deleteRecursively()
        }
    }
}
