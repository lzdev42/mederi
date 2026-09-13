package xyz.mederi.http

import ai.koog.http.client.HttpClientFactoryResolver
import ai.koog.http.client.KoogHttpClient
import kotlinx.serialization.json.Json

/**
 * Mederi 出站 HTTP 客户端的唯一工厂（唯一真理源）。
 *
 * 所有经 Koog 链路的 HTTP 请求（LLM 对话、models.dev 目录、MCP registry、模型列表拉取）
 * 都必须从这里创建客户端，统一注入 User-Agent 身份头。
 *
 * **禁止**在其他位置直接调 [HttpClientFactoryResolver.resolve()] 或使用 Koog 内部默认工厂——
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

    private val delegate: KoogHttpClient.Factory by lazy { HttpClientFactoryResolver.resolve() }

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
        requestTimeoutMillis = requestTimeoutMillis,
        connectTimeoutMillis = connectTimeoutMillis,
        socketTimeoutMillis = socketTimeoutMillis,
        json = json,
    )
}
