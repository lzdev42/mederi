package xyz.mederi.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.horizontalScroll
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import xyz.mederi.AppInfo
import xyz.mederi.core.contract.dto.CreateCustomProviderInput
import xyz.mederi.core.contract.dto.ProviderUpdateInput
import xyz.mederi.core.contract.models.*
import xyz.mederi.ui.appstate.LocalAppState
import xyz.mederi.ui.appstate.RemoteServerUiState
import xyz.mederi.ui.appstate.TunnelUiState
import xyz.mederi.theme.AppLanguage
import xyz.mederi.theme.AppThemeMode
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiColors
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.close
import mederi.app.shared.generated.resources.language_system
import kotlinx.coroutines.launch
import mederi.app.shared.generated.resources.settings_add
import xyz.mederi.debug.DebugLog
import xyz.mederi.ui.components.ChipSelectorPill
import xyz.mederi.ui.components.ModelPickerList
import xyz.mederi.ui.components.formatReasoningLevelLabel
import mederi.app.shared.generated.resources.input_thinking_label
import mederi.app.shared.generated.resources.settings_agents_desc
import mederi.app.shared.generated.resources.settings_agents_title
import mederi.app.shared.generated.resources.settings_agents_inherit
import mederi.app.shared.generated.resources.settings_agents_inherit_reasoning
import mederi.app.shared.generated.resources.settings_agents_inherit_reasoning_short
import mederi.app.shared.generated.resources.settings_agents_model_label
import mederi.app.shared.generated.resources.settings_agents_reasoning_label
import mederi.app.shared.generated.resources.settings_agents_status_custom
import mederi.app.shared.generated.resources.settings_agents_status_inheriting
import mederi.app.shared.generated.resources.settings_appearance_desc
import mederi.app.shared.generated.resources.settings_appearance_title
import mederi.app.shared.generated.resources.settings_language_desc
import mederi.app.shared.generated.resources.settings_language_title
import mederi.app.shared.generated.resources.settings_remote_desc
import mederi.app.shared.generated.resources.settings_remote_lan_address
import mederi.app.shared.generated.resources.settings_remote_no_lan
import mederi.app.shared.generated.resources.settings_remote_password_label
import mederi.app.shared.generated.resources.settings_remote_password_note
import mederi.app.shared.generated.resources.settings_remote_password_placeholder
import mederi.app.shared.generated.resources.settings_remote_port_fallback
import mederi.app.shared.generated.resources.settings_remote_state_failed
import mederi.app.shared.generated.resources.settings_remote_state_idle
import mederi.app.shared.generated.resources.settings_remote_state_running
import mederi.app.shared.generated.resources.settings_remote_state_starting
import mederi.app.shared.generated.resources.settings_remote_title
import mederi.app.shared.generated.resources.settings_retry
import mederi.app.shared.generated.resources.settings_sandbox_desc
import mederi.app.shared.generated.resources.settings_sandbox_enabled
import mederi.app.shared.generated.resources.settings_sandbox_querying
import mederi.app.shared.generated.resources.settings_sandbox_remote_hint
import mederi.app.shared.generated.resources.settings_sandbox_status_unavailable
import mederi.app.shared.generated.resources.settings_sandbox_title
import mederi.app.shared.generated.resources.settings_sandbox_unknown
import mederi.app.shared.generated.resources.settings_sandbox_unavailable
import mederi.app.shared.generated.resources.settings_sandbox_whitelist_desc
import mederi.app.shared.generated.resources.settings_sandbox_whitelist_empty
import mederi.app.shared.generated.resources.settings_sandbox_whitelist_title
import mederi.app.shared.generated.resources.settings_sandbox_write_scope
import mederi.app.shared.generated.resources.settings_show
import mederi.app.shared.generated.resources.settings_hide
import mederi.app.shared.generated.resources.settings_subtitle
import mederi.app.shared.generated.resources.settings_system_config_dir
import mederi.app.shared.generated.resources.settings_system_prefs_file
import mederi.app.shared.generated.resources.settings_system_title
import mederi.app.shared.generated.resources.settings_system_version
import mederi.app.shared.generated.resources.settings_tab_agents
import mederi.app.shared.generated.resources.settings_tab_general
import mederi.app.shared.generated.resources.settings_tab_providers
import mederi.app.shared.generated.resources.settings_tab_remote
import mederi.app.shared.generated.resources.settings_tab_sandbox
import mederi.app.shared.generated.resources.settings_tab_system
import mederi.app.shared.generated.resources.settings_title
import mederi.app.shared.generated.resources.settings_tunnel_desc
import mederi.app.shared.generated.resources.settings_tunnel_failed
import mederi.app.shared.generated.resources.settings_tunnel_no_hostname
import mederi.app.shared.generated.resources.settings_tunnel_not_installed
import mederi.app.shared.generated.resources.settings_tunnel_public_url
import mederi.app.shared.generated.resources.settings_tunnel_running
import mederi.app.shared.generated.resources.settings_tunnel_start
import mederi.app.shared.generated.resources.settings_tunnel_starting
import mederi.app.shared.generated.resources.settings_tunnel_title
import mederi.app.shared.generated.resources.settings_tunnel_stop
import mederi.app.shared.generated.resources.settings_remove
import mederi.app.shared.generated.resources.theme_dark
import mederi.app.shared.generated.resources.theme_light
import org.jetbrains.compose.resources.stringResource

