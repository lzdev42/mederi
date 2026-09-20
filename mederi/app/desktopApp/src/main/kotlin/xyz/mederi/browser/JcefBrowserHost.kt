package xyz.mederi

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.ArrowRight
import compose.icons.feathericons.Globe
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.X
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import xyz.kbrowser.webview.JcefChecker
import xyz.kbrowser.webview.KBrowser
import xyz.kbrowser.webview.KBPage
import xyz.kbrowser.webview.KBWebView
import xyz.kbrowser.webview.LoadingState
import xyz.kbrowser.webview.initializeKBrowser
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.browser_back
import mederi.app.shared.generated.resources.browser_empty_hint
import mederi.app.shared.generated.resources.browser_forward
import mederi.app.shared.generated.resources.browser_go
import mederi.app.shared.generated.resources.browser_new_tab
import mederi.app.shared.generated.resources.browser_reload
import mederi.app.shared.generated.resources.browser_stop
import mederi.app.shared.generated.resources.browser_url_placeholder
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.ui.browser.UiBrowserHost
import xyz.mederi.theme.MederiColors
import java.io.File

/**
 * 内置浏览器 KBrowser 渲染模式总开关（实验性）：
 * false = 非 OSR（CEF 原生窗口渲染，支持 WebGL 满帧，但不能叠加 Compose 元素）；
 * true = OSR（离屏渲染，融入 Compose 视图树）。非 OSR 验证不佳时改回 true 即整体回退。
 *
 * 注意：KBrowser.useOsrMode 是进程级全局字段（后写覆盖），inkcompose 的 mermaid worker
 * 会为其后台页面写回 true；因此每个 tab 创建 newPage 前都要重新断言本开关值。
 * 进程级 CefApp（windowless_rendering_enabled / remote 模式）由最先执行
 * initializeKBrowser() 的一方定死，mermaid worker 若在浏览器之前初始化过，
 * 两种模式并存的行为未定义——这是本实验的已知风险点。
 */
private const val BROWSER_USE_OSR = false

/**
 * 内置 JCEF 浏览器宿主：一个 tab = 一个 [KBPage] 的轻量标签容器。
 *
 * - AI 任务启动 → [createAiTab] 新建专属 KBPage tab，返回绑定它的 [JCEFBrowserControl]；
 *   同时自动切到该 tab（用户能看到 AI 在做什么）。
 * - 用户可手动开新 tab / 关 tab；切 tab 不影响已绑定页面（AI 操作作用在绑定 KBPage 上）。
 * - KBrowser 初始化惰性（首次创建 tab 时），渲染模式由 [BROWSER_USE_OSR] 控制
 *   （KBrowser.initializeConfig 只覆盖全局字段，重复调用安全）。
 */
