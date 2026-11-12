package xyz.mederi

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import xyz.mederi.config.MederiIdentity
import xyz.mederi.core.bridge.MederiAiCore
import xyz.mederi.core.contract.dto.ReadyInfo
import xyz.mederi.server.serverModule
import java.io.File

/**
 * Mederi Server：独立 JVM 进程运行的遥控端点（headless 部署用）。
 *
 * 与 desktop 内嵌 server 共享同一套 Ktor 配置（见 shared jvmMain 的 Server/serverModule）。
 * 完整发布包自托管内置 wasmJs Web UI；API-only 包不含页面。
 * 若指定 `MEDERI_WEBAPP_DIR` 则优先从外部目录加载（开发调试用）。
 *
 * 启动参数（环境变量）：
 * - MEDERI_SERVER_PORT（默认 8081）
 * - MEDERI_CONFIG_DIR（默认 ~/.mederi）
 * - MEDERI_SERVER_PASSWORD（非空则 /v1 需要 Bearer 鉴权）
 * - MEDERI_WEBAPP_DIR（可选，覆盖内置 wasmJs Web UI 静态资源目录）
 * - MEDERI_LLM_RETRY_MIN_MS / MEDERI_LLM_RETRY_MAX_MS（随机退避边界，默认 0 / 30000）
 */
fun main() {
    val port = System.getenv("MEDERI_SERVER_PORT")?.toIntOrNull() ?: 8081
    val configDir = System.getenv(MederiIdentity.configDirEnvVar) ?: "~/" + MederiIdentity.homeConfigRootName
    val password = System.getenv("MEDERI_SERVER_PASSWORD")?.takeIf { it.isNotBlank() }
    val webappDir = System.getenv("MEDERI_WEBAPP_DIR")?.takeIf { it.isNotBlank() && File(it).isDirectory }

    // 限流重试设定（经 MederiAiCore 构造参数注入）
    val retryMinDelayMs = System.getenv("MEDERI_LLM_RETRY_MIN_MS")?.toLongOrNull()
    val retryMaxDelayMs = System.getenv("MEDERI_LLM_RETRY_MAX_MS")?.toLongOrNull()
    println("[MederiServer] LLM retry: unlimited, delay=${retryMinDelayMs ?: "default"}-${retryMaxDelayMs ?: "default"}ms")

    val webHosting = when {
        webappDir != null -> "external directory ($webappDir)"
        Thread.currentThread().contextClassLoader.getResource("static/index.html") != null ->
            "embedded wasmJs resources (classpath:/static)"
        else -> "disabled (API-only distribution)"
    }
    println("[MederiServer] Web UI hosting: $webHosting")

    val aiCore = MederiAiCore(
        configDir,
        retryMinDelayMs = retryMinDelayMs,
        retryMaxDelayMs = retryMaxDelayMs
    )
    var initError: String? = null
    runBlocking {
        aiCore.initialize().onFailure { initError = it.message ?: "initialize failed" }
    }
    if (initError != null) {
        System.err.println("[MederiServer] core initialize failed: $initError")
    }

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        serverModule(
            aiCore = aiCore,
            ready = ReadyInfo(ready = initError == null, configDir = configDir),
            password = password,
            webappDir = webappDir,
        )
    }.start(wait = true)
}
