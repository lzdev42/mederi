package xyz.mederi.browser

import xyz.mederi.browser.bidi.BiDiBrowser
import xyz.mederi.browser.bidi.BiDiBrowserLocator
import xyz.mederi.browser.bidi.BiDiPage
import xyz.mederi.browser.bidi.CamoufoxConfig
import xyz.mederi.browser.bidi.KeyboardKey
import xyz.mederi.browser.bidi.OperationResult
import xyz.mederi.browser.bidi.SnapshotMode
import java.nio.file.Path

/**
 * [BrowserControl] 的 Camoufox 实现：用 BiDi 协议（从 BrowserPilot 移植）控制 Camoufox。
 *
 * Camoufox 路径与 config 由 BrowserSettingsManager 提供，不提供下载能力。
 * 单页模型：启动后使用第一个 page，所有操作都作用在当前页。
 */
class BiDiBrowserControl(
    private val binaryPath: String,
    private val profilePath: Path,
    private val headless: Boolean = true,
    private val config: CamoufoxConfig = CamoufoxConfig(),
    private val extraArgs: List<String> = emptyList(),
    // 已展开的 Firefox proxy prefs（透传给 BiDiBrowser，由上游把 ProxyConfig 展开成 Map）
    private val proxyPrefs: Map<String, Any> = emptyMap()
) : BrowserControl {

    private var browser: BiDiBrowser? = null
    private var page: BiDiPage? = null

    override suspend fun start() {
        if (browser?.isAlive == true) return
        val b = BiDiBrowser(
            binaryPath = binaryPath,
            profilePath = profilePath,
            headless = headless,
            extraArgs = extraArgs,
            config = config,
            proxyPrefs = proxyPrefs
        )
        b.start()
        browser = b
        page = b.pages.firstOrNull()
    }

    override suspend fun close() {
        browser?.close()
        browser = null
        page = null
    }

    override suspend fun getCurrentUrl(): String = page?.getUrl().orEmpty()

    override suspend fun getTitle(): String = page?.getTitle().orEmpty()

    override suspend fun navigate(url: String): OperationResult {
        val p = requirePage()
        return try {
            p.navigate(url)
            OperationResult.Success("navigate", detail = url)
        } catch (e: Exception) {
            OperationResult.Failure("navigate", e.message ?: e::class.simpleName.orEmpty())
        }
    }

    override suspend fun click(elementRef: String): OperationResult =
        requirePage().click(elementRef)

    override suspend fun clickByCoordinates(x: Int, y: Int): OperationResult =
        requirePage().clickByCoordinates(x, y)

    override suspend fun type(elementRef: String, text: String): OperationResult {
        val p = requirePage()
        // BiDi 的 type(text) 作用于当前聚焦元素——先点击目标元素获得焦点，再输入
        val focus = p.click(elementRef)
        if (!focus.success) return focus
        return try {
            p.type(text)
            OperationResult.Success("type", detail = text)
        } catch (e: Exception) {
            OperationResult.Failure("type", e.message ?: e::class.simpleName.orEmpty())
        }
    }

    override suspend fun scroll(elementRef: String, deltaX: Int, deltaY: Int): OperationResult =
        requirePage().scroll(elementRef, deltaX, deltaY)

    override suspend fun scrollPage(deltaX: Int, deltaY: Int): OperationResult {
        val p = requirePage()
        // 视口中心（文档坐标）：取当前滚动偏移 + 视口一半，保证页面已滚动后仍落在视口内
        val scroll = p.getScroll()
        val vw = p.evaluateJavascript("String(window.innerWidth)").trim().toDoubleOrNull() ?: 1280.0
        val vh = p.evaluateJavascript("String(window.innerHeight)").trim().toDoubleOrNull() ?: 720.0
        return p.scrollByCoordinates((scroll.x + vw / 2).toInt(), (scroll.y + vh / 2).toInt(), deltaX, deltaY)
    }

    override suspend fun press(key: KeyboardKey): OperationResult {
        return try {
            requirePage().press(key)
            OperationResult.Success("press", detail = key.name)
        } catch (e: Exception) {
            OperationResult.Failure("press", e.message ?: e::class.simpleName.orEmpty())
        }
    }

    override suspend fun snapshot(): PageSnapshot {
        val p = requirePage()
        val snap = p.snapshot(SnapshotMode.CLEAN)
        return PageSnapshot(
            url = p.getUrl().orEmpty(),
            title = p.getTitle().orEmpty(),
            yaml = snap.yaml,
            rawTree = snap.rawTree
        )
    }

    override suspend fun screenshot(): ByteArray? {
        return try {
            requirePage().screenshot()
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun evaluateJavascript(js: String): String =
        requirePage().evaluateJavascript(js)

    override suspend fun scrollByCoordinates(
        originX: Int,
        originY: Int,
        deltaX: Int,
        deltaY: Int
    ): OperationResult =
        requirePage().scrollByCoordinates(originX, originY, deltaX, deltaY)

    override suspend fun uploadFile(elementRef: String, filePaths: List<String>): OperationResult {
        requirePage().uploadFile(elementRef, filePaths)
        return OperationResult.Success("upload_file", detail = filePaths.joinToString(","))
    }

    override suspend fun locator(selector: String): BrowserLocator =
        BiDiBrowserLocator(requirePage().locator(selector), selector)

    override suspend fun openTab(): OperationResult {
        val b = requireBrowser()
        val newP = b.newPage(null)
        page = newP
        return OperationResult.Success("open_tab", detail = newP.contextId)
    }

    override suspend fun closeTab(tabId: String): OperationResult {
        val b = requireBrowser()
        val target = b.pages.find { it.contextId == tabId }
            ?: return OperationResult.Failure("close_tab", "tab not found: $tabId")
        b.closePage(target.contextId)
        if (page == target) page = b.pages.firstOrNull()
        return OperationResult.Success("close_tab", detail = tabId)
    }

    override suspend fun listTabs(): List<String> =
        requireBrowser().pages.map { it.contextId }

    override suspend fun selectTab(tabId: String): OperationResult {
        val b = requireBrowser()
        val target = b.pages.find { it.contextId == tabId }
            ?: return OperationResult.Failure("select_tab", "tab not found: $tabId")
        page = target
        return OperationResult.Success("select_tab", detail = tabId)
    }

    override suspend fun waitFor(selector: String, timeoutMs: Long): OperationResult {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                if (requirePage().locator(selector).count() > 0) {
                    return OperationResult.Success("wait_for", detail = selector)
                }
            } catch (_: Exception) {}
            kotlinx.coroutines.delay(500)
        }
        return OperationResult.Failure("wait_for", "timeout waiting for: $selector")
    }

    private fun requireBrowser(): BiDiBrowser =
        browser ?: error("Browser not started")

    private fun requirePage(): BiDiPage {
        return page ?: error("Browser not started — call start() first.")
    }
}
