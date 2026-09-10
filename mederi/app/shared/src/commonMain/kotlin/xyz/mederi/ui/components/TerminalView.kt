package xyz.mederi.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import xyz.mederi.core.contract.TerminalSession

/**
 * 终端视图（平台渲染分工）：
 * - desktop：jediterm（IntelliJ 终端本体）+ SwingPanel，进程内直连 pty 会话
 * - wasmJs / 移动端：遥控端，接远程 WS 终端（后续版本）
 */
@Composable
internal expect fun TerminalView(
    session: TerminalSession,
    isDark: Boolean,
    modifier: Modifier,
)
