package xyz.emuci.markdown.parser

import xyz.emuci.markdown.parser.ast.*
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * \tag{N}、环境自动编号等由 LaTeX 渲染库原生处理，
 * parser 层只需保证 literal 中完整保留原始 LaTeX 文本。
 */
class MathTagTest {

    @Test
    fun should_preserve_tag_in_single_line_math() {
        val parser = MarkdownParser()
        val doc = parser.parse("$$ E=mc^2 \\tag{1} $$")
        val math = doc.children.first()
        assertIs<MathBlock>(math)
        assertTrue(math.literal.contains("E=mc^2"))
        assertTrue(math.literal.contains("\\tag{1}"))
    }

    @Test
    fun should_preserve_tag_in_multiline_math() {
        val parser = MarkdownParser()
        val doc = parser.parse("""
$$
E=mc^2 \tag{2}
$$
        """.trimIndent())
        val math = doc.children.first()
        assertIs<MathBlock>(math)
        assertTrue(math.literal.contains("\\tag{2}"))
    }

    @Test
    fun should_parse_math_without_tag() {
        val parser = MarkdownParser()
        val doc = parser.parse("""
$$
x = \frac{-b \pm \sqrt{b^2 - 4ac}}{2a}
$$
        """.trimIndent())
        val math = doc.children.first()
        assertIs<MathBlock>(math)
        assertTrue(math.literal.contains("\\frac"))
        assertTrue(!math.literal.contains("\\tag"))
    }

    @Test
    fun should_preserve_tag_with_complex_label() {
        val parser = MarkdownParser()
        val doc = parser.parse("""
$$
\sum_{i=1}^{n} i = \frac{n(n+1)}{2} \tag{eq:sum}
$$
        """.trimIndent())
        val math = doc.children.first()
        assertIs<MathBlock>(math)
        assertTrue(math.literal.contains("\\tag{eq:sum}"))
    }

    @Test
    fun should_preserve_tag_star() {
        val parser = MarkdownParser()
        val doc = parser.parse("""
$$
a^2 + b^2 = c^2 \tag{*}
$$
        """.trimIndent())
        val math = doc.children.first()
        assertIs<MathBlock>(math)
        assertTrue(math.literal.contains("\\tag{*}"))
    }
}