/** 设置页标签（显示文案唯一映射点 = settingsTabLabel，枚举不持有表现层文案，i18n 约定）。 */
private enum class SettingsTab(val icon: ImageVector) {
    PROVIDERS(FeatherIcons.Cpu),
    GENERAL(FeatherIcons.Droplet),
    SANDBOX(FeatherIcons.Shield),
    AGENTS(FeatherIcons.Users),
    REMOTE(FeatherIcons.Sliders),
    SYSTEM(FeatherIcons.Activity)
}

/** 设置页标签文案唯一映射点。 */
@Composable
private fun settingsTabLabel(tab: SettingsTab): String = when (tab) {
    SettingsTab.PROVIDERS -> stringResource(Res.string.settings_tab_providers)
    SettingsTab.GENERAL -> stringResource(Res.string.settings_tab_general)
    SettingsTab.SANDBOX -> stringResource(Res.string.settings_tab_sandbox)
    SettingsTab.AGENTS -> stringResource(Res.string.settings_tab_agents)
    SettingsTab.REMOTE -> stringResource(Res.string.settings_tab_remote)
    SettingsTab.SYSTEM -> stringResource(Res.string.settings_tab_system)
}

@Composable
fun SettingsDialog(
    isVisible: Boolean,
    onClose: () -> Unit
) {
    if (!isVisible) return
    val appState = LocalAppState.current
    val colors = LocalMederiColors.current
    var selectedTab by remember { mutableStateOf(SettingsTab.PROVIDERS) }

    Box(
        modifier = Modifier.fillMaxSize().background(colors.surfaceOverlay).clickable(onClick = onClose),
        contentAlignment = Alignment.Center
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val isCompact = maxWidth < 700.dp
            val dialogWidth = if (isCompact) maxWidth else minOf(1120.dp, maxWidth - 40.dp)
            val dialogHeight = if (isCompact) maxHeight else minOf(740.dp, maxHeight - 40.dp)
            val dialogCorner = if (isCompact) 0.dp else 14.dp

            Column(
                modifier = Modifier
                    .width(dialogWidth)
                    .height(dialogHeight)
                    .clip(RoundedCornerShape(dialogCorner))
                    .background(colors.surfaceCard)
                    .border(if (isCompact) 0.dp else 1.dp, colors.divider, RoundedCornerShape(dialogCorner))
                    .clickable(enabled = false) {}
            ) {
                // 1. 顶部 Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surfaceSidebar)
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            modifier = Modifier.size(28.dp).clip(RoundedCornerShape(7.dp))
                                .background(colors.accentPrimary.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) { Icon(FeatherIcons.Sliders, null, tint = colors.accentPrimary, modifier = Modifier.size(15.dp)) }
                        Column {
                            Text(stringResource(Res.string.settings_title), color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            if (!isCompact) {
                                Text(stringResource(Res.string.settings_subtitle), color = colors.textMuted, fontSize = 10.5.sp)
                            }
                        }
                    }
                    Box(
                        modifier = Modifier.size(28.dp).clip(RoundedCornerShape(6.dp))
                            .background(colors.surfaceCard).border(1.dp, colors.divider, RoundedCornerShape(6.dp))
                            .clickable { onClose() }, contentAlignment = Alignment.Center
                    ) { Icon(FeatherIcons.X, stringResource(Res.string.close), tint = colors.textSecondary, modifier = Modifier.size(13.dp)) }
                }

                // 2. 浏览器风格水平标签栏 (Browser Tabs Bar)
                BrowserTabsBar(
                    selectedTab = selectedTab,
                    onSelectTab = { selectedTab = it },
                    colors = colors
                )

                HorizontalDivider(color = colors.divider)

                // 3. 全宽工作区内容
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(colors.surfaceCard)
                        .let { if (selectedTab == SettingsTab.PROVIDERS) it else it.padding(if (isCompact) 14.dp else 22.dp) }
                ) {
                    when (selectedTab) {
                        SettingsTab.PROVIDERS -> ProviderSettingsPanel()
                        SettingsTab.GENERAL -> GeneralPanel(isCompact = isCompact)
                        SettingsTab.SANDBOX -> SandboxPanel()
                        SettingsTab.AGENTS -> AgentsPanel(isCompact = isCompact)
                        SettingsTab.REMOTE -> RemoteControlPanel(isCompact = isCompact)
                        SettingsTab.SYSTEM -> SystemPanel(isCompact = isCompact)
                    }
                }
            }
        }
    }
}

