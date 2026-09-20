package xyz.mederi.core.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.browser.window

/**
 * wasm 宿主：compose resources 的 web 环境监听 `languagechange` 事件并读 navigator.languages。
 * navigator.languages 只读，须配合 webApp index.html 里的 `__customLocale` 覆盖脚本使用。
 */
actual object LocalAppLocale {
    private val LocalAppLocale = staticCompositionLocalOf { window.navigator.language }

    actual val current: String
        @Composable get() = LocalAppLocale.current

    @Composable
    actual infix fun provides(value: String?): ProvidedValue<*> {
        if (value != null) {
            setJsLocale(value)
        }
        return LocalAppLocale.provides(value ?: window.navigator.language)
    }
}

/** 写入自定义 locale 并广播 languagechange（index.html 覆盖脚本据此改写 navigator.languages）。 */
private fun setJsLocale(value: String) {
    js("window.__customLocale = value; window.dispatchEvent(new Event('languagechange'));")
}
