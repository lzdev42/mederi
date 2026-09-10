package xyz.mederi

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import xyz.mederi.core.ui.appstate.LocalAppState
import xyz.mederi.theme.AppTheme
import xyz.mederi.ui.MainScreen

@Composable
fun App() {
    // 主题唯一真理源 = AppState.theme，UI 全部经 LocalAppState 读写，无参数链透传
    val theme by LocalAppState.current.theme.collectAsState()
    AppTheme(themeMode = theme) {
        MainScreen()
    }
}
