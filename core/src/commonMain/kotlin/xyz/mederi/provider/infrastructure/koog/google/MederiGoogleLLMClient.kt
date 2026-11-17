package xyz.mederi.provider.infrastructure.koog.google

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.KoogHttpClientException
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.LLMClientException
import ai.koog.prompt.executor.clients.google.GoogleClientSettings
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.IncompleteStreamException
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import xyz.mederi.http.MederiHttpClientFactory
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass
import kotlin.reflect.full.cast
import kotlin.reflect.full.starProjectedType

/** 补齐当前 Koog Google 客户端没有映射的原生请求和响应字段。 */
internal class MederiGoogleLLMClient(apiKey: String, settings: GoogleClientSettings) :
    GoogleLLMClient(apiKey, settings, GoogleHttpClientFactory) {

    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
        val capture = GoogleResponseCapture()
        val response = withContext(capture) { super.execute(prompt, model, tools) }
        return response.copy(metaInfo = capture.enrichUsage(response.metaInfo))
    }

    override suspend fun executeMultipleChoices(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): LLMChoice {
        val capture = GoogleResponseCapture()
        return withContext(capture) { super.executeMultipleChoices(prompt, model, tools) }.map { response ->
            response.copy(metaInfo = capture.enrichUsage(response.metaInfo))
        }
    }

    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> = flow {
        val capture = GoogleResponseCapture()
        var end: StreamFrame.End? = null
        super.executeStreaming(prompt, model, tools)
            .flowOn(capture)
            .catch { failure ->
                if (failure is IncompleteStreamException) {
                    throw LLMClientException(clientName, capture.incompleteMessage(), failure)
                }
                throw failure
            }
            .collect { frame ->
                if (frame is StreamFrame.ToolCallComplete) {
                    capture.takeSignature(frame)?.let { signature ->
                        // 与 Koog 的非流式处理一致，紧贴其所属的 Tool.Call。
                        emit(StreamFrame.ReasoningComplete(id = null, content = emptyList(), encrypted = signature))
                    }
                }
                if (frame is StreamFrame.End) {
                    end = frame
                } else {
                    emit(frame)
                }
            }
        // Google 可以在 finishReason 之后单独发送最后一帧 usageMetadata。
        end?.let { emit(it.copy(metaInfo = capture.enrichUsage(it.metaInfo))) }
    }
}

private object GoogleHttpClientFactory : KoogHttpClient.Factory {
    override fun create(
        clientName: String,
        baseUrl: String,
        headers: Map<String, String>,
        queryParameters: Map<String, String>,
        requestTimeoutMillis: Long,
        connectTimeoutMillis: Long,
        socketTimeoutMillis: Long,
        json: Json,
    ): KoogHttpClient {
        val delegate = MederiHttpClientFactory.create(
            clientName, baseUrl, headers, queryParameters, requestTimeoutMillis, connectTimeoutMillis, socketTimeoutMillis, json,
        )
        return GoogleHttpClient(delegate, json)
    }
}

