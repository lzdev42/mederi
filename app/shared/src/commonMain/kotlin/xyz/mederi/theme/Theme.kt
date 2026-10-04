package xyz.mederi.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.emuci.inkcompose.LocalMarkdownConfig
import xyz.emuci.inkcompose.MarkdownColors
import xyz.emuci.inkcompose.MarkdownConfig
import xyz.emuci.inkcompose.RenderStyle
import xyz.emuci.markdown.renderer.MarkdownTheme
import xyz.emuci.syntax.theme.GithubLightTheme
import xyz.emuci.syntax.theme.LocalCodeTheme
import xyz.emuci.syntax.theme.OneDarkProTheme

/**
 * 应用主题模式枚举。
 * 遵循极简与系统化规范，全局仅支持高品质的深色模式与浅色模式。
 * 显示文案唯一映射点 = SettingsScreen 的主题卡（枚举不持有表现层文案，i18n 约定）。
 */
enum class AppThemeMode(val isDark: Boolean) {
    DARK(isDark = true),
    LIGHT(isDark = false);

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
    // 状态语义 token：会话状态指示灯与流转标识专用（工作 / 等待用户 / 正常结束空闲 / 报错）
    val statusWorking: Color,
    val statusWaiting: Color,
    val statusIdle: Color,
    val statusError: Color,
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
    // 语义别名 token（01-tokens §1.2）：accent 状态族（hover/bg/text/border/focus）+ 强边框 + 反色灰控件
    val accentHover: Color,
    val accentBg: Color,
    val accentText: Color,
    val accentBorder: Color,
    val accentFocus: Color,
    val borderStrong: Color,
    val successBg: Color,
    val successText: Color,
    val warningBg: Color,
    val warningText: Color,
    val dangerBg: Color,
    val dangerText: Color,
    val bgInverted: Color,
    val onInverted: Color,
    val bgInvertedHover: Color,
    val isDark: Boolean
)

/**
 * 深色模式配色 (Dark Theme Palette)
 *
 * 取值真理源 = docs/design/standard/01-tokens.md §1.1/§1.2（Radix 色阶），逐字照抄不自创：
 * 1. 背景三档：bg = gray-1 #111113，surface-1 = gray-2 #18181a，surface-2 = gray-3 #222225。
 * 2. 文字三档：text-primary = gray-12 #ededef，text-secondary = gray-11 #a09fa6，
 *    text-muted = #74737d（硬编码）。
 * 3. 品牌强调：accent = iris-9 #5b5bd6，on-accent = gray-1 #111113；hover = iris-10 #534bc6，
 *    软底 = iris-3，文字 = iris-11，边框 = iris-6，focus = iris-7。
 * 4. 状态色：success = green-9 #30a46c，warning = amber-9 #ffb224，danger = red-9 #e5484d。
 * 5. 保留角色（待裁定）：accentSecondary（Okabe-Ito sky）、status*（Okabe-Ito）、thought*。
 */
