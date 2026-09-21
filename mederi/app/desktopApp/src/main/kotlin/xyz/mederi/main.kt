package xyz.mederi

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.flow.distinctUntilChanged
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
            browserHostHolder?.shutdown()
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

                    // 注入内置 JCEF 浏览器宿主（tab = KBPage）+ 注册进 BrowserRegistry。
                    // 注册在 MederiAiCore 注册 camoufox 之后，且 BrowserRegistry 幂等覆盖——即使
                    // 注册顺序变化，"jcef" 也能正确解析；注册表默认策略也优先内置可见浏览器。
                    // AI 通过 run_browser_task(browser="jcef") 选择。
                    val browserHost = JcefBrowserHost()
                    browserHostHolder = browserHost
                    appState.uiBrowserHost = browserHost
                    xyz.mederi.browser.BrowserRegistry.register(
                        name = "jcef",
                        kind = xyz.mederi.browser.BrowserKind.JCEF,
                        factory = { taskId -> browserHost.createAiTab(taskId) },
                        statusSource = xyz.mederi.browser.BrowserStatusSource { browserHost.statusSnapshot() }
                    )
                }

                // 注入本地终端：pty4j registry（desktop 进程内直连，jediterm 渲染）
                appState.terminalManager = terminalHub
            },
        )
    }
}

/** 内置浏览器宿主引用（退出时回收 JCEF）。 */
private var browserHostHolder: JcefBrowserHost? = null
