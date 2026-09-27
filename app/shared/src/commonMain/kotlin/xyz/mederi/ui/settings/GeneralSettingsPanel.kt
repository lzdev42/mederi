package xyz.mederi.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.Globe
import compose.icons.feathericons.Moon
import compose.icons.feathericons.Sun
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.theme.AppLanguage
import xyz.mederi.theme.AppThemeMode
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiColors
import xyz.mederi.ui.appstate.LocalAppState

/**
 * 外观主题与界面语言设置面板。
 */
@Composable
fun GeneralSettingsPanel(isCompact: Boolean = false) {
    val appState = LocalAppState.current
    val colors = LocalMederiColors.current
    val currentTheme by appState.theme.collectAsState()
    val currentLanguage by appState.language.collectAsState()
    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // 主题选择
        SettingsSection(
            title = stringResource(Res.string.settings_appearance_title),
            description = stringResource(Res.string.settings_appearance_desc),
            colors = colors
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    ThemeCard(
                        theme = AppThemeMode.DARK,
                        isSelected = currentTheme == AppThemeMode.DARK,
                        colors = colors,
                        modifier = Modifier.weight(1f),
                        onSelect = { appState.setTheme(AppThemeMode.DARK) }
                    )
                    ThemeCard(
                        theme = AppThemeMode.LIGHT,
                        isSelected = currentTheme == AppThemeMode.LIGHT,
                        colors = colors,
                        modifier = Modifier.weight(1f),
                        onSelect = { appState.setTheme(AppThemeMode.LIGHT) }
                    )
                }

            }
        }

        // 语言选择
        SettingsSection(
            title = stringResource(Res.string.settings_language_title),
            description = stringResource(Res.string.settings_language_desc),
            colors = colors
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AppLanguage.entries.forEach { language ->
                    LanguageCard(
                        language = language,
                        isSelected = currentLanguage == language,
                        colors = colors,
                        modifier = Modifier.weight(1f),
                        onSelect = { appState.setLanguage(language) }
                    )
                }
            }
        }
    }
}

/**
 * 主题卡片（内含比例协调的高保真微型窗口预览）。
 */
@Composable
private fun ThemeCard(
    theme: AppThemeMode,
    isSelected: Boolean,
    colors: MederiColors,
    modifier: Modifier = Modifier,
    onSelect: () -> Unit
) {
    val isDark = theme.isDark
    val cardBorder = if (isSelected) colors.accentPrimary else colors.surfaceCardBorder
    val previewBg = if (isDark) Color(0xFF0D1117) else Color(0xFFFAFAFA)
    val previewSidebarBg = if (isDark) Color(0xFF161B22) else Color(0xFFF0F2F5)
    val previewCardBg = if (isDark) Color(0xFF21262D) else Color(0xFFFFFFFF)
    val previewCardBorder = if (isDark) Color(0xFF30363D) else Color(0xFFE1E4E8)
    val previewText = if (isDark) Color(0xFFE6EDF3) else Color(0xFF1F2328)
    val previewMuted = if (isDark) Color(0xFF8B949E) else Color(0xFF8C959F)
    val accentColor = if (isDark) Color(0xFF5E6AD2) else Color(0xFF4C5CD6)

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceCard)
            .border(if (isSelected) 2.dp else 1.dp, cardBorder, RoundedCornerShape(12.dp))
            .clickable { onSelect() }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 微型高保真窗口模拟
        val boxModifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(previewBg)
            .border(1.dp, previewCardBorder, RoundedCornerShape(8.dp))

        Box(modifier = boxModifier) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 窗口标题栏（macOS 风格三点 + 顶栏）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(previewSidebarBg)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(Modifier.size(5.dp).clip(CircleShape).background(Color(0xFFFF5F56).copy(alpha = 0.8f)))
                    Box(Modifier.size(5.dp).clip(CircleShape).background(Color(0xFFFFBD2E).copy(alpha = 0.8f)))
                    Box(Modifier.size(5.dp).clip(CircleShape).background(Color(0xFF27C93F).copy(alpha = 0.8f)))
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.width(48.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(previewMuted.copy(alpha = 0.4f)))
                }

                // 窗口下半部：微型左侧栏 + 右侧工作区
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    // 微型左侧栏
                    Column(
                        modifier = Modifier
                            .width(64.dp)
                            .fillMaxHeight()
                            .background(previewSidebarBg.copy(alpha = 0.6f))
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(2.dp)).background(accentColor.copy(alpha = 0.35f)))
                        Box(Modifier.fillMaxWidth(0.75f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(previewMuted.copy(alpha = 0.3f)))
                        Box(Modifier.fillMaxWidth(0.85f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(previewMuted.copy(alpha = 0.2f)))
                    }

                    // 微型右侧主工作区
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // 模拟卡片 1
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                                .background(previewCardBg)
                                .border(0.5.dp, previewCardBorder, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 5.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.width(70.dp).height(5.dp).clip(RoundedCornerShape(2.dp)).background(previewText))
                            Box(Modifier.size(6.dp).clip(CircleShape).background(accentColor))
                        }
                        // 模拟文字行
                        Box(Modifier.fillMaxWidth(0.6f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(previewMuted.copy(alpha = 0.4f)))
                    }
                }
            }
        }

        // 底部标签与单选指示
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
                    tint = if (isSelected) colors.accentPrimary else colors.textSecondary,
                    modifier = Modifier.size(15.dp)
                )
                val labelRes = when (theme) {
                    AppThemeMode.DARK -> Res.string.theme_dark
                    AppThemeMode.LIGHT -> Res.string.theme_light
                }
                Text(
                    text = stringResource(labelRes),
                    color = if (isSelected) colors.textPrimary else colors.textSecondary,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                )
            }

            if (isSelected) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(colors.accentPrimary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = FeatherIcons.Check,
                        contentDescription = null,
                        tint = colors.onAccentPrimary,
                        modifier = Modifier.size(11.dp)
                    )
                }
            }
        }
    }
}

/**
 * 语言选择卡片（附带清晰指示状态）。
 */
@Composable
private fun LanguageCard(
    language: AppLanguage,
    isSelected: Boolean,
    colors: MederiColors,
    modifier: Modifier = Modifier,
    onSelect: () -> Unit
) {
    val cardBorder = if (isSelected) colors.accentPrimary else colors.surfaceCardBorder
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surfaceCard)
            .border(if (isSelected) 1.5.dp else 1.dp, cardBorder, RoundedCornerShape(10.dp))
            .clickable { onSelect() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = FeatherIcons.Globe,
                contentDescription = null,
                tint = if (isSelected) colors.accentPrimary else colors.textMuted,
                modifier = Modifier.size(15.dp)
            )
            Text(
                text = if (language == AppLanguage.SYSTEM) stringResource(Res.string.language_system) else language.nativeName,
                color = if (isSelected) colors.textPrimary else colors.textSecondary,
                fontSize = 13.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
            )
        }
        if (isSelected) {
            Icon(
                imageVector = FeatherIcons.Check,
                contentDescription = null,
                tint = colors.accentPrimary,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}
