package xyz.emuci.latex.parser

import xyz.emuci.latex.parser.model.LatexNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SpaceTest {

    @Test
    fun should_parse_negative_thin_space_correctly() {
        // Arrange
        val input = "a \\! b"
        val parser = LatexParser()

        // Act
        val result = parser.parse(input)

        // Assert
        // a, space, \!, space, b
        // The parser converts tokenizer whitespaces to LatexNode.Space(NORMAL) if handled in parseFactor
        // Let's verify structure
        val children = result.children
        // Expected: Text(a), Space(NORMAL), Space(NEGATIVE_THIN), Space(NORMAL), Text(b)
        // Wait, LatexParser parseFactor:
        // is LatexToken.Whitespace -> LatexNode.Space(LatexNode.Space.SpaceType.NORMAL)
        
        // Let's print to see what we get or inspect children
        assertTrue(children.isNotEmpty())
        
        val negativeSpaceNode = children.find { 
            it is LatexNode.Space && it.type == LatexNode.Space.SpaceType.NEGATIVE_THIN 
        }
        
        assertTrue(negativeSpaceNode != null, "Should contain negative thin space")
    }

    @Test
    fun should_parse_hspace_correctly() {
        // Arrange
        val input = "a \\hspace{1cm} b"
        val parser = LatexParser()

        // Act
        val result = parser.parse(input)

        // Assert
        val children = result.children
        val hSpaceNode = children.find { it is LatexNode.HSpace }
        
        assertTrue(hSpaceNode != null, "Should contain HSpace")
        assertEquals("1cm", (hSpaceNode as LatexNode.HSpace).dimension)
    }
    
    @Test
    fun should_parse_hspace_with_different_units() {
        val parser = LatexParser()
        
        // Test pt
        val res1 = parser.parse("\\hspace{10pt}")
        assertEquals("10pt", (res1.children.first() as LatexNode.HSpace).dimension)
        
        // Test em
        val res2 = parser.parse("\\hspace{2em}")
        assertEquals("2em", (res2.children.first() as LatexNode.HSpace).dimension)
        
        // Test negative
        val res3 = parser.parse("\\hspace{-5mm}")
        assertEquals("-5mm", (res3.children.first() as LatexNode.HSpace).dimension)
    }

    @Test
    fun should_parse_common_spacing_aliases() {
        val parser = LatexParser()
        val spaceAliases = listOf(
            "\\ " to LatexNode.Space.SpaceType.NORMAL,
            "\\space" to LatexNode.Space.SpaceType.NORMAL,
            "\\," to LatexNode.Space.SpaceType.THIN,
            "\\thinspace" to LatexNode.Space.SpaceType.THIN,
            "\\:" to LatexNode.Space.SpaceType.MEDIUM,
            "\\>" to LatexNode.Space.SpaceType.MEDIUM,
            "\\medspace" to LatexNode.Space.SpaceType.MEDIUM,
            "\\;" to LatexNode.Space.SpaceType.THICK,
            "\\thickspace" to LatexNode.Space.SpaceType.THICK,
            "\\quad" to LatexNode.Space.SpaceType.QUAD,
            "\\qquad" to LatexNode.Space.SpaceType.QQUAD,
            "\\!" to LatexNode.Space.SpaceType.NEGATIVE_THIN,
            "\\negthinspace" to LatexNode.Space.SpaceType.NEGATIVE_THIN
        )

        spaceAliases.forEach { (command, expectedType) ->
            val node = assertIs<LatexNode.Space>(parser.parse(command).children.single())
            assertEquals(expectedType, node.type, command)
        }
    }

    @Test
    fun should_parse_extended_spacing_commands() {
        val parser = LatexParser()
        val dimensions = listOf(
            "\\enspace" to "0.5em",
            "\\enskip" to "0.5em",
            "\\negmedspace" to "-0.222em",
            "\\negthickspace" to "-0.277em"
        )

        dimensions.forEach { (command, expectedDimension) ->
            val node = assertIs<LatexNode.HSpace>(parser.parse(command).children.single())
            assertEquals(expectedDimension, node.dimension, command)
        }
    }

    @Test
    fun should_parse_escaped_space_before_superscript_without_unknown_command() {
        val parser = LatexParser()

        val result = parser.parse("–10\\ ^\\circ\\mathrm{C}.")
        val children = result.children

        assertFalse(children.any { it is LatexNode.Command && it.name.isEmpty() })

        val degree = children.firstOrNull { it is LatexNode.Superscript }
        assertIs<LatexNode.Superscript>(degree)
        assertIs<LatexNode.Space>(degree.base)
        val exponent = assertIs<LatexNode.Symbol>(degree.exponent)
        assertEquals("circ", exponent.symbol)
    }
}
