package xyz.mederi.ui.settings

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 重试等待上限输入解析纯函数 [parseRetryMaxDelayInput] 的直测。
 *
 * 观测协议（collector）：全部断言通过后把真实观测值写入 `app/shared/build/retry-ui/`：
 * - blank.txt  精确为 `restore-default`（parse("") 与 parse("   ") 均为 RestoreDefault 才合格）
 * - invalid.txt 精确为 `-3=invalid,abc=invalid`
 * 断言失败 → 文件缺失 → collector not-run → FAIL。
 */
class ProviderRetryInputParseTest {

    private fun RetryInputParse.observation(): String = when (this) {
        is RetryInputParse.Valid -> "valid"
        RetryInputParse.RestoreDefault -> "restore-default"
        RetryInputParse.Invalid -> "invalid"
    }

    @Test
    fun parseRetryMaxDelayInputMatchesSpec() {
        // ---- 前置断言：全部通过后才写观测文件 ----
        assertEquals(RetryInputParse.RestoreDefault, parseRetryMaxDelayInput(""), "空输入应恢复默认")
        assertEquals(RetryInputParse.RestoreDefault, parseRetryMaxDelayInput("   "), "纯空白应恢复默认")
        assertEquals(RetryInputParse.Valid(5), parseRetryMaxDelayInput("5"), "纯数字应合法")
        assertEquals(RetryInputParse.Valid(5), parseRetryMaxDelayInput(" 5 "), "带空白数字应合法")
        assertEquals(RetryInputParse.Valid(0), parseRetryMaxDelayInput("0"), "0 合法=不重试，不应误判非法")
        assertEquals(RetryInputParse.Invalid, parseRetryMaxDelayInput("-3"), "负数应非法")
        assertEquals(RetryInputParse.Invalid, parseRetryMaxDelayInput("abc"), "非数字应非法")
        assertEquals(RetryInputParse.Invalid, parseRetryMaxDelayInput("999999999999"), "溢出 Int 应非法")

        // ---- 观测值写入：必须在全部断言之后 ----
        val blankResults = listOf(parseRetryMaxDelayInput(""), parseRetryMaxDelayInput("   "))
        val blankObservation = when {
            blankResults.all { it is RetryInputParse.RestoreDefault } -> "restore-default"
            blankResults.any { it is RetryInputParse.Invalid } -> "invalid"
            else -> "valid"
        }
        assertTrue(blankObservation == "restore-default", "blank 观测应为 restore-default，实际: $blankObservation")

        val invalidObservation = listOf(
            "-3" to parseRetryMaxDelayInput("-3"),
            "abc" to parseRetryMaxDelayInput("abc"),
        ).joinToString(",") { (input, result) -> "$input=${result.observation()}" }
        assertTrue(invalidObservation == "-3=invalid,abc=invalid", "invalid 观测应为 -3=invalid,abc=invalid，实际: $invalidObservation")

        val outDir = File("build/retry-ui").apply { mkdirs() }
        File(outDir, "blank.txt").writeText(blankObservation)
        File(outDir, "invalid.txt").writeText(invalidObservation)
    }
}