package xyz.mederi

import xyz.mederi.core.contract.models.PastedTextAttachment
import xyz.mederi.util.PromptComposer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromptComposerTest {

    @Test
    fun testIsLargeText() {
        assertFalse(PromptComposer.isLargeText("Hello world"))
        assertFalse(PromptComposer.isLargeText("Line 1\nLine 2\nLine 3"))

        // 800 字符阈值
        val longString = "a".repeat(800)
        assertTrue(PromptComposer.isLargeText(longString))

        // 15 行阈值
        val manyLines = (1..15).joinToString("\n") { "line $it" }
        assertTrue(PromptComposer.isLargeText(manyLines))
    }

    @Test
    fun testComposeEmptyPastedTexts() {
        val instruction = "请分析这段代码"
        val result = PromptComposer.compose(instruction, emptyList())
        assertEquals("请分析这段代码", result)
    }

    @Test
    fun testComposeWithPastedTexts() {
        val instruction = "帮我看看报错"
        val pastedList = listOf(
            PastedTextAttachment(
                id = "p1",
                index = 1,
                text = "Exception in thread main java.lang.NullPointerException",
                lineCount = 1,
                charCount = 55
            ),
            PastedTextAttachment(
                id = "p2",
                index = 2,
                text = "line 1\nline 2\nline 3",
                lineCount = 3,
                charCount = 20
            )
        )
        val composed = PromptComposer.compose(instruction, pastedList)

        // 检查指令在开头
        assertTrue(composed.startsWith("帮我看看报错"))
        // 检查包含分隔线与说明
        assertTrue(composed.contains("The following are text sections pasted by the user for context:"))
        // 检查包含 index 1 和 index 2
        assertTrue(composed.contains("<pasted_text index=\"1\""))
        assertTrue(composed.contains("Exception in thread main java.lang.NullPointerException"))
        assertTrue(composed.contains("<pasted_text index=\"2\""))
        assertTrue(composed.contains("line 1\nline 2\nline 3"))
    }

    @Test
    fun testParseRoundTrip() {
        val originalInstruction = "请帮我重构这个函数"
        val pasted = listOf(
            PastedTextAttachment(
                id = "p1",
                index = 1,
                text = "fun oldCode() {\n    println(\"hello\")\n}",
                lineCount = 3,
                charCount = 37
            )
        )
        val composed = PromptComposer.compose(originalInstruction, pasted)
        val parsed = PromptComposer.parse(composed)

        assertEquals(originalInstruction, parsed.instruction)
        assertEquals(1, parsed.pastedTexts.size)
        assertEquals(1, parsed.pastedTexts[0].index)
        assertEquals(pasted[0].text, parsed.pastedTexts[0].text)
    }

    @Test
    fun testParsePureInstruction() {
        val text = "这是普通的一句提问"
        val parsed = PromptComposer.parse(text)
        assertEquals("这是普通的一句提问", parsed.instruction)
        assertTrue(parsed.pastedTexts.isEmpty())
    }

    @Test
    fun testStripUserIntervention() {
        val rawIntervention = """
            <user_intervention>
            [System Note: The user submitted the following guidance while you were executing tools. Incorporate this guidance into your ongoing task without restarting from scratch]:
            任务卡死了，你重调一下
            </user_intervention>
        """.trimIndent()

        println("[Test-Log] rawIntervention:\n$rawIntervention")
        val stripped = PromptComposer.stripUserIntervention(rawIntervention)
        println("[Test-Log] stripped: '$stripped'")
        assertEquals("任务卡死了，你重调一下", stripped)

        val mixedWithHidden = """
            <user_intervention>
            [System Note: The user submitted the following guidance while you were executing tools. Incorporate this guidance into your ongoing task without restarting from scratch]:
            换个目录重试
            </user_intervention>
            <<<NOT_FOR_UI>>>
            [2026-10-02 11:00:00 UTC]
        """.trimIndent()

        val sanitized = PromptComposer.sanitizeUserVisibleText(mixedWithHidden)
        println("[Test-Log] sanitized: '$sanitized'")
        assertEquals("换个目录重试", sanitized)

        val parsed = PromptComposer.parse(rawIntervention)
        println("[Test-Log] parsed instruction: '${parsed.instruction}'")
        assertEquals("任务卡死了，你重调一下", parsed.instruction)
    }
}
