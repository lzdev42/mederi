package xyz.emuci.diagram.mermaid

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import java.io.File
import java.security.MessageDigest

/**
 * JVM 桌面端 Mermaid 磁盘持久化缓存管理（具备全自愈能力）。
 *
 * 特性：
 * 1. 动态根路径：由 ViewModel 从 core 读出注入，默认自动展开 "~/.mederi"；
 * 2. 目录自愈：任何读写均探测 `${baseDir}/mermaid/`，若被外部误删自动 mkdirs() 重建；
 * 3. 坏文件自愈：读取时校验长度与 Skia 解码合法性，若遇 0 字节或损坏图片自动 delete 坏文件并重新触发生成；
 * 4. 原子安全写入：先写临时文件 .tmp 再原子 renameTo，避免写入中途崩溃产生坏文件。
 */
internal object MermaidDiskCache {

    private val defaultBaseDir: File by lazy {
        val userHome = System.getProperty("user.home") ?: "."
        File(userHome, ".mederi")
    }

    @Volatile
    private var baseDir: File = defaultBaseDir

    fun setBaseDir(path: String) {
        val expanded = if (path.startsWith("~")) {
            val userHome = System.getProperty("user.home") ?: "."
            userHome + path.removePrefix("~")
        } else path
        baseDir = File(expanded)
        ensureDir()
        println("[MermaidDiskCache] baseDir updated to: ${baseDir.absolutePath}")
    }

    fun getBaseDir(): String = baseDir.absolutePath

    /** 目标缓存子目录：`${baseDir}/mermaid`（带目录自愈） */
    fun getMermaidDir(): File {
        val dir = File(baseDir, "mermaid")
        if (!dir.exists()) {
            val created = dir.mkdirs()
            if (created) {
                println("[MermaidDiskCache] Directory self-healed: created ${dir.absolutePath}")
            }
        }
        return dir
    }

    fun ensureDir(): Boolean = getMermaidDir().exists()

    /** 计算内容指纹作为图片唯一文件名（支持消息/会话 ID 前缀，避免冲突并便于追溯） */
    fun computeKey(source: String, themeKey: Int, sessionKey: String? = null): String {
        val prefix = if (!sessionKey.isNullOrBlank()) {
            val sanitized = sessionKey.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(32)
            "${sanitized}_"
        } else ""
        val input = "$source\n__THEME__:$themeKey"
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        val hash = digest.joinToString("") { "%02x".format(it) }.take(20)
        return "$prefix$hash"
    }

    fun getCacheFile(key: String): File {
        return File(getMermaidDir(), "$key.png")
    }

    /**
     * 从磁盘读取有效位图。
     * 若文件不存在、为 0 字节或内容损坏无法解码，自动执行自愈清理并返回 null。
     */
    fun readValidBitmap(key: String): ImageBitmap? {
        val file = getCacheFile(key)
        if (!file.exists()) return null

        if (file.length() == 0L) {
            println("[MermaidDiskCache] Corrupted 0-byte file detected: ${file.name}. Self-healing: deleting...")
            file.delete()
            return null
        }

        return try {
            val bytes = file.readBytes()
            val skiaImage = Image.makeFromEncoded(bytes)
            skiaImage.toComposeImageBitmap()
        } catch (e: Throwable) {
            println("[MermaidDiskCache] Failed to decode ${file.name} (${e.message}). Self-healing: deleting corrupted file...")
            file.delete()
            null
        }
    }

    /**
     * 原子安全写入 PNG 字节。
     */
    fun savePng(key: String, bytes: ByteArray) {
        try {
            val dir = getMermaidDir()
            val targetFile = File(dir, "$key.png")
            val tempFile = File(dir, "$key.tmp.${System.currentTimeMillis()}")

            tempFile.writeBytes(bytes)
            if (tempFile.renameTo(targetFile)) {
                println("[MermaidDiskCache] Successfully cached diagram: ${targetFile.name} (${bytes.size} bytes)")
            } else {
                // 如果 rename 失败（如跨文件系统挂载点），回退直接覆写
                targetFile.writeBytes(bytes)
                tempFile.delete()
                println("[MermaidDiskCache] Cached diagram via direct write: ${targetFile.name} (${bytes.size} bytes)")
            }
        } catch (e: Throwable) {
            println("[MermaidDiskCache] Error writing cache for $key: ${e.message}")
            try {
                // 异常时再次确保目录并直接写入
                getMermaidDir().mkdirs()
                File(getMermaidDir(), "$key.png").writeBytes(bytes)
            } catch (ignored: Throwable) {}
        }
    }
}
