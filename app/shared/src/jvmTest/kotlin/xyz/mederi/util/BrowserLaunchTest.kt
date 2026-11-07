package xyz.mederi.util

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 只验证系统派发与错误返回，不启动任何外部应用。 */
class BrowserLaunchTest {
    private val url = "https://auth.openai.com/api/accounts/authorize?state=one&redirect_uri=http%3A%2F%2F127.0.0.1%3A1455%2Fauth%2Fcallback"

    @Test
    fun macOpensEntireAuthorizationUrlAsOneArgumentAndReportsFailure() {
        val commands = mutableListOf<List<String>>()
        assertTrue(openExternalWebUrl(url, "Mac OS X", { commands += it; 0 }, { error("Do not use AWT on macOS") }))
        assertEquals(listOf(listOf("open", url)), commands)
        assertFalse(openExternalWebUrl(url, "Mac OS X", { 1 }, { true }))
        assertFalse(openExternalWebUrl(url, "Mac OS X", { null }, { true }))
        assertFalse(openExternalWebUrl(url, "Mac OS X", { error("Cannot start process") }, { true }))
    }

    @Test
    fun invalidUrlsNeverReachSystemCommandsOrDesktop() {
        listOf("--background", "file:///etc/passwd", "javascript:alert(1)", "https:///missing-host",
            "https://user:password@example.com", "https://example.com/invalid space").forEach { invalid ->
            assertFalse(openExternalWebUrl(invalid, "Mac OS X", { error("Unexpected command") }, { error("Unexpected desktop") }))
        }
    }

    @Test
    fun windowsUsesSupportedDesktopBrowseAndMakesFailureObservable() {
        var opened: URI? = null
        assertTrue(openExternalWebUrl(url, "Windows 11", { error("Unexpected command") }, { opened = it; true }))
        assertEquals(URI(url), opened)
        assertFalse(openExternalWebUrl(url, "Windows 11", { error("Unexpected command") }, { false }))
        assertFalse(openExternalWebUrl(url, "Windows 11", { error("Unexpected command") }, { error("AWT denied") }))
    }

    @Test
    fun linuxUsesXdgOnlyWhenDesktopCannotLaunchAndPreservesUrl() {
        val commands = mutableListOf<List<String>>()
        assertTrue(openExternalWebUrl(url, "Linux", { commands += it; 0 }, { false }))
        assertEquals(listOf(listOf("xdg-open", url)), commands)
        assertTrue(openExternalWebUrl(url, "Linux", { error("Desktop already opened") }, { true }))
        assertTrue(openExternalWebUrl(url, "Linux", { 0 }, { error("AWT unavailable") }))
        assertFalse(openExternalWebUrl(url, "Linux", { 3 }, { false }))
    }
}
