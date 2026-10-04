package xyz.mederi.prompt

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.mederi.domain.model.AgentMode
import xyz.mederi.domain.model.SubagentRole

/**
 * 提示词分层拼装与编码纪律验证测试。
 * 验证通用原则与编码原则的 SSOT 拼装逻辑。
 */
class PromptCompositionTest {

    @Test
    fun mainAgent_containsGeneralAndCodingPrinciples() {
        val prompt = SystemPrompts.build(AgentMode.APPROVAL)
        assertTrue(prompt.contains("# General Principles"), "主代理应包含通用原则")
        assertTrue(prompt.contains("# Coding Principles"), "主代理应包含编码原则")
        assertTrue(prompt.contains("Fix root causes, never suppress symptoms"), "主代理应强化根因解决，严禁掩耳盗铃")
        assertTrue(prompt.contains("Minimal surgical edits"), "主代理应包含最小化修改原则")
    }

    @Test
    fun executor_containsGeneralAndCodingPrinciples() {
        val prompt = SystemPrompts.forSubagent(SubagentRole.EXECUTOR)
        assertTrue(prompt.contains("# General Principles"), "Executor 应包含通用原则")
        assertTrue(prompt.contains("# Coding Principles"), "Executor 应包含编码原则")
        assertTrue(prompt.contains("Fix root causes, never suppress symptoms"), "Executor 应强化根因解决")
        assertTrue(prompt.contains("Minimal surgical edits"), "Executor 应包含最小化修改原则")
    }

    @Test
    fun researcher_containsGeneralPrinciplesOnly() {
        val prompt = SystemPrompts.forSubagent(SubagentRole.RESEARCHER)
        assertTrue(prompt.contains("# General Principles"), "Researcher 应包含通用原则")
        assertFalse(prompt.contains("# Coding Principles"), "只读 Researcher 不应包含编码原则")
        assertFalse(prompt.contains("Minimal surgical edits"), "只读 Researcher 不应包含修改代码原则")
    }
}