/**
 * 类似浏览器的顶部水平标签栏。
 */
@Composable
private fun BrowserTabsBar(
    selectedTab: SettingsTab,
    onSelectTab: (SettingsTab) -> Unit,
    colors: MederiColors
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceSidebar)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 0.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        SettingsTab.entries.forEach { tab ->
            val isSelected = selectedTab == tab
            BrowserTabItem(
                icon = tab.icon,
                label = settingsTabLabel(tab),
                isSelected = isSelected,
                colors = colors,
                onClick = { onSelectTab(tab) }
            )
        }
    }
}

@Composable
private fun BrowserTabItem(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    colors: MederiColors,
    onClick: () -> Unit
) {
    val tabBg = if (isSelected) colors.surfaceCard else Color.Transparent
    val borderColor = if (isSelected) colors.divider else Color.Transparent

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
            .background(tabBg)
            .border(
                BorderStroke(1.dp, borderColor),
                RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) colors.accentPrimary else colors.iconMuted,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = label,
                color = if (isSelected) colors.textPrimary else colors.textSecondary,
                fontSize = 12.5.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

@Composable private fun GeneralPanel(isCompact: Boolean = false) {
    val appState = LocalAppState.current
    val c = LocalMederiColors.current
    val currentTheme by appState.theme.collectAsState()
    val currentLanguage by appState.language.collectAsState()
    val scroll = rememberScrollState()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(Res.string.settings_appearance_title), color = c.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(Res.string.settings_appearance_desc), color = c.textSecondary, fontSize = 12.sp)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ThemeCard(
                theme = AppThemeMode.DARK,
                isSelected = currentTheme == AppThemeMode.DARK,
                c = c,
                modifier = Modifier.weight(1f),
                onSelect = { appState.setTheme(AppThemeMode.DARK) }
            )
            ThemeCard(
                theme = AppThemeMode.LIGHT,
                isSelected = currentTheme == AppThemeMode.LIGHT,
                c = c,
                modifier = Modifier.weight(1f),
                onSelect = { appState.setTheme(AppThemeMode.LIGHT) }
            )
        }

        // ─── 语言（Language）：唯一真理源 = AppState.language；切换即时生效（AppEnvironment → LocalAppLocale → 全树重组） ───
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(Res.string.settings_language_title), color = c.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(Res.string.settings_language_desc), color = c.textSecondary, fontSize = 12.sp)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AppLanguage.entries.forEach { language ->
                LanguageCard(
                    language = language,
                    isSelected = currentLanguage == language,
                    c = c,
                    modifier = Modifier.weight(1f),
                    onSelect = { appState.setLanguage(language) }
                )
            }
        }
    }
}

/** 语言选择卡。SYSTEM 用资源文案，其余显示语言原生名（专有名词不翻译）。 */
@Composable
private fun LanguageCard(
    language: AppLanguage,
    isSelected: Boolean,
    c: MederiColors,
    modifier: Modifier = Modifier,
    onSelect: () -> Unit
) {
    val cardBorder = if (isSelected) c.accentPrimary else c.divider
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(c.surfaceCard)
            .border(if (isSelected) 2.dp else 1.dp, cardBorder, RoundedCornerShape(12.dp))
            .clickable { onSelect() }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = FeatherIcons.Globe,
            contentDescription = null,
            tint = if (isSelected) c.accentPrimary else c.textSecondary,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = if (language == AppLanguage.SYSTEM) stringResource(Res.string.language_system) else language.nativeName,
            color = if (isSelected) c.accentPrimary else c.textPrimary,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

/**
 * 沙盒面板：状态卡（平台机制/可用性/原因）+ 全局写白名单编辑器（项目外额外可写路径）。
 *
 * 沙盒永远开、无开关（docs/sandbox-plan.md）：本面板只提供
 * 1. 状态展示（为什么写不进去/哪里可用）；
 * 2. 全局白名单（合法扩大写范围的唯一入口，另一入口是把目录加入项目）。
 */
@Composable private fun SandboxPanel() {
    val appState = LocalAppState.current
    val c = LocalMederiColors.current
    val scroll = rememberScrollState()
    val hooks = appState.aiCore as? xyz.mederi.core.contract.SandboxHooks

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(Res.string.settings_sandbox_title), color = c.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(Res.string.settings_sandbox_desc), color = c.textSecondary, fontSize = 12.sp)
        }

        // 1. 状态卡
        SandboxStatusCard(hooks)

        // 2. 全局白名单（仅嵌入式 core 可编辑；远程/模拟端只读说明）
        if (hooks != null) {
            SandboxWhitelistCard(appState, c)
        } else {
            Text(
                stringResource(Res.string.settings_sandbox_remote_hint),
                color = c.textMuted, fontSize = 11.5.sp
            )
        }
    }
}

