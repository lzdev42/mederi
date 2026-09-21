package xyz.mederi

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import xyz.mederi.core.contract.AiCoreProvider
import xyz.mederi.core.contract.preferences.defaultPreferencesStore
import xyz.mederi.ui.DebugLog
import xyz.mederi.ui.appstate.AppState
import xyz.mederi.ui.appstate.LocalAppState

/**
 * Mederi 应用的统一入口。
 *
 * 负责 AppState 的创建与注入：创建一次、初始化引擎、提供 [LocalAppState]。
 * 所有平台入口（desktop / web / android / ios）只调用本函数，不各自重复初始化逻辑——
 * 这样任何新平台都不会漏掉 AppState 的提供。
 *
 * @param onAppStateReady AppState 创建并注入后回调一次（供宿主持有引用，例如退出前 flush 偏好）
 * @param content 应用内容，默认渲染 [App]
 */
@Composable
fun MederiApp(
    onAppStateReady: (AppState) -> Unit = {},
    content: @Composable () -> Unit = { App() },
) {
    val appState = remember {
        AppState(
            aiCore = AiCoreProvider.default(),
            preferences = defaultPreferencesStore(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }

    LaunchedEffect(Unit) {
        onAppStateReady(appState)
        appState.aiCore.initialize()
            .onSuccess { appState.hydrate() }
            .onFailure { e -> println("❌ Mederi 初始化失败: ${e.message}") }
    }

    // 结构图渲染由 inkcompose 内置（KBrowser webview + mermaid.js，全平台），宿主无需注入实现
    CompositionLocalProvider(LocalAppState provides appState) {
        content()
    }
}
