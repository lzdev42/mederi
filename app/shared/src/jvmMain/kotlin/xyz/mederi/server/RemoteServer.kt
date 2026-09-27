package xyz.mederi.server

import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.http.content.CompressedFileType
import io.ktor.server.http.content.staticFiles
import io.ktor.server.http.content.staticResources
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import xyz.mederi.core.contract.models.UpdateSubagentConfigInput
import xyz.mederi.core.contract.models.UpdateSubagentGlobalSettingsInput
import io.ktor.server.sse.ServerSSESession
import io.ktor.server.sse.SSE
import io.ktor.server.sse.heartbeat
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds
import xyz.mederi.api.exception.MederiException
import xyz.mederi.api.exception.MederiNotFoundException
import xyz.mederi.api.exception.MederiStateException
import xyz.mederi.api.exception.MederiValidationException
import xyz.mederi.core.bridge.MederiAiCore
import xyz.mederi.core.contract.dto.AddApiKeyInput
import xyz.mederi.core.contract.dto.AddModelInput
import xyz.mederi.core.contract.dto.ApiError
import xyz.mederi.core.contract.dto.BuiltinProviderInput
import xyz.mederi.core.contract.dto.ChatPromptInput
import xyz.mederi.core.contract.dto.CreateConversationInput
import xyz.mederi.core.contract.dto.CreateCustomProviderInput
import xyz.mederi.core.contract.dto.CreateProjectInput
import xyz.mederi.core.contract.dto.GenerateAgentsFileInput
import xyz.mederi.core.contract.dto.GenerateAgentsFileResponse
import xyz.mederi.core.contract.dto.InstallMcpServerInput
import xyz.mederi.core.contract.dto.InstallSkillInput
import xyz.mederi.core.contract.dto.McpServerJsonResponse
import xyz.mederi.core.contract.dto.SetMcpServerEnabledInput
import xyz.mederi.core.contract.dto.SetSkillsRootInput
import xyz.mederi.core.contract.dto.SkillsRootResponse
import xyz.mederi.core.contract.dto.UpdateMcpServerInput
import xyz.mederi.core.contract.dto.ProviderUpdateInput
import xyz.mederi.core.contract.dto.ReadyInfo
import xyz.mederi.core.contract.dto.RenameConversationInput
import xyz.mederi.core.contract.dto.RenameProjectInput
import xyz.mederi.core.contract.dto.ResolveQuestionInput
import xyz.mederi.core.contract.dto.ResolvePlanApprovalInput
import xyz.mederi.core.contract.dto.SetModelEnabledInput
import xyz.mederi.core.contract.dto.UpdateModelInput
import xyz.mederi.core.contract.models.CoreEvent
import xyz.mederi.core.contract.models.CoreEventType
import xyz.mederi.ui.appstate.RemoteStartResult
import java.io.File

/**
 * 内嵌遥控 server（desktop 与独立 server 共用）。
 *
 * 与本体共享同一个 [MederiAiCore] 实例——遥控端看到的 projects/providers/会话
 * 就是桌面 UI 正在用的那份，无同步成本。
 *
 * - `webappDir != null` 时同时托管 wasm Web UI（同源部署，浏览器访问根路径即 UI，
 *   wasm 端 ServerAiCore 默认连 origin，无需填任何参数）。
 * - `password != null` 时所有 `/v1` 路由要求 `Authorization: Bearer <password>`，
 *   `/` 与 `/v1/ready` 豁免（健康检查不泄露数据）。
 */
object RemoteServer {

    private var server: EmbeddedServer<*, *>? = null

    val isRunning: Boolean get() = server != null

