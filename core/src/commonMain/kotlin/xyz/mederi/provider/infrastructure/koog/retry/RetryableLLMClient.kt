package xyz.mederi.provider.infrastructure.koog.retry

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import xyz.mederi.debug.DebugLog
import xyz.mederi.debug.ErrorCollector
import xyz.mederi.debug.StreamTrace
import xyz.mederi.domain.model.EventType
import xyz.mederi.domain.model.MederiEvent
import java.time.Instant
import kotlin.random.Random

/**
 * 限流/临时故障重试包装器（LLMClient 装饰器）。
 *
 * 职责边界（刻意收窄）：
 * - 只拦 LLM 请求入口（execute / executeStreaming / executeMultipleChoices），逐次请求级别重试
 * - 只重试"环境态"临时故障：限流（429 / rpm / rate limit / quota_exceeded）与网关过载类。
 *   参数错误、鉴权失败、内容拒绝等确定性失败立即上抛，不浪费退避
 * - 重试期间经 [statusBus] 发 [EventType.STATUS] 事件（session 作用域），UI 状态栏据此显示
 *   "重试中"；不触碰 session 状态机、不落库、不污染业务流——对 TurnExecutor/Koog agent
 *   完全透明，它们看到的只是一次变慢的请求
 * - 服务端retry-after-ms/Retry-After/Google RetryInfo为下限，加0~1秒抖动；缺失/无效才用随机区间
 * - 临时故障无限重试，直到成功或协程取消；每次请求独立计数，恢复首帧即清除重试提示
 * - 重试设置（开关/随机区间）每次重试决策时经 [settingsProvider] 实时读取唯一真理源——
 *   turn 运行中用户改供应商设置立即生效，不等下个 turn 重建 client
 *
 * 不变量：CancellationException 永不重试（用户中止必须即时生效）。
 * settingsProvider 读到 enabled=false 时临时故障立即上抛（含服务端建议也忽略）。
 *
 * 流式重试安全性：首帧尚未发出前失败 = 本次请求整体作废，可安全重来；
 * 首帧已发出后失败 = 流已被消费一半，重试会造成重复内容，只能上抛。
 *
 * @property delegate 被包装的真实客户端
 * @property settingsProvider 重试设置提供器（suspend，每次重试决策时调用）：
 *   enabled=false = 临时故障立即上抛（含服务端建议也忽略）——供应商三态 0 走这里。
 *   null = 全局默认（无限重试，LlmRetryConfig 区间，同样按次实时读取）
 * @property statusBus 状态事件总线；null = 静默重试
 * @property sessionIdProvider 当前 turn 的 session id 提供器（STATUS 事件路由用）
 * @property random 随机源（测试注入）
 */
