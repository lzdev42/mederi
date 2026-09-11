package xyz.mederi.debug

import ai.koog.http.client.KoogHttpClientException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.ConnectException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/**
 * SSE 断流诊断提取器：从异常链中提取真正有用的排错数据。
 *
 * 核心问题：SSE 断了，不知道谁的原因断的。
 * 本工具沿 cause 链找出 [KoogHttpClientException]（含 HTTP 状态码 + 错误体），
 * 同时识别底层网络异常类型（Socket 超时 / 连接重置 / TLS 失败 / DNS 失败），
 * 给出结构化诊断结论——到底是 API Server 返回错误，还是网络层断了。
 *
 * 用法：
 * ```
 * val info = SseDiagnostics.extract(exception)
 * println(info.summary())  // "HTTP 502: upstream timeout" or "网络错误: SocketTimeoutException: Read timed out"
 * ```
 */
object SseDiagnostics {

    /** 断流模式分类——回答"谁的责任" */
    enum class FailureMode {
        /** 服务端返回了非 2xx HTTP 状态码（API Server 的责任） */
        HTTP_ERROR,
        /** HTTP 层成功（2xx）但流提前结束，未收到 [DONE]（API Server 或中间代理断连） */
        PREMATURE_CLOSE,
        /** 网络层异常：连接重置/超时/TLS 失败/DNS 失败（网络或对端责任） */
        NETWORK_ERROR,
        /** 协程取消（用户中止，非故障） */
        CANCELLED,
        /** 未知异常 */
        UNKNOWN
    }

    data class DiagnosticInfo(
        val failureMode: FailureMode,
        val httpStatusCode: Int? = null,
        val errorBody: String? = null,
        val exceptionType: String? = null,
        val exceptionMessage: String? = null,
        /** 完整 cause 链（类名 → 首行消息），用于日志/StreamTrace */
        val causeChain: List<String> = emptyList()
    ) {
        /**
         * 人类可读的诊断摘要——直接可用作 UI 警告或日志。
         * 例：
         * - "HTTP 502: upstream timeout"
         * - "网络错误(SocketTimeoutException): Read timed out"
         * - "连接被重置(SocketException): Connection reset"
         * - "TLS 握手失败(SSLHandshakeException): ..."
         */
        fun summary(): String = when (failureMode) {
            FailureMode.HTTP_ERROR -> buildString {
                append("HTTP $httpStatusCode")
                errorBody?.take(120)?.let { if (it.isNotBlank()) append(": $it") }
            }
            FailureMode.PREMATURE_CLOSE -> "服务器关闭连接但未发送结束标记"
            FailureMode.NETWORK_ERROR -> buildString {
                append("网络错误")
                exceptionType?.let { append("($it)") }
                exceptionMessage?.take(120)?.let { append(": $it") }
            }
            FailureMode.CANCELLED -> "用户中止"
            FailureMode.UNKNOWN -> buildString {
                exceptionType?.let { append(it) }
                exceptionMessage?.take(120)?.let { append(": $it") } ?: run {
                    if (exceptionType == null) append("未知错误")
                }
            }
        }

        /**
         * 责任方判定——用于 UI 提示和日志分类。
         * - SERVER: 服务端问题（HTTP 错误或服务端断连）
         * - NETWORK: 网络层问题
         * - CLIENT: 客户端（用户中止）
         * - UNKNOWN: 无法判定
         */
        val responsibleParty: String get() = when (failureMode) {
            FailureMode.HTTP_ERROR, FailureMode.PREMATURE_CLOSE -> "SERVER"
            FailureMode.NETWORK_ERROR -> "NETWORK"
            FailureMode.CANCELLED -> "CLIENT"
            FailureMode.UNKNOWN -> "UNKNOWN"
        }
    }

    /**
     * 从异常链中提取诊断信息。
     *
     * 沿 cause 链最多查 8 层，优先找 [KoogHttpClientException]（含 HTTP 状态码），
     * 其次识别底层网络异常类型。
     * [CancellationException] 直接判 CANCELLED。
     */
    fun extract(throwable: Throwable?): DiagnosticInfo {
        if (throwable == null) return DiagnosticInfo(FailureMode.UNKNOWN)
        if (throwable is kotlinx.coroutines.CancellationException) {
            return DiagnosticInfo(
                failureMode = FailureMode.CANCELLED,
                exceptionType = throwable::class.simpleName,
                exceptionMessage = throwable.message
            )
        }

        val chain = mutableListOf<String>()
        var koogException: KoogHttpClientException? = null
        var networkException: Throwable? = null
        var networkType: String? = null

        var cur: Throwable? = throwable
        var depth = 0
        while (cur != null && depth < 8) {
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
                exceptionType = throwable::class.simpleName,
                exceptionMessage = throwable.message?.lineSequence()?.firstOrNull()?.take(120),
                causeChain = chain
            )
        }

        // 其次用网络异常类型判定
        if (networkException != null) {
            return DiagnosticInfo(
                failureMode = FailureMode.NETWORK_ERROR,
                exceptionType = networkType ?: networkException::class.simpleName,
                exceptionMessage = networkException.message?.lineSequence()?.firstOrNull()?.take(120),
                causeChain = chain
            )
        }

        // KoogHttpClientException 但没有 statusCode（流读取中途异常）
        if (koogException != null) {
            return DiagnosticInfo(
                failureMode = FailureMode.NETWORK_ERROR,
                exceptionType = throwable::class.simpleName,
                exceptionMessage = koogException.message?.lineSequence()?.firstOrNull()?.take(120)
                    ?: throwable.message?.lineSequence()?.firstOrNull()?.take(120),
                causeChain = chain
            )
        }

        return DiagnosticInfo(
            failureMode = FailureMode.UNKNOWN,
            exceptionType = throwable::class.simpleName,
            exceptionMessage = throwable.message?.lineSequence()?.firstOrNull()?.take(120),
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
        is SocketException -> {
            when {
                e.message?.lowercase()?.contains("reset") == true -> "连接被重置"
                e.message?.lowercase()?.contains("closed") == true -> "连接已关闭"
                e.message?.lowercase()?.contains("broken") == true -> "连接断裂"
                else -> "Socket异常"
            }
        }
        else -> e::class.simpleName ?: "网络异常"
    }
}
