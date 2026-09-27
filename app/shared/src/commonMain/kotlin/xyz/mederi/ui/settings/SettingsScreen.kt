package xyz.mederi.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.AppInfo
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiColors

/** 设置页标签枚举。 */
private enum class SettingsTab(val icon: ImageVector) {
    PROVIDERS(FeatherIcons.Cpu),
    AGENTS(FeatherIcons.Users),
    GENERAL(FeatherIcons.Droplet),
    SANDBOX(FeatherIcons.Shield),
    REMOTE(FeatherIcons.Sliders),
    SYSTEM(FeatherIcons.Activity)
}

/** 设置页标签文案映射。 */
@Composable
private fun settingsTabLabel(tab: SettingsTab): String = when (tab) {
    SettingsTab.PROVIDERS -> stringResource(Res.string.settings_tab_providers)
    SettingsTab.AGENTS -> stringResource(Res.string.settings_tab_agents)
    SettingsTab.GENERAL -> stringResource(Res.string.settings_tab_general)
    SettingsTab.SANDBOX -> stringResource(Res.string.settings_tab_sandbox)
    SettingsTab.REMOTE -> stringResource(Res.string.settings_tab_remote)
    SettingsTab.SYSTEM -> stringResource(Res.string.settings_tab_system)
}

/** 设置页标签副标题说明。 */
@Composable
private fun settingsTabSubtitle(tab: SettingsTab): String = when (tab) {
    SettingsTab.PROVIDERS -> stringResource(Res.string.settings_subtitle)
    SettingsTab.AGENTS -> stringResource(Res.string.settings_agents_desc)
    SettingsTab.GENERAL -> stringResource(Res.string.settings_appearance_desc)
    SettingsTab.SANDBOX -> stringResource(Res.string.settings_sandbox_desc)
    SettingsTab.REMOTE -> stringResource(Res.string.settings_remote_desc)
    SettingsTab.SYSTEM -> stringResource(Res.string.settings_system_title)
}

@Composable
fun SettingsDialog(
    isVisible: Boolean,
    onClose: () -> Unit
) {
    if (!isVisible) return
    val colors = LocalMederiColors.current
    var selectedTab by remember { mutableStateOf(SettingsTab.AGENTS) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surfaceOverlay)
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val isCompact = maxWidth < 760.dp
            val dialogWidth = if (isCompact) maxWidth else minOf(1140.dp, maxWidth - 48.dp)
            val dialogHeight = if (isCompact) maxHeight else minOf(780.dp, maxHeight - 48.dp)
            val dialogCorner = if (isCompact) 0.dp else 16.dp

            // 整屏设置对话框壳：圆角随 isCompact 动态 0/16dp、无内边距，MederiCard（固定 Card 8dp + 内边距）不适用，
            // 用等价 Box 修饰链替代 M3 Surface，视觉与点击吞没（clickable(enabled=false)）完全保留
            Box(
                modifier = Modifier
                    .width(dialogWidth)
                    .height(dialogHeight)
                    .clip(RoundedCornerShape(dialogCorner))
                    .background(colors.surfaceCard)
                    .border(if (isCompact) 0.dp else 1.dp, colors.surfaceCardBorder, RoundedCornerShape(dialogCorner))
                    .clickable(enabled = false) {}
            ) {
                if (isCompact) {
                    // 移动端/紧凑屏：顶部紧凑 Header + 横向选项卡 + 单列
                    CompactSettingsLayout(
                        selectedTab = selectedTab,
                        onSelectTab = { selectedTab = it },
                        onClose = onClose,
                        colors = colors
                    )
                } else {
                    // 桌面端标准：左侧精致垂直侧栏导航 + 右侧宽适工作台
                    DesktopSettingsLayout(
                        selectedTab = selectedTab,
                        onSelectTab = { selectedTab = it },
                        onClose = onClose,
                        colors = colors
                    )
                }
            }
        }
    }
}

/**
 * 桌面端顶部浏览器式选项卡布局（Header + Tab Strip + Content Workspace）。
 */
