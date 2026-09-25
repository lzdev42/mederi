package xyz.emuci.markdown.renderer

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Markdown 色彩皮肤配置（纯颜色系统，解耦版式系统）。
 *
 * 专注于视觉色彩与深浅模式映射：
 * - 文本主色/次级色/弱化色/链接色；
 * - 容器底色（代码块、行内代码、表格表头、引用块）；
 * - 边框与分割线颜色。
 */
@Immutable
data class MarkdownColors(
    /** 正文主要文字颜色 */
    val textPrimary: Color,
    /** 次级文字颜色（如引用块文本） */
    val textSecondary: Color,
    /** 弱化文字颜色（如表格辅助标记） */
    val textMuted: Color,
    /** 链接文字颜色 */
    val link: Color,
    /** 水平分割线与标题底部实线颜色 */
    val divider: Color,
    /** 行内代码背景底色 */
    val inlineCodeBackground: Color,
    /** 代码块容器背景色 */
    val codeBlockBackground: Color,
    /** 引用块左侧强调边框色 */
    val blockQuoteBorder: Color,
    /** 表格边框线颜色 */
    val tableBorder: Color,
    /** 表格表头背景底色 */
    val tableHeaderBackground: Color,
    /** 任务列表选中颜色 */
    val taskCheckedColor: Color = Color(0xFF1A7F37),
    /** 任务列表未选中颜色 */
    val taskUncheckedColor: Color = Color(0xFFD0D7DE),
    /** 荧光笔高亮背景色 */
    val highlightColor: Color = Color(0xFFFFF3B0),
    /** 是否为暗色皮肤 */
    val isDark: Boolean = false,
) {
    companion object {
        /** 标准亮色色彩预设 (GitHub Light 风格调色板) */
        fun light(): MarkdownColors = MarkdownColors(
            textPrimary = Color(0xFF1F2328),
            textSecondary = Color(0xFF656D76),
            textMuted = Color(0xFF8C959F),
            link = Color(0xFF0969DA),
            divider = Color(0xFFD0D7DE),
            inlineCodeBackground = Color(0xFFEFF1F3),
            codeBlockBackground = Color(0xFFF6F8FA),
            blockQuoteBorder = Color(0xFFD0D7DE),
            tableBorder = Color(0xFFD0D7DE),
            tableHeaderBackground = Color(0xFFF6F8FA),
            taskCheckedColor = Color(0xFF1A7F37),
            taskUncheckedColor = Color(0xFFD0D7DE),
            highlightColor = Color(0xFFFFF3B0),
            isDark = false,
        )

        /** 标准暗色色彩预设 (GitHub Dark 风格调色板) */
        fun dark(): MarkdownColors = MarkdownColors(
            textPrimary = Color(0xFFE6EDF3),
            textSecondary = Color(0xFF8D96A0),
            textMuted = Color(0xFF6E7681),
            link = Color(0xFF4493F8),
            divider = Color(0xFF30363D),
            inlineCodeBackground = Color(0xFF21262D),
            codeBlockBackground = Color(0xFF161B22),
            blockQuoteBorder = Color(0xFF30363D),
            tableBorder = Color(0xFF30363D),
            tableHeaderBackground = Color(0xFF161B22),
            taskCheckedColor = Color(0xFF2EA043),
            taskUncheckedColor = Color(0xFF484F58),
            highlightColor = Color(0x3DF2CC60),
            isDark = true,
        )

        /** 从 Material 3 调色板派生色彩配置 */
        fun fromMaterial3(colorScheme: ColorScheme): MarkdownColors {
            val isDark = colorScheme.background.luminance() < 0.5f
            return MarkdownColors(
                textPrimary = colorScheme.onSurface,
                textSecondary = colorScheme.onSurfaceVariant,
                textMuted = colorScheme.outline,
                link = colorScheme.primary,
                divider = colorScheme.outlineVariant,
                inlineCodeBackground = if (isDark) Color(0xFF21262D) else Color(0xFFEFF1F3),
                codeBlockBackground = colorScheme.surfaceVariant,
                blockQuoteBorder = colorScheme.outline,
                tableBorder = colorScheme.outlineVariant,
                tableHeaderBackground = colorScheme.surfaceVariant,
                taskCheckedColor = colorScheme.primary,
                taskUncheckedColor = colorScheme.outline,
                highlightColor = if (isDark) Color(0x3DF2CC60) else Color(0xFFFFF3B0),
                isDark = isDark,
            )
        }

        /** 自动跟随当前 Compose MaterialTheme 调色板 */
        @Composable
        fun auto(): MarkdownColors {
            return fromMaterial3(MaterialTheme.colorScheme)
        }
    }
}

/** 全局渲染色彩配置 CompositionLocal */
val LocalMarkdownColors = compositionLocalOf { MarkdownColors.light() }
