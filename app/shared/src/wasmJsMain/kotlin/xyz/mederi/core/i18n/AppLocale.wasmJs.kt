package xyz.mederi.core.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.browser.window

import kotlin.js.ExperimentalWasmJsInterop

/**
 * wasm 宿主：compose resources 的 web 环境监听 `languagechange` 事件并读 navigator.languages。
 * navigator.languages 只读，须配合 webApp index.html 里的 `__customLocale` 覆盖脚本使用。
 */
private val LocalWasmAppLocale = staticCompositionLocalOf { window.navigator.language }

@Composable
internal actual fun currentAppLocale(): String = LocalWasmAppLocale.current

@Composable
internal actual fun provideAppLocale(value: String?): ProvidedValue<*> {
    if (value != null) {
        setJsLocale(value)
    }
    return LocalWasmAppLocale.provides(value ?: window.navigator.language)
}

/** 写入自定义 locale 并广播 languagechange（index.html 覆盖脚本据此改写 navigator.languages）。 */
@OptIn(ExperimentalWasmJsInterop::class)
private fun setJsLocale(value: String) {
    js("window.__customLocale = value; window.dispatchEvent(new Event('languagechange'));")
}
