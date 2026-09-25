package xyz.mederi.util

import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

actual object PlatformClipboard {
    actual fun getImage(): ClipboardImage? {
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

    actual fun getText(): String? {
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
}
