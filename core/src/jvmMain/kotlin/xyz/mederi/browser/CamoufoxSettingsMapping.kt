package xyz.mederi.browser

import xyz.mederi.browser.bidi.CamoufoxConfig

/**
 * Camoufox 设置模型 → BiDi 层映射（JVM 专属）。
 *
 * [CamoufoxSettings.toCamoufoxConfig]：把 core 设置逐字段映射到 [CamoufoxConfig]
 * （字段全可空，null = 不注入，用 Camoufox 默认指纹）。
 * headless / extraArgs 不是 CamoufoxConfig 的字段，由 BiDiBrowserControl 单独接收。
 */
fun CamoufoxSettings.toCamoufoxConfig(): CamoufoxConfig = CamoufoxConfig(
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
    humanize = humanize,
    humanizeMaxSeconds = humanizeMaxSeconds,
    blockImages = blockImages,
    blockWebgl = blockWebgl,
    blockWebrtc = blockWebrtc,
    disableCoop = disableCoop,
    advancedConfig = advancedConfig
)

/**
 * 解析 Camoufox profile 保存目录（跟随 camoufox 路径，**永不落到系统临时目录**）：
 * - [CamoufoxSettings.browserHome] 非空 → `{browserHome}/profiles`（与 [install.BrowserHome.profilesDir] 一致）
 * - 否则 [CamoufoxSettings.binaryPath] 非空 → `{binaryPath 父目录}/profiles`（二进制同级，跟随手动安装位置）
 * - 都为空 → null（保存时已被 [BrowserSettingsManager] 校验拦截，此处为防御性返回；
 *   调用方应报"未配置浏览器路径"错误）。
 *
 * 返回前对目录执行 mkdirs()（幂等）。
 */
fun CamoufoxSettings.resolveProfileDir(): java.nio.file.Path? {
    val home = browserHome
    if (!home.isNullOrBlank()) {
        return java.io.File(home, "profiles").apply { mkdirs() }.toPath()
    }
    val binary = binaryPath
    if (!binary.isNullOrBlank()) {
        val parent = java.io.File(binary).parentFile ?: java.io.File(".")
        return java.io.File(parent, "profiles").apply { mkdirs() }.toPath()
    }
    return null
}

/**
 * 代理配置 → Firefox proxy prefs（network.proxy.* 首选项）。
 *
 * type == "none" / host 空白 / port 不在 1..65535 → 空 Map（不注入代理）。
 */
fun ProxyConfig.toFirefoxPrefs(): Map<String, Any> {
    if (type == "none" || host.isBlank() || port !in 1..65535) return emptyMap()
    val prefs = mutableMapOf<String, Any>("network.proxy.type" to 1)
    when (type) {
        "http" -> {
            prefs["network.proxy.http"] = host
            prefs["network.proxy.http_port"] = port
            prefs["network.proxy.ssl"] = host
            prefs["network.proxy.ssl_port"] = port
        }
        "socks" -> {
            prefs["network.proxy.socks"] = host
            prefs["network.proxy.socks_port"] = port
            prefs["network.proxy.socks_version"] = 5
            if (!username.isNullOrBlank()) prefs["network.proxy.socks_username"] = username
            if (!password.isNullOrBlank()) prefs["network.proxy.socks_password"] = password
        }
    }
    if (bypass.isNotEmpty()) {
        prefs["network.proxy.no_proxies_on"] = bypass.joinToString(",")
    }
    return prefs
}
