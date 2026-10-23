package xyz.mederi.koog

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import xyz.mederi.api.SendMessageRequest
import xyz.mederi.domain.model.AIModel
import xyz.mederi.domain.model.AgentMode
import xyz.mederi.domain.model.Message
import xyz.mederi.domain.model.MessagePart
import xyz.mederi.domain.model.MessageRole
import xyz.mederi.domain.model.Project
import xyz.mederi.domain.model.Session
import xyz.mederi.domain.model.SessionStatus
import xyz.mederi.infrastructure.koog.TurnExecutor
import xyz.mederi.project.ProjectManager
import xyz.mederi.provider.ProviderManager
import xyz.mederi.provider.domain.model.ReasoningLevel
import xyz.mederi.store.InMemoryHistoryStore
import xyz.mederi.store.InMemorySessionStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TurnExecutor auto-continue 断流自愈的单元级验证。
 *
 * 背景：断流自愈（PREMATURE_CLOSE 时自动追加 "Continue" 续写半截回复）下沉到
 * TurnExecutor（主代理与子代理共享一份逻辑 + AUTO_CONTINUE_MAX_RETRIES 常量）。
 * runTurn 的 catch 块对 PREMATURE_CLOSE 重抛 PrematureCloseSignal；sendMessageInternal
 * 的 scope.launch 内 while 循环 catch 该信号，attempt < 最大重试 && 已吐 token 时
 * 追加 "Continue" + 重跑 runTurn，否则 emit MESSAGE_ERROR(PREMATURE_CLOSE)。
 *
 * 本测试验证两个可独立验证的构件：
 * 1. hasEmittedTokens(sessionId) —— 断流前是否已输出内容（最后一条 assistant 消息有非空 Text）。
 * 2. PrematureCloseSignal —— runTurn 重抛信号携带 errorPayload + cause。
 *
 * 完整的 auto-continue 集成（首次 execute 半截回复后抛 PREMATURE_CLOSE、第二次正常完成）
 * 需要 mock SSE 流中断：LLM client 由 runTurn 内部硬编码 KoogClientFactory.create(provider, key)
 * 创建，无注入点；现有测试范式（Stub ProviderManager 抛 UnsupportedOperationException）无法覆盖，
 * 见 @Ignore 的 autoContinueIntegration 方法说明。核心重试循环逻辑靠代码审查 + 人工验证保证。
 */
class TurnExecutorAutoContinueTest {

    private val tmpDir = File.createTempFile("mederi-auto-continue-test", "").apply {
        delete(); mkdirs()
    }

    private object UnusedProviderManager : ProviderManager {
        private fun unused(): Nothing = throw UnsupportedOperationException("not used in this test")
        override suspend fun list() = unused()
        override suspend fun listWithoutKeys() = unused()
        override suspend fun get(id: String) = unused()
        override suspend fun require(id: String) = unused()
        override suspend fun create(name: String, type: xyz.mederi.provider.domain.model.ProviderType, baseUrl: String, reasoningParameter: xyz.mederi.provider.domain.model.ReasoningParameter?, responseSanitization: Boolean, modelsDevKey: String?) = unused()
        override suspend fun update(id: String, name: String?, baseUrl: String?, reasoningParameter: xyz.mederi.provider.domain.model.ReasoningParameter?, modelsDevKey: String?) = unused()
        override suspend fun delete(id: String) = unused()
        override suspend fun listKeys(providerId: String) = unused()
        override suspend fun addKey(providerId: String, name: String, value: String, isDefault: Boolean) = unused()
        override suspend fun deleteKey(providerId: String, keyId: String) = unused()
        override suspend fun setDefaultKey(providerId: String, keyId: String) = unused()
        override suspend fun getDefaultKeyValue(providerId: String) = unused()
        override suspend fun getKeyValue(providerId: String, keyId: String) = unused()
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

    private fun model(id: String) = AIModel(id = id, providerModelId = id, name = "Model $id")

    @AfterTest
    fun cleanup() {
        tmpDir.deleteRecursively()
    }

    private fun newExecutor(historyStore: InMemoryHistoryStore): TurnExecutor {
        val store = InMemorySessionStore()
        val project = Project("proj_1", "p", tmpDir.absolutePath, "now", "now")
        return TurnExecutor(
            sessionStore = store,
            historyStore = historyStore,
            eventBus = MutableSharedFlow(),
            providerManager = UnusedProviderManager,
            projectManager = StubProjectManager(project)
        )
    }

    private fun assistant(text: String, sessionId: String) =
        Message(sessionId = sessionId, role = MessageRole.ASSISTANT, parts = listOf(MessagePart.Text(text)), createdAt = "now")

    private fun user(text: String, sessionId: String) =
        Message(sessionId = sessionId, role = MessageRole.USER, parts = listOf(MessagePart.Text(text)), createdAt = "now")

    // ── hasEmittedTokens（断流前是否已吐 token）──

    @Test
    fun hasEmittedTokens_emptyHistory_isFalse() = runBlocking {
        val history = InMemoryHistoryStore()
        val executor = newExecutor(history)
        assertFalse(invokeHasEmittedTokens(executor, "sess_empty"), "空历史 = 未吐 token")
    }