class RetryableLLMClient(
    private val delegate: LLMClient,
    private val settingsProvider: (suspend () -> ProviderRetrySettings)? = null,
    private val statusBus: MutableSharedFlow<MederiEvent>? = null,
    private val sessionIdProvider: () -> String? = { null },
    private val random: Random = Random.Default,
    /**
     * 重试时换 key 的 client 提供器。首次请求用 [delegate]，重试时（attempt > 0）调此函数取新 client。
     * null = 不换 key，所有重试都用原始 delegate（原有行为）。
     * 用于 API Key 轮询：重试换 key 才最准确——首次请求不换，失败重试时才换下一个 key。
     */
    private val retryDelegateProvider: (() -> LLMClient?)? = null
) : LLMClient() {

    /** 暴露内部 delegate——TurnExecutor 读取 [MederiOpenAILLMClient.lastStreamDiagnostics] 用 */
    fun delegate(): LLMClient = delegate

    override val clientName: String =
        if (retryDelegateProvider != null) "Retryable+Rotate(${delegate.clientName})"
        else "Retryable(${delegate.clientName})"

    override fun llmProvider() = delegate.llmProvider()

    override fun close() = delegate.close()

    /**
     * 按 attempt 号取 client：首次（attempt=0）用原始 delegate，重试时用 [retryDelegateProvider] 取下一个。
     */
    private fun clientForAttempt(attempt: Long): LLMClient =
        if (attempt == 0L) delegate
        else retryDelegateProvider?.invoke() ?: delegate

    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant =
        retrying("execute") { attempt -> clientForAttempt(attempt).execute(prompt, model, tools) }

    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
        flow {
            var attempt = 0L
            var retryVisible = false
            try {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    var emitted = false
                    try {
                        clientForAttempt(attempt).executeStreaming(prompt, model, tools).collect { frame ->
                            emitted = true
                            if (retryVisible) {
                                emitStatus("RETRY_FINISHED")
                                retryVisible = false
                            }
                            emit(frame)
                        }
                        return@flow
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        // 本地空闲超时交给 turn 追加 Continue；首帧前也不能在这里重放旧请求。
                        // 其他首帧后失败不可重发，避免重复内容。
                        val settings = currentSettings()
                        if (emitted || !settings.enabled || isSseIdleTimeout(e) || !isTransient(e)) throw e
                        attempt++
                        val delayMs = retryDelayMs(e, settings)
                        StreamTrace.record("Stream.Summary", mapOf(
                            "event" to "stream-retry", "attempt" to attempt.toString(), "note" to firstLine(e),
                        ))
                        DebugLog.event("Retry", "executeStreaming retry $attempt in ${delayMs}ms: ${firstLine(e)}")
                        retryVisible = true
                        emitStatus("RETRYING", firstLine(e), attempt, delayMs)
                        delay(delayMs)
                    }
                }
            } finally {
                // tryEmit 不挂起，取消也能清理；空流成功和不可重试错误同样收起提示。
                if (retryVisible) emitStatus("RETRY_FINISHED")
            }
        }

    override suspend fun executeMultipleChoices(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): LLMChoice =
        retrying("executeMultipleChoices") { attempt -> clientForAttempt(attempt).executeMultipleChoices(prompt, model, tools) }

    override suspend fun moderate(prompt: Prompt, model: LLModel) = delegate.moderate(prompt, model)

    override suspend fun models(): List<LLModel> = delegate.models()

    override fun getStandardJsonSchemaGenerator() = delegate.getStandardJsonSchemaGenerator()

    override fun getBasicJsonSchemaGenerator() = delegate.getBasicJsonSchemaGenerator()

    // ------------------------------------------------------------------
    // 重试内核（非流式路径）
    // ------------------------------------------------------------------

    /**
     * 按重试决策取当前生效设置：优先 [settingsProvider]（唯一真理源实时读），
     * 无提供器时用全局默认（LlmRetryConfig 同样按次读取，环境变量改动同样生效）。
     */
    private suspend fun currentSettings(): ProviderRetrySettings =
        settingsProvider?.invoke()
            ?: ProviderRetrySettings(
                enabled = true,
                minDelayMs = LlmRetryConfig.minDelayMs,
                maxDelayMs = LlmRetryConfig.maxDelayMs
            )

    private suspend fun <T> retrying(op: String, block: suspend (attempt: Long) -> T): T {
        var attempt = 0L
        try {
            while (true) {
                // delay(0) 不保证挂起，仍须每轮主动检查停止信号。
                currentCoroutineContext().ensureActive()
                try {
                    return block(attempt)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    val settings = currentSettings()
                    if (!settings.enabled || !isTransient(e)) throw e
                    attempt++
                    val delayMs = retryDelayMs(e, settings)
                    DebugLog.event("Retry", "$op retry $attempt in ${delayMs}ms: ${firstLine(e)}")
                    emitStatus("RETRYING", firstLine(e), attempt, delayMs)
                    delay(delayMs)
                }
            }
        } finally {
            if (attempt > 0) emitStatus("RETRY_FINISHED")
        }
    }

    private fun emitStatus(code: String, message: String? = null, attempt: Long? = null, delayMs: Long? = null) {
        val bus = statusBus ?: return
        val sid = sessionIdProvider() ?: return
        bus.tryEmit(
            MederiEvent(
                type = EventType.STATUS,
                sessionId = sid,
                payload = buildMap {
                    put("scope", "provider")
                    put("code", code)
                    message?.let { put("message", it) }
                    attempt?.let { put("attempt", it.toString()) }
                    delayMs?.let { put("delayMs", it.toString()) }
                },
                timestamp = Instant.now().toString()
            )
        )
    }

    // ------------------------------------------------------------------
    // 临时故障识别
    // ------------------------------------------------------------------

    /**
     * 异常链上找环境态临时故障特征。限流类错误 message 里带 429 / "rpm exhausted" /
     * "quota_exceeded_error" 等标记；网关过载类带 502/503/overloaded。
     * 沿 cause 链向上找（Koog 会包成 LLMClientException，原始 429 文本在 cause 里）。
     */
    private fun isTransient(e: Throwable): Boolean = isTransientError(e)

    private fun retryDelayMs(error: Throwable, settings: ProviderRetrySettings): Long {
        val advice = ProviderRetryPolicy.inspect(error)
        val serverDelay = advice.delayMs ?: return random.nextLong(settings.minDelayMs, settings.maxDelayMs + 1)
        val jitter = random.nextLong(0, 1001)
        val result = if (serverDelay > Long.MAX_VALUE - jitter) Long.MAX_VALUE else serverDelay + jitter
        DebugLog.event("Retry", "server delay source=${advice.source}, minimum=${serverDelay}ms, jitter=${jitter}ms")
        return result
    }

    /**
     * 重试提示用的错误摘要：优先取 errorBody 里解析出的供应商真实报错（如
     * "Insufficient Balance"/"Engine overloaded"），否则回退异常 message 首行。
     * 直接决定 StatusBar 重试态第二行 + StreamTrace 留痕的文本质量。
     */
    private fun firstLine(e: Throwable): String =
        ErrorCollector.extractServerMessage(e)
            ?: ErrorCollector.extractDiagnostics(e).takeIf {
                it.failureMode == ErrorCollector.FailureMode.NETWORK_ERROR ||
                    it.failureMode == ErrorCollector.FailureMode.PREMATURE_CLOSE
            }?.summary()
            ?: (e.message ?: e.javaClass.simpleName).lineSequence().firstOrNull()?.take(120)
                ?: e.javaClass.simpleName

    companion object {
        /** 本地停止等待策略，与供应商限流、网络断开等错误分别选择恢复方式。 */
        internal fun isSseIdleTimeout(error: Throwable): Boolean =
            generateSequence(error) { it.cause }.take(8).any { it is xyz.mederi.http.SseIdleTimeoutException }

        /**
         * 判断异常是否为环境态临时故障（限流/网关过载/网络中断）。
         * 供 TurnExecutor 对首帧后等不可继续重试的错误分类用途：限流/网络抖动型失败 session 保持 IDLE（环境态，
         * 用户稍后重发即可），不把会话标成 ERROR。
         */
        fun isTransientError(e: Throwable): Boolean {
            ProviderRetryPolicy.inspect(e).retryable?.let { return it }
            var cur: Throwable? = e
            var depth = 0
            while (cur != null && depth < 6) {
                if (cur is xyz.mederi.http.SseIdleTimeoutException || cur::class.simpleName == "SseIdleTimeoutException") return true
                // HTTP 读取可能以 EOF/Channel 关闭结束；Koog 外层只保留通用客户端文案。
                // 沿 cause 识别具体传输异常，不能把所有无状态的客户端异常都当作可重试。
                if (cur is java.io.EOFException || cur is java.nio.channels.ClosedChannelException) return true
                if (cur is java.net.SocketTimeoutException) return true
                if (ErrorCollector.isNetworkException(cur)) return true
                val lower = ((cur.message ?: "") + " " + cur.javaClass.simpleName).lowercase()
                if (TRANSIENT_MARKERS.any { lower.contains(it) }) return true
                cur = cur.cause
                depth++
            }
            return false
        }

        /** 环境态临时故障特征（小写匹配异常链 message + 异常类名） */
        val TRANSIENT_MARKERS = listOf(
            "429", "rpm exhausted", "tpm exhausted", "rate limit", "ratelimit",
            "quota_exceeded", "too many requests", "overloaded", "overload",
            "temporarily unavailable", "service unavailable",
            "502", "503", "504", "bad gateway", "gateway timeout",
            "sse idle timeout", "idle timeout", "sockettimeoutexception",
            "stream reset", "streamreset", "h2streamreset",
            "connection reset", "broken pipe"
        )
    }
}
