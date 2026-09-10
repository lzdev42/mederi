package xyz.emuci.syntax.i18n

import org.w3c.dom.Window
import kotlinx.browser.window

internal actual object PlatformLocale {
    actual fun current(): LocaleInfo {
        return try {
            val lang = window.navigator.language
            val parts = lang.split("-")
            LocaleInfo(
                language = parts.getOrNull(0) ?: "en",
                country = parts.getOrNull(1) ?: ""
            )
        } catch (e: Exception) {
            LocaleInfo(language = "en")
        }
    }
}
