package xyz.mederi.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * ask_user 目标摘要回归测试（ui-summary-no-regex）：
 * extractAskUserSummary 已从正则抠取 "prompt" 改为 JSON 解析（commonMain 经 probeToolTarget 公开入口可达）。
 */
class AskUserSummaryToolTargetTest {

    @Test
    fun `ask_user summary extracts first prompt via JSON parsing`() {
        val questions = """[{"id":"q1","prompt":"选择语言","options":["Kotlin","Java"]},{"id":"q2","prompt":"模块?"}]"""

        assertEquals("选择语言", probeToolTarget("ask_user", mapOf("questions" to questions), null))
    }

    @Test
    fun `ask_user summary falls back to truncated raw text when not a JSON array`() {
        // 非 JSON 文本 → 原文截断（不再用正则从字符串里抠 prompt）
        assertEquals("请确认以下问题", probeToolTarget("ask_user", mapOf("questions" to "请确认以下问题"), null))

        // 合法 JSON 但形态不符（对象）→ 不被当作摘要；复合 JSON 摘要被全局防御拦截 → null
        assertNull(probeToolTarget("ask_user", mapOf("questions" to """{"prompt":"对象形态"}"""), null))
    }

    @Test
    fun `ask_user summary prefers top-level prompt key`() {
        assertEquals(
            "直接说明",
            probeToolTarget("ask_user", mapOf("prompt" to "直接说明", "questions" to """[{"prompt":"数组里的"}]"""), null)
        )
    }

    @Test
    fun `ask_user summary returns null without questions or prompt`() {
        assertNull(probeToolTarget("ask_user", mapOf("task" to "whatever"), null))
    }
}