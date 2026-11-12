package xyz.mederi.browser

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowserBrainTest {

    /** 固定返回给定文本的 LLM caller（callWithTools / callWithToolsStreaming 走接口默认实现）。 */
    private class StubLLMCaller(private val response: String) : BrowserLLMCaller {
        override suspend fun call(systemPrompt: String, userPrompt: String, image: ByteArray?): String = response
    }

    @Test
    fun `test judge prompt building with recipe`() {
        val userPrompt = BrowserBrainPrompt.buildJudgeUserPrompt(
            content = "Company: Mederi\nRole: Kotlin Engineer\nRemote: Yes",
            instruction = "Check if remote is allowed",
            recipeRules = "Rule 1: Only consider remote positions.",
            collectedItems = listOf(
                BrowserCollectedItem(
                    url = "https://example.com/item",
                    siteName = "Example",
                    content = "Previous finding: salary 100k"
                )
            )
        )

        assertTrue(userPrompt.contains("Recipe Rules"))
        assertTrue(userPrompt.contains("Only consider remote positions"))
        assertTrue(userPrompt.contains("Company: Mederi"))
        assertTrue(userPrompt.contains("Check if remote is allowed"))
        assertTrue(userPrompt.contains("Previous finding: salary 100k"))
        assertTrue(userPrompt.contains("Collected Research Items"))
    }

    @Test
    fun `test report prompt building with history and collected items`() {
        val userPrompt = BrowserBrainPrompt.buildReportUserPrompt(
            taskGoal = "Find remote jobs",
            taskHistory = "Step 1: Opened site\nStep 2: Scrolled down",
            rawOperatorMessage = "Extracted 5 jobs",
            recipeRules = "Filter remote",
            collectedItems = listOf(
                BrowserCollectedItem(
                    url = "https://remoteok.com/job1",
                    siteName = "RemoteOK",
                    content = "Job 1: Senior Engineer at Mederi"
                )
            )
        )

        assertTrue(userPrompt.contains("Find remote jobs"))
        assertTrue(userPrompt.contains("Step 1: Opened site"))
        assertTrue(userPrompt.contains("Extracted 5 jobs"))
        assertTrue(userPrompt.contains("Filter remote"))
        assertTrue(userPrompt.contains("Collected Research Items"))
        assertTrue(userPrompt.contains("RemoteOK"))
        assertTrue(userPrompt.contains("Senior Engineer at Mederi"))
    }

    @Test
    fun `test brain result compact string variants`() {
        val matchTrue = BrainResult(
            success = true,
            data = mapOf("match" to "true", "reason" to "Requirement satisfied")
        )
        assertEquals("match=true, reason=Requirement satisfied", matchTrue.toCompactString())

        val summaryOnly = BrainResult(
            success = true,
            data = mapOf("summary" to "Task finished with 10 results")
        )
        assertEquals("summary=Task finished with 10 results", summaryOnly.toCompactString())

        val failure = BrainResult(
            success = false,
            text = "Rate limited"
        )
        assertEquals("FAIL: Rate limited", failure.toCompactString())
    }

    @Test
    fun `test recipe model defaults`() {
        val recipe = BrowserRecipe(name = "test_recipe")
        assertEquals("test_recipe", recipe.name)
        assertEquals("", recipe.description)
        assertEquals("", recipe.judgeRules)
        assertEquals(null, recipe.drillScript)
    }

    @Test
    fun `judge parses markdown fenced json`() = runBlocking {
        val brain = BrowserBrain(
            llmCaller = StubLLMCaller(
                """
                ```json
                {"match":"true","reason":"ok"}
                ```
                """.trimIndent()
            )
        )

        val result = brain.judge(content = "page text", instruction = "check")

        assertTrue(result.success, "围栏包裹的 JSON 应正常解析: ${result.text}")
        assertEquals("true", result.data["match"])
        assertEquals("ok", result.data["reason"])
    }

    @Test
    fun `judge with prose output fails with raw parse error`() = runBlocking {
        val brain = BrowserBrain(llmCaller = StubLLMCaller("The job is remote and pays 100k."))

        val result = brain.judge(content = "page text", instruction = "check")

        assertFalse(result.success, "纯散文输出必须判为失败: ${result.text}")
        assertTrue(result.data.isEmpty(), "失败时不得产出空判定数据: $result")
        assertTrue(
            result.text.startsWith("BrowserBrain judge error:"),
            "应带原始解析错误: ${result.text}"
        )
        assertTrue(
            result.text.length > "BrowserBrain judge error:".length,
            "错误信息不得为空: ${result.text}"
        )
    }

    @Test
    fun `judge with nested object value fails instead of toString coercion`() = runBlocking {
        val brain = BrowserBrain(llmCaller = StubLLMCaller("""{"match":"true","details":{"a":1}}"""))

        val result = brain.judge(content = "page text", instruction = "check")

        assertFalse(result.success, "嵌套对象值必须判为失败: ${result.text}")
        assertTrue(result.text.contains("scalar"), "错误应说明非标量值: ${result.text}")
    }

    @Test
    fun `generateFinalReport with prose output fails with raw parse error`() = runBlocking {
        val brain = BrowserBrain(llmCaller = StubLLMCaller("任务已经完成了，没有任何结构化输出"))

        val result = brain.generateFinalReport(
            taskHistory = "step 1",
            goal = "goal",
            rawOperatorMessage = "done"
        )

        assertFalse(result.success, "纯散文输出必须判为失败: ${result.text}")
        assertTrue(
            result.text.startsWith("BrowserBrain report error:"),
            "应带原始解析错误: ${result.text}"
        )
    }
}
