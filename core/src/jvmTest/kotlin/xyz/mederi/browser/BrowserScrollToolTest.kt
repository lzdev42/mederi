package xyz.mederi.browser

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class BrowserScrollToolTest {
    @Test
    fun `blank refid scrolls page not element`() = runBlocking {
        val control = FakeBrowserControl()
        val tool = ScrollTool(control)
        val r = tool.execute(ScrollArgs(elementRef = "", deltaX = 0, deltaY = 300))
        assertTrue(r.startsWith("scroll=ok"), "应返回成功: $r")
        assertTrue(control.log.any { it.startsWith("scrollPage") }, "空 refid 必须走页面级 scrollPage，不得进入元素级 scroll")
    }

    @Test
    fun `nonblank refid scrolls element not page`() = runBlocking {
        val control = FakeBrowserControl()
        val tool = ScrollTool(control)
        val r = tool.execute(ScrollArgs(elementRef = "r42", deltaX = 0, deltaY = 100))
        assertTrue(r.startsWith("scroll=ok"), "应返回成功: $r")
        assertTrue(control.log.none { it.startsWith("scrollPage") }, "非空 refid 不应走页面级 scrollPage")
    }
}
