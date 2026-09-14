package xyz.mederi.debug

import ai.koog.http.client.KoogHttpClientException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.ConnectException
import java.net.UnknownHostException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedDeque
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 统一错误收集器：从任何 [Throwable] 中提取真正能解决问题的结构化诊断信息。
 *
 * 本模块是错误信息的**唯一真理源**——所有 catch 块只需调一次 [collect]，
 * 内部自动完成诊断提取 + 日志输出 + 历史记录。调用方不再需要分别调
 * `DebugLog.error` / `SseDiagnostics.extract` / `StreamTrace.record`。
 *
 * 提取的信息：
 * - 异常类型（类名）+ 完整消息
 * - cause 链（完整，类名 → 首行消息，最多 8 层）
 * - 堆栈跟踪摘要（前 30 帧，去 reflect 噪音）
 * - HTTP 状态码 + 错误体（从 [KoogHttpClientException] 提取）
 * - 网络异常类型（超时/重置/TLS/DNS）
 * - 错误分类（[ErrorCategory]）+ 严重级别（[ErrorSeverity]）
 * - 发生上下文（阶段、供应商、模型、会话、工具名）
 * - 恢复建议（用户可操作的下一步）
 * - 完整人类可读诊断报告（[ErrorRecord.fullDiagnostic]）
 *
 * ## 用法
 *
 * ```
 * val record = ErrorCollector.collect(throwable, ErrorContext(
 *     phase = "model_call",
 *     sessionId = sessionId,
 *     providerName = provider.name,
 *     modelName = model.name
 * ))
 * // 内部已完成 DebugLog.error 输出，调用方无需再打
 * // 发到事件总线
 * emit(sessionId, EventType.MESSAGE_ERROR, payload = record.toPayload())
 * ```
 *
 * ## 查询
 *
 * - [recent]：获取最近 50 条错误记录（ring buffer）
 * - [recentForSession]：按会话过滤
 * - [get]：按 errorId 精确查
 *
 * ## 跨进程（server → wasmJs）
 *
 * [ErrorRecord.toPayload] 把全部字段序列化为 `Map<String, String>`，
 * 经事件总线传到 wasmJs 客户端：UI 简报读 "error"，点击详情读 "fullDiagnostic"。
 * JVM 端可通过 [get] / [recent] 访问内存历史，取回完整 [ErrorRecord]。
 */
object ErrorCollector {

    private val recentErrors = ConcurrentLinkedDeque<ErrorRecord>()

    private const val MAX_HISTORY = 50
    private const val MAX_STACK_FRAMES = 30
    private const val MAX_CAUSE_DEPTH = 8
    private const val MAX_ERROR_BODY_LEN = 500
    private const val MAX_STACK_TRACE_LEN = 2000
    private const val MAX_SERVER_MESSAGE_LEN = 300

    /** 错误体解析：宽容模式（容忍供应商/代理返回的杂 JSON），禁止手拼/手解 JSON。 */
    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * 从异常中提取结构化错误记录。
     *
     * 内部自动完成：
     * 1. 诊断提取（HTTP 状态码 / 网络类型 / cause 链 / 堆栈）
     * 2. 分类 + 严重级别推导
     * 3. 恢复建议生成
     * 4. DebugLog.error 输出（调用方无需再打日志）
     * 5. 存入内存历史
     *
     * @param throwable 异常（不能为 null）
     * @param context 错误发生时的上下文
     * @return 结构化错误记录
     */
    fun collect(throwable: Throwable, context: ErrorContext = ErrorContext()): ErrorRecord {
        val diag = extractDiagnostics(throwable)

        val category = categorize(throwable, diag, context.phase)
        val severity = severityOf(category)
        val causeChain = extractCauseChain(throwable)
        val stackTrace = formatStackTrace(throwable, MAX_STACK_FRAMES)
        val recovery = suggestRecovery(category, throwable, diag, context)
        val fullDiagnostic = buildDiagnostic(throwable, category, severity, diag, causeChain, stackTrace, context, recovery)

        val record = ErrorRecord(
            id = "err_${UUID.randomUUID().toString().take(8)}",
            timestamp = Instant.now().toString(),
            category = category,
            severity = severity,
            exceptionType = throwable::class.qualifiedName ?: throwable.javaClass.name,
            exceptionMessage = throwable.message ?: "",
            causeChain = causeChain,
            stackTrace = stackTrace,
            httpStatusCode = diag.httpStatusCode,
            errorBody = diag.errorBody?.take(MAX_ERROR_BODY_LEN),
            serverMessage = extractServerMessage(diag.errorBody),
            networkErrorType = diag.networkErrorType,
            failureMode = diag.failureMode,
            phase = context.phase,
            providerId = context.providerId,
            providerName = context.providerName,
            modelId = context.modelId,
            modelName = context.modelName,
            sessionId = context.sessionId,
            toolName = context.toolName,
            recoverySuggestion = recovery,
            fullDiagnostic = fullDiagnostic
        )

        // 内部日志：调用方无需再打 DebugLog.error
        DebugLog.error("ErrorCollector", record.fullDiagnostic, throwable)

        addToHistory(record)
        return record
    }

