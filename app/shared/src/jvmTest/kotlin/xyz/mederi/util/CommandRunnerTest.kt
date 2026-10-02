package xyz.mederi.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 外部命令执行助手的超时行为单测。
 *
 * 背景：`defaultAppNameFor`（xdg-mime / assoc+ftype）与 `revealInFolder` 走 `runCommand*`，
 * 2026-10 之前等待无超时——UI 线程（VM 异步化之前）可能被挂死的外部命令冻结。
 * 这里锁死两条最小契约：超时命令在限定时间内返回 null（而不是无限挂等），快命令正常出结果。
 */
class CommandRunnerTest {

    @Test
    fun longRunningCommandIsKilledWithinTimeout() {
        val start = System.nanoTime()
        val result = runCommand(listOf("/bin/sleep", "5"), timeoutMillis = 300)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertNull(result, "超时命令必须返回 null")
        assertTrue(
            elapsedMs < 3_000,
            "300ms 超时的命令应在 3s 内返回（实测 ${elapsedMs}ms，证明不是等了完整 5s）"
        )
    }

    @Test
    fun quickCommandReturnsTrimmedOutput() {
        val out = runCommand(listOf("/bin/echo", "hello world"), timeoutMillis = 3_000)
        assertEquals("hello world", out, "正常快命令应返回 stdout 的 trim 文本")
    }

    @Test
    fun missingCommandReturnsNullWithoutThrowing() {
        val out = runCommand(listOf("/nonexistent-binary-xyz", "a"), timeoutMillis = 3_000)
        assertNull(out)
    }

    @Test
    fun exitCodeVariantAlsoHonorsTimeout() {
        val start = System.nanoTime()
        val code = runCommandExitCode(listOf("/bin/sleep", "5"), timeoutMillis = 300)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertNull(code, "超时命令的 exit code 应为 null")
        assertTrue(elapsedMs < 3_000, "exit code 变体同样受超时约束（实测 ${elapsedMs}ms）")
    }

    @Test
    fun exitCodeVariantReturnsCodeForQuickCommand() {
        val code = runCommandExitCode(listOf("/bin/sh", "-c", "exit 42"), timeoutMillis = 3_000)
        assertNotNull(code)
        assertEquals(42, code)
    }
}
