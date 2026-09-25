package xyz.mederi.util

data class ClipboardImage(
    val bytes: ByteArray,
    val mimeType: String,
    val width: Int = 0,
    val height: Int = 0
) {
    override fun equals(other: Any?): Boolean = other is ClipboardImage &&
        mimeType == other.mimeType && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * mimeType.hashCode() + bytes.contentHashCode()
}

expect object PlatformClipboard {
    fun getImage(): ClipboardImage?
    fun getText(): String?
}