    /** 便捷重载 */
    fun collect(throwable: Throwable, phase: String, sessionId: String? = null): ErrorRecord =
        collect(throwable, ErrorContext(phase = phase, sessionId = sessionId))

    /**
     * 收集无异常上下文的结构化警告记录（不依赖 [Throwable]）。
     *
     * 断流（流式连接提前结束且无 finish 标记）等"静默失败"不是异常、进不了 [collect]，
     * 但仍是用户需要知道的不完整事件。统一走本出口生成 WARNING 级 [ErrorRecord]，
     * 经同一事件通道 → UI 单一错误展示（ErrorBoard / 详细报告），不再散落进各消息。
     */
    fun collectWarning(
        message: String,
        detail: String? = null,
        context: ErrorContext = ErrorContext()
    ): ErrorRecord {
        val fullDiagnostic = buildString {
            appendLine("WARNING（无异常，静默失败）：$message")
            appendLine("phase: ${context.phase}  sessionId: ${context.sessionId}")
            context.providerName?.let { appendLine("Provider: $it (${context.providerId ?: "?"})") }
            context.modelName?.let { appendLine("Model: $it (${context.modelId ?: "?"})") }
            if (!detail.isNullOrBlank()) {
                appendLine()
                append(detail)
            }
            appendLine()
            appendLine("建议：回复可能不完整。可重发该消息让模型继续，或检查网络/代理/供应商稳定性。")
        }
        val record = ErrorRecord(
            id = "err_${UUID.randomUUID().toString().take(8)}",
            timestamp = Instant.now().toString(),
            category = ErrorCategory.NETWORK,
            severity = ErrorSeverity.WARNING,
            exceptionType = "StreamInterrupted",
            exceptionMessage = message,
            causeChain = emptyList(),
            stackTrace = "(silent stream interruption, no exception)",
            failureMode = FailureMode.PREMATURE_CLOSE,
            phase = context.phase,
            providerId = context.providerId,
            providerName = context.providerName,
            modelId = context.modelId,
            modelName = context.modelName,
            sessionId = context.sessionId,
            toolName = context.toolName,
            recoverySuggestion = "回复可能不完整。可重发消息让模型继续，或检查网络/代理/供应商稳定性。",
            fullDiagnostic = fullDiagnostic
        )
        DebugLog.error("ErrorCollector", "WARNING: $message")
        addToHistory(record)
        return record
    }

    /** 获取最近 N 条错误记录（新的在前） */
    fun recent(): List<ErrorRecord> = recentErrors.toList()

    /** 获取指定会话的最近错误记录 */
    fun recentForSession(sessionId: String): List<ErrorRecord> =
        recentErrors.filter { it.sessionId == sessionId }.toList()

    /** 按 errorId 精确查询 */
    fun get(errorId: String): ErrorRecord? = recentErrors.find { it.id == errorId }

    /** 清空历史 */
    fun clearHistory() { recentErrors.clear() }

    // ==================================================================
    // 诊断提取（原 SseDiagnostics 逻辑，吸收为内部）
    // ==================================================================

