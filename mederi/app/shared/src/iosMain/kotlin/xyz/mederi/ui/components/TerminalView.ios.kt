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
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.termview_remote_title
import mederi.app.shared.generated.resources.termview_remote_unavailable
import org.jetbrains.compose.resources.stringResource
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
                text = stringResource(Res.string.termview_remote_title),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(Res.string.termview_remote_unavailable),
                fontSize = 11.sp,
            )
        }
    }
}
