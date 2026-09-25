package xyz.mederi.util

import kotlin.test.Test
import kotlin.test.assertNotNull

class PlatformUtilsTest {

    @Test
    fun testLogTokenFormattingAndModelLengths() {
        val sampleTokens = listOf(
            null,
            -1,
            0,
            4096,
            8192,
            16384,
            32768,
            65536,
            128000,
            131072,
            200000,
            256000,
            262144,
            512000,
            524288,
            1000000,
            1048576,
            2000000,
            2097152
        )

        println("=== [DEBUG LOG] formatContextWindow Current Output Verification ===")
        for (token in sampleTokens) {
            val formatted = formatContextWindow(token)
            println("Token: $token -> formatted: '$formatted'")
        }

        val sampleModelNames = listOf(
            "agnes-2.5-flash",
            "agnes-3.0-flash",
            "agnes-video-2.5-fast",
            "deepseek-v4-flash-free",
            "muse-spark-1.3-contributor-free",
            "mimo-v2.5-free",
            "deepseek-v4-flash",
            "glm-5.2",
            "sensenova-6.8-flash-lite",
            "kimi-k3"
        )

        println("\n=== [DEBUG LOG] Model Name Length Analysis ===")
        for (name in sampleModelNames) {
            println("Model: '$name', length: ${name.length} chars")
        }
        assertNotNull(sampleModelNames)
    }

    @Test
    fun testFormatContextWindowAssertions() {
        kotlin.test.assertNull(formatContextWindow(null))
        kotlin.test.assertNull(formatContextWindow(-1))
        kotlin.test.assertNull(formatContextWindow(0))
        kotlin.test.assertEquals("4K", formatContextWindow(4096))
        kotlin.test.assertEquals("8K", formatContextWindow(8192))
        kotlin.test.assertEquals("16K", formatContextWindow(16384))
        kotlin.test.assertEquals("32K", formatContextWindow(32768))
        kotlin.test.assertEquals("64K", formatContextWindow(65536))
        kotlin.test.assertEquals("128K", formatContextWindow(128000))
        kotlin.test.assertEquals("128K", formatContextWindow(131072))
        kotlin.test.assertEquals("200K", formatContextWindow(200000))
        kotlin.test.assertEquals("256K", formatContextWindow(256000))
        kotlin.test.assertEquals("256K", formatContextWindow(262144))
        kotlin.test.assertEquals("512K", formatContextWindow(512000))
        kotlin.test.assertEquals("512K", formatContextWindow(524288))
        kotlin.test.assertEquals("1M", formatContextWindow(1000000))
        kotlin.test.assertEquals("1M", formatContextWindow(1048576))
        kotlin.test.assertEquals("2M", formatContextWindow(2000000))
        kotlin.test.assertEquals("2M", formatContextWindow(2097152))
    }

    @Test
    fun testPlatformClipboardLoading() {
        val clazz = Class.forName("xyz.mederi.util.PlatformClipboard")
        kotlin.test.assertNotNull(clazz)
        println("Successfully loaded PlatformClipboard: $clazz")
        val text = PlatformClipboard.getText()
        println("PlatformClipboard.getText() result: $text")
    }
}
