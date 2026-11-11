package xyz.mederi.browser

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import java.io.File
import kotlin.test.Test
import xyz.mederi.browser.bidi.OperationResult
import xyz.mederi.browser.obscura.ObscuraBrowserControl
import xyz.mederi.browser.obscura.ObscuraMcpSession

/**
 * ObscuraBrowserControl 映射测试（Fake ObscuraMcpSession，ST3）。
 *
 * Fake 记录 (toolName, JsonObject) 调用序列，可编程返回 CallToolResult；
 * snapshot 应答镜像 `core/build/obscura-snapshot-sample.txt` 实测格式：
 * 首行 "URL: <url>"、次行 "Title: <title>"，其后元素行 `  - <role> "<name>" [ref=eN]`。
 */
class ObscuraBrowserControlTest {

    /** 按实测样例格式构造 snapshot 应答（about:blank 页 + 一个 textbox 行）。 */
    private fun snapshotText(url: String = "about:blank", title: String = ""): String =
        "URL: $url\nTitle: $title\n\n  - textbox \"Name\" [ref=e1]\n  - heading \"Hello\" [ref=e2]\n"

    /** 记录调用序列的 Fake 会话；handler 按 (tool,args) 编程返回。 */
    private class FakeSession(
        var handler: (tool: String, args: JsonObject) -> CallToolResult = { _, _ ->
            CallToolResult(content = listOf(TextContent("ok")))
        }
    ) : ObscuraMcpSession {
        data class Call(val tool: String, val args: JsonObject)
        val calls = mutableListOf<Call>()
        var closed = false

        override suspend fun listTools(): List<Tool> = emptyList()
        override suspend fun callTool(name: String, arguments: JsonObject): CallToolResult {
            calls += Call(name, arguments)
            return handler(name, arguments)
        }
        override suspend fun close() { closed = true }

        fun argString(call: Call, key: String): String? = call.args[key]?.jsonPrimitive?.content
        fun argLong(call: Call, key: String): Long? = call.args[key]?.jsonPrimitive?.content?.toLongOrNull()
    }

    /** 全部八键累积后整文件写一次（同探针模式）；static 共享使多 @Test 方法实例间累积。 */
    private fun writeAll() {
        outFile.parentFile?.mkdirs()
        val obj = JsonObject(observations.mapValues { (_, v) -> JsonPrimitive(v) })
        outFile.writeText(json.encodeToString(JsonObject.serializer(), obj))
    }

    private fun record(key: String, value: String) {
        observations[key] = value
        writeAll()
    }

    private companion object {
        val observations = linkedMapOf<String, String>()
        val json = Json { prettyPrint = true }
        val outFile = File("build/obscura-control.json")
    }

    private fun textResult(text: String, isError: Boolean = false) =
        CallToolResult(content = listOf(TextContent(text)), isError = isError)

    @Test
    fun `st3 navigate records browser_navigate url`() = runBlocking {
        val fake = FakeSession()
        val control = ObscuraBrowserControl(fake, sessionLifecycleOwner = false)
        control.start()
        val result = control.navigate("https://example.com")
        assertTrue(result is OperationResult.Success)
        val call = fake.calls.single()
        assertEquals("browser_navigate", call.tool)
        assertEquals("https://example.com", fake.argString(call, "url"))
        record("st3_navigate", "browser_navigate{url=https://example.com}")
        control.close()
    }

    @Test
    fun `st3 metadata uses browser_snapshot max_chars 0`() = runBlocking {
        val fake = FakeSession { tool, _ ->
            if (tool == "browser_snapshot") textResult(snapshotText("https://example.com", "Example")) else textResult("ok")
        }
        val control = ObscuraBrowserControl(fake, sessionLifecycleOwner = false)
        val url = control.getCurrentUrl()
        val title = control.getTitle()
        assertEquals("https://example.com", url)
        assertEquals("Example", title)
        assertEquals(2, fake.calls.size)
        fake.calls.forEach {
            assertEquals("browser_snapshot", it.tool)
            assertEquals(0L, fake.argLong(it, "max_chars"))
        }
        record("st3_metadata", "browser_snapshot{max_chars=0}")
        control.close()
    }

