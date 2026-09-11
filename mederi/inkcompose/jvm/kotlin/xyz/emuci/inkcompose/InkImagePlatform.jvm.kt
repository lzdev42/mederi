package xyz.emuci.inkcompose

import coil3.decode.Decoder

internal actual fun inkExpandUserHome(path: String): String {
    val home = System.getProperty("user.home") ?: return path
    return when {
        path == "~" -> home
        path.startsWith("~/") -> home + path.substring(1)
        else -> path
    }
}

internal actual fun inkSvgDecoderFactory(): Decoder.Factory? = InkSvgDecoderFactory()
