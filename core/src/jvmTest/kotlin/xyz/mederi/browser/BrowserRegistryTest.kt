package xyz.mederi.browser

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private companion object {
        const val T_JCEF = "test_jcef"
        const val T_CAMO = "test_camoufox"
    }
}