package xyz.mederi.browser

import xyz.kbrowser.webview.ElementNotFoundException
import xyz.kbrowser.webview.KBLocator
import xyz.kbrowser.webview.KBrowser
import xyz.kbrowser.webview.KBPage
import xyz.kbrowser.webview.SnapshotMode
import xyz.mederi.browser.bidi.KeyboardKey
import xyz.mederi.browser.bidi.OperationResult

/**
 * [BrowserControl] 的 JCEF/KBrowser 实现（core 侧自动化引擎），替代 Camoufox 成为主力引擎。
 *
 * 用 KBrowser 的 [KBPage]/KBWebView API 做薄适配：KBPage 已有大部分所需方法
 * （坐标点击 + 验证、逐字符输入、滚动、按键、JS、快照、截图、上传、waitFor）。
 * a11y tree snapshot 与 BiDi 对齐：refid 定位，KBPage.snapshot(CLEAN) 返回
 * yaml + rawTree（rawTree 仅用于同批 refid 重映射，用完即丢）。
 *
 * 生命周期与职责边界：
 * - 不负责 KBrowser 初始化（KBrowser.initializeConfig / initializeKBrowser 那是 [KBrowserRuntime] 的职责）；
 * - 不负责 inkcompose 注入（那是 main.kt 的职责）；
 * - 无 UI（headless/server）：[start] 创建带 viewport 的后台自动化页面（无 UI 挂载），适合离屏渲染；
 * - 有 UI（desktop）：由 [JcefBrowserHost.createAiTab] 创建可见 tab（viewport-less，挂进 KBWebView），
 *   用带页面的构造函数构造本类——[start] 为 no-op，[close] 只释放引用不关页面（UI 拥有生命周期）。
 *
 * 多 tab 模型：tabs（uuid → page）管理全部页面，[page] 始终指向当前激活 tab，
 * 所有操作作用在当前激活 tab 上；[selectTab]/[closeTab]/[openTab] 维护激活状态。
 */
class JcefBrowserControl(
    private val viewportWidth: Int = 1280,
    private val viewportHeight: Int = 720,
    private val uiOwned: Boolean = false
) : BrowserControl {

    /**
     * 用 UI 宿主已创建的可见页面构造（desktop 路径）。
     * - [start] 为 no-op（页面已存在）；
     * - [close] 只释放引用，不关页面——页面生命周期归 UI 宿主管。
     */
    constructor(page: KBPage) : this(uiOwned = true) {
        this.page = page
        this.tabs[page.uuid] = page
        this.activeTabId = page.uuid
    }

    private var page: KBPage? = null

    // 多 tab 管理：uuid → page；page 始终指向当前激活 tab
    private val tabs = mutableMapOf<String, KBPage>()
    private var activeTabId: String? = null

    override suspend fun start() {
        if (page != null) return
        // 带 viewport 的 newPage = 后台自动化页面（无 UI 挂载），仅 headless/server 路径
        val newPage = KBrowser.newPage(
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight
        )
        page = newPage
        tabs[newPage.uuid] = newPage
        activeTabId = newPage.uuid
    }

    override suspend fun close() {
        if (!uiOwned) {
            // headless/server 路径：关闭全部 tab，避免泄漏页面
            tabs.values.forEach { it.close() }
        }
        // UI 路径：只释放引用，不关页面（UI 宿主拥有生命周期）
        tabs.clear()
        activeTabId = null
        page = null
    }

    override suspend fun getCurrentUrl(): String = page?.webView?.currentUrl?.value ?: ""

    override suspend fun getTitle(): String = page?.webView?.currentTitle?.value ?: ""

    override suspend fun navigate(url: String): OperationResult {
        return try {
            requirePage().loadUrl(url)
            OperationResult.Success("navigate", detail = url)
        } catch (e: Exception) {
            OperationResult.Failure("navigate", e.message ?: e::class.simpleName.orEmpty())
        }
    }

    override suspend fun click(elementRef: String): OperationResult =
        requirePage().click(elementRef).toCore()

    override suspend fun clickByCoordinates(x: Int, y: Int): OperationResult =
        requirePage().clickByCoordinates(x, y).toCore()

    override suspend fun type(elementRef: String, text: String): OperationResult {
        val p = requirePage()
        // KBrowser 的 type(text) 作用于当前聚焦元素——先点击目标元素获得焦点，再输入
        p.click(elementRef)
        p.type(text)
        return OperationResult.Success("type", detail = text)
    }

    override suspend fun scroll(elementRef: String, deltaX: Int, deltaY: Int): OperationResult =
        requirePage().scroll(elementRef, deltaX, deltaY).toCore()

    override suspend fun press(key: KeyboardKey): OperationResult {
        return try {
            requirePage().press(key.toKBrowser())
            OperationResult.Success("press", detail = key.name)
        } catch (e: Exception) {
            OperationResult.Failure("press", e.message ?: e::class.simpleName.orEmpty())
        }
    }

    override suspend fun snapshot(): PageSnapshot {
        val p = requirePage()
        val result = p.snapshot(SnapshotMode.CLEAN)
        return PageSnapshot(
            url = getCurrentUrl(),
            title = getTitle(),
            yaml = result.yaml,
            rawTree = result.rawTree
        )
    }

    override suspend fun screenshot(): ByteArray? = requirePage().screenshot()

    override suspend fun evaluateJavascript(js: String): String = requirePage().evaluateJavascript(js)

    override suspend fun openTab(): OperationResult {
        val newPage = KBrowser.newPage(
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight
        )
        tabs[newPage.uuid] = newPage
        activeTabId = newPage.uuid
        page = newPage
        return OperationResult.Success("open_tab", detail = newPage.uuid)
    }

    override suspend fun closeTab(tabId: String): OperationResult {
        tabs.remove(tabId)?.close()
        // 关的是当前激活 tab → 激活状态落到剩余第一个；同时让 page 跟随，避免操作已销毁页面
        if (activeTabId == tabId) {
            activeTabId = tabs.keys.firstOrNull()
            page = activeTabId?.let { tabs[it] }
        }
        return OperationResult.Success("close_tab", detail = tabId)
    }

    override suspend fun listTabs(): List<String> = tabs.keys.toList()

    override suspend fun selectTab(tabId: String): OperationResult =
        tabs[tabId]?.let {
            page = it
            activeTabId = tabId
            OperationResult.Success("select_tab", detail = tabId)
        } ?: OperationResult.Failure("select_tab", "tab not found: $tabId")

    override suspend fun uploadFile(elementRef: String, filePaths: List<String>): OperationResult {
        requirePage().uploadFile(elementRef, filePaths)
        return OperationResult.Success("upload_file", detail = filePaths.joinToString(","))
    }

    override suspend fun scrollByCoordinates(
        originX: Int,
        originY: Int,
        deltaX: Int,
        deltaY: Int
    ): OperationResult =
        requirePage().scrollByCoordinates(originX, originY, deltaX, deltaY).toCore()

    override suspend fun waitFor(selector: String, timeoutMs: Long): OperationResult {
        return try {
            requirePage().waitFor(text = selector, timeoutMs = timeoutMs)
            OperationResult.Success("wait_for", detail = selector)
        } catch (e: Exception) {
            OperationResult.Failure("wait_for", e.message ?: "timeout")
        }
    }

    override suspend fun locator(selector: String): BrowserLocator =
        JcefBrowserLocator(requirePage().locator(selector), selector)

    private fun requirePage(): KBPage = page ?: error("Browser not started — call start() first.")
}

