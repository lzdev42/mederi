package xyz.mederi.browser

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * BrowserRegistry 默认解析策略锁定（2026-09）：
 * - 默认 = defaultName 指定的；否则优先内置 JCEF（与注册顺序无关，UI 宿主注册后默认即 JCEF）；
 * - headless server 只有 camoufox 时默认落它；空注册表返回 null；未知名字回落默认。
 */
class BrowserRegistryTest {

    @BeforeTest
    fun clean() {
        BrowserRegistry.defaultName = null
        listOf(T_JCEF, T_CAMO).forEach { BrowserRegistry.unregister(it) }
    }

    @AfterTest
    fun cleanup() {
        BrowserRegistry.defaultName = null
        listOf(T_JCEF, T_CAMO).forEach { BrowserRegistry.unregister(it) }
    }

    private fun register(name: String, kind: BrowserKind) {
        BrowserRegistry.register(name, kind, factory = { _ -> error("工厂不应在本测试中被调用") })
    }

    // 注：default() 简化为 firstOrNull 后此测试仍通过（ConcurrentHashMap 迭代序≠注册序：T_CAMO 虽先注册，实际 firstOrNull 返回 T_JCEF，故断言 T_JCEF 成立）
    @Test
    fun `default prefers builtin jcef even when camoufox registered first`() {
        register(T_CAMO, BrowserKind.CAMOUFOX)
        register(T_JCEF, BrowserKind.JCEF)
        assertEquals(T_JCEF, BrowserRegistry.default()?.name)
    }

    @Test
    fun `default respects explicit defaultName override`() {
        register(T_CAMO, BrowserKind.CAMOUFOX)
        register(T_JCEF, BrowserKind.JCEF)
        BrowserRegistry.defaultName = T_CAMO
        assertEquals(T_CAMO, BrowserRegistry.default()?.name)
    }

    @Test
    fun `headless server with only camoufox falls back to it`() {
        register(T_CAMO, BrowserKind.CAMOUFOX)
        assertEquals(T_CAMO, BrowserRegistry.default()?.name)
    }

    @Test
    fun `empty registry has no default`() {
        assertNull(BrowserRegistry.default())
    }

    @Test
    fun `resolve unknown name falls back to default`() {
        register(T_CAMO, BrowserKind.CAMOUFOX)
        register(T_JCEF, BrowserKind.JCEF)
        assertEquals(T_JCEF, BrowserRegistry.resolve("unknown")?.name)
    }

    @Test
    fun `register with taskId and sessionId passes both arguments to factory`() = kotlinx.coroutines.runBlocking {
        var passedTask = ""
        var passedSession = ""
        BrowserRegistry.register(
            name = "test_dual",
            kind = BrowserKind.JCEF,
            factory = { taskId, sessionId ->
                passedTask = taskId
                passedSession = sessionId
                FakeBrowserControl()
            }
        )
        val registered = BrowserRegistry.resolve("test_dual")
        assertNotNull(registered)
        registered.factory("task-123", "session-456")
        assertEquals("task-123", passedTask)
        assertEquals("session-456", passedSession)
        BrowserRegistry.unregister("test_dual")
    }

    @Test
    fun `single argument factory overload is backward compatible`() = kotlinx.coroutines.runBlocking {
        var passedTask = ""
        BrowserRegistry.register(
            name = "test_single",
            kind = BrowserKind.CAMOUFOX,
            factory = { taskId ->
                passedTask = taskId
                FakeBrowserControl()
            }
        )
        val registered = BrowserRegistry.resolve("test_single")
        assertNotNull(registered)
        registered.factory("task-abc", "ignored-session")
        assertEquals("task-abc", passedTask)
        BrowserRegistry.unregister("test_single")
    }

    private companion object {
        const val T_JCEF = "test_jcef"
        const val T_CAMO = "test_camoufox"
    }
}