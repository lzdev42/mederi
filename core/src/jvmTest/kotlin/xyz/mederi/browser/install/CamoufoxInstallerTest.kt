package xyz.mederi.browser.install

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CamoufoxInstallerTest {

    @Test
    fun `platform detection produces a known os and arch`() {
        assertTrue(CamoufoxPlatform.os in setOf("mac", "lin", "win"), "os=${CamoufoxPlatform.os}")
        assertTrue(CamoufoxPlatform.arch in setOf("arm64", "x86_64", "i686"), "arch=${CamoufoxPlatform.arch}")
        // win.arm64 官方无 asset → supported=false
        if (CamoufoxPlatform.os == "win" && CamoufoxPlatform.arch == "arm64") {
            assertTrue(!CamoufoxPlatform.supported)
        }
    }

    @Test
    fun `asset suffix matches correct file names`() {
        // 覆盖全平台组合：无论当前系统是什么，platformAsset 都能从列表里挑出正确那个
        val suffix = CamoufoxPlatform.assetSuffix
        val allCombos = listOf(
            "camoufox-1.0.0-lin.arm64.zip", "camoufox-1.0.0-lin.x86_64.zip",
            "camoufox-1.0.0-mac.arm64.zip", "camoufox-1.0.0-mac.x86_64.zip",
            "camoufox-1.0.0-win.i686.zip", "camoufox-1.0.0-win.x86_64.zip"
        )
        val fakeRelease = CamoufoxRelease(
            tagName = "v1.0.0",
            assets = allCombos.map { CamoufoxReleaseAsset(name = it) }
        )
        // 当前平台不在官方支持列表（win.arm64）时没有匹配——这个由 supported 表达
        if (CamoufoxPlatform.supported) {
            val matching = allCombos.first { it.endsWith(suffix) }
            assertEquals(matching, fakeRelease.platformAsset()?.name)
        } else {
            assertEquals(null, fakeRelease.platformAsset()?.name)
        }
    }

    @Test
    fun `findLatestForPlatform searches history for a matching asset`(): Unit = runBlocking {
        val home = BrowserHome(Files.createTempDirectory("camoufox-home-").toFile())
        try {
            CamoufoxReleaseServer().use { server ->
                val latest = CamoufoxRelease(tagName = "v2.0.0")
                val compatible = server.platformRelease("v1.0.0")
                server.respondLatest(latest)
                server.respondHistory(latest, compatible)
                val installer = CamoufoxInstaller(home, server.releasesUrl)

                val release = withTimeout(30_000) { installer.findLatestForPlatform() }
                assertEquals(compatible, release)
                assertNotNull(release?.platformAsset())
            }
        } finally {
            home.rootDir.deleteRecursively()
        }
    }

    @Test
    fun `checkForUpdate reports latest version without installation`() = runBlocking {
        val home = BrowserHome(Files.createTempDirectory("camoufox-home-").toFile())
        try {
            CamoufoxReleaseServer().use { server ->
                val latest = server.platformRelease("v1.0.0")
                server.respondLatest(latest)
                val installer = CamoufoxInstaller(home, server.releasesUrl)

                val info = withTimeout(30_000) { installer.checkForUpdate() }
                assertEquals(CamoufoxPlatform.supported, info.supported)
                assertNull(info.installedVersion)
                if (CamoufoxPlatform.supported) {
                    assertTrue(info.hasUpdate)
                    assertEquals(latest.tagName, info.latestAvailableVersion)
                }
            }
        } finally {
            home.rootDir.deleteRecursively()
        }
    }
}
