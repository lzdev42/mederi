package xyz.mederi.tools

import xyz.mederi.plan.VerificationSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PlanTools 规则 1 / 规则 4 / 规则 3 硬校验 helper 直测。
 *
 * validatePlan（规则 1/4）与 UpdateVerificationTool（规则 3）的判定逻辑已抽成
 * PlanTools companion 内的 4 个 internal helper（同 module 可见），测试直接调 helper，
 * 无需构造完整 PlanTools 实例（planStore/eventBus/审批器）：
 * - checkLiteralRequired：空 expectStdoutContains → 返错误文本；非空 → null
 * - checkParallelizableModuleOverlap：两 parallelizable 子任务同 gradle 模块 → 报错；异模块 → 空
 * - isVerificationDowngrade：丢命令段 / 减字面量 → true；都保持 → false
 * - reasonIndicatesDecisionChange：含 decision/changed 等关键词 → true；纯 "race" → false
 */
class PlanToolsValidationTest {

    private fun subtask(
        name: String = "st",
        targetFiles: List<String> = emptyList(),
        parallelizable: Boolean = false,
        expectStdoutContains: List<String> = emptyList()
    ) = PlanTools.SubtaskArg(
        name = name,
        verification = "./gradlew :core:build",
        verificationExpected = "BUILD SUCCESSFUL",
        targetFiles = targetFiles,
        parallelizable = parallelizable,
        verificationExpectStdoutContains = expectStdoutContains
    )

    // ===== 规则 1：验证必钉 ≥1 字面量 =====

    @Test
    fun `checkLiteralRequired returns error when expectStdoutContains is empty`() {
        val err = PlanTools.checkLiteralRequired(subtask(expectStdoutContains = emptyList()))
        assertNotNull(err, "empty literals must be rejected")
        assertTrue(err!!.contains("REQUIRED"), "error must state the requirement: $err")
        assertTrue(err.contains("verificationExpectStdoutContains"), "error must name the field: $err")
    }

    @Test
    fun `checkLiteralRequired passes when literals are pinned`() {
        assertNull(PlanTools.checkLiteralRequired(subtask(expectStdoutContains = listOf("PASS"))))
    }

    // ===== 规则 4：parallelizable 同模块 reject =====

    @Test
    fun `parallelizable subtasks sharing a gradle module are rejected`() {
        val errors = PlanTools.checkParallelizableModuleOverlap(listOf(
            subtask(name = "a", targetFiles = listOf("core/src/main/kotlin/A.kt"), parallelizable = true),
            subtask(name = "b", targetFiles = listOf("core/src/main/kotlin/B.kt"), parallelizable = true)
        ))
        assertEquals(1, errors.size, "one pair, one error: $errors")
        assertTrue(errors[0].contains("(a)"), "error must name subtask 0: ${errors[0]}")
        assertTrue(errors[0].contains("(b)"), "error must name subtask 1: ${errors[0]}")
        assertTrue(errors[0].contains("core"), "error must name the shared module: ${errors[0]}")
    }

    @Test
    fun `parallelizable subtasks in different modules pass`() {
        val errors = PlanTools.checkParallelizableModuleOverlap(listOf(
            subtask(name = "a", targetFiles = listOf("core/src/main/kotlin/A.kt"), parallelizable = true),
            subtask(name = "b", targetFiles = listOf("inkcompose/src/main/kotlin/B.kt"), parallelizable = true)
        ))
        assertTrue(errors.isEmpty(), "different modules must not conflict: $errors")
    }

    @Test
    fun `non-parallelizable subtasks sharing a module pass`() {
        val errors = PlanTools.checkParallelizableModuleOverlap(listOf(
            subtask(name = "a", targetFiles = listOf("core/src/main/kotlin/A.kt"), parallelizable = false),
            subtask(name = "b", targetFiles = listOf("core/src/main/kotlin/B.kt"), parallelizable = true)
        ))
        assertTrue(errors.isEmpty(), "only parallelizable pairs conflict: $errors")
    }

    // ===== 规则 3：修订降级检测 =====

    @Test
    fun `dropping the gradlew clause is a downgrade`() {
        val old = VerificationSpec(
            command = "./gradlew :core:build",
            expectStdoutContains = listOf("BUILD SUCCESSFUL")
        )
        val new = VerificationSpec(
            command = "echo done",
            expectStdoutContains = listOf("BUILD SUCCESSFUL")
        )
        assertTrue(PlanTools.isVerificationDowngrade(old, new))
    }

    @Test
    fun `reducing literal count is a downgrade`() {
        val old = VerificationSpec(
            command = "./gradlew :core:build",
            expectStdoutContains = listOf("a", "b")
        )
        val new = VerificationSpec(
            command = "./gradlew :core:build",
            expectStdoutContains = listOf("a")
        )
        assertTrue(PlanTools.isVerificationDowngrade(old, new))
    }

    @Test
    fun `keeping clauses and literals is not a downgrade`() {
        val old = VerificationSpec(
            command = "./gradlew :core:build",
            expectStdoutContains = listOf("a")
        )
        val new = VerificationSpec(
            command = "./gradlew :core:build",
            expectStdoutContains = listOf("a", "b")
        )
        assertFalse(PlanTools.isVerificationDowngrade(old, new))
    }

    @Test
    fun `reason with decision keywords passes rule 3`() {
        assertTrue(PlanTools.reasonIndicatesDecisionChange("Decision restated: task disposition changed"))
        assertTrue(PlanTools.reasonIndicatesDecisionChange("changed the verification strategy"))
        // 纯 "race"（无关键词）→ 不构成决策变更
        assertFalse(PlanTools.reasonIndicatesDecisionChange("race"))
    }
}
