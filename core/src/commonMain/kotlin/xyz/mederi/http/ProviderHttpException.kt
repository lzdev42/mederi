package xyz.mederi.http

/**
 * 单次 HTTP 失败的响应元数据，沿异常 cause 链传至重试策略。
 *
 * [responseHeaders] 仅包含工厂白名单内的重试相关头（小写名称），不缓存到客户端全局状态，
 * 避免并行请求、API Key 轮询与不同会话之间串用服务器的等待指令。
 * [cause] 保留 Koog 的 HTTP 异常，供现有错误收集器提取状态码和错误体。
 */
class ProviderHttpException(
    val statusCode: Int,
    val responseHeaders: Map<String, String>,
    val responseBody: String?,
    cause: Throwable,
) : Exception("Provider HTTP $statusCode", cause)
