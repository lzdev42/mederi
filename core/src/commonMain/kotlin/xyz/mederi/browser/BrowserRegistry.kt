package xyz.mederi.browser

import java.util.concurrent.ConcurrentHashMap

/**
 * 浏览器类型（AI 可感知，用于选择）。
 */
enum class BrowserKind(val label: String) {
    /**
     * 内置无头浏览器（Obscura，外挂二进制）：经 MCP stdio 驱动的独立进程，永远 stealth。
     * 适合所有无头自动化浏览任务（页面不可见）。
     */
    OBSCURA("built-in headless browser (Obscura) — external binary driven over MCP stdio, always stealth"),

    /** 宿主提供的自定义浏览器实现。 */
    CUSTOM("custom browser")
}

/**
 * 已注册的浏览器（名称 = AI 看到的标识）。
 *
 * 工厂是 suspend 且带 taskId：便于后端按需起进程/建页等异步初始化，
 * 同时需要把 taskId 记到实例上（browser_info 据此判断是不是当前会话启动的）。
 */
class RegisteredBrowser(
    val name: String,
    val kind: BrowserKind,
    val factory: suspend (taskId: String, sessionId: String) -> BrowserControl,
    /** 可选运行状态上报源（browser_info 用），未提供时从任务表获取。 */
    val statusSource: BrowserStatusSource? = null
)

/**
 * 浏览器注册中心：各宿主注册其可用的浏览器，AI 通过 browser(browser=name) 选择。
 *
 * - [register] 幂等：同名覆盖（宿主重连时更新实例）。
 * - [resolve] null → 用 [default]（默认名优先，否则选择一个已注册浏览器）。
 * - 注册发生在启动装配阶段（各宿主注册其浏览器实例），
 *   注册后立即生效——BrowserTaskManager 持有本注册中心引用，无需重启。
 * - 默认策略：defaultName 指定的浏览器优先；否则选择一个已注册浏览器。
 *   无浏览器注册时 resolve 返回 null，浏览器任务会失败。
 */
object BrowserRegistry {

    /** 宿主可设置优先使用的浏览器名。 */
    @Volatile
    var defaultName: String? = null

    private val browsers = ConcurrentHashMap<String, RegisteredBrowser>()

    fun register(
        name: String,
        kind: BrowserKind,
        factory: suspend (taskId: String, sessionId: String) -> BrowserControl,
        statusSource: BrowserStatusSource? = null
    ) {
        browsers[name] = RegisteredBrowser(name, kind, factory, statusSource)
    }

    fun register(
        name: String,
        kind: BrowserKind,
        factory: suspend (taskId: String) -> BrowserControl,
        statusSource: BrowserStatusSource? = null
    ) {
        register(name, kind, { taskId, _ -> factory(taskId) }, statusSource)
    }

    fun unregister(name: String) {
        browsers.remove(name)
    }

    /** 按名字解析；name 为 null 或未知 → 回落默认。 */
    fun resolve(name: String?): RegisteredBrowser? =
        name?.let { browsers[it] } ?: default()

    /**
     * 默认浏览器：defaultName 指定的；否则选择一个已注册浏览器。
     * 无浏览器注册时返回 null。
     */
    fun default(): RegisteredBrowser? =
        defaultName?.let { browsers[it] }
            ?: browsers.values.firstOrNull()

    fun list(): List<RegisteredBrowser> = browsers.values.toList()

    /** 已注册浏览器名列表（AI 提示词/工具描述用）。 */
    fun availableNames(): List<String> = browsers.keys.sorted()

    fun isEmpty(): Boolean = browsers.isEmpty()
}