    /** 断流模式分类——回答"谁的责任" */
    enum class FailureMode {
        HTTP_ERROR,      // 服务端返回了非 2xx HTTP 状态码
        PREMATURE_CLOSE, // HTTP 2xx 但流提前结束
        NETWORK_ERROR,   // 网络层异常（超时/重置/TLS/DNS）
        CANCELLED,       // 用户中止
        UNKNOWN
    }

    /** 从异常链中提取的诊断信息 */
    data class DiagnosticInfo(
        val failureMode: FailureMode,
        val httpStatusCode: Int? = null,
        val errorBody: String? = null,
        val networkErrorType: String? = null,
        val networkErrorMessage: String? = null,
        val causeChain: List<String> = emptyList()
    ) {
        /** 人类可读摘要 */
        fun summary(): String = when (failureMode) {
            FailureMode.HTTP_ERROR -> buildString {
                append("HTTP $httpStatusCode")
                errorBody?.take(120)?.let { if (it.isNotBlank()) append(": $it") }
            }
            FailureMode.PREMATURE_CLOSE -> "服务器关闭连接但未发送结束标记"
            FailureMode.NETWORK_ERROR -> buildString {
                append("网络错误")
                networkErrorType?.let { append("($it)") }
                networkErrorMessage?.take(120)?.let { append(": $it") }
            }
            FailureMode.CANCELLED -> "用户中止"
            FailureMode.UNKNOWN -> buildString {
                networkErrorType?.let { append(it) }
                networkErrorMessage?.take(120)?.let { append(": $it") } ?: run {
                    if (networkErrorType == null) append("未知错误")
                }
            }
        }

        /** 责任方判定 */
        val responsibleParty: String get() = when (failureMode) {
            FailureMode.HTTP_ERROR, FailureMode.PREMATURE_CLOSE -> "SERVER"
            FailureMode.NETWORK_ERROR -> "NETWORK"
            FailureMode.CANCELLED -> "CLIENT"
            FailureMode.UNKNOWN -> "UNKNOWN"
        }
    }

    /**
     * 从 HTTP 错误体（errorBody JSON）中提取供应商返回的真实错误信息。
     *
     * 各供应商错误 JSON 形状不一：OpenAI/OpenRouter/Google 为 `{"error":{"message":"..."}}`、
     * Anthropic 为 `{"error":{"type":"...","message":"..."}}`、FastAPI 为 `{"detail":"..."}`。
     * 解析失败（非 JSON，如代理返回纯文本）时回退到错误体首行非空文本。
     *
     * 提取出的真实信息用于 ErrorBoard 简报——429 时用户需要区分"欠费"还是"限流"，
     * 而不是只看到 `Error from client: xxx (HTTP 429)` 这种干瘪异常名。
     */
    fun extractServerMessage(errorBody: String?): String? {
        if (errorBody.isNullOrBlank()) return null
        val root = runCatching {
            lenientJson.parseToJsonElement(errorBody)
        }.getOrNull()
        val found = root?.let(::findErrorMessage)
        if (!found.isNullOrBlank()) return found.trim().take(MAX_SERVER_MESSAGE_LEN)
        // 非 JSON 错误体：取首行非空文本
        return errorBody.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(MAX_SERVER_MESSAGE_LEN)
    }

    /**
     * [extractServerMessage] 的 [Throwable] 重载：沿 cause 链找
     * [KoogHttpClientException].errorBody 再解析。供重试提示等轻量场景复用同一套提取逻辑。
     */
    fun extractServerMessage(throwable: Throwable?): String? =
        throwable?.let { extractServerMessage(extractDiagnostics(it).errorBody) }

    private fun findErrorMessage(el: JsonElement?): String? {
        if (el == null || el is JsonNull) return null
        return when (el) {
            is JsonPrimitive -> el.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
            is JsonObject ->
                el["message"]?.let(::findErrorMessage)
                    ?: el["error"]?.let(::findErrorMessage)
                    ?: el["detail"]?.let(::findErrorMessage)
            is JsonArray -> el.firstNotNullOfOrNull { findErrorMessage(it) }
        }
    }

