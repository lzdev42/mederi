package xyz.mederi.tools

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * verifyCommandOutcome 三态判定单测：
 * exit 0 → PASS；非零非超时 → FAIL；timedOut（exitCode 任意）→ TIMEOUT。
 */
class VerifyCommandOutcomeTest {

    @Test
    fun exitZeroIsPass() {
        assertEquals(VerifyCommandOutcome.PASS, verifyCommandOutcome(exitCode = 0, timedOut = false))
    }

    @Test
    fun nonZeroIsFail() {
        assertEquals(VerifyCommandOutcome.FAIL, verifyCommandOutcome(exitCode = 1, timedOut = false))
        assertEquals(VerifyCommandOutcome.FAIL, verifyCommandOutcome(exitCode = 42, timedOut = false))
    }

    @Test
    fun timedOutIsTimeoutRegardlessOfExitCode() {
        // 超时返回处 exitCode 固定为 -1
        assertEquals(VerifyCommandOutcome.TIMEOUT, verifyCommandOutcome(exitCode = -1, timedOut = true))
        // timedOut 优先于 exitCode：即使 exitCode 碰巧为 0 也算 TIMEOUT（进程未自然退出）
        assertEquals(VerifyCommandOutcome.TIMEOUT, verifyCommandOutcome(exitCode = 0, timedOut = true))
    }
}