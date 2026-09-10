package xyz.emuci.latex.parser

import xyz.emuci.latex.parser.model.LatexNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 四角标注测试 — \sideset
 */
class SideSetTest {

    private val parser = LatexParser()

    @Test
    fun testSideSetBasic() {
        val doc = parser.parse("\\sideset{_a^b}{_c^d}{\\sum}")
        assertEquals(1, doc.children.size)
        val sideSet = doc.children[0] as LatexNode.SideSet
        assertNotNull(sideSet.leftSub, "Left subscript should exist")
        assertNotNull(sideSet.leftSup, "Left superscript should exist")
        assertNotNull(sideSet.rightSub, "Right subscript should exist")
        assertNotNull(sideSet.rightSup, "Right superscript should exist")
    }

    @Test
    fun testSideSetPartial() {
        val doc = parser.parse("\\sideset{_a}{^d}{\\sum}")
        assertEquals(1, doc.children.size)
        val sideSet = doc.children[0] as LatexNode.SideSet
        assertNotNull(sideSet.leftSub)
        assertNull(sideSet.leftSup)
        assertNull(sideSet.rightSub)
        assertNotNull(sideSet.rightSup)
    }

    @Test
    fun testSideSetEmpty() {
        val doc = parser.parse("\\sideset{}{}{\\prod}")
        assertEquals(1, doc.children.size)
        val sideSet = doc.children[0] as LatexNode.SideSet
        assertNull(sideSet.leftSub)
        assertNull(sideSet.leftSup)
        assertNull(sideSet.rightSub)
        assertNull(sideSet.rightSup)
    }

    // ============ \prescript 前置上下标 ============

    @Test
    fun testPrescriptBasic() {
        val doc = parser.parse("\\prescript{A}{Z}{X}")
        assertEquals(1, doc.children.size)
        val prescript = doc.children[0] as LatexNode.Prescript
        assertNotNull(prescript.preSuperscript)
        assertNotNull(prescript.preSubscript)
        assertNotNull(prescript.base)
    }

    @Test
    fun testPrescriptIsotopeNotation() {
        // 同位素标记: ^{235}_{92}U
        val doc = parser.parse("\\prescript{235}{92}{U}")
        assertEquals(1, doc.children.size)
        val prescript = doc.children[0] as LatexNode.Prescript
        assertNotNull(prescript.preSuperscript)
        assertNotNull(prescript.preSubscript)
    }

    @Test
    fun testPrescriptOnlySupscript() {
        // 只有前置上标
        val doc = parser.parse("\\prescript{A}{}{X}")
        assertEquals(1, doc.children.size)
        val prescript = doc.children[0] as LatexNode.Prescript
        assertNotNull(prescript.preSuperscript)
        assertNull(prescript.preSubscript, "空花括号应视为 null")
    }

    @Test
    fun testPrescriptOnlySubscript() {
        // 只有前置下标
        val doc = parser.parse("\\prescript{}{Z}{X}")
        assertEquals(1, doc.children.size)
        val prescript = doc.children[0] as LatexNode.Prescript
        assertNull(prescript.preSuperscript, "空花括号应视为 null")
        assertNotNull(prescript.preSubscript)
    }

    @Test
    fun testPrescriptBothEmpty() {
        val doc = parser.parse("\\prescript{}{}{X}")
        assertEquals(1, doc.children.size)
        val prescript = doc.children[0] as LatexNode.Prescript
        assertNull(prescript.preSuperscript)
        assertNull(prescript.preSubscript)
    }
}
