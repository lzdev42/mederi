package xyz.mederi.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
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

/** Camoufox 设置 DTO（字段与 [CamoufoxSettings] 一一对应）。 */
@Serializable
data class CamoufoxSettingsDto(
    // ── 路径组 ──
    val browserHome: String? = null,
    val binaryPath: String? = null,
    val autoCheckUpdate: Boolean = true,

    // ── 启动行为组 ──
    val headless: Boolean = true,
    val humanize: Boolean = true,
    val humanizeMaxSeconds: Double? = null,
    val blockImages: Boolean = false,
    val blockWebgl: Boolean = false,
    val blockWebrtc: Boolean = true,
    val disableCoop: Boolean = true,
    val extraArgs: List<String> = emptyList(),

    // ── 指纹覆盖组 ──
    val userAgent: String? = null,
    val locale: String? = null,
    val timezone: String? = null,
    val geolocationLat: Double? = null,
    val geolocationLon: Double? = null,
    val webglVendor: String? = null,
    val webglRenderer: String? = null,
    val webrtcIpv4: String? = null,
    val webrtcIpv6: String? = null,
    val screenWidth: Int? = null,
    val screenHeight: Int? = null,
    val screenAvailWidth: Int? = null,
    val screenAvailHeight: Int? = null,
    val windowOuterWidth: Int? = null,
    val windowOuterHeight: Int? = null,
    val windowInnerWidth: Int? = null,
    val windowInnerHeight: Int? = null,
    val hardwareConcurrency: Int? = null,
    val maxTouchPoints: Int? = null,
    val fonts: List<String> = emptyList(),

    // ── 代理组 ──
    val proxy: ProxyConfigDto? = null,

    // ── 高级组 ──
    val advancedConfig: Map<String, JsonElement> = emptyMap()
)

/** 更新 Camoufox 设置请求。 */
@Serializable
data class UpdateCamoufoxSettingsInput(
    val settings: CamoufoxSettingsDto
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

/** 代理配置 DTO（字段与 [ProxyConfig] 一一对应）。 */
@Serializable
data class ProxyConfigDto(
    val type: String = "none",
    val host: String = "",
    val port: Int = 0,
    val bypass: List<String> = emptyList(),
    val username: String? = null,
    val password: String? = null
)

// ── core model ↔ DTO 扩展转换（手动逐字段映射） ──

fun CamoufoxSettings.toDto(): CamoufoxSettingsDto = CamoufoxSettingsDto(
    browserHome = browserHome,
    binaryPath = binaryPath,
    autoCheckUpdate = autoCheckUpdate,
    headless = headless,
    humanize = humanize,
    humanizeMaxSeconds = humanizeMaxSeconds,
    blockImages = blockImages,
    blockWebgl = blockWebgl,
    blockWebrtc = blockWebrtc,
    disableCoop = disableCoop,
    extraArgs = extraArgs,
    userAgent = userAgent,
    locale = locale,
    timezone = timezone,
    geolocationLat = geolocationLat,
    geolocationLon = geolocationLon,
    webglVendor = webglVendor,
    webglRenderer = webglRenderer,
    webrtcIpv4 = webrtcIpv4,
    webrtcIpv6 = webrtcIpv6,
    screenWidth = screenWidth,
    screenHeight = screenHeight,
    screenAvailWidth = screenAvailWidth,
    screenAvailHeight = screenAvailHeight,
    windowOuterWidth = windowOuterWidth,
    windowOuterHeight = windowOuterHeight,
    windowInnerWidth = windowInnerWidth,
    windowInnerHeight = windowInnerHeight,
    hardwareConcurrency = hardwareConcurrency,
    maxTouchPoints = maxTouchPoints,
    fonts = fonts,
    proxy = proxy?.toDto(),
    advancedConfig = advancedConfig
)

fun CamoufoxSettingsDto.toModel(): CamoufoxSettings = CamoufoxSettings(
    browserHome = browserHome,
    binaryPath = binaryPath,
    autoCheckUpdate = autoCheckUpdate,
    headless = headless,
    humanize = humanize,
    humanizeMaxSeconds = humanizeMaxSeconds,
    blockImages = blockImages,
    blockWebgl = blockWebgl,
    blockWebrtc = blockWebrtc,
    disableCoop = disableCoop,
    extraArgs = extraArgs,
    userAgent = userAgent,
    locale = locale,
    timezone = timezone,
    geolocationLat = geolocationLat,
    geolocationLon = geolocationLon,
    webglVendor = webglVendor,
    webglRenderer = webglRenderer,
    webrtcIpv4 = webrtcIpv4,
    webrtcIpv6 = webrtcIpv6,
    screenWidth = screenWidth,
    screenHeight = screenHeight,
    screenAvailWidth = screenAvailWidth,
    screenAvailHeight = screenAvailHeight,
    windowOuterWidth = windowOuterWidth,
    windowOuterHeight = windowOuterHeight,
    windowInnerWidth = windowInnerWidth,
    windowInnerHeight = windowInnerHeight,
    hardwareConcurrency = hardwareConcurrency,
    maxTouchPoints = maxTouchPoints,
    fonts = fonts,
    proxy = proxy?.toModel(),
    advancedConfig = advancedConfig
)

fun ProxyConfig.toDto(): ProxyConfigDto = ProxyConfigDto(
    type = type,
    host = host,
    port = port,
    bypass = bypass,
    username = username,
    password = password
)

fun ProxyConfigDto.toModel(): ProxyConfig = ProxyConfig(
    type = type,
    host = host,
    port = port,
    bypass = bypass,
    username = username,
    password = password
)
