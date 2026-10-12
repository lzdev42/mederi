package xyz.mederi.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertCircle
import compose.icons.feathericons.ChevronLeft
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Globe
import compose.icons.feathericons.Lock
import compose.icons.feathericons.Minus
import compose.icons.feathericons.Plus
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.Search
import compose.icons.feathericons.X
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.browser_back
import mederi.app.shared.generated.resources.browser_clear_url
import mederi.app.shared.generated.resources.browser_close_tab
import mederi.app.shared.generated.resources.browser_forward
import mederi.app.shared.generated.resources.browser_js_alert_title
import mederi.app.shared.generated.resources.browser_js_confirm_title
import mederi.app.shared.generated.resources.browser_js_prompt_title
import mederi.app.shared.generated.resources.browser_load_error_title
import mederi.app.shared.generated.resources.browser_new_tab
import mederi.app.shared.generated.resources.browser_ok
import mederi.app.shared.generated.resources.browser_reload
import mederi.app.shared.generated.resources.browser_retry
import mederi.app.shared.generated.resources.browser_stop
import mederi.app.shared.generated.resources.browser_url_placeholder
import mederi.app.shared.generated.resources.browser_zoom_in
import mederi.app.shared.generated.resources.browser_zoom_out
import mederi.app.shared.generated.resources.cancel
import mederi.app.shared.generated.resources.dock_browser_desktop_only
import mederi.app.shared.generated.resources.dock_browser_unsupported
import mederi.app.shared.generated.resources.rightdock_browser
import org.jetbrains.compose.resources.stringResource
import xyz.kbrowser.webview.LoadingState
import xyz.mederi.theme.MederiColors
import xyz.mederi.theme.MederiRadius
import xyz.mederi.ui.components.atoms.ConfirmDialog
import xyz.mederi.ui.components.atoms.MederiDialog
import xyz.mederi.ui.components.atoms.MederiGhostButton
import xyz.mederi.ui.components.atoms.MederiPanelHeaderIconButton
import xyz.mederi.ui.components.atoms.MederiPrimaryDecisionButton

// ============================================================================
// Composable 子组件
// ============================================================================

/**
 * 可横向滚动的多标签栏：
 * - 左侧标签列表支持 `horizontalScroll`，并在标签新增时自动平滑滚动到末尾；
 * - 右侧 `+` 新建按钮固定在滚动区域外，标签再多也随时可点。
 */
