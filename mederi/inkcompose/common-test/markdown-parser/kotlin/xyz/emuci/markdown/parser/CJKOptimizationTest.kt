package xyz.emuci.markdown.parser

import xyz.emuci.markdown.parser.ast.*
import xyz.emuci.markdown.parser.core.CharacterUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 中文/CJK 本地化优化测试。
 *
 * 验证以下场景：
 * 1. 全角标点后的分隔符正确识别（如 `*中文*。` 正确解析斜体）
 * 2. CharacterUtils 的 CJK/全角标点辅助方法
 * 3. 中英文混合场景的强调正确性
 * 4. `_` 下划线在 CJK 上下文中的词内规则
 */
class CJKOptimizationTest {

    private fun parse(input: String): Document {
        return MarkdownParser().parse(input)
    }

    private fun firstParagraphChildren(input: String): List<Node> {
        val doc = parse(input)
        val para = doc.children.first() as Paragraph
        return para.children
    }

    // ────── CharacterUtils CJK 方法测试 ──────

    @Test
    fun should_detect_cjk_characters() {
        assertTrue(CharacterUtils.isCJK('中'))
        assertTrue(CharacterUtils.isCJK('文'))
        assertTrue(CharacterUtils.isCJK('の'))  // Hiragana
        assertTrue(CharacterUtils.isCJK('ア'))  // Katakana
        assertTrue(CharacterUtils.isCJK('한'))  // Hangul
        assertFalse(CharacterUtils.isCJK('A'))
        assertFalse(CharacterUtils.isCJK(' '))
        assertFalse(CharacterUtils.isCJK('*'))
    }

    @Test
    fun should_detect_fullwidth_punctuation() {
        assertTrue(CharacterUtils.isFullWidthPunctuation('。'))
        assertTrue(CharacterUtils.isFullWidthPunctuation('，'))
        assertTrue(CharacterUtils.isFullWidthPunctuation('！'))
        assertTrue(CharacterUtils.isFullWidthPunctuation('？'))
        assertTrue(CharacterUtils.isFullWidthPunctuation('；'))
        assertTrue(CharacterUtils.isFullWidthPunctuation('：'))
        assertTrue(CharacterUtils.isFullWidthPunctuation('、'))
        assertTrue(CharacterUtils.isFullWidthPunctuation('》'))
        assertTrue(CharacterUtils.isFullWidthPunctuation('《'))
        assertFalse(CharacterUtils.isFullWidthPunctuation('.'))
        assertFalse(CharacterUtils.isFullWidthPunctuation(','))
        assertFalse(CharacterUtils.isFullWidthPunctuation('A'))
    }

    @Test
    fun should_detect_cjk_or_fullwidth_punctuation() {
        assertTrue(CharacterUtils.isCJKOrFullWidthPunctuation('中'))
        assertTrue(CharacterUtils.isCJKOrFullWidthPunctuation('。'))
        assertFalse(CharacterUtils.isCJKOrFullWidthPunctuation('A'))
        assertFalse(CharacterUtils.isCJKOrFullWidthPunctuation(' '))
    }

    // ────── 全角标点后的分隔符正确识别 ──────

    @Test
    fun should_parse_emphasis_before_fullwidth_period() {
        val children = firstParagraphChildren("*中文*。后续文本")
        val hasEmphasis = children.any { it is Emphasis }
        assertTrue(hasEmphasis, "Expected *中文* to be parsed as emphasis before 。")
    }

    @Test
    fun should_parse_emphasis_before_fullwidth_comma() {
        val children = firstParagraphChildren("*强调*，继续")
        val hasEmphasis = children.any { it is Emphasis }
        assertTrue(hasEmphasis, "Expected *强调* to be parsed as emphasis before ，")
    }

    @Test
    fun should_parse_strong_with_cjk() {
        val children = firstParagraphChildren("这是**粗体**文本")
        val hasStrong = children.any { it is StrongEmphasis }
        assertTrue(hasStrong, "Expected **粗体** to be parsed as strong emphasis")
    }

    @Test
    fun should_parse_emphasis_with_cjk_and_fullwidth_exclamation() {
        val children = firstParagraphChildren("*重要*！")
        val hasEmphasis = children.any { it is Emphasis }
        assertTrue(hasEmphasis, "Expected *重要* to be parsed as emphasis before ！")
    }