    /**
     * 从异常链中提取诊断信息。
     *
     * 沿 cause 链最多查 8 层，优先找 [KoogHttpClientException]（含 HTTP 状态码），
     * 其次识别底层网络异常类型。CancellationException 直接判 CANCELLED。
     */
    fun extractDiagnostics(throwable: Throwable?): DiagnosticInfo {
        if (throwable == null) return DiagnosticInfo(FailureMode.UNKNOWN)
        if (throwable is kotlinx.coroutines.CancellationException) {
            return DiagnosticInfo(
                failureMode = FailureMode.CANCELLED,
                networkErrorType = throwable::class.simpleName,
                networkErrorMessage = throwable.message
            )
        }

        val chain = mutableListOf<String>()
        var koogException: KoogHttpClientException? = null
        var networkException: Throwable? = null
        var networkType: String? = null

        var cur: Throwable? = throwable
        var depth = 0
        while (cur != null && depth < MAX_CAUSE_DEPTH) {
            val cls = cur::class.qualifiedName ?: cur.javaClass.name
            val msg = cur.message?.lineSequence()?.firstOrNull()?.take(120)
            chain.add(if (msg != null) "$cls → $msg" else cls)

            if (koogException == null && cur is KoogHttpClientException) {
                koogException = cur
            }

            if (networkException == null && isNetworkException(cur)) {
                networkException = cur
                networkType = networkExceptionType(cur)
            }

            cur = cur.cause
            depth++
        }

        // 优先用 HTTP 状态码判定
        if (koogException != null && koogException.statusCode != null) {
            return DiagnosticInfo(
                failureMode = FailureMode.HTTP_ERROR,
                httpStatusCode = koogException.statusCode,
                errorBody = koogException.errorBody,
                networkErrorType = throwable::class.simpleName,
                networkErrorMessage = throwable.message?.lineSequence()?.firstOrNull()?.take(120),
                causeChain = chain
            )
        }

        // 其次用网络异常类型判定
        if (networkException != null) {
            return DiagnosticInfo(
                failureMode = FailureMode.NETWORK_ERROR,
                networkErrorType = networkType ?: networkException::class.simpleName,
                networkErrorMessage = networkException.message?.lineSequence()?.firstOrNull()?.take(120),
                causeChain = chain
            )
        }

        // KoogHttpClientException 但没有 statusCode（流读取中途异常）
        if (koogException != null) {
            return DiagnosticInfo(
                failureMode = FailureMode.NETWORK_ERROR,
                networkErrorType = throwable::class.simpleName,
                networkErrorMessage = koogException.message?.lineSequence()?.firstOrNull()?.take(120)
                    ?: throwable.message?.lineSequence()?.firstOrNull()?.take(120),
                causeChain = chain
            )
        }

        return DiagnosticInfo(
            failureMode = FailureMode.UNKNOWN,
            networkErrorType = throwable::class.simpleName,
            networkErrorMessage = throwable.message?.lineSequence()?.firstOrNull()?.take(120),
            causeChain = chain
        )
    }

    private fun isNetworkException(e: Throwable): Boolean =
        e is SocketException || e is SocketTimeoutException || e is ConnectException ||
            e is UnknownHostException || e is SSLException || e is SSLHandshakeException

    private fun networkExceptionType(e: Throwable): String = when (e) {
        is SocketTimeoutException -> "SocketTimeout"
        is ConnectException -> "ConnectionRefused"
        is UnknownHostException -> "DNS解析失败"
        is SSLHandshakeException -> "TLS握手失败"
        is SSLException -> "TLS错误"
        is SocketException -> when {
            e.message?.lowercase()?.contains("reset") == true -> "连接被重置"
            e.message?.lowercase()?.contains("closed") == true -> "连接已关闭"
            e.message?.lowercase()?.contains("broken") == true -> "连接断裂"
            else -> "Socket异常"
        }
        else -> e::class.simpleName ?: "网络异常"
    }

    // ==================================================================
    // 分类与严重级别
    // ==================================================================