@Composable
internal fun BrowserTabBar(
    tabs: List<BrowserTabState>,
    selectedTabIndex: Int,
    colors: MederiColors,
    onSelectTab: (Int) -> Unit,
    onCloseTab: (Int) -> Unit,
    onNewTab: () -> Unit,
) {
    val scrollState = rememberScrollState()
    var previousTabCount by remember { mutableIntStateOf(tabs.size) }

    // 新增标签页时自动滚到最右侧
    LaunchedEffect(tabs.size) {
        if (tabs.size > previousTabCount) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
        previousTabCount = tabs.size
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceSidebar),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .padding(start = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 横向可滚动标签区
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .horizontalScroll(scrollState),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                tabs.forEachIndexed { index, tab ->
                    key(tab.id) {
                        BrowserTabChip(
                            tab = tab,
                            selected = index == selectedTabIndex,
                            colors = colors,
                            onClick = { onSelectTab(index) },
                            onClose = { onCloseTab(index) },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(16.dp)
                    .background(colors.divider),
            )
            Spacer(modifier = Modifier.width(4.dp))

            // 固定在右侧的新建标签按钮
            MederiPanelHeaderIconButton(
                icon = FeatherIcons.Plus,
                onClick = onNewTab,
                contentDescription = stringResource(Res.string.browser_new_tab),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.divider),
        )
    }
}

/**
 * 单个浏览器标签项：
 * - 展示加载态旋转指示器 / 错误图标 / 站点图标；
 * - 标题优先级：页面 `currentTitle` → `customTitle` → URL 域名 → 「新标签页」；
 * - 右侧独立关闭按钮。
 */
@Composable
private fun BrowserTabChip(
    tab: BrowserTabState,
    selected: Boolean,
    colors: MederiColors,
    onClick: () -> Unit,
    onClose: () -> Unit,
) {
    val pageTitle by tab.page.webView.currentTitle.collectAsState()
    val currentUrl by tab.page.webView.currentUrl.collectAsState()
    val loadingState by tab.page.webView.loadingState.collectAsState()

    val fallbackNewTab = stringResource(Res.string.browser_new_tab)
    val displayTitle = remember(pageTitle, tab.customTitle, currentUrl, fallbackNewTab) {
        resolveTabDisplayTitle(
            pageTitle = pageTitle,
            customTitle = tab.customTitle,
            currentUrl = currentUrl,
            fallback = fallbackNewTab,
        )
    }

    val chipInteraction = remember { MutableInteractionSource() }
    val chipHovered by chipInteraction.collectIsHoveredAsState()

    val closeInteraction = remember { MutableInteractionSource() }
    val closeHovered by closeInteraction.collectIsHoveredAsState()

    val shape = RoundedCornerShape(6.dp)
    val bgColor = when {
        selected -> colors.surfaceCard
        chipHovered -> colors.surfaceHover
        else -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .height(26.dp)
            .widthIn(min = 96.dp, max = 180.dp)
            .clip(shape)
            .background(bgColor)
            .then(
                if (selected) {
                    Modifier.border(1.dp, colors.surfaceCardBorder, shape)
                } else {
                    Modifier
                }
            )
            .hoverable(chipInteraction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = chipInteraction,
                indication = null,
                onClick = onClick,
            )
            .padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 状态图标：加载中转圈 / 错误提示 / 默认地球图标
        val isError = loadingState is LoadingState.Error &&
            (loadingState as LoadingState.Error).errorCode != CEF_ERR_ABORTED &&
            loadingState != tab.dismissedError

        when {
            loadingState is LoadingState.Loading -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(11.dp),
                    strokeWidth = 1.5.dp,
                    color = colors.accentPrimary,
                )
            }

            isError -> {
                Icon(
                    imageVector = FeatherIcons.AlertCircle,
                    contentDescription = null,
                    tint = colors.statusError,
                    modifier = Modifier.size(12.dp),
                )
            }

            else -> {
                Icon(
                    imageVector = FeatherIcons.Globe,
                    contentDescription = null,
                    tint = if (selected) colors.accentPrimary else colors.textMuted,
                    modifier = Modifier.size(12.dp),
                )
            }
        }

        Spacer(modifier = Modifier.width(6.dp))

        Text(
            text = displayTitle,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) colors.textPrimary else colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        Spacer(modifier = Modifier.width(4.dp))

        // 关闭按钮
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (closeHovered) colors.surfaceHover else Color.Transparent)
                .hoverable(closeInteraction)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(
                    interactionSource = closeInteraction,
                    indication = null,
                    onClick = onClose,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = FeatherIcons.X,
                contentDescription = stringResource(Res.string.browser_close_tab),
                tint = when {
                    closeHovered -> colors.textPrimary
                    selected || chipHovered -> colors.textSecondary
                    else -> colors.textMuted.copy(alpha = 0.65f)
                },
                modifier = Modifier.size(11.dp),
            )
        }
    }
}

/**
 * 导航工具栏：
 * - 后退 / 前进 / 刷新或停止合一按钮；
 * - 智能地址栏（Omnibox）：自动同步当前页面 URL，支持 Enter 触发导航或 Bing 搜索，聚焦时支持一键清空；
 * - 页面缩放控制组（`-` / `100%` / `+`）。
 */
