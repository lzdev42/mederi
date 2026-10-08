package xyz.mederi.ui.components.atoms

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.Copy
import kotlinx.coroutines.delay
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.util.rememberClipboardCopy

/**
 * 复制反馈状态：持有 copied 标志，copy(text) 置位并写入系统剪贴板。
 * 复位由 [CopyFeedbackScope] 的 LaunchedEffect 统一处理（1.5s 后自动回 false）。
 */
@Stable
class CopyFeedbackState internal constructor(
    private val copyToClipboard: ((String) -> Unit)? = null,
) {
    var copied by mutableStateOf(false)
        internal set

    fun copy(text: String) {
        copied = true
        copyToClipboard?.invoke(text)
    }
}

/**
 * 复制反馈作用域：订阅 [state.copied] 并在 1.5s 后自动复位。
 * [content] 接收当前 copied 值，供自定义布局渲染（图标切换等）。
 */
@Composable
fun CopyFeedbackScope(
    state: CopyFeedbackState,
    content: @Composable (Boolean) -> Unit,
) {
    val copied = state.copied
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            state.copied = false
        }
    }
    content(copied)
}

/**
 * 复制反馈状态工厂（供自定义复制行使用，如 footer 内联复制按钮）。
 */
@Composable
fun rememberCopyFeedback(): CopyFeedbackState {
    val copyToClipboard = rememberClipboardCopy()
    return remember(copyToClipboard) { CopyFeedbackState(copyToClipboard) }
}

/**
 * 标准复制按钮：[MederiIconButton] + 1.5s 成功反馈（Check 图标 + accentSuccess 高亮）。
 */
@Composable
fun CopyButton(
    text: String,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    size: Int = 28,
    successIcon: ImageVector = FeatherIcons.Check,
    copyIcon: ImageVector = FeatherIcons.Copy,
) {
    val colors = LocalMederiColors.current
    val state = rememberCopyFeedback()
    CopyFeedbackScope(state) { copied ->
        MederiIconButton(
            icon = if (copied) successIcon else copyIcon,
            onClick = { state.copy(text) },
            contentDescription = contentDescription,
            modifier = modifier,
            size = size,
            active = copied,
            activeTint = colors.accentSuccess,
        )
    }
}