    private fun categorize(
        throwable: Throwable,
        diag: DiagnosticInfo,
        phase: String
    ): ErrorCategory {
        if (throwable is kotlinx.coroutines.CancellationException) return ErrorCategory.CANCELLED

        if (phase.startsWith("tool") || phase == "tool_call") return ErrorCategory.TOOL

        val msg = (throwable.message ?: "").lowercase()
        if (msg.contains("no api key") || msg.contains("apikey") || msg.contains("api key")) return ErrorCategory.CONFIG
        if (msg.contains("model not found") || msg.contains("provider for model") || msg.contains("no ai model")) return ErrorCategory.CONFIG

        val httpStatus = diag.httpStatusCode
        if (httpStatus != null) {
            if (httpStatus == 401 || httpStatus == 403) return ErrorCategory.AUTH
            if (httpStatus == 429) return ErrorCategory.RATE_LIMIT
            if (httpStatus in 400..499) return ErrorCategory.API
            if (httpStatus in 500..599) return ErrorCategory.API
        }

        if (searchCauseChain(throwable, listOf("invalid api key", "unauthorized", "forbidden", "authentication"))) return ErrorCategory.AUTH
        if (searchCauseChain(throwable, listOf("429", "rate limit", "ratelimit", "quota", "rpm", "tpm", "too many requests"))) return ErrorCategory.RATE_LIMIT
        if (diag.failureMode == FailureMode.NETWORK_ERROR) return ErrorCategory.NETWORK
        if (searchCauseChain(throwable, listOf("serialization", "deserialization", "json", "parse", "no creators", "missing field"))) return ErrorCategory.SERIALIZATION
        if (throwable is IllegalStateException) return ErrorCategory.STATE

        return ErrorCategory.INTERNAL
    }

    private fun severityOf(category: ErrorCategory): ErrorSeverity = when (category) {
        ErrorCategory.CANCELLED -> ErrorSeverity.WARNING
        ErrorCategory.RATE_LIMIT -> ErrorSeverity.RECOVERABLE
        ErrorCategory.NETWORK -> ErrorSeverity.RECOVERABLE
        ErrorCategory.CONFIG -> ErrorSeverity.FATAL
        ErrorCategory.AUTH -> ErrorSeverity.FATAL
        ErrorCategory.API -> ErrorSeverity.FATAL
        ErrorCategory.TOOL -> ErrorSeverity.WARNING
        ErrorCategory.SERIALIZATION -> ErrorSeverity.FATAL
        ErrorCategory.STATE -> ErrorSeverity.FATAL
        ErrorCategory.INTERNAL -> ErrorSeverity.FATAL
    }

    private fun suggestRecovery(
        category: ErrorCategory,
        throwable: Throwable,
        diag: DiagnosticInfo,
        context: ErrorContext
    ): String = when (category) {
        ErrorCategory.AUTH -> "API Key 无效或已过期。请在设置页检查 ${context.providerName ?: "供应商"} 的 API Key。"
        ErrorCategory.RATE_LIMIT -> "供应商限流/配额用尽。请稍后重试，或切换到其他供应商/模型。"
        ErrorCategory.NETWORK -> "网络连接异常（${diag.networkErrorType ?: "未知"}）。请检查网络/代理/VPN 后重试。"
        ErrorCategory.CONFIG -> "配置错误：${throwable.message ?: "未知"}。请检查模型/供应商设置。"
        ErrorCategory.API -> {
            val status = diag.httpStatusCode
            val body = diag.errorBody?.take(200)
            "API 返回错误 HTTP $status${body?.let { ": $it" } ?: ""}。请检查模型参数或联系供应商。"
        }
        ErrorCategory.TOOL -> "工具执行失败（${context.toolName ?: "未知工具"}）。请检查工具参数或重试。"
        ErrorCategory.SERIALIZATION -> "数据序列化/反序列化错误。可能是模型输出格式异常，请重试或切换模型。"
        ErrorCategory.STATE -> "会话状态错误：${throwable.message ?: "未知"}。请尝试重新发送或重启会话。"
        ErrorCategory.CANCELLED -> "操作已被取消。"
        ErrorCategory.INTERNAL -> "内部错误。请查看完整诊断日志，必要时重启应用。"
    }

    // ==================================================================
    // cause 链与堆栈
    // ==================================================================