@Composable
internal fun BrowserNavToolbar(
    selectedTab: BrowserTabState?,
    colors: MederiColors,
    onNavigate: (String) -> Unit,
    onReload: () -> Unit,
    onStop: () -> Unit,
    onZoomOut: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomReset: () -> Unit,
) {
    val webView = selectedTab?.page?.webView
    val canGoBack by (webView?.canGoBack?.collectAsState() ?: remember { mutableStateOf(false) })
    val canGoForward by (webView?.canGoForward?.collectAsState() ?: remember { mutableStateOf(false) })
    val loadingState by (
        webView?.loadingState?.collectAsState()
            ?: remember { mutableStateOf<LoadingState>(LoadingState.Finished) }
        )
    val currentUrl by (webView?.currentUrl?.collectAsState() ?: remember { mutableStateOf<String?>(null) })

    val focusManager = LocalFocusManager.current
    var urlInput by remember(selectedTab?.id) {
        mutableStateOf(
            currentUrl?.takeIf { it.isNotBlank() && it != "about:blank" && !it.startsWith("data:") }.orEmpty()
        )
    }
    var urlFocused by remember(selectedTab?.id) { mutableStateOf(false) }

    // 未聚焦时跟随当前页面 URL
    LaunchedEffect(selectedTab?.id, currentUrl, urlFocused) {
        if (!urlFocused) {
            val displayUrl = currentUrl
                ?.takeIf { it.isNotBlank() && it != "about:blank" && !it.startsWith("data:") }
                .orEmpty()
            if (displayUrl != urlInput) {
                urlInput = displayUrl
            }
        }
    }

    val submitInput = {
        val trimmed = urlInput.trim()
        if (trimmed.isNotEmpty()) {
            val resolved = resolveUrlOrSearch(trimmed)
            urlInput = resolved
            focusManager.clearFocus()
            onNavigate(trimmed)
        }
    }

    val isLoading = loadingState is LoadingState.Loading
    val isError = loadingState is LoadingState.Error &&
        (loadingState as LoadingState.Error).errorCode != CEF_ERR_ABORTED &&
        loadingState != selectedTab?.dismissedError

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .background(colors.surfaceSidebar)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 后退
        BrowserToolbarIconButton(
            icon = FeatherIcons.ChevronLeft,
            contentDescription = stringResource(Res.string.browser_back),
            enabled = selectedTab != null && canGoBack,
            colors = colors,
            onClick = { webView?.goBack() },
        )

        // 前进
        BrowserToolbarIconButton(
            icon = FeatherIcons.ChevronRight,
            contentDescription = stringResource(Res.string.browser_forward),
            enabled = selectedTab != null && canGoForward,
            colors = colors,
            onClick = { webView?.goForward() },
        )

        // 刷新 / 停止合一按钮
        BrowserToolbarIconButton(
            icon = if (isLoading) FeatherIcons.X else FeatherIcons.RefreshCw,
            contentDescription = stringResource(
                if (isLoading) Res.string.browser_stop else Res.string.browser_reload
            ),
            enabled = selectedTab != null,
            colors = colors,
            onClick = {
                if (isLoading) onStop() else onReload()
            },
        )

        // 智能地址栏（Omnibox）
        val inputShape = RoundedCornerShape(6.dp)
        val borderColor = if (urlFocused) colors.accentFocus else colors.surfaceCardBorder
        Row(
            modifier = Modifier
                .weight(1f)
                .height(26.dp)
                .clip(inputShape)
                .background(colors.surfaceInput)
                .border(1.dp, borderColor, inputShape)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左侧协议/状态图标
            val leadingIcon = when {
                isError -> FeatherIcons.AlertCircle
                urlInput.startsWith("https://", ignoreCase = true) -> FeatherIcons.Lock
                urlFocused -> FeatherIcons.Search
                else -> FeatherIcons.Globe
            }
            val leadingTint = when {
                isError -> colors.statusError
                urlInput.startsWith("https://", ignoreCase = true) -> colors.accentSuccess
                else -> colors.textMuted
            }
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = leadingTint,
                modifier = Modifier.size(12.dp),
            )

            Spacer(modifier = Modifier.width(6.dp))

            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (urlInput.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.browser_url_placeholder),
                        fontSize = 12.sp,
                        color = colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    singleLine = true,
                    enabled = selectedTab != null,
                    textStyle = TextStyle(
                        fontSize = 12.sp,
                        color = colors.textPrimary,
                    ),
                    cursorBrush = SolidColor(colors.accentPrimary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submitInput() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { urlFocused = it.isFocused }
                        .onPreviewKeyEvent { event ->
                            if (
                                event.type == KeyEventType.KeyDown &&
                                (event.key == Key.Enter || event.key == Key.NumPadEnter)
                            ) {
                                submitInput()
                                true
                            } else {
                                false
                            }
                        },
                )
            }

            // 聚焦且有内容时提供一键清空按钮
            if (urlFocused && urlInput.isNotEmpty()) {
                Spacer(modifier = Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable { urlInput = "" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = FeatherIcons.X,
                        contentDescription = stringResource(Res.string.browser_clear_url),
                        tint = colors.textMuted,
                        modifier = Modifier.size(11.dp),
                    )
                }
            }
        }

        // 页面缩放控制组（- / 100% / +）
        val zoomPercent = selectedTab?.zoomPercent ?: 100
        Row(
            modifier = Modifier
                .height(26.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(colors.surfaceInput)
                .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(6.dp))
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrowserZoomStepButton(
                icon = FeatherIcons.Minus,
                contentDescription = stringResource(Res.string.browser_zoom_out),
                enabled = selectedTab != null && zoomPercent > ZOOM_STEPS.first(),
                colors = colors,
                onClick = onZoomOut,
            )

            val resetInteraction = remember { MutableInteractionSource() }
            val resetHovered by resetInteraction.collectIsHoveredAsState()
            Box(
                modifier = Modifier
                    .height(20.dp)
                    .widthIn(min = 36.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (resetHovered) colors.surfaceHover else Color.Transparent)
                    .hoverable(resetInteraction)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(
                        enabled = selectedTab != null && zoomPercent != 100,
                        interactionSource = resetInteraction,
                        indication = null,
                        onClick = onZoomReset,
                    )
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "$zoomPercent%",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = if (zoomPercent != 100) colors.accentPrimary else colors.textSecondary,
                )
            }

            BrowserZoomStepButton(
                icon = FeatherIcons.Plus,
                contentDescription = stringResource(Res.string.browser_zoom_in),
                enabled = selectedTab != null && zoomPercent < ZOOM_STEPS.last(),
                colors = colors,
                onClick = onZoomIn,
            )
        }
    }
}

