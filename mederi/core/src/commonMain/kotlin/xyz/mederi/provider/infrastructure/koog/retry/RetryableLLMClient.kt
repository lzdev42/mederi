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
 * - 退避：随机 [minDelayMs]~[maxDelayMs]（默认 1~10 秒，避免免费通道全站同步重试）
 * - 次数耗尽后抛最后一次的原始异常，错误语义与不重试时完全一致
 *
 * 不变量：CancellationException 永不重试（用户中止必须即时生效）。
 *
 * 流式重试安全性：首帧尚未发出前失败 = 本次请求整体作废，可安全重来；
 * 首帧已发出后失败 = 流已被消费一半，重试会造成重复内容，只能上抛。
 *
 * @property delegate 被包装的真实客户端
 * @property maxRetries 最大重试次数（不含首次），默认 10
 * @property minDelayMs 随机退避下限，默认 1s
 * @property maxDelayMs 随机退避上限，默认 10s
 * @property statusBus 状态事件总线；null = 静默重试
 * @property sessionIdProvider 当前 turn 的 session id 提供器（STATUS 事件路由用）
 * @property random 随机源（测试注入）
 */
class RetryableLLMClient(
    private val delegate: LLMClient,
    private val maxRetries: Int = 10,
    private val minDelayMs: Long = 1_000,
    private val maxDelayMs: Long = 10_000,
    private val statusBus: MutableSharedFlow<MederiEvent>? = null,
    private val sessionIdProvider: () -> String? = { null },
    private val random: Random = Random.Default
) : LLMClient() {

    /** 暴露内部 delegate——TurnExecutor 读取 [MederiOpenAILLMClient.lastStreamDiagnostics] 用 */
    fun delegate(): LLMClient = delegate

    override val clientName: String = "Retryable(${delegate.clientName})"

    override fun llmProvider() = delegate.llmProvider()

    override fun close() = delegate.close()

    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant =
        retrying("execute") { delegate.execute(prompt, model, tools) }

    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
        flow {
            var lastError: Throwable? = null
            for (attempt in 0..maxRetries) {
                var emitted = false
                try {
                    delegate.executeStreaming(prompt, model, tools).collect { frame ->
                        emitted = true
                        emit(frame)
                    }
                    return@flow
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // 已发出过帧：重试会重复内容，立即上抛（emitted 检查优先于一切）
                    if (emitted || !isTransient(e)) throw e
                    lastError = e
                    if (attempt == maxRetries) break
                    val delayMs = random.nextLong(minDelayMs, maxDelayMs + 1)
                    // 断流重试留痕：StreamTrace 导出时能对上"流在哪里断、断了几次"
                    StreamTrace.record(
                        "Stream.Summary",
                        mapOf(
                            "event" to "stream-retry",
                            "attempt" to "${attempt + 1}/${maxRetries + 1}",
                            "note" to firstLine(e)
                        )
                    )
                    DebugLog.event(
                        "Retry",
                        "executeStreaming transient failure (attempt ${attempt + 1}/${maxRetries + 1}), " +
                            "retrying in ${delayMs}ms: ${firstLine(e)}"
                    )
                    emitStatus("RETRYING", firstLine(e), attempt + 1, maxRetries + 1)
                    delay(delayMs)
                }
            }
            throw lastError ?: IllegalStateException("retry loop exited without result or error")
        }

    override suspend fun executeMultipleChoices(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): LLMChoice =
        retrying("executeMultipleChoices") { delegate.executeMultipleChoices(prompt, model, tools) }

    override suspend fun moderate(prompt: Prompt, model: LLModel) = delegate.moderate(prompt, model)

    override suspend fun models(): List<LLModel> = delegate.models()

    override fun getStandardJsonSchemaGenerator() = delegate.getStandardJsonSchemaGenerator()

    override fun getBasicJsonSchemaGenerator() = delegate.getBasicJsonSchemaGenerator()

    // ------------------------------------------------------------------
    // 重试内核（非流式路径）
    // ------------------------------------------------------------------

    private suspend fun <T> retrying(op: String, block: suspend () -> T): T {
        var lastError: Throwable? = null
        for (attempt in 0..maxRetries) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (!isTransient(e)) throw e
                lastError = e
                if (attempt == maxRetries) break
                val delayMs = random.nextLong(minDelayMs, maxDelayMs + 1)
                DebugLog.event(
                    "Retry",
                    "$op transient failure (attempt ${attempt + 1}/${maxRetries + 1}), " +
                        "retrying in ${delayMs}ms: ${firstLine(e)}"
                )
                emitStatus("RETRYING", firstLine(e), attempt + 1, maxRetries + 1)
                delay(delayMs)
            }
        }
        throw lastError ?: IllegalStateException("retry loop exited without result or error")
    }

    private fun emitStatus(code: String, message: String, attempt: Int, maxAttempts: Int) {
        val bus = statusBus ?: return
        val sid = sessionIdProvider() ?: return
        bus.tryEmit(
            MederiEvent(
                type = EventType.STATUS,
                sessionId = sid,
                payload = mapOf(
                    "scope" to "provider",
                    "code" to code,
                    "message" to message,
                    "attempt" to attempt.toString(),
                    "maxAttempts" to maxAttempts.toString()
                ),
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
    private fun isTransient(e: Throwable): Boolean {
        var cur: Throwable? = e
        var depth = 0
        while (cur != null && depth < 6) {
            val lower = ((cur.message ?: "") + " " + cur.javaClass.simpleName).lowercase()
            if (TRANSIENT_MARKERS.any { lower.contains(it) }) return true
            cur = cur.cause
            depth++
        }
        return false
    }

    /**
     * 重试提示用的错误摘要：优先取 errorBody 里解析出的供应商真实报错（如
     * "Insufficient Balance"/"Engine overloaded"），否则回退异常 message 首行。
     * 直接决定 StatusBar 重试态第二行 + StreamTrace 留痕的文本质量。
     */
    private fun firstLine(e: Throwable): String =
        ErrorCollector.extractServerMessage(e)
            ?: (e.message ?: e.javaClass.simpleName).lineSequence().firstOrNull()?.take(120)
                ?: e.javaClass.simpleName

    companion object {
        /**
         * 判断异常是否为环境态临时故障（限流/网关过载）。
         * 供 TurnExecutor 在重试耗尽后分类错误用途：限流型失败 session 保持 IDLE（环境态，
         * 用户稍后重发即可），不把会话标成 ERROR。
         */
        fun isTransientError(e: Throwable): Boolean {
            var cur: Throwable? = e
            var depth = 0
            while (cur != null && depth < 6) {
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
            "502", "503", "504", "bad gateway", "gateway timeout"
        )
    }
}
