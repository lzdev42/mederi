package xyz.mederi.browser

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.kbrowser.webview.JsPromptResultCallback
import xyz.kbrowser.webview.JsResultCallback
import xyz.kbrowser.webview.KBPage
import xyz.kbrowser.webview.KBWebChromeClient
import xyz.kbrowser.webview.KBWebView
import xyz.kbrowser.webview.LoadingState
import xyz.kbrowser.webview.PermissionRequest
import xyz.mederi.DesktopBrowserRuntime
import xyz.mederi.theme.MederiColors
import xyz.mederi.ui.browser.UiBrowserHost
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * JCEF 内置浏览器宿主（desktop 端 [UiBrowserHost] 实现）。
 *
 * 标准简化版多标签浏览器：
 * 1. **弹窗转新 Tab**：拦截 `target="_blank"` / `window.open()`（[KBPage.onNewPage]），在当前标签右侧新建并选中标签页；
 * 2. **可横向滚动的多 Tab 栏**：支持任意数量标签横向滚动、固定右侧 `+` 按钮、打开面板自动创建首个标签、关闭最后标签自动补新标签；
 * 3. **智能地址栏（Omnibox）与导航栏**：支持协议自动补全（域名补 `https://`、本地地址补 `http://`）、非 URL 关键词走 **Bing 搜索**、
 *    后退/前进/刷新与停止合一按钮、顶部 2dp 加载进度条；
 * 4. **页面加载错误覆层**：遇到网络/证书/DNS 错误时展示可读错误页与重试按钮；
 * 5. **JS 对话框与文件上传**：桥接 `alert`/`confirm`/`prompt` 到 Mederi 弹窗组件，桥接 `<input type="file">` 到系统原生文件选择器；
 * 6. **页面缩放控制**：支持 50% ~ 200% 缩放调节与一键重置 100%。
 */
class JcefBrowserHost : UiBrowserHost {

    private val tabIdGenerator = AtomicLong(1L)

    /** 自维护的 tab 列表（KBrowser.pages 不会自动移除已关闭的 page）。 */
    private val tabs = mutableStateListOf<BrowserTabState>()

    /** 当前选中 tab 索引。 */
    private var selectedTabIndex by mutableIntStateOf(0)

    /** 防止首次打开时并发重复触发初始空白 tab 创建。 */
    private val creatingInitialTab = AtomicBoolean(false)

    /** 类级主线程协程作用域，shutdown 时取消。 */
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override val isAvailable: Boolean get() = DesktopBrowserRuntime.isInitialized

