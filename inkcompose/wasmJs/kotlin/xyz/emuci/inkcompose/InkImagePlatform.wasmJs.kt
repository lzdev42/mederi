package xyz.emuci.inkcompose

internal actual fun inkExpandUserHome(path: String): String = path

// skia 的 wasm 构建包含 SVG 模块（SVGDOM 绑定齐全），与 jvm/ios 同一份解码器
internal actual fun inkSvgDecoderFactory(): coil3.decode.Decoder.Factory? = InkSvgDecoderFactory()