    private fun extractCauseChain(throwable: Throwable): List<String> {
        val chain = mutableListOf<String>()
        var cur: Throwable? = throwable
        var depth = 0
        while (cur != null && depth < MAX_CAUSE_DEPTH) {
            val cls = cur::class.qualifiedName ?: cur.javaClass.name
            val msg = cur.message?.lineSequence()?.firstOrNull()?.take(200)
            chain.add(if (msg != null) "$cls → $msg" else cls)
            cur = cur.cause
            depth++
        }
        return chain
    }

    private fun formatStackTrace(throwable: Throwable, maxFrames: Int): String {
        val frames = throwable.stackTrace.take(maxFrames)
        val filtered = frames.filterNot { frame ->
            val cls = frame.className
            cls.startsWith("java.lang.reflect.") ||
                cls.startsWith("sun.reflect.") ||
                cls.startsWith("jdk.internal.reflect.")
        }
        val sb = StringBuilder()
        sb.append(throwable::class.qualifiedName ?: throwable.javaClass.name)
        val msg = throwable.message
        if (!msg.isNullOrBlank()) sb.append(": ").append(msg)
        sb.append("\n")
        for (frame in filtered) {
            val cls = frame.className.substringAfterLast('.')
            val method = if (frame.methodName != cls) "${cls}.${frame.methodName}" else cls
            val file = frame.fileName ?: "<unknown>"
            sb.append("    at $method($file:${frame.lineNumber})\n")
        }
        if (throwable.cause != null) {
            sb.append("Caused by: ")
            sb.append(formatStackTrace(throwable.cause!!, maxFrames / 2))
        }
        val result = sb.toString().trim()
        return if (result.length > MAX_STACK_TRACE_LEN) result.take(MAX_STACK_TRACE_LEN) + "\n... (truncated)" else result
    }

    private fun searchCauseChain(throwable: Throwable, markers: List<String>): Boolean {
        var cur: Throwable? = throwable
        var depth = 0
        while (cur != null && depth < 6) {
            val text = ((cur.message ?: "") + " " + cur.javaClass.simpleName).lowercase()
            if (markers.any { text.contains(it) }) return true
            cur = cur.cause
            depth++
        }
        return false
    }

    // ==================================================================
    // 完整诊断报告
    // ==================================================================

    private fun buildDiagnostic(
        throwable: Throwable,
        category: ErrorCategory,
        severity: ErrorSeverity,
        diag: DiagnosticInfo,
        causeChain: List<String>,
        stackTrace: String,
        context: ErrorContext,
        recovery: String
    ): String = buildString {
        appendLine("[$severity] $category: ${throwable::class.simpleName ?: throwable.javaClass.simpleName}")
        appendLine()

        val msg = throwable.message
        if (!msg.isNullOrBlank()) {
            appendLine("Message: $msg")
            appendLine()
        }

        if (diag.httpStatusCode != null) {
            appendLine("HTTP Status: ${diag.httpStatusCode}")
            diag.errorBody?.take(MAX_ERROR_BODY_LEN)?.let {
                if (it.isNotBlank()) appendLine("Error Body: $it")
            }
            appendLine("Failure Mode: ${diag.failureMode}")
            appendLine("Responsible Party: ${diag.responsibleParty}")
            appendLine()
        } else if (diag.failureMode == FailureMode.NETWORK_ERROR) {
            appendLine("Network Error: ${diag.networkErrorType ?: "unknown"}")
            diag.networkErrorMessage?.let { appendLine("Detail: $it") }
            appendLine()
        }

        appendLine("Context:")
        appendLine("  Phase: ${context.phase}")
        context.sessionId?.let { appendLine("  Session: $it") }
        context.providerName?.let { appendLine("  Provider: $it (${context.providerId ?: "?"})") }
        context.modelName?.let { appendLine("  Model: $it (${context.modelId ?: "?"})") }
        context.toolName?.let { appendLine("  Tool: $it") }
        appendLine()

        if (causeChain.size > 1) {
            appendLine("Cause Chain:")
            causeChain.forEachIndexed { i, entry ->
                val prefix = if (i == 0) "  → " else "  ↳ "
                appendLine("$prefix$entry")
            }
            appendLine()
        }

        appendLine("Stack Trace:")
        appendLine(stackTrace)
        appendLine()

        appendLine("Suggestion: $recovery")
    }

