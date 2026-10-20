package xyz.mederi.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.kbrowser.webview.JsPromptResultCallback
import xyz.kbrowser.webview.JsResultCallback
import xyz.kbrowser.webview.KBWebChromeClient
import xyz.kbrowser.webview.PermissionRequest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 单个会话维度的浏览器状态容器。
 *
 * 核心设计：
 * 1. 会话硬隔离：每个 [sessionId] 拥有独立的 tab 列表与选中状态。
 * 2. 后台常驻：即使当前 UI 切至其他会话，本会话的所有 [BrowserTabState]（底层 [KBPage]）
 *    依然在 CEF 引擎后台正常运行，网络/JS/自动化不受影响。
 * 3. 动态生命周期：支持通过 [openTab]、[closeTab]、[selectTab] 管理多 Tab。
 */
internal class BrowserSessionState(
    val sessionId: String,
    private val browser: xyz.kbrowser.webview.KBrowser,
    private val scope: CoroutineScope,
    private val tabIdGenerator: AtomicLong,
) {
    /** 本会话自维护的 tab 列表。 */
    val tabs = mutableStateListOf<BrowserTabState>()

    /** 本会话当前选中的 tab 索引。 */
    var selectedTabIndex by mutableIntStateOf(0)

    /** 防止初次打开面板并发重复触发初始空白 tab 创建。 */
    val creatingInitialTab = AtomicBoolean(false)

    /** 本会话当前激活的 tab。 */
    val selectedTab: BrowserTabState?
        get() = if (tabs.isEmpty()) null else tabs.getOrNull(selectedTabIndex.coerceIn(0, tabs.lastIndex))

    /**
     * 在本会话中创建并打开一个新标签页。
     */
    suspend fun openTab(
        initialUrl: String? = null,
        initialHtml: String? = null,
        customTitle: String? = null,
        select: Boolean = true,
        insertAfterIndex: Int? = null,
    ): BrowserTabState {
        val page = withContext(Dispatchers.Default) {
            browser.newPage()
        }
        return withContext(Dispatchers.Main) {
            val tab = BrowserTabState(
                id = tabIdGenerator.getAndIncrement(),
                page = page,
                customTitle = customTitle,
            )
            configureTabPage(tab)

            val targetIndex = if (insertAfterIndex != null && insertAfterIndex in 0..tabs.lastIndex) {
                insertAfterIndex + 1
            } else {
                tabs.size
            }
            tabs.add(targetIndex, tab)
            if (select) {
                selectedTabIndex = targetIndex
            }

            if (!initialHtml.isNullOrEmpty()) {
                page.webView.loadHtml(initialHtml)
            } else if (!initialUrl.isNullOrBlank()) {
                page.webView.loadUrl(initialUrl)
            }
            tab
        }
    }

    /**
     * 关闭指定 ID 的标签页（可传 tab 序号 id 或 page.uuid）。
     */
    fun closeTab(tabId: String): Boolean {
        val index = tabs.indexOfFirst { it.id.toString() == tabId || it.page.uuid == tabId }
        if (index < 0) return false
        closeTabByIndex(index)
        return true
    }

    /**
     * 关闭指定索引的标签页。若关闭后无剩余标签页，若为前台操作可由 UI 触发补新 tab。
     */
    fun closeTabByIndex(index: Int) {
        val tab = tabs.getOrNull(index) ?: return
        tab.pendingJsDialog?.cancel()
        tab.pendingJsDialog = null
        tab.page.close()
        tabs.removeAt(index)
        if (tabs.isNotEmpty()) {
            selectedTabIndex = when {
                index < selectedTabIndex -> (selectedTabIndex - 1).coerceAtLeast(0)
                else -> selectedTabIndex.coerceAtMost(tabs.lastIndex)
            }
        }
    }

    /**
     * 切换选中标签页。
     */
    fun selectTab(tabId: String): Boolean {
        val index = tabs.indexOfFirst { it.id.toString() == tabId || it.page.uuid == tabId }
        if (index < 0) return false
        selectedTabIndex = index
        return true
    }

    /**
     * 列出本会话的所有 tab 标识。
     */
    fun listTabIds(): List<String> = tabs.map { it.id.toString() }

    /**
     * 销毁本会话的所有 tab，释放底层 CEF 页面资源。
     */
    fun destroy() {
        tabs.forEach { tab ->
            tab.pendingJsDialog?.cancel()
            tab.pendingJsDialog = null
            runCatching { tab.page.close() }
        }
        tabs.clear()
        selectedTabIndex = 0
    }

    private fun configureTabPage(tab: BrowserTabState) {
        // 1. 拦截弹窗 → 转为本会话右侧新 Tab
        tab.page.onNewPage = { popupUrl ->
            val trimmed = popupUrl.trim()
            scope.launch(Dispatchers.Main) {
                val parentIndex = tabs.indexOfFirst { it.id == tab.id }
                val insertAfter = if (parentIndex >= 0) parentIndex else selectedTabIndex
                openTab(
                    initialUrl = trimmed.takeIf { it.isNotEmpty() && it != "about:blank" },
                    select = true,
                    insertAfterIndex = insertAfter,
                )
            }
        }

        // 2. 拦截 JS Alert / Confirm / Prompt 与权限请求
        tab.page.webView.setWebChromeClient(
            object : KBWebChromeClient {
                override fun onJsAlert(url: String, message: String, callback: JsResultCallback) {
                    scope.launch(Dispatchers.Main) {
                        tab.pendingJsDialog?.cancel()
                        tab.pendingJsDialog = BrowserJsDialog.Alert(
                            url = url,
                            message = message,
                            callback = callback,
                            onResolved = { tab.pendingJsDialog = null },
                        )
                    }
                }

                override fun onJsConfirm(url: String, message: String, callback: JsResultCallback) {
                    scope.launch(Dispatchers.Main) {
                        tab.pendingJsDialog?.cancel()
                        tab.pendingJsDialog = BrowserJsDialog.Confirm(
                            url = url,
                            message = message,
                            callback = callback,
                            onResolved = { tab.pendingJsDialog = null },
                        )
                    }
                }

                override fun onJsPrompt(
                    url: String,
                    message: String,
                    defaultValue: String?,
                    callback: JsPromptResultCallback,
                ) {
                    scope.launch(Dispatchers.Main) {
                        tab.pendingJsDialog?.cancel()
                        tab.pendingJsDialog = BrowserJsDialog.Prompt(
                            url = url,
                            message = message,
                            defaultValue = defaultValue.orEmpty(),
                            callback = callback,
                            onResolved = { tab.pendingJsDialog = null },
                        )
                    }
                }

                override fun onPermissionRequest(request: PermissionRequest) {
                    runCatching { request.deny() }
                }
            }
        )

        // 3. 拦截 <input type="file"> 文件上传对话框
        tab.page.onFileDialog = { request, callback ->
            showNativeFileDialog(request, callback)
        }
    }
}
