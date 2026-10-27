package xyz.emuci.syntax.theme

import androidx.compose.ui.graphics.Color
import xyz.emuci.syntax.ast.TokenType

/**
 * 代码主题接口，定义代码高亮的颜色方案。
 * 对外公开，支持自定义主题实现。
 */
interface CodeTheme {
    /** 按 Token 类型返回对应颜色 */
    fun colorFor(type: TokenType): Color

    /** 代码块背景色 */
    val background: Color

    /** 是否为暗色主题 */
    val isDark: Boolean

    val highlightedLineBackground: Color
        get() = if (isDark) Color(0x22FFFFFF) else Color(0x12000000)

    val diffAddedLineBackground: Color
        get() = if (isDark) Color(0xFF1F3D2A) else Color(0xFFDAFBE1)

    val diffRemovedLineBackground: Color
        get() = if (isDark) Color(0xFF3A1E22) else Color(0xFFFFE5E4)

    val diffMetaLineBackground: Color
        get() = if (isDark) Color(0x224B5563) else Color(0x140A66A1)

    val diffGutterTextColor: Color
        get() = if (isDark) Color(0xFF6B7280) else Color(0xFF6E7781)

    val diffGutterActionColor: Color
        get() = if (isDark) Color(0xFF1F6FEB) else Color(0xFF0969DA)

    val diffExpanderBackground: Color
        get() = if (isDark) Color(0xFF2C313A) else Color(0xFFF6F8FA)

    val diffExpanderBorderColor: Color
        get() = if (isDark) Color(0xFF3E4451) else Color(0xFFD0D7DE)

    val diffExpanderTextColor: Color
        get() = if (isDark) Color(0xFFABB2BF) else Color(0xFF57606A)

    val diffHeaderBackground: Color
        get() = if (isDark) Color(0xFF21262D) else Color(0xFFF6F8FA)

    val diffHeaderTextColor: Color
        get() = if (isDark) Color(0xFFE6EDF3) else Color(0xFF1F2328)

    val diffHeaderMutedTextColor: Color
        get() = if (isDark) Color(0xFF9CA3AF) else Color(0xFF6E7781)

    val diffAddedTextColor: Color
        get() = if (isDark) Color(0xFF4ADE80) else Color(0xFF1A7F37)

    val diffRemovedTextColor: Color
        get() = if (isDark) Color(0xFFF87171) else Color(0xFFCF222E)
}

/**
 * 安全获取颜色，缺失类型时回退到 PLAIN 颜色。
 * 标记为 internal，仅供模块内部使用。
 */
internal fun CodeTheme.safeColorFor(type: TokenType): Color {
    return try {
        colorFor(type)
    } catch (_: Exception) {
        colorFor(TokenType.PLAIN)
    }
}
