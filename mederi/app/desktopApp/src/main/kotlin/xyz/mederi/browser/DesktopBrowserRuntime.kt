package xyz.mederi

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.emuci.diagram.mermaid.SingleMermaidWorker
import xyz.emuci.inkcompose.MarkdownExporter
import xyz.kbrowser.webview.JcefChecker
import xyz.kbrowser.webview.KBrowser
import xyz.kbrowser.webview.initializeKBrowser
import java.io.File
import kotlin.concurrent.Volatile

/**
 * 桌面端全局唯一的 KBrowser / JCEF 运行时宿主。
 *
 * 核心职责：
 * 1. 统一管理 KBrowser 的生命周期，确保底层 JCEF (Chromium) 在整个进程生命周期中仅配置并初始化一次；
 * 2. 统一指定 storageDir 与渲染模式（useOsr = true，同时满足离屏渲染与 Compose 树嵌入）；
 * 3. 负责向 inkcompose 模块（SingleMermaidWorker、MarkdownExporter）注入已就绪的单例引用；
 * 4. 为 JcefBrowserHost（内置浏览器 Tab 栏）提供统一的页面创建与状态能力。
 */
object DesktopBrowserRuntime {

    private val initMutex = Mutex()

    @Volatile
    private var initialized = false

    val storageDir: String = System.getProperty("java.io.tmpdir") + File.separator + "mederi-jcef"

    val isAvailable: Boolean get() = JcefChecker.isJcefAvailable

    val isInitialized: Boolean get() = initialized

    val browser: KBrowser get() = KBrowser

    /**
     * 保证 KBrowser 在全进程中仅配置与初始化一次（线程安全、幂等）。
     * 初始化成功后自动向 inkcompose 注入 KBrowser 引用。
     */
    suspend fun ensureInitialized(): Boolean {
        if (initialized) return true
        if (!isAvailable) {
            println("[DesktopBrowserRuntime] JCEF runtime is not available in current JBR environment.")
            return false
        }

        return initMutex.withLock {
            if (initialized) return@withLock true

            try {
                println("[DesktopBrowserRuntime] Initializing KBrowser singleton (storageDir=$storageDir, useOsr=true)...")
                File(storageDir).mkdirs()
                KBrowser.initializeConfig(storageDir, useOsr = true)
                initializeKBrowser()

                // 向 inkcompose 模块注入单例引用
                SingleMermaidWorker.attachBrowser(KBrowser)
                MarkdownExporter.setBrowser(KBrowser)

                initialized = true
                println("[DesktopBrowserRuntime] KBrowser singleton successfully initialized and attached to inkcompose.")
                true
            } catch (e: Throwable) {
                println("[DesktopBrowserRuntime] Failed to initialize KBrowser: ${e.message}")
                e.printStackTrace()
                false
            }
        }
    }

    /**
     * 退出清理。
     */
    fun shutdown() {
        if (initialized) {
            runCatching {
                KBrowser.shutdown()
            }
            initialized = false
        }
    }
}
