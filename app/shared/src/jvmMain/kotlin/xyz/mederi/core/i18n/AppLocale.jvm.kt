package xyz.mederi.core.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.staticCompositionLocalOf
import java.util.Locale

/** desktop 宿主：资源环境读 JVM `Locale.getDefault()`，覆盖 = 改写默认 locale。 */
private var defaultJvmLocale: Locale? = null
private val LocalJvmAppLocale = staticCompositionLocalOf { Locale.getDefault().toString() }

@Composable
internal actual fun currentAppLocale(): String = LocalJvmAppLocale.current

@Composable
internal actual fun provideAppLocale(value: String?): ProvidedValue<*> {
    if (defaultJvmLocale == null) {
        defaultJvmLocale = Locale.getDefault()
    }
    val new = when (value) {
        null -> defaultJvmLocale!!
        else -> Locale.forLanguageTag(value)
    }
    Locale.setDefault(new)
    return LocalJvmAppLocale.provides(new.toString())
}
