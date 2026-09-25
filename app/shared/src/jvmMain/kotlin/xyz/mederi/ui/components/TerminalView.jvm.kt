package xyz.mederi.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import com.jediterm.core.util.TermSize
import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.emulator.ColorPalette
import com.jediterm.terminal.emulator.ColorPaletteImpl
import com.jediterm.terminal.TtyConnector
import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import xyz.mederi.core.contract.TerminalSession
import java.awt.Font
import java.util.concurrent.ConcurrentHashMap

/**
 * desktop actual：jediterm（IntelliJ 终端本体）渲染 [session]，经 SwingPanel 嵌入 Compose。
 *
 * ## Widget 生命周期（关键不变量）
 *
 * jediterm widget 按 [TerminalSession] 实例缓存在进程级 [widgetCache]：
 * - 切 tab = widget 离开组合，**只摘视图、绝不 close**。close 停 emulator 线程且在 EDT 上执行，
 *   若与阻塞中的 read() 互相等待会卡死整个 AWT 事件队列（表现为光标不闪、点击全无效）
 * - 切回 tab = 从缓存原样取回 widget（滚屏/光标/内容天然保留）
 * - 会话退出后由清扫逻辑 close 释放；"重新打开"产生新 session 实例 → 新 widget
 *
 * JVM-only：jediterm 是纯 Java Swing，desktop 专属。
 */
@Composable
internal actual fun TerminalView(
    session: TerminalSession,
    isDark: Boolean,
    modifier: Modifier,
) {
    val widget = remember(session) { getOrCreateWidget(session, isDark) }

    // 会话退出（当前 tab 活跃时）：延迟到 EDT 释放 widget（此时 emulator 线程已因 EOF 退出，不会阻塞）
    LaunchedEffect(session) {
        session.isRunning.first { !it }
        disposeWidget(session)
    }

    SwingPanel(
        factory = { widget },
        modifier = modifier,
    )
}

/** 进程级 widget 缓存：session 实例（identity 语义）→ 活跃 widget。 */
private val widgetCache = ConcurrentHashMap<TerminalSession, JediTermWidget>()

private fun getOrCreateWidget(session: TerminalSession, isDark: Boolean): JediTermWidget {
    // 清扫已退出的残留 widget（如会话在非活跃 tab 期间结束）
    widgetCache.entries.removeIf { entry ->
        if (!entry.key.isRunning.value) {
            javax.swing.SwingUtilities.invokeLater { entry.value.close() }
            true
        } else {
            false
        }
    }
    return widgetCache.computeIfAbsent(session) { createJediTermWidget(session, isDark) }
}

private fun disposeWidget(session: TerminalSession) {
    widgetCache.remove(session)?.let { widget ->
        javax.swing.SwingUtilities.invokeLater { widget.close() }
    }
}

private fun createJediTermWidget(session: TerminalSession, isDark: Boolean): JediTermWidget {
    val widget = JediTermWidget(80, 24, MederiTerminalSettings(isDark))
    widget.setTtyConnector(SessionTtyConnector(session))
    widget.start()
    return widget
}

/**
 * 主题适配：深浅色背景/前景 + 等宽字体。
 * 其余交互参数沿用 jediterm 默认（滚动、复制粘贴、鼠标上报等开箱即用）。
 */
private class MederiTerminalSettings(private val isDark: Boolean) : DefaultSettingsProvider() {
    private val palette = ColorPaletteImpl.XTERM_PALETTE

    override fun getTerminalColorPalette(): ColorPalette = palette

    override fun getTerminalFont(): Font = Font("JetBrains Mono", Font.PLAIN, 13)

    override fun getTerminalFontSize(): Float = 13f

    override fun getDefaultBackground(): TerminalColor =
        if (isDark) TerminalColor.rgb(0x14, 0x14, 0x16) else TerminalColor.rgb(0xFF, 0xFF, 0xFF)

    override fun getDefaultForeground(): TerminalColor =
        if (isDark) TerminalColor.rgb(0xE6, 0xE6, 0xE6) else TerminalColor.rgb(0x1F, 0x1F, 0x1F)

    override fun getDefaultStyle(): TextStyle = TextStyle(getDefaultForeground(), getDefaultBackground())
}

/**
 * jediterm ⇆ [TerminalSession] 桥：阻塞读走订阅 channel（jediterm 的 emulator 线程上 runBlocking），
 * 写/resize 直传会话。close 只断 UI 订阅（detach），不杀会话。
 */
private class SessionTtyConnector(private val session: TerminalSession) : TtyConnector {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val chunks = Channel<String>(Channel.UNLIMITED)
    private val decoderBuffer = StringBuilder()
    private var feedJob: Job? = null

    private fun ensureFeed() {
        if (feedJob != null) return
        feedJob = scope.launch {
            // 订阅到会话退出为止（output 流自然关闭 → channel close → read 返回 -1）
            session.output().collect { chunk ->
                chunks.send(chunk)
            }
            chunks.close()
        }
    }

    override fun read(buf: CharArray, offset: Int, length: Int): Int {
        ensureFeed()
        // 缓冲有剩余先吃剩余，否则阻塞取下一块（仅 jediterm emulator 线程调用）
        if (decoderBuffer.isEmpty()) {
            val chunk = runBlocking { chunks.receiveCatching().getOrNull() } ?: return -1
            decoderBuffer.append(chunk)
        }
        val n = minOf(length, decoderBuffer.length)
        for (i in 0 until n) buf[offset + i] = decoderBuffer[i]
        decoderBuffer.delete(0, n)
        return if (n == 0) -1 else n
    }

    override fun write(bytes: ByteArray) {
        runBlocking { session.write(String(bytes, Charsets.UTF_8)) }
    }

    override fun write(string: String) {
        runBlocking { session.write(string) }
    }

    override fun isConnected(): Boolean = session.isRunning.value

    override fun resize(termSize: TermSize) = session.resize(termSize.columns, termSize.rows)

    override fun waitFor(): Int {
        // 阻塞等待会话退出（jediterm 收尾线程调用）
        while (session.isRunning.value) {
            Thread.sleep(100)
        }
        return session.exitCode.value ?: 0
    }

    override fun ready(): Boolean = session.isRunning.value

    override fun getName(): String = session.title

    override fun close() {
        // detach：不 kill 会话；断开订阅。emulator 线程阻塞中的 read 收到 channel 关闭返回 -1 自然退出
        feedJob?.cancel()
        feedJob = null
        chunks.close()
        scope.cancel()
    }
}
