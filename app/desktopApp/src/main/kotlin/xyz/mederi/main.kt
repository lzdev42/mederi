package xyz.mederi

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import xyz.mederi.browser.DesktopKBrowserRuntime
import xyz.mederi.core.bridge.MederiAiCore
import xyz.mederi.mcp.runtime.McpRuntime
import xyz.mederi.runtime.RuntimeSetupPage
import xyz.mederi.server.Server
import xyz.mederi.server.terminal.PtyTerminalHub
import xyz.mederi.ui.appstate.AppState
import xyz.mederi.ui.host.LocalProjectFileTreeProvider
import xyz.mederi.ui.host.ProjectFileTreeProviderJvm
import java.awt.Desktop
import java.io.File

/** 终端会话注册表（desktop 全局单例，随进程生命周期）。 */
private val terminalHub = PtyTerminalHub()

fun main() = application {
    val missingRuntimes = remember { mutableStateOf<Set<McpRuntime.Component>?>(null) }
    LaunchedEffect(Unit) {
        missingRuntimes.value = withContext(Dispatchers.IO) { McpRuntime.missing() }
    }

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

    fun requestClose() {
        // null means detection is still in progress. Only a successful probe unlocks exit.
        if (missingRuntimes.value?.isEmpty() != true) return
        appStateHolder.value?.stopRemoteControl() ?: Server.stop()
        terminalHub.shutdown()
        appStateHolder.value?.uiBrowserHost?.shutdown()
        xyz.mederi.browser.DesktopKBrowserRuntime.shutdown()
        appStateHolder.value?.let { runBlocking { it.flushAll() } }
        // abort 在飞 turn + drain data.db 写队列（半截正文已由流式取消路径落库）
        (appStateHolder.value?.aiCore as? MederiAiCore)?.shutdown()
        exitApplication()
    }

    DisposableEffect(Unit) {
        // macOS Command-Q and the system Quit menu can bypass the window close event.
        val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
        val quitDesktop = desktop?.takeIf { it.isSupported(Desktop.Action.APP_QUIT_HANDLER) }
        quitDesktop?.setQuitHandler { _, response ->
            response.cancelQuit()
            requestClose()
        }
        onDispose { quitDesktop?.setQuitHandler(null) }
    }

    Window(
        onCloseRequest = ::requestClose,
        title = "Mederi",
        visible = missingRuntimes.value != null,
    ) {
        val missing = missingRuntimes.value ?: return@Window
        if (missing.isNotEmpty()) {
            RuntimeSetupPage(missing) { missingRuntimes.value = it }
            return@Window
        }

        // 注入文件树数据源（desktop 宿主专属）：根 = 当前工作目录（AppState 项目目录在 MederiApp 内
        // 创建才可得，宿主注入点取不到，按 S3 spec 退路用 user.dir）
        val fileTreeProvider = remember { ProjectFileTreeProviderJvm(File(System.getProperty("user.dir") ?: ".")) }
        CompositionLocalProvider(LocalProjectFileTreeProvider provides fileTreeProvider) {
            MederiApp(
                onAppStateReady = { appState ->
                    appStateHolder.value = appState

                // 注入遥控 hooks：desktop 宿主 = 内嵌 server + cloudflared 隧道（共享同一 MederiAiCore 实例）
                    val aiCore = appState.aiCore
                    if (aiCore is MederiAiCore) {
                        appState.remoteControl = DesktopRemoteControlHooks(aiCore, webappDir)

                        // JCEF 是桌面渲染能力，由桌面宿主初始化并注入 InkCompose。
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                            aiCore.isReady.first { it }
                            val kb = DesktopKBrowserRuntime.ensureInitialized()
                            if (kb != null) {
                                xyz.emuci.diagram.mermaid.SingleMermaidWorker.attachBrowser(kb)
                                xyz.emuci.inkcompose.MarkdownExporter.setBrowser(kb)
                            }
                        }
                    }

                    // 注入本地终端：pty4j registry（desktop 进程内直连，jediterm 渲染）
                    appState.terminalManager = terminalHub

                    // 注入文件树数据源到 AppState（VM 层读取用；CompositionLocal 同源实例，树面板继续用它）
                    appState.fileTreeProvider = fileTreeProvider
                },
            )
        }
    }
}