/** 沙盒状态卡：后端机制 + 可用性 + 不可用原因（含安装命令文案）。 */
@Composable
private fun SandboxStatusCard(hooks: xyz.mederi.core.contract.SandboxHooks?) {
    val c = LocalMederiColors.current
    // 沙盒可用性是进程级环境事实（一次性查询）：经 LaunchedEffect 异步查询，
    // 不阻塞组合；hooks 非空且查询未返回时显示"正在查询"而非误报"未知"
    var status by remember(hooks) { mutableStateOf<xyz.mederi.core.contract.SandboxStatusInfo?>(null) }
    LaunchedEffect(hooks) { status = hooks?.sandboxStatus() }
    val isQuerying = hooks != null && status == null

    val cardBg = c.surfaceCard
    val cardBorder = c.surfaceCardBorder

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(cardBg)
            .border(1.dp, cardBorder, RoundedCornerShape(10.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                imageVector = if (status?.available == true) FeatherIcons.Shield else FeatherIcons.AlertCircle,
                contentDescription = null,
                tint = if (status?.available == true) c.accentPrimary else c.accentWarning,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = run {
                    val st = status
                    when {
                        isQuerying -> stringResource(Res.string.settings_sandbox_querying)
                        st == null -> stringResource(Res.string.settings_sandbox_unknown)
                        st.available -> stringResource(Res.string.settings_sandbox_enabled, st.backend)
                        else -> stringResource(Res.string.settings_sandbox_unavailable)
                    }
                },
                color = c.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.weight(1f))
            Text("shell: ${status?.shell ?: "-"}", color = c.textMuted, fontSize = 11.sp)
        }
        Text(
            text = status?.detail
                ?: stringResource(Res.string.settings_sandbox_status_unavailable),
            color = c.textSecondary, fontSize = 11.5.sp
        )
        Text(
            text = stringResource(Res.string.settings_sandbox_write_scope),
            color = c.textMuted, fontSize = 11.sp
        )
    }
}

/** 全局白名单编辑卡：路径列表增删，实时写穿生效。 */
@Composable
private fun SandboxWhitelistCard(appState: xyz.mederi.ui.appstate.AppState, c: MederiColors) {
    val paths by appState.sandboxExtraPaths.collectAsState()
    var input by remember { mutableStateOf("") }

    val cardBg = c.surfaceCard
    val cardBorder = c.surfaceCardBorder

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(cardBg)
            .border(1.dp, cardBorder, RoundedCornerShape(10.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(Res.string.settings_sandbox_whitelist_title), color = c.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(Res.string.settings_sandbox_whitelist_desc), color = c.textMuted, fontSize = 11.sp)
        }

        // 已添加路径列表
        if (paths.isEmpty()) {
            Text(stringResource(Res.string.settings_sandbox_whitelist_empty), color = c.textMuted, fontSize = 11.5.sp)
        } else {
            paths.forEach { path ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = path,
                        color = c.textPrimary, fontSize = 11.5.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = stringResource(Res.string.settings_remove),
                        color = c.accentDanger, fontSize = 11.sp,
                        modifier = Modifier.clickable { appState.setSandboxExtraPaths(paths - path) }
                    )
                }
            }
        }

        // 添加输入行
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("/absolute/path/to/dir", color = c.textMuted, fontSize = 11.5.sp) },
                textStyle = androidx.compose.ui.text.TextStyle(color = c.textPrimary, fontSize = 12.sp),
                singleLine = true,
                modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                shape = RoundedCornerShape(8.dp)
            )
            Button(
                onClick = {
                    val p = input.trim()
                    if (p.isNotEmpty() && p !in paths) {
                        appState.setSandboxExtraPaths(paths + p)
                        input = ""
                    }
                },
                enabled = input.trim().isNotEmpty(),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = c.accentPrimary)
            ) {
                Text(stringResource(Res.string.settings_add), fontSize = 11.5.sp, color = c.onAccentPrimary)
            }
        }
    }
}

/** 远程遥控面板：desktop 内嵌 server 的启停 + 密码（独立 tab，与主题无关）。 */
@Composable private fun RemoteControlPanel(isCompact: Boolean = false) {    val c = LocalMederiColors.current
    val scroll = rememberScrollState()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(Res.string.settings_remote_title), color = c.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(Res.string.settings_remote_desc), color = c.textSecondary, fontSize = 12.sp)
        }
        RemoteControlCard(isCompact = isCompact)
    }
}

/**
 * 远程遥控卡片：desktop 上允许手机/浏览器遥控本机（内嵌 server 启停 + 密码）。
 * 其他端 appState.remoteControl 为 null，整卡不显示。
 */
