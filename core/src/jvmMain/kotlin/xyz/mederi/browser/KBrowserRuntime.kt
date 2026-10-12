package xyz.mederi.browser

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.kbrowser.webview.JcefChecker
import xyz.kbrowser.webview.KBrowser
import xyz.kbrowser.webview.initializeKBrowser
import xyz.mederi.debug.DebugLog
import java.io.File
import kotlin.concurrent.Volatile

/**
 * core 侧全局唯一的 KBrowser / JCEF 运行时管理器。
 *
 * 职责：
 * 1. 统一管理 KBrowser 生命周期，确保底层 JCEF (Chromium) 在整个进程生命周期中仅配置并初始化一次；
 * 2. 统一指定 storageDir 与渲染模式（useOsr = true，同时满足离屏渲染与 Compose 树嵌入）；
 * 3. 向调用方暴露 KBrowser 实例（JCEF 不可用 / 初始化失败时为 null）。
 *
 * 不负责（调用方各自职责，勿在此注入）：
 * - 向 inkcompose 注入（SingleMermaidWorker.attachBrowser / MarkdownExporter.setBrowser）——那是 main.kt 的职责；
 * - BrowserRegistry 注册——那是 MederiAiCore 的职责。
 *
 * 从 desktopApp 的 DesktopBrowserRuntime 搬迁而来（剥离 inkcompose 注入部分）。
 */
object KBrowserRuntime {

    private const val LAYER = "KBrowser"

    private val initMutex = Mutex()

    @Volatile
    private var initialized = false

    @Volatile
    private var kBrowser: KBrowser? = null

    val storageDir: String = System.getProperty("java.io.tmpdir") + File.separator + "mederi-jcef"

    val isAvailable: Boolean get() = JcefChecker.isJcefAvailable

    val isInitialized: Boolean get() = initialized

    /** 初始化成功后才返回实例；未初始化 / 不支持 / 初始化失败均为 null。 */
    val browser: KBrowser? get() = if (initialized) kBrowser else null

    /**
     * 保证 KBrowser 在全进程中仅配置与初始化一次（线程安全、幂等）。
     *
     * @return 初始化成功返回 KBrowser 实例；JCEF 不可用或初始化失败返回 null。
     */
    suspend fun ensureInitialized(): KBrowser? {
        if (initialized) return kBrowser
        if (!isAvailable) {
            DebugLog.info(LAYER, "JCEF runtime is not available in current JBR environment.")
            return null
        }

        return initMutex.withLock {
            if (initialized) return@withLock kBrowser

            try {
                DebugLog.info(LAYER, "Initializing KBrowser singleton (storageDir=$storageDir, useOsr=true)...")
                File(storageDir).mkdirs()
                KBrowser.initializeConfig(storageDir, useOsr = true)
                initializeKBrowser()

                kBrowser = KBrowser
                initialized = true
                DebugLog.info(LAYER, "KBrowser singleton successfully initialized.")
                KBrowser
            } catch (e: Throwable) {
                // try-catch 必须覆盖 Throwable（不只 Exception）：JCEF native 初始化可能抛 Error
                DebugLog.error(LAYER, "Failed to initialize KBrowser: ${e.message}", e)
                null
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
            kBrowser = null
        }
    }
}
