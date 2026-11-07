package xyz.mederi.prompt

import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun onlyMainAgentPromptsRequireRepliesBeforeToolsAndUpdatesAfterAnnouncedSteps() {
        val heading = "## Respond before acting"
        AgentMode.entries.forEach { mode ->
            val prompt = SystemPrompts.build(mode)
            assertTrue(prompt.contains(heading))
            assertTrue(prompt.contains("including one received while working"))
            assertTrue(prompt.contains("before the next tool call"))
            assertTrue(prompt.contains("report that outcome before moving on"))
        }
        listOf(SubagentRole.EXECUTOR, SubagentRole.RESEARCHER).forEach { role ->
            assertFalse(SystemPrompts.forSubagent(role).contains(heading))
        }
    }

    @Test
    fun evidenceGuidanceReachesEveryTurnRoleWithoutDuplicatingIt() {
        val prompts = AgentMode.entries.map { SystemPrompts.build(it) } +
            listOf(SubagentRole.EXECUTOR, SubagentRole.RESEARCHER).map { SystemPrompts.forSubagent(it) }
        val guidance = GeneralPrompts.EVIDENCE_AND_CAUSAL_REASONING
        prompts.forEach { prompt ->
            val start = prompt.indexOf(guidance)
            assertTrue(start >= 0, "每个角色都应收到完整证据与因果推理引导")
            assertEquals(-1, prompt.indexOf(guidance, start + guidance.length), "同一角色不重复挂载引导")
            if (prompt.contains(GeneralPrompts.PRINCIPLES)) {
                assertTrue(prompt.indexOf(GeneralPrompts.PRINCIPLES) < start)
            }
        }
    }

    @Test
    fun everyMainModeIncludesPlanApproachAndPreDispatchReview() {
        AgentMode.entries.forEach { mode ->
            val prompt = SystemPrompts.build(mode)
            assertTrue(prompt.contains(PromptGuides.PLAN_OVERVIEW_GUIDE))
            assertTrue(prompt.contains(PromptGuides.PLAN_APPENDIX_GUIDE))
            assertTrue(prompt.contains(PromptGuides.SPEC_WRITING_GUIDE))
            assertTrue(prompt.indexOf("## Mandatory pre-dispatch self-check") < prompt.indexOf("# Plan Loop —"))
        }
    }

    @Test
    fun allTurnRolesGroupReadyOperationsAndOnlyMainAgentsGroupPlanCalls() {
        val mainPrompts = AgentMode.entries.map { SystemPrompts.build(it) }
        val subagentPrompts = listOf(SubagentRole.EXECUTOR, SubagentRole.RESEARCHER).map { SystemPrompts.forSubagent(it) }
        (mainPrompts + subagentPrompts).forEach { prompt ->
            assertTrue(prompt.contains("put them in one batch and reason over the results"))
            assertTrue(prompt.contains("Use ordinary parallel tool calls in the same response"))
            assertTrue(prompt.contains("successful results and revisit only what is missing"))
        }
        mainPrompts.forEach { prompt ->
            assertTrue(prompt.contains("independent generate_spec calls together in one response"))
            assertTrue(prompt.contains("Send write_plan alone and wait for specs"))
        }
        subagentPrompts.forEach { prompt ->
            assertFalse(prompt.contains(PromptGuides.PLAN_TOOL_GUIDE))
        }
    }

    @Test
    fun requestCountPolicyReplacesSelectiveReadsForEveryTurnRole() {
        for (enabled in listOf(false, true)) {
            val prompts = AgentMode.entries.map { SystemPrompts.build(it, enabled) } +
                listOf(SubagentRole.EXECUTOR, SubagentRole.RESEARCHER).map { SystemPrompts.forSubagent(it, enabled) }
            prompts.forEach { prompt ->
                assertEquals(enabled, prompt.contains("This model is billed per request"))
                assertEquals(enabled, prompt.contains("Input and output tokens add no cost"))
                assertEquals(enabled, prompt.contains("Read together all files you can already locate"))
                assertEquals(!enabled, prompt.contains("Use the project map and targeted searches to pick files and line ranges"))
                assertTrue(prompt.contains("Keep background commands, overlapping writes, and dependencies out"))
            }
        }
    }

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
        assertTrue(prompt.contains("do not broadly re-explore") || prompt.contains("Do NOT explore"), "计划执行器应包含读码边界约束")
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
