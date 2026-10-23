package xyz.mederi.browser

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import xyz.mederi.browser.BrowserConstants
import xyz.mederi.browser.BrowserControl
import xyz.mederi.browser.bidi.OperationResult

/**
 * 通用浏览器操作工具集（基于 BrowserControl 抽象，jcef/camoufox 后端自动适配），
 * 由 BrowserOperator 直接装配。
 */

private fun OperationResult.toText(action: String): String = when (this) {
    is OperationResult.Success -> "$action=ok${if (detail.isNotBlank()) ": $detail" else ""}"
    is OperationResult.Failure -> "$action=FAIL: $reason"
    is OperationResult.Acknowledged -> "$action=ok"
}

private suspend fun safeCall(action: String, block: suspend () -> OperationResult): String =
    try {
        block().toText(action)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        "$action=FAIL: ${e.message}"
    }

@Serializable
data class NavigateArgs(
    @LLMDescription("URL to navigate to")
    val url: String = ""
)

class NavigateTool(private val control: BrowserControl) : SimpleTool<NavigateArgs>(
    argsType = typeToken<NavigateArgs>(),
    name = BrowserConstants.ToolNames.NAVIGATE,
    description = "Navigate the browser to a URL."
) {
    override suspend fun execute(args: NavigateArgs): String = safeCall(name) { control.navigate(args.url) }
}

@Serializable
data class ClickArgs(
    @LLMDescription("Element refid from a11y snapshot")
    val elementRef: String = ""
)

class ClickTool(private val control: BrowserControl) : SimpleTool<ClickArgs>(
    argsType = typeToken<ClickArgs>(),
    name = BrowserConstants.ToolNames.CLICK,
    description = "Click an element identified by its refid from the a11y snapshot."
) {
    override suspend fun execute(args: ClickArgs): String = safeCall(name) { control.click(args.elementRef) }
}

@Serializable
data class TypeArgs(
    @LLMDescription("Element refid")
    val elementRef: String = "",
    @LLMDescription("Text to type")
    val text: String = ""
)

class TypeTool(private val control: BrowserControl) : SimpleTool<TypeArgs>(
    argsType = typeToken<TypeArgs>(),
    name = BrowserConstants.ToolNames.TYPE,
    description = "Type text into an element identified by its refid."
) {
    override suspend fun execute(args: TypeArgs): String = safeCall(name) { control.type(args.elementRef, args.text) }
}

@Serializable
data class ScrollArgs(
    @LLMDescription("Optional element refid. Leave EMPTY to scroll the whole page/viewport; provide a refid only to scroll a specific scrollable element.")
    val elementRef: String = "",
    @LLMDescription("Horizontal scroll delta")
    val deltaX: Int = 0,
    @LLMDescription("Vertical scroll delta")
    val deltaY: Int = 0
)

class ScrollTool(private val control: BrowserControl) : SimpleTool<ScrollArgs>(
    argsType = typeToken<ScrollArgs>(),
    name = BrowserConstants.ToolNames.SCROLL,
    description = "Scroll the page (leave elementRef empty) or a specific element (provide its refid) by the given horizontal/vertical deltas."
) {
    override suspend fun execute(args: ScrollArgs): String =
        safeCall(name) {
            // 空 refid 走页面级 scrollPage，非空走元素级 scroll——空串永不再进入 refid 查找
            if (args.elementRef.isBlank()) control.scrollPage(args.deltaX, args.deltaY)
            else control.scroll(args.elementRef, args.deltaX, args.deltaY)
        }
}

@Serializable
data class WaitForArgs(
    @LLMDescription("CSS selector to wait for")
    val selector: String = ""
)

class WaitForTool(private val control: BrowserControl) : SimpleTool<WaitForArgs>(
    argsType = typeToken<WaitForArgs>(),
    name = BrowserConstants.ToolNames.WAIT_FOR,
    description = "Wait until the element matching the CSS selector appears or becomes visible."
) {
    override suspend fun execute(args: WaitForArgs): String = safeCall(name) { control.waitFor(args.selector) }
}

@Serializable
data class TabsArgs(
    @LLMDescription("Tab action: NEW/CLOSE/LIST/SELECT")
    val action: String = "",
    @LLMDescription("Tab id for CLOSE/SELECT")
    val tabId: String = ""
)

class TabsTool(private val control: BrowserControl) : SimpleTool<TabsArgs>(
    argsType = typeToken<TabsArgs>(),
    name = BrowserConstants.ToolNames.TABS,
    description = "Manage browser tabs: NEW opens a tab, CLOSE closes the given tab id, LIST lists all tab ids, SELECT switches to the given tab id."
) {
    override suspend fun execute(args: TabsArgs): String = safeCall(name) {
        when (args.action.uppercase()) {
            "NEW" -> control.openTab()
            "CLOSE" -> control.closeTab(args.tabId)
            "LIST" -> {
                val tabIds = control.listTabs()
                OperationResult.Success(name, detail = tabIds.joinToString(","))
            }
            "SELECT" -> control.selectTab(args.tabId)
            else -> OperationResult.Failure(name, "unknown tabAction: ${args.action}")
        }
    }
}

/**
 * 装配浏览器物理操作工具集。
 */
fun buildBrowserOperatorTools(control: BrowserControl): List<ToolBase<*, *>> = listOf(
    NavigateTool(control),
    ClickTool(control),
    TypeTool(control),
    ScrollTool(control),
    WaitForTool(control),
    TabsTool(control)
)