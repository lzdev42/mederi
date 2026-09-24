package xyz.emuci.markdown.renderer

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Markdown 排版版式配置（纯几何与结构规则，解耦色彩系统）。
 *
 * 专注于排版骨架：
 * - 字体阶梯 (Typography Scale)：各级标题字号、字重、行高与正文样式；
 * - 空间呼吸感 (Layout Spacing)：段落垂直间距 (blockSpacing)、列表缩进 (listIndent)、内边距；
 * - 结构特征 (Structural Features)：H1/H2 底部实线分割线开关 (showHeadingDividers)、边框宽度；
 * - 盒模型几何 (Shapes)：代码块圆角、行内代码圆角胶囊。
 *
 * 包含两套开箱即用的标准预设：
 * - [RenderStyle.Chat]：对话消息场景（小行距、紧凑垂直间距、平缓字号、关闭分割线）；
 * - [RenderStyle.Github]：正规文档阅读场景（大呼吸感、GFM 大字号阶梯、开启 H1/H2 分割线）。
 */
@Immutable
data class RenderStyle(
    /** 正文文字样式（字号、字重、行高） */
    val bodyStyle: TextStyle,
    /** 各级标题文字样式 (H1 ~ H6) */
    val headingStyles: List<TextStyle>,
    /** 行内代码样式（字号、等宽字体） */
    val inlineCodeStyle: SpanStyle = SpanStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
    ),
    /** 代码块文字样式 */
    val codeBlockStyle: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.5.sp,
        lineHeight = 18.sp,
    ),
    /** 块级组件垂直间距（段落间距、段与块间距） */
    val blockSpacing: Dp,
    /** 列表项左侧缩进距离 */
    val listIndent: Dp,
    /** 表格单元格内边距 */
    val tableCellPadding: Dp,
    /** 代码块内边距 */
    val codeBlockPadding: Dp,
    /** 引用块内边距 */
    val blockQuotePadding: Dp,
    /** 引用块左侧条宽度 */
    val blockQuoteBorderWidth: Dp,
    /** 水平分割线粗细 */
    val dividerThickness: Dp = 1.dp,
    /** 是否在 H1 与 H2 标题底部渲染实线分割线（GitHub GFM 标准特征） */
    val showHeadingDividers: Boolean,
    /** 行内代码圆角形状 */
    val inlineCodeShape: Shape = RoundedCornerShape(4.dp),
    /** 代码块圆角形状 */
    val codeBlockShape: Shape = RoundedCornerShape(8.dp),
) {
    companion object {
        /**
         * 对话消息场景预设：
         * - 针对气泡与密集消息流优化；
         * - 紧凑的段落与块间距 (6dp)；
         * - 较为平缓的字号落差；
         * - 关闭标题底部实线分割线，避免在小气泡内造成割裂感。
         */
        val Chat = RenderStyle(
            bodyStyle = TextStyle(
                fontSize = 13.5.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Normal
            ),
            headingStyles = listOf(
                TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold, lineHeight = 22.sp),
                TextStyle(fontSize = 15.5.sp, fontWeight = FontWeight.Bold, lineHeight = 20.sp),
                TextStyle(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 19.sp),
                TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp),
                TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 17.sp),
                TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp),
            ),
            inlineCodeStyle = SpanStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            ),
            codeBlockStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            ),
            blockSpacing = 6.dp,
            listIndent = 16.dp,
            tableCellPadding = 5.dp,
            codeBlockPadding = 8.dp,
            blockQuotePadding = 8.dp,
            blockQuoteBorderWidth = 3.dp,
            dividerThickness = 1.dp,
            showHeadingDividers = false,
            inlineCodeShape = RoundedCornerShape(4.dp),
            codeBlockShape = RoundedCornerShape(6.dp),
        )

        /**
         * GitHub 正规文档阅读场景预设（标准 GFM 排版）：
         * - 针对大屏与独立长文阅读优化；
         * - 宽裕标准的段落呼吸感 (blockSpacing = 16dp)；
         * - 标准 GFM 大字号阶梯 (H1 24sp、H2 20sp、H3 16.5sp、Body 14sp)；
         * - 开启 H1 与 H2 底部实线分割线；
         * - 舒适的列表缩进 (24dp) 与单元格间距。
         */
        val Github = RenderStyle(
            bodyStyle = TextStyle(
                fontSize = 14.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.Normal
            ),
            headingStyles = listOf(
                TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp),
                TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 26.sp),
                TextStyle(fontSize = 16.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
                TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp),
                TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 18.sp),
                TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.Medium, lineHeight = 18.sp),
            ),
            inlineCodeStyle = SpanStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.5.sp,
            ),
            codeBlockStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.5.sp,
                lineHeight = 18.5.sp,
            ),
            blockSpacing = 16.dp,
            listIndent = 24.dp,
            tableCellPadding = 8.dp,
            codeBlockPadding = 12.dp,
            blockQuotePadding = 12.dp,
            blockQuoteBorderWidth = 4.dp,
            dividerThickness = 1.dp,
            showHeadingDividers = true,
            inlineCodeShape = RoundedCornerShape(4.dp),
            codeBlockShape = RoundedCornerShape(8.dp),
        )
    }
}

/** 全局渲染版式风格 CompositionLocal */
val LocalRenderStyle = compositionLocalOf { RenderStyle.Chat }
