package xyz.mederi.browser

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import xyz.mederi.browser.drill.DrillExecutor
import xyz.mederi.browser.drill.models.DrillScript
import xyz.mederi.browser.drill.models.DrillStep
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Obscura 契约观测测试（写 collector 观测文件，gradle workingDir=core/）：
 * - prompt：断言 BrowserPrompt 同时含 refs 直传与每快照失效语义 → build/obscura-contract/prompt.txt
 * - drill_fail：upload_file 触发 UnsupportedOperationException 时该 step FAIL（原因含
 *   "unsupported"）且无异常冒泡 → build/obscura-contract/drill-fail.txt
 */
class ObscuraContractObservationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private fun observationDir(): File {
        val dir = File("build/obscura-contract")
        dir.mkdirs()
        return dir
    }

    @Test
    fun `prompt observation records refs-direct plus refs-invalidated`() {
        val prompt = BrowserPrompt.build(maxSteps = 20)
        val hasRefsDirect = prompt.contains("passed directly to the page tools")
        val hasRefsInvalidated = prompt.contains("Every new snapshot invalidates all old refs")

        val out = File(observationDir(), "prompt.txt")
        when {
            hasRefsDirect && hasRefsInvalidated -> out.writeText("refs-direct+refs-invalidated")
            !hasRefsDirect -> {
                out.writeText("missing:refs-direct")
                assertTrue(false, "prompt 缺 refs 直传语义")
            }
            else -> {
                out.writeText("missing:refs-invalidated")
                assertTrue(false, "prompt 缺每快照失效语义")
            }
        }
    }

    @Test
    fun `drill_fail observation records FAIL(unsupported) without bubbling`() = runBlocking {
        val control = FakeBrowserControl(
            elements = mapOf(".card" to FakeElement(count = 1)),
            jsResults = mapOf("document.querySelectorAll" to """[{"id":"1"}]""")
        )
        control.unsupportedSelectors = mapOf("input[type=file]" to "uploadFileBySelector not supported by this backend")
        val script = DrillScript(
            task_name = "unsupported-test",
            steps = listOf(
                DrillStep(
                    step_name = "extract",
                    action = "extract_list",
                    selector_value = ".card",
                    record_fields = mapOf("id" to ".id")
                )
            ),
            item_steps = listOf(
                DrillStep(
                    step_name = "upload",
                    action = "upload_file",
                    selector_value = "input[type=file]",
                    action_value = "demo.txt"
                )
            )
        )

        val executor = DrillExecutor(control)
        // 异常不得冒泡：executeScript 正常返回 FAIL 结果
        val result = executor.executeScript(json.encodeToJsonElement(DrillScript.serializer(), script))

        val stepFailed = result.drill_results.singleOrNull()?.let {
            it["_status"] == "FAIL" && it["_failure_reason"]?.contains("unsupported") == true
        } == true
        val overallFailed = !result.success && result.error_message?.contains("unsupported") == true

        val out = File(observationDir(), "drill-fail.txt")
        if (stepFailed && overallFailed) {
            out.writeText("FAIL(unsupported)")
        } else {
            out.writeText(
                "actual:success=${result.success},error=${result.error_message},results=${result.drill_results}"
            )
            assertTrue(false, "upload_file unsupported 应落为该 step FAIL(results=${result.drill_results}, error=${result.error_message})")
        }
    }
}
