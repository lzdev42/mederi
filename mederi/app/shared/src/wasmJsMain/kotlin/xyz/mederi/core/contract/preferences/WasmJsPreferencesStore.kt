package xyz.mederi.core.contract.preferences

import kotlinx.browser.localStorage

/**
 * wasmJs 端偏好存储：落浏览器 localStorage（同步读写，页面刷新/重开不丢）。
 *
 * 键名统一加 `mederi.pref.` 前缀，与 RemoteGate 的 `mederi.remote.password`
 * 等其他 localStorage 键隔离，避免命名冲突。
 *
 * 浏览器禁用 localStorage（隐私模式/策略限制）时降级为进程内内存存储，
 * 保证应用可用不崩溃——此时偏好不跨刷新持久，属环境限制。
 */
class WasmJsPreferencesStore : PreferencesStore {
    private val memoryFallback = mutableMapOf<String, String>()

    override suspend fun getString(key: String): String? =
        runCatching { localStorage.getItem(prefixed(key)) }.getOrNull() ?: memoryFallback[key]

    override suspend fun putString(key: String, value: String) {
        memoryFallback[key] = value
        runCatching { localStorage.setItem(prefixed(key), value) }
    }

    override suspend fun remove(key: String) {
        memoryFallback.remove(key)
        runCatching { localStorage.removeItem(prefixed(key)) }
    }

    private fun prefixed(key: String) = "mederi.pref.$key"
}
