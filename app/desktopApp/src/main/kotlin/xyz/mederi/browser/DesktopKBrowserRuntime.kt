package xyz.mederi.browser

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.kbrowser.webview.JcefChecker
import xyz.kbrowser.webview.KBrowser
import xyz.kbrowser.webview.initializeKBrowser
import java.io.File
import kotlin.concurrent.Volatile

/**
 * desktopApp 本地 KBrowser 渲染运行时（原 core KBrowserRuntime 的回归，2026-11 core 去 KBrowser）。
 *
 * 职责：
 * 1. 管理桌面进程内 KBrowser/JCEF 生命周期：仅配置并初始化一次（幂等、线程安全）；
 * 2. 统一 storageDir 与渲染模式（useOsr = true，离屏渲染）；
 * 3. 仅服务于 inkcompose 离屏渲染注入（Mermaid）——浏览自动化不经过本类（那是 Obscura 的职责）。
 *
 * JCEF 不可用或初始化失败时返回 null 降级（Mermaid 渲染退化为占位）。
 */
object DesktopKBrowserRuntime {

    private val initMutex = Mutex()

    @Volatile
    private var initialized = false

    @Volatile
    private var kBrowser: KBrowser? = null

    val storageDir: String = System.getProperty("java.io.tmpdir") + File.separator + "mederi-jcef"

    val isAvailable: Boolean get() = JcefChecker.isJcefAvailable

    /** 初始化成功返回 KBrowser 实例；未初始化 / 不支持 / 初始化失败均为 null。 */
    suspend fun ensureInitialized(): KBrowser? {
        if (initialized) return kBrowser
        if (!isAvailable) return null

        return initMutex.withLock {
            if (initialized) return@withLock kBrowser

            try {
                File(storageDir).mkdirs()
                KBrowser.initializeConfig(storageDir, useOsr = true)
                initializeKBrowser()

                kBrowser = KBrowser
                initialized = true
                KBrowser
            } catch (e: Throwable) {
                // try-catch 必须覆盖 Throwable（不只 Exception）：JCEF native 初始化可能抛 Error
                null
            }
        }
    }

    /** 退出清理。 */
    fun shutdown() {
        if (initialized) {
            runCatching { KBrowser.shutdown() }
            initialized = false
            kBrowser = null
        }
    }
}
