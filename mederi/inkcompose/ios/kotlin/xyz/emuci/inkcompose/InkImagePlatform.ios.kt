package xyz.emuci.inkcompose

import coil3.decode.Decoder
import platform.Foundation.NSHomeDirectory

internal actual fun inkExpandUserHome(path: String): String {
    val home = NSHomeDirectory()
    return when {
        path == "~" -> home
        path.startsWith("~/") -> home + path.substring(1)
        else -> path
    }
}

internal actual fun inkSvgDecoderFactory(): Decoder.Factory? = InkSvgDecoderFactory()
