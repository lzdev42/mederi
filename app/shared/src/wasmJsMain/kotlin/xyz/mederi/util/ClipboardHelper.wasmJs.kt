@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class, kotlin.io.encoding.ExperimentalEncodingApi::class)

package xyz.mederi.util

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import kotlinx.browser.window
import kotlinx.coroutines.await
import xyz.mederi.ui.DebugLog
import kotlin.io.encoding.Base64
import kotlin.js.JsString
import kotlin.js.Promise

internal actual fun platformClipboardGetImage(): ClipboardImage? = null

internal actual fun platformClipboardGetText(): String? = null

internal actual suspend fun platformClipboardGetImageAsync(): ClipboardImage? {
    return try {
        DebugLog.event("UI", "PlatformClipboard.getImageAsync: reading navigator.clipboard.read()")
        val payload = readClipboardImagePromise().await<JsString>().toString()
        if (payload.isEmpty()) {
            DebugLog.event("UI", "PlatformClipboard.getImageAsync: no image found in clipboard")
            return null
        }
        val colon = payload.indexOf(':')
        if (colon <= 0) {
            DebugLog.event("UI", "PlatformClipboard.getImageAsync: invalid image payload format")
            return null
        }
        val mimeType = payload.substring(0, colon)
        val b64 = payload.substring(colon + 1)
        val bytes = Base64.Default.decode(b64)
        DebugLog.data("UI", "PlatformClipboard.getImageAsync: success", "mime=$mimeType, size=${bytes.size}")
        ClipboardImage(
            bytes = bytes,
            mimeType = mimeType
        )
    } catch (e: Throwable) {
        DebugLog.data("UI", "PlatformClipboard.getImageAsync: error", "${e.message}")
        null
    }
}

internal actual suspend fun platformClipboardGetTextAsync(): String? =
    runCatching {
        DebugLog.event("UI", "PlatformClipboard.getTextAsync: reading navigator.clipboard.readText()")
        val text = window.navigator.clipboard.readText().await<JsString>().toString()
        DebugLog.data("UI", "PlatformClipboard.getTextAsync: success", "len=${text.length}")
        text
    }.onFailure {
        DebugLog.data("UI", "PlatformClipboard.getTextAsync: error", "${it.message}")
    }.getOrNull()

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun plainTextClipEntry(text: String): ClipEntry? =
    ClipEntry.withPlainText(text)

@OptIn(ExperimentalComposeUiApi::class)
internal actual suspend fun readPlainTextFromClip(clipEntry: ClipEntry?): String? =
    platformClipboardGetTextAsync()

private fun readClipboardImagePromise(): Promise<JsString> = js("""
    new Promise((resolve) => {
        try {
            if (!window.navigator || !window.navigator.clipboard || !window.navigator.clipboard.read) {
                resolve('');
                return;
            }
            window.navigator.clipboard.read().then((items) => {
                for (let i = 0; i < items.length; i++) {
                    const item = items[i];
                    for (let t = 0; t < item.types.length; t++) {
                        const type = item.types[t];
                        if (type.startsWith('image/')) {
                            item.getType(type).then((blob) => {
                                const reader = new FileReader();
                                reader.onload = () => {
                                    const res = reader.result;
                                    if (typeof res === 'string') {
                                        const comma = res.indexOf(',');
                                        const b64 = comma >= 0 ? res.substring(comma + 1) : res;
                                        resolve(type + ':' + b64);
                                    } else {
                                        resolve('');
                                    }
                                };
                                reader.onerror = () => resolve('');
                                reader.readAsDataURL(blob);
                            }).catch(() => resolve(''));
                            return;
                        }
                    }
                }
                resolve('');
            }).catch(() => resolve(''));
        } catch (e) {
            resolve('');
        }
    })
""")

