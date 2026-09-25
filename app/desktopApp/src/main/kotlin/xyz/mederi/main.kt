package xyz.mederi

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import xyz.mederi.core.bridge.MederiAiCore
import xyz.mederi.server.RemoteServer
import xyz.mederi.server.terminal.PtyTerminalHub
import xyz.mederi.ui.appstate.AppState
import java.io.File

/** 终端会话注册表（desktop 全局单例，随进程生命周期）。 */
private val terminalHub = PtyTerminalHub()

fun main() = application {
    // 持有 AppState 引用：退出前 flush 未落盘的偏好写（内部带 2s 兜底超时）
    val appStateHolder = remember { mutableStateOf<AppState?>(null) }

    // wasm Web UI 产物目录（运行时约定路径，可选）：非空则内嵌 server 同源托管 UI。
    // 优先环境变量 MEDERI_WEBAPP_DIR；否则按常见工作目录探测（项目根 / desktopApp / IDE run）。
    val webappDir = System.getenv("MEDERI_WEBAPP_DIR")
        ?: listOf(
            "webApp/build/dist/wasmJs/productionExecutable",          // IDE: run from project root
            "../webApp/build/dist/wasmJs/productionExecutable",        // gradle :desktopApp:run
            "../../webApp/build/dist/wasmJs/productionExecutable",     // 打包后 resources 相邻布局
        ).firstOrNull { File(it).isDirectory }

    // 观察遥控开关（含启动后 hydrate 恢复 + 运行时切换），自动启停内嵌 server。
    // 设置页的启停是入口，这里兜底保证状态与 server 实际运行一致。
    LaunchedEffect(appStateHolder.value) {
        val appState = appStateHolder.value ?: return@LaunchedEffect
        appState.remoteControlEnabled
            .collect { enabled ->
                if (enabled) appState.startRemoteControl() else appState.stopRemoteControl()
            }
    }

    Window(
        onCloseRequest = {
            RemoteServer.stop()
            terminalHub.shutdown()
            DesktopBrowserRuntime.shutdown()
            appStateHolder.value?.remoteControl?.stopTunnel()
            appStateHolder.value?.let { runBlocking { it.flushPreferences() } }
            exitApplication()
        },
        title = "Mederi",
    ) {
        MederiApp(
            onAppStateReady = { appState ->
                appStateHolder.value = appState

                // 注入遥控 hooks：desktop 宿主 = 内嵌 server + cloudflared 隧道（共享同一 MederiAiCore 实例）
                val aiCore = appState.aiCore
                if (aiCore is MederiAiCore) {
                    appState.remoteControl = DesktopRemoteControlHooks(aiCore, webappDir)

                    // 启动后台预热桌面浏览器全局单例（固定 useOsr = true，专供 inkcompose 结构图与 Markdown 导出）
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                        DesktopBrowserRuntime.ensureInitialized()
                    }
                }

                // 注入本地终端：pty4j registry（desktop 进程内直连，jediterm 渲染）
                appState.terminalManager = terminalHub
            },
        )
    }
}