private class GoogleHttpClient(private val delegate: KoogHttpClient, private val json: Json) :
    KoogHttpClient by delegate {

    override suspend fun <T : Any, R : Any> post(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        responseType: KClass<R>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): R {
        if (!path.endsWith(":generateContent")) {
            return delegate.post(path, requestBody, requestBodyType, responseType, parameters, headers)
        }
        val raw = delegate.post(path, encodeRequest(requestBody, requestBodyType), String::class, String::class,
            parameters, headers)
        val metadata = inspect(raw)
        currentCoroutineContext()[GoogleResponseCapture]?.record(metadata)
        return responseType.cast(json.decodeFromString(json.serializersModule.serializer(responseType.starProjectedType), raw))
    }

    override fun <T : Any, R : Any, O : Any> sse(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        dataFilter: (String?) -> Boolean,
        decodeStreamingResponse: (String) -> R,
        processStreamingChunk: (R) -> O?,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): Flow<O> = flow {
        val capture = currentCoroutineContext()[GoogleResponseCapture]
        delegate.sse(
            path, encodeRequest(requestBody, requestBodyType), String::class, dataFilter,
            decodeStreamingResponse = { it },
            processStreamingChunk = { it },
            parameters = parameters,
            headers = headers,
        ).buffer(0).collect { raw ->
            // 在 Ktor SSE 处理边界之外抛出供应商错误，避免错误体随传输包装丢失。
            val metadata = inspect(raw)
            capture?.record(metadata)
            // Koog 的响应 DTO 要求 candidates。只有 usage 的分块已在上面采集。
            if (metadata.candidates.isNotEmpty() || metadata.usageMetadata == null) {
                processStreamingChunk(decodeStreamingResponse(raw))?.let { emit(it) }
            }
        }
    }

    private fun <T : Any> encodeRequest(body: T, bodyType: KClass<T>): String {
        val serializer = json.serializersModule.serializer(bodyType.starProjectedType)
        val request = json.encodeToJsonElement(serializer, body).jsonObject
        val config = request["generationConfig"] as? JsonObject ?: return json.encodeToString(request)
        val extensions = config["additionalProperties"] as? JsonObject ?: return json.encodeToString(request)
        val thinking = extensions["thinkingConfig"] ?: return json.encodeToString(request)

        val remaining = extensions - "thinkingConfig"
        val updated = (config - "additionalProperties") + ("thinkingConfig" to thinking)
        val flattened = if (remaining.isEmpty()) updated else updated + ("additionalProperties" to JsonObject(remaining))
        return json.encodeToString(JsonObject(request + ("generationConfig" to JsonObject(flattened))))
    }

    private fun inspect(raw: String): GoogleResponseMetadata {
        val response = json.decodeFromString<GoogleResponseMetadata>(raw)
        val blockReason = response.promptFeedback?.string("blockReason")
        val error = response.error
        if (error != null || blockReason != null) {
            throw KoogHttpClientException(
                statusCode = (error?.get("code") as? JsonPrimitive)?.intOrNull?.takeIf { it in 400..599 },
                errorBody = raw,
                message = error?.string("message") ?: blockReason?.let { "Google blocked the prompt ($it)" }
                    ?: "Google returned an error in the response body",
            )
        }
        return response
    }

    private fun JsonObject.string(field: String): String? =
        (get(field) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}

@Serializable
private data class GoogleResponseMetadata(
    val responseId: String? = null,
    val promptFeedback: JsonObject? = null,
    val error: JsonObject? = null,
    val candidates: List<GoogleCandidateMetadata> = emptyList(),
    val usageMetadata: GoogleUsageMetadata? = null,
)

@Serializable
private data class GoogleUsageMetadata(
    val promptTokenCount: Int? = null,
    val candidatesTokenCount: Int? = null,
    val totalTokenCount: Int? = null,
    val cachedContentTokenCount: Int? = null,
)

@Serializable
private data class GoogleCandidateMetadata(
    val content: GoogleContentMetadata? = null,
    val finishReason: String? = null,
)

@Serializable
private data class GoogleContentMetadata(val parts: List<GooglePartMetadata> = emptyList())

@Serializable
private data class GooglePartMetadata(
    val functionCall: GoogleFunctionCallMetadata? = null,
    val thoughtSignature: String? = null,
)

@Serializable
private data class GoogleFunctionCallMetadata(val name: String, val id: String? = null)

/** 每个请求独立采集原始用量，流式工具签名按调用匹配回 Koog 已组装的帧。 */
private class GoogleResponseCapture : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<GoogleResponseCapture>

    private data class Call(val id: String?, val name: String, val index: Int, val signature: String?)

    private val calls = mutableListOf<Call>()
    private var responseId: String? = null
    private var finishReason: String? = null
    private var chunks = 0
    private var inputTokens: Int? = null
    private var outputTokens: Int? = null
    private var totalTokens: Int? = null
    private var cachedTokens: Int? = null

    @Synchronized
    fun record(response: GoogleResponseMetadata) {
        chunks++
        response.responseId?.let { responseId = it }
        response.usageMetadata?.let { usage ->
            usage.promptTokenCount?.let { inputTokens = it }
            usage.candidatesTokenCount?.let { outputTokens = it }
            usage.totalTokenCount?.let { totalTokens = it }
            usage.cachedContentTokenCount?.let { cachedTokens = it }
        }
        val candidate = response.candidates.firstOrNull() ?: return
        candidate.finishReason?.let { finishReason = it }
        candidate.content?.parts?.forEachIndexed { index, part ->
            part.functionCall?.let { call ->
                val previous = if (call.id == null) -1 else calls.indexOfFirst { it.id == call.id }
                if (previous == -1) {
                    calls.add(Call(call.id, call.name, index, part.thoughtSignature))
                } else if (part.thoughtSignature != null) {
                    calls[previous] = calls[previous].copy(signature = part.thoughtSignature)
                }
            }
        }
    }

    @Synchronized
    fun enrichUsage(meta: ResponseMetaInfo): ResponseMetaInfo = meta.copy(
        totalTokensCount = totalTokens ?: meta.totalTokensCount,
        inputTokensCount = inputTokens ?: meta.inputTokensCount,
        outputTokensCount = outputTokens ?: meta.outputTokensCount,
        metadata = cachedTokens?.let { cached ->
            buildJsonObject {
                meta.metadata?.forEach { (key, value) -> put(key, value) }
                put("cachedTokens", cached)
            }
        } ?: meta.metadata,
    )

    @Synchronized
    fun takeSignature(frame: StreamFrame.ToolCallComplete): String? {
        val byId = if (frame.id == null) -1 else calls.indexOfFirst { it.id == frame.id }
        val index = if (byId >= 0) byId else calls.indexOfFirst {
            it.id == null && it.name == frame.name && it.index == frame.index
        }
        return if (index >= 0) calls.removeAt(index).signature else null
    }

    @Synchronized
    fun incompleteMessage(): String = "Google SSE ended without an End frame (chunks=$chunks" +
        (responseId?.let { ", responseId=${it.take(64)}" } ?: "") +
        (finishReason?.let { ", finishReason=${it.take(32)}" } ?: "") + ")"
}