    /**
     * 启动遥控端点。已在运行时幂等返回 Started(实际端口)。
     *
     * 端口策略：先按 [requestedPort] 起；若该端口被占用（bind 失败），改用 port=0
     * 让 OS 自动挑空闲端口，再经 resolvedConnectors 读回实际端口。返回结果由调用方
     * （AppState.startRemoteControl）持久化为下次启动的默认端口。
     */
    suspend fun start(
        aiCore: MederiAiCore,
        requestedPort: Int = 8081,
        password: String? = null,
        webappDir: String? = null,
    ): RemoteStartResult {
        val existing = server
        if (existing != null) {
            val actual = try {
                existing.engine.resolvedConnectors().first().port
            } catch (e: Exception) {
                requestedPort
            }
            return RemoteStartResult.Started(actual)
        }
        val ready = ReadyInfo(ready = true, configDir = "~/.mederi")

        suspend fun tryBind(port: Int): Result<Int> = try {
            val srv = embeddedServer(Netty, port = port, host = "0.0.0.0") {
                remoteModule(aiCore, ready, password, webappDir)
            }
            srv.start(wait = false)
            val bound = srv.engine.resolvedConnectors().first().port
            server = srv
            Result.success(bound)
        } catch (e: Exception) {
            Result.failure(e)
        }

        val first = tryBind(requestedPort)
        return when {
            first.isSuccess -> RemoteStartResult.Started(first.getOrThrow())
            else -> {
                // 请求端口被占用 → port=0 让 OS 分配空闲端口
                val fallback = tryBind(0)
                if (fallback.isSuccess) {
                    RemoteStartResult.Started(fallback.getOrThrow(), portFallback = true)
                } else {
                    RemoteStartResult.Failed(fallback.exceptionOrNull()?.message ?: "端口启动失败")
                }
            }
        }
    }

    /** 停止并销毁 server。未运行时无操作（幂等）。 */
    fun stop() {
        server?.stop(gracePeriodMillis = 500, timeoutMillis = 2000)
        server = null
    }
}

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
private val ContentTypeWasm = ContentType("application", "wasm")

/**
 * Ktor Application 配置：REST/SSE + 鉴权 + 可选 wasm UI 静态托管。
 * desktop 内嵌（[RemoteServer]）与独立 server 入口共用同一套配置。
 */
fun Application.remoteModule(
    aiCore: MederiAiCore,
    ready: ReadyInfo,
    password: String? = null,
    webappDir: String? = null,
) {
    install(ContentNegotiation) { json(json) }
    install(SSE)
    install(CORS) {
        // 本地开发工具：wasmJs dev server 与 server 跨端口，放开跨域；
        // Authorization：遥控鉴权 Bearer 密码（浏览器 fetch 携带自定义 header 会走 preflight）
        // Cache-Control：ktor SSE 客户端带 no-store，跨端口 SSE 的 preflight 必须放行该头
        anyHost()
        allowHeader("Content-Type")
        allowHeader("Authorization")
        allowHeader("Cache-Control")
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
        allowNonSimpleContentTypes = true
    }
    if (password != null) {
        install(Authentication) {
            bearer("mederi-remote") {
                // Ktor 3.5 API：authenticate（receiver=call, 参数=credentials）；旧版为 validate
                authenticate { credentials ->
                    if (credentials.token == password) UserIdPrincipal("remote") else null
                }
            }
        }
    }

    routing {
        // wasmJs Web UI 同源托管：
        // 1. 若外部指定 webappDir（MEDERI_WEBAPP_DIR），从本地文件系统加载（开发调试用）；
        // 2. 否则自托管：直接从 Classpath 内置资源（resources/static）读取打包的 wasmJs 产物。
        if (webappDir != null) {
            staticFiles("/", File(webappDir)) {
                default("index.html")   // SPA fallback：任意路径回首页
                preCompressed(CompressedFileType.GZIP)
                contentType { file ->
                    if (file.extension.equals("wasm", ignoreCase = true)) ContentTypeWasm else null
                }
            }
        } else {
            staticResources("/", "static") {
                default("index.html")   // SPA fallback：任意路径回首页
                preCompressed(CompressedFileType.GZIP)
                contentType { url ->
                    if (url.path.endsWith(".wasm", ignoreCase = true)) ContentTypeWasm else null
                }
            }
        }

        get("/info") { call.respondText("Mederi Server", ContentType.Text.Plain) }
        // 健康检查豁免鉴权：探测"server 活着"不泄露任何数据
        get("/v1/ready") { call.respond(ready) }

        if (password != null) {
            authenticate("mederi-remote") { v1Routes(aiCore) }
        } else {
            v1Routes(aiCore)
        }
    }
}

