package xyz.mederi.util

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsString

internal actual fun platformClipboardGetImage(): ClipboardImage? = null

internal actual fun platformClipboardGetText(): String? = null

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun plainTextClipEntry(text: String): ClipEntry? =
    ClipEntry.withPlainText(text)

@OptIn(ExperimentalComposeUiApi::class, ExperimentalWasmJsInterop::class)
internal actual suspend fun readPlainTextFromClip(clipEntry: ClipEntry?): String? =
    runCatching {
        window.navigator.clipboard.readText().await<JsString>().toString()
    }.getOrNull()
