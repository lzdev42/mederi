package xyz.emuci.latex.parser

import xyz.emuci.latex.parser.model.LatexNode
import xyz.emuci.latex.parser.tokenizer.LatexToken
import xyz.emuci.latex.parser.tokenizer.LatexTokenizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PrimeSyntaxTest {
    @Test
    fun should_tokenize_prime_separately_from_text() {
        val tokens = LatexTokenizer("f''").tokenize()

        assertEquals(4, tokens.size)
        assertIs<LatexToken.Text>(tokens[0])
        assertIs<LatexToken.Prime>(tokens[1])
        assertIs<LatexToken.Prime>(tokens[2])
        assertIs<LatexToken.EOF>(tokens[3])
    }

    @Test
    fun should_parse_consecutive_primes_as_one_superscript() {
        val document = LatexParser().parse("f''")
        val superscript = assertIs<LatexNode.Superscript>(document.children.single())
        val primes = assertIs<LatexNode.Group>(superscript.exponent)

        assertEquals("f", assertIs<LatexNode.Text>(superscript.base).content)
        assertEquals(2, primes.children.size)
        primes.children.forEach { prime ->
            assertEquals("prime", assertIs<LatexNode.Symbol>(prime).symbol)
        }
    }

    @Test
    fun should_keep_invisible_left_delimiter_separate_from_content() {
        val delimited = LatexParser().parse("\\left.U(x)\\right|").children.single()

        assertEquals("", assertIs<LatexNode.Delimited>(delimited).left)
        assertEquals("U", assertIs<LatexNode.Text>(delimited.content.first()).content)
    }

    @Test
    fun should_preserve_apostrophe_in_text_mode() {
        val texts = listOf("\\text{don't}" to "don't", "\\text{'quoted'}" to "'quoted'")

        texts.forEach { (latex, expected) ->
            val text = LatexParser().parse(latex).children.single()
            assertEquals(expected, assertIs<LatexNode.TextMode>(text).text)
        }
    }

    @Test
    fun should_merge_prime_and_explicit_superscript() {
        val superscript = LatexParser().parse("f'^2").children.single()
        val exponent = assertIs<LatexNode.Group>(assertIs<LatexNode.Superscript>(superscript).exponent)

        assertEquals(2, exponent.children.size)
        assertEquals("prime", assertIs<LatexNode.Symbol>(exponent.children[0]).symbol)
        assertEquals("2", assertIs<LatexNode.Text>(exponent.children[1]).content)
    }

    @Test
    fun should_report_prime_after_explicit_superscript() {
        val invalid = listOf("f^2'", "f'_i^2")

        invalid.forEach { latex ->
            val result = LatexParser().parseWithDiagnostics(latex)
            assertTrue(result.errors.any { diagnostic -> diagnostic.message == "Double superscript" })
        }
    }

    @Test
    fun should_merge_adjacent_prime_and_superscript_after_subscript() {
        val superscript = LatexParser().parse("f_i'^2").children.single()
        val exponent = assertIs<LatexNode.Group>(assertIs<LatexNode.Superscript>(superscript).exponent)

        assertEquals(2, exponent.children.size)
    }
}