    @Test
    fun `st3 click uses browser_click ref`() = runBlocking {
        val fake = FakeSession()
        val control = ObscuraBrowserControl(fake, sessionLifecycleOwner = false)
        val result = control.click("e5")
        assertTrue(result is OperationResult.Success)
        val call = fake.calls.single()
        assertEquals("browser_click", call.tool)
        assertEquals("e5", fake.argString(call, "ref"))
        record("st3_click", "browser_click{ref=e5}")
        control.close()
    }

    @Test
    fun `st3 screenshot decodes base64 image`() = runBlocking {
        val expected = "ABCD".toByteArray()
        val fake = FakeSession { tool, _ ->
            if (tool == "browser_screenshot") {
                CallToolResult(content = listOf(ImageContent(data = "QUJDRA==", mimeType = "image/png")))
            } else textResult("ok")
        }
        val control = ObscuraBrowserControl(fake, sessionLifecycleOwner = false)
        val bytes = control.screenshot()
        assertNotNull(bytes)
        assertEquals("browser_screenshot", fake.calls.single().tool)
        val decoded = if (bytes != null && bytes.contentEquals(expected)) "match" else "mismatch"
        assertEquals("match", decoded)
        record("st3_screenshot", "browser_screenshot{decoded=$decoded}")
        control.close()
    }

    @Test
    fun `st3 upload throws UnsupportedOperationException`() = runBlocking {
        val fake = FakeSession()
        val control = ObscuraBrowserControl(fake, sessionLifecycleOwner = false)
        val ex = runCatching { control.uploadFile("e1", listOf("/tmp/a.txt")) }.exceptionOrNull()
        assertTrue(ex is UnsupportedOperationException)
        assertTrue(fake.calls.isEmpty())
        record("st3_upload", "UnsupportedOperationException")
        control.close()
    }

    @Test
    fun `st3 clickByCoordinates uses evaluate elementFromPoint`() = runBlocking {
        val fake = FakeSession()
        val control = ObscuraBrowserControl(fake, sessionLifecycleOwner = false)
        val result = control.clickByCoordinates(10, 20)
        assertTrue(result is OperationResult.Success)
        val call = fake.calls.single()
        assertEquals("browser_evaluate", call.tool)
        val script = fake.argString(call, "expression") ?: ""
        assertTrue(script.contains("elementFromPoint"))
        assertTrue(script.contains("10") && script.contains("20"))
        record("st3_coords", "browser_evaluate{elementFromPoint}")
        control.close()
    }

    @Test
    fun `st3 tabs sequence`() = runBlocking {
        val fake = FakeSession { tool, _ ->
            if (tool == "browser_tab_list") textResult("tab-1\ntab-2") else textResult("ok")
        }
        val control = ObscuraBrowserControl(fake, sessionLifecycleOwner = false)
        control.openTab()
        val tabs = control.listTabs()
        assertEquals(listOf("tab-1", "tab-2"), tabs)
        control.selectTab("tab-2")
        control.closeTab("tab-2")
        val tools = fake.calls.map { it.tool }.joinToString(",")
        assertEquals("browser_tab_new,browser_tab_list,browser_tab_switch,browser_tab_close", tools)
        assertEquals("tab-2", fake.argString(fake.calls[2], "tab_id"))
        assertEquals("tab-2", fake.argString(fake.calls[3], "tab_id"))
        record("st3_tabs", tools)
        control.close()
    }

    @Test
    fun `st3 snapshot slim has no rawTree member`() = runBlocking {
        val fields = PageSnapshot::class.java.declaredFields.map { it.name }
        assertTrue("rawTree must be removed, fields=$fields", "rawTree" !in fields)
        assertTrue("url" in fields && "title" in fields && "yaml" in fields)
        record("st3_snapshot_slim", "rawTree-removed")
        writeAll()
            // 附带：snapshot() 按样例格式解析 url/title 且 yaml 为全文
        val fake = FakeSession { tool, _ ->
            if (tool == "browser_snapshot") textResult(snapshotText("https://example.com", "Example")) else textResult("ok")
        }
        val control = ObscuraBrowserControl(fake, sessionLifecycleOwner = false)
        val snap = control.snapshot()
        assertEquals("https://example.com", snap.url)
        assertEquals("Example", snap.title)
        assertTrue(snap.yaml.startsWith("URL: https://example.com"))
        assertEquals(20000L, fake.argLong(fake.calls.single(), "max_chars"))
        control.close()
    }
}
