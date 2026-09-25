package xyz.mederi.browser.install

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
    fun `findLatestForPlatform returns a release with current platform asset`() = runBlocking {
        // 真实网络调用（GitHub API）——本地验证官方命名/存在性
        val home = BrowserHome(java.io.File.createTempFile("camoufox-home-", "").apply { delete() })
        val installer = CamoufoxInstaller(home)
        withTimeout(30_000) {
            val release = installer.findLatestForPlatform()
            if (CamoufoxPlatform.supported) {
                assertNotNull(release, "本平台应有可用 release")
                assertTrue(release!!.assets.any { it.name.endsWith(CamoufoxPlatform.assetSuffix) })
            } else {
                // 不支持平台（win.arm64）→ 允许为 null
            }
        }
    }

    @Test
    fun `checkForUpdate never crashes without installation`() = runBlocking {
        val home = BrowserHome(java.io.File.createTempFile("camoufox-home-", "").apply { delete() })
        val installer = CamoufoxInstaller(home)
        withTimeout(30_000) {
            val info = installer.checkForUpdate()
            assertTrue(info.supported || !CamoufoxPlatform.supported)
        }
    }
}
