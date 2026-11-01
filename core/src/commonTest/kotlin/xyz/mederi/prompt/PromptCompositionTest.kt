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
        assertTrue(prompt.contains("# Input Verification"), "主代理应包含输入验证原则")
        assertTrue(prompt.contains("do not correct"), "主代理应包含证据门槛——无证据不纠正")
    }

    @Test
    fun executor_hasIndependentPrompt() {
        val prompt = SystemPrompts.forSubagent(SubagentRole.EXECUTOR)
        assertTrue(prompt.contains("Executor Subagent"), "Executor 应包含独立身份标识")
        assertTrue(prompt.contains("## Plan-based execution"), "Executor 应区分计划执行规则")
        assertTrue(prompt.contains("SPEC_FEEDBACK"), "Executor 应包含 SPEC_FEEDBACK 格式")
        assertTrue(prompt.contains("Do NOT explore the codebase broadly"), "计划执行器应包含读码边界约束")
        // 5 类 SPEC_FEEDBACK 类别必须全部出现
        assertTrue(prompt.contains("SPEC_FEEDBACK(missing)"), "Executor 应包含 missing 类别")
        assertTrue(prompt.contains("SPEC_FEEDBACK(ambiguous)"), "Executor 应包含 ambiguous 类别")
        assertTrue(prompt.contains("SPEC_FEEDBACK(vs-reality)"), "Executor 应包含 vs-reality 类别")
        assertTrue(prompt.contains("SPEC_FEEDBACK(unsatisfiable)"), "Executor 应包含 unsatisfiable 类别")
        assertTrue(prompt.contains("SPEC_FEEDBACK(compile-blocked)"), "Executor 应包含 compile-blocked 类别")
        // 编译限制只适用于计划；无计划的 Office/研究等交付不需要编译。
        assertTrue(prompt.contains("## Ad-hoc execution (no stored spec)"))
        assertTrue(prompt.contains("No compile command is required\nfor non-software work"))
        assertTrue(prompt.contains("do not apply plan-only", ignoreCase = true))
        // 计划执行器不得自行发现编译命令——适用的命令由 spec 指定
        assertTrue(prompt.contains("do NOT discover or select a build command"), "Executor 不得自行发现编译命令")
        assertFalse(prompt.contains("# General Principles"), "Executor 不应包含通用原则（主代理的）")
        assertFalse(prompt.contains("# Coding Principles"), "Executor 不应包含编码原则（主代理的）")
        assertFalse(prompt.contains("do NOT transcribe"), "Executor 不应包含旧 spec 转录约束")
    }

    @Test
    fun researcher_containsGeneralPrinciplesOnly() {
        val prompt = SystemPrompts.forSubagent(SubagentRole.RESEARCHER)
        assertTrue(prompt.contains("# General Principles"), "Researcher 应包含通用原则")
        assertFalse(prompt.contains("# Coding Principles"), "只读 Researcher 不应包含编码原则")
        assertFalse(prompt.contains("Minimal surgical edits"), "只读 Researcher 不应包含修改代码原则")
    }
}
