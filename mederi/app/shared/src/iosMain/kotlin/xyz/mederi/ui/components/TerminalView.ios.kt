package xyz.mederi.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.mederi.core.contract.TerminalSession

/**
 * ios actual：移动端为遥控端（iOS 沙箱禁止 fork/exec，本地 pty 不可能）。
 * 远程终端（连 server WS + WebView xterm.js）为后续版本。
 */
@Composable
internal actual fun TerminalView(
    session: TerminalSession,
    isDark: Boolean,
    modifier: Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "远程终端",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "将随遥控 WS 通道（/v1/terminal/ws）一同提供",
                fontSize = 11.sp,
            )
        }
    }
}
