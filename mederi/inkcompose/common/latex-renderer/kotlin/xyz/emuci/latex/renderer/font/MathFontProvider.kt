package xyz.emuci.latex.renderer.font

import androidx.compose.ui.text.font.FontFamily

/** KaTeX TTF 字体度量的统一入口。 */
internal interface MathFontProvider {
    fun axisHeight(fontSizePx: Float): Float
    fun fractionRuleThickness(fontSizePx: Float): Float
    fun fractionNumeratorDisplayGap(fontSizePx: Float): Float
    fun fractionNumeratorGap(fontSizePx: Float): Float
    fun fractionDenominatorDisplayGap(fontSizePx: Float): Float
    fun fractionDenominatorGap(fontSizePx: Float): Float
    fun fractionNumeratorShiftUp(fontSizePx: Float, displayStyle: Boolean, hasRule: Boolean): Float
    fun fractionDenominatorShiftDown(fontSizePx: Float, displayStyle: Boolean): Float
    fun superscriptShiftUp(fontSizePx: Float): Float
    fun superscriptShiftUp(fontSizePx: Float, displayStyle: Boolean, crampedStyle: Boolean): Float =
        superscriptShiftUp(fontSizePx)
    fun subscriptShiftDown(fontSizePx: Float): Float
    fun subscriptShiftDown(fontSizePx: Float, hasSuperscript: Boolean): Float =
        subscriptShiftDown(fontSizePx)
    fun superscriptDrop(fontSizePx: Float): Float = 0f
    fun subscriptDrop(fontSizePx: Float): Float = 0f
    fun xHeight(fontSizePx: Float): Float
    fun spaceAfterScript(fontSizePx: Float): Float = fontSizePx * 0.05f
    fun subSuperscriptGapMin(fontSizePx: Float): Float
    fun radicalDisplayVerticalGap(fontSizePx: Float): Float
    fun radicalVerticalGap(fontSizePx: Float): Float
    fun radicalRuleThickness(fontSizePx: Float): Float
    fun upperLimitGap(fontSizePx: Float, limitDepthPx: Float): Float
    fun lowerLimitGap(fontSizePx: Float, limitHeightPx: Float): Float
    fun italicCorrection(glyphChar: String, fontSizePx: Float): Float
    fun topAccentAttachment(
        glyphChar: String,
        fontSizePx: Float,
        glyphAdvancePx: Float
    ): Float
    fun fontFamilyFor(role: MathFontRole): FontFamily
    fun fontBytes(role: MathFontRole): ByteArray?
}

/** KaTeX 字体集中的字形角色。 */
enum class MathFontRole {
    ROMAN,
    MATH_ITALIC,
    BLACKBOARD_BOLD,
    CALLIGRAPHIC,
    FRAKTUR,
    SCRIPT,
    SANS_SERIF,
    MONOSPACE,
    LARGE_OPERATOR,
    DELIMITER,
}
