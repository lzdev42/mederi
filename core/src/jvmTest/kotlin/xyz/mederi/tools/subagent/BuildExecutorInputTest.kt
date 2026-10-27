package xyz.mederi.tools.subagent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [SubagentRunnerImpl.buildExecutorInput] 确定性测试。
 *
 * 验证：
 * - Plan 路径：spec 原样传递，task 不进输入，briefing 只含 planDetail 定位信息。
 * - Ad-hoc 路径：task + briefing 都是执行器输入。
 * - 两条路径的边界情况（空 briefing、空 task）。
 */
class BuildExecutorInputTest {

    @Test
    fun planPath_includesOnlySpec_notTask_notBriefing() {
        val spec = "1. Add function foo() to Bar.kt\n2. Wire foo() into Baz.kt"
        val briefing = "Brief: Implement foo for subtask 0"
        val task = "Execute subtask 0"

        val input = SubagentRunnerImpl.buildExecutorInput(task, briefing, spec)

        assertTrue(input.contains("Spec checklist to execute (top-down):"), "plan path must include spec header")
        assertTrue(input.contains(spec), "plan path must include spec content verbatim")
        assertFalse(input.contains(task), "plan path must NOT include task — task is identifier only")
        assertFalse(input.contains(briefing), "plan path must NOT include briefing — planDetail can conflict with spec")
    }

    @Test
    fun planPath_withoutBriefing_includesOnlySpec() {
        val spec = "1. Change X to Y"
        val input = SubagentRunnerImpl.buildExecutorInput("Execute subtask 1", null, spec)

        assertTrue(input.contains(spec))
        assertTrue(input.startsWith("Spec checklist"), "no briefing → spec is first content")
    }

    @Test
    fun adHocPath_includesTaskAndBriefing() {
        val task = "Fix the typo in README.md line 42"
        val briefing = "The word 'recieve' should be 'receive'"

        val input = SubagentRunnerImpl.buildExecutorInput(task, briefing, null)

        assertTrue(input.contains(task), "ad-hoc path must include task")
        assertTrue(input.contains(briefing), "ad-hoc path must include briefing")
        assertFalse(input.contains("Spec checklist"), "ad-hoc path must not have spec header")
    }

    @Test
    fun adHocPath_withoutBriefing_includesOnlyTask() {
        val task = "Fix typo in README.md: recieve → receive"
        val input = SubagentRunnerImpl.buildExecutorInput(task, null, null)

        assertEquals(task, input, "ad-hoc with no briefing → input = task only")
    }

    @Test
    fun planPath_doesNotIncludeBriefingContent() {
        // Plan path must exclude ALL briefing content — not just the label
        val briefing = "Brief: some processing logic that could conflict with spec"
        val input = SubagentRunnerImpl.buildExecutorInput("task", briefing, "spec content")
        assertFalse(input.contains(briefing), "plan path must not include any briefing content")
        assertFalse(input.contains("Brief:"), "plan path must not include Brief: prefix")
        assertFalse(input.contains("Additional info:"), "plan path must not use ad-hoc briefing label")
    }

    @Test
    fun adHocPath_usesAdditionalInfoLabel() {
        val input = SubagentRunnerImpl.buildExecutorInput("task", "extra context", null)
        assertTrue(input.contains("Additional info:"), "ad-hoc path uses 'Additional info:' label for briefing")
    }
}
