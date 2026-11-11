package xyz.mederi.provider.infrastructure.koog.retry

import java.io.File
import org.junit.Test
import kotlin.test.assertEquals

class ProviderRetrySettingsTest {

    /** 验收观测目录（collector 协议：测试写真实观测值，验证命令 rm 后强制重跑再比对） */
    private val observationDir = File("build/retry-behavior")

    private fun observe(name: String, value: String) {
        observationDir.mkdirs()
        File(observationDir, name).writeText(value)
    }

    @Test
    fun `resolve provider retry settings four states`() {
        // 全局边界约定传 0 / 30000（与 LlmRetryConfig 默认一致，显式传参隔离默认值改动）
        val states = listOf(
            "off5" to resolveProviderRetrySettings(autoRetryEnabled = false, retryMaxDelaySeconds = 5, globalMinDelayMs = 0, globalMaxDelayMs = 30_000),
            "null" to resolveProviderRetrySettings(autoRetryEnabled = true, retryMaxDelaySeconds = null, globalMinDelayMs = 0, globalMaxDelayMs = 30_000),
            "0" to resolveProviderRetrySettings(autoRetryEnabled = true, retryMaxDelaySeconds = 0, globalMinDelayMs = 0, globalMaxDelayMs = 30_000),
            "5" to resolveProviderRetrySettings(autoRetryEnabled = true, retryMaxDelaySeconds = 5, globalMinDelayMs = 0, globalMaxDelayMs = 30_000),
        )

        assertEquals(ProviderRetrySettings(enabled = false, minDelayMs = 0, maxDelayMs = 30_000), states[0].second)
        assertEquals(ProviderRetrySettings(enabled = true, minDelayMs = 0, maxDelayMs = 30_000), states[1].second)
        assertEquals(ProviderRetrySettings(enabled = true, minDelayMs = 0, maxDelayMs = 0), states[2].second)
        assertEquals(ProviderRetrySettings(enabled = true, minDelayMs = 1000, maxDelayMs = 5000), states[3].second)

        // 验收观测值（实测值非期望值；断言在前，断言失败则不写文件=not-run=FAIL）
        val line = states.joinToString(" ") { (input, s) -> "$input=(${s.enabled},${s.minDelayMs},${s.maxDelayMs})" }
        observe("resolver.txt", line)
    }
}
