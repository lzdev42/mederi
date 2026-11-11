package xyz.mederi.provider.infrastructure.koog.retry

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import xyz.mederi.domain.model.MederiEvent
import java.io.File
import kotlin.random.Random
import kotlin.test.*

class RetryableLLMClientTest {
    private val input = prompt("retry-test") { user("hello") }
    private val model = LLModel(provider = LLMProvider.OpenAI, id = "test")
    private val response = Message.Assistant(listOf(MessagePart.Text("ok")), ResponseMetaInfo.Empty)

    private open class Client(val request: suspend () -> Unit) : LLMClient() {
        override val clientName = "retry-test"
        override fun llmProvider() = LLMProvider.OpenAI
        override fun close() {}
        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
            request()
            return Message.Assistant(listOf(MessagePart.Text("ok")), ResponseMetaInfo.Empty)
        }
        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> = flow {
            request()
            emit(StreamFrame.TextComplete(text = "ok", index = 0))
        }
        override suspend fun executeMultipleChoices(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): LLMChoice {
            request()
            throw UnsupportedOperationException("end choices test")
        }
        override suspend fun moderate(prompt: Prompt, model: LLModel): Nothing = throw UnsupportedOperationException()
        override suspend fun models(): List<LLModel> = emptyList()
    }

    private fun bus() = MutableSharedFlow<MederiEvent>(replay = 1024)

    @Test
    fun localIdleTimeoutBeforeFirstFrameEscapesToTurnContinuation() = runBlocking {
        var calls = 0
        val error = wrappedTransportError(xyz.mederi.http.SseIdleTimeoutException("no SSE lines for 90s"))
        val events = bus()
        val client = RetryableLLMClient(Client { calls++; throw error },
            settingsProvider = { ProviderRetrySettings(enabled = true, minDelayMs = 0, maxDelayMs = 0) }, statusBus = events, sessionIdProvider = { "child" })
        withTimeout(5_000) {
            assertSame(error, assertFailsWith<ai.koog.prompt.executor.clients.LLMClientException> {
                client.executeStreaming(input, model, emptyList()).toList()
            })
        }
        assertEquals(1, calls)
        assertTrue(events.replayCache.none { it.payload["code"] == "RETRYING" })
    }

    private fun wrappedTransportError(cause: Throwable) = ai.koog.prompt.executor.clients.LLMClientException(
        "MederiOpenAILLMClient", "Error from client: MederiOpenAILLMClient",
        ai.koog.http.client.KoogHttpClientException(message = "Exception during streaming", cause = cause))

    @Test
    fun wrappedEofAndClosedChannelRetryBeforeOutputAcrossRequestPaths() = runBlocking {
        for (cause in listOf(java.io.EOFException("Unexpected end of stream"), java.nio.channels.ClosedChannelException())) {
            for (mode in listOf("stream", "execute", "choices")) {
                var calls = 0
                val events = bus()
                val error = wrappedTransportError(cause)
                val client = RetryableLLMClient(Client { if (++calls == 1) throw error },
                    settingsProvider = { ProviderRetrySettings(enabled = true, minDelayMs = 0, maxDelayMs = 0) }, statusBus = events, sessionIdProvider = { "sub-agent" })
                when (mode) {
                    "stream" -> assertEquals(1, client.executeStreaming(input, model, emptyList()).toList().size)
                    "execute" -> assertEquals(response, client.execute(input, model, emptyList()))
                    else -> assertFailsWith<UnsupportedOperationException> { client.executeMultipleChoices(input, model, emptyList()) }
                }
                assertEquals(2, calls, "$mode: ${cause::class.simpleName}")
                assertEquals("1", events.replayCache.single { it.payload["code"] == "RETRYING" }.payload["attempt"])
                assertTrue(events.replayCache.all { it.sessionId == "sub-agent" })
                assertEquals("RETRY_FINISHED", events.replayCache.last().payload["code"])
            }
        }
    }

    @Test
    fun transportFailureAfterOutputIsNotReplayedAndKeepsPartialOutput() = runBlocking {
        for (cause in listOf(java.io.EOFException("Unexpected end of stream"), java.nio.channels.ClosedChannelException())) {
            var calls = 0
            val error = wrappedTransportError(cause)
            val events = bus()
            val client = RetryableLLMClient(object : Client({}) {
                override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> = flow {
                    calls++
                    emit(StreamFrame.TextDelta("partial response"))
                    throw error
                }
            }, settingsProvider = { ProviderRetrySettings(enabled = true, minDelayMs = 0, maxDelayMs = 0) }, statusBus = events, sessionIdProvider = { "sub-agent" })
            val received = mutableListOf<StreamFrame>()
            assertSame(error, assertFailsWith<ai.koog.prompt.executor.clients.LLMClientException> {
                client.executeStreaming(input, model, emptyList()).collect { received += it }
            })
            assertEquals(1, calls)
            assertEquals<List<StreamFrame>>(listOf(StreamFrame.TextDelta("partial response")), received)
            assertTrue(events.replayCache.isEmpty())
        }
    }

    @Test
    fun explicitAuthenticationFailureStillWinsOverTransportCause() = runBlocking {
        var calls = 0
        val http = ai.koog.http.client.KoogHttpClientException(statusCode = 401, cause = java.io.EOFException())
        val error = wrappedTransportError(http)
        val client = RetryableLLMClient(Client { calls++; throw error }, settingsProvider = { ProviderRetrySettings(enabled = true, minDelayMs = 0, maxDelayMs = 0) })
        assertSame(error, assertFailsWith<ai.koog.prompt.executor.clients.LLMClientException> {
            client.executeStreaming(input, model, emptyList()).toList()
        })
        assertEquals(1, calls)
    }

    @Test
    fun streamingRetriesPastOldLimitAndRestartsAtOneAfterRecovery() = runBlocking {
        val events = bus()
        var attempts = 0
        val bounds = mutableListOf<Pair<Long, Long>>()
        val random = object : Random() {
            override fun nextBits(bitCount: Int) = 0
            override fun nextLong(from: Long, until: Long): Long {
                bounds += from to until
                return from
            }
        }
        val client = RetryableLLMClient(Client {
            attempts++
            if (attempts % 122 != 0) error("429 rate limit")
        }, statusBus = events, sessionIdProvider = { "s" }, random = random)
        repeat(2) {
            assertEquals(1, client.executeStreaming(input, model, emptyList()).toList().size)
        }
        val retries = events.replayCache.filter { it.payload["code"] == "RETRYING" }
        assertEquals(242, retries.size)
        assertEquals("121", retries[120].payload["attempt"])
        assertEquals("1", retries[121].payload["attempt"])
        assertTrue(retries.all { "maxAttempts" !in it.payload && it.payload["delayMs"] == "0" })
        assertEquals(List(242) { 0L to 30_001L }, bounds)
        assertEquals(2, events.replayCache.count { it.payload["code"] == "RETRY_FINISHED" })
    }

    @Test
    fun nonStreamingAlsoRetriesWithoutLimit() = runBlocking {
        var calls = 0
        val events = bus()
        val client = RetryableLLMClient(Client { if (++calls <= 120) error("503 overloaded") },
            settingsProvider = { ProviderRetrySettings(enabled = true, minDelayMs = 0, maxDelayMs = 0) }, statusBus = events, sessionIdProvider = { "s" })
        assertEquals(response, client.execute(input, model, emptyList()))
        assertEquals(121, calls)
        assertEquals("RETRY_FINISHED", events.replayCache.last().payload["code"])
    }

    @Test
    fun choicesAlsoRetriesPastOldLimitAndPropagatesPermanentFailure() = runBlocking {
        var calls = 0
        val client = RetryableLLMClient(Client { if (++calls <= 120) error("502 bad gateway") },
            settingsProvider = { ProviderRetrySettings(enabled = true, minDelayMs = 0, maxDelayMs = 0) })
        assertFailsWith<UnsupportedOperationException> { client.executeMultipleChoices(input, model, emptyList()) }
        assertEquals(121, calls)
    }

    @Test
    fun stopDuringDelayCancelsImmediatelyAndClearsHint() = runBlocking {
        var calls = 0
        val events = bus()
        val client = RetryableLLMClient(Client { calls++; error("429 rate limit") },
            settingsProvider = { ProviderRetrySettings(enabled = true, minDelayMs = 30_000, maxDelayMs = 30_000) }, statusBus = events, sessionIdProvider = { "s" })
        val job = launch(start = CoroutineStart.UNDISPATCHED) { client.executeStreaming(input, model, emptyList()).toList() }
        assertEquals("RETRYING", events.replayCache.last().payload["code"])
        withTimeout(1000) { job.cancelAndJoin() }
        assertEquals(1, calls)
        assertEquals("RETRY_FINISHED", events.replayCache.last().payload["code"])
    }

    @Test
    fun cancellationWithZeroDelayAndPermanentErrorsNeverRetry() = runBlocking {
        val job = Job()
        var calls = 0
        val client = RetryableLLMClient(Client { calls++; job.cancel(); error("429 rate limit") }, settingsProvider = { ProviderRetrySettings(enabled = true, minDelayMs = 0, maxDelayMs = 0) })
        assertFailsWith<CancellationException> {
            withContext(job) { client.execute(input, model, emptyList()) }
        }
        assertEquals(1, calls)
        calls = 0
        val permanent = RetryableLLMClient(Client { calls++; error("401 invalid API key") }, settingsProvider = { ProviderRetrySettings(enabled = true, minDelayMs = 0, maxDelayMs = 0) })
        assertFailsWith<IllegalStateException> { permanent.execute(input, model, emptyList()) }
        assertEquals(1, calls)
    }
    @Test
    fun serverDelayIsUsedInEveryRequestPathAndRemainsCancellable() = runBlocking {
        for (mode in listOf("stream", "execute", "choices")) {
            var calls = 0
            val events = bus()
            val error = xyz.mederi.http.ProviderHttpException(429, mapOf("retry-after" to "120"), null,
                ai.koog.http.client.KoogHttpClientException(statusCode = 429))
            val client = RetryableLLMClient(Client { calls++; throw error },
                settingsProvider = { ProviderRetrySettings(enabled = true, minDelayMs = 0, maxDelayMs = 0) },
                statusBus = events, sessionIdProvider = { "s" })
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                when (mode) {
                    "stream" -> client.executeStreaming(input, model, emptyList()).toList()
                    "execute" -> client.execute(input, model, emptyList())
                    else -> client.executeMultipleChoices(input, model, emptyList())
                }
            }
            val wait = events.replayCache.last().payload["delayMs"]!!.toLong()
            assertTrue(wait in 120_000L..121_000L, "server minimum must override random fallback")
            withTimeout(1000) { job.cancelAndJoin() }
            assertEquals(1, calls)
            assertEquals("RETRY_FINISHED", events.replayCache.last().payload["code"])
        }
    }

    @Test
    fun permanent429AndServerOptOutNeverEnterRetryLoop() = runBlocking {
        for (headers in listOf(mapOf("x-should-retry" to "false"), emptyMap())) {
            var calls = 0
            val body = if (headers.isEmpty()) """{"error":{"code":"credit_balance_exhausted"}}""" else null
            val error = xyz.mederi.http.ProviderHttpException(429, headers, body,
                ai.koog.http.client.KoogHttpClientException(statusCode = 429, errorBody = body))
            val events = bus()
            val client = RetryableLLMClient(Client { calls++; throw error }, settingsProvider = { ProviderRetrySettings(enabled = true, minDelayMs = 0, maxDelayMs = 0) },
                statusBus = events, sessionIdProvider = { "s" })
            assertFailsWith<xyz.mederi.http.ProviderHttpException> { client.execute(input, model, emptyList()) }
            assertEquals(1, calls)
            assertTrue(events.replayCache.isEmpty())
        }
    }

    @Test
    fun senseNovaTpmRpmFailureActuallyRetriesAcrossAllRequestPaths() = runBlocking {
        val body = """{"error":{"message":"inference exceeds tpm/rpm limit","type":"rate_limit_error","code":"insufficient_quota"}}"""
        for (mode in listOf("stream", "execute", "choices")) {
            var calls = 0
            val events = bus()
            val fallbackBounds = mutableListOf<Pair<Long, Long>>()
            val random = object : Random() {
                override fun nextBits(bitCount: Int) = 0
                override fun nextLong(from: Long, until: Long): Long {
                    fallbackBounds += from to until
                    return from
                }
            }
            val http = ai.koog.http.client.KoogHttpClientException(statusCode = 429, errorBody = body)
            val metadata = xyz.mederi.http.ProviderHttpException(429, emptyMap(), body, http)
            val wrapper = ai.koog.http.client.KoogHttpClientException(message = "Exception during streaming", cause = metadata)
            val error = ai.koog.prompt.executor.clients.LLMClientException("MederiOpenAILLMClient", "streaming failed", wrapper)
            val client = RetryableLLMClient(Client { if (++calls == 1) throw error },
                statusBus = events, sessionIdProvider = { "s" }, random = random)
            when (mode) {
                "stream" -> assertEquals(1, client.executeStreaming(input, model, emptyList()).toList().size)
                "execute" -> assertEquals(response, client.execute(input, model, emptyList()))
                else -> assertFailsWith<UnsupportedOperationException> { client.executeMultipleChoices(input, model, emptyList()) }
            }
            assertEquals(2, calls, mode)
            assertEquals(listOf(0L to 30_001L), fallbackBounds)
            val retry = events.replayCache.single { it.payload["code"] == "RETRYING" }
            assertEquals("1", retry.payload["attempt"])
            assertEquals("inference exceeds tpm/rpm limit", retry.payload["message"])
            assertEquals("RETRY_FINISHED", events.replayCache.last().payload["code"])
        }
    }

    @Test
    fun `retry disabled rethrows transient failure immediately on execute and stream`() = runBlocking {
        val events = bus()
        val http = ai.koog.http.client.KoogHttpClientException(
            statusCode = 429, errorBody = """{"error":{"message":"rate limit exceeded"}}""")
        val error = wrappedTransportError(http)

        suspend fun rethrownSame(block: suspend () -> Unit): Boolean = try {
            withTimeout(5_000) { block() }
            false
        } catch (e: Throwable) {
            e === error
        }

        var streamCalls = 0
        val streamClient = RetryableLLMClient(Client { streamCalls++; throw error },
            settingsProvider = { ProviderRetrySettings(enabled = false, minDelayMs = 0, maxDelayMs = 0) },
            statusBus = events, sessionIdProvider = { "s" })
        val streamRethrown = rethrownSame { streamClient.executeStreaming(input, model, emptyList()).toList() }
        assertEquals(1, streamCalls)
        assertTrue(streamRethrown, "stream path must rethrow the exact transient exception instance")

        var executeCalls = 0
        val executeClient = RetryableLLMClient(Client { executeCalls++; throw error },
            settingsProvider = { ProviderRetrySettings(enabled = false, minDelayMs = 0, maxDelayMs = 0) },
            statusBus = events, sessionIdProvider = { "s" })
        val executeRethrown = rethrownSame { executeClient.execute(input, model, emptyList()) }
        assertEquals(1, executeCalls)
        assertTrue(executeRethrown, "execute path must rethrow the exact transient exception instance")

        val retryingEvents = events.replayCache.count { it.payload["code"] == "RETRYING" }
        assertEquals(0, retryingEvents)

        // 验收观测值（断言在前：断言失败则不写文件=not-run=FAIL）
        val observationDir = File("build/retry-behavior")
        observationDir.mkdirs()
        File(observationDir, "disabled.txt").writeText(
            "executeRethrown=$executeRethrown,streamRethrown=$streamRethrown,retryingEvents=$retryingEvents"
        )
    }

    @Test
    fun `retry settings are re-read per attempt for real-time effect`() = runBlocking {
        // 真理源实时性：第一次读 enabled（0ms 立即重试）→ 重试再失败 → 第二次读 disabled → 立即上抛。
        // 若设置在构造时固化（旧行为），第二次读到的仍是 enabled，循环不会停。
        val body = """{"error":{"message":"rate limit exceeded"}}"""
        val http = ai.koog.http.client.KoogHttpClientException(statusCode = 429, errorBody = body)
        val metadata = xyz.mederi.http.ProviderHttpException(429, emptyMap(), body, http)
        val wrapper = ai.koog.http.client.KoogHttpClientException(message = "Exception during streaming", cause = metadata)
        val error = ai.koog.prompt.executor.clients.LLMClientException("MederiOpenAILLMClient", "streaming failed", wrapper)
        var settingsReads = 0
        var calls = 0
        val client = RetryableLLMClient(
            Client { calls++; throw error },
            settingsProvider = {
                settingsReads++
                if (settingsReads <= 1) ProviderRetrySettings(enabled = true, minDelayMs = 0, maxDelayMs = 0)
                else ProviderRetrySettings(enabled = false, minDelayMs = 0, maxDelayMs = 0)
            },
            statusBus = bus(), sessionIdProvider = { "s" })
        withTimeout(5_000) {
            assertSame(error, assertFailsWith<ai.koog.prompt.executor.clients.LLMClientException> {
                client.execute(input, model, emptyList())
            })
        }
        assertEquals(2, calls, "first attempt + one retry, then disabled stops the loop")
        assertEquals(2, settingsReads, "settings must be re-read on every retry decision")
    }

}
