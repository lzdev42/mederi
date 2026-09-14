package xyz.mederi.skills

import ai.koog.rag.base.files.JVMFileSystemProvider
import ai.koog.skills.discovery.discoverSkills
import ai.koog.skills.model.Skill
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import xyz.mederi.api.exception.MederiNotFoundException
import xyz.mederi.api.exception.MederiValidationException
import xyz.mederi.skills.domain.SkillInfo
import xyz.mederi.store.SettingsStore
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * [SkillManager] 默认实现。
 *
 * - 扫描/发现：Koog `discoverSkills()` 扫根目录下的 SKILL.md（目录里放了就是已安装，天然自动发现）
 * - 根目录：持久化在 [SettingsStore]，key=`skills.root`，默认 [defaultSkillsRoot]
 * - 安装：Ktor 下载 zip → ZipInputStream 解压到临时目录 → 扫描验证有 SKILL.md → 移入根目录
 * - 卸载：删除根目录下对应目录
 *
 * core 当前仅 jvm 目标，java.io / ZipInputStream 属 JVM 侧实现。
 */
class SkillManagerImpl(
    private val settingsStore: SettingsStore,
    private val defaultSkillsRoot: String,
    private val downloader: suspend (String) -> ByteArray = SkillManagerImpl::downloadFromUrl,
) : SkillManager {

    private companion object {
        const val ROOT_KEY = "skills.root"
        const val SKILL_MD_FILE = "SKILL.md"
        val SKILL_NAME_PATTERN = Regex("^[a-zA-Z0-9_-]+$")

        // 静态复用；downloadFromUrl 可作默认参数引用（companion 函数先于实例构造）
        val httpClient = HttpClient()

        suspend fun downloadFromUrl(url: String): ByteArray =
            httpClient.get(url).body()
    }

    override suspend fun list(): List<SkillInfo> {
        val root = rootDirectory()
        if (!File(root).exists()) return emptyList()
        return discoverSkills(JVMFileSystemProvider.ReadOnly, listOf(root)).map { it.toSkillInfo() }
    }

    override suspend fun getRootDirectory(): String = rootDirectory()

    override suspend fun setRootDirectory(path: String) {
        val trimmed = path.trim()
        if (trimmed.isBlank()) throw MederiValidationException("skill 根目录不能为空")
        val expanded = expandUserHome(trimmed)
        File(expanded).mkdirs()
        settingsStore.set(ROOT_KEY, expanded)
    }

    override suspend fun install(url: String): SkillInfo {
        val trimmed = url.trim()
        if (trimmed.isBlank()) throw MederiValidationException("下载地址不能为空")
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            throw MederiValidationException("下载地址必须是 http(s) URL: $trimmed")
        }

        val root = rootDirectory()
        val zipBytes = download(trimmed)

        // 解压到临时目录，扫描验证内容，再逐个移入根目录
        val tempDir = createTempDirectory("mederi-skill-install")
        try {
            unzip(zipBytes, tempDir)
            val discovered = discoverSkills(JVMFileSystemProvider.ReadOnly, listOf(tempDir.absolutePath))
            if (discovered.isEmpty()) {
                throw MederiValidationException("压缩包内未找到有效的 SKILL.md（缺少 name/description frontmatter）")
            }

            // 已存在同名 skill → 先删除再装（install 幂等，重新安装场景不报错）
            val installed = discovered.map { skill ->
                val targetDir = File(root, skill.name)
                if (targetDir.exists()) targetDir.deleteRecursively()
                targetDir.parentFile?.mkdirs()
                File(skill.location).parentFile.copyRecursively(targetDir, overwrite = true)
                SkillInfo(
                    name = skill.name,
                    description = skill.description,
                    location = File(targetDir, SKILL_MD_FILE).absolutePath,
                    license = skill.license,
                    compatibility = skill.compatibility,
                    allowedTools = skill.allowedTools,
                )
            }
            return installed.first()
        } catch (e: MederiValidationException) {
            throw e
        } catch (e: MederiNotFoundException) {
            throw e
        } finally {
            tempDir.deleteRecursively()
        }
    }

    override suspend fun uninstall(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) throw MederiValidationException("skill 名称不能为空")
        if (!SKILL_NAME_PATTERN.matches(trimmed)) {
            throw MederiValidationException("非法的 skill 名称: $trimmed")
        }
        val root = rootDirectory()
        val dir = File(root, trimmed)
        if (!dir.exists()) {
            throw MederiNotFoundException("skill '$trimmed' 不存在")
        }
        dir.deleteRecursively()
    }

    // ==================== 内部 ====================

    private suspend fun rootDirectory(): String {
        return settingsStore.get(ROOT_KEY) ?: defaultSkillsRoot
    }

    private suspend fun download(url: String): ByteArray {
        return try {
            downloader(url)
        } catch (e: MederiValidationException) {
            throw e
        } catch (e: Exception) {
            throw MederiValidationException("下载 skill 压缩包失败（$url）: ${e.message}", e)
        }
    }

    private fun unzip(zipBytes: ByteArray, targetDir: File) {
        ZipInputStream(zipBytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val entryPath = entry.name
                val outFile = File(targetDir, entryPath).normalize()
                // zip slip 防护：解压路径必须落在目标目录内
                if (!outFile.absolutePath.startsWith(targetDir.absolutePath)) {
                    throw MederiValidationException("压缩包包含非法路径: $entryPath")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { out -> zip.copyTo(out) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private fun createTempDirectory(prefix: String): File {
        val base = File(System.getProperty("java.io.tmpdir"))
        return java.nio.file.Files.createTempDirectory(base.toPath(), prefix).toFile()
    }

    private fun expandUserHome(path: String): String = when {
        path == "~" -> System.getProperty("user.home")
        path.startsWith("~/") -> System.getProperty("user.home") + path.substring(1)
        else -> path
    }

    private fun Skill.toSkillInfo() = SkillInfo(
        name = name,
        description = description,
        location = location,
        license = license,
        compatibility = compatibility,
        allowedTools = allowedTools,
    )
}
