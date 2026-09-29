package xyz.mederi.browser

import kotlinx.serialization.json.Json
import xyz.mederi.api.exception.MederiValidationException
import xyz.mederi.debug.DebugLog
import xyz.mederi.store.SettingsStore

/**
 * Camoufox 浏览器设置管理器（唯一真理源）。
 *
 * 持久化在 [SettingsStore]（key = [KEY]），以 JSON 存储完整 [CamoufoxSettings]；
 * 读取时解码失败回退 [CamoufoxSettings.DEFAULT] 并打日志；保存前做基础校验。
 */
class BrowserSettingsManager(
    private val settingsStore: SettingsStore
) {
    companion object {
        const val KEY = "browser.camoufox.settings"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 最近一次读/写的设置缓存（JVM 工厂非 suspend 快速读用）。 */
    @Volatile
    private var cached: CamoufoxSettings = CamoufoxSettings.DEFAULT

    /**
     * 读取设置：store 有值则解码（失败回退 DEFAULT 并打日志），无值返回 DEFAULT。
     * 更新缓存并返回。
     */
    suspend fun get(): CamoufoxSettings {
        val raw = settingsStore.get(KEY)
        val settings = if (raw == null) {
            CamoufoxSettings.DEFAULT
        } else {
            runCatching {
                json.decodeFromString<CamoufoxSettings>(raw)
            }.getOrElse { e ->
                DebugLog.error("BrowserSettings", "Camoufox 设置解析失败，回退默认: ${e.message}")
                CamoufoxSettings.DEFAULT
            }
        }
        cached = settings
        return settings
    }

    /**
     * 保存设置：先 [validate]，再写入 store，更新缓存，返回保存后的设置。
     */
    suspend fun save(settings: CamoufoxSettings): CamoufoxSettings {
        validate(settings)
        settingsStore.set(KEY, json.encodeToString(CamoufoxSettings.serializer(), settings))
        cached = settings
        return settings
    }

    /**
     * 最近一次值（非 suspend 快速读）。
     * 首次为 [CamoufoxSettings.DEFAULT]，实际值在 MederiAiCore.initialize 里调 [get] 预热。
     */
    fun current(): CamoufoxSettings = cached

    /**
     * 基础校验：
     * 1. **路径必填**：browserHome 与 binaryPath 均 null/blank 时拒绝保存——路径不设默认
     *    （浏览器二进制体积大，不自动下载到默认路径），必须手动配置至少其一。
     * 2. proxy 非 none 且 host 非空时，port 必须在 1..65535。
     * advancedConfig 不做内容校验（Camoufox 自身校验）。
     */
    private fun validate(s: CamoufoxSettings) {
        if (s.browserHome.isNullOrBlank() && s.binaryPath.isNullOrBlank()) {
            throw MederiValidationException(
                "Camoufox 浏览器路径未配置：browserHome 与 binaryPath 必须至少设置一个"
            )
        }
        val proxy = s.proxy
        if (proxy != null && proxy.type != "none" && proxy.host.isNotBlank()) {
            if (proxy.port !in 1..65535) {
                throw MederiValidationException(
                    "Camoufox 代理端口非法: ${proxy.port}（需在 1..65535 之间）"
                )
            }
        }
    }
}