    // ────── 空格分隔的 CJK 强调正常工作 ──────

    @Test
    fun should_parse_emphasis_with_spaces_around_cjk() {
        val children = firstParagraphChildren("我 *的* 文档")
        val hasEmphasis = children.any { it is Emphasis }
        assertTrue(hasEmphasis, "Expected 我 *的* 文档 to be parsed as emphasis (space-delimited)")
    }

    @Test
    fun should_parse_strong_with_spaces_around_cjk() {
        val children = firstParagraphChildren("这是 **粗体** 内容")
        val hasStrong = children.any { it is StrongEmphasis }
        assertTrue(hasStrong, "Expected **粗体** with spaces to be parsed as strong")
    }

    // ────── 行首/行尾的 CJK 强调正常工作 ──────

    @Test
    fun should_parse_emphasis_at_line_start_with_cjk() {
        val children = firstParagraphChildren("*中文斜体*结尾")
        val hasEmphasis = children.any { it is Emphasis }
        assertTrue(hasEmphasis, "Expected *中文斜体* at line start to be parsed as emphasis")
    }

    @Test
    fun should_parse_emphasis_at_line_end_with_cjk() {
        val children = firstParagraphChildren("开头*中文斜体*")
        val hasEmphasis = children.any { it is Emphasis }
        assertTrue(hasEmphasis, "Expected *中文斜体* at line end to be parsed as emphasis")
    }

    // ────── 标点边界的 CJK 强调 ──────

    @Test
    fun should_parse_emphasis_after_fullwidth_punctuation() {
        val children = firstParagraphChildren("说：*强调*")
        val hasEmphasis = children.any { it is Emphasis }
        assertTrue(hasEmphasis, "Expected *强调* after ： to be parsed as emphasis")
    }

    @Test
    fun should_parse_emphasis_between_fullwidth_punctuation() {
        val children = firstParagraphChildren("（*强调*）")
        val hasEmphasis = children.any { it is Emphasis }
        assertTrue(hasEmphasis, "Expected (*强调*) with fullwidth parens to be parsed as emphasis")
    }

    @Test
    fun should_parse_emphasis_before_fullwidth_question_mark() {
        val children = firstParagraphChildren("这是*斜体*？")
        val hasEmphasis = children.any { it is Emphasis }
        assertTrue(hasEmphasis, "Expected *斜体* to be parsed as emphasis before ？")
    }

    // ────── 混合场景 ──────

    @Test
    fun should_handle_mixed_cjk_and_english_emphasis() {
        val children = firstParagraphChildren("中文 *English* 混合")
        val hasEmphasis = children.any { it is Emphasis }
        assertTrue(hasEmphasis, "Expected *English* in mixed content to be parsed as emphasis")
    }

    @Test
    fun should_parse_underscore_emphasis_with_cjk_boundary() {
        val children = firstParagraphChildren("前文 _斜体_ 后文")
        val hasEmphasis = children.any { it is Emphasis }
        assertTrue(hasEmphasis, "Expected _斜体_ with spaces to be parsed as emphasis")
    }

    @Test
    fun should_not_parse_underscore_between_cjk_without_spaces() {
        val children = firstParagraphChildren("我_的_文档")
        val hasEmphasis = children.any { it is Emphasis }
        assertFalse(hasEmphasis, "Expected 我_的_文档 NOT to be parsed as emphasis")
    }

    // ────── 全角引号场景 ──────

    @Test
    fun should_parse_emphasis_inside_fullwidth_quotes() {
        val children = firstParagraphChildren("\u201C*重要*\u201D")
        val hasEmphasis = children.any { it is Emphasis }
        assertTrue(hasEmphasis, "Expected *重要* inside fullwidth quotes to be parsed as emphasis")
    }

    @Test
    fun should_parse_strong_inside_cjk_brackets() {
        val children = firstParagraphChildren("《**书名**》")
        val hasStrong = children.any { it is StrongEmphasis }
        assertTrue(hasStrong, "Expected **书名** inside 《》 to be parsed as strong")
    }
}
