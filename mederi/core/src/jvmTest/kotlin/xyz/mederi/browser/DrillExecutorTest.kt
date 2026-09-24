package xyz.mederi.browser

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import xyz.mederi.browser.drill.DrillExecutor
import xyz.mederi.browser.drill.models.DrillBranch
import xyz.mederi.browser.drill.models.DrillScript
import xyz.mederi.browser.drill.models.DrillStep
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DrillExecutor 测试：用 FakeBrowserControl 脚本化驱动，覆盖
 * legacy loop / 两阶段 extract_list+item_steps / branch / abort_item / write_file。
 */
class DrillExecutorTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private fun scriptElement(script: DrillScript) = json.encodeToJsonElement(DrillScript.serializer(), script)

    // ── 1. legacy loop：loop_count 循环 + record_result ──

    @Test
    fun `legacy loop runs steps loop_count times and records results`() = runBlocking {
        val control = FakeBrowserControl(
            elements = mapOf(".job-title" to FakeElement(text = "Engineer", visible = true, count = 2))
        )
        val script = DrillScript(
            task_name = "legacy-test",
            loop_count = 2,
            steps = listOf(
                DrillStep(step_name = "click", action = "click", selector_value = ".job-title"),
                DrillStep(step_name = "record", action = "none", selector_value = ".job-title", record_result = true)
            )
        )

        val executor = DrillExecutor(control)
        val result = executor.executeScript(scriptElement(script))

        assertTrue(result.success, "success 应为 true, error=${result.error_message}")
        assertEquals(2, result.drill_results.size, "loop_count=2 应产生 2 条 drill_results")
        assertTrue(result.drill_results.all { it.containsKey("record") }, "每条结果都应含 record key")
    }

    // ── 2. 两阶段：extract_list 页面级步骤 → item_steps 逐条 fetch_content + ask_ai ──

    @Test
    fun `extract_list feeds item_steps with fetch_content and ask_ai`() = runBlocking {
        val control = FakeBrowserControl(
            elements = mapOf(
                ".job-card" to FakeElement(count = 2),
                ".jd" to FakeElement(text = "职位描述内容")
            ),
            jsResults = mapOf(
                // extract_list 生成的 JS 包含该子串，FakeBrowserControl 做子串匹配
                "document.querySelectorAll" to """[{"title":"A","link":"http://a"},{"title":"B","link":"http://b"}]"""
            )
        )
        val script = DrillScript(
            task_name = "two-stage-test",
            steps = listOf(
                DrillStep(
                    step_name = "extract",
                    action = "extract_list",
                    selector_value = ".job-card",
                    record_fields = mapOf("title" to ".title", "link" to "a@href")
                )
            ),
            item_steps = listOf(
                DrillStep(step_name = "fetch", action = "fetch_content", selector_value = ".jd"),
                DrillStep(
                    step_name = "judge",
                    action = "ask_ai",
                    content_from = listOf("content"),
                    instruction = "判定职位 {title} 是否匹配",
                    ai_result_key = "ai"
                )
            )
        )

        val executor = DrillExecutor(control)
        val result = executor.executeScript(
            scriptElement(script),
            onJudgeContent = { _: String, _: String ->
                BrainResult(success = true, data = mapOf("match" to "true"))
            }
        )

        assertTrue(result.success, "success 应为 true, error=${result.error_message}")
        assertEquals(2, result.drill_results.size, "extract_list 2 项应产生 2 条 drill_results")
        val first = result.drill_results[0]
        assertEquals("true", first["ai.match"], "ask_ai 结果应以 ai.match 前缀存入 itemContext")
        assertEquals("职位描述内容", first["content"], "fetch_content 内容应存入 content key")
        assertTrue(first["instruction"]?.isEmpty() == false || first.containsKey("title"), "item 应包含提取的原始字段")
    }

    // ── 3. branch：exists 条件命中匹配分支 ──

    @Test
    fun `branch executes matched branch when exists condition holds`() = runBlocking {
        val control = FakeBrowserControl(
            elements = mapOf(
                ".card" to FakeElement(count = 1),
                ".loaded" to FakeElement(visible = true, count = 1)
            ),
            jsResults = mapOf(
                "document.querySelectorAll" to """[{"id":"1"}]"""
            )
        )
        val script = DrillScript(
            task_name = "branch-test",
            steps = listOf(
                DrillStep(
                    step_name = "extract",
                    action = "extract_list",
                    selector_value = ".card",
                    record_fields = mapOf("id" to ".id-field")
                )
            ),
            item_steps = listOf(
                DrillStep(
                    step_name = "branchy",
                    action = "branch",
                    branches = listOf(
                        DrillBranch(
                            condition = "exists:.loaded",
                            steps = listOf(
                                DrillStep(step_name = "click-loaded", action = "click", selector_value = ".loaded")
                            )
                        )
                    ),
                    default_steps = listOf(DrillStep(step_name = "done", action = "none"))
                )
            )
        )

        val executor = DrillExecutor(control)
        val result = executor.executeScript(scriptElement(script))

        assertTrue(result.success, "success 应为 true, error=${result.error_message}")
        assertEquals(1, result.drill_results.size)
        assertTrue(
            control.log.any { it == "locator.click .loaded" },
            "应执行匹配分支的 click 动作, log=${control.log}"
        )
    }

    // ── 4. abort_item：条件成立跳过该 item，整体仍 success ──

    @Test
    fun `abort_item skips matching item without failing whole drill`() = runBlocking {
        val control = FakeBrowserControl(
            elements = mapOf(
                ".card" to FakeElement(count = 2),
                ".x" to FakeElement(text = "hello")
            ),
            jsResults = mapOf(
                "document.querySelectorAll" to """[{"id":"1"},{"id":"2"}]"""
            )
        )
        val script = DrillScript(
            task_name = "abort-test",
            steps = listOf(
                DrillStep(
                    step_name = "extract",
                    action = "extract_list",
                    selector_value = ".card",
                    record_fields = mapOf("id" to ".id")
                )
            ),
            item_steps = listOf(
                DrillStep(step_name = "abort", action = "abort_item", condition = "{id} == 2", then = "close_tab"),
                DrillStep(step_name = "fetch", action = "fetch_content", selector_value = ".x")
            )
        )

        val executor = DrillExecutor(control)
        val result = executor.executeScript(scriptElement(script))

        assertTrue(result.success, "abort_item 不应导致整体失败, error=${result.error_message}")
        assertEquals(1, result.drill_results.size, "id=1 的 item 应正常完成并进 results，id=2 的 item 应被跳过")
        assertEquals("hello", result.drill_results[0]["content"])
    }

    // ── 5. write_file：relative workingDir 写入 ──

    @Test
    fun `write_file writes content relative to workingDir`() = runBlocking {
        val dir = Files.createTempDirectory("drill-executor-test").toFile()
        try {
            val control = FakeBrowserControl(
                elements = mapOf(".card" to FakeElement(count = 1)),
                jsResults = mapOf(
                    "document.querySelectorAll" to """[{"id":"1"}]"""
                )
            )
            val script = DrillScript(
                task_name = "write-test",
                steps = listOf(
                    DrillStep(
                        step_name = "extract",
                        action = "extract_list",
                        selector_value = ".card",
                        record_fields = mapOf("id" to ".id")
                    )
                ),
                item_steps = listOf(
                    DrillStep(step_name = "write", action = "write_file", file_path = "out.txt", action_value = "hello")
                )
            )

            val executor = DrillExecutor(control, workingDir = dir)
            val result = executor.executeScript(scriptElement(script))

            assertTrue(result.success, "success 应为 true, error=${result.error_message}")
            val out = File(dir, "out.txt")
            assertTrue(out.exists(), "out.txt 应被写入 $dir")
            assertEquals("hello", out.readText())
        } finally {
            dir.deleteRecursively()
        }
    }
}