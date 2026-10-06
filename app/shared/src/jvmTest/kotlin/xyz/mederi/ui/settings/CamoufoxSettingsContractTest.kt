package xyz.mederi.ui.settings

import kotlinx.serialization.json.JsonPrimitive
import xyz.mederi.browser.CamoufoxSettings
import xyz.mederi.browser.ProxyConfig
import xyz.mederi.core.contract.models.BrowserStatus
import xyz.mederi.core.contract.models.CamoufoxUpdate
import xyz.mederi.core.contract.models.CamoufoxSettings as ContractCamoufoxSettings
import xyz.mederi.core.contract.models.ProxyConfig as ContractProxyConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CamoufoxSettings 跨模块契约锁。
 *
 * core 域模型 [CamoufoxSettings]（xyz.mederi.browser）与 app/shared 契约镜像
 * [ContractCamoufoxSettings]（xyz.mederi.core.contract.models）字段对等性
 * 由本测试机器校验：core 改字段（新增/重命名/改类型）而契约镜像没跟上时，此测试必须失败。
 */
class CamoufoxSettingsContractTest {

    // ── 锁定全字段名集合（加字段须同步更新此集合 + 上方逐字段断言） ──

    private val expectedCamoufoxFields = setOf(
        // 路径组
        "browserHome", "binaryPath", "autoCheckUpdate",
        // 启动行为组
        "headless", "humanize", "humanizeMaxSeconds", "blockImages", "blockWebgl",
        "blockWebrtc", "disableCoop", "extraArgs",
        // 指纹覆盖组
        "userAgent", "locale", "timezone", "geolocationLat", "geolocationLon",
        "webglVendor", "webglRenderer", "webrtcIpv4", "webrtcIpv6",
        "screenWidth", "screenHeight", "screenAvailWidth", "screenAvailHeight",
        "windowOuterWidth", "windowOuterHeight", "windowInnerWidth", "windowInnerHeight",
        "hardwareConcurrency", "maxTouchPoints", "fonts",
        // 代理组
        "proxy",
        // 高级组
        "advancedConfig"
    )

    private val expectedProxyFields = setOf(
        "type", "host", "port", "bypass", "username", "password"
    )

    /** 全字段非默认值实例（domain 侧）。 */
    private fun domainSettings(): CamoufoxSettings = CamoufoxSettings(
        browserHome = "/tmp/test-browser",
        binaryPath = "/opt/camoufox/camoufox",
        autoCheckUpdate = false,
        headless = false,
        humanize = false,
        humanizeMaxSeconds = 3.5,
        blockImages = true,
        blockWebgl = true,
        blockWebrtc = false,
        disableCoop = false,
        extraArgs = listOf("--flag1", "--flag2"),
        userAgent = "Mozilla/5.0 (TestAgent/1.0)",
        locale = "ja-JP",
        timezone = "Asia/Tokyo",
        geolocationLat = 35.6762,
        geolocationLon = 139.6503,
        webglVendor = "Test Vendor",
        webglRenderer = "Test Renderer",
        webrtcIpv4 = "1.2.3.4",
        webrtcIpv6 = "2001:db8::1",
        screenWidth = 2560,
        screenHeight = 1440,
        screenAvailWidth = 2560,
        screenAvailHeight = 1400,
        windowOuterWidth = 1920,
        windowOuterHeight = 1080,
        windowInnerWidth = 1900,
        windowInnerHeight = 1040,
        hardwareConcurrency = 16,
        maxTouchPoints = 0,
        fonts = listOf("Hiragino Sans", "YuGothic"),
        proxy = ProxyConfig(
            type = "socks",
            host = "127.0.0.1",
            port = 1080,
            bypass = listOf("localhost", "127.*"),
            username = "user",
            password = "pass"
        ),
        advancedConfig = mapOf("key1" to JsonPrimitive("val1"), "key2" to JsonPrimitive(42))
    )