val DarkColors = MederiColors(
    surfaceSidebar = Color(0xFF111113),        // bg-sidebar = gray-1
    surfaceWorkspace = Color(0xFF111113),      // bg-workspace = gray-1
    surfaceCard = Color(0xFF18181A),           // bg-card = gray-2
    surfaceCardBorder = Color(0xFF3A3A40),     // border = gray-6
    surfaceHover = Color(0xFF222225),          // bg-hover = gray-3
    surfaceInput = Color(0xFF18181A),          // bg-input = gray-2
    surfaceOverlay = Color(0x99000000),        // 遮罩 scrim（半透明黑，保留角色）
    surfaceCode = Color(0xFF131316),           // bg-code（硬编码，待 token 化）
    onSurfaceCode = Color(0xFFA09FA6),         // text-secondary = gray-11
    textPrimary = Color(0xFFEDEDEF),           // text-primary = gray-12
    textSecondary = Color(0xFFA09FA6),         // text-secondary = gray-11
    textMuted = Color(0xFF74737D),             // text-muted（硬编码）
    accentPrimary = Color(0xFF5B5BD6),         // accent = iris-9
    onAccentPrimary = Color(0xFF111113),       // on-accent = gray-1
    accentSecondary = Color(0xFF56B4E9),       // Okabe-Ito sky（保留角色，待裁定）
    accentDanger = Color(0xFFE5484D),          // danger = red-9
    accentWarning = Color(0xFFFFB224),         // warning = amber-9
    accentSuccess = Color(0xFF30A46C),         // success = green-9
    statusWorking = Color(0xFFF59E0B),         // 状态-工作中（保留，待裁定归并 warning）
    statusWaiting = Color(0xFF10B981),         // 状态-等待用户（保留，待裁定归并 success）
    statusIdle = Color(0xFF38BDF8),            // 状态-正常结束（保留，待裁定归并 text-muted）
    statusError = Color(0xFFEF4444),           // 状态-报错（保留，待裁定归并 danger）
    buttonSecondary = Color(0xFF18181A),       // 按钮次级底 = gray-2
    onButtonSecondary = Color(0xFFEDEDEF),     // 次级按钮文字 = gray-12
    iconMuted = Color(0xFF74737D),             // = text-muted
    divider = Color(0xFF3A3A40),               // border = gray-6
    thoughtAccent = Color(0xFF9D86E9),         // thought 角色（保留，待裁定）
    thoughtBackground = Color(0xFF1E212B),     // thought 角色（保留，待裁定）
    thoughtBorder = Color(0xFF2E3240),         // thought 角色（保留，待裁定）
    thoughtText = Color(0xFFE2E8F0),           // thought 角色（保留，待裁定）
    userBubbleBackground = Color(0xFF222225),  // = bg-hover gray-3（裁定：原型实底）
    userBubbleBorder = Color(0xFF3A3A40),      // = border gray-6（裁定：原型实线）
    accentHover = Color(0xFF534BC6),           // accent-hover = iris-10
    accentBg = Color(0xFF201E39),              // accent-bg = iris-3
    accentText = Color(0xFFA39CF4),            // accent-text = iris-11
    accentBorder = Color(0xFF3C3673),          // accent-border = iris-6
    accentFocus = Color(0xFF4E459C),           // accent-focus = iris-7
    borderStrong = Color(0xFF484851),          // border-strong = gray-7
    successBg = Color(0xFF13271F),             // success-bg = green-3
    successText = Color(0xFF55CF8D),           // success-text = green-11
    warningBg = Color(0xFF2A1F0A),             // warning-bg = amber-3
    warningText = Color(0xFFFFCA16),           // warning-text = amber-11
    dangerBg = Color(0xFF291415),              // danger-bg = red-3
    dangerText = Color(0xFFFF8589),            // danger-text = red-11
    bgInverted = Color(0xFFEDEDEF),            // bg-inverted = gray-12
    onInverted = Color(0xFF111113),            // on-inverted = gray-1
    bgInvertedHover = Color(0xFFFFFFFF),       // bg-inverted-hover = 纯白（比 gray-12 更亮一档）
    isDark = true
)

/**
 * 浅色模式配色 (Light Theme Palette)
 *
 * 取值真理源 = docs/design/standard/01-tokens.md §1.1/§1.2（Radix 色阶），逐字照抄不自创：
 * 1. 背景三档：bg = gray-1 #fcfcfd（workspace/card/input 硬编码为 #ffffff），
 *    surface-1/surface-2 = gray-2 #f9f9fb。
 * 2. 文字三档：text-primary = gray-12 #18181b，text-secondary = gray-11 #646470，
 *    text-muted = #8c8b94（硬编码）。
 * 3. 品牌强调：accent = iris-9 #5b5bd6（两主题同值），on-accent = #ffffff；hover = iris-10 #514ec7，
 *    软底 = iris-3，文字 = iris-11 #534bc6（light 语义反转），边框 = iris-6，focus = iris-7。
 * 4. 状态色：success = green-9 #2d9d64，warning = amber-9 #e59300，danger = red-9 #e5484d。
 * 5. 保留角色（待裁定）：accentSecondary（Okabe-Ito sky）、status*（Okabe-Ito）、thought*。
 */
