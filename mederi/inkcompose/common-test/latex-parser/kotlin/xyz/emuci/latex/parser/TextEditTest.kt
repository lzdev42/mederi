package xyz.emuci.latex.parser

import xyz.emuci.latex.parser.incremental.TextEdit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextEditTest {

    // ========== diff 基本功能 ==========

    @Test
    fun diff_identicalStrings_returnsZeroEdit() {
        val edit = TextEdit.diff("hello", "hello")
        assertEquals(0, edit.startOffset)
        assertEquals(0, edit.oldEndOffset)
        assertEquals(0, edit.newEndOffset)
        assertEquals(0, edit.delta)
    }

    @Test
    fun diff_emptyToNonEmpty_returnsFullInsertion() {
        val edit = TextEdit.diff("", "abc")
        assertEquals(0, edit.startOffset)
        assertEquals(0, edit.oldEndOffset)
        assertEquals(3, edit.newEndOffset)
        assertTrue(edit.isInsertion)
    }

    @Test
    fun diff_nonEmptyToEmpty_returnsFullDeletion() {
        val edit = TextEdit.diff("abc", "")
        assertEquals(0, edit.startOffset)
        assertEquals(3, edit.oldEndOffset)
        assertEquals(0, edit.newEndOffset)
        assertTrue(edit.isDeletion)
    }

    @Test
    fun diff_appendAtEnd() {
        val edit = TextEdit.diff("abc", "abcdef")
        assertEquals(3, edit.startOffset)
        assertEquals(3, edit.oldEndOffset)
        assertEquals(6, edit.newEndOffset)
        assertTrue(edit.isInsertion)
        assertEquals(3, edit.delta)
    }

    @Test
    fun diff_insertInMiddle() {
        val edit = TextEdit.diff("abcdef", "abcXYZdef")
        assertEquals(3, edit.startOffset)
        assertEquals(3, edit.oldEndOffset)
        assertEquals(6, edit.newEndOffset)
        assertTrue(edit.isInsertion)
        assertEquals(3, edit.delta)
    }

    @Test
    fun diff_deleteFromMiddle() {
        val edit = TextEdit.diff("abcXYZdef", "abcdef")
        assertEquals(3, edit.startOffset)
        assertEquals(6, edit.oldEndOffset)
        assertEquals(3, edit.newEndOffset)
        assertTrue(edit.isDeletion)
        assertEquals(-3, edit.delta)
    }

    @Test
    fun diff_replaceInMiddle() {
        val edit = TextEdit.diff("abcdef", "abcXYdef")
        // 公共前缀: "abc" = 3, 公共后缀: "def" = 3
        // 旧: [3,3) → 空; 新: [3,5) → "XY" → 这是纯插入
        assertEquals(3, edit.startOffset)
        assertEquals(3, edit.oldEndOffset)
        assertEquals(5, edit.newEndOffset)
        assertTrue(edit.isInsertion)
        assertEquals(2, edit.delta)
    }

    @Test
    fun diff_replaceAtStart() {
        val edit = TextEdit.diff("abcdef", "XYcdef")
        assertEquals(0, edit.startOffset)
        assertEquals(2, edit.oldEndOffset)
        assertEquals(2, edit.newEndOffset)
        assertEquals(0, edit.delta)
    }

    @Test
    fun diff_replaceAtEnd() {
        val edit = TextEdit.diff("abcdef", "abcdXY")
        assertEquals(4, edit.startOffset)
        assertEquals(6, edit.oldEndOffset)
        assertEquals(6, edit.newEndOffset)
        assertEquals(0, edit.delta)
    }

    // ========== fromAppend ==========

    @Test
    fun fromAppend_correctEditForAppendScenario() {
        val edit = TextEdit.fromAppend(5, 10)
        assertEquals(5, edit.startOffset)
        assertEquals(5, edit.oldEndOffset)
        assertEquals(10, edit.newEndOffset)
        assertTrue(edit.isInsertion)
        assertEquals(5, edit.delta)
    }

    // ========== LaTeX 场景 ==========

    @Test
    fun diff_latexAppendCharByChar() {
        // 模拟逐字符输入 \frac
        var text = "\\fra"
        val edit = TextEdit.diff(text, text + "c")
        assertEquals(4, edit.startOffset)
        assertEquals(4, edit.oldEndOffset)
        assertEquals(5, edit.newEndOffset)
        assertEquals(1, edit.delta)
    }

    @Test
    fun diff_latexInsertInBrace() {
        // 在 \frac{}{b} 的第一个花括号中插入 a
        val edit = TextEdit.diff("\\frac{}{b}", "\\frac{a}{b}")
        assertEquals(6, edit.startOffset)
        assertEquals(6, edit.oldEndOffset)
        assertEquals(7, edit.newEndOffset)
        assertEquals(1, edit.delta)
    }

    @Test
    fun diff_latexDeleteCommand() {
        // 删除 \alpha → 剩下 x+y
        val edit = TextEdit.diff("x+\\alpha y", "x+y")
        assertEquals(2, edit.startOffset)
        assertEquals(9, edit.oldEndOffset)
        assertEquals(2, edit.newEndOffset)
    }
}
