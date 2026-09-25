package xyz.mederi.core.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 运行时可覆盖的应用语言（BCP 47 标签，如 "zh" / "en" / "mnc"）。
 * null = 跟随系统语言。全局唯一写入点 = [AppEnvironment] 外层按 AppState.language 赋值。
 *
 * 这是官方 expect/actual 临时方案（compose resources 尚未提供统一公共 API）：
 * compose resources 的 stringResource 按平台环境解析 locale——
 * desktop 读 JVM Locale.getDefault()、Android 读 Configuration、iOS 读 AppleLanguages、
 * wasm 读 navigator.languages（languagechange 事件）。
 */
var customAppLocale by mutableStateOf<String?>(null)

expect object LocalAppLocale {
    val current: String
        @Composable get

    @Composable
    infix fun provides(value: String?): ProvidedValue<*>
}

/** 用 AppState 持久化的语言设置包裹整个应用；key() 保证语言切换时全树重组、资源环境重新解析。 */
@Composable
fun AppEnvironment(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalAppLocale provides customAppLocale,
    ) {
        key(customAppLocale) {
            content()
        }
    }
}
