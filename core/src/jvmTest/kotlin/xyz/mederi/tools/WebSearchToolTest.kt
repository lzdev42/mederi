package xyz.mederi.tools

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定 web 工具（web_search / web_fetch）的名册注册与输入校验：
 * - WEB_TOOL_NAMES 恰为 ["web_search", "web_fetch"] 且都在 ALL_TOOL_NAMES（否则会被 TurnExecutor 的名字白名单静默裁剪）
 * - 空 query / 非 http(s) URL / 空 URL 拒绝；引号通过HTTP原样/URL编码传递，不经过shell。
 */
class WebSearchToolTest {

    @Test
    fun `web 工具在 WEB_TOOL_NAMES 与 ALL_TOOL_NAMES 中`() {
        assertEquals(
            listOf(ToolName.WEB_SEARCH, ToolName.WEB_FETCH),
            ToolFactory.WEB_TOOL_NAMES
        )
        assertTrue("web_search must be in ALL_TOOL_NAMES", ToolName.WEB_SEARCH in ToolFactory.ALL_TOOL_NAMES)
        assertTrue("web_fetch must be in ALL_TOOL_NAMES", ToolName.WEB_FETCH in ToolFactory.ALL_TOOL_NAMES)
    }

    @Test
    fun `WebSearchTool 拒绝空 query`() = runBlocking {
        val tool = ShellTools(listOf("/tmp")).WebSearchTool()
        val result = tool.execute(ShellTools.WebSearchArgs(query = ""))
        assertTrue("expected an Error for empty query, got: $result", result.contains("Error"))
    }

    @Test
    fun `WebSearchTool safely encodes quotes without invoking a shell`() = runBlocking {
        var requested = ""
        val tool = ShellTools(listOf("/tmp"), webReader = { url, _ -> requested = url; "<html>safe search</html>" }).WebSearchTool()
        val result = tool.execute(ShellTools.WebSearchArgs(query = "kotlin'"))
        assertTrue(requested.contains("kotlin%27"))
        assertTrue(result.contains("safe search"))
    }

    @Test
    fun `WebFetchTool 拒绝非 http URL`() = runBlocking {
        val tool = ShellTools(listOf("/tmp")).WebFetchTool()
        val result = tool.execute(ShellTools.WebFetchArgs(url = "ftp://example.com"))
        assertTrue("expected an Error for non-http url, got: $result", result.contains("Error"))
    }

    @Test
    fun `WebFetchTool passes quoted paths directly to HTTP`() = runBlocking {
        var requested = ""
        val tool = ShellTools(listOf("/tmp"), webReader = { url, _ -> requested = url; "<html>safe fetch</html>" }).WebFetchTool()
        val result = tool.execute(ShellTools.WebFetchArgs(url = "https://example.com/a'b"))
        assertEquals("https://example.com/a'b", requested)
        assertTrue(result.contains("safe fetch"))
    }

    @Test
    fun `WebFetchTool 拒绝空 URL`() = runBlocking {
        val tool = ShellTools(listOf("/tmp")).WebFetchTool()
        val result = tool.execute(ShellTools.WebFetchArgs(url = ""))
        assertTrue("expected an Error for empty url, got: $result", result.contains("Error"))
    }
}

// 2026-10: web_search/web_fetch 拆分后重写测试
