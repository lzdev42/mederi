package xyz.emuci.markdown.parser

import xyz.emuci.markdown.parser.ast.*
import xyz.emuci.markdown.parser.lint.DiagnosticCode
import xyz.emuci.markdown.parser.lint.DiagnosticSeverity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 语法验证/Linting 功能测试。
 */
class LintingTest {

    private fun parseWithLinting(input: String): MarkdownParser {
        val parser = MarkdownParser(enableLinting = true)
        parser.parse(input)
        return parser
    }

    // ────── 标题层级跳跃检测 ──────

    @Test
    fun should_detect_heading_level_skip_h1_to_h3() {
        val parser = parseWithLinting("""
            # Title
            
            ### Skipped h2
        """.trimIndent())

        val diagnostics = parser.diagnostics
        assertTrue(diagnostics.hasIssues)
        val levelSkips = diagnostics.filter(DiagnosticCode.HEADING_LEVEL_SKIP)
        assertEquals(1, levelSkips.size)
        assertEquals(DiagnosticSeverity.WARNING, levelSkips[0].severity)
        assertTrue(levelSkips[0].message.contains("h1"))
        assertTrue(levelSkips[0].message.contains("h3"))
    }

    @Test
    fun should_detect_multiple_heading_level_skips() {
        val parser = parseWithLinting("""
            # Title
            
            #### Skipped h2 and h3
            
            ###### Skipped h5
        """.trimIndent())

        val diagnostics = parser.diagnostics
        val levelSkips = diagnostics.filter(DiagnosticCode.HEADING_LEVEL_SKIP)
        assertEquals(2, levelSkips.size)
    }

    @Test
    fun should_not_report_consecutive_headings() {
        val parser = parseWithLinting("""
            # Title
            
            ## Section
            
            ### Subsection
        """.trimIndent())

        val diagnostics = parser.diagnostics
        val levelSkips = diagnostics.filter(DiagnosticCode.HEADING_LEVEL_SKIP)
        assertEquals(0, levelSkips.size)
    }

    @Test
    fun should_allow_decreasing_heading_levels() {
        val parser = parseWithLinting("""
            # Title
            
            ## Section
            
            ### Subsection
            
            ## Another Section
        """.trimIndent())

        val diagnostics = parser.diagnostics
        val levelSkips = diagnostics.filter(DiagnosticCode.HEADING_LEVEL_SKIP)
        assertEquals(0, levelSkips.size)
    }

    // ────── 重复标题 ID 检测 ──────

    @Test
    fun should_detect_duplicate_heading_ids() {
        val parser = parseWithLinting("""
            # Hello
            
            ## Hello
        """.trimIndent())

        val diagnostics = parser.diagnostics
        val duplicates = diagnostics.filter(DiagnosticCode.DUPLICATE_HEADING_ID)
        assertEquals(1, duplicates.size)
        assertEquals(DiagnosticSeverity.WARNING, duplicates[0].severity)
        // 检测基于 slug，不是最终 ID（最终 ID 可能被 de-duplicate 为 hello-1）
        assertTrue(duplicates[0].message.contains("hello"))
    }

    @Test
    fun should_not_report_unique_headings() {
        val parser = parseWithLinting("""
            # First
            
            ## Second
            
            ### Third
        """.trimIndent())

        val diagnostics = parser.diagnostics
        val duplicates = diagnostics.filter(DiagnosticCode.DUPLICATE_HEADING_ID)
        assertEquals(0, duplicates.size)
    }

    // ────── 脚注引用检测 ──────

    @Test
    fun should_detect_invalid_footnote_reference() {
        val parser = parseWithLinting("""
            Text with a missing footnote[^missing].
        """.trimIndent())

        val diagnostics = parser.diagnostics
        val invalids = diagnostics.filter(DiagnosticCode.INVALID_FOOTNOTE_REFERENCE)
        assertEquals(1, invalids.size)
        assertEquals(DiagnosticSeverity.ERROR, invalids[0].severity)
        assertTrue(invalids[0].message.contains("missing"))
    }

