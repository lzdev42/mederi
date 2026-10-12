package xyz.mederi.tools

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定 web 工具（web_search / web_fetch）的名册注册与输入校验：
 * - WEB_TOOL_NAMES 恰为 ["web_search", "web_fetch"] 且都在 ALL_TOOL_NAMES（否则会被 TurnExecutor 的名字白名单静默裁剪）
 * - 输入校验在 runCommand 之前执行——空 query / 含单引号 query、非 http(s) URL / 含单引号 URL / 空 URL
 *   一律拒绝，不走 curl、不需要网络
 */
class WebSearchToolTest {

    @Test
    fun `web 工具在 WEB_TOOL_NAMES 与 ALL_TOOL_NAMES 中`() {
        assertEquals(
            listOf("web_search", "web_fetch"),
            ToolFactory.WEB_TOOL_NAMES
        )
        assertTrue("web_search must be in ALL_TOOL_NAMES", "web_search" in ToolFactory.ALL_TOOL_NAMES)
        assertTrue("web_fetch must be in ALL_TOOL_NAMES", "web_fetch" in ToolFactory.ALL_TOOL_NAMES)
    }

    @Test
    fun `WebSearchTool 拒绝空 query`() = runBlocking {
        val tool = ShellTools(listOf("/tmp")).WebSearchTool()
        val result = tool.execute(ShellTools.WebSearchArgs(query = ""))
        assertTrue("expected an Error for empty query, got: $result", result.contains("Error"))
    }

    @Test
    fun `WebSearchTool 拒绝含单引号的 query`() = runBlocking {
        val tool = ShellTools(listOf("/tmp")).WebSearchTool()
        val result = tool.execute(ShellTools.WebSearchArgs(query = "kotlin'"))
        assertTrue("expected an Error for single-quote query, got: $result", result.contains("Error"))
    }

    @Test
    fun `WebFetchTool 拒绝非 http URL`() = runBlocking {
        val tool = ShellTools(listOf("/tmp")).WebFetchTool()
        val result = tool.execute(ShellTools.WebFetchArgs(url = "ftp://example.com"))
        assertTrue("expected an Error for non-http url, got: $result", result.contains("Error"))
    }

    @Test
    fun `WebFetchTool 拒绝含单引号的 URL`() = runBlocking {
        val tool = ShellTools(listOf("/tmp")).WebFetchTool()
        val result = tool.execute(ShellTools.WebFetchArgs(url = "https://example.com'"))
        assertTrue("expected an Error for single-quote url, got: $result", result.contains("Error"))
    }

    @Test
    fun `WebFetchTool 拒绝空 URL`() = runBlocking {
        val tool = ShellTools(listOf("/tmp")).WebFetchTool()
        val result = tool.execute(ShellTools.WebFetchArgs(url = ""))
        assertTrue("expected an Error for empty url, got: $result", result.contains("Error"))
    }
}

// 2026-10: web_search/web_fetch 拆分后重写测试
