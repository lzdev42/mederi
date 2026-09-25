package xyz.emuci.latex.parser

import xyz.emuci.latex.parser.model.LatexNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 张量/指标测试 — \tensor, \indices
 */
class TensorTest {

    private val parser = LatexParser()

    @Test
    fun testTensorBasic() {
        val doc = parser.parse("\\tensor{T}{^a_b}")
        assertEquals(1, doc.children.size)
        val tensor = doc.children[0] as LatexNode.Tensor
        assertEquals(2, tensor.indices.size)
        assertTrue(tensor.indices[0].first, "First index should be upper (^a)")
        assertTrue(!tensor.indices[1].first, "Second index should be lower (_b)")
    }

    @Test
    fun testTensorMultipleIndices() {
        val doc = parser.parse("\\tensor{R}{^a_b^c_d}")
        assertEquals(1, doc.children.size)
        val tensor = doc.children[0] as LatexNode.Tensor
        assertEquals(4, tensor.indices.size)
        assertTrue(tensor.indices[0].first)   // ^a
        assertTrue(!tensor.indices[1].first)  // _b
        assertTrue(tensor.indices[2].first)   // ^c
        assertTrue(!tensor.indices[3].first)  // _d
    }

    @Test
    fun testIndices() {
        val doc = parser.parse("T\\indices{^a_b}")
        val tensorNode = doc.children.filterIsInstance<LatexNode.Tensor>().firstOrNull()
        assertNotNull(tensorNode, "Tensor node should exist from \\indices")
        assertTrue(tensorNode.indices.isNotEmpty())
    }

    @Test
    fun testTensorNoIndices() {
        val doc = parser.parse("\\tensor{T}{}")
        assertEquals(1, doc.children.size)
        val tensor = doc.children[0] as LatexNode.Tensor
        assertEquals(0, tensor.indices.size)
    }
}
