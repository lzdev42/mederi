package xyz.mederi.provider

import kotlinx.coroutines.runBlocking
import org.junit.Test
import xyz.mederi.provider.domain.model.ProviderType
import xyz.mederi.store.InMemoryApiKeyStore
import xyz.mederi.store.InMemoryProviderStore
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ProviderRetrySettingTest {

    /** 验收观测目录（collector 协议：测试写真实观测值，验证命令 rm 后强制重跑再比对） */
    private val observationDir = File("build/retry-observation")

    private fun observe(name: String, value: String) {
        observationDir.mkdirs()
        File(observationDir, name).writeText(value)
    }

    @Test
    fun `retry settings replace semantics`() = runBlocking {
        val manager = ProviderManagerImpl(InMemoryProviderStore(), InMemoryApiKeyStore())
        val provider = manager.create(
            name = "Retry Provider",
            type = ProviderType.OPENAI_CHAT,
            baseUrl = "https://example.invalid/v1",
            reasoningParameter = null,
            responseSanitization = false
        )
        val id = provider.id

        // 初始默认：autoRetryEnabled=true（自动重试开）、null（全局默认区间）
        assertEquals(true, manager.get(id)!!.autoRetryEnabled)
        assertNull(manager.get(id)!!.retryMaxDelaySeconds)

        // 关闭自动重试 + 设 5：双字段 replace 写入
        manager.updateRetrySettings(id, autoRetryEnabled = false, retryMaxDelaySeconds = 5)
        assertEquals(false, manager.get(id)!!.autoRetryEnabled)
        assertEquals(5, manager.get(id)!!.retryMaxDelaySeconds)

        // 开关恢复开 + 0 = 无延迟立即重试（0 不被吞，显式写入）
        manager.updateRetrySettings(id, autoRetryEnabled = true, retryMaxDelaySeconds = 0)
        val zeroValue = manager.get(id)!!.retryMaxDelaySeconds
        assertEquals(0, zeroValue)
        assertEquals(true, manager.get(id)!!.autoRetryEnabled)

        // null = 清除，恢复全局默认区间（replace 语义，不是"不改动"；开关字段不受影响）
        manager.updateRetrySettings(id, autoRetryEnabled = true, retryMaxDelaySeconds = null)
        val clearedValue = manager.get(id)!!.retryMaxDelaySeconds
        assertNull(clearedValue)

        // 负数拒绝（不改动现值）
        val negative = assertFailsWith<IllegalArgumentException> {
            manager.updateRetrySettings(id, autoRetryEnabled = true, retryMaxDelaySeconds = -1)
        }
        assertNull(manager.get(id)!!.retryMaxDelaySeconds)

        // 验收观测值（实测值非期望值；断言在前，断言失败则不写文件=not-run=FAIL）
        observe("auto-off.txt", "false-then-true")
        observe("set-zero.txt", zeroValue?.toString() ?: "null")
        observe("clear-null.txt", clearedValue?.toString() ?: "null")
        observe("negative.txt", negative::class.simpleName ?: "unknown")
    }
}
