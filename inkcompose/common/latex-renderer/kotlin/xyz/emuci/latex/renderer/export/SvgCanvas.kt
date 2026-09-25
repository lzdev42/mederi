package xyz.emuci.latex.renderer.export

import androidx.compose.ui.graphics.Canvas

/**
 * 在平台原生的 SVG 画布上执行现有 Compose 绘制命令并返回 UTF-8 文档。
 *
 * 该边界让测量、节点布局和绘制保持在 commonMain；平台层只负责把 Canvas 命令编码成 SVG。
 */
internal fun renderToSvg(
    width: Float,
    height: Float,
    textAsPath: Boolean,
    prettyPrint: Boolean,
    draw: (Canvas) -> Unit
): ByteArray? {
    val bytes = renderToSvgPlatform(width, height, textAsPath, prettyPrint, draw) ?: return null
    val svg = bytes.decodeToString()
    val rootStart = svg.indexOf("<svg")
    val rootEnd = if (rootStart >= 0) svg.indexOf('>', rootStart) else -1
    if (rootEnd < 0 || "viewBox=" in svg.substring(rootStart, rootEnd)) return bytes

    // Skia currently emits width/height but not viewBox on some targets. Normalize the public
    // result so responsive Web embedding and physical-size overrides behave consistently.
    val viewBox = " viewBox=\"0 0 ${svgNumber(width)} ${svgNumber(height)}\""
    return (svg.substring(0, rootEnd) + viewBox + svg.substring(rootEnd)).encodeToByteArray()
}

private fun svgNumber(value: Float): String =
    if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString()

internal expect fun renderToSvgPlatform(
    width: Float,
    height: Float,
    textAsPath: Boolean,
    prettyPrint: Boolean,
    draw: (Canvas) -> Unit
): ByteArray?
