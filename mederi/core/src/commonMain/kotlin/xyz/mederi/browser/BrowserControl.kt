package xyz.mederi.browser

import xyz.mederi.browser.bidi.KeyboardKey
import xyz.mederi.browser.bidi.OperationResult

/**
 * 浏览器控制抽象。
 *
 * mederi core 只依赖这个接口操作浏览器——不关心底层是 Camoufox（BiDi 协议）还是
 * 桌面版集成的 JCEF（app 层实现）。BrowserAgentRunner / BrowserTaskManager 只跟它打交道。
 *
 * 实现选择与注册（2026-09-14）：
 * - core 默认 `BiDiBrowserControl`（Camoufox + BiDi 协议，反检测无头）。
 * - 桌面版 JCEF（KBrowser）由 UI 层实现本接口并注册进 [BrowserRegistry]（name="jcef"）。
 * - AI 通过 run_browser_task(browser=name) 选择；[start]/[close] 语义：
 *   Camoufox 版 start 真正拉起浏览器、close 关闭；JCEF 版由 UI 拥有页面生命周期，
 *   start/close 可为幂等空实现（不杀 UI 正在渲染的浏览器）。
 *
 * 操作以 a11y tree 的 elementRef（refid）定位元素——snapshot 返回的 YAML 树里每行
 * 对应一个 refid，LLM 据此选择要操作的元素（KBrowser 与 BiDi 均为 refid 定位，API 对齐）。
 */

/** 页面快照：a11y tree 的紧凑 YAML + 原始树（rawTree 仅用于同批内 refid 重映射，用完即丢）。 */
data class PageSnapshot(
    val url: String,
    val title: String,
    val yaml: String,
    val rawTree: Any?
)

interface BrowserControl {

    /** 启动浏览器（连接内核），幂等。 */
    suspend fun start()

    /** 关闭浏览器，释放进程。幂等。 */
    suspend fun close()

    /** 当前页面 URL。 */
    suspend fun getCurrentUrl(): String

    /** 当前页面标题。 */
    suspend fun getTitle(): String

    /** 导航到 url。 */
    suspend fun navigate(url: String): OperationResult

    /** 点击 a11y 树中的元素（refid 定位）。 */
    suspend fun click(elementRef: String): OperationResult

    /** 按文档坐标点击（视觉模式兜底）。 */
    suspend fun clickByCoordinates(x: Int, y: Int): OperationResult

    /** 在元素中输入文本（refid 定位）。 */
    suspend fun type(elementRef: String, text: String): OperationResult

    /** 在元素上滚动（refid 定位）。 */
    suspend fun scroll(elementRef: String, deltaX: Int, deltaY: Int): OperationResult

    /** 按键。 */
    suspend fun press(key: KeyboardKey): OperationResult

    /** 获取当前页面的 a11y 快照（CLEAN 模式：仅视口可见可交互元素）。 */
    suspend fun snapshot(): PageSnapshot

    /** 截图（base64 PNG），供视觉模型。不可用时返回 null。 */
    suspend fun screenshot(): ByteArray?
}
