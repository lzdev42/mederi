package xyz.mederi.util

import android.content.ClipData
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry

internal actual fun platformClipboardGetImage(): ClipboardImage? = null

internal actual fun platformClipboardGetText(): String? = null

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun plainTextClipEntry(text: String): ClipEntry? =
    ClipEntry(ClipData.newPlainText("text", text))

@OptIn(ExperimentalComposeUiApi::class)
internal actual suspend fun readPlainTextFromClip(clipEntry: ClipEntry?): String? =
    clipEntry?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
