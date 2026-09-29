package xyz.mederi.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.CamoufoxSettings
import xyz.mederi.core.contract.models.ProxyConfig
import xyz.mederi.isDesktopPlatform
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiColors
import xyz.mederi.theme.MederiRadius
import xyz.mederi.theme.MederiTypeScale
import xyz.mederi.ui.appstate.LocalAppState
import xyz.mederi.ui.components.atoms.MederiGhostButton
import xyz.mederi.ui.components.atoms.MederiPrimaryDecisionButton
import xyz.mederi.ui.components.atoms.MederiSurfaceButton
import xyz.mederi.util.pickDirectory

/**
 * Camoufox 浏览器设置面板（设置页 BROWSER Tab）。
 *
 * 五个分区：路径与安装 / 启动行为 / 指纹覆盖 / 代理 / 高级。
 * 编辑经本地 draft 缓冲，保存时统一解析数字/列表字段（失败保持 null/空）。
 */
@Composable
fun BrowserSettingsPanel() {
    val appState = LocalAppState.current
    val colors = LocalMederiColors.current
    val store = appState.browserSettingsStore
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()

    val settings by store.settings.collectAsState()
    val status by store.status.collectAsState()
    val installedVersions by store.installedVersions.collectAsState()
    val isOperating by store.isOperating.collectAsState()
    val errorMessage by store.errorMessage.collectAsState()

    // 直接可编辑的字符串/布尔字段缓冲（与 store 设置同源，保存成功后随 settings 重置）
    var draft by remember { mutableStateOf(settings) }
    // 数字/列表/JSON 字段用独立文本状态，保存时才解析，避免输入过程被 toIntOrNull 吞掉
    var humanizeMaxSecondsText by remember { mutableStateOf(settings.humanizeMaxSeconds?.toString().orEmpty()) }
    var extraArgsText by remember { mutableStateOf(settings.extraArgs.joinToString(" ")) }
    var geolocationLatText by remember { mutableStateOf(settings.geolocationLat?.toString().orEmpty()) }
    var geolocationLonText by remember { mutableStateOf(settings.geolocationLon?.toString().orEmpty()) }
    var screenWidthText by remember { mutableStateOf(settings.screenWidth?.toString().orEmpty()) }
    var screenHeightText by remember { mutableStateOf(settings.screenHeight?.toString().orEmpty()) }
    var screenAvailWidthText by remember { mutableStateOf(settings.screenAvailWidth?.toString().orEmpty()) }
    var screenAvailHeightText by remember { mutableStateOf(settings.screenAvailHeight?.toString().orEmpty()) }
    var windowOuterWidthText by remember { mutableStateOf(settings.windowOuterWidth?.toString().orEmpty()) }
    var windowOuterHeightText by remember { mutableStateOf(settings.windowOuterHeight?.toString().orEmpty()) }
    var windowInnerWidthText by remember { mutableStateOf(settings.windowInnerWidth?.toString().orEmpty()) }
    var windowInnerHeightText by remember { mutableStateOf(settings.windowInnerHeight?.toString().orEmpty()) }
    var hardwareConcurrencyText by remember { mutableStateOf(settings.hardwareConcurrency?.toString().orEmpty()) }
    var maxTouchPointsText by remember { mutableStateOf(settings.maxTouchPoints?.toString().orEmpty()) }
    var fontsText by remember { mutableStateOf(settings.fonts.joinToString(", ")) }
    var proxyType by remember { mutableStateOf(settings.proxy?.type ?: "none") }
    var proxyHostText by remember { mutableStateOf(settings.proxy?.host.orEmpty()) }
    var proxyPortText by remember { mutableStateOf(settings.proxy?.port?.takeIf { it != 0 }?.toString().orEmpty()) }
    var proxyBypassText by remember { mutableStateOf(settings.proxy?.bypass?.joinToString(", ").orEmpty()) }
    var proxyUsernameText by remember { mutableStateOf(settings.proxy?.username.orEmpty()) }
    var proxyPasswordText by remember { mutableStateOf(settings.proxy?.password.orEmpty()) }
    var advancedConfigText by remember { mutableStateOf(settings.advancedConfig.toJsonText()) }
    var showHomeDialog by remember { mutableStateOf(false) }

    // 外部设置变化（刷新/保存成功）时重置草稿与各文本状态
    LaunchedEffect(settings) {
        draft = settings
        humanizeMaxSecondsText = settings.humanizeMaxSeconds?.toString().orEmpty()
        extraArgsText = settings.extraArgs.joinToString(" ")
        geolocationLatText = settings.geolocationLat?.toString().orEmpty()
        geolocationLonText = settings.geolocationLon?.toString().orEmpty()
        screenWidthText = settings.screenWidth?.toString().orEmpty()
        screenHeightText = settings.screenHeight?.toString().orEmpty()
        screenAvailWidthText = settings.screenAvailWidth?.toString().orEmpty()
        screenAvailHeightText = settings.screenAvailHeight?.toString().orEmpty()
        windowOuterWidthText = settings.windowOuterWidth?.toString().orEmpty()
        windowOuterHeightText = settings.windowOuterHeight?.toString().orEmpty()
        windowInnerWidthText = settings.windowInnerWidth?.toString().orEmpty()
        windowInnerHeightText = settings.windowInnerHeight?.toString().orEmpty()
        hardwareConcurrencyText = settings.hardwareConcurrency?.toString().orEmpty()
        maxTouchPointsText = settings.maxTouchPoints?.toString().orEmpty()
        fontsText = settings.fonts.joinToString(", ")
        proxyType = settings.proxy?.type ?: "none"
        proxyHostText = settings.proxy?.host.orEmpty()
        proxyPortText = settings.proxy?.port?.takeIf { it != 0 }?.toString().orEmpty()
        proxyBypassText = settings.proxy?.bypass?.joinToString(", ").orEmpty()
        proxyUsernameText = settings.proxy?.username.orEmpty()
        proxyPasswordText = settings.proxy?.password.orEmpty()
        advancedConfigText = settings.advancedConfig.toJsonText()
    }

    // 高级 JSON 合法性：空 = 合法（不注入）；非空必须能解析为 JSON 对象
    val advancedConfigValid = remember(advancedConfigText) {
        advancedConfigText.isBlank() || runCatching {
            Json.parseToJsonElement(advancedConfigText) is JsonObject
        }.getOrDefault(false)
    }

    // 路径必填：browserHome 与 binaryPath 至少配置一个（与 core BrowserSettingsManager 保存校验同规则，UI 提前拦截）
    val pathConfigured =
        !draft.browserHome.isNullOrBlank() || !draft.binaryPath.isNullOrBlank()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // ── 分区 1：路径与安装 ──
        SettingsSection(
            title = stringResource(Res.string.browser_section_path),
            colors = colors
        ) {
            SettingsCard(colors = colors) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingsRow(
                        title = stringResource(Res.string.browser_field_home),
                        subtitle = draft.browserHome?.takeIf { it.isNotBlank() }
                            ?: stringResource(Res.string.browser_not_configured_hint),
                        colors = colors
                    ) {
                        MederiGhostButton(
                            text = stringResource(Res.string.browser_modify),
                            onClick = { showHomeDialog = true },
                            icon = FeatherIcons.Folder
                        )
                    }

                    LabeledInput(
                        label = stringResource(Res.string.browser_field_binary_path),
                        value = draft.binaryPath.orEmpty(),
                        onValueChange = { draft = draft.copy(binaryPath = it.ifBlank { null }) },
                        colors = colors,
                        monospace = true
                    )

                    SettingsRow(
                        title = stringResource(Res.string.browser_field_auto_check_update),
                        colors = colors
                    ) {
                        Switch(
                            checked = draft.autoCheckUpdate,
                            onCheckedChange = { draft = draft.copy(autoCheckUpdate = it) },
                            colors = switchColors(colors)
                        )
                    }

                    HorizontalDivider(color = colors.divider.copy(alpha = 0.5f))

                    // 已安装版本
                    Text(
                        text = stringResource(Res.string.browser_installed_versions),
                        color = colors.textPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (installedVersions.isEmpty()) {
                        Text(
                            text = stringResource(Res.string.browser_versions_empty),
                            color = colors.textMuted,
                            fontSize = 11.5.sp
                        )
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            installedVersions.forEach { version ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(colors.surfaceWorkspace)
                                        .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(6.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = version,
                                        color = colors.textSecondary,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MederiSurfaceButton(
                            text = stringResource(Res.string.browser_check_update),
                            onClick = { scope.launch { store.checkUpdate() } },
                            enabled = !isOperating,
                            icon = FeatherIcons.RefreshCw
                        )
                        MederiPrimaryDecisionButton(
                            text = stringResource(Res.string.browser_install_latest),
                            onClick = { scope.launch { store.install(null) } },
                            enabled = !isOperating,
                            icon = FeatherIcons.Download
                        )
                    }

                    // 状态行
                    status?.let { st ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            when {
                                !st.configured -> Text(
                                    text = stringResource(Res.string.browser_not_configured_hint),
                                    color = colors.textMuted,
                                    fontSize = 11.5.sp
                                )
                                st.hasUpdate -> Text(
                                    text = stringResource(Res.string.browser_status_update_available),
                                    color = colors.accentWarning,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                else -> Text(
                                    text = stringResource(Res.string.browser_status_up_to_date),
                                    color = colors.accentSuccess,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            if (!st.installedVersion.isNullOrBlank()) {
                                Text(
                                    text = stringResource(Res.string.browser_status_installed, st.installedVersion),
                                    color = colors.textSecondary,
                                    fontSize = 11.5.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            if (st.hasUpdate && !st.latestVersion.isNullOrBlank()) {
                                Text(
                                    text = stringResource(Res.string.browser_status_latest, st.latestVersion),
                                    color = colors.textSecondary,
                                    fontSize = 11.5.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            if (st.reason.isNotBlank()) {
                                Text(
                                    text = st.reason,
                                    color = colors.textMuted,
                                    fontSize = 11.sp,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── 分区 2：启动行为 ──
        SettingsSection(
            title = stringResource(Res.string.browser_section_behavior),
            colors = colors
        ) {
            SettingsCard(colors = colors) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SwitchRow(
                        title = stringResource(Res.string.browser_field_headless),
                        checked = draft.headless,
                        onCheckedChange = { draft = draft.copy(headless = it) },
                        colors = colors
                    )
                    SwitchRow(
                        title = stringResource(Res.string.browser_field_humanize),
                        checked = draft.humanize,
                        onCheckedChange = { draft = draft.copy(humanize = it) },
                        colors = colors
                    )
                    LabeledInput(
                        label = stringResource(Res.string.browser_field_humanize_max),
                        value = humanizeMaxSecondsText,
                        onValueChange = { humanizeMaxSecondsText = it },
                        colors = colors,
                        placeholder = "5.0",
                        monospace = true
                    )
                    HorizontalDivider(color = colors.divider.copy(alpha = 0.5f))
                    SwitchRow(
                        title = stringResource(Res.string.browser_field_block_images),
                        checked = draft.blockImages,
                        onCheckedChange = { draft = draft.copy(blockImages = it) },
                        colors = colors
                    )
                    SwitchRow(
                        title = stringResource(Res.string.browser_field_block_webgl),
                        checked = draft.blockWebgl,
                        onCheckedChange = { draft = draft.copy(blockWebgl = it) },
                        colors = colors
                    )
                    SwitchRow(
                        title = stringResource(Res.string.browser_field_block_webrtc),
                        checked = draft.blockWebrtc,
                        onCheckedChange = { draft = draft.copy(blockWebrtc = it) },
                        colors = colors
                    )
                    SwitchRow(
                        title = stringResource(Res.string.browser_field_disable_coop),
                        checked = draft.disableCoop,
                        onCheckedChange = { draft = draft.copy(disableCoop = it) },
                        colors = colors
                    )
                    HorizontalDivider(color = colors.divider.copy(alpha = 0.5f))
                    LabeledInput(
                        label = stringResource(Res.string.browser_field_extra_args),
                        value = extraArgsText,
                        onValueChange = { extraArgsText = it },
                        colors = colors,
                        placeholder = "--disable-blink-features=AutomationControlled",
                        monospace = true
                    )
                }
            }
        }

        // ── 分区 3：指纹覆盖 ──
        SettingsSection(
            title = stringResource(Res.string.browser_section_fingerprint),
            description = stringResource(Res.string.browser_fingerprint_hint),
            colors = colors
        ) {
            SettingsCard(colors = colors) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LabeledInput(
                        label = stringResource(Res.string.browser_field_user_agent),
                        value = draft.userAgent.orEmpty(),
                        onValueChange = { draft = draft.copy(userAgent = it.ifBlank { null }) },
                        colors = colors,
                        monospace = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LabeledInput(
                            label = stringResource(Res.string.browser_field_locale),
                            value = draft.locale.orEmpty(),
                            onValueChange = { draft = draft.copy(locale = it.ifBlank { null }) },
                            colors = colors,
                            modifier = Modifier.weight(1f)
                        )
                        LabeledInput(
                            label = stringResource(Res.string.browser_field_timezone),
                            value = draft.timezone.orEmpty(),
                            onValueChange = { draft = draft.copy(timezone = it.ifBlank { null }) },
                            colors = colors,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    LabeledInput(
                        label = stringResource(Res.string.browser_field_webgl_vendor),
                        value = draft.webglVendor.orEmpty(),
                        onValueChange = { draft = draft.copy(webglVendor = it.ifBlank { null }) },
                        colors = colors
                    )
                    LabeledInput(
                        label = stringResource(Res.string.browser_field_webgl_renderer),
                        value = draft.webglRenderer.orEmpty(),
                        onValueChange = { draft = draft.copy(webglRenderer = it.ifBlank { null }) },
                        colors = colors
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LabeledInput(
                            label = stringResource(Res.string.browser_field_webrtc_ipv4),
                            value = draft.webrtcIpv4.orEmpty(),
                            onValueChange = { draft = draft.copy(webrtcIpv4 = it.ifBlank { null }) },
                            colors = colors,
                            modifier = Modifier.weight(1f)
                        )
                        LabeledInput(
                            label = stringResource(Res.string.browser_field_webrtc_ipv6),
                            value = draft.webrtcIpv6.orEmpty(),
                            onValueChange = { draft = draft.copy(webrtcIpv6 = it.ifBlank { null }) },
                            colors = colors,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    HorizontalDivider(color = colors.divider.copy(alpha = 0.5f))

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LabeledInput(
                            label = stringResource(Res.string.browser_field_geolocation_lat),
                            value = geolocationLatText,
                            onValueChange = { geolocationLatText = it },
                            colors = colors,
                            placeholder = "39.9042",
                            monospace = true,
                            modifier = Modifier.weight(1f)
                        )
                        LabeledInput(
                            label = stringResource(Res.string.browser_field_geolocation_lon),
                            value = geolocationLonText,
                            onValueChange = { geolocationLonText = it },
                            colors = colors,
                            placeholder = "116.4074",
                            monospace = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Text(
                        text = stringResource(Res.string.browser_field_screen_size),
                        color = colors.textPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LabeledInput(
                            label = "screenWidth",
                            value = screenWidthText,
                            onValueChange = { screenWidthText = it },
                            colors = colors,
                            monospace = true,
                            modifier = Modifier.weight(1f)
                        )
                        LabeledInput(
                            label = "screenHeight",
                            value = screenHeightText,
                            onValueChange = { screenHeightText = it },
                            colors = colors,
                            monospace = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LabeledInput(
                            label = "screenAvailWidth",
                            value = screenAvailWidthText,
                            onValueChange = { screenAvailWidthText = it },
                            colors = colors,
                            monospace = true,
                            modifier = Modifier.weight(1f)
                        )
                        LabeledInput(
                            label = "screenAvailHeight",
                            value = screenAvailHeightText,
                            onValueChange = { screenAvailHeightText = it },
                            colors = colors,
                            monospace = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Text(
                        text = stringResource(Res.string.browser_field_window_size),
                        color = colors.textPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LabeledInput(
                            label = "windowOuterWidth",
                            value = windowOuterWidthText,
                            onValueChange = { windowOuterWidthText = it },
                            colors = colors,
                            monospace = true,
                            modifier = Modifier.weight(1f)
                        )
                        LabeledInput(
                            label = "windowOuterHeight",
                            value = windowOuterHeightText,
                            onValueChange = { windowOuterHeightText = it },
                            colors = colors,
                            monospace = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LabeledInput(
                            label = "windowInnerWidth",
                            value = windowInnerWidthText,
                            onValueChange = { windowInnerWidthText = it },
                            colors = colors,
                            monospace = true,
                            modifier = Modifier.weight(1f)
                        )
                        LabeledInput(
                            label = "windowInnerHeight",
                            value = windowInnerHeightText,
                            onValueChange = { windowInnerHeightText = it },
                            colors = colors,
                            monospace = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LabeledInput(
                            label = stringResource(Res.string.browser_field_hardware_concurrency),
                            value = hardwareConcurrencyText,
                            onValueChange = { hardwareConcurrencyText = it },
                            colors = colors,
                            placeholder = "8",
                            monospace = true,
                            modifier = Modifier.weight(1f)
                        )
                        LabeledInput(
                            label = stringResource(Res.string.browser_field_max_touch_points),
                            value = maxTouchPointsText,
                            onValueChange = { maxTouchPointsText = it },
                            colors = colors,
                            placeholder = "10",
                            monospace = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    LabeledInput(
                        label = stringResource(Res.string.browser_field_fonts),
                        value = fontsText,
                        onValueChange = { fontsText = it },
                        colors = colors,
                        placeholder = "Arial, Times New Roman",
                        monospace = true
                    )
                }
            }
        }

        // ── 分区 4：代理 ──
        SettingsSection(
            title = stringResource(Res.string.browser_section_proxy),
            colors = colors
        ) {
            SettingsCard(colors = colors) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = stringResource(Res.string.browser_field_proxy_type),
                            color = colors.textPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        ProxyTypeMenu(
                            selected = proxyType,
                            onSelect = { proxyType = it },
                            colors = colors
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LabeledInput(
                            label = stringResource(Res.string.browser_field_proxy_host),
                            value = proxyHostText,
                            onValueChange = { proxyHostText = it },
                            colors = colors,
                            placeholder = "127.0.0.1",
                            monospace = true,
                            modifier = Modifier.weight(1f)
                        )
                        LabeledInput(
                            label = stringResource(Res.string.browser_field_proxy_port),
                            value = proxyPortText,
                            onValueChange = { proxyPortText = it },
                            colors = colors,
                            placeholder = "1080",
                            monospace = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    LabeledInput(
                        label = stringResource(Res.string.browser_field_proxy_bypass),
                        value = proxyBypassText,
                        onValueChange = { proxyBypassText = it },
                        colors = colors,
                        placeholder = "localhost, 127.0.0.1",
                        monospace = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LabeledInput(
                            label = stringResource(Res.string.browser_field_proxy_username),
                            value = proxyUsernameText,
                            onValueChange = { proxyUsernameText = it },
                            colors = colors,
                            modifier = Modifier.weight(1f)
                        )
                        LabeledInput(
                            label = stringResource(Res.string.browser_field_proxy_password),
                            value = proxyPasswordText,
                            onValueChange = { proxyPasswordText = it },
                            colors = colors,
                            isPassword = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Text(
                        text = stringResource(Res.string.browser_proxy_http_cred_hint),
                        color = colors.textMuted,
                        fontSize = 11.sp
                    )
                }
            }
        }

        // ── 分区 5：高级 ──
        SettingsSection(
            title = stringResource(Res.string.browser_section_advanced),
            colors = colors
        ) {
            SettingsCard(colors = colors) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabeledInput(
                        label = stringResource(Res.string.browser_field_advanced_json),
                        value = advancedConfigText,
                        onValueChange = { advancedConfigText = it },
                        colors = colors,
                        placeholder = """{ "log": { "level": "info" } }""",
                        monospace = true,
                        singleLine = false,
                        minHeight = 96.dp
                    )
                    if (!advancedConfigValid) {
                        Text(
                            text = stringResource(Res.string.browser_advanced_invalid_json),
                            color = colors.accentDanger,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        // ── 错误消息 ──
        errorMessage?.let { msg ->
            Text(
                text = stringResource(msg.key, *msg.args.toTypedArray()),
                color = colors.accentDanger,
                fontSize = 12.sp
            )
        }

        // ── 路径必填错误（browserHome 与 binaryPath 均未配置时显示并禁用保存） ──
        if (!pathConfigured) {
            Text(
                text = stringResource(Res.string.browser_path_required),
                color = colors.accentDanger,
                fontSize = 12.sp
            )
        }

        // ── 底部保存 ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            MederiPrimaryDecisionButton(
                text = stringResource(Res.string.browser_save),
                onClick = {
                    if (!pathConfigured) return@MederiPrimaryDecisionButton  // 路径未配置：见下方错误提示
                    val parsed = buildSettings(
                        draft = draft,
                        humanizeMaxSecondsText = humanizeMaxSecondsText,
                        extraArgsText = extraArgsText,
                        geolocationLatText = geolocationLatText,
                        geolocationLonText = geolocationLonText,
                        screenWidthText = screenWidthText,
                        screenHeightText = screenHeightText,
                        screenAvailWidthText = screenAvailWidthText,
                        screenAvailHeightText = screenAvailHeightText,
                        windowOuterWidthText = windowOuterWidthText,
                        windowOuterHeightText = windowOuterHeightText,
                        windowInnerWidthText = windowInnerWidthText,
                        windowInnerHeightText = windowInnerHeightText,
                        hardwareConcurrencyText = hardwareConcurrencyText,
                        maxTouchPointsText = maxTouchPointsText,
                        fontsText = fontsText,
                        proxyType = proxyType,
                        proxyHostText = proxyHostText,
                        proxyPortText = proxyPortText,
                        proxyBypassText = proxyBypassText,
                        proxyUsernameText = proxyUsernameText,
                        proxyPasswordText = proxyPasswordText,
                        advancedConfigText = advancedConfigText
                    )
                    scope.launch { store.save(parsed) }
                },
                enabled = !isOperating && advancedConfigValid && pathConfigured,
                icon = FeatherIcons.Check
            )
        }
    }

    // 目录选择对话框
    if (showHomeDialog) {
        BrowserHomeDialog(
            initialPath = draft.browserHome.orEmpty(),
            onDismiss = { showHomeDialog = false },
            onConfirm = { path ->
                draft = draft.copy(browserHome = path)
                showHomeDialog = false
            },
            colors = colors
        )
    }
}

/** 保存时统一解析：数字/列表字段失败保持 null/空；高级 JSON 合法转 Map。 */
private fun buildSettings(
    draft: CamoufoxSettings,
    humanizeMaxSecondsText: String,
    extraArgsText: String,
    geolocationLatText: String,
    geolocationLonText: String,
    screenWidthText: String,
    screenHeightText: String,
    screenAvailWidthText: String,
    screenAvailHeightText: String,
    windowOuterWidthText: String,
    windowOuterHeightText: String,
    windowInnerWidthText: String,
    windowInnerHeightText: String,
    hardwareConcurrencyText: String,
    maxTouchPointsText: String,
    fontsText: String,
    proxyType: String,
    proxyHostText: String,
    proxyPortText: String,
    proxyBypassText: String,
    proxyUsernameText: String,
    proxyPasswordText: String,
    advancedConfigText: String
): CamoufoxSettings {
    val proxy = ProxyConfig(
        type = proxyTypeOf(proxyType),
        host = proxyHostText.trim(),
        port = proxyPortText.toIntOrNull() ?: 0,
        bypass = splitComma(proxyBypassText),
        username = proxyUsernameText.trim().ifBlank { null },
        password = proxyPasswordText.ifBlank { null }
    )
    return draft.copy(
        humanizeMaxSeconds = humanizeMaxSecondsText.toDoubleOrNull(),
        extraArgs = splitArgs(extraArgsText),
        geolocationLat = geolocationLatText.toDoubleOrNull(),
        geolocationLon = geolocationLonText.toDoubleOrNull(),
        screenWidth = screenWidthText.toIntOrNull(),
        screenHeight = screenHeightText.toIntOrNull(),
        screenAvailWidth = screenAvailWidthText.toIntOrNull(),
        screenAvailHeight = screenAvailHeightText.toIntOrNull(),
        windowOuterWidth = windowOuterWidthText.toIntOrNull(),
        windowOuterHeight = windowOuterHeightText.toIntOrNull(),
        windowInnerWidth = windowInnerWidthText.toIntOrNull(),
        windowInnerHeight = windowInnerHeightText.toIntOrNull(),
        hardwareConcurrency = hardwareConcurrencyText.toIntOrNull(),
        maxTouchPoints = maxTouchPointsText.toIntOrNull(),
        fonts = splitComma(fontsText),
        proxy = proxy,
        advancedConfig = if (advancedConfigText.isBlank()) {
            emptyMap()
        } else {
            (Json.parseToJsonElement(advancedConfigText) as JsonObject)
        }
    )
}

private fun proxyTypeOf(value: String): String = when (value) {
    "http", "socks" -> value
    else -> "none"
}

private fun splitArgs(text: String): List<String> =
    text.split(Regex("[,\\s]+")).map { it.trim() }.filter { it.isNotEmpty() }

private fun splitComma(text: String): List<String> =
    text.split(",").map { it.trim() }.filter { it.isNotEmpty() }

/** 面板级 JSON 格式（prettyPrint 用于展示高级配置）。 */
private val browserPanelJson: Json = Json { prettyPrint = true }

/** 设置 Map → 可编辑 JSON 文本（空 Map 显示为空串）。 */
private fun Map<String, kotlinx.serialization.json.JsonElement>.toJsonText(): String =
    if (isEmpty()) "" else browserPanelJson.encodeToString(JsonElement.serializer(), JsonObject(this))

/** 开关行。 */
@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    colors: MederiColors,
    subtitle: String? = null
) {
    SettingsRow(title = title, subtitle = subtitle, colors = colors) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = switchColors(colors)
        )
    }
}

/** 标签 + 输入框行（本地化标签，技术占位符）。 */
@Composable
private fun LabeledInput(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    colors: MederiColors,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    monospace: Boolean = false,
    singleLine: Boolean = true,
    isPassword: Boolean = false,
    minHeight: androidx.compose.ui.unit.Dp = 0.dp
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = label,
            color = colors.textPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
        val style = if (monospace) {
            androidx.compose.ui.text.TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        } else {
            MederiTypeScale.Row
        }
        val effectiveModifier = if (minHeight > 0.dp) {
            Modifier.heightIn(min = minHeight)
        } else {
            Modifier
        }
        SettingsInputField(
            value = value,
            onValueChange = onValueChange,
            modifier = effectiveModifier.fillMaxWidth(),
            placeholder = placeholder,
            singleLine = singleLine,
            visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            textStyle = style,
            colors = colors
        )
    }
}

/** 代理类型下拉（none/http/socks）。 */
@Composable
private fun ProxyTypeMenu(
    selected: String,
    onSelect: (String) -> Unit,
    colors: MederiColors
) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf("none", "http", "socks")

    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(MederiRadius.Control))
                .background(colors.surfaceWorkspace)
                .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(MederiRadius.Control))
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = selected,
                color = colors.textPrimary,
                fontSize = 12.5.sp,
                fontFamily = FontFamily.Monospace
            )
            Icon(
                imageVector = FeatherIcons.ChevronDown,
                contentDescription = null,
                tint = colors.textMuted,
                modifier = Modifier.size(14.dp)
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = colors.surfaceCard,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .widthIn(min = 120.dp)
                .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(10.dp))
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    modifier = Modifier
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (option == selected) colors.accentPrimary.copy(alpha = 0.12f) else Color.Transparent),
                    text = {
                        Text(
                            text = option,
                            color = if (option == selected) colors.accentPrimary else colors.textPrimary,
                            fontSize = 12.5.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

/** 浏览器目录选择对话框（SetSkillRootDialog 同款：桌面端 trailingIcon 系统目录选择，遥控端纯文本输入）。 */
@Composable
private fun BrowserHomeDialog(
    initialPath: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    colors: MederiColors
) {
    var pathText by remember { mutableStateOf(initialPath) }
    val scope = rememberCoroutineScope()
    val pickDirectoryTitle = stringResource(Res.string.browser_pick_home_title)

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .width(420.dp)
                .padding(16.dp),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = colors.surfaceCard),
            border = BorderStroke(1.dp, colors.divider)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(Res.string.browser_pick_home_title),
                    color = colors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )

                OutlinedTextField(
                    value = pathText,
                    onValueChange = { pathText = it },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = if (isDesktopPlatform) {
                        {
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        val picked = pickDirectory(pickDirectoryTitle)
                                        if (!picked.isNullOrBlank()) {
                                            pathText = picked
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = FeatherIcons.Folder,
                                    contentDescription = stringResource(Res.string.browser_pick_home_title),
                                    tint = colors.accentPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    } else null,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = colors.textPrimary
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.accentPrimary,
                        unfocusedBorderColor = colors.divider,
                        focusedContainerColor = colors.surfaceWorkspace,
                        unfocusedContainerColor = colors.surfaceWorkspace
                    ),
                    singleLine = true
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MederiGhostButton(
                        text = stringResource(Res.string.cancel),
                        onClick = onDismiss
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    MederiPrimaryDecisionButton(
                        text = stringResource(Res.string.browser_save),
                        onClick = {
                            if (pathText.isNotBlank()) {
                                onConfirm(pathText.trim())
                            }
                        },
                        enabled = pathText.isNotBlank()
                    )
                }
            }
        }
    }
}
