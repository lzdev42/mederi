package xyz.emuci.latex.parser

import xyz.emuci.latex.parser.model.LatexNode
import xyz.emuci.latex.parser.visitor.AccessibilityVisitor
import xyz.emuci.latex.parser.visitor.MathMLVisitor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PackageCommandTest {
    private val parser = LatexParser()

    @Test
    fun parsesMathStrutAsZeroWidthVerticalPhantom() {
        val node = parser.parse("a\\mathstrut+b").children[1]

        assertIs<LatexNode.VPhantom>(node)
        assertEquals("(", (node.content.single() as LatexNode.Text).content)
    }

    @Test
    fun parsesCircledUsingCircleEnclosure() {
        val node = parser.parse("\\circled{1}").children.single()

        assertIs<LatexNode.Enclose>(node)
        assertEquals(listOf(LatexNode.Enclose.Notation.CIRCLE), node.notations)
        assertEquals("1", (node.content.single() as LatexNode.Text).content)
    }

    @Test
    fun parsesLongEqualWithLabels() {
        val node = parser.parse("\\xlongequal[below]{above}").children.single()

        assertIs<LatexNode.ExtensibleArrow>(node)
        assertEquals(LatexNode.ExtensibleArrow.Direction.EQUAL, node.direction)
        assertNotNull(node.below)
        assertTrue(MathMLVisitor.convert(node).contains("<mo stretchy=\"true\">=</mo>"))
        assertTrue(AccessibilityVisitor.describe(node).contains("long equals"))
    }

    @Test
    fun parsesPhysicsDelimiters() {
        val expected = mapOf(
            "\\abs{x}" to ("|" to "|"),
            "\\norm{x}" to ("‖" to "‖"),
            "\\bra{x}" to ("⟨" to "|"),
            "\\ket{x}" to ("|" to "⟩"),
            "\\braket{a|b}" to ("⟨" to "⟩")
        )

        expected.forEach { (latex, delimiters) ->
            val node = parser.parse(latex).children.single()
            assertIs<LatexNode.Delimited>(node)
            assertEquals(delimiters.first, node.left)
            assertEquals(delimiters.second, node.right)
        }
    }

    @Test
    fun parsesOrdinaryAndPartialDerivatives() {
        val ordinary = parser.parse("\\dv{x}").children.single()
        assertIs<LatexNode.Fraction>(ordinary)
        assertEquals(1, (ordinary.numerator as LatexNode.Group).children.size)

        val partial = parser.parse("\\pdv[2]{f}{x}").children.single()
        assertIs<LatexNode.Fraction>(partial)
        val numerator = partial.numerator as LatexNode.Group
        val denominator = partial.denominator as LatexNode.Group
        assertIs<LatexNode.Superscript>(numerator.children.first())
        assertIs<LatexNode.Superscript>(denominator.children.last())
        assertEquals("partial", ((numerator.children.first() as LatexNode.Superscript).base as LatexNode.Symbol).symbol)
    }

    @Test
    fun formatsSiunitxNumbersAndUnits() {
        val number = parser.parse("\\num{1.23e4}").children.single()
        assertIs<LatexNode.Group>(number)
        assertTrue(number.children.any { it is LatexNode.Symbol && it.symbol == "times" })
        assertTrue(number.children.any { it is LatexNode.Superscript })

        val unit = parser.parse("\\si{kg.m/s^2}").children.single()
        assertIs<LatexNode.Style>(unit)
        assertEquals(LatexNode.Style.StyleType.ROMAN, unit.styleType)
        assertTrue(unit.fold(false) { found, node ->
            found || (node is LatexNode.Text && '·' in node.content)
        })

        val quantity = parser.parse("\\SI{9.8}{m/s^2}").children.single()
        assertIs<LatexNode.Group>(quantity)
        assertEquals(3, quantity.children.size)
        assertIs<LatexNode.Style>(quantity.children.last())
    }
}
