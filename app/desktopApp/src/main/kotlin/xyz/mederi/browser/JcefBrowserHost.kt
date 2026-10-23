package xyz.mederi.browser

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import xyz.kbrowser.webview.KBWebView
import xyz.kbrowser.webview.LoadingState
import xyz.mederi.theme.MederiColors
import xyz.mederi.ui.browser.UiBrowserHost
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * JCEF 内置浏览器宿主（desktop 端 [UiBrowserHost] 实现）。
 *
 * 会话隔离与多 Tab 架构：
 * 1. **会话级隔离**：按会话 ID 维护独立的 [BrowserSessionState]；每个会话拥有各自的 Tab 列表与选中状态。
 * 2. **后台常驻运行**：切换会话时，只换绑当前视口渲染的会话容器；所有后台会话的 [BrowserTabState]（底层 [KBPage]）
 *    在 Chromium 进程中持续保持活跃，网络与 JS 脚本不受切换干扰。
 * 3. **子 Agent 闭环控制**：子 Agent 通过 [createControl] 获取绑定到所属会话的 [JcefSessionBrowserControl]，
 *    具备自主创建、查询与关闭不再使用 Tab 的能力，及时回收资源。
 */
class JcefBrowserHost(private val browser: xyz.kbrowser.webview.KBrowser) : UiBrowserHost {

    private val tabIdGenerator = AtomicLong(1L)

    /** 按会话 ID 隔离的状态容器字典。 */
    private val sessions = ConcurrentHashMap<String, BrowserSessionState>()

    /** 类级主线程协程作用域，shutdown 时取消。 */
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override val isAvailable: Boolean get() = true

    internal fun getOrCreateSession(sessionId: String): BrowserSessionState {
        val effectiveId = sessionId.ifBlank { BrowserConstants.DEFAULT_SESSION_ID }
        return sessions.computeIfAbsent(effectiveId) { id ->
            BrowserSessionState(
                sessionId = id,
                browser = browser,
                scope = scope,
                tabIdGenerator = tabIdGenerator
            )
        }
    }

    @Composable
    override fun BrowserContent(
        sessionId: String,
        colors: MederiColors,
    ) {
        if (!isAvailable) {
            BrowserUnavailableView(colors = colors)
            return
        }

        val session = getOrCreateSession(sessionId)
        val tabs = session.tabs

        // 首次打开面板且无任何 tab 时，自动创建本会话首个标签页
        LaunchedEffect(session.sessionId, isAvailable, tabs.size) {
            if (isAvailable && tabs.isEmpty() && session.creatingInitialTab.compareAndSet(false, true)) {
                session.openTab(initialUrl = null, select = true)
                session.creatingInitialTab.set(false)
            }
        }

        val selectedTab = session.selectedTab

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
                selectedTabIndex = session.selectedTabIndex,
                colors = colors,
                onSelectTab = { index -> session.selectedTabIndex = index.coerceIn(0, (tabs.size - 1).coerceAtLeast(0)) },
                onCloseTab = { index ->
                    session.closeTabByIndex(index)
                    if (session.tabs.isEmpty()) {
                        scope.launch { session.openTab(initialUrl = null, select = true) }
                    }
                },
                onNewTab = {
                    scope.launch { session.openTab(initialUrl = null, select = true) }
                },
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

            // ── 4. 主内容区（网页视图 100% 全宽）──
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
                        // key(session.sessionId, selectedTab.id) 确保切换会话或 tab 时 SwingPanel 正确重绑定
                        key(session.sessionId, selectedTab.id) {
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

    /**
     * 为指定会话中的 AI 浏览器任务创建/获取控制端。
     */
    suspend fun createControl(taskId: String, sessionId: String = ""): BrowserControl {
        val session = getOrCreateSession(sessionId)
        if (session.tabs.isEmpty()) {
            session.openTab(initialUrl = null, select = true)
        }
        return JcefSessionBrowserControl(session, taskId)
    }

    /** 向后兼容的别名方法。 */
    suspend fun createAiTab(taskId: String, sessionId: String = ""): BrowserControl =
        createControl(taskId, sessionId)

    /** 在指定会话的新标签页中加载 HTML（Office 文档预览等）。 */
    override fun loadHtml(sessionId: String, title: String, html: String) {
        if (!isAvailable) {
            println("[JcefBrowserHost] JCEF browser runtime is not initialized; ignore loadHtml(title=$title).")
            return
        }
        val session = getOrCreateSession(sessionId)
        scope.launch {
            session.openTab(
                initialHtml = html,
                customTitle = title.takeIf { it.isNotBlank() },
                select = true,
            )
        }
    }

    /** 关闭并清理指定会话的所有 Tab。 */
    override fun closeSession(sessionId: String) {
        val effectiveId = sessionId.ifBlank { BrowserConstants.DEFAULT_SESSION_ID }
        sessions.remove(effectiveId)?.destroy()
    }

    /** 退出清理：关闭所有会话中的所有 Tab 并取消协程作用域。 */
    override fun shutdown() {
        sessions.values.forEach { it.destroy() }
        sessions.clear()
        scope.cancel()
    }
}
