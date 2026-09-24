package xyz.mederi.browser

import xyz.mederi.browser.bidi.KeyboardKey
import xyz.mederi.browser.bidi.OperationResult

/** 脚本化元素状态：selector → 元素。 */
data class FakeElement(
    val text: String = "",
    val visible: Boolean = true,
    val count: Int = 1,
    val attributes: Map<String, String> = emptyMap()
)

/**
 * 内存脚本化的 [BrowserControl]，供 Drill / Operator 相关 jvmTest 使用。
 *
 * - 既有方法：返回 Success / Acknowledged / 固定快照，不触达真实浏览器；
 * - 新契约（locator / evaluateJavascript / tab / upload / scrollByCoordinates / waitFor）：
 *   真实脚本化实现，ST1 DrillExecutor 测试可直接驱动：
 *   - [elements]：selector → 元素状态（getText / count / isVisible / getAttribute / click 可见性判断）
 *   - [jsResults]：JS 内容（精确或子串匹配）→ 预设返回值
 *   - [tabs] / [selectedTab]：内存标签页列表
 *   - [log]：操作日志，测试可断言调用序列
 */
class FakeBrowserControl(
    var currentUrl: String = "https://example.com",
    var title: String = "Example Page",
    elements: Map<String, FakeElement> = emptyMap(),
    jsResults: Map<String, String> = emptyMap()
) : BrowserControl {

    /** selector → 元素状态。 */
    val elements: MutableMap<String, FakeElement> = mutableMapOf()

    /** JS 内容 → 预设返回值（精确匹配优先，再子串匹配）。 */
    val jsResults: MutableMap<String, String> = mutableMapOf()

    /** 内存标签页列表，初始含 tab-1。 */
    val tabs: MutableList<String> = mutableListOf("tab-1")

    /** 当前选中标签页。 */
    var selectedTab: String = "tab-1"

    /** 操作日志。 */
    val log: MutableList<String> = mutableListOf()

    init {
        this.elements.putAll(elements)
        this.jsResults.putAll(jsResults)
    }

    fun setElement(selector: String, element: FakeElement) {
        elements[selector] = element
    }

    fun setElementText(selector: String, text: String) {
        elements[selector] = elements[selector]?.copy(text = text) ?: FakeElement(text = text)
    }

    fun registerJs(js: String, result: String) {
        jsResults[js] = result
    }

    // ── 既有方法：脚本化固定行为 ──

    override suspend fun start() {
        log += "start"
    }

    override suspend fun close() {
        log += "close"
    }

    override suspend fun getCurrentUrl(): String = currentUrl

    override suspend fun getTitle(): String = title

    override suspend fun navigate(url: String): OperationResult {
        currentUrl = url
        log += "navigate $url"
        return OperationResult.Success("navigate", detail = url)
    }

    override suspend fun click(elementRef: String): OperationResult {
        log += "click $elementRef"
        return OperationResult.Success("click", detail = elementRef)
    }

    override suspend fun clickByCoordinates(x: Int, y: Int): OperationResult {
        log += "clickByCoordinates $x,$y"
        return OperationResult.Success("clickByCoordinates", detail = "$x,$y")
    }

    override suspend fun type(elementRef: String, text: String): OperationResult {
        log += "type $elementRef=$text"
        return OperationResult.Success("type", detail = text)
    }

    override suspend fun scroll(elementRef: String, deltaX: Int, deltaY: Int): OperationResult =
        OperationResult.Success("scroll", detail = "$deltaX,$deltaY")

    override suspend fun press(key: KeyboardKey): OperationResult =
        OperationResult.Success("press", detail = key.name)

    override suspend fun snapshot(): PageSnapshot = PageSnapshot(
        url = currentUrl,
        title = title,
        yaml = "url: $currentUrl\ntitle: $title",
        rawTree = null
    )

    override suspend fun screenshot(): ByteArray? = FIXED_SCREENSHOT

    // ── 新契约：真实脚本化实现（ST1 DrillExecutor 测试可用）──

    override suspend fun locator(selector: String): BrowserLocator = FakeLocator(this, selector)

    override suspend fun evaluateJavascript(js: String): String {
        jsResults[js]?.let { return it }
        jsResults.entries.firstOrNull { js.contains(it.key) }?.let { return it.value }
        log += "evaluateJavascript $js"
        return ""
    }

    override suspend fun openTab(): OperationResult {
        val id = "tab-${tabs.size + 1}"
        tabs += id
        log += "openTab $id"
        return OperationResult.Success("openTab", detail = id)
    }

    override suspend fun closeTab(tabId: String): OperationResult {
        tabs.remove(tabId)
        log += "closeTab $tabId"
        return OperationResult.Success("closeTab", detail = tabId)
    }

    override suspend fun listTabs(): List<String> = tabs.toList()

    override suspend fun selectTab(tabId: String): OperationResult {
        selectedTab = tabId
        log += "selectTab $tabId"
        return OperationResult.Success("selectTab", detail = tabId)
    }

    override suspend fun uploadFile(elementRef: String, filePaths: List<String>): OperationResult {
        log += "uploadFile $elementRef ${filePaths.joinToString(",")}"
        return OperationResult.Success("uploadFile", detail = filePaths.joinToString(","))
    }

    override suspend fun scrollByCoordinates(originX: Int, originY: Int, deltaX: Int, deltaY: Int): OperationResult {
        log += "scrollByCoordinates $originX,$originY,$deltaX,$deltaY"
        return OperationResult.Success("scrollByCoordinates", detail = "$originX,$originY,$deltaX,$deltaY")
    }

    override suspend fun waitFor(selector: String, timeoutMs: Long): OperationResult {
        log += "waitFor $selector ${timeoutMs}ms"
        return OperationResult.Success("waitFor", detail = selector)
    }

    /**
     * 脚本化 [BrowserLocator]：所有查询委托给 owner 的 [elements] map，
     * nth/first/last/filter 返回带修饰后缀的新定位器（供断言区分）。
     */
    inner class FakeLocator(
        private val owner: FakeBrowserControl,
        private val selector: String
    ) : BrowserLocator {

        private fun element(): FakeElement = owner.elements[selector] ?: FakeElement()

        override suspend fun click(): OperationResult {
            val el = element()
            owner.log += "locator.click $selector"
            return if (el.visible) {
                OperationResult.Success("click", detail = selector)
            } else {
                OperationResult.Failure("click", "element not visible: $selector")
            }
        }

        override suspend fun fill(text: String): OperationResult {
            owner.log += "locator.fill $selector=$text"
            return OperationResult.Success("fill", detail = text)
        }

        override suspend fun hover(): OperationResult {
            owner.log += "locator.hover $selector"
            return OperationResult.Success("hover", detail = selector)
        }

        override suspend fun getText(): String = element().text

        override suspend fun getAttribute(name: String): String? = element().attributes[name]

        override suspend fun count(): Int = element().count

        override suspend fun isVisible(): Boolean = element().visible

        override suspend fun nth(i: Int): BrowserLocator = FakeLocator(owner, "$selector:nth($i)")

        override suspend fun first(): BrowserLocator = FakeLocator(owner, "$selector:first")

        override suspend fun last(): BrowserLocator = FakeLocator(owner, "$selector:last")

        override suspend fun filter(hasText: String): BrowserLocator = FakeLocator(owner, "$selector:hasText($hasText)")
    }

    companion object {
        val FIXED_SCREENSHOT: ByteArray = byteArrayOf(1, 2, 3, 4, 5)
    }
}