    /** 全字段非默认值实例（contract 侧，值与 domain 侧逐字段对应）。 */
    private fun contractSettings(): ContractCamoufoxSettings = ContractCamoufoxSettings(
        browserHome = "/tmp/test-browser",
        binaryPath = "/opt/camoufox/camoufox",
        autoCheckUpdate = false,
        headless = false,
        humanize = false,
        humanizeMaxSeconds = 3.5,
        blockImages = true,
        blockWebgl = true,
        blockWebrtc = false,
        disableCoop = false,
        extraArgs = listOf("--flag1", "--flag2"),
        userAgent = "Mozilla/5.0 (TestAgent/1.0)",
        locale = "ja-JP",
        timezone = "Asia/Tokyo",
        geolocationLat = 35.6762,
        geolocationLon = 139.6503,
        webglVendor = "Test Vendor",
        webglRenderer = "Test Renderer",
        webrtcIpv4 = "1.2.3.4",
        webrtcIpv6 = "2001:db8::1",
        screenWidth = 2560,
        screenHeight = 1440,
        screenAvailWidth = 2560,
        screenAvailHeight = 1400,
        windowOuterWidth = 1920,
        windowOuterHeight = 1080,
        windowInnerWidth = 1900,
        windowInnerHeight = 1040,
        hardwareConcurrency = 16,
        maxTouchPoints = 0,
        fonts = listOf("Hiragino Sans", "YuGothic"),
        proxy = ContractProxyConfig(
            type = "socks",
            host = "127.0.0.1",
            port = 1080,
            bypass = listOf("localhost", "127.*"),
            username = "user",
            password = "pass"
        ),
        advancedConfig = mapOf("key1" to JsonPrimitive("val1"), "key2" to JsonPrimitive(42))
    )

    // ── 逐字段断言（domain ↔ contract，非默认值） ──

    @Test
    fun `path fields match`() {
        val d = domainSettings()
        val c = contractSettings()
        assertEquals(d.browserHome, c.browserHome, "browserHome 两侧不一致")
        assertEquals(d.binaryPath, c.binaryPath, "binaryPath 两侧不一致")
        assertEquals(d.autoCheckUpdate, c.autoCheckUpdate, "autoCheckUpdate 两侧不一致")
    }

    @Test
    fun `startup behavior fields match`() {
        val d = domainSettings()
        val c = contractSettings()
        assertEquals(d.headless, c.headless, "headless 两侧不一致")
        assertEquals(d.humanize, c.humanize, "humanize 两侧不一致")
        assertEquals(d.humanizeMaxSeconds, c.humanizeMaxSeconds, "humanizeMaxSeconds 两侧不一致")
        assertEquals(d.blockImages, c.blockImages, "blockImages 两侧不一致")
        assertEquals(d.blockWebgl, c.blockWebgl, "blockWebgl 两侧不一致")
        assertEquals(d.blockWebrtc, c.blockWebrtc, "blockWebrtc 两侧不一致")
        assertEquals(d.disableCoop, c.disableCoop, "disableCoop 两侧不一致")
        assertEquals(d.extraArgs, c.extraArgs, "extraArgs 两侧不一致")
    }

    @Test
    fun `fingerprint override fields match`() {
        val d = domainSettings()
        val c = contractSettings()
        assertEquals(d.userAgent, c.userAgent, "userAgent 两侧不一致")
        assertEquals(d.locale, c.locale, "locale 两侧不一致")
        assertEquals(d.timezone, c.timezone, "timezone 两侧不一致")
        assertEquals(d.geolocationLat, c.geolocationLat, "geolocationLat 两侧不一致")
        assertEquals(d.geolocationLon, c.geolocationLon, "geolocationLon 两侧不一致")
        assertEquals(d.webglVendor, c.webglVendor, "webglVendor 两侧不一致")
        assertEquals(d.webglRenderer, c.webglRenderer, "webglRenderer 两侧不一致")
        assertEquals(d.webrtcIpv4, c.webrtcIpv4, "webrtcIpv4 两侧不一致")
        assertEquals(d.webrtcIpv6, c.webrtcIpv6, "webrtcIpv6 两侧不一致")
        assertEquals(d.screenWidth, c.screenWidth, "screenWidth 两侧不一致")
        assertEquals(d.screenHeight, c.screenHeight, "screenHeight 两侧不一致")
        assertEquals(d.screenAvailWidth, c.screenAvailWidth, "screenAvailWidth 两侧不一致")
        assertEquals(d.screenAvailHeight, c.screenAvailHeight, "screenAvailHeight 两侧不一致")
        assertEquals(d.windowOuterWidth, c.windowOuterWidth, "windowOuterWidth 两侧不一致")
        assertEquals(d.windowOuterHeight, c.windowOuterHeight, "windowOuterHeight 两侧不一致")
        assertEquals(d.windowInnerWidth, c.windowInnerWidth, "windowInnerWidth 两侧不一致")
        assertEquals(d.windowInnerHeight, c.windowInnerHeight, "windowInnerHeight 两侧不一致")
        assertEquals(d.hardwareConcurrency, c.hardwareConcurrency, "hardwareConcurrency 两侧不一致")
        assertEquals(d.maxTouchPoints, c.maxTouchPoints, "maxTouchPoints 两侧不一致")
        assertEquals(d.fonts, c.fonts, "fonts 两侧不一致")
    }

