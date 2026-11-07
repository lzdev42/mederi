package xyz.emuci.markdown.parser

import xyz.emuci.markdown.parser.ast.*
import xyz.emuci.markdown.parser.block.BlockParser
import xyz.emuci.markdown.parser.core.SourceText
import xyz.emuci.markdown.parser.html.HtmlRenderer
import kotlin.test.*

class FrontMatterFallbackTest {
    private val report = """
        ---

        ## 子代理执行能力测试报告

        全部 64 个测试通过。

        | 子任务 | 结果 |
        |---|---|
        | Lexer | PASS |

        - 正文中的列表也必须保留

        ```python
        print("ALL_TESTS_PASSED")
        ```

        最后结论不能消失。
    """.trimIndent()

    private fun assertReport(document: Document) {
        assertIs<ThematicBreak>(document.children.first())
        assertTrue(document.children.any { it is Heading })
        assertTrue(document.children.any { it is Table })
        assertTrue(document.children.any { it is ListBlock })
        assertTrue(document.children.any { it is FencedCodeBlock })
        val html = HtmlRenderer.render(document)
        assertTrue(html.contains("子代理执行能力测试报告"))
        assertTrue(html.contains("全部 64 个测试通过。"))
        assertTrue(html.contains("最后结论不能消失。"))
    }

    @Test
    fun leadingSeparatorPreservesEntireReportAndOriginalLineNumbers() {
        val doc = MarkdownParser().parse(report)
        assertReport(doc)
        assertEquals(2, doc.children.filterIsInstance<Heading>().single().lineRange.startLine)
        assertEquals(0, doc.children.first().lineRange.startLine)
    }

    @Test
    fun unclosedTomlPreservesMarkerAndBodyAsOrdinaryMarkdown() {
        val doc = MarkdownParser().parse("+++\n\n# Visible heading\n\nVisible ending")
        assertTrue(doc.children.none { it is FrontMatter })
        val html = HtmlRenderer.render(doc)
        assertTrue(html.contains("+++"))
        assertTrue(html.contains("Visible heading"))
        assertTrue(html.contains("Visible ending"))
    }

    @Test
    fun closedYamlAndTomlStillKeepMetadataSeparateFromBody() {
        for (marker in listOf("---", "+++")) {
            val doc = MarkdownParser().parse("$marker\ntitle: Example\n$marker\n\n# Visible body")
            assertEquals("title: Example", assertIs<FrontMatter>(doc.children.first()).literal)
            assertTrue(doc.children.any { it is Heading })
            assertTrue(HtmlRenderer.render(doc).contains("Visible body"))
        }
    }

    @Test
    fun boundedAndLegacyBlockParsingDoNotConsumeUnclosedMetadata() {
        // 闭合符在 parseLines 的范围之外，不能据此吞掉所选范围内的标题。
        val source = SourceText.of("---\n# Visible heading\n---\nAfter metadata")
        val bounded = BlockParser(source).parseLines(0, 2)
        assertIs<ThematicBreak>(bounded.first())
        assertIs<Heading>(bounded.last())
        val legacy = BlockParser(SourceText.of(report)).parse()
        assertIs<ThematicBreak>(legacy.children.first())
        assertTrue(legacy.children.any { it is Table })
        assertTrue(legacy.children.any { it is FencedCodeBlock })
    }

    @Test
    fun streamingUnclosedReportKeepsBodyDuringAndAfterStreaming() {
        for (chunkSize in listOf(1, 7, 64)) {
            val parser = MarkdownParser()
            parser.beginStream()
            report.chunked(chunkSize).forEach { parser.append(it) }
            assertReport(parser.document)
            assertReport(parser.endStream())
        }
    }

    @Test
    fun laterClosingMarkerReclassifiesPreviouslyVisibleMarkdownAsMetadata() {
        for (marker in listOf("---", "+++")) {
            val parser = MarkdownParser()
            parser.beginStream()
            parser.append("$marker\ntitle: Example\n\nauthor: Example\n")
            assertTrue(parser.document.children.none { it is FrontMatter })
            // 闭合符逐字符到达，不能被尾部增量解析误当普通分隔线或段落。
            marker.forEach { parser.append(it.toString()) }
            assertIs<FrontMatter>(parser.document.children.first())
            parser.append("\n\n# Visible body")
            assertIs<FrontMatter>(parser.document.children.first())
            assertTrue(HtmlRenderer.render(parser.document).contains("Visible body"))
            assertIs<FrontMatter>(parser.endStream().children.first())
        }
    }

    @Test
    fun editingClosingMarkerRestoresAndReclassifiesCompleteBody() {
        for (marker in listOf("---", "+++")) {
            val text = "$marker\ntitle: Example\n$marker\n\n# Visible body"
            val closingOffset = text.indexOf(marker, marker.length)
            val parser = MarkdownParser()
            parser.parse(text)
            parser.delete(closingOffset, marker.length)
            assertTrue(parser.document.children.none { it is FrontMatter })
            assertTrue(HtmlRenderer.render(parser.document).contains("Visible body"))
            parser.insert(closingOffset, marker)
            assertIs<FrontMatter>(parser.document.children.first())
            assertTrue(HtmlRenderer.render(parser.document).contains("Visible body"))
        }
    }

    @Test
    fun ordinaryBodyAndIndentedDelimitersArePreserved() {
        val doc = MarkdownParser().parse("---\n\n---\n\nBody after empty metadata")
        assertIs<FrontMatter>(doc.children.first())
        assertTrue(HtmlRenderer.render(doc).contains("Body after empty metadata"))
        val indented = MarkdownParser().parse("    ---\n\n# Visible body")
        assertIs<IndentedCodeBlock>(indented.children.first())
        assertTrue(HtmlRenderer.render(indented).contains("Visible body"))
    }
}
