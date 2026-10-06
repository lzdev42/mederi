package xyz.mederi.provider.infrastructure.koog.sanitize

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIUsage
import ai.koog.prompt.executor.clients.openai.base.models.PromptTokensDetails
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 验证流式路径 [MederiOpenAILLMClient.processStreamingFlow] 把 usage 的 cached_tokens
 * 写进 End 帧的 metaInfo.metadata（修复前流式走父类 createMetaInfo 不写 cached，导致
 * Message.cachedTokens 常态 null）。
 *
 * 直接驱动 processStreamingFlow（internal），不经 HTTP——但父类构造期 createConfiguredHttpClient
 * 会调 factory.create，故需一个返回 fake client 的 factory。
 */
class MederiOpenAILLMClientTest {

    /** 不会被实际调用（processStreamingFlow 不走 HTTP）；构造期需要一个非 null 的 KoogHttpClient */
    private val fakeHttpClient = object : KoogHttpClient {
        override val clientName: String = "fake"
        override suspend fun <R : Any> get(
            path: String, responseType: KClass<R>,
            parameters: Map<String, String>, headers: Map<String, String>
        ): R = error("not used")

        override suspend fun <T : Any, R : Any> post(
            path: String, requestBody: T, requestBodyType: KClass<T>, responseType: KClass<R>,
            parameters: Map<String, String>, headers: Map<String, String>
        ): R = error("not used")

        override fun <T : Any, R : Any, O : Any> sse(
            path: String, requestBody: T, requestBodyType: KClass<T>,
            dataFilter: (String?) -> Boolean,
            decodeStreamingResponse: (String) -> R,
            processStreamingChunk: (R) -> O?,
            parameters: Map<String, String>, headers: Map<String, String>
        ): Flow<O> = error("not used")

        override fun <T : Any> lines(
            path: String, requestBody: T, requestBodyType: KClass<T>,
            parameters: Map<String, String>, headers: Map<String, String>
        ): Flow<String> = error("not used")

        override fun close() { }
    }

    private val fakeFactory = object : KoogHttpClient.Factory {
        override fun create(
            clientName: String,
            baseUrl: String,
            headers: Map<String, String>,
            queryParameters: Map<String, String>,
            requestTimeoutMillis: Long,
            connectTimeoutMillis: Long,
            socketTimeoutMillis: Long,
            json: Json
        ): KoogHttpClient = fakeHttpClient
    }

    private fun makeClient(): MederiOpenAILLMClient =
        MederiOpenAILLMClient(
            apiKey = "test-key",
            settings = OpenAIClientSettings(),
            httpClientFactory = fakeFactory,
            chatCompletionsPath = "https://example.com/v1/chat/completions"
        )

    @Test
    fun `streaming end frame carries cached tokens from usage`() = runBlocking {
        val client = makeClient()
        // OpenAI 流式惯例：最后一帧带 usage、choices 可为空
        val response = flowOf(
            SanitizedStreamResponse(
                choices = listOf(
                    SanitizedStreamChoice(
                        delta = SanitizedStreamDelta(content = "hello"),
                        finishReason = null,
                        index = 0
                    )
                ),
                created = 0L,
                id = "1",
                model = "test",
                objectType = "chat.completion.chunk",
                usage = null
            ),
            SanitizedStreamResponse(
                choices = emptyList(),
                created = 0L,
                id = "1",
                model = "test",
                objectType = "chat.completion.chunk",
                usage = OpenAIUsage(
                    promptTokens = 100,
                    completionTokens = 50,
                    totalTokens = 150,
                    promptTokensDetails = PromptTokensDetails(cachedTokens = 80)
                )
            )
        )

        val frames = client.processStreamingFlow(response).toList()
        val end = frames.filterIsInstance<StreamFrame.End>().single()

        assertEquals(100, end.metaInfo.inputTokensCount)
        assertEquals(50, end.metaInfo.outputTokensCount)
        // 修复前（走父类 createMetaInfo）metadata 为 null → 此断言失败
        val cached = end.metaInfo.metadata?.get("cachedTokens")
        assertNotNull(cached, "cachedTokens metadata 必须存在（修复后走 createMetaInfoInternal）")
        assertEquals("80", cached.jsonPrimitive.content)
    }

    @Test
    fun `streaming without usage produces end frame with null token counts`() = runBlocking {
        val client = makeClient()
        val response = flowOf(
            SanitizedStreamResponse(
                choices = listOf(
                    SanitizedStreamChoice(
                        delta = SanitizedStreamDelta(content = "hi"),
                        finishReason = "stop",
                        index = 0
                    )
                ),
                created = 0L,
                id = "1",
                model = "test",
                objectType = "chat.completion.chunk",
                usage = null
            )
        )

        val frames = client.processStreamingFlow(response).toList()
        val end = frames.filterIsInstance<StreamFrame.End>().single()

        assertNull(end.metaInfo.inputTokensCount)
        assertNull(end.metaInfo.outputTokensCount)
        assertNull(end.metaInfo.metadata)
    }
}
