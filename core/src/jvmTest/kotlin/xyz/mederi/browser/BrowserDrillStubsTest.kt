package xyz.mederi.browser

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import xyz.mederi.browser.drill.DrillExecutor
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 补齐的罕见 Drill 动作测试：extract_chat_history / upload_file。
 *
 * 两个动作都只在 item_steps 路径分发，因此测试必须走两阶段
 * （extract_list 页面级步骤 → item_steps 条目级步骤）驱动。
 */
class BrowserDrillStubsTest {

    @Test
    fun `extract_chat_history extracts chat messages and stores chat_history`() = runBlocking {
        val control = FakeBrowserControl(
            jsResults = mapOf(
                // extract_list 生成的 JS 文本含 ".card" 字面量，FakeBrowserControl 子串匹配命中
                ".card" to """[{"id":"1"}]""",
                // extract_chat_history 生成的 JS 文本含 ".msg" 字面量，子串匹配命中
                ".msg" to """[{"sender":"me","text":"hi"},{"sender":"them","text":"hello"}]"""
            )
        )
        val script = """
            {"task_name":"chat","steps":[{"step_name":"list","action":"extract_list","selector_value":".card","record_fields":{"id":""}}],
             "item_steps":[{"step_name":"chat","action":"extract_chat_history","selector_value":".msg"}]}
        """.trimIndent()

        val executor = DrillExecutor(control)
        val result = executor.executeScript(Json.parseToJsonElement(script))

        assertTrue(result.success, "success 应为 true, error=${result.error_message}, drill_results=${result.drill_results}")
        assertTrue(
            result.drill_results.any {
                val chat = it["chat_history"]
                chat != null && chat.contains("[我方]: hi") && chat.contains("[对方]: hello")
            },
            "drill_results 应含 chat_history 记录且同时含 '[我方]: hi' 与 '[对方]: hello', drill_results=${result.drill_results}"
        )
    }

    @Test
    fun `upload_file resolves image_assets and calls uploadFileBySelector`() = runBlocking {
        val control = FakeBrowserControl(
            jsResults = mapOf(
                // extract_list 生成的 JS 文本含 ".card" 字面量，FakeBrowserControl 子串匹配命中
                ".card" to """[{"id":"1"}]"""
            )
        )
        val script = """
            {"task_name":"upload","image_assets":{"resume":{"path":"/tmp/a.pdf","desc":"简历"}},
             "steps":[{"step_name":"list","action":"extract_list","selector_value":".card","record_fields":{"id":""}}],
             "item_steps":[{"step_name":"upload","action":"upload_file","selector_value":"#file","action_value":"resume"}]}
        """.trimIndent()

        val executor = DrillExecutor(control)
        val result = executor.executeScript(Json.parseToJsonElement(script))

        assertTrue(result.success, "success 应为 true, error=${result.error_message}, drill_results=${result.drill_results}")
        assertTrue(
            control.log.any { it.contains("uploadFileBySelector #file /tmp/a.pdf") },
            "control.log 应含 uploadFileBySelector 调用, log=${control.log}"
        )
    }
}
