package xyz.mederi.browser

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import xyz.mederi.api.exception.MederiException
import xyz.mederi.api.exception.MederiValidationException
import xyz.mederi.store.InMemorySettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class BrowserSettingsManagerTest {

    @Test
    fun `get unconfigured returns DEFAULT`() = runBlocking {
        val manager = BrowserSettingsManager(InMemorySettingsStore())

        val settings = manager.get()
        assertEquals(CamoufoxSettings.DEFAULT, settings)
        assertNull(settings.browserHome)
        assertTrue(settings.headless)
        assertNull(settings.proxy)
    }

    @Test
    fun `save then get round-trips with proxy fonts and advancedConfig`() = runBlocking {
        val manager = BrowserSettingsManager(InMemorySettingsStore())
        val saved = CamoufoxSettings(
            browserHome = "/tmp/camoufox-home",
            binaryPath = "/tmp/camoufox-home/camoufox/v152.0.4/bin",
            autoCheckUpdate = false,
            headless = false,
            humanize = true,
            humanizeMaxSeconds = 5.5,
            blockImages = true,
            blockWebgl = true,
            blockWebrtc = false,
            disableCoop = false,
            extraArgs = listOf("--no-sandbox", "--disable-dev-shm-usage"),
            userAgent = "Mozilla/5.0 (Test)",
            locale = "zh-CN",
            timezone = "Asia/Shanghai",
            geolocationLat = 31.2304,
            geolocationLon = 121.4737,
            webglVendor = "Google Inc.",
            webglRenderer = "ANGLE (Test)",
            webrtcIpv4 = "1.2.3.4",
            webrtcIpv6 = "::1",
            screenWidth = 1920,
            screenHeight = 1080,
            screenAvailWidth = 1920,
            screenAvailHeight = 1040,
            windowOuterWidth = 1920,
            windowOuterHeight = 1080,
            windowInnerWidth = 1920,
            windowInnerHeight = 969,
            hardwareConcurrency = 8,
            maxTouchPoints = 0,
            fonts = listOf("Arial", "sans-serif"),
            proxy = ProxyConfig(
                type = "http",
                host = "127.0.0.1",
                port = 8080,
                bypass = listOf("localhost", "*.local"),
                username = "user",
                password = "pass"
            ),
            advancedConfig = mapOf("foo" to JsonPrimitive("bar"))
        )

        manager.save(saved)
        val readBack = manager.get()

        assertEquals(saved, readBack)
        assertEquals("http", readBack.proxy?.type)
        assertEquals("127.0.0.1", readBack.proxy?.host)
        assertEquals(8080, readBack.proxy?.port)
        assertEquals(listOf("Arial", "sans-serif"), readBack.fonts)
        assertEquals(JsonPrimitive("bar"), readBack.advancedConfig["foo"])
    }

    @Test
    fun `save persists to settingsStore under KEY`() = runBlocking {
        val store = InMemorySettingsStore()
        val manager = BrowserSettingsManager(store)

        manager.save(CamoufoxSettings(browserHome = "/tmp/camoufox-home"))

        val raw = store.get(BrowserSettingsManager.KEY)
        assertNotNull(raw)
        assertTrue(raw.contains("browserHome"))
    }

    @Test
    fun `invalid proxy port throws MederiException`() = runBlocking {
        val manager = BrowserSettingsManager(InMemorySettingsStore())

        val bad = CamoufoxSettings(
            browserHome = "/tmp/camoufox-home",
            proxy = ProxyConfig(type = "http", host = "127.0.0.1", port = 70000)
        )
        try {
            manager.save(bad)
            fail("expected MederiException for invalid proxy port")
        } catch (e: MederiException) {
            assertTrue(e is MederiValidationException)
        }
    }

    @Test
    fun `advancedConfig accepts arbitrary keys and values`() = runBlocking {
        val manager = BrowserSettingsManager(InMemorySettingsStore())
        val settings = CamoufoxSettings(
            browserHome = "/tmp/camoufox-home",
            advancedConfig = mapOf(
                "customProxy" to JsonPrimitive("custom"),
                "nested" to Json.parseToJsonElement("""{"a": 1, "b": [true, "x"]}""")
            )
        )

        manager.save(settings)
        val readBack = manager.get()

        assertEquals(settings, readBack)
        assertEquals(JsonPrimitive("custom"), readBack.advancedConfig["customProxy"])
        assertEquals(Json.parseToJsonElement("""{"a": 1, "b": [true, "x"]}"""), readBack.advancedConfig["nested"])
    }

    @Test
    fun `save rejects when both browserHome and binaryPath empty`() = runBlocking {
        val manager = BrowserSettingsManager(InMemorySettingsStore())

        // 全空（DEFAULT 路径均为 null）
        try {
            manager.save(CamoufoxSettings())
            fail("expected MederiValidationException for missing browser path")
        } catch (e: MederiException) {
            assertTrue(e is MederiValidationException)
        }

        // blank 字符串同样拒绝（isNullOrBlank 判定）
        try {
            manager.save(CamoufoxSettings(browserHome = "   ", binaryPath = " "))
            fail("expected MederiValidationException for blank browser path")
        } catch (e: MederiException) {
            assertTrue(e is MederiValidationException)
        }
    }

    @Test
    fun `save succeeds with only binaryPath set`() = runBlocking {
        val manager = BrowserSettingsManager(InMemorySettingsStore())

        val saved = CamoufoxSettings(binaryPath = "/opt/camoufox/camoufox")
        manager.save(saved)
        val readBack = manager.get()

        assertEquals(saved, readBack)
        assertNull(readBack.browserHome)
        assertEquals("/opt/camoufox/camoufox", readBack.binaryPath)
    }
}
