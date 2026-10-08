package xyz.mederi.core.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.staticCompositionLocalOf
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSLocale
import platform.Foundation.preferredLanguages

/** iOS 宿主：写 AppleLanguages 偏好（compose resources 的 iOS 环境读取它）。 */
private const val LANG_KEY = "AppleLanguages"
private val defaultIosLocale: String = NSLocale.preferredLanguages.firstOrNull() as? String ?: "en"
private val LocalIosAppLocale = staticCompositionLocalOf { defaultIosLocale }

@Composable
internal actual fun currentAppLocale(): String = LocalIosAppLocale.current

@Composable
internal actual fun provideAppLocale(value: String?): ProvidedValue<*> {
    val new = value ?: defaultIosLocale
    if (value == null) {
        NSUserDefaults.standardUserDefaults.removeObjectForKey(LANG_KEY)
    } else {
        NSUserDefaults.standardUserDefaults.setObject(listOf(new), forKey = LANG_KEY)
    }
    return LocalIosAppLocale.provides(new)
}