/** 全部 `/v1` 业务路由（鉴权包裹与否由 [remoteModule] 决定）。 */
fun Route.v1Routes(aiCore: MederiAiCore) {
    get("/v1/presets") { call.respondData(aiCore.builtinPresets) }
    get("/v1/agents") { call.respondData(aiCore.availableAgents.value) }
    get("/v1/models") { call.respondData(aiCore.availableModels.value) }
    get("/v1/providers") { call.respondData(aiCore.providers.value) }
    get("/v1/projects") { call.respondData(aiCore.projects.value) }

    // 进程资源监控：返回本进程（即被遥控端宿主）真实 CPU/内存占用，遥控端透传展示
    get("/v1/system/stats") { call.respondResult(aiCore.getProcessStats()) }

    // ------------------------------------------------------------------
    // Project
    // ------------------------------------------------------------------

    post("/v1/projects") {
        val input = call.receive<CreateProjectInput>()
        call.respondResult(aiCore.createProject(input))
    }
    patch("/v1/projects/{id}") {
        val input = call.receive<RenameProjectInput>()
        call.respondResult(aiCore.renameProject(call.parameters["id"]!!, input.name))
    }
    delete("/v1/projects/{id}") {
        call.respondResult(aiCore.deleteProject(call.parameters["id"]!!))
    }
    // AGENTS.md 生成：扫描项目并生成（已存在则原地改进）项目根 AGENTS.md
    post("/v1/projects/{id}/agents-file/generate") {
        val input = call.receive<GenerateAgentsFileInput>()
        call.respondResult(
            aiCore.generateAgentsFile(call.parameters["id"]!!, input.modelId)
                .map { GenerateAgentsFileResponse(it) }
        )
    }

    // ------------------------------------------------------------------
    // Session / Conversation
    // ------------------------------------------------------------------

    post("/v1/sessions") {
        val input = call.receive<CreateConversationInput>()
        call.respondResult(aiCore.createConversation(input.projectId, input.agent))
    }
    delete("/v1/sessions/{id}") {
        call.respondResult(aiCore.deleteConversation(call.parameters["id"]!!))
    }
    patch("/v1/sessions/{id}") {
        val input = call.receive<RenameConversationInput>()
        call.respondResult(aiCore.renameConversation(call.parameters["id"]!!, input.title))
    }
    get("/v1/sessions/{id}/snapshot") {
        call.respondResult(aiCore.getSnapshot(call.parameters["id"]!!))
    }
    get("/v1/sessions/{id}/messages") {
        call.respondResult(aiCore.listMessagesPage(call.parameters["id"]!!))
    }
    // 原始消息直读（调试用）：落库 JSON 原样透传，不解析不映射
    get("/v1/sessions/{id}/messages/raw") {
        call.respondResult(aiCore.listRawMessages(call.parameters["id"]!!))
    }
    get("/v1/sessions/{id}/messages/{messageId}") {
        call.respondResult(aiCore.getMessage(call.parameters["id"]!!, call.parameters["messageId"]!!))
    }
    post("/v1/sessions/{id}/messages") {
        val input = call.receive<ChatPromptInput>()
        call.respondResult(aiCore.sendMessage(call.parameters["id"]!!, input))
    }
    post("/v1/sessions/{id}/steer") {
        val input = call.receive<ChatPromptInput>()
        call.respondResult(aiCore.steerMessage(call.parameters["id"]!!, input))
    }
    post("/v1/sessions/{id}/abort") {
        call.respondResult(aiCore.abort(call.parameters["id"]!!))
    }
    post("/v1/sessions/{id}/rollback") {
        val input = call.receive<xyz.mederi.core.contract.dto.RollbackMessageInput>()
        call.respondResult(aiCore.rollbackToMessage(call.parameters["id"]!!, input.messageId))
    }
    post("/v1/sessions/{id}/questions/{questionId}") {
        val input = call.receive<ResolveQuestionInput>()
        call.respondResult(aiCore.resolveQuestion(call.parameters["id"]!!, call.parameters["questionId"]!!, input.answers))
    }
    post("/v1/sessions/{id}/plans/{planId}/approve") {
        val input = call.receive<ResolvePlanApprovalInput>()
        call.respondResult(
            aiCore.resolvePlanApproval(
                call.parameters["id"]!!,
                call.parameters["planId"]!!,
                input.approved,
                input.model,
                input.thinkingLevel
            )
        )
    }
    post("/v1/sessions/{id}/compress") {
        call.respondResult(aiCore.compressHistory(call.parameters["id"]!!))
    }
    get("/v1/sessions/{id}/diffs") {
        call.respondResult(aiCore.getFileDiffs(call.parameters["id"]!!, call.parameters["messageId"]))
    }
    get("/v1/sessions/{id}/office-preview") {
        call.respondResult(aiCore.previewOffice(call.parameters["id"]!!, call.parameters["path"] ?: ""))
    }

    // ------------------------------------------------------------------
    // 事件 SSE
    // ------------------------------------------------------------------

    // 全局事件流（AiCore.events() 的 wire 形态）
    sse("/v1/events") {
        respondEventStream(aiCore.events())
    }
    // 会话事件流：观察某个会话的客户端用 SnapshotReducer 聚合为快照
    sse("/v1/sessions/{id}/events") {
        val id = call.parameters["id"]!!
        respondEventStream(
            aiCore.events()
                .filter { it.sessionId == id }
        )
    }

    // ------------------------------------------------------------------
    // Provider / Model / Key
    // ------------------------------------------------------------------

    post("/v1/providers") {
        val input = call.receive<CreateCustomProviderInput>()
        call.respondResult(aiCore.createCustomProvider(input))
    }
    post("/v1/providers/builtin") {
        val input = call.receive<BuiltinProviderInput>()
        call.respondResult(aiCore.addBuiltinProvider(input.name, input.apiKey))
    }
    patch("/v1/providers/{id}") {
        val input = call.receive<ProviderUpdateInput>()
        call.respondResult(aiCore.configureProvider(call.parameters["id"]!!, input))
    }
    delete("/v1/providers/{id}") {
        call.respondResult(aiCore.deleteProvider(call.parameters["id"]!!))
    }
    post("/v1/providers/{id}/models") {
        val input = call.receive<AddModelInput>()
        call.respondResult(
            aiCore.addProviderModel(
                providerId = call.parameters["id"]!!,
                providerModelId = input.providerModelId,
                name = input.name,
                supportsThinking = input.supportsThinking,
                supportsImages = input.supportsImages,
                contextWindow = input.contextWindow,
                maxTokens = input.maxTokens,
                reasoningLevels = input.reasoningLevels,
                isEnabled = input.isEnabled
            )
        )
    }
    post("/v1/providers/{id}/models/refresh") {
        call.respondResult(aiCore.refreshProviderModels(call.parameters["id"]!!))
    }
    post("/v1/providers/{id}/models/auto-setup") {
        call.respondResult(aiCore.autoSetupProviderModels(call.parameters["id"]!!))
    }
    patch("/v1/providers/{id}/models/{modelId}") {
        val input = call.receive<UpdateModelInput>()
        call.respondResult(
            aiCore.updateProviderModel(
                providerId = call.parameters["id"]!!,
                modelId = call.parameters["modelId"]!!,
                name = input.name,
                supportsThinking = input.supportsThinking,
                supportsImages = input.supportsImages,
                contextWindow = input.contextWindow,
                maxTokens = input.maxTokens,
                reasoningLevels = input.reasoningLevels,
                isEnabled = input.isEnabled
            )
        )
    }
    post("/v1/providers/{id}/models/{modelId}/enabled") {
        val input = call.receive<SetModelEnabledInput>()
        call.respondResult(aiCore.setModelEnabled(call.parameters["id"]!!, call.parameters["modelId"]!!, input.enabled))
    }
    delete("/v1/providers/{id}/models/{modelId}") {
        call.respondResult(aiCore.deleteProviderModel(call.parameters["id"]!!, call.parameters["modelId"]!!))
    }
    post("/v1/providers/{id}/keys") {
        val input = call.receive<AddApiKeyInput>()
        call.respondResult(aiCore.addProviderApiKey(call.parameters["id"]!!, input.name, input.key, input.isDefault))
    }
    delete("/v1/providers/{id}/keys/{keyId}") {
        call.respondResult(aiCore.deleteProviderApiKey(call.parameters["id"]!!, call.parameters["keyId"]!!))
    }
    post("/v1/providers/{id}/keys/{keyId}/set-default") {
        call.respondResult(aiCore.setDefaultProviderApiKey(call.parameters["id"]!!, call.parameters["keyId"]!!))
    }

    // ------------------------------------------------------------------
    // MCP Server 管理
    // ------------------------------------------------------------------

    get("/v1/mcp/servers") {
        call.respondResult(aiCore.listMcpServers())
    }
    post("/v1/mcp/servers") {
        val input = call.receive<InstallMcpServerInput>()
        call.respondResult(aiCore.installMcpServer(input.json))
    }
    patch("/v1/mcp/servers/{name}") {
        val input = call.receive<UpdateMcpServerInput>()
        call.respondResult(aiCore.updateMcpServer(call.parameters["name"]!!, input.json))
    }
    post("/v1/mcp/servers/{name}/enabled") {
        val input = call.receive<SetMcpServerEnabledInput>()
        call.respondResult(aiCore.setMcpServerEnabled(call.parameters["name"]!!, input.enabled))
    }
    delete("/v1/mcp/servers/{name}") {
        call.respondResult(aiCore.deleteMcpServer(call.parameters["name"]!!))
    }
    post("/v1/mcp/servers/{name}/verify") {
        call.respondResult(aiCore.verifyMcpServer(call.parameters["name"]!!))
    }
    get("/v1/mcp/servers/{name}/json") {
        call.respondResult(aiCore.getMcpServerJson(call.parameters["name"]!!).map { McpServerJsonResponse(it) })
    }

    // ------------------------------------------------------------------
    // Skill 管理
    // ------------------------------------------------------------------

    get("/v1/skills") {
        call.respondResult(aiCore.listSkills())
    }
    get("/v1/skills/root") {
        call.respondResult(aiCore.getSkillsRoot().map { SkillsRootResponse(it) })
    }
    post("/v1/skills/root") {
        val input = call.receive<SetSkillsRootInput>()
        call.respondResult(aiCore.setSkillsRoot(input.path))
    }
    post("/v1/skills/install") {
        val input = call.receive<InstallSkillInput>()
        call.respondResult(aiCore.installSkill(input.url))
    }
    delete("/v1/skills/{name}") {
        call.respondResult(aiCore.uninstallSkill(call.parameters["name"]!!))
    }

    // ------------------------------------------------------------------
    // 子代理模型配置
    // ------------------------------------------------------------------

    get("/v1/subagent-configs") {
        call.respondResult(aiCore.listSubagentConfigs())
    }
    put("/v1/subagent-configs/{role}") {
        val role = call.parameters["role"]!!
        val input = call.receive<UpdateSubagentConfigInput>()
        call.respondResult(aiCore.updateSubagentConfig(role, input))
    }

    get("/v1/subagent-configs/global") {
        call.respondResult(aiCore.getSubagentGlobalSettings())
    }

    put("/v1/subagent-configs/global") {
        val input = call.receive<UpdateSubagentGlobalSettingsInput>()
        call.respondResult(aiCore.updateSubagentGlobalSettings(input))
    }

    // ------------------------------------------------------------------
    // 子代理任务汇报
    // ------------------------------------------------------------------

    get("/v1/subagents/{agentId}/report") {
        val agentId = call.parameters["agentId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        call.respondResult(aiCore.getSubagentReport(agentId))
    }
}

// ---------------------------------------------------------------------------
// 响应助手（ApplicationCall 扩展）
// ---------------------------------------------------------------------------

/** Result → JSON 响应；Unit 成功返回 {}，失败按异常类型映射 HTTP 状态码。 */
suspend inline fun <reified T : Any> ApplicationCall.respondResult(result: Result<T>) {
    result.fold(
        onSuccess = { value ->
            if (value is Unit) {
                respondText("{}", ContentType.Application.Json)
            } else {
                respond(value)
            }
        },
        onFailure = { e -> respondError(e) }
    )
}

/** 裸值（StateFlow 当前值等非 Result 数据）→ JSON 响应。 */
suspend inline fun <reified T : Any> ApplicationCall.respondData(value: T) {
    respond(value)
}

suspend fun ApplicationCall.respondError(e: Throwable) {
    val (status, message) = when (e) {
        is MederiNotFoundException -> HttpStatusCode.NotFound to (e.message ?: "not found")
        is MederiValidationException -> HttpStatusCode.BadRequest to (e.message ?: "bad request")
        is MederiStateException -> HttpStatusCode.Conflict to (e.message ?: "conflict")
        is MederiException -> HttpStatusCode.BadRequest to (e.message ?: "mederi error")
        else -> HttpStatusCode.InternalServerError to (e.message ?: "internal error")
    }
    respond(status, ApiError(message))
}

/**
 * [ServerSSESession] 事件发送：15s 心跳（注释帧，客户端默认不消费）防代理断连。
 * 帧体用 [CoreEvent] 类型化序列化（禁止拼装 JSON 字符串）。
 * 客户端断开时 collect 被取消，Flow 随结构化并发一起取消。
 */
suspend fun ServerSSESession.respondEventStream(events: Flow<CoreEvent>) {
    heartbeat {
        period = 15.seconds
        eventProvider = { ServerSentEvent(comments = "ping") }
    }
    events.collect { event ->
        send(ServerSentEvent(data = json.encodeToString(CoreEvent.serializer(), event)))
    }
}
