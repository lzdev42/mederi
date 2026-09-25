package xyz.mederi.browser

import java.util.concurrent.ConcurrentHashMap

/**
 * 浏览器类型（AI 可感知，用于选择）。
 */
enum class BrowserKind(val label: String) {
    /**
     * 内置可见浏览器（JCEF，UI 提供）。页面可见，用户能看到在做什么。
     * 优先用于：测试用户开发的网页、需要用户观察/交互的场景。
     */
    JCEF("built-in visible browser (JCEF) — shows the page live; prefer for testing the user's own web pages or when the user wants to watch"),

    /**
     * 无头反检测浏览器（Camoufox，core 提供）。反爬、不暴露窗口。
     * 优先用于：第三方网站的自动化操作/抓取。
     */
    CAMOUFOX("headless anti-detection browser (Camoufox) — prefer for automation/scraping on third-party sites")
}

/**
 * 已注册的浏览器（名称 = AI 看到的标识）。
 *
 * 工厂是 suspend 且带 taskId：JCEF 版创建 KBPage 需要挂到 Main 线程且 newPage 本身是 suspend，
 * 同时需要把 taskId 记到 tab 上（browser_info 据此判断是不是当前会话启动的）；
 * Camoufox 版工厂只构造对象（真正的进程启动在 start() 里，非 suspend）。
 */
class RegisteredBrowser(
    val name: String,
    val kind: BrowserKind,
    val factory: suspend (taskId: String) -> BrowserControl,
    /** 可选运行状态上报源（browser_info 用）：JCEF = UI host 上报 tab；Camoufox = null（用任务表）。 */
    val statusSource: BrowserStatusSource? = null
)

/**
 * 浏览器注册中心：UI 注册 JCEF，core 注册 Camoufox，AI 通过 run_browser_task(browser=name) 选择。
 *
 * - [register] 幂等：同名覆盖（UI 重连时更新 JCEF 实例）。
 * - [resolve] null → 用 [default]（默认名优先，否则内置 JCEF 优先，最后才退回第一个注册的）。
 * - 注册发生在启动装配阶段（MederiAiCore 注册 camoufox；desktop UI 注册 jcef），
 *   注册后立即生效——BrowserTaskManager 持有本注册中心引用，无需重启。
 * - 默认策略（2026-09 起）：UI 宿主注册 JCEF 后默认即用内置可见浏览器（用户可观察），
 *   与注册顺序无关；headless server 没有 JCEF 时才以 camoufox 为默认。
 */
object BrowserRegistry {

    /** 默认浏览器名（UI 可设置，最高优先级）；null = 内置 JCEF 优先，再退回第一个注册的。 */
    @Volatile
    var defaultName: String? = null

    private val browsers = ConcurrentHashMap<String, RegisteredBrowser>()

    fun register(
        name: String,
        kind: BrowserKind,
        factory: suspend (taskId: String) -> BrowserControl,
        statusSource: BrowserStatusSource? = null
    ) {
        browsers[name] = RegisteredBrowser(name, kind, factory, statusSource)
    }

    fun unregister(name: String) {
        browsers.remove(name)
    }

    /** 按名字解析；name 为 null 或未知 → 回落默认。 */
    fun resolve(name: String?): RegisteredBrowser? =
        name?.let { browsers[it] } ?: default()

    /**
     * 默认浏览器：defaultName 指定的；否则优先内置 JCEF（用户可观察、最常用），
     * 与注册顺序无关；再没有（如 headless server 只有 camoufox）才用第一个注册的；没有返回 null。
     */
    fun default(): RegisteredBrowser? =
        defaultName?.let { browsers[it] }
            ?: browsers.values.firstOrNull { it.kind == BrowserKind.JCEF }
            ?: browsers.values.firstOrNull()

    fun list(): List<RegisteredBrowser> = browsers.values.toList()

    /** 已注册浏览器名列表（AI 提示词/工具描述用）。 */
    fun availableNames(): List<String> = browsers.keys.sorted()

    fun isEmpty(): Boolean = browsers.isEmpty()
}
