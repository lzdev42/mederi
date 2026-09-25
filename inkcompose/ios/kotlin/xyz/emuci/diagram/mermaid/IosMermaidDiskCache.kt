package xyz.emuci.diagram.mermaid

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlin.concurrent.Volatile
import org.jetbrains.skia.Image
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataWithBytes
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal object IosMermaidDiskCache {

    private val defaultBaseDir: String by lazy {
        val cachesUrl = NSFileManager.defaultManager.URLForDirectory(
            NSCachesDirectory, 1u, null, true, null
        )
        val dir = (cachesUrl as? NSURL)?.path ?: (NSHomeDirectory() + "/Library/Caches")
        val path = "$dir/inkcompose"
        println("[IosMermaidDiskCache] defaultBaseDir initialized to: $path")
        path
    }

    @Volatile
    private var baseDir: String = defaultBaseDir

    fun setBaseDir(path: String) {
        val expanded = if (path.startsWith("~")) {
            NSHomeDirectory() + path.removePrefix("~")
        } else path
        // 防御：目标路径不可创建/不可写则拒绝切换并保留当前目录。
        // 场景：遥控端可能收到 server 机器的项目路径（设备上不存在/无沙盒权限），盲目切换会让缓存永久失效。
        val fm = NSFileManager.defaultManager
        val probeDir = "$expanded/mermaid"
        if (!fm.fileExistsAtPath(probeDir) &&
            !fm.createDirectoryAtPath(probeDir, withIntermediateDirectories = true, attributes = null, error = null)
        ) {
            println("[IosMermaidDiskCache] baseDir not writable, keep current: $baseDir (rejected: $expanded)")
            return
        }
        baseDir = expanded
        println("[IosMermaidDiskCache] baseDir updated to: $baseDir")
    }

    fun getBaseDir(): String = baseDir

    fun getMermaidDir(): String {
        val dir = "$baseDir/mermaid"
        val fm = NSFileManager.defaultManager
        if (!fm.fileExistsAtPath(dir)) {
            fm.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        }
        return dir
    }

    fun ensureDir(): Boolean {
        val dir = getMermaidDir()
        return NSFileManager.defaultManager.fileExistsAtPath(dir)
    }

    fun computeKey(source: String, themeKey: Int, sessionKey: String? = null): String {
        val prefix = if (!sessionKey.isNullOrBlank()) {
            val sanitized = sessionKey.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(32)
            "${sanitized}_"
        } else ""
        // 轻量指纹计算
        val input = "$source\n__THEME__:$themeKey"
        var hash = 0L
        for (ch in input) {
            hash = (hash * 31L + ch.code.toLong()) and 0x7fffffffffffffffL
        }
        val hashStr = hash.toString(16).padStart(16, '0')
        return "$prefix$hashStr"
    }

    fun getCacheFilePath(key: String): String = "${getMermaidDir()}/$key.png"

    fun readValidBitmap(key: String): ImageBitmap? {
        val path = getCacheFilePath(key)
        val fm = NSFileManager.defaultManager
        if (!fm.fileExistsAtPath(path)) return null

        val data = fm.contentsAtPath(path) ?: return null
        if (data.length.toInt() == 0) {
            fm.removeItemAtPath(path, null)
            return null
        }

        return try {
            val length = data.length.toInt()
            val bytes = ByteArray(length)
            bytes.usePinned { pinned ->
                memcpy(pinned.addressOf(0), data.bytes, data.length)
            }
            val skia = Image.makeFromEncoded(bytes)
            skia.toComposeImageBitmap()
        } catch (e: Throwable) {
            fm.removeItemAtPath(path, null)
            null
        }
    }

    fun savePng(key: String, bytes: ByteArray) {
        try {
            val path = getCacheFilePath(key)
            val tempPath = "$path.tmp.${platform.posix.time(null)}"
            val fm = NSFileManager.defaultManager
            val data = bytes.usePinned { pinned ->
                NSData.dataWithBytes(pinned.addressOf(0), bytes.size.toULong())
            }
            if (data != null) {
                fm.createFileAtPath(tempPath, data, null)
                if (fm.moveItemAtPath(tempPath, path, null)) {
                    println("[IosMermaidDiskCache] Successfully cached diagram: $key.png")
                } else {
                    fm.createFileAtPath(path, data, null)
                    fm.removeItemAtPath(tempPath, null)
                }
            }
        } catch (e: Throwable) {
            println("[IosMermaidDiskCache] Error saving PNG: ${e.message}")
        }
    }

    fun clearSession(sessionKey: String) {
        if (sessionKey.isBlank()) return
        val sanitized = sessionKey.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(32)
        val prefix = "${sanitized}_"
        val dir = getMermaidDir()
        val fm = NSFileManager.defaultManager
        if (fm.fileExistsAtPath(dir)) {
            val items = fm.contentsOfDirectoryAtPath(dir, null) ?: return
            for (item in items) {
                val name = item.toString()
                if (name.startsWith(prefix) && name.endsWith(".png")) {
                    fm.removeItemAtPath("$dir/$name", null)
                    println("[IosMermaidDiskCache] Deleted session cache: $name")
                }
            }
        }
    }
}