@Composable private fun RemoteControlCard(isCompact: Boolean = false) {
    val appState = LocalAppState.current
    val c = LocalMederiColors.current
    val hooks = appState.remoteControl ?: return

    val enabled by appState.remoteControlEnabled.collectAsState()
    val savedPassword by appState.remoteControlPassword.collectAsState()
    val serverState by appState.remoteServerState.collectAsState()
    val tunnelState by appState.tunnelState.collectAsState()
    val localAddr = hooks.localAddress

    var password by remember { mutableStateOf(savedPassword ?: "") }
    var showPassword by remember { mutableStateOf(false) }

    val cardBg = c.surfaceCard
    val cardBorder = c.surfaceCardBorder

    val port = when (val s = serverState) {
        is RemoteServerUiState.Running -> s.port
        else -> appState.remotePort.value
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(cardBg)
            .border(1.dp, cardBorder, RoundedCornerShape(12.dp))
            .padding(if (isCompact) 12.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ─── 开关行 ───
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(Res.string.settings_remote_title), color = c.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(
                    when (serverState) {
                        is RemoteServerUiState.Idle -> stringResource(Res.string.settings_remote_state_idle)
                        is RemoteServerUiState.Starting -> stringResource(Res.string.settings_remote_state_starting)
                        is RemoteServerUiState.Running -> stringResource(Res.string.settings_remote_state_running)
                        is RemoteServerUiState.Failed -> stringResource(Res.string.settings_remote_state_failed)
                    },
                    color = c.textSecondary, fontSize = 12.sp
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { on ->
                    appState.setRemoteControl(on, password)
                }
            )
        }

        // ─── 状态详情 ───
        val ss = serverState
        when (ss) {
            is RemoteServerUiState.Running -> {
                if (ss.portFallback) {
                    Text(
                        stringResource(Res.string.settings_remote_port_fallback, ss.port),
                        color = c.accentWarning, fontSize = 12.sp
                    )
                }
                // 局域网地址
                if (localAddr != null) {
                    Text(
                        stringResource(Res.string.settings_remote_lan_address, localAddr, ss.port),
                        color = c.accentSuccess, fontSize = 13.sp, fontWeight = FontWeight.Medium
                    )
                } else {
                    Text(stringResource(Res.string.settings_remote_no_lan), color = c.textMuted, fontSize = 12.sp)
                }
            }
            is RemoteServerUiState.Failed -> {
                val reasonText = stringResource(ss.reason.key, *ss.reason.args.toTypedArray())
                Text(reasonText, color = c.accentDanger, fontSize = 12.sp)
            }
            else -> {}
        }

        // ─── 密码输入框 ───
        if (enabled && serverState !is RemoteServerUiState.Starting) {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(Res.string.settings_remote_password_label)) },
                placeholder = { Text(stringResource(Res.string.settings_remote_password_placeholder), fontSize = 11.sp) },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    Text(
                        stringResource(if (showPassword) Res.string.settings_hide else Res.string.settings_show),
                        color = c.accentPrimary, fontSize = 12.sp,
                        modifier = Modifier.clickable { showPassword = !showPassword }.padding(horizontal = 10.dp)
                    )
                },
                colors = OutlinedTextFieldDefaults.colors()
            )
            Text(
                stringResource(Res.string.settings_remote_password_note),
                color = c.textSecondary, fontSize = 11.sp
            )
        }

        // ─── Cloudflare 隧道区块 ───
        if (ss is RemoteServerUiState.Running) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.settings_tunnel_title), color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)

                val ts = tunnelState
                when (ts) {
                    is TunnelUiState.Idle -> {
                        Text(stringResource(Res.string.settings_tunnel_desc), color = c.textSecondary, fontSize = 11.sp)
                        Button(
                            onClick = { appState.startTunnel() },
                            enabled = true,
                            colors = ButtonDefaults.buttonColors(containerColor = c.accentPrimary, contentColor = c.onAccentPrimary)
                        ) { Text(stringResource(Res.string.settings_tunnel_start), fontSize = 12.sp) }
                    }
                    is TunnelUiState.Starting -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = c.accentPrimary)
                            Text(stringResource(Res.string.settings_tunnel_starting), color = c.textSecondary, fontSize = 12.sp)
                        }
                    }
                    is TunnelUiState.Running -> {
                        Text(stringResource(Res.string.settings_tunnel_running), color = c.accentSuccess, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        val url = ts.url
                        if (url != null) {
                            Text(stringResource(Res.string.settings_tunnel_public_url, url), color = c.accentPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        } else {
                            Text(stringResource(Res.string.settings_tunnel_no_hostname), color = c.textMuted, fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = { appState.stopTunnel() },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = c.accentDanger)
                        ) { Text(stringResource(Res.string.settings_tunnel_stop), fontSize = 12.sp) }
                    }
                    is TunnelUiState.Failed -> {
                        if (ts.notInstalled) {
                            Text(stringResource(Res.string.settings_tunnel_not_installed), color = c.accentDanger, fontSize = 12.sp)
                        } else {
                            val tunnelReason = stringResource(ts.reason.key, *ts.reason.args.toTypedArray())
                            Text(stringResource(Res.string.settings_tunnel_failed, tunnelReason), color = c.accentDanger, fontSize = 12.sp)
                        }
                        Button(
                            onClick = { appState.startTunnel() },
                            colors = ButtonDefaults.buttonColors(containerColor = c.accentPrimary, contentColor = c.onAccentPrimary)
                        ) { Text(stringResource(Res.string.settings_retry), fontSize = 12.sp) }
                    }
                }
            }
        }
    }
}

