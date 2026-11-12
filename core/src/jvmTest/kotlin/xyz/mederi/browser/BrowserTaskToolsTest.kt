package xyz.mederi.browser

import kotlinx.coroutines.runBlocking
import xyz.mederi.domain.model.AIModel
import xyz.mederi.provider.domain.model.ReasoningLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RunBrowserTaskTool 的 recipe 参数严格性锁定：
 * - recipe 为非法 JSON → 返回 Error 文本且不触达 service.runTask（不再拿坏字符串当配方名继续执行）；
 * - recipe 为空 → 传 null recipe 正常派发；
 * - recipe 为合法 BrowserRecipe JSON → 解析后传给 service.runTask；
 * - 工具描述向 LLM 说明 BrowserRecipe 的完整 JSON 结构。
 */
class BrowserTaskToolsTest {

    /** 记录调用的 fake service（同 BrowserToolTest 的最小实现）。 */
    private class FakeService : BrowserTaskService {
        var runTaskCalls = 0
        var lastRecipe: BrowserRecipe? = null
        var lastBrowser: String? = null

        override fun runTask(
            task: String,
            aiModel: AIModel,
            reasoningLevel: ReasoningLevel,
            projectId: String,
            parentSessionId: String,
            browser: String?,
            apiKeyId: String?,
            recipe: BrowserRecipe?
        ): String {
            runTaskCalls++
            lastRecipe = recipe
            lastBrowser = browser
            return "task_1"
        }

        override fun taskStatus(taskId: String): String = """{"taskId":"$taskId","status":"COMPLETED"}"""
        override fun stopTask(taskId: String): String? = null
        override fun availableBrowsers(): List<String> = listOf("jcef")
        override fun defaultBrowser(): String = "jcef"
        override suspend fun browserStatus(currentSessionId: String): String = """{"browsers":[]}"""
    }

    private fun newTool(service: FakeService): RunBrowserTaskTool {
        val model = AIModel(id = "m", providerModelId = "m", name = "m")
        return RunBrowserTaskTool(service, model, ReasoningLevel.NONE, "p1", "sess_1")
    }

    @Test
    fun `recipe invalid json returns error and does not run task`() = runBlocking {
        val svc = FakeService()
        val result = newTool(svc).execute(
            RunBrowserTaskArgs(task = "search jobs", recipe = "not-json{{")
        )

        assertTrue(
            result.startsWith("Error: recipe must be valid BrowserRecipe JSON:"),
            "坏 recipe 应返回 Error 文本: $result"
        )
        assertEquals(0, svc.runTaskCalls, "坏 recipe 不得触达 service.runTask")
    }

    @Test
    fun `recipe blank passes null recipe and runs task`() = runBlocking {
        val svc = FakeService()
        val result = newTool(svc).execute(RunBrowserTaskArgs(task = "search jobs", recipe = ""))

        assertEquals(1, svc.runTaskCalls)
        assertEquals(null, svc.lastRecipe)
        assertTrue(result.contains("\"taskId\":\"task_1\""), "空 recipe 应正常派发: $result")
    }

    @Test
    fun `recipe valid json is parsed and passed to runTask`() = runBlocking {
        val svc = FakeService()
        val validRecipe =
            """{"name":"job_filter","description":"岗位筛选","operatorContent":"打开列表页","judgeRules":"薪资>20k","drillScript":null}"""

        val result = newTool(svc).execute(RunBrowserTaskArgs(task = "search jobs", recipe = validRecipe))

        assertEquals(1, svc.runTaskCalls)
        assertEquals("job_filter", svc.lastRecipe?.name)
        assertEquals("岗位筛选", svc.lastRecipe?.description)
        assertEquals("打开列表页", svc.lastRecipe?.operatorContent)
        assertEquals("薪资>20k", svc.lastRecipe?.judgeRules)
        assertEquals(null, svc.lastRecipe?.drillScript)
        assertTrue(result.contains("\"taskId\":\"task_1\""), "合法 recipe 应正常派发: $result")
    }

    @Test
    fun `description documents recipe json structure`() {
        val description = RunBrowserTaskTool.buildDescription(FakeService())

        assertTrue(description.contains("recipe"), "描述应说明 recipe 参数: $description")
        assertTrue(description.contains("judgeRules"), "描述应含 judgeRules 字段: $description")
        assertTrue(description.contains("drillScript"), "描述应含 drillScript 字段: $description")
        assertTrue(description.contains("operatorContent"), "描述应含 operatorContent 字段: $description")
    }
}