package xyz.emuci.diagram.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt

/**
 * [DiagramTheme] → Mermaid 原生初始化配置 JSON。
 * 图形配色完全交给 Mermaid 内置主题（浅色 'default' / 深色 'dark'），不注入调色板变量；
 * themeVariables 仅保留三个与主题引擎无冲突的标准变量：
 * - background：画布底色对齐 app surface（PNG/SVG 背景一致）
 * - fontFamily / fontSize：沿用 app 排版
 * 各平台 Web 渲染管线（jvm 离屏 / wasmJs DOM / android / ios 桥接）共用。
 */
internal fun mermaidConfigPayloadJson(theme: DiagramTheme): String {
    val background = (theme.colors.canvas).toCssColor()
    val fontFamily = jsonEscape(theme.typography.bodyFont.family)
    val fontSize = theme.typography.bodyFont.sizeSp.toInt()

    return buildString {
        append("{")
        append("\"theme\":\"").append(if (theme.isDark) "dark" else "default").append("\",")
        append("\"darkMode\":").append(theme.isDark).append(",")
        append("\"themeVariables\":{")
        append("\"background\":\"").append(background).append("\",")
        append("\"fontFamily\":\"").append(fontFamily).append("\",")
        append("\"fontSize\":\"").append(fontSize).append("px\"")
        append("}")
        append("}")
    }
}

/** Compose [Color] → CSS 颜色字符串（支持 alpha RGBA）。 */
internal fun Color.toCssColor(): String {
    val a = alpha
    val r = (red * 255f).roundToInt().coerceIn(0, 255)
    val g = (green * 255f).roundToInt().coerceIn(0, 255)
    val b = (blue * 255f).roundToInt().coerceIn(0, 255)
    return if (a >= 0.999f) {
        val rgb = (r shl 16) or (g shl 8) or b
        "#" + rgb.toString(16).uppercase().padStart(6, '0')
    } else {
        val aFormatted = ((a * 100).roundToInt() / 100.0).toString()
        "rgba($r,$g,$b,$aFormatted)"
    }
}

internal fun jsonEscape(s: String): String = buildString {
    for (c in s) {
        when {
            c == '\\' -> append("\\\\")
            c == '"' -> append("\\\"")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
            else -> append(c)
        }
    }
}
