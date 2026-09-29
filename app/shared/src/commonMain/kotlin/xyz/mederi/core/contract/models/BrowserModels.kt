package xyz.mederi.core.contract.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// ==========================================
// 浏览器设置契约（遥控 REST 层）
// ==========================================

/**
 * Camoufox 浏览器设置（UI 契约层）。
 *
 * 字段与 core 模型 [xyz.mederi.browser.CamoufoxSettings] 一一对应、同默认值
 * （app/shared commonMain 不依赖 core，故契约 DTO 必须自成一份）；
 * 命名不带 *Dto 后缀，避免 MederiAiCore（子任务 3）import 同名冲突。
 */
@Serializable
data class CamoufoxSettings(
    // ── 路径组 ──
    /** 浏览器工作目录（browserHome 的 root）。 */
    val browserHome: String? = null,
    /** Camoufox 可执行文件路径（可选，默认在 {browserHome}/camoufox/{version}/ 下探测）。 */
    val binaryPath: String? = null,
    /** 启动时自动检查更新。 */
    val autoCheckUpdate: Boolean = true,

    // ── 启动行为组 ──
    val headless: Boolean = true,
    val humanize: Boolean = true,
    /** 人类化操作最大延迟秒数（null = 用内置默认）。 */
    val humanizeMaxSeconds: Double? = null,
    val blockImages: Boolean = false,
    val blockWebgl: Boolean = false,
    val blockWebrtc: Boolean = true,
    val disableCoop: Boolean = true,
    val extraArgs: List<String> = emptyList(),

    // ── 指纹覆盖组（null = 不注入，用 Camoufox 默认指纹） ──
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
    val proxy: ProxyConfig? = null,

    // ── 高级组（原样透传，Camoufox 自身校验） ──
    val advancedConfig: Map<String, JsonElement> = emptyMap()
)

/**
 * 代理配置。type 取值 `"none"` | `"http"` | `"socks"`。
 */
@Serializable
data class ProxyConfig(
    val type: String = "none",
    val host: String = "",
    val port: Int = 0,
    val bypass: List<String> = emptyList(),
    val username: String? = null,
    val password: String? = null
)

/** 更新 Camoufox 设置请求（整体替换，保存前校验非法值在 core 侧）。 */
@Serializable
data class UpdateCamoufoxSettingsInput(
    val settings: CamoufoxSettings
)

/** 浏览器状态（configured = browserHome 已配置；不访问网络）。 */
@Serializable
data class BrowserStatus(
    val configured: Boolean = false,
    val installedVersion: String? = null,
    val latestVersion: String? = null,
    val hasUpdate: Boolean = false,
    val supported: Boolean = true,
    val reason: String = ""
)

/** 更新检查结果（字段同 [BrowserStatus]，latestVersion/hasUpdate 由网络查询填充）。 */
@Serializable
data class CamoufoxUpdate(
    val configured: Boolean = false,
    val installedVersion: String? = null,
    val latestVersion: String? = null,
    val hasUpdate: Boolean = false,
    val supported: Boolean = true,
    val reason: String = ""
)