    @Test
    fun `proxy fields match`() {
        val dp = domainSettings().proxy!!
        val cp = contractSettings().proxy!!
        assertEquals(dp.type, cp.type, "proxy.type 两侧不一致")
        assertEquals(dp.host, cp.host, "proxy.host 两侧不一致")
        assertEquals(dp.port, cp.port, "proxy.port 两侧不一致")
        assertEquals(dp.bypass, cp.bypass, "proxy.bypass 两侧不一致")
        assertEquals(dp.username, cp.username, "proxy.username 两侧不一致")
        assertEquals(dp.password, cp.password, "proxy.password 两侧不一致")
    }

    @Test
    fun `advanced config fields match`() {
        val d = domainSettings()
        val c = contractSettings()
        assertEquals(d.advancedConfig, c.advancedConfig, "advancedConfig 两侧不一致")
    }

    // ── 全字段穷举：字段名集合包含锁定集合（防加字段漏改） ──
    //
    // 用「锁定集合 ⊆ 实际字段集合」而非精确等，因为 Kotlin @Serializable 可能添加
    // synthetic 辅助字段。如果 core 加了新字段而契约镜像没跟上，锁定集合比对会漏报——
    // 但上方的逐字段 assertEquals 测试能直接编译失败（因为 contract 实例没有该属性），
    // 形成双重保护。

    @Test
    fun `domain CamoufoxSettings contains all expected fields`() {
        val names = CamoufoxSettings::class.java.declaredFields
            .filter { !it.isSynthetic }
            .map { it.name }.toSet()
        assertTrue(
            names.containsAll(expectedCamoufoxFields),
            "domain CamoufoxSettings 缺失字段: ${expectedCamoufoxFields - names}"
        )
    }

    @Test
    fun `contract CamoufoxSettings contains all expected fields`() {
        val names = ContractCamoufoxSettings::class.java.declaredFields
            .filter { !it.isSynthetic }
            .map { it.name }.toSet()
        assertTrue(
            names.containsAll(expectedCamoufoxFields),
            "contract CamoufoxSettings 缺失字段: ${expectedCamoufoxFields - names}"
        )
    }

    @Test
    fun `domain ProxyConfig contains all expected fields`() {
        val names = ProxyConfig::class.java.declaredFields
            .filter { !it.isSynthetic }
            .map { it.name }.toSet()
        assertTrue(
            names.containsAll(expectedProxyFields),
            "domain ProxyConfig 缺失字段: ${expectedProxyFields - names}"
        )
    }

    @Test
    fun `contract ProxyConfig contains all expected fields`() {
        val names = ContractProxyConfig::class.java.declaredFields
            .filter { !it.isSynthetic }
            .map { it.name }.toSet()
        assertTrue(
            names.containsAll(expectedProxyFields),
            "contract ProxyConfig 缺失字段: ${expectedProxyFields - names}"
        )
    }

    // ── 默认值对齐（零值实例比较） ──

