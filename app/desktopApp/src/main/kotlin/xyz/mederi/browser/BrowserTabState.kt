package xyz.mederi.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import xyz.kbrowser.webview.JsPromptResultCallback
import xyz.kbrowser.webview.JsResultCallback
import xyz.kbrowser.webview.KBPage
import xyz.kbrowser.webview.KBWebChromeClient
import xyz.kbrowser.webview.LoadingState
import java.util.concurrent.atomic.AtomicBoolean

/** 支持的页面缩放档位（百分比）。 */
internal val ZOOM_STEPS = listOf(50, 67, 75, 80, 90, 100, 110, 125, 150, 175, 200)

/** CEF 导航被主动中止时的错误码（stopLoading 或重定向覆盖），不作为页面错误页展示。 */
internal const val CEF_ERR_ABORTED = -3

/**
 * 单个浏览器标签页状态。
 *
 * @property id 稳定唯一标识（供 Compose `key(tab.id)` 复用）
 * @property page 底层 KBrowser [KBPage] 实例
 * @property customTitle 外部注入标题（如 [JcefBrowserHost.loadHtml] 传入的文档标题）
 */
internal class BrowserTabState(
    val id: Long,
    val page: KBPage,
    val customTitle: String? = null,
) {
    /** 页面缩放百分比（默认 100%，对应 [ZOOM_STEPS]）。 */
    var zoomPercent by mutableIntStateOf(100)

    /** 用户点击重试或重新导航后已忽略的错误状态（确保 KBWebView 立即挂载以完成导航）。 */
    var dismissedError by mutableStateOf<LoadingState.Error?>(null)

    /** 当前挂起的 JS 对话框（alert / confirm / prompt）。 */
    var pendingJsDialog by mutableStateOf<BrowserJsDialog?>(null)
}

/**
 * 网页 JS 弹窗状态模型（由 [KBWebChromeClient] 拦截并交由 Mederi 标准弹窗渲染）。
 *
 * 每个实例内部通过 [AtomicBoolean] 保证底层 CEF 回调只被触发一次，防止重复回调导致 CEF 异常。
 */
internal sealed class BrowserJsDialog {
    abstract val url: String?
    abstract val message: String
    abstract fun cancel()

    class Alert(
        override val url: String?,
        override val message: String,
        private val callback: JsResultCallback,
        private val onResolved: () -> Unit,
    ) : BrowserJsDialog() {
        private val completed = AtomicBoolean(false)

        fun confirm() {
            if (completed.compareAndSet(false, true)) {
                runCatching { callback.confirm() }
                onResolved()
            }
        }

        override fun cancel() = confirm()
    }

    class Confirm(
        override val url: String?,
        override val message: String,
        private val callback: JsResultCallback,
        private val onResolved: () -> Unit,
    ) : BrowserJsDialog() {
        private val completed = AtomicBoolean(false)

        fun confirm() {
            if (completed.compareAndSet(false, true)) {
                runCatching { callback.confirm() }
                onResolved()
            }
        }

        override fun cancel() {
            if (completed.compareAndSet(false, true)) {
                runCatching { callback.cancel() }
                onResolved()
            }
        }
    }

    class Prompt(
        override val url: String?,
        override val message: String,
        val defaultValue: String,
        private val callback: JsPromptResultCallback,
        private val onResolved: () -> Unit,
    ) : BrowserJsDialog() {
        private val completed = AtomicBoolean(false)

        fun confirm(value: String) {
            if (completed.compareAndSet(false, true)) {
                runCatching { callback.confirm(value) }
                onResolved()
            }
        }

        override fun cancel() {
            if (completed.compareAndSet(false, true)) {
                runCatching { callback.cancel() }
                onResolved()
            }
        }
    }
}
