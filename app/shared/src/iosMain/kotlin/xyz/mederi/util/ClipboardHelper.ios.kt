package xyz.mederi.util

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry

internal actual fun platformClipboardGetImage(): ClipboardImage? = null

internal actual fun platformClipboardGetText(): String? = null

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun plainTextClipEntry(text: String): ClipEntry? =
    ClipEntry.withPlainText(text)

@OptIn(ExperimentalComposeUiApi::class)
internal actual suspend fun readPlainTextFromClip(clipEntry: ClipEntry?): String? =
    clipEntry?.getPlainText()
