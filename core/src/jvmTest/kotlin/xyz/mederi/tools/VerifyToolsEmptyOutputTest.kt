package xyz.mederi.tools

import xyz.mederi.plan.Subtask
import xyz.mederi.plan.VerificationSpec
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 规则 2（空产出门禁）直测：shouldRejectEmptyOutputPass + emptyOutputRejectionMessage。
 */
class VerifyToolsEmptyOutputTest {

    private fun subtask(
        executorTouchedFiles: List<String> = emptyList(),
        targetFiles: List<String> = emptyList()
    ) = Subtask(
        index = 0,
        name = "test",
        planDetail = "do something",
        verification = VerificationSpec(command = "true"),
        targetFiles = targetFiles,
        executorTouchedFiles = executorTouchedFiles
    )

    @Test
    fun `empty touched + file targets - reject`() {
        val st = subtask(targetFiles = listOf("src/Main.kt", "build.gradle.kts"))
        assertTrue(shouldRejectEmptyOutputPass(st), "should reject: executorTouchedFiles empty + real file targets")
    }

    @Test
    fun `non-empty touched + file targets - allow`() {
        val st = subtask(
            executorTouchedFiles = listOf("src/Main.kt"),
            targetFiles = listOf("src/Main.kt")
        )
        assertFalse(shouldRejectEmptyOutputPass(st), "executor touched files - not empty, allow")
    }

    @Test
    fun `empty touched + directory targets - exempt`() {
        val st = subtask(targetFiles = listOf("app/shared/src/commonMain/"))
        assertFalse(shouldRejectEmptyOutputPass(st), "directory targets (endsWith /) are gate/exempt")
    }

    @Test
    fun `empty touched + empty targets - allow`() {
        val st = subtask()
        assertFalse(shouldRejectEmptyOutputPass(st), "no targetFiles - nothing to gate on")
    }

    @Test
    fun `empty touched + mixed targets (dir and file) - reject on file`() {
        val st = subtask(targetFiles = listOf("some/dir/", "src/Main.kt"))
        assertTrue(shouldRejectEmptyOutputPass(st), "one file target is enough to trigger gate")
    }

    @Test
    fun `rejection message contains key guidance`() {
        val st = subtask(targetFiles = listOf("src/Main.kt"))
        val msg = emptyOutputRejectionMessage(st)
        assertTrue(msg.contains("src/Main.kt"), "message lists the offending file targets: $msg")
        assertTrue(msg.contains("IMPLEMENTATION"), "message guides to rootCause=IMPLEMENTATION: $msg")
        assertTrue(msg.contains("converge_plan"), "message guides to converge_plan: $msg")
        assertTrue(msg.contains("exempt"), "message notes gate exemption: $msg")
    }
}
