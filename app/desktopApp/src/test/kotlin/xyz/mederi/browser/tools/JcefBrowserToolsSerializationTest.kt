package xyz.mederi.browser.tools

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * 验证 JCEF 浏览器物理操作工具的所有 Args 类的序列化能力：
 * 确保已正确应用 kotlinSerialization 编译器插件，避免运行时报
 * "Serializer for class 'XxxArgs' is not found" 异常。
 */
class JcefBrowserToolsSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `NavigateArgs 序列化器存在且可反序列化`() {
        assertNotNull(serializer<NavigateArgs>())
        val args = json.decodeFromString<NavigateArgs>("""{"url":"https://example.com"}""")
        assertEquals("https://example.com", args.url)
    }

    @Test
    fun `ClickArgs 序列化器存在且可反序列化`() {
        assertNotNull(serializer<ClickArgs>())
        val args = json.decodeFromString<ClickArgs>("""{"elementRef":"e12"}""")
        assertEquals("e12", args.elementRef)
    }

    @Test
    fun `TypeArgs 序列化器存在且可反序列化`() {
        assertNotNull(serializer<TypeArgs>())
        val args = json.decodeFromString<TypeArgs>("""{"elementRef":"e12","text":"test input"}""")
        assertEquals("e12", args.elementRef)
        assertEquals("test input", args.text)
    }

    @Test
    fun `ScrollArgs 序列化器存在且可反序列化`() {
        assertNotNull(serializer<ScrollArgs>())
        val args = json.decodeFromString<ScrollArgs>("""{"elementRef":"e12","deltaX":0,"deltaY":300}""")
        assertEquals("e12", args.elementRef)
        assertEquals(0, args.deltaX)
        assertEquals(300, args.deltaY)
    }

    @Test
    fun `WaitForArgs 序列化器存在且可反序列化`() {
        assertNotNull(serializer<WaitForArgs>())
        val args = json.decodeFromString<WaitForArgs>("""{"selector":"#submit-btn"}""")
        assertEquals("#submit-btn", args.selector)
    }

    @Test
    fun `TabsArgs 序列化器存在且可反序列化`() {
        assertNotNull(serializer<TabsArgs>())
        val args = json.decodeFromString<TabsArgs>("""{"action":"NEW","tabId":"t1"}""")
        assertEquals("NEW", args.action)
        assertEquals("t1", args.tabId)
    }

}

