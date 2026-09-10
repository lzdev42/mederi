package xyz.mederi.util

actual object PlatformClipboard {
    actual fun getImage(): ClipboardImage? = null

    actual fun getText(): String? = null
}
