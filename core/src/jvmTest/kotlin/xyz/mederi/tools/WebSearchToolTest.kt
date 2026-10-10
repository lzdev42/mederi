package xyz.mederi.tools

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定 web_search 工具的名册注册与 URL 校验：
 * - RESEARCHER_TOOL_NAMES 恰为 ["web_search"] 且它在 ALL_TOOL_NAMES（否则会被 TurnExecutor 的名字白名单静默裁剪）
 * - URL 校验在 runCommand 之前执行——非 http/https、含单引号、空 URL 一律拒绝，不走 curl、不需要网络
 */
class WebSearchToolTest {

    @Test
    fun `web_search 在 RESEARCHER_TOOL_NAMES 与 ALL_TOOL_NAMES 中`() {
        assertEquals(
            listOf("web_search"),
            ToolFactory.RESEARCHER_TOOL_NAMES
        )
        assertTrue("web_search must be in ALL_TOOL_NAMES", "web_search" in ToolFactory.ALL_TOOL_NAMES)
    }

    @Test
    fun `WebSearchTool 拒绝非 http URL`() = runBlocking {
        val tool = ShellTools(listOf("/tmp")).WebSearchTool()
        val result = tool.execute(ShellTools.WebSearchArgs(url = "ftp://example.com"))
        assertTrue("expected an Error for non-http url, got: $result", result.contains("Error"))
    }

    @Test
    fun `WebSearchTool 拒绝含单引号的 URL`() = runBlocking {
        val tool = ShellTools(listOf("/tmp")).WebSearchTool()
        val result = tool.execute(ShellTools.WebSearchArgs(url = "https://example.com'"))
        assertTrue("expected an Error for single-quote url, got: $result", result.contains("Error"))
    }

    @Test
    fun `WebSearchTool 拒绝空 URL`() = runBlocking {
        val tool = ShellTools(listOf("/tmp")).WebSearchTool()
        val result = tool.execute(ShellTools.WebSearchArgs(url = ""))
        assertTrue("expected an Error for empty url, got: $result", result.contains("Error"))
    }
}