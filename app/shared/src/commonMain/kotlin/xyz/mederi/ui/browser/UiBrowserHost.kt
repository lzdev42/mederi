package xyz.mederi.ui.browser

import androidx.compose.runtime.Composable
import xyz.mederi.theme.MederiColors

/**
 * UI 端内置浏览器宿主（desktop 注入 JCEF 实现；遥控/wasm 不注入 → 面板显示"当前端不可用"）。
 *
 * 它不是一个完整浏览器，而是"一个 tab = 一个 KBPage"的轻量标签容器：
 * - 每个 tab 持有独立的 [KBPage]（viewport-less，挂进 KBWebView 渲染）；
 * - AI 任务创建专属 tab 的逻辑在 desktopApp 的宿主实现里（core 的 BrowserRegistry 注册 lambda
 *   直接调宿主的创建方法，两者都在 desktopApp，commonMain 不需要知道 core 浏览器类型——
 *   core 仅有 jvm 目标，wasm/移动端经契约桥访问、不直连 core，commonMain 禁止引用 core 类型）。
 * - 用户可手动开/关 tab，切 tab 不影响 AI 正在操作的页面（每个 control 绑定自己的 KBPage）。
 *
 * 装配：desktopApp 的 onAppStateReady 里 `appState.uiBrowserHost = JcefBrowserHost()`，
 * 同时 `BrowserRegistry.register("jcef", BrowserKind.JCEF) { host.createAiTab() }`。
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
