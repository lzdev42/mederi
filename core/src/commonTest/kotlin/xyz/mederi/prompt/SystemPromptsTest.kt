package xyz.mederi.prompt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.mederi.domain.model.AgentMode

/**
 * SystemPrompts.build / dynamicSuffix 行为测试：
 * 验证静态前缀不含动态段，动态段互斥规则正确，缓存前缀不变量成立。
 */
class SystemPromptsTest {

    @Test
    fun build_doesNotContainDynamicSections() {
        val prompt = SystemPrompts.build(AgentMode.APPROVAL)
        assertFalse(prompt.contains("# Active Plan"), "build() 不应包含 Active Plan 段")
        assertFalse(prompt.contains("# Current Todo"), "build() 不应包含 Current Todo 段")
    }

    @Test
    fun dynamicSuffix_appendPlan() {
        val result = SystemPrompts.dynamicSuffix("## Plan\n- s1", null)
        assertTrue(result.contains("# Active Plan"), "有 plan 时应包含 Active Plan 段")
        assertTrue(result.contains("## Plan"), "应包含 plan 内容")
        assertFalse(result.contains("# Current Todo"), "有 plan 时不应包含 Current Todo 段")
    }

    @Test
    fun dynamicSuffix_appendTodo() {
        val result = SystemPrompts.dynamicSuffix(null, "- item")
        assertTrue(result.contains("# Current Todo"), "无 plan 有 todo 时应包含 Current Todo 段")
        assertTrue(result.contains("- item"), "应包含 todo 内容")
        assertFalse(result.contains("# Active Plan"), "无 plan 时不应包含 Active Plan 段")
    }

    @Test
    fun dynamicSuffix_mutualExclusion() {
        val result = SystemPrompts.dynamicSuffix("plan", "- todo")
        assertTrue(result.contains("# Active Plan"), "有 plan 时应挂 plan")
        assertFalse(result.contains("# Current Todo"), "互斥：有 plan 就不挂 todo")
    }

    @Test
    fun dynamicSuffix_bothNull_returnsEmpty() {
        assertEquals("", SystemPrompts.dynamicSuffix(null, null))
    }

    @Test
    fun dynamicSuffix_blankTodo_returnsEmpty() {
        assertEquals("", SystemPrompts.dynamicSuffix(null, "  "))
    }
}
