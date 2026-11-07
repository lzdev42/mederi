package xyz.mederi.browser.install

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.net.InetSocketAddress
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Camoufox 安装管线测试。
 *
 * 由本地 HTTP 提供 release 元数据和平台 ZIP，覆盖查询、下载、解压和版本记录。
 */
class CamoufoxInstallerPipelineTest {

    // ── 工具：按当前平台构造真实结构的 Camoufox zip ──

    private fun buildFakeCamoufoxZip(): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            fun add(name: String, content: String) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
            // 顶部一个 README（像真实包里那样在根目录）
            add("README.txt", "camoufox test fixture")
            when (CamoufoxPlatform.os) {
                "mac" -> add("Camoufox.app/Contents/MacOS/Camoufox", "#!/bin/sh\nexit 0\n")
                "win" -> add("camoufox.exe", "MZ-test-exe")
                else -> add("camoufox", "#!/bin/sh\nexit 0\n")
            }
        }
        return baos.toByteArray()
    }

    private fun tempHome(): BrowserHome {
        val dir = java.nio.file.Files.createTempDirectory("camoufox-test-home-").toFile()
        return BrowserHome(dir)
    }

    // ── 测试 ──

    @Test
    fun `download preserves bytes from HTTP response`() = runBlocking {
        val bytes = ByteArray(256 * 1024 + 17) { it.toByte() }
        val home = tempHome()
        try {
            CamoufoxReleaseServer().use { server ->
                server.respond("/download", bytes)
                val target = File(home.rootDir, "download.bin")
                withTimeout(30_000) {
                    CamoufoxInstaller(home).download("${server.baseUrl}/download", target)
                }
                assertContentEquals(bytes, target.readBytes())
            }
        } finally {
            home.rootDir.deleteRecursively()
        }
    }

    /** 解压：真实 zip 格式 → 结构正确落盘 + zip-slip 防护生效。 */
    @Test
    fun `extractZip extracts real zip and rejects path escape`() {
        val installer = CamoufoxInstaller(tempHome())
        val zip = File.createTempFile("camoufox-", ".zip")
        zip.writeBytes(buildFakeCamoufoxZip())

        val target = java.nio.file.Files.createTempDirectory("camoufox-extract-").toFile()
        installer.extractZip(zip, target)

        // 真实结构在盘上
        val expected = when (CamoufoxPlatform.os) {
            "mac" -> File(target, "Camoufox.app/Contents/MacOS/Camoufox")
            "win" -> File(target, "camoufox.exe")
            else -> File(target, "camoufox")
        }
        assertTrue(expected.exists(), "解压后应有二进制: $expected")

        // zip-slip：条目 ../ 越界必须拒绝
        val evilZip = File.createTempFile("evil-", ".zip")
        ZipOutputStream(evilZip.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("../evil.txt"))
            zos.write("x".toByteArray())
            zos.closeEntry()
        }
        val escaped = runCatching { installer.extractZip(evilZip, target) }
        assertTrue(escaped.isFailure, "越界 zip 必须抛异常")
        evilZip.delete(); zip.delete()
    }

    /** findBinary：真实结构 → 能定位到可执行二进制（含执行位修复）。 */
    @Test
    fun `findBinary locates executable from extracted structure`() {
        val installer = CamoufoxInstaller(tempHome())
        val dir = java.nio.file.Files.createTempDirectory("camoufox-bin-").toFile()
        val zip = File.createTempFile("camoufox-", ".zip")
        zip.writeBytes(buildFakeCamoufoxZip())
        installer.extractZip(zip, dir)

        val binary = installer.findBinary(dir)
        assertNotNull(binary, "应在解压结构里找到二进制")
        // mac 必须指向 .app/Contents/MacOS 下的二进制
        if (CamoufoxPlatform.os == "mac") {
            assertTrue(binary!!.absolutePath.contains("Contents${File.separator}MacOS"), "mac 必须指向 MacOS 内二进制")
        }
        assertTrue(binary!!.exists())
        // 不存在的目录 → null
        assertNull(installer.findBinary(File.createTempFile("empty-", "").apply { delete() }.also { it.mkdir() }))
        zip.delete()
    }

    @Test
    fun `install follows release asset URL and records installed version`() = runBlocking {
        if (!CamoufoxPlatform.supported) return@runBlocking

        val home = tempHome()
        try {
            CamoufoxReleaseServer().use { server ->
                val release = server.platformRelease("v1.0.0")
                server.respondLatest(release)
                server.respond("/camoufox.zip", buildFakeCamoufoxZip())

                val installer = CamoufoxInstaller(home, server.releasesUrl)
                val binaryPath = withTimeout(30_000) { installer.install() }
                assertTrue(File(binaryPath).isFile, "安装后应有可执行文件: $binaryPath")

                val info = assertNotNull(installer.readInstalledVersion(), "应写入 version.json")
                assertEquals(release.tagName, info.version)
                assertEquals(CamoufoxPlatform.os, info.os)
                assertEquals(CamoufoxPlatform.arch, info.arch)
                assertEquals(release.platformAsset()?.name, info.assetName)
                assertEquals(binaryPath, installer.installedBinaryPath())
                assertTrue(!File(home.camoufoxDir, "${release.tagName}.zip").exists(), "安装后 zip 应被清理")
            }
        } finally {
            home.rootDir.deleteRecursively()
        }
    }

    /** version.json 读写 + 存在性校验。 */
    @Test
    fun `version json round trips and validates binary existence`() { runBlocking {
        val home = tempHome()
        val installer = CamoufoxInstaller(home)

        assertNull(installer.readInstalledVersion())
        assertNull(installer.installedBinaryPath())

        // 写一个指向真实存在文件的 version.json
        val realFile = File.createTempFile("camoufox-binary-", ".bin")
        val release = CamoufoxRelease(tagName = "vTest", assets = listOf(CamoufoxReleaseAsset(name = "camoufox-vTest${CamoufoxPlatform.assetSuffix}")))
        val versionDir = home.camoufoxVersionDir("vTest")
        versionDir.mkdirs()
        // 通过 writeVersionInfo 的完整路径（用 installFromRelease 的复用分支触达：预置同结构）
        val zip = File.createTempFile("camoufox-", ".zip")
        zip.writeBytes(buildFakeCamoufoxZip())
        installer.extractZip(zip, versionDir)
        zip.delete()

        val asset = CamoufoxReleaseAsset(name = "camoufox-vTest${CamoufoxPlatform.assetSuffix}")
        val path = installer.installFromRelease(release, asset)
        assertTrue(File(path).exists())
        assertEquals(path, installer.installedBinaryPath())

        // 二进制被删 → installedBinaryPath 返回 null（不返回假路径）
        File(path).deleteRecursively()
        assertNull(installer.installedBinaryPath(), "二进制不存在时不应返回路径")
        }
    }
}

internal class CamoufoxReleaseServer : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { start() }
    val baseUrl = "http://127.0.0.1:${server.address.port}"
    val releasesUrl = "$baseUrl/releases"

    fun platformRelease(tag: String) = CamoufoxRelease(
        tagName = tag,
        assets = listOf(
            CamoufoxReleaseAsset(
                name = "camoufox-$tag${CamoufoxPlatform.assetSuffix}",
                browserDownloadUrl = "$baseUrl/camoufox.zip"
            )
        )
    )

    fun respondLatest(release: CamoufoxRelease) {
        respond("/releases/latest", CamoufoxJson.json.encodeToString(CamoufoxRelease.serializer(), release).toByteArray())
    }

    fun respondHistory(vararg releases: CamoufoxRelease) {
        val body = CamoufoxJson.json.encodeToString(ListSerializer(CamoufoxRelease.serializer()), releases.toList())
        respond("/releases", body.toByteArray())
    }

    fun respond(route: String, bytes: ByteArray) {
        server.createContext(route) { exchange ->
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    override fun close() = server.stop(0)
}
