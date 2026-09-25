package xyz.mederi.browser

import kotlinx.coroutines.runBlocking
import xyz.mederi.domain.model.AIModel
import xyz.mederi.provider.domain.model.ReasoningLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BrowserTool（4→1 合一，action 分流）行为锁定：
 * - RUN 委托 RunBrowserTaskTool（空 task 的校验错误透传；正常路径返回 taskId JSON）
 * - STATUS / STOP 委托原工具；taskId 空白返回 Error 文本（不抛异常）
 * - INFO 委托 BrowserInfoTool（browserStatus(sessionId)）
 */
class BrowserToolTest {

    /** 记录调用的 fake service。 */
    private class FakeService : BrowserTaskService {
        var lastRunTask: String? = null
        var lastBrowser: String? = null
        var lastRecipe: BrowserRecipe? = null
        var statusQueried: String? = null
        var stopped: String? = null
        var statusSessionId: String? = null

        override fun runTask(
            task: String, aiModel: AIModel, reasoningLevel: ReasoningLevel,
            projectId: String, parentSessionId: String, browser: String?, apiKeyId: String?,
            recipe: BrowserRecipe?
        ): String {
            lastRunTask = task
            lastBrowser = browser
            lastRecipe = recipe
            return "task_1"
        }

        override fun taskStatus(taskId: String): String {
            statusQueried = taskId
            return """{"taskId":"$taskId","status":"COMPLETED"}"""
        }

        override fun stopTask(taskId: String): String {
            stopped = taskId
            return """{"taskId":"$taskId","status":"STOPPED"}"""
        }

        override fun availableBrowsers(): List<String> = listOf("jcef", "camoufox")
        override fun defaultBrowser(): String = "jcef"

        override suspend fun browserStatus(currentSessionId: String): String {
            statusSessionId = currentSessionId
            return """{"browsers":[]}"""
        }
    }

    private fun newTool(service: FakeService): BrowserTool {
        val model = AIModel(id = "m", providerModelId = "m", name = "m")
        return BrowserTool(
            run = RunBrowserTaskTool(service, model, ReasoningLevel.NONE, "p1", "sess_1"),
            status = BrowserTaskStatusTool(service),
            stop = StopBrowserTaskTool(service),
            info = BrowserInfoTool(service, "sess_1"),
            service = service
        )
    }

    @Test
    fun `run delegates with task and browser and recipe`() = runBlocking {
        val svc = FakeService()
        val result = newTool(svc).execute(
            BrowserArgs(action = BrowserTaskAction.RUN, task = "search jobs", browser = "camoufox", recipe = "job_filter")
        )

        assertEquals("search jobs", svc.lastRunTask)
        assertEquals("camoufox", svc.lastBrowser)
        assertEquals("job_filter", svc.lastRecipe?.name)
        assertTrue(result.contains("\"taskId\":\"task_1\""), "RUN 应返回 taskId JSON: $result")
        assertTrue(result.contains("\"browser\":\"camoufox\""), "RUN 应返回所选 browser: $result")
    }

    @Test
    fun `run with blank task keeps original validation error`() = runBlocking {
        val result = newTool(FakeService()).execute(BrowserArgs(action = BrowserTaskAction.RUN))

        assertTrue(result.startsWith("Error: task must not be empty"), "空 task 校验错误应透传: $result")
    }

    @Test
    fun `status and stop delegate with taskId`() = runBlocking {
        val svc = FakeService()
        val tool = newTool(svc)

        val status = tool.execute(BrowserArgs(action = BrowserTaskAction.STATUS, taskId = "task_9"))
        val stop = tool.execute(BrowserArgs(action = BrowserTaskAction.STOP, taskId = "task_9"))

        assertEquals("task_9", svc.statusQueried)
        assertEquals("task_9", svc.stopped)
        assertTrue(status.contains("\"status\":\"COMPLETED\""))
        assertTrue(stop.contains("\"status\":\"STOPPED\""))
    }

    @Test
    fun `status and stop with blank taskId yield error text`() = runBlocking {
        val svc = FakeService()
        val tool = newTool(svc)

        val status = tool.execute(BrowserArgs(action = BrowserTaskAction.STATUS))
        val stop = tool.execute(BrowserArgs(action = BrowserTaskAction.STOP))

        assertTrue(status.startsWith("Error: STATUS"), "STATUS 缺 taskId 应返回 Error: $status")
        assertTrue(stop.startsWith("Error: STOP"), "STOP 缺 taskId 应返回 Error: $stop")
        assertEquals(null, svc.statusQueried, "缺 taskId 不得触达 service")
    }

    @Test
    fun `info delegates to browserStatus with session`() = runBlocking {
        val svc = FakeService()
        val result = newTool(svc).execute(BrowserArgs(action = BrowserTaskAction.INFO))

        assertEquals("sess_1", svc.statusSessionId)
        assertTrue(result.contains("\"browsers\""))
    }
}
