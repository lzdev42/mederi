package xyz.mederi.browser.bidi

import xyz.mederi.browser.BrowserLocator

/**
 * 把 [BiDiLocator] 适配成 [BrowserLocator]（Drill 契约）。
 *
 * 与 JcefSessionBrowserLocator 同模式：委托 BiDiLocator，把"元素未找到"
 * （[ElementNotFoundException]）转成 [OperationResult.Failure]（recoverable=false），
 * 其余异常转成普通 Failure，避免向 Drill 抛异常。
 */
class BiDiBrowserLocator(
    private val delegate: BiDiLocator,
    private val selector: String
) : BrowserLocator {

    override suspend fun click(): OperationResult =
        withNotFound("locator.click") {
            delegate.click()
            OperationResult.Acknowledged
        }

    override suspend fun fill(text: String): OperationResult =
        withNotFound("locator.fill") { delegate.fill(text) }

    override suspend fun hover(): OperationResult =
        withNotFound("locator.hover") {
            delegate.hover()
            OperationResult.Acknowledged
        }

    override suspend fun getText(): String =
        runCatching { delegate.getText() }.getOrDefault("")

    override suspend fun getAttribute(name: String): String? =
        runCatching { delegate.getAttribute(name) }.getOrNull()

    override suspend fun count(): Int = delegate.count()

    override suspend fun isVisible(): Boolean = delegate.isVisible()

    override suspend fun nth(i: Int): BrowserLocator =
        BiDiBrowserLocator(delegate.nth(i), selector)

    override suspend fun first(): BrowserLocator =
        BiDiBrowserLocator(delegate.first(), selector)

    override suspend fun last(): BrowserLocator =
        BiDiBrowserLocator(delegate.last(), selector)

    override suspend fun filter(hasText: String): BrowserLocator =
        BiDiBrowserLocator(delegate.filter { it.text.contains(hasText) }, selector)

    private suspend fun withNotFound(action: String, block: suspend () -> OperationResult): OperationResult =
        try {
            block()
        } catch (e: ElementNotFoundException) {
            OperationResult.Failure(action, "element not found: $selector", recoverable = false)
        } catch (e: Exception) {
            OperationResult.Failure(action, e.message ?: e::class.simpleName.orEmpty())
        }
}