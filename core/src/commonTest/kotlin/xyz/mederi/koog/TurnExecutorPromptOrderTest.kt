package xyz.mederi.koog

import kotlin.test.Test
import kotlin.test.assertTrue
import xyz.mederi.domain.model.AgentMode
import xyz.mederi.project.AgentsFileLoader
import xyz.mederi.prompt.SystemPrompts

/**
 * TurnExecutor 系统提示词装配顺序的契约测试。
 *
 * 装配顺序不变量 = 静态骨架 → skills → AGENTS.md → 动态 plan/todo 后缀。
 * 本测试纯函数复刻 TurnExecutor 的装配序列，锁定 AGENTS.md 段必须在动态段之前
 * （OpenAI 按最长前缀缓存，动态内容必须永远在最后才不破坏缓存前缀）。
 */
class TurnExecutorPromptOrderTest {

    @Test
    fun planSection_comesAfterProjectInstructions() {
        // 与 TurnExecutor 装配顺序一一对应：
        val base = SystemPrompts.build(AgentMode.APPROVAL)
        val withSkills = SystemPrompts.withSkills(base, emptyList())
        val agentsFiles = listOf(
            AgentsFileLoader.AgentsFile(
                path = "AGENTS.md",
                relativePath = "AGENTS.md",
                content = "## Root Rules\n- rule"
            )
        )
        val withRules = SystemPrompts.withProjectRules(withSkills, agentsFiles)
        val planContent = "# Active Plan\n- subtask 1"
        val full = withRules + SystemPrompts.dynamicSuffix(planContent, null)

        val projectIdx = full.indexOf("# Project Instructions (AGENTS.md)")
        val planIdx = full.indexOf("# Active Plan")
        assertTrue(projectIdx in 0..full.length, "AGENTS.md 段必须存在")
        assertTrue(projectIdx > 0, "AGENTS.md 段不能在最开头")
        assertTrue(planIdx > 0, "Active Plan 段必须存在")
        assertTrue(projectIdx < planIdx, "AGENTS.md 段必须在 Active Plan 段之前（缓存前缀不变量）")
    }

    @Test
    fun todoSection_comesAfterProjectInstructions() {
        val base = SystemPrompts.build(AgentMode.APPROVAL)
        val withSkills = SystemPrompts.withSkills(base, emptyList())
        val agentsFiles = listOf(
            AgentsFileLoader.AgentsFile(
                path = "AGENTS.md",
                relativePath = "AGENTS.md",
                content = "## Root Rules\n- rule"
            )
        )
        val withRules = SystemPrompts.withProjectRules(withSkills, agentsFiles)
        val full = withRules + SystemPrompts.dynamicSuffix(null, "- t")

        val projectIdx = full.indexOf("# Project Instructions (AGENTS.md)")
        val todoIdx = full.indexOf("# Current Todo")
        assertTrue(projectIdx in 0..full.length, "AGENTS.md 段必须存在")
        assertTrue(todoIdx > 0, "Current Todo 段必须存在")
        assertTrue(projectIdx < todoIdx, "AGENTS.md 段必须在 Current Todo 段之前（缓存前缀不变量）")
    }
}