class JcefBrowserHost(
    private val kbrowserStorageDir: String = System.getProperty("java.io.tmpdir") + File.separator + "mederi-jcef",
) : UiBrowserHost {

    class BrowserTab(val id: Int, val page: KBPage, val taskId: String? = null) {
        var title by mutableStateOf("")
        var url by mutableStateOf("")
        var closed by mutableStateOf(false)
    }

    private val _tabs = mutableStateListOf<BrowserTab>()
    private var activeIndex by mutableStateOf(-1)
    private var nextTabId = 0

    private val initMutex = Mutex()
    private var initialized = false

    val tabs: List<BrowserTab> get() = _tabs

    override val isAvailable: Boolean get() = JcefChecker.isJcefAvailable

    /** 重新断言本宿主的渲染模式（mermaid worker 可能已把全局 useOsrMode 改写）。 */
    private fun applyRenderMode() {
        KBrowser.initializeConfig(kbrowserStorageDir, useOsr = BROWSER_USE_OSR)
    }

    /** 初始化 KBrowser（惰性、幂等、线程安全；JCEF 不可用返回 false）。 */
    private suspend fun ensureInit(): Boolean = initMutex.withLock {
        if (initialized) return@withLock true
        if (!JcefChecker.isJcefAvailable) return@withLock false
        File(kbrowserStorageDir).mkdirs()
        applyRenderMode()
        initializeKBrowser()
        initialized = true
        true
    }

    private fun bindTab(tab: BrowserTab) {
        tab.page.webView.onNewWindowRequest = { newUrl ->
            println("[JcefBrowserHost] onNewWindowRequest: $newUrl")
            kotlinx.coroutines.MainScope().launch {
                runCatching {
                    val newTab = createUserTab()
                    newTab.page.webView.loadUrl(newUrl)
                }.onFailure {
                    println("[JcefBrowserHost] 响应新窗口请求失败: ${it.message}")
                }
            }
        }
    }

    suspend fun createAiTab(taskId: String): JCEFBrowserControl {
        if (!ensureInit()) {
            throw IllegalStateException("JCEF 不可用：当前 JBR 缺少 jcef 支持")
        }
        return withContext(Dispatchers.Main) {
            applyRenderMode()
            val page = KBrowser.newPage()
            val tab = BrowserTab(nextTabId++, page, taskId = taskId)
            bindTab(tab)
            _tabs.add(tab)
            activeIndex = _tabs.size - 1
            JCEFBrowserControl(page = page)
        }
    }

    /**
     * 当前打开 tab 的快照（browser_info 用的 BrowserStatusSource）。
     * 主代理在后台协程调用 → 切 Main 读 Compose 状态（线程安全）。
     */
    suspend fun statusSnapshot(): List<xyz.mederi.browser.BrowserStatusSource.Instance> =
        withContext(Dispatchers.Main) {
            _tabs.filter { !it.closed }.map { tab ->
                xyz.mederi.browser.BrowserStatusSource.Instance(
                    id = "tab-${tab.id}",
                    taskId = tab.taskId,
                    url = tab.url,
                    title = tab.title
                )
            }
        }

    fun closeTab(tab: BrowserTab) {
        if (tab.closed) return
        tab.closed = true
        val idx = _tabs.indexOf(tab)
        if (idx >= 0) {
            _tabs.removeAt(idx)
            if (activeIndex == idx) {
                activeIndex = if (_tabs.isEmpty()) -1 else (idx - 1).coerceAtLeast(0)
            } else if (idx < activeIndex) {
                activeIndex--
            }
        }
        runCatching { tab.page.close() }
    }

    fun selectTab(tab: BrowserTab) {
        val idx = _tabs.indexOf(tab)
        if (idx >= 0) activeIndex = idx
    }

    val activeTab: BrowserTab? get() = _tabs.getOrNull(activeIndex)

    /** 用户手动开 tab（UI 线程经协程调用）。 */
    suspend fun createUserTab(): BrowserTab {
        if (!ensureInit()) throw IllegalStateException("JCEF 不可用")
        return withContext(Dispatchers.Main) {
            applyRenderMode()
            val page = KBrowser.newPage()
            val tab = BrowserTab(nextTabId++, page)
            bindTab(tab)
            _tabs.add(tab)
            activeIndex = _tabs.size - 1
            tab
        }
    }

    override fun shutdown() {
        runCatching {
            _tabs.toList().forEach { it.page.close() }
            _tabs.clear()
            activeIndex = -1
            KBrowser.shutdown()
        }
    }

    override fun loadHtml(title: String, html: String) {
        kotlinx.coroutines.runBlocking(Dispatchers.Main) {
            if (!ensureInit()) return@runBlocking
            applyRenderMode()
            val page = KBrowser.newPage()
            // data URL 方式加载 HTML（避免依赖 KBPage.loadHtml 方法是否存在）
            val encoded = java.util.Base64.getEncoder().encodeToString(html.toByteArray(Charsets.UTF_8))
            page.webView.loadUrl("data:text/html;charset=UTF-8;base64,$encoded")
            val tab = BrowserTab(nextTabId++, page, taskId = null)
            bindTab(tab)
            _tabs.add(tab)
            activeIndex = _tabs.size - 1
        }
    }

    // ------------------------------------------------------------------

    // ------------------------------------------------------------------

    @Composable
    override fun BrowserContent(colors: MederiColors) {
        val scope = rememberCoroutineScope()
        Column(modifier = Modifier.fillMaxSize().background(colors.surfaceWorkspace)) {
            BrowserTabBar(
                tabs = _tabs,
                activeIndex = activeIndex,
                colors = colors,
                onSelect = { selectTab(it) },
                onClose = { closeTab(it) },
                onNewTab = {
                    scope.launch {
                        runCatching { createUserTab() }.onFailure {
                            println("[JcefBrowserHost] 新建 tab 失败: ${it.message}")
                        }
                    }
                }
            )

            HorizontalDivider(color = colors.divider, thickness = 1.dp)

            val current = activeTab
            if (current == null) {
                EmptyBrowserPlaceholder(colors)
            } else {
                BrowserToolbar(
                    tab = current,
                    colors = colors,
                    onNavigate = { rawUrl ->
                        val targetUrl = normalizeUrl(rawUrl)
                        println("[JcefBrowserHost] onNavigate: raw='$rawUrl', target='$targetUrl'")
                        if (targetUrl.isNotEmpty()) {
                            current.page.webView.loadUrl(targetUrl)
                        }
                    }
                )

                HorizontalDivider(color = colors.divider, thickness = 1.dp)

                Box(modifier = Modifier.fillMaxSize()) {
                    KBWebView(webView = current.page.webView, modifier = Modifier.fillMaxSize())

                    // 响应式监听当前页面标题并更新 tab
                    LaunchedEffect(current) {
                        current.page.webView.currentTitle.collect { title ->
                            if (!title.isNullOrBlank()) {
                                current.title = title
                            }
                        }
                    }

                    // URL 跟踪（供 browser_info 状态快照）
                    LaunchedEffect(current) {
                        current.page.webView.currentUrl.collect { url ->
                            if (!url.isNullOrBlank()) {
                                current.url = url
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserTabBar(
    tabs: List<JcefBrowserHost.BrowserTab>,
    activeIndex: Int,
    colors: MederiColors,
    onSelect: (JcefBrowserHost.BrowserTab) -> Unit,
    onClose: (JcefBrowserHost.BrowserTab) -> Unit,
    onNewTab: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceSidebar)
            .horizontalScroll(rememberScrollState())
            .padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEachIndexed { index, tab ->
            val selected = index == activeIndex
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                    .background(if (selected) colors.surfaceCard else colors.surfaceSidebar)
                    .clickable { onSelect(tab) }
                    .padding(start = 12.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = tab.title.ifEmpty { stringResource(Res.string.browser_new_tab) },
                    color = if (selected) colors.textPrimary else colors.textSecondary,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 140.dp)
                )
                Text(
                    text = "×",
                    color = colors.textMuted,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { onClose(tab) }
                        .padding(horizontal = 4.dp)
                )
            }
        }
        Text(
            text = "+",
            color = colors.textSecondary,
            fontSize = 16.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onNewTab)
                .padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun EmptyBrowserPlaceholder(colors: MederiColors) {
    Box(
        modifier = Modifier.fillMaxSize().background(colors.surfaceWorkspace),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(Res.string.browser_empty_hint),
            color = colors.textMuted,
            fontSize = 12.sp
        )
    }
}

/**
 * 规范化用户输入的 URL，自动补全协议：
 * - 已经包含协议头（http://, https://, about:, file: 等）保持原样；
 * - 本地开发地址（localhost, 127.0.0.1 等）自动补全 http://；
 * - 其余常规网址补全 https://。
 */
fun normalizeUrl(input: String): String {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return ""
    if (trimmed.contains("://") || trimmed.startsWith("about:") || trimmed.startsWith("data:") || trimmed.startsWith("file:") || trimmed.startsWith("javascript:")) {
        return trimmed
    }
    val lower = trimmed.lowercase()
    if (lower.startsWith("localhost") || lower.startsWith("127.0.0.1") || lower.startsWith("0.0.0.0")) {
        return "http://$trimmed"
    }
    return "https://$trimmed"
}

@Composable
private fun BrowserToolbar(
    tab: JcefBrowserHost.BrowserTab,
    colors: MederiColors,
    onNavigate: (String) -> Unit
) {
    val webView = tab.page.webView
    val canGoBack by webView.canGoBack.collectAsState()
    val canGoForward by webView.canGoForward.collectAsState()
    val loadingState by webView.loadingState.collectAsState()
    val progress by webView.progress.collectAsState()
    val currentUrl by webView.currentUrl.collectAsState()

    val isLoading = loadingState is LoadingState.Loading || loadingState is LoadingState.Initializing

    var textInput by remember(tab) { mutableStateOf(currentUrl ?: "") }
    var isFocused by remember { mutableStateOf(false) }

    LaunchedEffect(currentUrl, isFocused) {
        if (!isFocused) {
            textInput = currentUrl ?: ""
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceSidebar)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // 后退按钮
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .then(
                        if (canGoBack) Modifier.clickable {
                            println("[JcefBrowserHost] 后退")
                            webView.goBack()
                        }
                        else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = FeatherIcons.ArrowLeft,
                    contentDescription = stringResource(Res.string.browser_back),
                    tint = if (canGoBack) colors.textPrimary else colors.textMuted.copy(alpha = 0.35f),
                    modifier = Modifier.size(15.dp)
                )
            }

            // 前进按钮
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .then(
                        if (canGoForward) Modifier.clickable {
                            println("[JcefBrowserHost] 前进")
                            webView.goForward()
                        }
                        else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = FeatherIcons.ArrowRight,
                    contentDescription = stringResource(Res.string.browser_forward),
                    tint = if (canGoForward) colors.textPrimary else colors.textMuted.copy(alpha = 0.35f),
                    modifier = Modifier.size(15.dp)
                )
            }

            // 刷新 / 停止按钮
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        if (isLoading) {
                            println("[JcefBrowserHost] 停止加载")
                            webView.stopLoading()
                        } else {
                            println("[JcefBrowserHost] 重新加载")
                            webView.reload()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isLoading) FeatherIcons.X else FeatherIcons.RefreshCw,
                    contentDescription = if (isLoading) stringResource(Res.string.browser_stop) else stringResource(Res.string.browser_reload),
                    tint = colors.textSecondary,
                    modifier = Modifier.size(14.dp)
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            // 地址输入栏
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.surfaceWorkspace)
                    .border(
                        width = 1.dp,
                        color = if (isFocused) colors.accentPrimary else colors.surfaceCardBorder,
                        shape = RoundedCornerShape(6.dp)
                    )
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = FeatherIcons.Globe,
                    contentDescription = null,
                    tint = colors.textMuted,
                    modifier = Modifier.size(13.dp)
                )

                Spacer(modifier = Modifier.width(6.dp))

                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (textInput.isEmpty() && !isFocused) {
                        Text(
                            text = stringResource(Res.string.browser_url_placeholder),
                            color = colors.textMuted,
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                    }

                    BasicTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = colors.textPrimary,
                            fontSize = 12.sp
                        ),
                        cursorBrush = SolidColor(colors.accentPrimary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { onNavigate(textInput) }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { isFocused = it.isFocused }
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                                    onNavigate(textInput)
                                    true
                                } else {
                                    false
                                }
                            }
                    )
                }

                if (textInput.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { onNavigate(textInput) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = FeatherIcons.ArrowRight,
                            contentDescription = stringResource(Res.string.browser_go),
                            tint = colors.accentPrimary,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }
        }

        // 加载进度条（高度 2dp）
        if (isLoading && progress in 0.01f..0.99f) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = colors.accentPrimary,
                trackColor = Color.Transparent
            )
        } else {
            Spacer(modifier = Modifier.height(2.dp))
        }
    }
}

