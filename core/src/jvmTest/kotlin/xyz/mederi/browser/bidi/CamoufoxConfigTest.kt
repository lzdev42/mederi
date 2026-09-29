package xyz.mederi.browser.bidi

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * CamoufoxConfig 纯 data class 单测：不启动真实浏览器。
 * 关键回归点：空 config（全默认）→ toConfigJson() 为空 JsonObject（无 navigator.platform 注入）。
 */
class CamoufoxConfigTest {

    @Test
    fun `empty config produces empty config json and no platform injection`() {
        val json = CamoufoxConfig().toConfigJson()

        assertEquals(JsonObject(emptyMap()), json)
        assertFalse(json.containsKey("navigator.platform"))
    }

    @Test
    fun `userAgent maps to navigator userAgent`() {
        val json = CamoufoxConfig(userAgent = "Mozilla/5.0 (Test UA)").toConfigJson()

        assertEquals(JsonPrimitive("Mozilla/5.0 (Test UA)"), json["navigator.userAgent"])
    }

    @Test
    fun `webgl vendor and renderer use exact webGl key casing`() {
        val json = CamoufoxConfig(
            webglVendor = "Google Inc.",
            webglRenderer = "ANGLE (Test)"
        ).toConfigJson()

        assertEquals(JsonPrimitive("Google Inc."), json["webGl:vendor"])
        assertEquals(JsonPrimitive("ANGLE (Test)"), json["webGl:renderer"])
        // 确保没有小写 webgl 键
        assertFalse(json.containsKey("webgl:vendor"))
        assertFalse(json.containsKey("webgl:renderer"))
    }

    @Test
    fun `screenWidth maps to screen width`() {
        val json = CamoufoxConfig(screenWidth = 1920).toConfigJson()

        assertEquals(JsonPrimitive(1920), json["screen.width"])
        assertFalse(json.containsKey("screen.height"))
    }

    @Test
    fun `fonts serialize as json array`() {
        val json = CamoufoxConfig(fonts = listOf("Arial", "sans-serif")).toConfigJson()

        val fonts = json["fonts"]
        assertIs<JsonArray>(fonts)
        assertEquals(listOf("Arial", "sans-serif"), fonts.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `advancedConfig keys appear and override derived values`() {
        val json = CamoufoxConfig(
            userAgent = "derived-ua",
            advancedConfig = mapOf(
                "navigator.userAgent" to JsonPrimitive("advanced-ua"),
                "custom.key" to JsonPrimitive("custom-value")
            )
        ).toConfigJson()

        assertEquals(JsonPrimitive("advanced-ua"), json["navigator.userAgent"])
        assertEquals(JsonPrimitive("custom-value"), json["custom.key"])
    }

    @Test
    fun `toFirefoxPrefs maps blockImages and blockWebgl`() {
        val prefs = CamoufoxConfig(blockImages = true, blockWebgl = true).toFirefoxPrefs()

        assertEquals(2, prefs["permissions.default.image"])
        assertEquals(true, prefs["webgl.disabled"])
    }

    @Test
    fun `blockImages false does not set image pref`() {
        val prefs = CamoufoxConfig().toFirefoxPrefs()

        assertFalse(prefs.containsKey("permissions.default.image"))
    }

    @Test
    fun `humanize true injects humanize flag and maxTime when set`() {
        val json = CamoufoxConfig(
            humanize = true,
            humanizeMaxSeconds = 5.5
        ).toConfigJson()

        assertEquals(JsonPrimitive(true), json["humanize"])
        assertEquals(JsonPrimitive(5.5), json["humanize:maxTime"])
    }

    @Test
    fun `humanize false default leaves config json empty`() {
        assertTrue(CamoufoxConfig().toConfigJson().isEmpty())
    }
}