@Composable private fun ThemeCard(
    theme: AppThemeMode,
    isSelected: Boolean,
    c: MederiColors,
    modifier: Modifier = Modifier,
    onSelect: () -> Unit
) {
    val isDark = theme.isDark
    val cardBg = c.surfaceCard
    val cardBorder = if (isSelected) c.accentPrimary else c.surfaceCardBorder
    val previewBg = c.surfaceWorkspace
    val previewText = if (isDark) Color(0xFFE6EDF3) else Color(0xFF1F2328)
    val previewSub = if (isDark) Color(0xFF8B949E) else Color(0xFF656D76)

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(cardBg)
            .border(if (isSelected) 2.dp else 1.dp, cardBorder, RoundedCornerShape(12.dp))
            .clickable { onSelect() }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 预览模拟卡片
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(previewBg)
                .border(1.dp, c.surfaceCardBorder, RoundedCornerShape(8.dp))
                .padding(10.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .width(60.dp)
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(previewText)
                    )
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isDark) Color(0xFF5E6AD2) else Color(0xFF4C5CD6))
                    )
                }
                Box(
                    modifier = Modifier
                        .width(100.dp)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(previewSub)
                )
                Box(
                    modifier = Modifier
                        .width(45.dp)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(previewSub.copy(alpha = 0.5f))
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (isDark) FeatherIcons.Moon else FeatherIcons.Sun,
                    contentDescription = null,
                    tint = if (isSelected) c.accentPrimary else c.textSecondary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = stringResource(if (theme.isDark) Res.string.theme_dark else Res.string.theme_light),
                    color = if (isSelected) c.accentPrimary else c.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                )
            }

            if (isSelected) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(c.accentPrimary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = FeatherIcons.Check,
                        contentDescription = null,
                        tint = c.onAccentPrimary,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentsPanel(isCompact: Boolean = false) {
    val appState = LocalAppState.current
    val c = LocalMederiColors.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()

    var configs by remember { mutableStateOf<List<SubagentConfigItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    val availableModels by appState.availableModels.collectAsState()
    val providers by appState.providers.collectAsState()
    val parentModel by appState.selectedModel.collectAsState()

    val refreshConfigs: () -> Unit = {
        scope.launch {
            val list = appState.aiCore.listSubagentConfigs().getOrDefault(emptyList())
            DebugLog.info("AgentsPanel", "Subagent configs fetched: count=${list.size}, configs=$list")
            configs = list
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshConfigs()
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(Res.string.settings_agents_title), color = c.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(Res.string.settings_agents_desc), color = c.textMuted, fontSize = 12.sp)
        }

        if (isLoading && configs.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = c.accentPrimary, strokeWidth = 2.dp)
            }
        } else {
            configs.forEach { item ->
                AgentConfigCard(
                    item = item,
                    availableModels = availableModels,
                    providers = providers,
                    parentModel = parentModel,
                    isCompact = isCompact,
                    colors = c,
                    onUpdate = { modelId, reasoningLevel ->
                        DebugLog.info("AgentsPanel", "Update config for role=${item.role}: modelId=$modelId, reasoningLevel=$reasoningLevel")
                        scope.launch {
                            appState.aiCore.updateSubagentConfig(
                                item.role,
                                UpdateSubagentConfigInput(modelId = modelId, reasoningLevel = reasoningLevel)
                            )
                            refreshConfigs()
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun AgentConfigCard(
    item: SubagentConfigItem,
    availableModels: List<ModelOption>,
    providers: List<ProviderConfig>,
    parentModel: ModelOption?,
    isCompact: Boolean,
    colors: MederiColors,
    onUpdate: (modelId: String?, reasoningLevel: String?) -> Unit
) {
    // 选中的模型对象：如果未指定（null），说明继承父会话模型；否则查 availableModels
    val currentSelectedModel = remember(item.modelId, availableModels) {
        item.modelId?.let { id -> availableModels.find { it.id == id } }
    }

    // 真正生效的模型（用于推导是否支持推理）：
    // 若指定了模型则为该模型；若继承父模型则为当前会话主模型 parentModel
    val effectiveModel = currentSelectedModel ?: parentModel

    // 推理能力关联判断：完全复用对话框逻辑
    val reasoningLevels = effectiveModel?.reasoningLevels ?: emptyList()
    val supportsReasoning = effectiveModel?.supportsThinking == true && reasoningLevels.isNotEmpty()
    DebugLog.debug("AgentsPanel", "Role ${item.role} effectiveModel=${effectiveModel?.id}, supportsReasoning=$supportsReasoning, levels=$reasoningLevels")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceCard)
            .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(12.dp))
            .padding(if (isCompact) 12.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 头部：角色名 + 状态徽章 + 恢复继承按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    if (item.role == "RESEARCHER") FeatherIcons.Search else FeatherIcons.Cpu,
                    contentDescription = null,
                    tint = colors.accentPrimary,
                    modifier = Modifier.size(16.dp)
                )
                Text(item.displayName, color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                // 状态徽章
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (item.isInheriting) colors.surfaceCardBorder else colors.accentPrimary.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        if (item.isInheriting) stringResource(Res.string.settings_agents_status_inheriting)
                        else stringResource(Res.string.settings_agents_status_custom),
                        color = if (item.isInheriting) colors.textMuted else colors.accentPrimary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            if (!item.isInheriting) {
                Text(
                    text = stringResource(Res.string.settings_agents_inherit),
                    color = colors.accentPrimary,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { onUpdate(null, null) }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                )
            }
        }

        // 描述文本
        if (item.description.isNotBlank()) {
            Text(item.description, color = colors.textMuted, fontSize = 12.sp)
        }

        HorizontalDivider(color = colors.divider.copy(alpha = 0.5f))

        // 选择器栏：完全复用对话框样式（ChipSelectorPill）与弹窗（ModelPickerList）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. 模型选择器（复用对话框 ModelSelectorMenu 弹窗与样式）
            SubagentModelMenu(
                selectedModelId = item.modelId,
                selectedModelName = currentSelectedModel?.name ?: item.modelName,
                models = availableModels,
                providers = providers,
                compact = isCompact,
                colors = colors,
                onSelect = { selectedModel ->
                    if (selectedModel == null) {
                        // 选择了继承父会话模型
                        onUpdate(null, null)
                    } else {
                        // 选择具体模型后，检查原有 reasoningLevel 在新模型中是否合法
                        val newLevels = selectedModel.reasoningLevels
                        val validReasoning = if (selectedModel.supportsThinking && item.reasoningLevel != null && newLevels.contains(item.reasoningLevel)) {
                            item.reasoningLevel
                        } else null
                        onUpdate(selectedModel.id, validReasoning)
                    }
                }
            )

            // 2. 推理选择器（关联生效：仅当当前生效模型支持推理时展示，复用对话框 ThinkingLevelMenu 逻辑与样式）
            if (supportsReasoning) {
                SubagentReasoningMenu(
                    currentReasoningLevel = item.reasoningLevel,
                    levels = reasoningLevels,
                    colors = colors,
                    onSelect = { lvl ->
                        onUpdate(item.modelId, lvl)
                    }
                )
            }
        }
    }
}

/**
 * 子代理模型选择器菜单：完全复用对话框 [ModelPickerList] 结构与 [DropdownMenu] 样式。
 */
@Composable
private fun SubagentModelMenu(
    selectedModelId: String?,
    selectedModelName: String?,
    models: List<ModelOption>,
    providers: List<ProviderConfig>,
    compact: Boolean,
    colors: MederiColors,
    onSelect: (ModelOption?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    val pillLabel = if (selectedModelId == null) {
        stringResource(Res.string.settings_agents_inherit)
    } else {
        selectedModelName ?: selectedModelId
    }

    Box {
        ChipSelectorPill(
            label = pillLabel,
            onClick = { expanded = true }
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = colors.surfaceCard,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .widthIn(min = if (compact) 400.dp else 500.dp, max = if (compact) 480.dp else 560.dp)
                .heightIn(max = 480.dp)
                .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(10.dp))
        ) {
            // 首项：继承父会话模型
            val isInheritSelected = selectedModelId == null
            DropdownMenuItem(
                modifier = Modifier
                    .padding(horizontal = 4.dp, vertical = 1.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isInheritSelected) colors.accentPrimary.copy(alpha = 0.12f) else Color.Transparent),
                text = {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                FeatherIcons.CornerDownRight,
                                null,
                                tint = if (isInheritSelected) colors.accentPrimary else colors.textMuted,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = stringResource(Res.string.settings_agents_inherit),
                                fontSize = if (compact) 12.sp else 12.5.sp,
                                fontWeight = if (isInheritSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isInheritSelected) colors.accentPrimary else colors.textPrimary
                            )
                        }
                        if (isInheritSelected) {
                            Icon(FeatherIcons.Check, null, tint = colors.accentPrimary, modifier = Modifier.size(14.dp))
                        }
                    }
                },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                onClick = {
                    onSelect(null)
                    expanded = false
                }
            )

            HorizontalDivider(
                color = colors.divider.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )

            // 图 2 的供应商分组模型列表（完全复用 ModelPickerList）
            ModelPickerList(
                models = models,
                providers = providers,
                selectedModelId = selectedModelId,
                nameFontSize = if (compact) 12.sp else 12.5.sp,
                unselectedContextColor = colors.textSecondary,
                checkSize = 14.dp,
                onSelect = { model ->
                    onSelect(model)
                    expanded = false
                },
                colors = colors,
                itemWrapper = { _, isSelected, onClick, content ->
                    DropdownMenuItem(
                        modifier = Modifier
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isSelected) colors.accentPrimary.copy(alpha = 0.12f) else Color.Transparent),
                        text = { content() },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        onClick = onClick,
                    )
                },
                groupWrapper = { groupIndex, content ->
                    if (groupIndex > 0) {
                        HorizontalDivider(
                            color = colors.divider.copy(alpha = 0.6f),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    content()
                }
            )
        }
    }
}

