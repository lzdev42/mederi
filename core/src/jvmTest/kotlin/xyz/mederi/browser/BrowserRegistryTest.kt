package xyz.mederi.browser

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BrowserRegistryTest {

    @BeforeTest
    fun clean() {
        BrowserRegistry.defaultName = null
        listOf(T_JCEF, T_CUSTOM).forEach { BrowserRegistry.unregister(it) }
    }

    @AfterTest
    fun cleanup() {
        BrowserRegistry.defaultName = null
        listOf(T_JCEF, T_CUSTOM).forEach { BrowserRegistry.unregister(it) }
    }

    private fun register(name: String, kind: BrowserKind) {
        BrowserRegistry.register(name, kind, factory = { _ -> error("工厂不应在本测试中被调用") })
    }

    @Test
    fun `default respects explicit defaultName override`() {
        register(T_CUSTOM, BrowserKind.CUSTOM)
        register(T_JCEF, BrowserKind.JCEF)
        BrowserRegistry.defaultName = T_CUSTOM
        assertEquals(T_CUSTOM, BrowserRegistry.default()?.name)
    }

    @Test
    fun `default selects the only registered custom browser`() {
        register(T_CUSTOM, BrowserKind.CUSTOM)
        assertEquals(T_CUSTOM, BrowserRegistry.default()?.name)
    }

    @Test
    fun `empty registry has no default`() {
        assertNull(BrowserRegistry.default())
    }

    @Test
    fun `resolve unknown name falls back to default`() {
        register(T_CUSTOM, BrowserKind.CUSTOM)
        register(T_JCEF, BrowserKind.JCEF)
        BrowserRegistry.defaultName = T_JCEF
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
            kind = BrowserKind.CUSTOM,
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
        const val T_CUSTOM = "test_custom"
    }
}