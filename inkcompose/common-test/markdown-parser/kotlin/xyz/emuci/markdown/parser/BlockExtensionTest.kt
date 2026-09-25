package xyz.emuci.markdown.parser

import xyz.emuci.markdown.parser.ast.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// ────── 分页符 (PageBreak) 测试 ──────

class PageBreakTest {

    private val parser = MarkdownParser()

    @Test
    fun should_parse_pagebreak() {
        val doc = parser.parse("***pagebreak***")
        val node = doc.children.first()
        assertIs<PageBreak>(node)
    }

    @Test
    fun should_parse_pagebreak_case_insensitive() {
        val doc = parser.parse("***PAGEBREAK***")
        val node = doc.children.first()
        assertIs<PageBreak>(node)
    }

    @Test
    fun should_parse_pagebreak_mixed_case() {
        val doc = parser.parse("***PageBreak***")
        val node = doc.children.first()
        assertIs<PageBreak>(node)
    }

    @Test
    fun should_parse_pagebreak_with_leading_spaces() {
        val doc = parser.parse("   ***pagebreak***")
        val node = doc.children.first()
        assertIs<PageBreak>(node)
    }

    @Test
    fun should_not_parse_pagebreak_with_extra_text() {
        val doc = parser.parse("***pagebreak*** extra")
        val node = doc.children.first()
        assertIs<Paragraph>(node)
    }

    @Test
    fun should_not_parse_partial_pagebreak() {
        val doc = parser.parse("**pagebreak**")
        val node = doc.children.first()
        assertIs<Paragraph>(node)
    }

    @Test
    fun should_parse_pagebreak_between_paragraphs() {
        val doc = parser.parse("Before\n\n***pagebreak***\n\nAfter")
        assertEquals(3, doc.children.size)
        assertIs<Paragraph>(doc.children[0])
        assertIs<PageBreak>(doc.children[1])
        assertIs<Paragraph>(doc.children[2])
    }

    @Test
    fun should_interrupt_paragraph_with_pagebreak() {
        val doc = parser.parse("Before\n***pagebreak***\nAfter")
        val nodes = doc.children.toList()
        assertTrue(nodes.any { it is PageBreak })
    }
}

// ────── TOC 高级配置测试 ──────

class TocAdvancedConfigTest {

    private val parser = MarkdownParser()

    @Test
    fun should_parse_basic_toc() {
        val doc = parser.parse("[TOC]")
        val toc = doc.children.first()
        assertIs<TocPlaceholder>(toc)
        assertEquals(1, toc.minDepth)
        assertEquals(6, toc.maxDepth)
        assertEquals("asc", toc.order)
        assertTrue(toc.excludeIds.isEmpty())
    }

    @Test
    fun should_parse_toc_with_depth_range() {
        val doc = parser.parse("[TOC]\n:depth=2-4")
        val toc = doc.children.first()
        assertIs<TocPlaceholder>(toc)
        assertEquals(2, toc.minDepth)
        assertEquals(4, toc.maxDepth)
    }

    @Test
    fun should_parse_toc_with_single_depth() {
        val doc = parser.parse("[TOC]\n:depth=3")
        val toc = doc.children.first()
        assertIs<TocPlaceholder>(toc)
        assertEquals(1, toc.minDepth)
        assertEquals(3, toc.maxDepth)
    }

    @Test
    fun should_parse_toc_with_exclude() {
        val doc = parser.parse("[TOC]\n:exclude=#ignore,#skip")
        val toc = doc.children.first()
        assertIs<TocPlaceholder>(toc)
        assertEquals(listOf("ignore", "skip"), toc.excludeIds)
    }

    @Test
    fun should_parse_toc_with_order_desc() {
        val doc = parser.parse("[TOC]\n:order=desc")
        val toc = doc.children.first()
        assertIs<TocPlaceholder>(toc)
        assertEquals("desc", toc.order)
    }

