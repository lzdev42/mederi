package xyz.emuci.diagram.mermaid

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.security.MessageDigest

internal object AndroidMermaidDiskCache {

    private val defaultBaseDir: File by lazy {
        val tmp = System.getProperty("java.io.tmpdir") ?: "/data/local/tmp"
        val dir = File(tmp, "inkcompose")
        println("[AndroidMermaidDiskCache] defaultBaseDir initialized to: ${dir.absolutePath}")
        dir
    }

    @Volatile
    private var baseDir: File = defaultBaseDir

    fun setBaseDir(path: String) {
        val expanded = if (path.startsWith("~")) {
            val userHome = System.getProperty("user.home") ?: "."
            userHome + path.removePrefix("~")
        } else path
        val candidate = File(expanded)
        // 防御：目标路径不可创建/不可写则拒绝切换并保留当前目录。
        // 场景：遥控端可能收到 server 机器的项目路径（设备上不存在/无权限），盲目切换会让缓存永久失效。
        val probe = File(candidate, "mermaid")
        if (!probe.isDirectory && !probe.mkdirs()) {
            println("[AndroidMermaidDiskCache] baseDir not writable, keep current: ${baseDir.absolutePath} (rejected: $expanded)")
            return
        }
        baseDir = candidate
        println("[AndroidMermaidDiskCache] baseDir updated to: ${baseDir.absolutePath}")
    }

    fun getBaseDir(): String = baseDir.absolutePath

    fun getMermaidDir(): File {
        val dir = File(baseDir, "mermaid")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun ensureDir(): Boolean = getMermaidDir().exists()

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

    fun readValidBitmap(key: String): ImageBitmap? {
        val file = getCacheFile(key)
        if (!file.exists() || file.length() == 0L) return null
        return try {
            val bytes = file.readBytes()
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            bitmap?.asImageBitmap()
        } catch (e: Throwable) {
            file.delete()
            null
        }
    }

    fun savePng(key: String, bytes: ByteArray) {
        try {
            val dir = getMermaidDir()
            val targetFile = File(dir, "$key.png")
            val tempFile = File(dir, "$key.tmp.${System.currentTimeMillis()}")
            tempFile.writeBytes(bytes)
            if (!tempFile.renameTo(targetFile)) {
                targetFile.writeBytes(bytes)
                tempFile.delete()
            }
        } catch (e: Throwable) {
            println("[AndroidMermaidDiskCache] Error writing cache for $key: ${e.message}")
        }
    }

    fun clearSession(sessionKey: String) {
        if (sessionKey.isBlank()) return
        val sanitized = sessionKey.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(32)
        val prefix = "${sanitized}_"
        val dir = getMermaidDir()
        if (dir.exists() && dir.isDirectory) {
            val files = dir.listFiles { _, name -> name.startsWith(prefix) && name.endsWith(".png") }
            files?.forEach { file ->
                val deleted = file.delete()
                println("[AndroidMermaidDiskCache] Deleted session cache: ${file.name}, success=$deleted")
            }
        }
    }
}