/**
 * 子代理推理等级菜单：完全复用对话框 [ThinkingLevelMenu] 的选项推导格式化与样式。
 */
@Composable
private fun SubagentReasoningMenu(
    currentReasoningLevel: String?,
    levels: List<String>,
    colors: MederiColors,
    onSelect: (String?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    val pillLabel = if (currentReasoningLevel == null) {
        stringResource(Res.string.input_thinking_label, stringResource(Res.string.settings_agents_inherit_reasoning_short))
    } else {
        stringResource(Res.string.input_thinking_label, formatReasoningLevelLabel(currentReasoningLevel))
    }

    Box {
        ChipSelectorPill(
            icon = FeatherIcons.Sliders,
            label = pillLabel,
            onClick = { expanded = true }
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = colors.surfaceCard,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .widthIn(min = 160.dp)
                .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(8.dp))
        ) {
            // 首项：继承父会话推理级别
            val isInheritSelected = currentReasoningLevel == null
            DropdownMenuItem(
                text = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(Res.string.settings_agents_inherit_reasoning),
                            fontSize = 12.sp,
                            fontWeight = if (isInheritSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isInheritSelected) colors.accentPrimary else colors.textPrimary
                        )
                        if (isInheritSelected) {
                            Icon(
                                FeatherIcons.Check,
                                null,
                                tint = colors.accentPrimary,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                onClick = {
                    onSelect(null)
                    expanded = false
                }
            )

            if (levels.isNotEmpty()) {
                HorizontalDivider(
                    color = colors.divider.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            // 各级别项（复用对话框格式化与高亮逻辑）
            levels.forEach { lvl ->
                val isSelected = currentReasoningLevel.equals(lvl, ignoreCase = true)
                DropdownMenuItem(
                    text = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = formatReasoningLevelLabel(lvl),
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) colors.accentPrimary else if (lvl == "NONE") colors.textSecondary else colors.textPrimary
                            )
                            if (isSelected) {
                                Icon(
                                    FeatherIcons.Check,
                                    null,
                                    tint = colors.accentPrimary,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    onClick = {
                        onSelect(lvl)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable private fun SystemPanel(isCompact: Boolean = false) {
    val c = LocalMederiColors.current
    val scroll = rememberScrollState()
    val columns = if (isCompact) 1 else 2
    Column(Modifier.fillMaxSize().verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(Res.string.settings_system_title), color = c.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        listOf(
            Triple(stringResource(Res.string.settings_system_version), AppInfo.VERSION, FeatherIcons.Info),
            Triple(stringResource(Res.string.settings_system_config_dir), "~/.mederi/", FeatherIcons.Folder),
            Triple(stringResource(Res.string.settings_system_prefs_file), "~/.mederi/preferences.json", FeatherIcons.FileText)
        ).chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (l, v, i) -> Box(Modifier.weight(1f)) { InfoCard(l, v, i, c) } }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}


@Composable private fun InfoCard(label: String, value: String, icon: ImageVector, c: xyz.mederi.theme.MederiColors) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.surfaceSidebar).border(1.dp, c.divider, RoundedCornerShape(10.dp)).padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(c.surfaceCard), contentAlignment = Alignment.Center) { Icon(icon, null, tint = c.accentPrimary, modifier = Modifier.size(15.dp)) }
            Column { Text(label, color = c.textMuted, fontSize = 11.sp); Text(value, color = c.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

