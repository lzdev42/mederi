package xyz.mederi.browser.install

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.InetSocketAddress
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Camoufox 安装管线测试。
 *
 * release 解析走**真实 GitHub**（findLatestForPlatform 真调 API 拿真实 release/asset）。
 * 唯一无法避免的替换：300MB 真实二进制字节 —— 用本地 HTTP 服务器提供一个小 zip，
 * 但 zip 内部结构与真实 Camoufox 一致（mac=.app/Contents/MacOS，win=.exe，lin=camoufox 可执行）。
 * 这样 download→解压→findBinary→version.json→路径 全链路都是真实代码路径。
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

    /** 起一个本地 HTTP 服务器，返回 (server, baseUrl)；服务一个路径返回指定字节。 */
    private fun serve(route: String, bytes: ByteArray): Pair<HttpServer, String> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(route) { exchange ->
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server to "http://127.0.0.1:${server.address.port}"
    }

    private fun tempHome(): BrowserHome {
        val dir = java.nio.file.Files.createTempDirectory("camoufox-test-home-").toFile()
        return BrowserHome(dir)
    }

    // ── 测试 ──

    /** 真 HTTP 下载：从 GitHub 真实下载一个小的真实文件（releases API JSON），校验字节。 */
    @Test
    fun `download fetches real bytes over real HTTP`() { runBlocking {
        val installer = CamoufoxInstaller(tempHome())
        val target = File.createTempFile("camoufox-dl-", ".json")
        withTimeout(30_000) {
            installer.download("https://api.github.com/repos/daijro/camoufox/releases/latest", target)
        }
        val text = target.readText()
        assertTrue(text.contains("tag_name"), "应下载到真实 GitHub API 响应（含 tag_name），实际: ${text.take(80)}")
        target.delete()
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

    /**
     * 端到端管线：真实 GitHub 解析出真实 release + 真实 asset 名，
     * 字节源换成本地小 zip（唯一替换），跑完整 installFromRelease。
     */
    @Test
    fun `installFromRelease runs full pipeline with real GitHub release`() { runBlocking {
        if (!CamoufoxPlatform.supported) return@runBlocking

        val home = tempHome()
        val installer = CamoufoxInstaller(home)

        // 1) 真实 GitHub：拿到本平台真实最新 release + 真实 asset
        val release = withTimeout(30_000) { installer.findLatestForPlatform() }
        assertNotNull(release, "真实 GitHub 应返回 release")
        val asset = release!!.platformAsset()
        assertNotNull(asset, "release ${release.tagName} 应有本平台 asset: ${release.assets.map { it.name }}")

        // 2) 本地服务器服务一个小 zip（结构同真实）
        val (server, baseUrl) = serve("/camoufox.zip", buildFakeCamoufoxZip())

        // 3) 跑完整管线：下载→解压→找二进制→写 version.json
        val binaryPath = withTimeout(60_000) {
            installer.installFromRelease(release, asset, downloadUrlOverride = "$baseUrl/camoufox.zip")
        }

        // 4) 断言
        val binary = File(binaryPath)
        assertTrue(binary.exists(), "返回的二进制路径应存在: $binaryPath")

        // version.json 记录真实 tag
        val info = installer.readInstalledVersion()
        assertNotNull(info, "应写入 version.json")
        assertEquals(release.tagName, info!!.version, "version.json 应记录真实 tag")
        assertEquals(CamoufoxPlatform.os, info.os)
        assertEquals(CamoufoxPlatform.arch, info.arch)
        assertEquals(binaryPath, info.binaryPath)

        // 二次读取：installedBinaryPath 走 version.json 拿回同一路径
        assertEquals(binaryPath, installer.installedBinaryPath())

        // 已装同版本 → install() 直接复用，不再下载（zip 已被清理）
        val zipFile = File(home.camoufoxDir, "${release.tagName}.zip")
        assertTrue(!zipFile.exists(), "安装后 zip 应被清理")

        server.stop(0)
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
