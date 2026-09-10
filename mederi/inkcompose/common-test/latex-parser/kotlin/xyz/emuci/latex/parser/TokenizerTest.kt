package xyz.emuci.latex.parser

import xyz.emuci.latex.parser.tokenizer.LatexToken
import xyz.emuci.latex.parser.tokenizer.LatexTokenizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TokenizerTest {
    
    @Test
    fun testSimpleText() {
        val tokenizer = LatexTokenizer("hello")
        val tokens = tokenizer.tokenize()
        
        assertEquals(2, tokens.size) // text + EOF
        assertTrue(tokens[0] is LatexToken.Text)
        assertTrue(tokens[1] is LatexToken.EOF)
    }
    
    @Test
    fun testCommand() {
        val tokenizer = LatexTokenizer("\\alpha")
        val tokens = tokenizer.tokenize()
        
        assertTrue(tokens[0] is LatexToken.Command)
        assertEquals("alpha", (tokens[0] as LatexToken.Command).name)
    }
    
    @Test
    fun testBraces() {
        val tokenizer = LatexTokenizer("{}")
        val tokens = tokenizer.tokenize()
        
        assertTrue(tokens[0] is LatexToken.LeftBrace)
        assertTrue(tokens[1] is LatexToken.RightBrace)
    }
    
    @Test
    fun testBrackets() {
        val tokenizer = LatexTokenizer("[]")
        val tokens = tokenizer.tokenize()
        
        assertTrue(tokens[0] is LatexToken.LeftBracket)
        assertTrue(tokens[1] is LatexToken.RightBracket)
    }
    
    @Test
    fun testSuperscript() {
        val tokenizer = LatexTokenizer("x^2")
        val tokens = tokenizer.tokenize()
        
        assertTrue(tokens.any { it is LatexToken.Superscript })
    }
    
    @Test
    fun testSubscript() {
        val tokenizer = LatexTokenizer("x_i")
        val tokens = tokenizer.tokenize()
        
        assertTrue(tokens.any { it is LatexToken.Subscript })
    }
    
    @Test
    fun testAmpersand() {
        val tokenizer = LatexTokenizer("a & b")
        val tokens = tokenizer.tokenize()
        
        assertTrue(tokens.any { it is LatexToken.Ampersand })
    }
    
    @Test
    fun testBeginEnvironment() {
        val tokenizer = LatexTokenizer("\\begin{matrix}")
        val tokens = tokenizer.tokenize()
        
        assertTrue(tokens[0] is LatexToken.BeginEnvironment)
        assertEquals("matrix", (tokens[0] as LatexToken.BeginEnvironment).name)
    }
    
    @Test
    fun testEndEnvironment() {
        val tokenizer = LatexTokenizer("\\end{matrix}")
        val tokens = tokenizer.tokenize()
        
        assertTrue(tokens[0] is LatexToken.EndEnvironment)
        assertEquals("matrix", (tokens[0] as LatexToken.EndEnvironment).name)
    }
    
    @Test
    fun testNewLine() {
        val tokenizer = LatexTokenizer("\\\\")
        val tokens = tokenizer.tokenize()
        
        assertTrue(tokens[0] is LatexToken.NewLine)
    }
    
    @Test
    fun testWhitespace() {
        val tokenizer = LatexTokenizer("a b")
        val tokens = tokenizer.tokenize()
        
        assertTrue(tokens.any { it is LatexToken.Whitespace })
    }
    
    @Test
    fun testComplexExpression() {
        val tokenizer = LatexTokenizer("\\frac{a}{b}")
        val tokens = tokenizer.tokenize()
        
        // \frac { a } { b } EOF
        assertTrue(tokens[0] is LatexToken.Command)
        assertTrue(tokens[1] is LatexToken.LeftBrace)
        assertTrue(tokens[2] is LatexToken.Text)
        assertTrue(tokens[3] is LatexToken.RightBrace)
        assertTrue(tokens[4] is LatexToken.LeftBrace)
        assertTrue(tokens[5] is LatexToken.Text)
        assertTrue(tokens[6] is LatexToken.RightBrace)
        assertTrue(tokens[7] is LatexToken.EOF)
    }

    @Test
    fun testDisplayMathBracketsAreTokenizedAsMathShift() {
        val tokenizer = LatexTokenizer("\\[x+y\\]")
        val tokens = tokenizer.tokenize()

        val mathShifts = tokens.filterIsInstance<LatexToken.MathShift>()
        assertEquals(2, mathShifts.size)
        assertTrue(mathShifts.all { it.count == 2 })
    }
    
    @Test
    fun testMultipleCommands() {
        val tokenizer = LatexTokenizer("\\alpha\\beta")
        val tokens = tokenizer.tokenize()
        
        val commands = tokens.filterIsInstance<LatexToken.Command>()
        assertEquals(2, commands.size)
        assertEquals("alpha", commands[0].name)
        assertEquals("beta", commands[1].name)
    }
    
    @Test
    fun testEscapedCharacters() {
        val tokenizer = LatexTokenizer("\\{ \\}")
        val tokens = tokenizer.tokenize()
        
        assertTrue(tokens[0] is LatexToken.Command)
        assertTrue(tokens[2] is LatexToken.Command)
    }
    
    @Test
    fun testEmptyString() {
        val tokenizer = LatexTokenizer("")
        val tokens = tokenizer.tokenize()
        
        assertEquals(1, tokens.size)
        assertTrue(tokens[0] is LatexToken.EOF)
    }
    
    @Test
    fun testMixedContent() {
        val tokenizer = LatexTokenizer("E = mc^2")
        val tokens = tokenizer.tokenize()
        
        assertTrue(tokens.any { it is LatexToken.Text })
        assertTrue(tokens.any { it is LatexToken.Superscript })
        assertTrue(tokens.last() is LatexToken.EOF)
    }

    @Test
    fun testOperatorsWithoutSpacesAreTokenizedSeparately() {
        val tokenizer = LatexTokenizer("-x^2-2ax-a,x<0")
        val tokens = tokenizer.tokenize()
        val contents = tokens.filterIsInstance<LatexToken.Text>().map { it.content }

        assertEquals(listOf("-", "x", "2", "-", "2ax", "-", "a", ",", "x", "<", "0"), contents)
        assertTrue(tokens.any { it is LatexToken.Superscript })
    }

    // ========== 不断开空格 (~) ==========

    @Test
    fun testTildeAsNonBreakingSpace() {
        val tokenizer = LatexTokenizer("a~b")
        val tokens = tokenizer.tokenize()

        assertEquals(4, tokens.size) // text + whitespace + text + EOF
        assertTrue(tokens[0] is LatexToken.Text)
        assertTrue(tokens[1] is LatexToken.Whitespace)
        assertEquals("\u00A0", (tokens[1] as LatexToken.Whitespace).content)
        assertTrue(tokens[2] is LatexToken.Text)
    }

    @Test
    fun testMultipleTildes() {
        val tokenizer = LatexTokenizer("a~~b")
        val tokens = tokenizer.tokenize()

        val wsTokens = tokens.filterIsInstance<LatexToken.Whitespace>()
        assertEquals(2, wsTokens.size)
    }

    @Test
    fun testEscapedSpaceIsTokenizedAsSpaceCommand() {
        val tokenizer = LatexTokenizer("–10\\ ^\\circ")
        val tokens = tokenizer.tokenize()

        val commands = tokens.filterIsInstance<LatexToken.Command>()
        assertEquals(listOf(" ", "circ"), commands.map { it.name })
        assertTrue(tokens.none { it is LatexToken.Whitespace && it.range.start == 4 && it.range.end == 5 })
    }

    // ========== 注释处理 (%) ==========

    @Test
    fun testCommentIgnoresRestOfLine() {
        val tokenizer = LatexTokenizer("a % comment\nb")
        val tokens = tokenizer.tokenize()

        val textTokens = tokens.filterIsInstance<LatexToken.Text>()
        assertEquals(2, textTokens.size)
        assertEquals("a", textTokens[0].content)
        assertEquals("b", textTokens[1].content)
    }

    @Test
    fun testCommentAtEndOfInput() {
        val tokenizer = LatexTokenizer("hello % end comment")
        val tokens = tokenizer.tokenize()

        val textTokens = tokens.filterIsInstance<LatexToken.Text>()
        assertEquals(1, textTokens.size)
        assertEquals("hello", textTokens[0].content)
    }

    @Test
    fun testLineWithOnlyComment() {
        val tokenizer = LatexTokenizer("% only comment")
        val tokens = tokenizer.tokenize()

        val textTokens = tokens.filterIsInstance<LatexToken.Text>()
        assertEquals(0, textTokens.size)
    }
}
