package xyz.emuci.diagram.theme

import androidx.compose.ui.graphics.Color

/**
 * 图表域颜色 Token。直接复用 Compose [Color]，无需自建色值类型。
 * 只约束 app 侧 UI（fallback 源码框、占位文字、webview 底色）；
 * 图形内部配色完全交给 Mermaid 内置主题，不注入。
 */
data class ThemeColors(
    /** 全局画布/容器底色 (Canvas Background) */
    val canvas: Color,
    /** 主卡片/节点表面背景色 (Surface Fill) */
    val surface: Color,
    /** 次级卡片/分组/Cluster 背景色 (Secondary Surface Fill) */
    val surfaceAlt: Color,
    /** 主要文本颜色 (Primary Text) */
    val textPrimary: Color,
    /** 次要文本/辅助说明颜色 (Secondary Text) */
    val textSecondary: Color,
    /** 默认通用边框/分隔线颜色 (Default Border Line) */
    val border: Color,
    /** 主强调色/品牌蓝 (Primary Accent) */
    val accent: Color,
    /** 次级强调色 (Secondary Accent) */
    val accentSecondary: Color,
    /** 三级强调色 (Tertiary Accent) */
    val accentTertiary: Color,
    /** 成功/绿色状态颜色 */
    val success: Color,
    /** 警告/橙黄色状态颜色 */
    val warning: Color,
    /** 危险/红/错误状态颜色 */
    val danger: Color,
    /** 选中/高亮框颜色 */
    val selection: Color,
    /** 诊断/报错下划线颜色 */
    val diagnostic: Color,
)

/** 图表正文字体。 */
data class DiagramFont(
    /** CSS font-family，如 "sans-serif"、"monospace" */
    val family: String,
    /** 字号（sp） */
    val sizeSp: Float,
)

data class DiagramTypography(
    val bodyFont: DiagramFont,
)

/**
 * 图表渲染主题。
 * 图形配色策略 = Mermaid 内置主题（isDark 决定 'default'/'dark'），不自绘调色板。
 * 通过 [DiagramTheme.Default] / [DiagramTheme.Dark] 使用，或 `copy()` 定制颜色 Token。
 */
data class DiagramTheme(
    val colors: ThemeColors,
    val typography: DiagramTypography,
    val isDark: Boolean = false,
) {
    companion object {
        private val LightColors = ThemeColors(
            canvas = Color(0xFFF8FAFC),
            surface = Color(0xFFFFFFFF),
            surfaceAlt = Color(0xFFF1F5F9),
            textPrimary = Color(0xFF0F172A),
            textSecondary = Color(0xFF64748B),
            border = Color(0xFFCBD5E1),
            accent = Color(0xFF2563EB),
            accentSecondary = Color(0xFF7C3AED),
            accentTertiary = Color(0xFF0284C7),
            success = Color(0xFF16A34A),
            warning = Color(0xFFD97706),
            danger = Color(0xFFDC2626),
            selection = Color(0xFFBFDBFE),
            diagnostic = Color(0xFFDC2626),
        )

        private val DarkColors = ThemeColors(
            canvas = Color(0xFF0B0F19),
            surface = Color(0xFF1E293B),
            surfaceAlt = Color(0xFF141C2E),
            textPrimary = Color(0xFFF8FAFC),
            textSecondary = Color(0xFF94A3B8),
            border = Color(0xFF334155),
            accent = Color(0xFF60A5FA),
            accentSecondary = Color(0xFFA78BFA),
            accentTertiary = Color(0xFF38BDF8),
            success = Color(0xFF4ADE80),
            warning = Color(0xFFFBBF24),
            danger = Color(0xFFF87171),
            selection = Color(0xFF1D4ED8),
            diagnostic = Color(0xFFF87171),
        )

        private val DefaultTypography = DiagramTypography(
            bodyFont = DiagramFont(family = "ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif", sizeSp = 14f),
        )

        val Default: DiagramTheme = DiagramTheme(colors = LightColors, typography = DefaultTypography, isDark = false)
        val Dark: DiagramTheme = DiagramTheme(colors = DarkColors, typography = DefaultTypography, isDark = true)
    }
}
