package xyz.mederi.http

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.KoogHttpClientException
import ai.koog.http.client.ktor.KtorKoogHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.readRemaining
import kotlinx.io.readString
import xyz.mederi.debug.DebugLog
import kotlinx.serialization.json.Json

/**
 * Mederi 出站 HTTP 客户端的唯一工厂（唯一真理源）。
 *
 * 所有经 Koog 链路的 HTTP 请求（LLM 对话、models.dev 目录、MCP registry、模型列表拉取）
 * 都必须从这里创建客户端，统一注入 User-Agent 身份头。
 *
 * **禁止**在其他位置直接调用 HTTP 工厂解析器或使用 Koog 内部默认工厂——
 * 那会绕开 User-Agent 注入，且让"如何获取 UA"这一逻辑分散到各处。
 *
 * [userAgent] 由 Mederi 装配层启动时注入（app/shared 的 `AppInfo.userAgent`）；
 * 默认值仅供 core 被单独使用（如测试）时兜底，不应被业务依赖。
 */
object MederiHttpClientFactory : KoogHttpClient.Factory {

    private const val USER_AGENT_HEADER = "User-Agent"

    /** 出站 User-Agent 值：由 Mederi 装配层（MederiAiCore / server）初始化时注入。 */
    @Volatile
    var userAgent: String = "Mederi/dev"

    // Koog 的异常不保留响应头；在它转换异常之前统一保留服务器的重试指令。
    // 使用与上游默认工厂相同的 HttpClient() 引擎选择，不替换传输引擎或流式解析器。
    private val delegate: KoogHttpClient.Factory by lazy {
        KtorKoogHttpClient.Factory(baseClient = HttpClient { install(ProviderFailureMetadata) })
    }

    override fun create(
        clientName: String,
        baseUrl: String,
        headers: Map<String, String>,
        queryParameters: Map<String, String>,
        requestTimeoutMillis: Long,
        connectTimeoutMillis: Long,
        socketTimeoutMillis: Long,
        json: Json,
    ): KoogHttpClient = delegate.create(
        clientName = clientName,
        baseUrl = baseUrl,
        // Mederi 身份头永远权威：即使调用处传入同名头也以它为准
        headers = headers + (USER_AGENT_HEADER to userAgent),
        queryParameters = queryParameters,
        // **禁用 Koog/Ktor 的请求超时与 socket 超时（INFINITE_TIMEOUT_MS = 禁用）**。
        // 它们是从"发出请求/收到首帧"起算的总时限——超长推理（30+ 分钟甚至 1 小时）只要
        // 没在时限内跑完就会被切断，这本质是在约束 AI 的处理时长，不是真正意义的超时。
        // 真正的"连接死亡"超时由 [MederiOpenAILLMClient.SSE_IDLE_TIMEOUT] 自行掌控：
        // SSE 只要还在吐任何一行（含 keep-alive 注释行）就不超时；只有连续一段时间没有任何
        // 数据到达才判超时。
        requestTimeoutMillis = io.ktor.client.plugins.HttpTimeoutConfig.INFINITE_TIMEOUT_MS,
        connectTimeoutMillis = connectTimeoutMillis,
        socketTimeoutMillis = io.ktor.client.plugins.HttpTimeoutConfig.INFINITE_TIMEOUT_MS,
        json = json,
    )
}

/** 只截取失败响应；成功的 SSE/lines 响应必须保持流式消费。 */
@OptIn(InternalAPI::class)
private val ProviderFailureMetadata = createClientPlugin("MederiProviderFailureMetadata") {
    onResponse { response ->
        if (response.status.value >= 400) {
            // SSE 的 bodyAsText 会再次进入 SSE 变换器并吞掉失败体。
            // 这里只在 >=400 时读取原始字节，绕过成功流专用的 SSE 变换器。
            val body = response.rawContent.readRemaining().readString()
            val headers = response.headers.entries()
                .filter { (name, _) -> name.lowercase() in PROVIDER_RETRY_HEADERS }
                .associate { (name, values) -> name.lowercase() to values.joinToString(",") }
            DebugLog.event("ProviderHttp", "HTTP ${response.status.value}; retryHeaders=$headers")
            throw ProviderHttpException(
                statusCode = response.status.value,
                responseHeaders = headers,
                responseBody = body,
                // 保持 ErrorCollector 原有的 Koog HTTP 诊断提取链路。
                cause = KoogHttpClientException(statusCode = response.status.value, errorBody = body),
            )
        }
    }
}

// 不收集 Set-Cookie、鉴权或其它无关响应头，避免把敏感元数据带入诊断。
private val PROVIDER_RETRY_HEADERS = setOf(
    "retry-after", "retry-after-ms", "x-ms-retry-after-ms", "x-should-retry", "date",
)
