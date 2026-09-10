package xyz.emuci.latex.parser

import xyz.emuci.latex.parser.model.LatexNode
import xyz.emuci.latex.parser.visitor.BaseLatexVisitor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VisitorTest {

    private val parser = LatexParser()

    /**
     * 计数访问者 - 统计各类型节点的数量
     */
    private class CountingVisitor : BaseLatexVisitor<Unit>() {
        val counts = mutableMapOf<String, Int>()

        override fun defaultVisit(node: LatexNode) {
            val typeName = node::class.simpleName ?: "Unknown"
            counts[typeName] = counts.getOrPut(typeName) { 0 } + 1
        }
    }

    /**
     * 文本提取访问者 - 提取所有文本内容
     */
    private class TextExtractorVisitor : BaseLatexVisitor<String>() {
        private val texts = mutableListOf<String>()

        override fun defaultVisit(node: LatexNode): String {
            return ""
        }

        override fun visitText(node: LatexNode.Text): String {
            texts.add(node.content)
            return node.content
        }

        fun getAllTexts(): List<String> = texts
    }

    /**
     * 深度计算访问者 - 计算AST的最大深度
     */
    private class DepthCalculatorVisitor : BaseLatexVisitor<Int>() {
        override fun defaultVisit(node: LatexNode): Int = 0

        override fun visitDocument(node: LatexNode.Document): Int {
            return 1 + (node.children.maxOfOrNull { visit(it) } ?: 0)
        }

        override fun visitGroup(node: LatexNode.Group): Int {
            return 1 + (node.children.maxOfOrNull { visit(it) } ?: 0)
        }

        override fun visitFraction(node: LatexNode.Fraction): Int {
            return 1 + maxOf(visit(node.numerator), visit(node.denominator))
        }

        override fun visitRoot(node: LatexNode.Root): Int {
            val indexDepth = node.index?.let { visit(it) } ?: 0
            return 1 + maxOf(visit(node.content), indexDepth)
        }
    }

    @Test
    fun testCountingVisitor() {
        val doc = parser.parse("\\frac{a}{b}")
        val visitor = CountingVisitor()

        visitor.visit(doc)

        assertTrue(visitor.counts["Fraction"] ?: 0 > 0)
        assertTrue(visitor.counts["Group"] ?: 0 > 0)
    }

    @Test
    fun testTextExtractor() {
        val doc = parser.parse("hello world")
        val visitor = TextExtractorVisitor()

        visitor.visit(doc)
        val texts = visitor.getAllTexts()

        assertTrue(texts.contains("hello"))
        assertTrue(texts.contains("world"))
    }

    @Test
    fun testTextExtractorInFraction() {
        val doc = parser.parse("\\frac{abc}{xyz}")
        val visitor = TextExtractorVisitor()

        visitor.visit(doc)
        val texts = visitor.getAllTexts()

        assertTrue(texts.contains("abc"))
        assertTrue(texts.contains("xyz"))
    }

    @Test
    fun testDepthCalculator() {
        val doc = parser.parse("hello")
        val visitor = DepthCalculatorVisitor()

        val depth = visitor.visit(doc)
        assertTrue(depth >= 1)
    }

    @Test
    fun testDepthWithNesting() {
        val doc = parser.parse("\\frac{a}{b}")
        val visitor = DepthCalculatorVisitor()

        val depth = visitor.visit(doc)
        assertTrue(depth >= 2)
    }

    @Test
    fun testDepthWithDeepNesting() {
        val doc = parser.parse("\\frac{\\frac{a}{b}}{c}")
        val visitor = DepthCalculatorVisitor()

        val depth = visitor.visit(doc)
        assertTrue(depth >= 3)
    }

    @Test
    fun testCountNodeTypes() {
        val doc = parser.parse("\\frac{1}{2} + \\frac{3}{4}")
        val visitor = CountingVisitor()

        visitor.visit(doc)

        // 应该有2个分数节点
        assertEquals(2, visitor.counts["Fraction"] ?: 0)
    }

    @Test
    fun testCountSymbols() {
        val doc = parser.parse("\\alpha + \\beta + \\gamma")
        val visitor = CountingVisitor()

        visitor.visit(doc)

        // 应该有3个符号节点
        assertEquals(3, visitor.counts["Symbol"] ?: 0)
    }

    @Test
    fun testVisitMatrix() {
        val doc = parser.parse(
            """
            \begin{matrix}
            a & b \\
            c & d
            \end{matrix}
        """.trimIndent()
        )

        val visitor = CountingVisitor()
        visitor.visit(doc)

        assertTrue(visitor.counts["Matrix"] ?: 0 > 0)
    }

    @Test
    fun testVisitEnvironment() {
        val doc = parser.parse(
            """
            \begin{equation}
            E = mc^2
            \end{equation}
        """.trimIndent()
        )

        val visitor = CountingVisitor()
        visitor.visit(doc)

        assertTrue(visitor.counts["Environment"] ?: 0 > 0)
    }

    @Test
    fun testVisitComplexExpression() {
        val doc = parser.parse("\\sum_{i=1}^{n} \\frac{1}{i}")
        val visitor = CountingVisitor()

        visitor.visit(doc)

        assertTrue(visitor.counts["BigOperator"] ?: 0 > 0)
        assertTrue(visitor.counts["Fraction"] ?: 0 > 0)
    }
}
