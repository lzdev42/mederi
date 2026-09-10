package xyz.mederi.core.contract.preferences

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import okio.FileSystem
import okio.IOException
import okio.Path

/**
 * 跨平台 JSON 文件偏好存储（commonMain 唯一实现，jvm / android / ios 共用）。
 *
 * 各平台注入文件路径与 [FileSystem]（见各平台 PreferencesFactory），读写逻辑统一在此：
 * - 所有读写用 [Mutex] 串行化，避免并发"读-改-写"导致的丢失更新（丢 key）。
 * - 写入走临时文件 + 原子 rename，避免半写损坏主文件。
 * - 解析失败时**不**静默用空数据覆盖原文件：先备份损坏文件再继续，保证不丢数据。
 *
 * wasmJs 无文件系统概念，浏览器端走 WasmJsPreferencesStore（localStorage）。
 */
class JsonFilePreferencesStore(
    private val file: Path,
    private val fileSystem: FileSystem,
) : PreferencesStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()

    override suspend fun getString(key: String): String? = mutex.withLock {
        load().items[key]
    }

    override suspend fun putString(key: String, value: String) = mutex.withLock {
        ensureParentDir()
        writeAll(load().items + (key to value))
    }

    override suspend fun remove(key: String) = mutex.withLock {
        ensureParentDir()
        writeAll(load().items - key)
    }

    private fun ensureParentDir() {
        file.parent?.let { fileSystem.createDirectories(it) }
    }

    /** 读取并解析；解析失败时备份原文件并返回空，绝不静默覆盖原文件。 */
    private fun load(): PreferencesData {
        if (!fileSystem.exists(file)) return PreferencesData()
        val content = fileSystem.read(file) { readUtf8() }.trim()
        if (content.isEmpty()) return PreferencesData()
        return runCatching { json.decodeFromString<PreferencesData>(content) }
            .getOrElse { e ->
                println("[Mederi] 偏好文件解析失败，已备份原文件: ${e.message}")
                backupCorruptFile()
                PreferencesData()
            }
    }

    private fun backupCorruptFile() {
        val backup = (file.parent ?: file) / "${file.name}.corrupt-${Clock.System.now().toEpochMilliseconds()}.bak"
        runCatching { fileSystem.copy(file, backup) }
            .onFailure { println("[Mederi] 备份损坏的偏好文件失败: ${it.message}") }
    }

    /** 原子写：先写临时文件，再原子 rename 覆盖主文件；不支持原子 move 的文件系统（如 Windows 已有目标）回退删除+复制。 */
    private fun writeAll(items: Map<String, String>) {
        val tmp = (file.parent ?: file) / "${file.name}.tmp"
        fileSystem.write(tmp) { writeUtf8(json.encodeToString(PreferencesData(items))) }
        try {
            fileSystem.atomicMove(tmp, file)
        } catch (_: IOException) {
            // 回退路径存在极短的"无主文件"窗口，仅在不支持原子 move 的环境出现
            fileSystem.delete(file)
            fileSystem.copy(tmp, file)
            fileSystem.delete(tmp)
        }
    }
}

@Serializable
data class PreferencesData(val items: Map<String, String> = emptyMap())
