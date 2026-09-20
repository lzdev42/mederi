package xyz.mederi

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import xyz.mederi.core.i18n.AppEnvironment
import xyz.mederi.core.i18n.customAppLocale
import xyz.mederi.core.ui.appstate.LocalAppState
import xyz.mederi.theme.AppTheme
import xyz.mederi.ui.MainScreen

@Composable
fun App() {
    // 主题/语言唯一真理源 = AppState.theme / AppState.language，UI 全部经 LocalAppState 读写，无参数链透传
    val appState = LocalAppState.current
    val theme by appState.theme.collectAsState()
    val language by appState.language.collectAsState()

    // 语言切换生效链路：写 AppState.language → 这里同步 customAppLocale → AppEnvironment 提供
    // LocalAppLocale + key() 全树重组 → stringResource 按新 locale 重新解析
    customAppLocale = language.tag

    AppTheme(themeMode = theme) {
        AppEnvironment {
            MainScreen()
        }
    }
}