val LightColors = MederiColors(
    surfaceSidebar = Color(0xFFF9F9FB),        // bg-sidebar = gray-2
    surfaceWorkspace = Color(0xFFFFFFFF),      // bg-workspace（硬编码 #ffffff）
    surfaceCard = Color(0xFFFFFFFF),           // bg-card（硬编码 #ffffff）
    surfaceCardBorder = Color(0xFFD9D9DF),     // border = gray-6
    surfaceHover = Color(0xFFF0F0F3),          // bg-hover = gray-3
    surfaceInput = Color(0xFFFFFFFF),          // bg-input（硬编码 #ffffff）
    surfaceOverlay = Color(0x40000000),        // 遮罩 scrim（半透明黑，保留角色）
    surfaceCode = Color(0xFFF3F3F6),           // bg-code（硬编码，待 token 化）
    onSurfaceCode = Color(0xFF646470),         // text-secondary = gray-11
    textPrimary = Color(0xFF18181B),           // text-primary = gray-12
    textSecondary = Color(0xFF646470),         // text-secondary = gray-11
    textMuted = Color(0xFF8C8B94),             // text-muted（硬编码）
    accentPrimary = Color(0xFF5B5BD6),         // accent = iris-9（两主题同值）
    onAccentPrimary = Color(0xFFFFFFFF),       // on-accent（light = #ffffff）
    accentSecondary = Color(0xFF0969DA),       // Okabe-Ito sky（保留角色，待裁定）
    accentDanger = Color(0xFFE5484D),          // danger = red-9（两主题同值）
    accentWarning = Color(0xFFE59300),         // warning = amber-9
    accentSuccess = Color(0xFF2D9D64),         // success = green-9
    statusWorking = Color(0xFFF59E0B),         // 状态-工作中（保留，待裁定归并 warning）
    statusWaiting = Color(0xFF10B981),         // 状态-等待用户（保留，待裁定归并 success）
    statusIdle = Color(0xFF38BDF8),            // 状态-正常结束（保留，待裁定归并 text-muted）
    statusError = Color(0xFFEF4444),           // 状态-报错（保留，待裁定归并 danger）
    buttonSecondary = Color(0xFFFFFFFF),       // 按钮次级底（硬编码 #ffffff）
    onButtonSecondary = Color(0xFF18181B),     // 次级按钮文字 = gray-12
    iconMuted = Color(0xFF8C8B94),             // = text-muted
    divider = Color(0xFFD9D9DF),               // border = gray-6
    thoughtAccent = Color(0xFF7C3AED),         // thought 角色（保留，待裁定）
    thoughtBackground = Color(0xFFF1F3F5),     // thought 角色（保留，待裁定）
    thoughtBorder = Color(0xFFE2E5E9),         // thought 角色（保留，待裁定）
    thoughtText = Color(0xFF1F2328),           // thought 角色（保留，待裁定）
    userBubbleBackground = Color(0xFFF0F0F3),  // = bg-hover gray-3（裁定：原型实底）
    userBubbleBorder = Color(0xFFD9D9DF),      // = border gray-6（裁定：原型实线）
    accentHover = Color(0xFF514EC7),           // accent-hover = iris-10
    accentBg = Color(0xFFF0F0FF),              // accent-bg = iris-3
    accentText = Color(0xFF534BC6),            // accent-text = iris-11（light 反转为深紫）
    accentBorder = Color(0xFFCBCAFE),          // accent-border = iris-6
    accentFocus = Color(0xFFB8B6FC),           // accent-focus = iris-7
    borderStrong = Color(0xFFCECED6),          // border-strong = gray-7
    successBg = Color(0xFFE6F6EB),             // success-bg = green-3
    successText = Color(0xFF218358),           // success-text = green-11
    warningBg = Color(0xFFFEF3D6),             // warning-bg = amber-3
    warningText = Color(0xFF9B5A00),           // warning-text = amber-11
    dangerBg = Color(0xFFFEECEE),              // danger-bg = red-3
    dangerText = Color(0xFFCE2C31),            // danger-text = red-11
    bgInverted = Color(0xFF18181B),            // bg-inverted = gray-12
    onInverted = Color(0xFFFCFCFD),            // on-inverted = gray-1
    bgInvertedHover = Color(0xFF18181B),       // bg-inverted-hover = gray-12
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
        LocalMarkdownConfig provides MederiMarkdownConfig,
    ) {
        MaterialTheme(
            colorScheme = materialColors,
            content = content
        )
    }
}

