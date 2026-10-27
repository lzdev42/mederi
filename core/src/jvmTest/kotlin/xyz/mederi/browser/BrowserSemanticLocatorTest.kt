package xyz.mederi.browser

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/** 语义定位器（[LocatorKind]）按 kind 构造的显示键断言。 */
class BrowserSemanticLocatorTest {

    @Test
    fun `ROLE with name builds role colon name key`() = runBlocking {
        val control = FakeBrowserControl()
        val locator = control.locator(LocatorKind.ROLE, "button", name = "提交")
        val r = locator.click()
        assertTrue(r.success, "ROLE click 应成功: $r")
        assertTrue(
            control.log.any { it == "locator.click ROLE:button:提交" },
            "期望日志包含 locator.click ROLE:button:提交，实际日志: ${control.log}"
        )
    }

    @Test
    fun `TEXT builds text key`() = runBlocking {
        val control = FakeBrowserControl()
        control.locator(LocatorKind.TEXT, "登录").click()
        assertTrue(
            control.log.any { it == "locator.click TEXT:登录" },
            "期望日志包含 locator.click TEXT:登录，实际日志: ${control.log}"
        )
    }

    @Test
    fun `CSS builds css key`() = runBlocking {
        val control = FakeBrowserControl()
        control.locator(LocatorKind.CSS, "#x").click()
        assertTrue(
            control.log.any { it == "locator.click CSS:#x" },
            "期望日志包含 locator.click CSS:#x，实际日志: ${control.log}"
        )
    }

    @Test
    fun `ROLE with null name omits trailing colon`() = runBlocking {
        val control = FakeBrowserControl()
        control.locator(LocatorKind.ROLE, "button", name = null).click()
        assertTrue(
            control.log.any { it == "locator.click ROLE:button" },
            "期望日志包含 locator.click ROLE:button（不含尾部冒号），实际日志: ${control.log}"
        )
    }
}