/** 导航栏图标按钮（26×26dp，带悬停态与禁用态）。 */
@Composable
private fun BrowserToolbarIconButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    colors: MederiColors,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (enabled && isHovered) colors.surfaceHover else Color.Transparent)
            .hoverable(interactionSource)
            .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = when {
                !enabled -> colors.textMuted.copy(alpha = 0.4f)
                isHovered -> colors.textPrimary
                else -> colors.textSecondary
            },
            modifier = Modifier.size(14.dp),
        )
    }
}

/** 缩放胶囊内的微调按钮（20×20dp）。 */
@Composable
private fun BrowserZoomStepButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    colors: MederiColors,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (enabled && isHovered) colors.surfaceHover else Color.Transparent)
            .hoverable(interactionSource)
            .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = when {
                !enabled -> colors.textMuted.copy(alpha = 0.4f)
                isHovered -> colors.textPrimary
                else -> colors.textSecondary
            },
            modifier = Modifier.size(11.dp),
        )
    }
}

/**
 * 网页加载错误视图：展示错误原因、错误码、失败 URL 与重试按钮。
 */
@Composable
internal fun BrowserErrorView(
    error: LoadingState.Error,
    colors: MederiColors,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surfaceWorkspace)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.widthIn(max = 380.dp),
        ) {
            Icon(
                imageVector = FeatherIcons.AlertCircle,
                contentDescription = null,
                tint = colors.statusError,
                modifier = Modifier.size(36.dp),
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(Res.string.browser_load_error_title),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "${error.description} (${error.errorCode})",
                fontSize = 12.sp,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
            )

            val failingUrl = error.failingUrl.takeIf { it.isNotBlank() }
            if (failingUrl != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = failingUrl,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.textMuted,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            MederiPrimaryDecisionButton(
                text = stringResource(Res.string.browser_retry),
                onClick = onRetry,
                colors = colors,
            )
        }
    }
}