/**
 * Mederi 应用级 Markdown 配置唯一真理源：
 * 默认关闭 `==高亮==` 解析，防止代码与正文中裸写的比较运算符（如 `state==Running`）被误判为高亮标记。
 */
val MederiMarkdownConfig = MarkdownConfig(enableHighlight = false)

/**
 * 获取适配当前 Mederi 主题配色的 [MarkdownColors]。
 */
@Composable
fun rememberMederiMarkdownColors(): MarkdownColors {
    val colors = LocalMederiColors.current
    return remember(colors) {
        MarkdownColors(
            textPrimary = colors.textPrimary,
            textSecondary = colors.textSecondary,
            textMuted = colors.textMuted,
            link = colors.accentPrimary,
            inlineCodeBackground = if (colors.isDark) Color(0xFF222225) else Color(0xFFF0F0F3), // gray-3
            codeBlockBackground = if (colors.isDark) Color(0xFF131316) else Color(0xFFF3F3F6), // bg-code
            blockQuoteBorder = colors.divider,
            divider = colors.divider,
            tableBorder = colors.divider,
            tableHeaderBackground = if (colors.isDark) Color(0xFF18181A) else Color(0xFFF9F9FB), // gray-2
            highlightColor = if (colors.isDark) Color(0x3DF2CC60) else Color(0xFFFFF3B0),
            isDark = colors.isDark,
        )
    }
}

/**
 * Mederi 统一 Markdown 主题适配：
 * 将业务色彩规范（[rememberMederiMarkdownColors]）与几何排版（[RenderStyle]）组装为 [MarkdownTheme]。
 *
 * @param style 排版风格（默认为面向对话的 [RenderStyle.Chat]，长文阅读可传入 [RenderStyle.Github]）
 * @param compact 紧凑变体，适用于思维链、追踪折叠卡片等空间受限区域。
 */
@Composable
fun rememberMederiMarkdownTheme(
    style: RenderStyle = RenderStyle.Chat,
    compact: Boolean = false,
): MarkdownTheme {
    val colors = rememberMederiMarkdownColors()
    return remember(colors, style, compact) {
        val effectiveStyle = if (compact) {
            style.copy(
                blockSpacing = 5.dp,
                bodyStyle = style.bodyStyle.copy(
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                ),
                headingStyles = listOf(
                    TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, lineHeight = 20.sp),
                    TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, lineHeight = 19.sp),
                    TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp),
                    TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 17.sp),
                    TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp),
                    TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.Medium, lineHeight = 15.sp),
                ),
                codeBlockStyle = style.codeBlockStyle.copy(
                    fontSize = 12.sp,
                    lineHeight = 16.5.sp,
                ),
                inlineCodeStyle = style.inlineCodeStyle.copy(
                    fontSize = 11.5.sp,
                ),
                tableCellPadding = 4.dp,
                codeBlockPadding = 6.dp,
            )
        } else {
            style
        }
        MarkdownTheme.from(style = effectiveStyle, colors = colors)
    }
}