    @Test
    fun should_detect_unused_footnote_definition() {
        val parser = parseWithLinting("""
            Text without any footnote references.
            
            [^unused]: This footnote is never referenced.
        """.trimIndent())

        val diagnostics = parser.diagnostics
        val unused = diagnostics.filter(DiagnosticCode.UNUSED_FOOTNOTE_DEFINITION)
        assertEquals(1, unused.size)
        assertEquals(DiagnosticSeverity.WARNING, unused[0].severity)
        assertTrue(unused[0].message.contains("unused"))
    }

    @Test
    fun should_not_report_matched_footnotes() {
        val parser = parseWithLinting("""
            Text with a valid footnote[^1].
            
            [^1]: This is properly referenced.
        """.trimIndent())

        val diagnostics = parser.diagnostics
        val invalids = diagnostics.filter(DiagnosticCode.INVALID_FOOTNOTE_REFERENCE)
        val unused = diagnostics.filter(DiagnosticCode.UNUSED_FOOTNOTE_DEFINITION)
        assertEquals(0, invalids.size)
        assertEquals(0, unused.size)
    }

    // ────── 空链接检测 ──────

    @Test
    fun should_detect_empty_link_destination() {
        val parser = parseWithLinting("""
            [click here]()
        """.trimIndent())

        val diagnostics = parser.diagnostics
        val empties = diagnostics.filter(DiagnosticCode.EMPTY_LINK_DESTINATION)
        assertEquals(1, empties.size)
    }

    // ────── 无诊断情况 ──────

    @Test
    fun should_produce_no_diagnostics_for_clean_document() {
        val parser = parseWithLinting("""
            # Welcome
            
            ## Getting Started
            
            This is a clean document with **bold** and *italic* text.
            
            - Item 1
            - Item 2
        """.trimIndent())

        val diagnostics = parser.diagnostics
        assertFalse(diagnostics.hasIssues)
        assertEquals(0, diagnostics.errorCount)
        assertEquals(0, diagnostics.warningCount)
    }

    // ────── Linting 关闭时不产生诊断 ──────

    @Test
    fun should_not_produce_diagnostics_when_linting_disabled() {
        val parser = MarkdownParser(enableLinting = false)
        parser.parse("""
            # Title
            
            ### Skipped h2
        """.trimIndent())

        val diagnostics = parser.diagnostics
        assertFalse(diagnostics.hasIssues)
    }

    // ────── 诊断结果排序 ──────

    @Test
    fun should_sort_diagnostics_by_line() {
        val parser = parseWithLinting("""
            # Title
            
            #### Skipped levels
            
            Text[^missing]
            
            [^unused]: Unused footnote
        """.trimIndent())

        val diagnostics = parser.diagnostics
        assertTrue(diagnostics.hasIssues)
        val lines = diagnostics.diagnostics.map { it.line }
        assertEquals(lines.sorted(), lines)
    }

    // ────── 综合诊断 ──────

    @Test
    fun should_detect_multiple_issue_types() {
        val parser = parseWithLinting("""
            # Title
            
            #### Skipped h2 and h3
            
            # Title
            
            See footnote[^ref].
        """.trimIndent())

        val diagnostics = parser.diagnostics
        assertTrue(diagnostics.hasIssues)
        assertTrue(diagnostics.filter(DiagnosticCode.HEADING_LEVEL_SKIP).isNotEmpty())
        assertTrue(diagnostics.filter(DiagnosticCode.DUPLICATE_HEADING_ID).isNotEmpty())
        assertTrue(diagnostics.filter(DiagnosticCode.INVALID_FOOTNOTE_REFERENCE).isNotEmpty())
    }

    // ────── Document 上的诊断信息 ──────

    @Test
    fun should_attach_diagnostics_to_document() {
        val parser = MarkdownParser(enableLinting = true)
        val doc = parser.parse("""
            # Title
            
            ### Skipped
        """.trimIndent())

        assertTrue(doc.diagnostics.hasIssues)
        assertEquals(parser.diagnostics.diagnostics, doc.diagnostics.diagnostics)
    }
}