    @Composable
    override fun BrowserContent(colors: MederiColors) {
        if (!isAvailable) {
            BrowserUnavailableView(colors = colors)
            return
        }

        // 首次打开面板且无任何 tab 时，自动创建首个标签页
        LaunchedEffect(isAvailable, tabs.size) {
            if (isAvailable && tabs.isEmpty() && creatingInitialTab.compareAndSet(false, true)) {
                openTabInternal(initialUrl = null, select = true)
                creatingInitialTab.set(false)
            }
        }

        val selectedTab = if (tabs.isEmpty()) {
            null
        } else {
            tabs.getOrNull(selectedTabIndex.coerceIn(0, tabs.lastIndex))
        }

        val loadingState by (
            selectedTab?.page?.webView?.loadingState?.collectAsState()
                ?: remember { mutableStateOf<LoadingState>(LoadingState.Finished) }
            )
        val progress by (
            selectedTab?.page?.webView?.progress?.collectAsState()
                ?: remember { mutableStateOf(0f) }
            )
        val currentUrl by (
            selectedTab?.page?.webView?.currentUrl?.collectAsState()
                ?: remember { mutableStateOf<String?>(null) }
            )

        // 同步 WebView 背景色与当前 Mederi 主题一致，避免加载前黑屏闪烁
        LaunchedEffect(selectedTab?.id, colors.surfaceWorkspace) {
            selectedTab?.page?.backgroundColor = colors.surfaceWorkspace
        }

        // 页面加载完成或缩放比例变化时，向 DOM 注入缩放比例
        LaunchedEffect(selectedTab?.id, selectedTab?.zoomPercent, loadingState) {
            val tab = selectedTab ?: return@LaunchedEffect
            if (loadingState is LoadingState.Finished || tab.zoomPercent != 100) {
                applyZoomToWebView(tab.page.webView, tab.zoomPercent)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.surfaceWorkspace),
        ) {
            // ── 1. 可滚动多标签栏（34dp）──
            BrowserTabBar(
                tabs = tabs,
                selectedTabIndex = selectedTabIndex,
                colors = colors,
                onSelectTab = { index -> selectedTabIndex = index.coerceIn(0, (tabs.size - 1).coerceAtLeast(0)) },
                onCloseTab = ::closeTab,
                onNewTab = ::newTab,
            )

            // ── 2. 导航与智能地址栏（38dp）──
            BrowserNavToolbar(
                selectedTab = selectedTab,
                colors = colors,
                onNavigate = { rawInput ->
                    val target = resolveUrlOrSearch(rawInput)
                    if (target.isNotEmpty() && selectedTab != null) {
                        navigateTab(selectedTab, target)
                    }
                },
                onReload = {
                    val tab = selectedTab ?: return@BrowserNavToolbar
                    tab.dismissedError = null
                    val errUrl = (loadingState as? LoadingState.Error)?.failingUrl
                    if (errUrl != null && errUrl.isNotBlank()) {
                        tab.page.webView.loadUrl(errUrl)
                    } else {
                        tab.page.webView.reload()
                    }
                },
                onStop = {
                    selectedTab?.page?.webView?.stopLoading()
                },
                onZoomOut = {
                    selectedTab?.let { adjustZoom(it, deltaStep = -1) }
                },
                onZoomIn = {
                    selectedTab?.let { adjustZoom(it, deltaStep = 1) }
                },
                onZoomReset = {
                    selectedTab?.zoomPercent = 100
                },
            )

            // ── 3. 顶部 2dp 线性加载进度条 ──
            val isLoading = loadingState is LoadingState.Loading
            val animatedProgress by animateFloatAsState(
                targetValue = if (isLoading) progress.coerceIn(0.08f, 0.95f) else 0f,
                animationSpec = tween(durationMillis = 180),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(colors.divider),
            ) {
                if (isLoading && animatedProgress > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(animatedProgress)
                            .background(colors.accentPrimary),
                    )
                }
            }

            // ── 4. 主内容区（KBWebView 或错误状态页）──
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(colors.surfaceWorkspace),
            ) {
                if (selectedTab != null) {
                    val activeError = (loadingState as? LoadingState.Error)
                        ?.takeIf { it.errorCode != CEF_ERR_ABORTED && it != selectedTab.dismissedError }

                    if (activeError != null) {
                        BrowserErrorView(
                            error = activeError,
                            colors = colors,
                            onRetry = {
                                selectedTab.dismissedError = activeError
                                val retryUrl = activeError.failingUrl.takeIf { it.isNotBlank() }
                                    ?: currentUrl?.takeIf { it.isNotBlank() }
                                if (retryUrl != null) {
                                    selectedTab.page.webView.loadUrl(retryUrl)
                                } else {
                                    selectedTab.page.webView.reload()
                                }
                            },
                        )
                    } else {
                        // key(selectedTab.id) 确保切换 tab 时 SwingPanel 正确绑定对应 KBWebView
                        key(selectedTab.id) {
                            KBWebView(
                                webView = selectedTab.page.webView,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(colors.surfaceWorkspace),
                            )
                        }
                    }
                }
            }
        }

        // ── 5. 当前 Tab 的 JS 对话框（Alert / Confirm / Prompt）──
        selectedTab?.pendingJsDialog?.let { dialog ->
            BrowserJsDialogHost(dialog = dialog, colors = colors)
        }
    }

    /** 新建空白标签页并选中。 */
    private fun newTab() {
        if (!isAvailable) return
        scope.launch {
            openTabInternal(initialUrl = null, select = true)
        }
    }

    /**
     * 内部创建标签页：配置弹窗拦截、JS 对话框、文件选择器，按需加载初始 URL 或 HTML。
     */
    private suspend fun openTabInternal(
        initialUrl: String? = null,
        initialHtml: String? = null,
        customTitle: String? = null,
        select: Boolean = true,
        insertAfterIndex: Int? = null,
    ): BrowserTabState? {
        if (!isAvailable) return null
        val page = withContext(Dispatchers.Default) {
            DesktopBrowserRuntime.browser.newPage()
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
     * 为单个标签页绑定：
     * 1. `onNewPage`：拦截 `target="_blank"` / `window.open()` 转为当前标签右侧的新标签页；
     * 2. `setWebChromeClient`：拦截 `alert` / `confirm` / `prompt` 交由 Mederi 对话框渲染；
     * 3. `onFileDialog`：拦截 `<input type="file">` 调起桌面原生文件选择器。
     */
    private fun configureTabPage(tab: BrowserTabState) {
        // 1. 拦截弹窗 → 转为新 Tab
        tab.page.onNewPage = { popupUrl ->
            val trimmed = popupUrl.trim()
            scope.launch(Dispatchers.Main) {
                val parentIndex = tabs.indexOfFirst { it.id == tab.id }
                val insertAfter = if (parentIndex >= 0) parentIndex else selectedTabIndex
                openTabInternal(
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

    /** 触发指定标签页导航，同时重置已忽略的错误状态。 */
    private fun navigateTab(tab: BrowserTabState, targetUrl: String) {
        tab.dismissedError = null
        tab.page.webView.loadUrl(targetUrl)
    }

    /** 按 [ZOOM_STEPS] 档位调整标签页缩放比例。 */
    private fun adjustZoom(tab: BrowserTabState, deltaStep: Int) {
        val currentIdx = ZOOM_STEPS.indexOf(tab.zoomPercent).let { idx ->
            if (idx >= 0) idx else ZOOM_STEPS.indexOfFirst { it >= tab.zoomPercent }.takeIf { it >= 0 } ?: 5
        }
        val nextIdx = (currentIdx + deltaStep).coerceIn(0, ZOOM_STEPS.lastIndex)
        tab.zoomPercent = ZOOM_STEPS[nextIdx]
    }

    /** 关闭指定索引的标签页；若关闭后无剩余标签页，自动新建一个空白标签页。 */
    private fun closeTab(index: Int) {
        val tab = tabs.getOrNull(index) ?: return
        tab.pendingJsDialog?.cancel()
        tab.pendingJsDialog = null
        tab.page.close()
        tabs.removeAt(index)
        if (tabs.isEmpty()) {
            newTab()
        } else {
            selectedTabIndex = when {
                index < selectedTabIndex -> (selectedTabIndex - 1).coerceAtLeast(0)
                else -> selectedTabIndex.coerceAtMost(tabs.lastIndex)
            }
        }
    }

    /** 在新标签页中加载 HTML（Office 文档预览等）。 */
    override fun loadHtml(title: String, html: String) {
        if (!isAvailable) {
            println("[JcefBrowserHost] JCEF browser runtime is not initialized; ignore loadHtml(title=$title).")
            return
        }
        scope.launch {
            openTabInternal(
                initialHtml = html,
                customTitle = title.takeIf { it.isNotBlank() },
                select = true,
            )
        }
    }

    /** 退出清理：取消所有挂起的 JS 弹窗、关闭所有 tab 并取消协程作用域。 */
    override fun shutdown() {
        tabs.forEach { tab ->
            tab.pendingJsDialog?.cancel()
            tab.pendingJsDialog = null
            runCatching { tab.page.close() }
        }
        tabs.clear()
        scope.cancel()
    }
}
