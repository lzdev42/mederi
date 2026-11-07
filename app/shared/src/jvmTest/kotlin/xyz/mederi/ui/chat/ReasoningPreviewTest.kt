package xyz.mederi.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReasoningPreviewTest {
    @Test
    fun `preview follows newest reasoning instead of initial paragraph`() {
        val text = "old reasoning ".repeat(100) + "latest observation"
        val preview = reasoningPreview(text)
        assertTrue(preview.endsWith("latest observation"))
        assertTrue(preview.length <= 240)
    }

    @Test
    fun `multiline and missing reasoning stay within a single summary line`() {
        assertEquals("检查 文件 然后验证", reasoningPreview(" 检查\n文件\t然后验证 "))
        assertEquals("", reasoningPreview("  \n\t"))
        assertFalse(reasoningPreview("first\r\nlast").contains('\n'))
    }
}
