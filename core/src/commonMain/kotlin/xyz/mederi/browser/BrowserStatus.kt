package xyz.mederi.browser

import kotlinx.serialization.Serializable

/**
 * 浏览器运行状态信息（browser_info 工具的返回模型，只读、无副作用）。
 *
 * 与任务操作（run_browser_task/browser_task_status/stop_browser_task）分开——
 * 主代理查询"现在浏览器什么状态"时用这个工具，不派发任务。
 *
 * 每个注册的浏览器都会出现在
 * [BrowserStatusInfo.browsers] 里（instances 空 = 当前未在使用）；instance 标注
 * 是任务驱动还是用户手动开的，以及是否由当前会话启动。
 */
@Serializable
data class BrowserStatusInfo(
    val browsers: List<BrowserStatusEntry> = emptyList()
)

/** 单个已注册浏览器的状态。 */
@Serializable
data class BrowserStatusEntry(
    /** 注册名（AI 工具参数用）。 */
    val name: String,
    /** 浏览器种类：JCEF / CUSTOM。 */
    val kind: String,
    /** 人话标签。 */
    val label: String,
    /** 是否正在使用中（有打开的 tab / 活跃任务）。 */
    val inUse: Boolean,
    /** 当前打开的实例（tab 或无头浏览器进程）。 */
    val instances: List<BrowserInstanceInfo> = emptyList()
)

/** 一个打开中的浏览器实例。 */
@Serializable
data class BrowserInstanceInfo(
    /** 实例 ID：任务驱动 = taskId；用户手动开 = tab 编号。 */
    val instanceId: String,
    /** 来源：task（任务驱动）/ user（用户手动开）。 */
    val origin: String,
    /** 当前页面 URL（可能为空 = 尚未导航）。 */
    val url: String = "",
    /** 页面标题（尽力而为）。 */
    val title: String = "",
    /** 是否由当前会话（调用本工具的会话）启动的任务创建。用户手动开的恒为 false。 */
    val isCurrentSession: Boolean = false,
    /** 实例状态：任务驱动 = STARTED/RUNNING/COMPLETED/ERROR/STOPPED；用户手动开 = OPEN。 */
    val status: String = ""
)

/**
 * 注册浏览器可选上报的运行状态源（默认 null = 该浏览器只有任务驱动的实例）。
 *
 * 宿主可以上报打开的标签页。未提供时由任务表推导浏览器状态。
 */
fun interface BrowserStatusSource {
    /** 当前打开的实例快照（实例 ID / 来源 taskId / URL / 标题）。实现须线程安全（可在内部切 Main）。 */
    suspend fun instances(): List<Instance>

    data class Instance(
        val id: String,
        /** 创建该 tab 的任务 taskId（用户手动开的为 null）。 */
        val taskId: String?,
        val url: String,
        val title: String
    )
}
