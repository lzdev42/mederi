package xyz.mederi.browser

import xyz.mederi.domain.model.AIModel
import xyz.mederi.provider.domain.model.ReasoningLevel

/**
 * 浏览器任务服务接口（主代理工具只依赖这个）。
 *
 * 实现（BrowserTaskManager）持有 [BrowserRegistry]——UI 注册 JCEF、core 注册 Camoufox，
 * AI 通过 run_browser_task 的 browser 参数选择；本接口暴露已注册浏览器供工具动态描述。
 */
interface BrowserTaskService {

    /**
     * 异步派发浏览器任务，立即返回 taskId（不阻塞）。
     * @param browser 浏览器名（注册表里的 name）；null = 用默认。
     */
    fun runTask(
        task: String,
        aiModel: AIModel,
        reasoningLevel: ReasoningLevel,
        projectId: String,
        parentSessionId: String,
        browser: String? = null
    ): String

    /** 查询任务状态，返回 JSON（RUNNING/COMPLETED/ERROR/STOPPED/NOT_FOUND + 简短消息 + browser）。 */
    fun taskStatus(taskId: String): String

    /** 停止任务，返回 JSON。 */
    fun stopTask(taskId: String): String

    /** 已注册浏览器名列表（工具描述动态生成用）。 */
    fun availableBrowsers(): List<String>

    /** 默认浏览器名（无注册返回 null）。 */
    fun defaultBrowser(): String?

    /**
     * 浏览器运行状态查询（browser_info，只读、无副作用，与任务操作区分）。
     * 返回 [BrowserStatusInfo] 的 JSON：每个已注册浏览器（JCEF/Camoufox 可同时打开）的
     * 使用状态、打开实例（tab/无头进程）数量、各自页面 URL/标题，以及是否由当前会话启动。
     */
    suspend fun browserStatus(currentSessionId: String): String
}