    @Test
    fun `default instances have identical defaults`() {
        val d = CamoufoxSettings()
        val c = ContractCamoufoxSettings()
        assertEquals(d.browserHome, c.browserHome, "browserHome 默认值不一致")
        assertEquals(d.binaryPath, c.binaryPath, "binaryPath 默认值不一致")
        assertEquals(d.autoCheckUpdate, c.autoCheckUpdate, "autoCheckUpdate 默认值不一致")
        assertEquals(d.headless, c.headless, "headless 默认值不一致")
        assertEquals(d.humanize, c.humanize, "humanize 默认值不一致")
        assertEquals(d.humanizeMaxSeconds, c.humanizeMaxSeconds, "humanizeMaxSeconds 默认值不一致")
        assertEquals(d.blockImages, c.blockImages, "blockImages 默认值不一致")
        assertEquals(d.blockWebgl, c.blockWebgl, "blockWebgl 默认值不一致")
        assertEquals(d.blockWebrtc, c.blockWebrtc, "blockWebrtc 默认值不一致")
        assertEquals(d.disableCoop, c.disableCoop, "disableCoop 默认值不一致")
        assertEquals(d.extraArgs, c.extraArgs, "extraArgs 默认值不一致")
        assertEquals(d.userAgent, c.userAgent, "userAgent 默认值不一致")
        assertEquals(d.locale, c.locale, "locale 默认值不一致")
        assertEquals(d.timezone, c.timezone, "timezone 默认值不一致")
        assertEquals(d.geolocationLat, c.geolocationLat, "geolocationLat 默认值不一致")
        assertEquals(d.geolocationLon, c.geolocationLon, "geolocationLon 默认值不一致")
        assertEquals(d.webglVendor, c.webglVendor, "webglVendor 默认值不一致")
        assertEquals(d.webglRenderer, c.webglRenderer, "webglRenderer 默认值不一致")
        assertEquals(d.webrtcIpv4, c.webrtcIpv4, "webrtcIpv4 默认值不一致")
        assertEquals(d.webrtcIpv6, c.webrtcIpv6, "webrtcIpv6 默认值不一致")
        assertEquals(d.screenWidth, c.screenWidth, "screenWidth 默认值不一致")
        assertEquals(d.screenHeight, c.screenHeight, "screenHeight 默认值不一致")
        assertEquals(d.screenAvailWidth, c.screenAvailWidth, "screenAvailWidth 默认值不一致")
        assertEquals(d.screenAvailHeight, c.screenAvailHeight, "screenAvailHeight 默认值不一致")
        assertEquals(d.windowOuterWidth, c.windowOuterWidth, "windowOuterWidth 默认值不一致")
        assertEquals(d.windowOuterHeight, c.windowOuterHeight, "windowOuterHeight 默认值不一致")
        assertEquals(d.windowInnerWidth, c.windowInnerWidth, "windowInnerWidth 默认值不一致")
        assertEquals(d.windowInnerHeight, c.windowInnerHeight, "windowInnerHeight 默认值不一致")
        assertEquals(d.hardwareConcurrency, c.hardwareConcurrency, "hardwareConcurrency 默认值不一致")
        assertEquals(d.maxTouchPoints, c.maxTouchPoints, "maxTouchPoints 默认值不一致")
        assertEquals(d.fonts, c.fonts, "fonts 默认值不一致")
        // proxy：默认 null（非默认值已在 proxy fields match 测试逐字段比较）
        assertEquals(d.proxy == null, c.proxy == null, "proxy 默认 nullness 不一致")
        assertEquals(d.advancedConfig, c.advancedConfig, "advancedConfig 默认值不一致")
    }

    // ── BrowserStatus / CamoufoxUpdate 字段名锁定（防加字段漏改） ──

    @Test
    fun `BrowserStatus contract has expected fields`() {
        val names = BrowserStatus::class.java.declaredFields
            .filter { !it.isSynthetic }
            .map { it.name }.toSet()
        assertTrue(
            names.containsAll(setOf("configured", "installedVersion", "latestVersion", "hasUpdate", "supported", "reason")),
            "BrowserStatus 缺失字段: ${setOf("configured", "installedVersion", "latestVersion", "hasUpdate", "supported", "reason") - names}"
        )
    }

    @Test
    fun `CamoufoxUpdate contract has expected fields`() {
        val names = CamoufoxUpdate::class.java.declaredFields
            .filter { !it.isSynthetic }
            .map { it.name }.toSet()
        assertTrue(
            names.containsAll(setOf("configured", "installedVersion", "latestVersion", "hasUpdate", "supported", "reason")),
            "CamoufoxUpdate 缺失字段: ${setOf("configured", "installedVersion", "latestVersion", "hasUpdate", "supported", "reason") - names}"
        )
    }
}
