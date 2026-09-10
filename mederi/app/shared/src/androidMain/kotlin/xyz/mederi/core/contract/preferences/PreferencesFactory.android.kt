package xyz.mederi.core.contract.preferences

import android.content.Context

/**
 * Android 应用级 Context 持有器。
 * 由 androidApp 壳在 Application/Activity 启动时注入，避免库反向依赖应用模块。
 */
object AndroidAppContext {
    @Volatile
    var applicationContext: Context? = null
}

actual fun defaultPreferencesStore(): PreferencesStore {
    val context = AndroidAppContext.applicationContext
        ?: throw IllegalStateException(
            "AndroidAppContext.applicationContext 未初始化，请在 Application/Activity onCreate 中注入"
        )
    return SharedPrefsPreferencesStore(context)
}
