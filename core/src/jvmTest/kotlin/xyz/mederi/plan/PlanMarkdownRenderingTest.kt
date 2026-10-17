package xyz.mederi.plan

import xyz.mederi.domain.model.AgentMode
import java.io.File
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PlanStore.buildMarkdown Part 1 渲染行为锁定（子任务 0）：
 * - User Review 段彻底移除：字段有值也不渲染
 * - background + problemAnalysis + expectedOutcome 合并为 `## 目标（Goal）` 单段（任一非空才渲染）
 * - `## 实现方案（Approach）` 无条件渲染（空也出段头，让缺失可见）
 * - `## 验证计划（Verification Plan）`（原 测试方法）条件渲染：testMethod 非空才出段
 */
class PlanMarkdownRenderingTest {

    private val tmpDir = File.createTempFile("mederi-plan-render-test", "").apply {
        delete(); mkdirs()
        File(this, ".mederi/plans").mkdirs()
    }
    private val planStore = PlanStore(listOf(tmpDir.absolutePath))

    private fun render(plan: Plan): String {
        planStore.save(plan)
        return File(tmpDir, ".mederi/plans/${plan.id}/plan.md").readText()
    }

    private fun makePlan(
        id: String = "plan_${UUID.randomUUID().toString().take(8)}",
        background: String = "",
        problemAnalysis: String = "",
        approach: String = "",
        expectedOutcome: String = "",
        testMethod: String = "",
        userReviewRequired: List<String> = emptyList(),
    ): Plan = Plan(
        id = id,
        title = "t",
        sessionId = "sess_test",
        background = background,
        problemAnalysis = problemAnalysis,
        approach = approach,
        expectedOutcome = expectedOutcome,
        testMethod = testMethod,
        userReviewRequired = userReviewRequired,
        subtasks = emptyList(),
        createdAt = "2026-10-01T00:00:00Z",
        agentMode = AgentMode.APPROVAL
    )

    @AfterTest
    fun cleanup() {
        tmpDir.deleteRecursively()
    }

    @Test
    fun `minimal plan renders goal and approach but omits empty sections`() {
        val plan = makePlan(background = "从零搭建一个 Kotlin 服务", approach = "用 Ktor 实现核心 API")
        val md = render(plan)
        assertTrue(md.contains("## 目标（Goal）"))
        assertTrue(md.contains("## 实现方案（Approach）"))
        assertTrue(md.contains(plan.background))
        assertFalse(md.contains("## 需要你确认"))
        assertFalse(md.contains("User Review Required"))
        assertFalse(md.contains("## 背景（Background）"))
        assertFalse(md.contains("## 问题/需求分析"))
        assertFalse(md.contains("## 预期结果"))
        assertFalse(md.contains("## 验证计划（Verification Plan）"))
        assertFalse(md.contains("## 风险"))
        assertFalse(md.contains("## 成功标准"))
        assertFalse(md.contains("## 范围"))
    }

    @Test
    fun `full plan merges background problem analysis and expected outcome under goal`() {
        val plan = makePlan(
            background = "背景：现有系统缺少权限模块",
            problemAnalysis = "问题分析：接口无鉴权导致越权风险",
            expectedOutcome = "预期结果：所有接口均需鉴权通过",
            testMethod = "运行 gradle test 并断言权限用例"
        )
        val md = render(plan)
        assertTrue(md.contains("## 目标（Goal）"))
        assertTrue(md.contains(plan.background))
        assertTrue(md.contains(plan.problemAnalysis))
        assertTrue(md.contains(plan.expectedOutcome))
        assertTrue(md.contains("## 验证计划（Verification Plan）"))
        assertFalse(md.contains("## 测试方法"))
        assertFalse(md.contains("## 需要你确认"))
    }

    @Test
    fun `approach section renders unconditionally even when empty`() {
        val plan = makePlan(approach = "")
        val md = render(plan)
        assertTrue(md.contains("## 实现方案（Approach）"))
    }

    @Test
    fun `user review section is never rendered even when field is populated`() {
        val plan = makePlan(userReviewRequired = listOf("某破坏性项"))
        val md = render(plan)
        assertFalse(md.contains("## 需要你确认"))
    }
}
