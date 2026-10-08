package xyz.emuci.syntax.i18n

import kotlinx.browser.window

internal actual fun currentPlatformLocale(): LocaleInfo {
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
