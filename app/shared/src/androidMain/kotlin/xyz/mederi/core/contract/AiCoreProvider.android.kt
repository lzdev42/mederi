package xyz.mederi.core.contract

import kotlinx.coroutines.runBlocking
import xyz.mederi.core.bridge.ServerAiCore
import xyz.mederi.core.contract.preferences.PreferencesStore

/**
 * Android：100% 遥控端。core 跑在远端 JVM server 进程里，
 * server 地址 + 密码存 SharedPrefs（键与 desktop 遥控设置一致）。
 *
 * SharedPrefs 是同步内存读，启动时 runBlocking 一次可接受；
 * 未配置时 baseUrl 为空串，ServerAiCore.initialize 会给出友好报错。
 */
internal actual fun createDefaultAiCore(): AiCore {
    val store = preferencesStore()
    val baseUrl = runBlocking { store.getString(AiCoreProvider.KEY_SERVER_URL) }.orEmpty()
    val password = runBlocking { store.getString(AiCoreProvider.KEY_SERVER_PASSWORD) }
    return ServerAiCore(baseUrl = baseUrl, passwordProvider = { password })
}

private fun preferencesStore(): PreferencesStore =
    xyz.mederi.core.contract.preferences.defaultPreferencesStore()
