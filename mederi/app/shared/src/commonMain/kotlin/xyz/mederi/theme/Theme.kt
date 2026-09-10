package xyz.mederi.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import xyz.emuci.syntax.theme.GithubLightTheme
import xyz.emuci.syntax.theme.LocalCodeTheme
import xyz.emuci.syntax.theme.OneDarkProTheme

/**
 * 应用主题模式枚举。
 * 遵循极简与系统化规范，全局仅支持高品质的深色模式与浅色模式。
 */
enum class AppThemeMode(val displayName: String, val isDark: Boolean) {
    DARK("深色模式", true),
    LIGHT("浅色模式", false);

    companion object {
        fun fromString(value: String?): AppThemeMode {
            return when (value?.uppercase()) {
                "LIGHT", "LINEAR_LIGHT", "CATPPUCCIN_LATTE", "GRUVBOX_LIGHT", "ROSE_PINE_DAWN" -> LIGHT
                else -> DARK
            }
        }
    }
}

@Immutable
data class MederiColors(
    val surfaceSidebar: Color,
    val surfaceWorkspace: Color,
    val surfaceCard: Color,
    val surfaceCardBorder: Color,
    val surfaceHover: Color,
    val surfaceInput: Color,
    val surfaceOverlay: Color,
    val surfaceCode: Color,
    val onSurfaceCode: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accentPrimary: Color,
    val onAccentPrimary: Color,
    val accentSecondary: Color,
    val accentDanger: Color,
    val accentWarning: Color,
    val accentSuccess: Color,
    val buttonSecondary: Color,
    val onButtonSecondary: Color,
    val iconMuted: Color,
    val divider: Color,
    val thoughtAccent: Color,
    val thoughtBackground: Color,
    val thoughtBorder: Color,
    val thoughtText: Color,
    val userBubbleBackground: Color,
    val userBubbleBorder: Color,
    val isDark: Boolean
)

/**
 * 高级感深色模式配色 (Dark Theme Palette)
 *
 * 遵循人体工学规范：
 * 1. 背景采用 #0D1117 ~ #161B22 区间深灰，杜绝纯黑死黑与 OLED 眩光。
 * 2. 正文文字采用 87% 不透明度 (#E6EDF3)，次要信息 60% (#8B949E)，禁用/占位 38% (#6E7681)。
 * 3. 强调色整体降低 10-20% 饱和度，避免暗光过曝。
 * 4. 辅助状态色采用 Okabe-Ito 色盲友好色系。
 */
val DarkColors = MederiColors(
    surfaceSidebar = Color(0xFF090C10),
    surfaceWorkspace = Color(0xFF0D1117),
    surfaceCard = Color(0xFF161B22),
    surfaceCardBorder = Color(0xFF21262D),
    surfaceHover = Color(0xFF1C2128),
    surfaceInput = Color(0xFF0D1117),
    surfaceOverlay = Color(0x99000000),
    surfaceCode = Color(0xFF0D1117),
    onSurfaceCode = Color(0xFFC9D1D9),
    textPrimary = Color(0xFFE6EDF3),     // ~87% opacity white on dark
    textSecondary = Color(0xFF8B949E),   // ~60% opacity
    textMuted = Color(0xFF6E7681),       // ~38% opacity
    accentPrimary = Color(0xFF5E6AD2),   // Restrained Linear indigo
    onAccentPrimary = Color(0xFFF0F6FC),
    accentSecondary = Color(0xFF56B4E9), // Okabe-Ito Sky Blue
    accentDanger = Color(0xFFE5534B),    // Desaturated Soft Red
    accentWarning = Color(0xFFE69F00),   // Okabe-Ito Amber/Orange
    accentSuccess = Color(0xFF009E73),   // Okabe-Ito Bluish Green
    buttonSecondary = Color(0xFF21262D),
    onButtonSecondary = Color(0xFFC9D1D9),
    iconMuted = Color(0xFF7D8590),
    divider = Color(0xFF21262D),
    thoughtAccent = Color(0xFF9D86E9),
    thoughtBackground = Color(0x0AFFFFFF),
    thoughtBorder = Color(0x14FFFFFF),
    thoughtText = Color(0xFF8B949E),
    userBubbleBackground = Color(0x385E6AD2),
    userBubbleBorder = Color(0x805E6AD2),
    isDark = true
)

