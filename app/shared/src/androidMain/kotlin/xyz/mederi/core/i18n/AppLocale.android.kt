package xyz.mederi.core.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/** Android 宿主：更新 Configuration + JVM 默认 locale，双写保证 compose resources 解析生效。 */
private var defaultAndroidLocale: Locale? = null

@Composable
internal actual fun currentAppLocale(): String =
    LocalConfiguration.current.locales.get(0).toString()

@Composable
internal actual fun provideAppLocale(value: String?): ProvidedValue<*> {
    val configuration = LocalConfiguration.current
    if (defaultAndroidLocale == null) {
        defaultAndroidLocale = Locale.getDefault()
    }
    val new = when (value) {
        null -> defaultAndroidLocale!!
        else -> Locale.forLanguageTag(value)
    }
    Locale.setDefault(new)
    configuration.setLocale(new)
    val resources = LocalContext.current.resources
    @Suppress("DEPRECATION")
    resources.updateConfiguration(configuration, resources.displayMetrics)
    return LocalConfiguration.provides(configuration)
}
