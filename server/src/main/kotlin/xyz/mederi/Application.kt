package xyz.mederi

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import xyz.mederi.core.bridge.MederiAiCore
import xyz.mederi.core.contract.dto.ReadyInfo
import xyz.mederi.server.remoteModule
import xyz.mederi.provider.infrastructure.koog.retry.LlmRetryConfig
import java.io.File

/**
 * Mederi Server：独立 JVM 进程运行的遥控端点（headless 部署用）。
 *
 * 与 desktop 内嵌 server 共享同一套 Ktor 配置（见 shared jvmMain 的 RemoteServer/remoteModule）。
 * 默认自托管内置 wasmJs Web UI（同源访问）；若指定 `MEDERI_WEBAPP_DIR` 则优先从外部目录加载（开发调试用）。
 *
 * 启动参数（环境变量）：
 * - MEDERI_SERVER_PORT（默认 8081）
 * - MEDERI_CONFIG_DIR（默认 ~/.mederi）
 * - MEDERI_SERVER_PASSWORD（非空则 /v1 需要 Bearer 鉴权）
 * - MEDERI_WEBAPP_DIR（可选，覆盖内置 wasmJs Web UI 静态资源目录）
 * - MEDERI_LLM_RETRY_MAX（限流重试次数，默认 10；0 = 关闭重试）
 * - MEDERI_LLM_RETRY_MIN_MS / MEDERI_LLM_RETRY_MAX_MS（随机退避边界，默认 1000 / 10000）
 */
fun main() {
    val port = System.getenv("MEDERI_SERVER_PORT")?.toIntOrNull() ?: 8081
    val configDir = System.getenv("MEDERI_CONFIG_DIR") ?: "~/.mederi"
    val password = System.getenv("MEDERI_SERVER_PASSWORD")?.takeIf { it.isNotBlank() }
    val webappDir = System.getenv("MEDERI_WEBAPP_DIR")?.takeIf { it.isNotBlank() && File(it).isDirectory }

    // 限流重试设定（进程级，RetryableLLMClient 每次包装时读取）
    System.getenv("MEDERI_LLM_RETRY_MAX")?.toIntOrNull()?.let { LlmRetryConfig.maxRetries = it }
    System.getenv("MEDERI_LLM_RETRY_MIN_MS")?.toLongOrNull()?.let { LlmRetryConfig.minDelayMs = it }
    System.getenv("MEDERI_LLM_RETRY_MAX_MS")?.toLongOrNull()?.let { LlmRetryConfig.maxDelayMs = it }
    println("[MederiServer] LLM retry: maxRetries=${LlmRetryConfig.maxRetries}, " +
        "backoff=${LlmRetryConfig.minDelayMs}-${LlmRetryConfig.maxDelayMs}ms (random)")

    println("[MederiServer] Web UI hosting: ${webappDir?.let { "external directory ($it)" } ?: "embedded wasmJs resources (classpath:/static)"}")

    val aiCore = MederiAiCore(configDir)
    var initError: String? = null
    runBlocking {
        aiCore.initialize().onFailure { initError = it.message ?: "initialize failed" }
    }
    if (initError != null) {
        System.err.println("[MederiServer] core initialize failed: $initError")
    }

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        remoteModule(
            aiCore = aiCore,
            ready = ReadyInfo(ready = initError == null, configDir = configDir),
            password = password,
            webappDir = webappDir,
        )
    }.start(wait = true)
}
