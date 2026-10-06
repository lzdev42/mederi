package xyz.mederi.api

import kotlinx.serialization.Serializable
import xyz.mederi.browser.CamoufoxSettings
import xyz.mederi.browser.ProxyConfig

/**
 * Camoufox 浏览器设置 API。
 *
 * 设置读写走 [xyz.mederi.browser.BrowserSettingsManager]（SettingsStore 持久化）；
 * 状态/更新/安装/版本列表走 jvmMain 的 CamoufoxInstaller（依赖 browserHome）。
 */
interface BrowserSettingsApi {

    /** 读取当前 Camoufox 设置（未配置返回默认）。 */
    suspend fun getSettings(): CamoufoxSettingsDto

    /** 更新 Camoufox 设置（整体替换，保存前校验非法值）。 */
    suspend fun updateSettings(input: UpdateCamoufoxSettingsInput): CamoufoxSettingsDto

    /** 获取浏览器状态（是否配置、已安装版本、平台支持等；不访问网络）。 */
    suspend fun getStatus(): BrowserStatusDto

    /** 检查更新（查询本平台最新可用版本，不下载）。 */
    suspend fun checkUpdate(): CamoufoxUpdateDto

    /** 安装/更新到指定版本（null = 本平台最新可用）。 */
    suspend fun install(versionTag: String?): Unit

    /** 列出已安装的 Camoufox 版本（{browserHome}/camoufox/ 下子目录名）。 */
    suspend fun listInstalledVersions(): List<String>
}

/**
 * Camoufox 设置 DTO（typealias 指向 domain [CamoufoxSettings]，消除 API 层同形副本）。
 *
 * 历史：原为独立 data class（字段与 domain 逐一复制 + 手写 toDto/toModel mapper），
 * 2026-10 合并：字段完全同形且无语义差异，typealias 收口为 1 份 domain 定义。
 */
typealias CamoufoxSettingsDto = CamoufoxSettings

/** 更新 Camoufox 设置请求。 */
@Serializable
data class UpdateCamoufoxSettingsInput(
    val settings: CamoufoxSettings
)

/** 浏览器状态（configured = browserHome 已配置；不访问网络）。 */
@Serializable
data class BrowserStatusDto(
    val configured: Boolean,
    val installedVersion: String?,
    val latestVersion: String?,
    val hasUpdate: Boolean,
    val supported: Boolean,
    val reason: String
)

/** 更新检查结果（字段同 [BrowserStatusDto]，latestVersion/hasUpdate 由网络查询填充）。 */
@Serializable
data class CamoufoxUpdateDto(
    val configured: Boolean,
    val installedVersion: String?,
    val latestVersion: String?,
    val hasUpdate: Boolean,
    val supported: Boolean,
    val reason: String
)

/**
 * 代理配置 DTO（typealias 指向 domain [ProxyConfig]，消除 API 层同形副本）。
 */
typealias ProxyConfigDto = ProxyConfig

// ── 历史 toDto/toModel 映射已删除（CamoufoxSettingsDto 现为 CamoufoxSettings 的 typealias） ──
