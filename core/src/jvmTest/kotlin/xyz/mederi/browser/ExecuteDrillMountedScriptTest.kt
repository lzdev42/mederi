package xyz.mederi.browser

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue
import xyz.mederi.browser.drill.DrillExecutor

/**
 * ExecuteDrillTool 挂载脚本（mountedScript）测试：
 * - 空 script 参数时执行挂载脚本；
 * - 显式 script 覆盖挂载脚本；
 * - 两者皆空返回明确失败。
 *
 * 关键约束：DrillExecutor 的 legacy loop（item_steps == null）只支持 locator 类动作，
 * navigate 只能在 item_steps 路径（executeItemStep）执行，因此测试脚本统一使用
 * 两阶段结构：steps 放 extract_list，item_steps 放 navigate。
 */
class ExecuteDrillMountedScriptTest {

    private val mountedRecipe = BrowserRecipe(
        name = "mounted-recipe",
        drillScript = """{"task_name":"mounted","steps":[{"step_name":"list","action":"extract_list","selector_value":".card","record_fields":{"id":""}}],"item_steps":[{"step_name":"go","action":"navigate","url":"https://mounted.example"}]}"""
    )

    // ── 用例 A：空参跑挂载脚本 ──

    @Test
    fun `empty script runs mounted script`() = runBlocking {
        val control = FakeBrowserControl(jsResults = mutableMapOf(".card" to """[{"id":"1"}]"""))
        val drill = DrillExecutor(control)
        val tool = ExecuteDrillTool(
            drillExecutor = drill,
            brain = null,
            recipe = mountedRecipe,
            mountedScript = mountedRecipe.drillScript
        )

        val out = tool.execute(DrillArgs())

        assertTrue(out.startsWith("drill=ok"), "空 script 应执行挂载脚本并成功, out=$out, log=${control.log}")
        assertTrue(
            control.log.contains("navigate https://mounted.example"),
            "应导航到挂载脚本 URL, out=$out, log=${control.log}"
        )
    }

    // ── 用例 B：显式 script 覆盖挂载 ──

    @Test
    fun `explicit script overrides mounted script`() = runBlocking {
        val control = FakeBrowserControl(jsResults = mutableMapOf(".card" to """[{"id":"1"}]"""))
        val drill = DrillExecutor(control)
        val tool = ExecuteDrillTool(
            drillExecutor = drill,
            brain = null,
            recipe = mountedRecipe,
            mountedScript = mountedRecipe.drillScript
        )

        val out2 = tool.execute(
            DrillArgs(
                script = """{"task_name":"inline","steps":[{"step_name":"list","action":"extract_list","selector_value":".card","record_fields":{"id":""}}],"item_steps":[{"step_name":"go","action":"navigate","url":"https://inline.example"}]}"""
            )
        )

        assertTrue(out2.startsWith("drill=ok"), "显式 script 应覆盖挂载脚本并成功, out=$out2, log=${control.log}")
        assertTrue(
            control.log.contains("navigate https://inline.example"),
            "应导航到显式 script URL 而非挂载脚本, out=$out2, log=${control.log}"
        )
        assertTrue(
            !control.log.contains("https://mounted.example"),
            "不应同时出现挂载脚本 URL, out=$out2, log=${control.log}"
        )
    }

    // ── 用例 C：两者皆空 → 明确失败 ──

    @Test
    fun `no script and no mounted script fails clearly`() = runBlocking {
        val control = FakeBrowserControl()
        val drill = DrillExecutor(control)
        val bare = ExecuteDrillTool(
            drillExecutor = drill,
            brain = null,
            recipe = null,
            mountedScript = null
        )

        val out3 = bare.execute(DrillArgs())

        assertTrue(out3.startsWith("drill=FAIL"), "script 与挂载脚本皆空应返回明确失败, out=$out3")
    }
}
