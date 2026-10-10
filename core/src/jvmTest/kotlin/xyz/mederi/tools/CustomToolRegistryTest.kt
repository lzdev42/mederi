package xyz.mederi.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable

/**
 * CustomToolRegistry 全局注册表行为锁定：
 * - register → buildAll() 产出工厂实例；同名注册幂等覆盖（只保留最新工厂）；
 * - unregister / clear 后 buildAll() 为空；
 * - isEmpty() 反映注册状态。
 *
 * 注册表是进程级全局 object，每个用例结束后必须 clear()（本类 @BeforeTest/@AfterTest
 * 双保险 + 用例体内显式 clear()），避免污染其他测试。
 */
class CustomToolRegistryTest {

    @BeforeTest
    fun clean() {
        CustomToolRegistry.clear()
    }

    @AfterTest
    fun cleanup() {
        CustomToolRegistry.clear()
    }

    @Test
    fun `register 后 buildAll 包含注册的工厂实例`() {
        val tool = TestStubTool()
        CustomToolRegistry.register(TOOL_NAME) { tool }
        val built = CustomToolRegistry.buildAll()
        assertEquals(1, built.size, "注册一个工厂后 buildAll 应只产出 1 个工具, got: $built")
        assertTrue(built.first() === tool, "buildAll 返回的应是注册工厂产出的实例, got: $built")
        CustomToolRegistry.clear()
    }

    @Test
    fun `unregister 后 buildAll 为空`() {
        val tool = TestStubTool()
        CustomToolRegistry.register(TOOL_NAME) { tool }
        CustomToolRegistry.unregister(TOOL_NAME)
        assertTrue(CustomToolRegistry.buildAll().isEmpty(), "unregister 后 buildAll 应为空")
        CustomToolRegistry.clear()
    }

    @Test
    fun `register 同名幂等覆盖，buildAll 只剩第二次注册的实例`() {
        val first = TestStubTool()
        val second = TestStubTool()
        CustomToolRegistry.register(TOOL_NAME) { first }
        CustomToolRegistry.register(TOOL_NAME) { second }
        val built = CustomToolRegistry.buildAll()
        assertEquals(1, built.size, "同名注册两次后应只有 1 个工具, got: $built")
        assertTrue(built.first() === second, "同名覆盖后应只剩第二次注册工厂的实例, got: $built")
        CustomToolRegistry.clear()
    }

    @Test
    fun `clear 后 buildAll 为空`() {
        val tool = TestStubTool()
        CustomToolRegistry.register(TOOL_NAME) { tool }
        CustomToolRegistry.clear()
        assertTrue(CustomToolRegistry.buildAll().isEmpty(), "clear 后 buildAll 应为空")
        CustomToolRegistry.clear()
    }

    @Test
    fun `isEmpty 反映注册状态`() {
        assertTrue(CustomToolRegistry.isEmpty(), "未注册时 isEmpty 应为 true")
        val tool = TestStubTool()
        CustomToolRegistry.register(TOOL_NAME) { tool }
        assertFalse(CustomToolRegistry.isEmpty(), "注册后 isEmpty 应为 false")
        CustomToolRegistry.unregister(TOOL_NAME)
        assertTrue(CustomToolRegistry.isEmpty(), "unregister 后 isEmpty 应为 true")
        CustomToolRegistry.clear()
    }

    private companion object {
        /** 与 [TestStubTool].name 保持一致；唯一前缀避免与内置工具/其他测试冲突。 */
        const val TOOL_NAME = "custom_registry_test_stub"
    }
}

/** 测试桩参数：最简可序列化参数类型。 */
@Serializable
private data class TestStubArgs(val value: String = "")

/** 最简 SimpleTool 子类：不执行真实逻辑，只用于验证注册表行为。 */
private class TestStubTool : SimpleTool<TestStubArgs>(
    argsType = typeToken<TestStubArgs>(),
    name = "custom_registry_test_stub",
    description = "CustomToolRegistry 单元测试桩，无实际行为"
) {
    override suspend fun execute(args: TestStubArgs): String = "ok:${args.value}"
}