    @Test
    fun should_parse_toc_with_multiple_configs() {
        val doc = parser.parse("[TOC]\n:depth=2-4\n:exclude=#ignore\n:order=asc")
        val toc = doc.children.first()
        assertIs<TocPlaceholder>(toc)
        assertEquals(2, toc.minDepth)
        assertEquals(4, toc.maxDepth)
        assertEquals(listOf("ignore"), toc.excludeIds)
        assertEquals("asc", toc.order)
    }

    @Test
    fun should_parse_toc_bracket_style() {
        val doc = parser.parse("[[toc]]")
        val toc = doc.children.first()
        assertIs<TocPlaceholder>(toc)
    }

    @Test
    fun should_not_parse_toc_with_non_config_lines() {
        val doc = parser.parse("[TOC]\nsome text")
        val node = doc.children.first()
        assertIs<Paragraph>(node)
    }

    @Test
    fun should_parse_toc_with_exclude_without_hash() {
        val doc = parser.parse("[TOC]\n:exclude=myid")
        val toc = doc.children.first()
        assertIs<TocPlaceholder>(toc)
        assertEquals(listOf("myid"), toc.excludeIds)
    }
}

// ────── 多列布局 (ColumnsLayout) 测试 ──────

class ColumnsLayoutTest {

    private val parser = MarkdownParser()

    @Test
    fun should_parse_basic_columns_layout() {
        val input = """
            :::columns
            :::column
            左列内容
            :::column
            右列内容
            :::
        """.trimIndent()
        val doc = parser.parse(input)
        val layout = doc.children.first()
        assertIs<ColumnsLayout>(layout)
        assertEquals(2, layout.children.size)
        assertIs<ColumnItem>(layout.children[0])
        assertIs<ColumnItem>(layout.children[1])
    }

    @Test
    fun should_parse_columns_with_width() {
        val input = """
            :::columns
            :::column "width=30%"
            左列
            :::column "width=70%"
            右列
            :::
        """.trimIndent()
        val doc = parser.parse(input)
        val layout = doc.children.first()
        assertIs<ColumnsLayout>(layout)
        val col1 = layout.children[0]
        val col2 = layout.children[1]
        assertIs<ColumnItem>(col1)
        assertIs<ColumnItem>(col2)
        assertEquals("30%", col1.width)
        assertEquals("70%", col2.width)
    }

    @Test
    fun should_parse_three_columns() {
        val input = """
            :::columns
            :::column
            列1
            :::column
            列2
            :::column
            列3
            :::
        """.trimIndent()
        val doc = parser.parse(input)
        val layout = doc.children.first()
        assertIs<ColumnsLayout>(layout)
        assertEquals(3, layout.children.size)
    }

    @Test
    fun should_parse_columns_with_rich_content() {
        val input = """
            :::columns
            :::column
            # 标题
            
            一段文字。
            :::column
            - 项目一
            - 项目二
            :::
        """.trimIndent()
        val doc = parser.parse(input)
        val layout = doc.children.first()
        assertIs<ColumnsLayout>(layout)
        assertEquals(2, layout.children.size)
        val col1 = layout.children[0] as ColumnItem
        assertTrue(col1.children.isNotEmpty())
    }

    @Test
    fun should_not_convert_non_columns_container() {
        val input = """
            :::warning
            这不是列布局
            :::
        """.trimIndent()
        val doc = parser.parse(input)
        val container = doc.children.first()
        assertIs<CustomContainer>(container)
    }

    @Test
    fun should_parse_empty_columns() {
        val input = """
            :::columns
            :::
        """.trimIndent()
        val doc = parser.parse(input)
        val layout = doc.children.first()
        assertIs<ColumnsLayout>(layout)
        assertEquals(0, layout.children.size)
    }

    @Test
    fun should_handle_content_before_first_column() {
        val input = """
            :::columns
            前置内容
            :::column
            列内容
            :::
        """.trimIndent()
        val doc = parser.parse(input)
        val layout = doc.children.first()
        assertIs<ColumnsLayout>(layout)
        // 前置内容应被包装在隐式列中
        assertTrue(layout.children.size >= 2)
    }
}
