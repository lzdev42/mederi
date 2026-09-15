package xyz.mederi.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定"浏览器工具被名字白名单裁剪"的回归：
 * TurnExecutor 构建主代理工具时传 `ToolFactory.ALL_TOOL_NAMES` 作为显式过滤，
 * 工具即使注册进了 browserTaskToolMap，只要没进 BROWSER_TASK_TOOL_NAMES 就会被静默丢弃。
 * （真实事故：browser_info 只进了 map、没进名单，AI 一直说没有该工具。）
 */
class BrowserTaskToolNamesTest {

    @Test
    fun `browser 工具四件套全在某名单，且 browser_info 在 ALL_TOOL_NAMES`() {
        assertEquals(
            setOf("run_browser_task", "browser_task_status", "stop_browser_task", "browser_info"),
            ToolFactory.BROWSER_TASK_TOOL_NAMES.toSet()
        )
        assertTrue("browser_info must be in ALL_TOOL_NAMES", "browser_info" in ToolFactory.ALL_TOOL_NAMES)
        assertTrue("run_browser_task must be in ALL_TOOL_NAMES", "run_browser_task" in ToolFactory.ALL_TOOL_NAMES)
        assertTrue("browser_task_status must be in ALL_TOOL_NAMES", "browser_task_status" in ToolFactory.ALL_TOOL_NAMES)
        assertTrue("stop_browser_task must be in ALL_TOOL_NAMES", "stop_browser_task" in ToolFactory.ALL_TOOL_NAMES)
    }
}