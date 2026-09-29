package xyz.mederi.browser

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Camoufox 浏览器设置（纯数据模型，持久化于 SettingsStore，key = `browser.camoufox.settings`）。
 *
 * **默认值为 Camoufox 官方推荐实践（2026-09 对照官方 python 库默认值与官方文档/社区反检测实践核对，
 * 本模型默认值恰好等于推荐组合，勿随手改动，改动前先核对依据）**：
 * - 官方 python 库代码默认：headless=False、humanize=False、block_images/block_webrtc/block_webgl/disable_coop 全 False、
 *   enable_cache 默认关、os 随机、指纹自动生成（fpgen/browserforge）。我们**不照抄**库默认（那是给交互式使用的），
 *   而是采用文档+社区一致推荐的 **agent/反检测场景组合**：
 *   - `headless=true`（无头自动化）；`humanize=true`（真实人类鼠标轨迹，反检测）
 *   - `disableCoop=true`（Turnstile 跨域 iframe 兼容）；`blockWebrtc=true`（无代理时防本地 IP 泄漏）
 *   - `blockImages=false`（省流量可选，非必要不屏蔽——屏蔽可能改变页面布局被检测）
 *   - `blockWebgl=false`（官方警告 "To prevent leaks, only use this for special cases"，默认不屏蔽）
 *   - **不要固定 window/screen 尺寸**（会造成指纹化）→ 指纹覆盖组全 null = 不注入，交 Camoufox 自动生成
 *   - `enableCache` 保持关（Camoufox 默认，本模型无此字段即不开启）
 * - 路径组：**browserHome / binaryPath 不设默认**——必须手动配置至少其一（[BrowserSettingsManager] 保存校验）。
 *   浏览器二进制体积大（数百 MB），不自动下载到默认路径；binaryPath 模式时 profile 目录跟随二进制同级（见 [resolveProfileDir]）。
 *
 * 字段按用途分组：
 * - 路径组：浏览器工作目录 / 二进制路径 / 启动时自动检查更新
 * - 启动行为组：headless、人类化操作延迟、资源屏蔽、COOP 禁用、附加参数
 * - 指纹覆盖组：全可空（null = 不注入，用 Camoufox 默认指纹）
 * - 代理组：[ProxyConfig]
 * - 高级组：任意键值 JSON（原样透传给 Camoufox，不做内容校验）
 */
@Serializable
data class CamoufoxSettings(
    // ── 路径组 ──
    /** 浏览器工作目录（{browserHome}，BrowserHome 的 root）。
     *  不设默认——必须与 [binaryPath] 至少设置其一（保存时校验）；设置了它时 profile 目录落在 {browserHome}/profiles。 */
    val browserHome: String? = null,
    /** Camoufox 可执行文件路径（仅当未设置 [browserHome] 时用于定位二进制；设置了它时 profile 目录跟随其父目录/profiles）。
     *  不设默认——必须与 [browserHome] 至少设置其一（保存时校验）。 */
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

    // ── 指纹覆盖组（null = 不注入） ──
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
) {
    companion object {
        /** 默认设置（全部字段用默认值）。 */
        val DEFAULT = CamoufoxSettings()
    }
}

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
