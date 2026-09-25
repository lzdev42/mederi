package xyz.emuci.inkcompose

internal actual fun inkExpandUserHome(path: String): String = path

// Android 的 SVG 解码由 coil-svg 依赖经 ServiceLoader 自动注册，无需手动提供
internal actual fun inkSvgDecoderFactory(): coil3.decode.Decoder.Factory? = null