    // ==================================================================
    // 历史管理
    // ==================================================================

    private fun addToHistory(record: ErrorRecord) {
        recentErrors.addFirst(record)
        while (recentErrors.size > MAX_HISTORY) {
            recentErrors.removeLast()
        }
    }
}

// ======================================================================
// 数据模型
// ======================================================================

enum class ErrorCategory {
    NETWORK, API, AUTH, RATE_LIMIT, TOOL, CONFIG, SERIALIZATION, STATE, CANCELLED, INTERNAL
}

enum class ErrorSeverity {
    FATAL, RECOVERABLE, WARNING
}

data class ErrorContext(
    val phase: String = "unknown",
    val sessionId: String? = null,
    val providerId: String? = null,
    val providerName: String? = null,
    val modelId: String? = null,
    val modelName: String? = null,
    val toolName: String? = null
)

data class ErrorRecord(
    val id: String,
    val timestamp: String,
    val category: ErrorCategory,
    val severity: ErrorSeverity,
    val exceptionType: String,
    val exceptionMessage: String,
    val causeChain: List<String>,
    val stackTrace: String,
    val httpStatusCode: Int? = null,
    val errorBody: String? = null,
    val serverMessage: String? = null,
    val networkErrorType: String? = null,
    val failureMode: ErrorCollector.FailureMode = ErrorCollector.FailureMode.UNKNOWN,
    val phase: String,
    val providerId: String? = null,
    val providerName: String? = null,
    val modelId: String? = null,
    val modelName: String? = null,
    val sessionId: String? = null,
    val toolName: String? = null,
    val recoverySuggestion: String? = null,
    val fullDiagnostic: String
) {
    /**
     * 转为事件 payload（Map<String, String>）——发到事件总线供 UI 消费。
     *
     * UI 读 "error" 显示简报，读 "errorId" 调 ErrorCollector.get(id) 取完整详情。
     * wasmJs 端无 ErrorCollector 访问，可直接读 "fullDiagnostic" 等字段。
     */
    fun toPayload(): Map<String, String> = buildMap {
        put("error", formatShortMessage())
        put("errorId", id)
        put("errorCategory", category.name)
        put("errorSeverity", severity.name)
        put("errorType", exceptionType)
        put("errorPhase", phase)
        httpStatusCode?.let { put("httpStatus", it.toString()) }
        errorBody?.let { put("errorBody", it) }
        serverMessage?.let { put("serverMessage", it) }
        networkErrorType?.let { put("networkErrorType", it) }
        put("failureMode", failureMode.name)
        providerName?.let { put("providerName", it) }
        modelName?.let { put("modelName", it) }
        toolName?.let { put("toolName", it) }
        recoverySuggestion?.let { put("recoverySuggestion", it) }
        put("fullDiagnostic", fullDiagnostic)
        if (causeChain.isNotEmpty()) put("causeChain", causeChain.joinToString("\n"))
    }

    /** 短消息——给 UI 简报用（一行，有分类前缀 + 上下文） */
    fun formatShortMessage(): String = buildString {
        // 优先展示供应商错误体里解析出的真实信息（如 429 是欠费还是限流），
        // 而不是异常类型 + message 首行（对 HTTP 错误往往只是干瘪的 client/status 描述）。
        val serverMsg = serverMessage?.trim()?.take(200)
        if (!serverMsg.isNullOrBlank()) {
            append("[$category] $serverMsg")
            httpStatusCode?.let { append(" (HTTP $it)") }
            return@buildString
        }
        val shortType = exceptionType.substringAfterLast('.')
        append("[$category] $shortType")
        if (exceptionMessage.isNotBlank()) {
            append(": ")
            append(exceptionMessage.lineSequence().firstOrNull()?.take(150) ?: exceptionMessage.take(150))
        }
        httpStatusCode?.let { append(" (HTTP $it)") }
    }
}
