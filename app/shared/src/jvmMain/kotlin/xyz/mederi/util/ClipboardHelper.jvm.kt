package xyz.mederi.util

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

internal actual fun platformClipboardGetImage(): ClipboardImage? {
    return try {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        if (clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) {
            val image = clipboard.getData(DataFlavor.imageFlavor) as? Image ?: return null
            val bufferedImage = if (image is BufferedImage) {
                image
            } else {
                val width = image.getWidth(null).coerceAtLeast(1)
                val height = image.getHeight(null).coerceAtLeast(1)
                val bImg = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
                val g = bImg.createGraphics()
                g.drawImage(image, 0, 0, null)
                g.dispose()
                bImg
            }
            val baos = ByteArrayOutputStream()
            ImageIO.write(bufferedImage, "PNG", baos)
            val bytes = baos.toByteArray()
            ClipboardImage(
                bytes = bytes,
                mimeType = "image/png",
                width = bufferedImage.width,
                height = bufferedImage.height
            )
        } else {
            null
        }
    } catch (_: Throwable) {
        null
    }
}

internal actual fun platformClipboardGetText(): String? {
    return try {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
            clipboard.getData(DataFlavor.stringFlavor) as? String
        } else {
            null
        }
    } catch (_: Throwable) {
        null
    }
}

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun plainTextClipEntry(text: String): ClipEntry? =
    ClipEntry(StringSelection(text))

@OptIn(ExperimentalComposeUiApi::class)
internal actual suspend fun readPlainTextFromClip(clipEntry: ClipEntry?): String? {
    val transferable = clipEntry?.nativeClipEntry as? Transferable
    if (transferable != null && transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) {
        return runCatching { transferable.getTransferData(DataFlavor.stringFlavor) as? String }.getOrNull()
    }
    return platformClipboardGetText()
}
