package xyz.mederi.ui.browser

import androidx.compose.runtime.Composable
import xyz.mederi.theme.MederiColors

/**
 * UI 端内置浏览器的可选宿主。未注入宿主时，面板显示当前端不可用。
 *
 * 桌面 JCEF 实现为每个标签页持有独立的 KBPage，并按会话管理页面资源。
 */
interface UiBrowserHost {

    /** 当前端是否可渲染内置浏览器（遥控端/wasm 为 false）。 */
    val isAvailable: Boolean

    /**
     * 渲染指定会话的浏览器内容（tab 栏 + 导航栏 + 当前会话激活 tab 的 KBPage 视图）。
     * 仅可用端被调用。
     */
    @Composable
    fun BrowserContent(
        sessionId: String,
        colors: MederiColors,
    )

    /**
     * 在指定会话的新 tab 中加载任意 HTML 字符串（用于 Office 文档预览等）。
     * 实现端创建一个 KBPage 并 loadHtml；title 用于 tab 栏显示。
     */
    fun loadHtml(sessionId: String = "", title: String, html: String)

    /** 关闭并回收指定会话的所有 Tab 与 KBPage 资源。 */
    fun closeSession(sessionId: String)

    /** 应用退出清理（关闭所有会话的 KBPage / 回收 JCEF）。 */
    fun shutdown()
}
