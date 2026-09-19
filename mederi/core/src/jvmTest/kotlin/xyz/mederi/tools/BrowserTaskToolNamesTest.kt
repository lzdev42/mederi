package xyz.mederi.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定"浏览器工具被名字白名单裁剪"的回归：
 * TurnExecutor 构建主代理工具时传 `ToolFactory.ALL_TOOL_NAMES` 作为显式过滤，
 * 工具即使注册进了 browserTaskToolMap，只要没进 BROWSER_TASK_TOOL_NAMES 就会被静默丢弃。
 * （真实事故：browser_info 只进了 map、没进名单，AI 一直说没有该工具。）
 *
 * 2026-09 合并后：四件套封装为单一 `browser`（action 分流）——
 * 名单不变量 = BROWSER_TASK_TOOL_NAMES 恰为 ["browser"] 且它在 ALL_TOOL_NAMES。
 */
class BrowserTaskToolNamesTest {

    @Test
    fun `browser 合并入口在名单中且 ALL_TOOL_NAMES 包含它`() {
        assertEquals(
            listOf("browser"),
            ToolFactory.BROWSER_TASK_TOOL_NAMES
        )
        assertTrue("browser must be in ALL_TOOL_NAMES", "browser" in ToolFactory.ALL_TOOL_NAMES)
        // 旧名不得残留（残留 = 静默丢弃的幽灵注册）
        listOf("run_browser_task", "browser_task_status", "stop_browser_task", "browser_info").forEach {
            assertTrue("$it must NOT be in ALL_TOOL_NAMES after the merge", it !in ToolFactory.ALL_TOOL_NAMES)
        }
    }
}
