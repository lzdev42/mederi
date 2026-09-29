package xyz.mederi.core.bridge

import xyz.mederi.api.BrowserStatusDto
import xyz.mederi.api.CamoufoxSettingsDto
import xyz.mederi.api.CamoufoxUpdateDto
import xyz.mederi.api.ProxyConfigDto
import xyz.mederi.core.contract.models.BrowserStatus
import xyz.mederi.core.contract.models.CamoufoxSettings
import xyz.mederi.core.contract.models.CamoufoxUpdate
import xyz.mederi.core.contract.models.ProxyConfig

/**
 * core BrowserSettingsApi DTO ↔ AiCore 契约模型映射（app/shared jvmMain）。
 *
 * 契约层不依赖 core，DTO 形状由各自声明，这里逐字段复制。
 * 双向只有 settings/proxy（update 输入需要反向）；status/update 为只读，单向 toContract。
 */

fun CamoufoxSettingsDto.toContract(): CamoufoxSettings = CamoufoxSettings(
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
    proxy = proxy?.toContract(),
    advancedConfig = advancedConfig
)

fun CamoufoxSettings.toCoreDto(): CamoufoxSettingsDto = CamoufoxSettingsDto(
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
    proxy = proxy?.toCoreDto(),
    advancedConfig = advancedConfig
)

fun BrowserStatusDto.toContract(): BrowserStatus = BrowserStatus(
    configured = configured,
    installedVersion = installedVersion,
    latestVersion = latestVersion,
    hasUpdate = hasUpdate,
    supported = supported,
    reason = reason
)

fun CamoufoxUpdateDto.toContract(): CamoufoxUpdate = CamoufoxUpdate(
    configured = configured,
    installedVersion = installedVersion,
    latestVersion = latestVersion,
    hasUpdate = hasUpdate,
    supported = supported,
    reason = reason
)

fun ProxyConfigDto.toContract(): ProxyConfig = ProxyConfig(
    type = type,
    host = host,
    port = port,
    bypass = bypass,
    username = username,
    password = password
)

fun ProxyConfig.toCoreDto(): ProxyConfigDto = ProxyConfigDto(
    type = type,
    host = host,
    port = port,
    bypass = bypass,
    username = username,
    password = password
)
