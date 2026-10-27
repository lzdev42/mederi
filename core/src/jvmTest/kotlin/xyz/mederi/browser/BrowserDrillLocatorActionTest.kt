package xyz.mederi.browser

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import xyz.mederi.browser.drill.DrillExecutor
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * BrowserLocator 元素级动作（press/check/scroll/type）经 Drill 消费的测试。
 *
 * 用 FakeBrowserControl + DrillExecutor 执行两阶段脚本：
 * - 页面级 steps 里放 extract_list + 定位器类动作（click/press/check/scroll，经 executeAction）；
 * - item_steps 里放 type_slowly（executeAction 不处理 type_slowly，仅 executeItemStep 分发）。
 *
 * 语义定位键规则（FakeBrowserControl）：`KIND:value[:name][:inexact]`——
 * 脚本未传 exact 时缺省 false，故键尾带 :inexact。
 */
class BrowserDrillLocatorActionTest {

    // 两阶段脚本：item_steps 非空 → 走 runPageLevelWorkflow
    private val SCRIPT_JSON = """
        {
          "task_name": "locator-actions",
          "steps": [
            {"step_name": "list", "action": "extract_list", "selector_value": ".card", "record_fields": {"id": ""}},
            {"step_name": "role-click", "action": "click", "playwright_api": "getByRole", "selector_type": "button", "selector_value": "提交", "selector_options": {"name": "提交"}},
            {"step_name": "text-press", "action": "press", "playwright_api": "getByText", "selector_value": "登录", "action_value": "Enter"},
            {"step_name": "check", "action": "check", "selector_value": ".agree"},
            {"step_name": "scroll", "action": "scroll", "selector_value": ".list", "action_value": "down"}
          ],
          "item_steps": [
            {"step_name": "slow-type", "action": "type_slowly", "selector_value": "#input", "action_value": "hello"}
          ]
        }
    """.trimIndent()

    @Test
    fun `locator element actions consumed by drill`() = runBlocking {
        val control = FakeBrowserControl(
            jsResults = mutableMapOf(".card" to """[{"id":"1"}]""")
        )
        val executor = DrillExecutor(control)
        val result = executor.executeScript(JsonPrimitive(SCRIPT_JSON))
        val log = control.log.joinToString("\n")

        assertTrue(result.success, "Drill 执行应成功；error=${result.error_message}；log 全文:\n$log")
        assertTrue(
            log.contains("locator.click ROLE:button:提交:inexact"),
            "getByRole 应走语义定位（role 来自 selector_type、name 来自 selector_options）；log 全文:\n$log"
        )
        assertTrue(
            log.contains("locator.press TEXT:登录:inexact=ENTER"),
            "getByText + 元素级 press 应记录按键名；log 全文:\n$log"
        )
        assertTrue(log.contains("locator.check CSS:.agree:inexact"), "元素级 check 应记录；log 全文:\n$log")
        assertTrue(
            log.contains("locator.scroll CSS:.list:inexact=0,400"),
            "元素级 scroll 应以元素中心为原点记录 0,400；log 全文:\n$log"
        )
        assertTrue(log.contains("locator.type #input=hello"), "type_slowly 应走元素级 type；log 全文:\n$log")
    }
}
