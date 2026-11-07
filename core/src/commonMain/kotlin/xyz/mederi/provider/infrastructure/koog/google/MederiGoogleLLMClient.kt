package xyz.mederi.provider.infrastructure.koog.google

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.KoogHttpClientException
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.LLMClientException
import ai.koog.prompt.executor.clients.google.GoogleClientSettings
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.streaming.IncompleteStreamException
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.serializer
import xyz.mederi.http.MederiHttpClientFactory
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass
import kotlin.reflect.full.cast
import kotlin.reflect.full.starProjectedType

/** 补齐当前 Koog Google 客户端没有映射的原生请求和流式响应字段。 */
internal class MederiGoogleLLMClient(apiKey: String, settings: GoogleClientSettings) :
    GoogleLLMClient(apiKey, settings, GoogleHttpClientFactory) {

    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> = flow {
        val capture = GoogleStreamCapture()
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
                emit(frame)
            }
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
        inspect(raw)
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
        val capture = currentCoroutineContext()[GoogleStreamCapture]
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
            processStreamingChunk(decodeStreamingResponse(raw))?.let { emit(it) }
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

/** 隔离每次流式请求，并把传输层采集的签名匹配回 Koog 已组装的工具调用。 */
private class GoogleStreamCapture : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<GoogleStreamCapture>

    private data class Call(val id: String?, val name: String, val index: Int, val signature: String?)

    private val calls = mutableListOf<Call>()
    private var responseId: String? = null
    private var finishReason: String? = null
    private var chunks = 0

    @Synchronized
    fun record(response: GoogleResponseMetadata) {
        chunks++
        response.responseId?.let { responseId = it }
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
