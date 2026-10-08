package xyz.mederi.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

data class ClipboardImage(
    val bytes: ByteArray,
    val mimeType: String,
    val width: Int = 0,
    val height: Int = 0
) {
    override fun equals(other: Any?): Boolean = other is ClipboardImage &&
        mimeType == other.mimeType && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * mimeType.hashCode() + bytes.contentHashCode()
}

object PlatformClipboard {
    fun getImage(): ClipboardImage? = platformClipboardGetImage()
    fun getText(): String? = platformClipboardGetText()
}

internal expect fun platformClipboardGetImage(): ClipboardImage?
internal expect fun platformClipboardGetText(): String?
internal expect fun plainTextClipEntry(text: String): ClipEntry?
internal expect suspend fun readPlainTextFromClip(clipEntry: ClipEntry?): String?

/** 使用 [Clipboard.setClipEntry] 写入纯文本（UNDISPATCHED 保持 Web 用户手势上下文）。 */
fun Clipboard.copyText(scope: CoroutineScope, text: String) {
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
        setClipEntry(plainTextClipEntry(text))
    }
}

/** 记忆化的纯文本复制回调（基于 [LocalClipboard]）。 */
@Composable
fun rememberClipboardCopy(): (String) -> Unit {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    return remember(clipboard, scope) {
        { text -> clipboard.copyText(scope, text) }
    }
}
