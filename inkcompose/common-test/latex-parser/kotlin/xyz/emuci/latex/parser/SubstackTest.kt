package xyz.emuci.latex.parser

import xyz.emuci.latex.parser.model.LatexNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * \substack 多行下标条件测试
 */
class SubstackTest {

    private val parser = LatexParser()

    @Test
    fun should_parse_substack() {
        val result = parser.parse("\\sum_{\\substack{i<n \\\\ j<m}} x_{ij}")
        assertIs<LatexNode.Document>(result)
        val hasSubstack = findNode(result) { it is LatexNode.Substack }
        assertTrue(hasSubstack, "Should contain substack node")
    }

    @Test
    fun should_parse_substack_with_multiple_rows() {
        val result = parser.parse("\\substack{a \\\\ b \\\\ c}")
        assertIs<LatexNode.Document>(result)
        val substack = result.children[0]
        assertIs<LatexNode.Substack>(substack)
        assertEquals(3, substack.rows.size)
    }

    @Test
    fun should_parse_substack_single_row() {
        val result = parser.parse("\\substack{a + b}")
        assertIs<LatexNode.Document>(result)
        val substack = result.children[0]
        assertIs<LatexNode.Substack>(substack)
        assertEquals(1, substack.rows.size)
    }

    private fun findNode(node: LatexNode, predicate: (LatexNode) -> Boolean): Boolean {
        if (predicate(node)) return true
        return when (node) {
            is LatexNode.Document -> node.children.any { findNode(it, predicate) }
            is LatexNode.Group -> node.children.any { findNode(it, predicate) }
            is LatexNode.Subscript -> findNode(node.base, predicate) || findNode(node.index, predicate)
            is LatexNode.Superscript -> findNode(node.base, predicate) || findNode(node.exponent, predicate)
            is LatexNode.BigOperator -> {
                (node.subscript?.let { findNode(it, predicate) } ?: false) ||
                        (node.superscript?.let { findNode(it, predicate) } ?: false)
            }
            else -> false
        }
    }
}