/**
 * JCEF 未就绪时的占位视图（与 `BrowserPanelContent` / `TerminalUnsupportedPlaceholder` 视觉一致）。
 */
@Composable
internal fun BrowserUnavailableView(colors: MederiColors) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surfaceWorkspace),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(24.dp),
        ) {
            Icon(
                imageVector = FeatherIcons.Globe,
                contentDescription = stringResource(Res.string.rightdock_browser),
                tint = colors.textMuted,
                modifier = Modifier.size(36.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(Res.string.dock_browser_unsupported),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = colors.textSecondary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(Res.string.dock_browser_desktop_only),
                fontSize = 11.sp,
                color = colors.textMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 渲染当前标签页挂起的 JS 对话框（Alert / Confirm / Prompt）。
 */
@Composable
internal fun BrowserJsDialogHost(
    dialog: BrowserJsDialog,
    colors: MederiColors,
) {
    when (dialog) {
        is BrowserJsDialog.Alert -> {
            MederiDialog(
                onDismiss = dialog::cancel,
                width = 360.dp,
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = stringResource(Res.string.browser_js_alert_title),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary,
                        )
                        dialog.url?.takeIf { it.isNotBlank() }?.let { urlText ->
                            Text(
                                text = urlText,
                                fontSize = 11.sp,
                                color = colors.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
            ) {
                Text(
                    text = dialog.message,
                    fontSize = 12.sp,
                    color = colors.textSecondary,
                    lineHeight = 18.sp,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MederiPrimaryDecisionButton(
                        text = stringResource(Res.string.browser_ok),
                        onClick = dialog::confirm,
                        colors = colors,
                    )
                }
            }
        }

        is BrowserJsDialog.Confirm -> {
            ConfirmDialog(
                title = stringResource(Res.string.browser_js_confirm_title),
                message = dialog.message,
                confirmLabel = stringResource(Res.string.browser_ok),
                cancelLabel = stringResource(Res.string.cancel),
                onConfirm = dialog::confirm,
                onDismiss = dialog::cancel,
                width = 360.dp,
            )
        }

        is BrowserJsDialog.Prompt -> {
            var inputValue by remember(dialog) { mutableStateOf(dialog.defaultValue) }
            MederiDialog(
                onDismiss = dialog::cancel,
                width = 360.dp,
                title = {
                    Text(
                        text = stringResource(Res.string.browser_js_prompt_title),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                    )
                },
            ) {
                if (dialog.message.isNotBlank()) {
                    Text(
                        text = dialog.message,
                        fontSize = 12.sp,
                        color = colors.textSecondary,
                        lineHeight = 18.sp,
                    )
                }
                OutlinedTextField(
                    value = inputValue,
                    onValueChange = { inputValue = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(MederiRadius.Card),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.accentPrimary,
                        unfocusedBorderColor = colors.surfaceCardBorder,
                        focusedContainerColor = colors.surfaceInput,
                        unfocusedContainerColor = colors.surfaceInput,
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary,
                    ),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MederiGhostButton(
                        text = stringResource(Res.string.cancel),
                        onClick = dialog::cancel,
                        colors = colors,
                    )
                    Spacer(Modifier.width(6.dp))
                    MederiPrimaryDecisionButton(
                        text = stringResource(Res.string.browser_ok),
                        onClick = { dialog.confirm(inputValue) },
                        colors = colors,
                    )
                }
            }
        }
    }
}
