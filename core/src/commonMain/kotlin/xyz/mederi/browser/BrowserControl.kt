package xyz.mederi.browser

import xyz.mederi.browser.bidi.KeyboardKey
import xyz.mederi.browser.bidi.OperationResult

/** 页面快照：a11y tree 的紧凑 YAML + 原始树（rawTree 仅用于同批内 refid 重映射，用完即丢）。 */
data class PageSnapshot(
    val url: String,
    val title: String,
    val yaml: String,
    val rawTree: Any?
)

/** 语义定位方式（对齐 Playwright locator 家族；Drill 的 playwright_api 映射到此）。 */
enum class LocatorKind { CSS, XPATH, TEXT, ROLE, LABEL, PLACEHOLDER, ALT_TEXT, TITLE, TEST_ID }

/**
 * BrowserOperator 与 BrowserTaskManager 通过此接口控制宿主注册到 [BrowserRegistry] 的浏览器。
 * 主代理可通过快照中的 refid 定位页面元素。
 */
interface BrowserControl {

    /** 启动浏览器（连接内核），幂等。 */
    suspend fun start()

    /** 关闭浏览器，释放进程。幂等。 */
    suspend fun close()

    /** 当前页面 URL。 */
    suspend fun getCurrentUrl(): String

    /** 当前页面标题。 */
    suspend fun getTitle(): String

    /** 导航到 url。 */
    suspend fun navigate(url: String): OperationResult

    /** 点击 a11y 树中的元素（refid 定位）。 */
    suspend fun click(elementRef: String): OperationResult

    /** 按文档坐标点击（视觉模式兜底）。 */
    suspend fun clickByCoordinates(x: Int, y: Int): OperationResult

    /** 在元素中输入文本（refid 定位）。 */
    suspend fun type(elementRef: String, text: String): OperationResult

    /** 在元素上滚动（refid 定位）。 */
    suspend fun scroll(elementRef: String, deltaX: Int, deltaY: Int): OperationResult

    /**
     * 页面级滚动（无 refid）：滚动整个文档/视口。
     * 与 [scroll] 互斥：不带 refid 时用此方法，避免空串进入 refid 查找。
     * 默认用 window.scrollBy；后端可覆盖为视口中心真实滚轮事件。
     */
    suspend fun scrollPage(deltaX: Int, deltaY: Int): OperationResult =
        runCatching { evaluateJavascript("window.scrollBy($deltaX,$deltaY)") }
            .fold(
                onSuccess = { OperationResult.Acknowledged },
                onFailure = { OperationResult.Failure("scroll_page", it.message ?: "scrollBy failed") }
            )

    /** 按键。 */
    suspend fun press(key: KeyboardKey): OperationResult

    /** 获取当前页面的 a11y 快照（CLEAN 模式：仅视口可见可交互元素）。 */
    suspend fun snapshot(): PageSnapshot

    /** 截图（base64 PNG），供视觉模型。不可用时返回 null。 */
    suspend fun screenshot(): ByteArray?

    // ── 以下为 Drill（确定性自动化脚本）所需的新契约，全部提供 default 实现 ──
    // 后端未实现时抛 UnsupportedOperationException，不破坏既有实现编译。

    /** 按 CSS/文本选择器定位元素（Playwright 风格 locator 的轻量版）。 */
    suspend fun locator(selector: String): BrowserLocator =
        throw UnsupportedOperationException("selector locator not supported by this backend")

    /**
     * 按语义方式定位元素。
     * - ROLE：value = role，name = 可访问名（可空）
     * - TEXT：value = 可见文本，exact 控制精确/包含
     * - CSS/XPATH 之外的 kind 一律按语义查询，不要退化成 CSS。
     */
    suspend fun locator(
        kind: LocatorKind,
        value: String,
        name: String? = null,
        exact: Boolean = true
    ): BrowserLocator = throw UnsupportedOperationException("semantic locator not supported by this backend")

    /** 在当前页面执行 JS，返回结果字符串。 */
    suspend fun evaluateJavascript(js: String): String =
        throw UnsupportedOperationException("evaluateJavascript not supported")

    /** 打开新标签页，返回处理结果。 */
    suspend fun openTab(): OperationResult =
        throw UnsupportedOperationException("openTab not supported")

    /** 关闭指定标签页。 */
    suspend fun closeTab(tabId: String): OperationResult =
        throw UnsupportedOperationException("closeTab not supported")

    /** 列出所有标签页 id。 */
    suspend fun listTabs(): List<String> =
        throw UnsupportedOperationException("listTabs not supported")

    /** 切换到指定标签页。 */
    suspend fun selectTab(tabId: String): OperationResult =
        throw UnsupportedOperationException("selectTab not supported")

    /** 上传文件到指定元素（elementRef 定位）。 */
    suspend fun uploadFile(elementRef: String, filePaths: List<String>): OperationResult =
        throw UnsupportedOperationException("uploadFile not supported")

    /** 按 CSS 选择器上传文件到 input[type=file]（支持 display:none 的隐藏 input）。 */
    suspend fun uploadFileBySelector(selector: String, filePaths: List<String>): OperationResult =
        throw UnsupportedOperationException("uploadFileBySelector not supported by this backend")

    /** 按页面坐标增量滚动。 */
    suspend fun scrollByCoordinates(originX: Int, originY: Int, deltaX: Int, deltaY: Int): OperationResult =
        throw UnsupportedOperationException("scrollByCoordinates not supported")

    /** 等待选择器对应元素出现/可见。 */
    suspend fun waitFor(selector: String, timeoutMs: Long = 5000): OperationResult =
        throw UnsupportedOperationException("waitFor not supported")
}

/**
 * 元素定位器（Playwright Locator 的轻量子集，仅 Drill 需要的方法）。
 *
 * 由 [BrowserControl.locator] 返回；不支持的后端直接抛 UnsupportedOperationException。
 * 每个方法都返回一个新的定位器（或操作结果），不修改原定位器。
 */
interface BrowserLocator {

    /** 点击定位到的元素。 */
    suspend fun click(): OperationResult

    /** 在定位到的元素内填入文本。 */
    suspend fun fill(text: String): OperationResult

    /** 鼠标悬停到元素上。 */
    suspend fun hover(): OperationResult

    /** 取元素文本内容。 */
    suspend fun getText(): String

    /** 取元素属性（无则 null）。 */
    suspend fun getAttribute(name: String): String?

    /** 匹配到的元素个数。 */
    suspend fun count(): Int

    /** 元素是否可见。 */
    suspend fun isVisible(): Boolean

    /** 取第 i 个匹配元素（0-based）。 */
    suspend fun nth(i: Int): BrowserLocator

    /** 取第一个匹配元素。 */
    suspend fun first(): BrowserLocator

    /** 取最后一个匹配元素。 */
    suspend fun last(): BrowserLocator

    /** 过滤出文本包含 hasText 的匹配元素。 */
    suspend fun filter(hasText: String): BrowserLocator

    /** 聚焦元素后按下按键（元素级）。 */
    suspend fun press(key: KeyboardKey): OperationResult

    /** 勾选元素（checkbox/radio；uncheck 同语义，底层为点击切换）。 */
    suspend fun check(): OperationResult

    /** 以元素中心为原点滚动。 */
    suspend fun scroll(deltaX: Int, deltaY: Int): OperationResult

    /** 在元素上逐字符输入（先清空原值）。 */
    suspend fun type(text: String): OperationResult
}
