package xyz.mederi.ui.components

import androidx.compose.runtime.Composable
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.attachment_chars_unit
import org.jetbrains.compose.resources.stringResource

/**
 * 通用秒数格式化：≥10s 显示一位小数秒，否则毫秒。KMP 兼容（wasmJs 无 String.format）。
 */
internal fun formatSeconds(ms: Long): String =
    if (ms >= 10_000) {
        // KMP 兼容的一位小数秒（wasmJs 无 String.format）
        val secs = ms / 1000
        val tenths = (ms % 1000) / 100
        "${secs}.${tenths}s"
    } else {
        "${ms}ms"
    }

/**
 * 通用字符计数格式化：≥1000 显示一位小数 k，否则原值。单位文案走 composeResources。
 */
@Composable
internal fun formatCharCount(count: Int): String {
    val unit = stringResource(Res.string.attachment_chars_unit)
    return if (count >= 1000) {
        val k = count / 1000
        val dec = (count % 1000) / 100
        "${k}.${dec}k $unit"
    } else {
        "$count $unit"
    }
}
