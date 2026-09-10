package xyz.mederi.core.contract.preferences

import kotlinx.cinterop.ExperimentalForeignApi
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL

actual fun defaultPreferencesStore(): PreferencesStore =
    JsonFilePreferencesStore(preferencesFilePath(), FileSystem.SYSTEM)

/** iOS 沙盒内 Documents 目录下的偏好文件路径（应用卸载前持久，iTunes/iCloud 可备份）。 */
@OptIn(ExperimentalForeignApi::class)
private fun preferencesFilePath(): Path {
    val documentsUrl = NSFileManager.defaultManager.URLForDirectory(
        NSDocumentDirectory, 1u, null, true, null
    )
    val dir = (documentsUrl as? NSURL)?.path ?: NSHomeDirectory()
    return "$dir/preferences.json".toPath()
}
