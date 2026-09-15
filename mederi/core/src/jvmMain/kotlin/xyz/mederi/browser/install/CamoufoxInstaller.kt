package xyz.mederi.browser.install

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import xyz.mederi.debug.DebugLog
import xyz.mederi.http.MederiHttpClientFactory
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Camoufox 下载/安装/更新管理器。
 *
 * - 从 GitHub 官方 releases（daijro/camoufox）下载当前平台对应的版本。
 * - 命名规则 `camoufox-{version}-{os}.{arch}.zip`；**不保证各平台同步**——
 *   `findLatestForPlatform` 从最新 release 往回找第一个有本平台 asset 的版本。
 * - 平台无官方 asset（如 win.arm64）→ [CamoufoxPlatform.supported]=false，抛 [CamoufoxUnsupportedException]。
 * - 安装信息写入 {browserHome}/version.json，启动时 `checkForUpdate` 比对是否落后。
 *
 * 目录/下载都必须在设置好的 [BrowserHome] 内（强制设置目录，浏览器体积大，不许落临时目录）。
 */
class CamoufoxInstaller(
    private val home: BrowserHome
) {
    private val client: HttpClient by lazy {
        HttpClient(CIO) {
            install(UserAgent) { agent = MederiHttpClientFactory.userAgent }
            engine {
                requestTimeout = 0          // 大文件下载不设请求超时
            }
        }
    }

    // ── 查询 ──

    /**
     * 检查更新：读取已安装版本，查询本平台可用的最新版本，比对。
     * 不下载。返回更新信息（含是否落后）。
     */
    suspend fun checkForUpdate(): CamoufoxUpdateInfo {
        if (!CamoufoxPlatform.supported) {
            return CamoufoxUpdateInfo(supported = false, reason = CamoufoxPlatform.unsupportedReason)
        }
        val installed = readInstalledVersion()
        val latest = findLatestForPlatform()
        if (latest == null) {
            return CamoufoxUpdateInfo(
                installedVersion = installed?.version,
                hasUpdate = false,
                reason = "GitHub 上找不到 ${CamoufoxPlatform.displayName} 的 Camoufox release"
            )
        }
        val hasUpdate = installed?.version != latest.tagName
        return CamoufoxUpdateInfo(
            installedVersion = installed?.version,
            latestAvailableVersion = latest.tagName,
            hasUpdate = hasUpdate,
            reason = if (hasUpdate) "已安装 ${installed?.version ?: "无"}，最新 ${latest.tagName}" else "已是最新 ${latest.tagName}"
        )
    }

    /**
     * 查找当前平台可用的最新 release。
     * 从 `/releases/latest` 开始；没有本平台 asset 就往回翻 release 列表
     * （跨平台发布不同步——某平台可能没发最新版），直到找到第一个有 asset 的版本。
     */
    suspend fun findLatestForPlatform(): CamoufoxRelease? {
        val suffix = CamoufoxPlatform.assetSuffix
        // 先看 latest
        val latest = try {
            fetchReleases("https://api.github.com/repos/daijro/camoufox/releases/latest")
        } catch (e: Exception) {
            DebugLog.error("Camoufox", "latest release 查询失败: ${e.message}")
            null
        }
        if (latest?.assets?.any { it.name.endsWith(suffix) } == true) return latest

        // 往回翻 release 列表（每页 30，翻到 20 页=600 个仍未找到即放弃）
        for (page in 1..20) {
            val releases = try {
                fetchReleaseList(page)
            } catch (e: Exception) {
                DebugLog.error("Camoufox", "release 列表 page=$page 查询失败: ${e.message}")
                break
            }
            if (releases.isEmpty()) break
            releases.firstOrNull { it.assets.any { a -> a.name.endsWith(suffix) } }?.let { return it }
        }
        return null
    }

    /**
     * 下载并安装指定版本（默认 = 本平台最新可用），写入 version.json，返回二进制路径。
     * release 解析走真实 GitHub；asset 字节下载默认走 release 的真实 URL。
     */
    suspend fun install(versionTag: String? = null): String {
        if (!CamoufoxPlatform.supported) throw CamoufoxUnsupportedException(CamoufoxPlatform.unsupportedReason)
        home.ensureDirectories()

        val release = when {
            versionTag != null -> fetchReleaseByTag(versionTag)
                ?: throw CamoufoxNotFoundException("找不到 release: $versionTag")
            else -> findLatestForPlatform()
                ?: throw CamoufoxNotFoundException(
                    "GitHub 上找不到 ${CamoufoxPlatform.displayName} 的 Camoufox release"
                )
        }
        val asset = release.platformAsset()
            ?: throw CamoufoxNotFoundException(
                "release ${release.tagName} 没有 ${CamoufoxPlatform.assetSuffix} 的 asset"
            )
        return installFromRelease(release, asset)
    }

    /**
     * 管线核心：下载 asset → 解压 → 探测二进制 → 写 version.json → 返回二进制路径。
     * 独立出来供测试（真实 release 解析 + 可替换 asset 字节）。默认下载真实 URL。
     */
    internal suspend fun installFromRelease(
        release: CamoufoxRelease,
        asset: CamoufoxReleaseAsset,
        downloadUrlOverride: String? = null
    ): String {
        if (!CamoufoxPlatform.supported) throw CamoufoxUnsupportedException(CamoufoxPlatform.unsupportedReason)
        home.ensureDirectories()

        val versionDir = home.camoufoxVersionDir(release.tagName)
        val zipFile = File(home.camoufoxDir, "${release.tagName}.zip")

        // 已安装同版本且二进制存在 → 直接复用
        if (versionDir.exists() && findBinary(versionDir) != null) {
            return writeVersionInfo(release, versionDir, asset)
        }

        // 下载（覆盖旧的同名 zip）
        val url = downloadUrlOverride ?: asset.browserDownloadUrl
        DebugLog.info("Camoufox", "downloading ${asset.name} (${asset.size / 1024 / 1024} MB) → $zipFile")
        download(url, zipFile)

        // 解压到 {camoufox}/{version}/
        if (versionDir.exists()) versionDir.deleteRecursively()
        extractZip(zipFile, versionDir)

        val binary = findBinary(versionDir)
            ?: throw IOException("解压后找不到 Camoufox 可执行文件: ${versionDir.absolutePath}")

        // 清理 zip，写版本信息
        zipFile.delete()
        DebugLog.info("Camoufox", "installed ${release.tagName} → $binary")
        return writeVersionInfo(release, versionDir, asset, binary.absolutePath)
    }

    // ── 读取 ──

    /** 读取已安装版本信息（无 version.json 返回 null）。 */
    fun readInstalledVersion(): CamoufoxVersionInfo? {
        if (!home.versionFile.exists()) return null
        return try {
            CamoufoxJson.json.decodeFromString(
                CamoufoxVersionInfo.serializer(),
                home.versionFile.readText()
            )
        } catch (e: Exception) {
            DebugLog.error("Camoufox", "version.json 解析失败: ${e.message}")
            null
        }
    }

    /** 当前可用二进制路径：version.json 记录 + 文件存在性校验。 */
    fun installedBinaryPath(): String? {
        val info = readInstalledVersion() ?: return null
        val binary = File(info.binaryPath)
        return if (binary.exists()) info.binaryPath else null
    }

    // ── 内部 ──

    internal suspend fun download(url: String, target: File) {
        client.prepareGet(url).execute { response ->
            if (!response.status.isSuccess()) {
                throw IOException("下载失败: HTTP ${response.status.value} ($url)")
            }
            val channel = response.bodyAsChannel()
            target.outputStream().use { out ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    val n = channel.readAvailable(buffer, 0, buffer.size)
                    if (n <= 0) break
                    out.write(buffer, 0, n)
                }
            }
        }
    }

    internal fun extractZip(zipFile: File, targetDir: File) {
        targetDir.mkdirs()
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val outFile = File(targetDir, entry.name)
                // zip slip 防护：解压目标必须落在 targetDir 内
                if (!outFile.canonicalPath.startsWith(targetDir.canonicalPath + File.separator)) {
                    throw IOException("zip 条目越界: ${entry.name}")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { out -> zis.copyTo(out) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /** 在版本目录里找当前平台的 Camoufox 可执行文件。 */
    internal fun findBinary(versionDir: File): File? {
        if (!versionDir.isDirectory) return null
        val osName = System.getProperty("os.name").lowercase()
        val candidate = versionDir.walkTopDown().firstOrNull { file ->
            val name = file.name
            when {
                osName.contains("mac") -> file.isDirectory && name.endsWith(".app", ignoreCase = true)
                osName.contains("win") -> file.isFile && name.endsWith(".exe", ignoreCase = true) &&
                    name.contains("camoufox", ignoreCase = true)
                else -> file.isFile && name.contains("camoufox", ignoreCase = true)
            }
        } ?: return null

        val binary = if (osName.contains("mac")) {
            // macOS 必须指向 .app/Contents/MacOS/{name}
            val bin = File(candidate, "Contents/MacOS/${candidate.nameWithoutExtension}")
            if (bin.exists()) bin else return null
        } else {
            candidate
        }
        // 防御：解压偶尔丢执行位（zip 不保证权限），尝试补上；仍不可执行则报错
        if (!osName.contains("win") && !binary.canExecute()) {
            runCatching { binary.setExecutable(true, false) }
        }
        return if (osName.contains("win") || binary.canExecute()) binary else null
    }

    private fun writeVersionInfo(
        release: CamoufoxRelease,
        versionDir: File,
        asset: CamoufoxReleaseAsset,
        binaryPath: String = ""
    ): String {
        val binary = binaryPath.ifBlank {
            findBinary(versionDir)?.absolutePath ?: ""
        }
        val info = CamoufoxVersionInfo(
            version = release.tagName,
            os = CamoufoxPlatform.os,
            arch = CamoufoxPlatform.arch,
            assetName = asset.name,
            binaryPath = binary,
            installedAt = Instant.now().toString()
        )
        home.versionFile.writeText(CamoufoxJson.json.encodeToString(CamoufoxVersionInfo.serializer(), info))
        return binary
    }

    private suspend fun fetchReleases(url: String): CamoufoxRelease? {
        val text = client.get(url).bodyAsText()
        return CamoufoxJson.json.decodeFromString(CamoufoxRelease.serializer(), text)
    }

    private suspend fun fetchReleaseList(page: Int): List<CamoufoxRelease> {
        val url = "https://api.github.com/repos/daijro/camoufox/releases?per_page=30&page=$page"
        val text = client.get(url).bodyAsText()
        return CamoufoxJson.json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(CamoufoxRelease.serializer()),
            text
        )
    }

    private suspend fun fetchReleaseByTag(tag: String): CamoufoxRelease? {
        val url = "https://api.github.com/repos/daijro/camoufox/releases/tags/$tag"
        return try {
            fetchReleases(url)
        } catch (e: Exception) {
            DebugLog.error("Camoufox", "release by tag $tag 查询失败: ${e.message}")
            null
        }
    }
}

class CamoufoxUnsupportedException(message: String) : RuntimeException(message)
class CamoufoxNotFoundException(message: String) : RuntimeException(message)
