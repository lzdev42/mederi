package xyz.mederi.provider.infrastructure.koog.retry

/**
 * LLM 请求限流重试配置（进程级，SandboxConfig 同款模式：UI 写穿、下个请求即生效）。
 *
 * 只管"环境态临时故障"的自动重试：限流（429/rpm/quota）、网关过载（502/503）。
 * 确定性失败（鉴权、参数、内容拒绝）不重试，立即上抛。
 *
 * 重试行为本身在 [RetryableLLMClient]；此处只存用户可调的数值。
 * core 业务代码对此无感知——对它们来说重试就是"这次请求慢了点"。
 */
object LlmRetryConfig {
    /** 最大重试次数（不含首次请求）。默认 10。设 0 = 关闭重试 */
    @Volatile
    var maxRetries: Int = 100

    /** 随机退避下限（毫秒），默认 1 秒 */
    @Volatile
    var minDelayMs: Long = 1_000

    /** 随机退避上限（毫秒），默认 10 秒 */
    @Volatile
    var maxDelayMs: Long = 10_000
}
