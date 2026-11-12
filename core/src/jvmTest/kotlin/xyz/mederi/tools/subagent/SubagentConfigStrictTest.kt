package xyz.mederi.tools.subagent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import xyz.mederi.api.exception.MederiValidationException
import xyz.mederi.domain.model.SubagentRole
import xyz.mederi.store.InMemorySettingsStore
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * subagent-config-strict 验收：损坏的子代理配置 JSON 不得静默回退默认配置。
 *
 * 旧行为：get() 用 runCatching{}.getOrDefault(SubagentModelConfig())——配置损坏时
 * 子代理模型/并发悄悄变成系统默认，行为被静默改变且无任何提示。
 * 新行为：与 SandboxManager.loadSettings 一致，抛 [MederiValidationException]
 * （cause = 原始解析异常），提示用户在设置里修复配置 JSON；list() 复用 get()，
 * 任一角色配置损坏时 list 同样如实报错。
 */
class SubagentConfigStrictTest {

    @Test
    fun `unparseable stored config throws MederiValidationException with original cause`() = runBlocking {
        val settingsStore = InMemorySettingsStore()
        settingsStore.set(SubagentConfigManager.settingKey(SubagentRole.EXECUTOR), "{ not valid json")
        val manager = SubagentConfigManager(settingsStore, FakeProviderManager(emptyMap()))

        val e = assertFailsWith<MederiValidationException> {
            manager.get(SubagentRole.EXECUTOR)
        }
        assertTrue(e.message!!.contains("EXECUTOR"), "message must mention the role: ${e.message}")
        assertTrue(e.cause is SerializationException, "cause must be the original parse exception: ${e.cause}")
    }

    @Test
    fun `type-mismatched stored config also throws instead of defaulting`() = runBlocking {
        // JSON 可解析但字段类型错误（modelId 应为字符串，给了数字）——同样属于配置损坏，
        // 不能静默回退默认配置。
        val settingsStore = InMemorySettingsStore()
        settingsStore.set(SubagentConfigManager.settingKey(SubagentRole.RESEARCHER), """{"modelId":123}""")
        val manager = SubagentConfigManager(settingsStore, FakeProviderManager(emptyMap()))

        val e = assertFailsWith<MederiValidationException> {
            manager.get(SubagentRole.RESEARCHER)
        }
        assertTrue(e.cause is SerializationException, "cause must be the original decode exception: ${e.cause}")
    }

    @Test
    fun `list propagates corrupt config error instead of silently defaulting`() = runBlocking {
        val settingsStore = InMemorySettingsStore()
        settingsStore.set(SubagentConfigManager.settingKey(SubagentRole.BROWSER_OPERATOR), """{"reasoningLevel":99}""")
        val manager = SubagentConfigManager(settingsStore, FakeProviderManager(emptyMap()))

        val e = assertFailsWith<MederiValidationException> {
            manager.list()
        }
        assertTrue(e.message!!.contains("BROWSER_OPERATOR"), "message must mention the role: ${e.message}")
    }
}
