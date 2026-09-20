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
import xyz.mederi.core.ui.appstate.LocalAppState
import xyz.mederi.core.ui.appstate.RemoteServerUiState
import xyz.mederi.core.ui.appstate.TunnelUiState
import xyz.mederi.theme.AppLanguage
import xyz.mederi.theme.AppThemeMode
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiColors
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.close
import mederi.app.shared.generated.resources.language_system
import mederi.app.shared.generated.resources.settings_add
import mederi.app.shared.generated.resources.settings_agents_desc
import mederi.app.shared.generated.resources.settings_agents_title
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
                        SettingsTab.AGENTS -> AgentsPanel()
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

    val cardBg = if (c.isDark) Color(0xFF161B22) else Color(0xFFFFFFFF)
    val cardBorder = if (c.isDark) Color(0xFF21262D) else Color(0xFFE5E7EB)

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
private fun SandboxWhitelistCard(appState: xyz.mederi.core.ui.appstate.AppState, c: MederiColors) {
    val paths by appState.sandboxExtraPaths.collectAsState()
    var input by remember { mutableStateOf("") }

    val cardBg = if (c.isDark) Color(0xFF161B22) else Color(0xFFFFFFFF)
    val cardBorder = if (c.isDark) Color(0xFF21262D) else Color(0xFFE5E7EB)

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

    val cardBg = if (c.isDark) Color(0xFF161B22) else Color(0xFFFFFFFF)
    val cardBorder = if (c.isDark) Color(0xFF21262D) else Color(0xFFE5E7EB)

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
                Text(ss.reason, color = c.accentDanger, fontSize = 12.sp)
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
                            Text(stringResource(Res.string.settings_tunnel_failed, ts.reason), color = c.accentDanger, fontSize = 12.sp)
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
    val cardBg = if (isDark) Color(0xFF161B22) else Color(0xFFFFFFFF)
    val cardBorder = if (isSelected) c.accentPrimary else if (isDark) Color(0xFF21262D) else Color(0xFFE5E7EB)
    val previewBg = if (isDark) Color(0xFF0D1117) else Color(0xFFFAFAFA)
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
                .border(1.dp, if (isDark) Color(0xFF21262D) else Color(0xFFE5E7EB), RoundedCornerShape(8.dp))
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
private fun AgentsPanel() {
    val c = LocalMederiColors.current
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize().verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(Res.string.settings_agents_title), color = c.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(Res.string.settings_agents_desc), color = c.textMuted, fontSize = 12.sp)
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

