package xyz.mederi.browser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.kbrowser.webview.ElementNotFoundException
import xyz.kbrowser.webview.KBLocator
import xyz.kbrowser.webview.KBPage
import xyz.kbrowser.webview.SnapshotMode
import xyz.mederi.browser.bidi.KeyboardKey
import xyz.mederi.browser.bidi.OperationResult

/**
 * 绑定到指定 [BrowserSessionState] 的浏览器控制端（提供给对应会话的子 Agent）。
 *
 * 核心设计：
 * 1. 严格会话边界：所有多 Tab 操作（[openTab], [closeTab], [listTabs], [selectTab]）
 *    均限定在当前 [session] 内部，无法跨会话访问其他会话的 Tab。
 * 2. 动态生命周期：子 Agent 可自主关闭自己不再使用的 Tab，释放对应 [KBPage]。
 * 3. 实时响应：动作直接作用在当前会话的激活 Tab 上；如果用户当前正处于该会话，UI 视图实时联动。
 */
internal class JcefSessionBrowserControl(
    private val session: BrowserSessionState,
    val taskId: String,
) : BrowserControl {

    override suspend fun start() {
        // 若当前会话尚无任何 tab，自动打开首个空白 tab
        if (session.tabs.isEmpty()) {
            session.openTab(initialUrl = null, select = true)
        }
    }

    override suspend fun close() {
        // 任务关闭后完全关闭浏览器：销毁本会话中的全部 Tab，释放底层页面资源
        withContext(Dispatchers.Main) {
            session.destroy()
        }
    }

    override suspend fun getCurrentUrl(): String =
        session.selectedTab?.page?.webView?.currentUrl?.value ?: ""

    override suspend fun getTitle(): String =
        session.selectedTab?.page?.webView?.currentTitle?.value ?: ""

    override suspend fun navigate(url: String): OperationResult {
        return try {
            val p = requirePage()
            p.loadUrl(url)
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
        p.click(elementRef)
        p.type(text)
        return OperationResult.Success("type", detail = text)
    }

    override suspend fun scroll(elementRef: String, deltaX: Int, deltaY: Int): OperationResult =
        requirePage().scroll(elementRef, deltaX, deltaY).toCore()

    override suspend fun scrollPage(deltaX: Int, deltaY: Int): OperationResult {
        val p = requirePage()
        // 视口中心（文档坐标）：内层滚动容器（常见 SPA）也能命中，
        // 比硬编码 640/360 在页面已滚动后落到视口外更可靠
        val center = p.evaluateJavascript(
            "(function(){return [window.scrollX+window.innerWidth/2, window.scrollY+window.innerHeight/2].join(',')})()"
        ).trim()
        val parts = center.split(',')
        val x = parts.getOrNull(0)?.trim()?.toDoubleOrNull()?.toInt() ?: 640
        val y = parts.getOrNull(1)?.trim()?.toDoubleOrNull()?.toInt() ?: 360
        return p.scrollByCoordinates(x, y, deltaX, deltaY).toCore()
    }

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

    override suspend fun evaluateJavascript(js: String): String =
        requirePage().evaluateJavascript(js)

    override suspend fun openTab(): OperationResult {
        val tab = session.openTab(initialUrl = null, select = true)
        return OperationResult.Success("open_tab", detail = tab.id.toString())
    }

    override suspend fun closeTab(tabId: String): OperationResult {
        val closed = session.closeTab(tabId)
        return if (closed) {
            OperationResult.Success("close_tab", detail = tabId)
        } else {
            OperationResult.Failure("close_tab", "tab not found in this session: $tabId")
        }
    }

    override suspend fun listTabs(): List<String> = session.listTabIds()

    override suspend fun selectTab(tabId: String): OperationResult {
        val selected = session.selectTab(tabId)
        return if (selected) {
            OperationResult.Success("select_tab", detail = tabId)
        } else {
            OperationResult.Failure("select_tab", "tab not found in this session: $tabId")
        }
    }

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
        JcefSessionBrowserLocator(requirePage().locator(selector), selector)

    private suspend fun requirePage(): KBPage {
        val existing = session.selectedTab?.page
        if (existing != null) return existing
        val newTab = session.openTab(initialUrl = null, select = true)
        return newTab.page
    }
}

private class JcefSessionBrowserLocator(
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

    override suspend fun nth(i: Int): BrowserLocator = JcefSessionBrowserLocator(delegate.nth(i), selector)

    override suspend fun first(): BrowserLocator = JcefSessionBrowserLocator(delegate.first(), selector)

    override suspend fun last(): BrowserLocator = JcefSessionBrowserLocator(delegate.last(), selector)

    override suspend fun filter(hasText: String): BrowserLocator =
        JcefSessionBrowserLocator(delegate.filter { it.text.contains(hasText) }, selector)

    private suspend fun withNotFound(action: String, block: suspend () -> OperationResult): OperationResult =
        try {
            block()
        } catch (e: ElementNotFoundException) {
            OperationResult.Failure(action, "element not found: $selector", recoverable = false)
        } catch (e: Exception) {
            OperationResult.Failure(action, e.message ?: e::class.simpleName.orEmpty())
        }
}

private fun xyz.kbrowser.webview.OperationResult.toCore(): OperationResult = when (this) {
    is xyz.kbrowser.webview.OperationResult.Success ->
        OperationResult.Success(action, verified, detail)
    is xyz.kbrowser.webview.OperationResult.Failure ->
        OperationResult.Failure(action, reason, recoverable)
    xyz.kbrowser.webview.OperationResult.Acknowledged ->
        OperationResult.Acknowledged
}

private fun KeyboardKey.toKBrowser(): xyz.kbrowser.webview.KeyboardKey =
    xyz.kbrowser.webview.KeyboardKey.valueOf(this.name)
