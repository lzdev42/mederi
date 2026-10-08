package xyz.emuci.syntax.i18n

import java.util.Locale

internal actual fun currentPlatformLocale(): LocaleInfo {
    val locale = Locale.getDefault()
    return LocaleInfo(
        language = locale.language ?: "en",
        country = locale.country ?: ""
    )
}
