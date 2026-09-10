package xyz.emuci.latex.renderer.export

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.asComposeCanvas
import org.jetbrains.skia.DynamicMemoryWStream
import org.jetbrains.skia.Rect
import org.jetbrains.skia.svg.SVGCanvas

internal actual fun renderToSvgPlatform(
    width: Float,
    height: Float,
    textAsPath: Boolean,
    prettyPrint: Boolean,
    draw: (Canvas) -> Unit
): ByteArray? {
    val stream = DynamicMemoryWStream()
    return try {
        val svgCanvas = SVGCanvas.make(
            bounds = Rect.makeWH(width, height),
            out = stream,
            convertTextToPaths = textAsPath,
            prettyXML = prettyPrint
        )
        try {
            draw(svgCanvas.asComposeCanvas())
        } finally {
            // SVGCanvas may buffer commands; closing it finalizes the XML document.
            svgCanvas.close()
        }

        val bytes = ByteArray(stream.bytesWritten())
        if (stream.read(bytes, 0, bytes.size)) bytes else null
    } finally {
        stream.close()
    }
}