/**
 * [BrowserLocator] 的 KBLocator 适配：薄包装，把 KBLocator 的方法映射到 core 契约，
 * 并把"元素未找到"（[ElementNotFoundException]）转成 [OperationResult.Failure] 而非抛异常。
 */
private class JcefBrowserLocator(
    private val delegate: KBLocator,
    private val selector: String
) : BrowserLocator {

    override suspend fun click(): OperationResult =
        withNotFound("locator.click") { delegate.click().toCore() }

    override suspend fun fill(text: String): OperationResult =
        withNotFound("locator.fill") { delegate.fill(text).toCore() }

    override suspend fun hover(): OperationResult = withNotFound("locator.hover") {
        delegate.hover()
        OperationResult.Acknowledged
    }

    override suspend fun getText(): String = runCatching { delegate.getText() }.getOrDefault("")

    override suspend fun getAttribute(name: String): String? =
        runCatching { delegate.getAttribute(name) }.getOrNull()

    override suspend fun count(): Int = delegate.count()

    override suspend fun isVisible(): Boolean = delegate.isVisible()

    override suspend fun nth(i: Int): BrowserLocator = JcefBrowserLocator(delegate.nth(i), selector)

    override suspend fun first(): BrowserLocator = JcefBrowserLocator(delegate.first(), selector)

    override suspend fun last(): BrowserLocator = JcefBrowserLocator(delegate.last(), selector)

    override suspend fun filter(hasText: String): BrowserLocator =
        JcefBrowserLocator(delegate.filter { it.text.contains(hasText) }, selector)

    /** 元素未找到 → Failure（recoverable=false，重试无意义）；其他异常 → Failure。 */
    private suspend fun withNotFound(action: String, block: suspend () -> OperationResult): OperationResult =
        try {
            block()
        } catch (e: ElementNotFoundException) {
            OperationResult.Failure(action, "element not found: $selector", recoverable = false)
        } catch (e: Exception) {
            OperationResult.Failure(action, e.message ?: e::class.simpleName.orEmpty())
        }
}

// ────────────────────────────────────────────────
// 类型映射（文件私有，JcefBrowserControl 与 JcefBrowserLocator 共用）
// ────────────────────────────────────────────────

/** KBrowser OperationResult → core OperationResult（两者结构完全一致）。 */
private fun xyz.kbrowser.webview.OperationResult.toCore(): OperationResult = when (this) {
    is xyz.kbrowser.webview.OperationResult.Success ->
        OperationResult.Success(action, verified, detail)
    is xyz.kbrowser.webview.OperationResult.Failure ->
        OperationResult.Failure(action, reason, recoverable)
    xyz.kbrowser.webview.OperationResult.Acknowledged ->
        OperationResult.Acknowledged
}

/** core KeyboardKey → KBrowser KeyboardKey（同名 enum，valueOf 直接转）。 */
private fun KeyboardKey.toKBrowser(): xyz.kbrowser.webview.KeyboardKey =
    xyz.kbrowser.webview.KeyboardKey.valueOf(this.name)