@Composable
private fun DesktopSettingsLayout(
    selectedTab: SettingsTab,
    onSelectTab: (SettingsTab) -> Unit,
    onClose: () -> Unit,
    colors: MederiColors
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // ─── 顶部标题栏：品牌 + 版本 + 关闭按钮 ───
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surfaceSidebar)
                .padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.accentPrimary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = FeatherIcons.Sliders,
                        contentDescription = null,
                        tint = colors.accentPrimary,
                        modifier = Modifier.size(15.dp)
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        text = stringResource(Res.string.settings_title),
                        color = colors.textPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "v${AppInfo.VERSION}",
                        color = colors.textMuted,
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // 关闭按钮
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.surfaceCard)
                    .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(8.dp))
                    .clickable { onClose() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = FeatherIcons.X,
                    contentDescription = stringResource(Res.string.close),
                    tint = colors.textSecondary,
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        // ─── 浏览器式横向选项卡条 ───
        BrowserTabStrip(
            selectedTab = selectedTab,
            onSelectTab = onSelectTab,
            colors = colors
        )

        // ─── 内容工作区 ───
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(colors.surfaceWorkspace)
        ) {
            // 选中标签的标题栏与说明
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 14.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = settingsTabLabel(selectedTab),
                        color = colors.textPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = settingsTabSubtitle(selectedTab),
                        color = colors.textMuted,
                        fontSize = 12.sp
                    )
                }
            }

            HorizontalDivider(color = colors.divider.copy(alpha = 0.6f))

            // 主内容容器
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (selectedTab == SettingsTab.PROVIDERS) {
                    // 供应商管理自身内含完整双/三栏工作区，直接铺满
                    ProviderSettingsPanel()
                } else {
                    // 其他面板：居左限制在舒适阅读宽度（780dp），避免宽屏大面积黑洞
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp, vertical = 20.dp),
                        contentAlignment = Alignment.TopStart
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 780.dp)
                        ) {
                            when (selectedTab) {
                                SettingsTab.AGENTS -> AgentSettingsPanel(isCompact = false)
                                SettingsTab.GENERAL -> GeneralSettingsPanel(isCompact = false)
                                SettingsTab.SANDBOX -> SandboxSettingsPanel()
                                SettingsTab.REMOTE -> RemoteSettingsPanel(isCompact = false)
                                SettingsTab.SYSTEM -> SystemSettingsPanel(isCompact = false)
                                SettingsTab.PROVIDERS -> {}
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 浏览器式横向选项卡条：选中标签底色与内容区一致并盖住底部分割线，
 * 形成“标签与下方内容视觉相连”的浏览器效果；未选中标签透明，分割线在其下贯通。
 */
@Composable
private fun BrowserTabStrip(
    selectedTab: SettingsTab,
    onSelectTab: (SettingsTab) -> Unit,
    colors: MederiColors
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceSidebar)
    ) {
        // 底部分割线先绘制，选中标签的实底会后盖住它
        HorizontalDivider(
            color = colors.divider.copy(alpha = 0.6f),
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SettingsTab.entries.forEach { tab ->
                val isSelected = selectedTab == tab
                val bg by animateColorAsState(
                    if (isSelected) colors.surfaceWorkspace else Color.Transparent
                )

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                        .background(bg)
                        .clickable { onSelectTab(tab) }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = null,
                        tint = if (isSelected) colors.accentPrimary else colors.iconMuted,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = settingsTabLabel(tab),
                        color = if (isSelected) colors.textPrimary else colors.textSecondary,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

/**
 * 移动端/小屏紧凑布局。
 */
@Composable
private fun CompactSettingsLayout(
    selectedTab: SettingsTab,
    onSelectTab: (SettingsTab) -> Unit,
    onClose: () -> Unit,
    colors: MederiColors
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部 Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surfaceSidebar)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.accentPrimary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = FeatherIcons.Sliders,
                        contentDescription = null,
                        tint = colors.accentPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                }
                Text(
                    text = stringResource(Res.string.settings_title),
                    color = colors.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.surfaceCard)
                    .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(6.dp))
                    .clickable { onClose() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = FeatherIcons.X,
                    contentDescription = stringResource(Res.string.close),
                    tint = colors.textSecondary,
                    modifier = Modifier.size(13.dp)
                )
            }
        }

        // 水平标签胶囊栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surfaceSidebar)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SettingsTab.entries.forEach { tab ->
                val isSelected = selectedTab == tab
                val bg by animateColorAsState(
                    if (isSelected) colors.surfaceCard else Color.Transparent
                )
                val border = if (isSelected) colors.surfaceCardBorder else Color.Transparent

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(bg)
                        .border(1.dp, border, RoundedCornerShape(8.dp))
                        .clickable { onSelectTab(tab) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = null,
                        tint = if (isSelected) colors.accentPrimary else colors.iconMuted,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = settingsTabLabel(tab),
                        color = if (isSelected) colors.textPrimary else colors.textSecondary,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }

        HorizontalDivider(color = colors.divider)

        // 紧凑内容展示
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .let { if (selectedTab == SettingsTab.PROVIDERS) it else it.padding(14.dp) }
        ) {
            when (selectedTab) {
                SettingsTab.PROVIDERS -> ProviderSettingsPanel()
                SettingsTab.AGENTS -> AgentSettingsPanel(isCompact = true)
                SettingsTab.GENERAL -> GeneralSettingsPanel(isCompact = true)
                SettingsTab.SANDBOX -> SandboxSettingsPanel()
                SettingsTab.REMOTE -> RemoteSettingsPanel(isCompact = true)
                SettingsTab.SYSTEM -> SystemSettingsPanel(isCompact = true)
            }
        }
    }
}
