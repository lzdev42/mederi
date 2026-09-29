package xyz.mederi.debug

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.http.client.KoogHttpClientException
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.LLMClientException
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.apache.hc.core5.http2.H2StreamResetException
import xyz.mederi.provider.infrastructure.koog.retry.RetryableLLMClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Http2StreamResetTest {

    private fun createH2StreamResetExceptionChain(): LLMClientException {
        val rootCause = H2StreamResetException(1, "Stream reset (1)")
        val koogException = KoogHttpClientException(
            clientName = "OpenAILLMClient",
            message = "Exception during streaming: Stream reset (1)",
            cause = rootCause
        )
        return LLMClientException(
            clientName = "MederiOpenAILLMClient",
            message = "Error from client: MederiOpenAILLMClient\nError from client: OpenAILLMClient\nMessage: Exception during streaming: Stream reset (1)",
            cause = koogException
        )
    }

    @Test
    fun h2StreamResetException_isAccuratelyClassifiedByErrorCollector() {
        val llmClientException = createH2StreamResetExceptionChain()
        val context = ErrorContext(
            phase = "turn_execution",
            sessionId = "sess_f7b06b77",
            providerId = "prov_7fc60c9f",
            providerName = "OpenRouter",
            modelId = "mdl_17a6448c",
            modelName = "stealth/space-bunny-alpha"
        )

        val record = ErrorCollector.collect(llmClientException, context)

        assertEquals(ErrorCategory.NETWORK, record.category)
        assertEquals(ErrorSeverity.RECOVERABLE, record.severity)
        assertEquals(ErrorCollector.FailureMode.NETWORK_ERROR, record.failureMode)
        assertEquals("HTTP/2流被重置", record.networkErrorType)
        assertTrue(record.recoverySuggestion?.contains("HTTP/2流被重置") == true)
        assertTrue(record.causeChain.any { it.contains("H2StreamResetException") })
    }

    @Test
    fun h2StreamResetException_isRecognizedAsTransientError() {
        val llmClientException = createH2StreamResetExceptionChain()
        assertTrue(RetryableLLMClient.isTransientError(llmClientException))
    }

    @Test
    fun retryableLLMClient_retriesH2StreamResetBeforeFirstFrame() = runBlocking {
        var attempts = 0
        val fakeClient = object : LLMClient() {
            override val clientName: String = "FakeClient"
            override fun llmProvider(): LLMProvider = LLMProvider.OpenAI
            override fun close() {}
            override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant =
                throw UnsupportedOperationException()
            override suspend fun executeMultipleChoices(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): LLMChoice =
                throw UnsupportedOperationException()
            override suspend fun moderate(prompt: Prompt, model: LLModel): Nothing =
                throw UnsupportedOperationException()
            override suspend fun models(): List<LLModel> = emptyList()
            override fun executeStreaming(
                prompt: Prompt,
                model: LLModel,
                tools: List<ToolDescriptor>
            ): Flow<StreamFrame> = flow {
                attempts++
                if (attempts < 3) {
                    throw createH2StreamResetExceptionChain()
                }
                emit(StreamFrame.TextComplete(text = "recovered response", index = 0))
            }
        }

        val retryClient = RetryableLLMClient(
            delegate = fakeClient,
            maxRetries = 3,
            minDelayMs = 10,
            maxDelayMs = 20
        )

        val testPrompt = prompt("test") { user("hello") }
        val testModel = LLModel(provider = LLMProvider.OpenAI, id = "test-model")

        val frames = retryClient.executeStreaming(
            prompt = testPrompt,
            model = testModel,
            tools = emptyList()
        ).toList()

        assertEquals(3, attempts)
        assertEquals(1, frames.size)
        assertEquals("recovered response", (frames[0] as StreamFrame.TextComplete).text)
    }

    @Test
    fun retryableLLMClient_throwsImmediatelyIfH2StreamResetOccursAfterFirstFrame() = runBlocking {
        var attempts = 0
        val fakeClient = object : LLMClient() {
            override val clientName: String = "FakeClient"
            override fun llmProvider(): LLMProvider = LLMProvider.OpenAI
            override fun close() {}
            override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant =
                throw UnsupportedOperationException()
            override suspend fun executeMultipleChoices(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): LLMChoice =
                throw UnsupportedOperationException()
            override suspend fun moderate(prompt: Prompt, model: LLModel): Nothing =
                throw UnsupportedOperationException()
            override suspend fun models(): List<LLModel> = emptyList()
            override fun executeStreaming(
                prompt: Prompt,
                model: LLModel,
                tools: List<ToolDescriptor>
            ): Flow<StreamFrame> = flow {
                attempts++
                emit(StreamFrame.TextComplete(text = "first token", index = 0))
                throw createH2StreamResetExceptionChain()
            }
        }

        val retryClient = RetryableLLMClient(
            delegate = fakeClient,
            maxRetries = 3,
            minDelayMs = 10,
            maxDelayMs = 20
        )

        val testPrompt = prompt("test") { user("hello") }
        val testModel = LLModel(provider = LLMProvider.OpenAI, id = "test-model")

        assertFailsWith<LLMClientException> {
            retryClient.executeStreaming(
                prompt = testPrompt,
                model = testModel,
                tools = emptyList()
            ).toList()
        }

        // 首帧已发，严禁重试避免内容重复，尝试次数必须为 1
        assertEquals(1, attempts)
    }
}
