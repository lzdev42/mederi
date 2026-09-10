package xyz.emuci.latex.parser

import xyz.emuci.latex.parser.model.LatexNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * \tag 和 \tag* 公式编号标签测试
 */
class TagTest {

    private val parser = LatexParser()

    @Test
    fun should_parse_tag() {
        val result = parser.parse("E = mc^2 \\tag{1}")
        assertIs<LatexNode.Document>(result)
        val hasTag = result.children.any { it is LatexNode.Tag }
        assertTrue(hasTag, "Should contain tag node")
        val tag = result.children.filterIsInstance<LatexNode.Tag>().first()
        assertEquals(false, tag.starred)
    }

    @Test
    fun should_parse_tag_star() {
        val result = parser.parse("E = mc^2 \\tag*{A}")
        assertIs<LatexNode.Document>(result)
        val hasTag = result.children.any { it is LatexNode.Tag }
        assertTrue(hasTag, "Should contain tag* node")
        val tag = result.children.filterIsInstance<LatexNode.Tag>().first()
        assertEquals(true, tag.starred)
    }
}