/**
 * 高级感浅色模式配色 (Light Theme Palette)
 *
 * 遵循人体工学规范：
 * 1. 背景采用 #FAFAFA，消除纯白在屏幕上的反光刺眼感。
 * 2. 正文文字采用 #1F2328，避免纯黑产生生硬边缘对比。
 * 3. 采用 1px 细微描边与极轻视觉分隔。
 */
val LightColors = MederiColors(
    surfaceSidebar = Color(0xFFF3F4F6),
    surfaceWorkspace = Color(0xFFFAFAFA),
    surfaceCard = Color(0xFFFFFFFF),
    surfaceCardBorder = Color(0xFFE5E7EB),
    surfaceHover = Color(0xFFECEEF1),
    surfaceInput = Color(0xFFF3F4F6),
    surfaceOverlay = Color(0x40000000),
    surfaceCode = Color(0xFFF6F8FA),
    onSurfaceCode = Color(0xFF24292F),
    textPrimary = Color(0xFF1F2328),
    textSecondary = Color(0xFF656D76),
    textMuted = Color(0xFF8C959F),
    accentPrimary = Color(0xFF4C5CD6),
    onAccentPrimary = Color(0xFFFFFFFF),
    accentSecondary = Color(0xFF0969DA),
    accentDanger = Color(0xFFCF222E),
    accentWarning = Color(0xFF9A6700),
    accentSuccess = Color(0xFF1A7F37),
    buttonSecondary = Color(0xFFEAECEF),
    onButtonSecondary = Color(0xFF24292F),
    iconMuted = Color(0xFF8C959F),
    divider = Color(0xFFD0D7DE),
    thoughtAccent = Color(0xFF7C3AED),
    thoughtBackground = Color(0x06000000),
    thoughtBorder = Color(0x12000000),
    thoughtText = Color(0xFF656D76),
    userBubbleBackground = Color(0x1F4C5CD6),
    userBubbleBorder = Color(0x594C5CD6),
    isDark = false
)

val LocalMederiColors = staticCompositionLocalOf { DarkColors }
val LocalAppThemeMode = staticCompositionLocalOf { AppThemeMode.DARK }

private val themeColorMap = mapOf(
    AppThemeMode.DARK to DarkColors,
    AppThemeMode.LIGHT to LightColors
)

@Composable
fun AppTheme(
    themeMode: AppThemeMode = AppThemeMode.DARK,
    content: @Composable () -> Unit
) {
    val customColors = themeColorMap[themeMode] ?: DarkColors

    val materialColors = if (customColors.isDark) {
        darkColorScheme(
            background = customColors.surfaceWorkspace,
            surface = customColors.surfaceCard,
            surfaceContainer = customColors.surfaceCard,
            surfaceContainerHigh = customColors.surfaceSidebar,
            surfaceVariant = customColors.surfaceInput,
            onSurfaceVariant = customColors.textSecondary,
            primary = customColors.accentPrimary,
            onPrimary = customColors.onAccentPrimary,
            secondary = customColors.accentSecondary,
            onSecondary = customColors.onAccentPrimary,
            error = customColors.accentDanger,
            onError = customColors.onAccentPrimary,
            onBackground = customColors.textPrimary,
            onSurface = customColors.textPrimary,
            outline = customColors.surfaceCardBorder,
            outlineVariant = customColors.divider
        )
    } else {
        lightColorScheme(
            background = customColors.surfaceWorkspace,
            surface = customColors.surfaceCard,
            surfaceContainer = customColors.surfaceCard,
            surfaceContainerHigh = customColors.surfaceSidebar,
            surfaceVariant = customColors.surfaceInput,
            onSurfaceVariant = customColors.textSecondary,
            primary = customColors.accentPrimary,
            onPrimary = customColors.onAccentPrimary,
            secondary = customColors.accentSecondary,
            onSecondary = customColors.onAccentPrimary,
            error = customColors.accentDanger,
            onError = customColors.onAccentPrimary,
            onBackground = customColors.textPrimary,
            onSurface = customColors.textPrimary,
            outline = customColors.surfaceCardBorder,
            outlineVariant = customColors.divider
        )
    }

    val codeTheme = if (customColors.isDark) OneDarkProTheme else GithubLightTheme

    CompositionLocalProvider(
        LocalMederiColors provides customColors,
        LocalAppThemeMode provides themeMode,
        LocalCodeTheme provides codeTheme,
    ) {
        MaterialTheme(
            colorScheme = materialColors,
            content = content
        )
    }
}