    @Test
    fun hasEmittedTokens_onlyUserMessages_isFalse() = runBlocking {
        val history = InMemoryHistoryStore()
        history.append("sess_u", user("hello", "sess_u"))
        history.append("sess_u", user("please continue", "sess_u"))
        val executor = newExecutor(history)
        assertFalse(invokeHasEmittedTokens(executor, "sess_u"), "只有用户消息 = 未吐 token")
    }

    @Test
    fun hasEmittedTokens_assistantBlankText_isFalse() = runBlocking {
        val history = InMemoryHistoryStore()
        history.append("sess_blank", user("hello", "sess_blank"))
        history.append("sess_blank", assistant("   \n", "sess_blank"))
        val executor = newExecutor(history)
        assertFalse(invokeHasEmittedTokens(executor, "sess_blank"), "assistant 文本全空白 = 未吐 token")
    }

    @Test
    fun hasEmittedTokens_assistantNonBlankText_isTrue() = runBlocking {
        val history = InMemoryHistoryStore()
        history.append("sess_ok", user("hello", "sess_ok"))
        history.append("sess_ok", assistant("部分回复内容", "sess_ok"))
        val executor = newExecutor(history)
        assertTrue(invokeHasEmittedTokens(executor, "sess_ok"), "最后一条 assistant 有非空 Text = 已吐 token")
    }

    @Test
    fun hasEmittedTokens_trailingUserAfterAssistant_isTrue() = runBlocking {
        val history = InMemoryHistoryStore()
        history.append("sess_trail", user("hello", "sess_trail"))
        history.append("sess_trail", assistant("半截回复", "sess_trail"))
        history.append("sess_trail", user("Continue", "sess_trail"))
        val executor = newExecutor(history)
        assertTrue(invokeHasEmittedTokens(executor, "sess_trail"), "最后一条是 user 但前面 assistant 已吐 token")
    }

    // ── PrematureCloseSignal（runTurn 重抛信号）──

    @Test
    fun prematureCloseSignal_carriesPayloadAndCause() {
        val cls = Class.forName("xyz.mederi.infrastructure.koog.TurnExecutor\$PrematureCloseSignal")
        val ctor = cls.getDeclaredConstructor(Map::class.java, Throwable::class.java)
        ctor.isAccessible = true
        val cause = RuntimeException("stream closed before end marker")
        val payload = mapOf(
            "failureMode" to "PREMATURE_CLOSE",
            "error" to "服务器关闭连接但未发送结束标记",
            "errorId" to "err_1"
        )
        val signal = ctor.newInstance(payload, cause) as RuntimeException
        assertNotNull(signal, "PrematureCloseSignal 可构造（runTurn catch 重抛用）")
        assertEquals(cause.message, signal.cause?.message, "信号 cause 保留原始异常的 message")
        assertTrue(signal.cause === cause, "信号 cause 保留原始异常")

        @Suppress("UNCHECKED_CAST")
        val carried = cls.getDeclaredMethod("getErrorPayload").apply { isAccessible = true }.invoke(signal) as Map<String, String>
        assertEquals(payload, carried, "errorPayload 原样携带（auto-continue 耗尽时 emit MESSAGE_ERROR 用）")
    }

    // ── 集成测试说明（@Ignore）──

    @Test
    @Ignore("需要 mock SSE 流中断：LLM client 由 runTurn 内部 KoogClientFactory.create 硬编码创建，无注入点；见类注释")
    fun autoContinueIntegration_prematureCloseThenContinue() {
        // 预期场景（人工/集成验证）：
        // 1. fake LLMClient 首次 execute 返回半截回复后抛 PREMATURE_CLOSE 异常 → runTurn catch 重抛 PrematureCloseSignal
        // 2. auto-continue 循环：attempt < AUTO_CONTINUE_MAX_RETRIES && hasEmittedTokens → historyStore 追加 "Continue" + 重跑 runTurn
        // 3. fake LLMClient 第二次 execute 正常完成 → MESSAGE_COMPLETED 事件发出，未发 MESSAGE_ERROR
    }
}

// hasEmittedTokens 是 TurnExecutor 的 private suspend fun；JVM 字节码签名 =
// hasEmittedTokens(String, Continuation)。InMemoryHistoryStore.load 同步返回（不真正挂起），
// 故 method.invoke 直接返回 Boolean 结果（而非 COROUTINE_SUSPENDED）。
private fun invokeHasEmittedTokens(executor: TurnExecutor, sessionId: String): Boolean {
    val method = TurnExecutor::class.java.getDeclaredMethod(
        "hasEmittedTokens", String::class.java, Continuation::class.java
    )
    method.isAccessible = true
    return method.invoke(executor, sessionId, NoOpContinuation) as Boolean
}

private object NoOpContinuation : Continuation<Any?> {
    override val context: CoroutineContext = EmptyCoroutineContext
    override fun resumeWith(result: Result<Any?>) = Unit
}
