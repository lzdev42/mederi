package xyz.mederi.plan

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * verifspec-command-required 验收：VerificationSpec 对象形态必须携带字符串 command。
 *
 * 旧行为：对象形态缺 command 时静默取 ""——空验证命令 exit 0 假 PASS，是验收造假。
 * 新行为：command 缺失 / null / 非字符串 → 抛 [SerializationException]；纯字符串
 * legacy 形态与其余字段缺省语义保留；空字符串 command 保留（结构化验收计划的
 * overallAcceptance 子任务 command 为空、由 acceptanceCases 驱动，属合法语义）。
 */
class VerificationSpecStrictTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `object form missing command field throws`() {
        assertFailsWith<SerializationException> {
            json.decodeFromString<VerificationSpec>("""{"cwd":"x"}""")
        }
    }

    @Test
    fun `object form with null command throws`() {
        assertFailsWith<SerializationException> {
            json.decodeFromString<VerificationSpec>("""{"command":null,"cwd":"x"}""")
        }
    }

    @Test
    fun `object form with non-string command throws`() {
        assertFailsWith<SerializationException> {
            json.decodeFromString<VerificationSpec>("""{"command":{"nested":true}}""")
        }
    }

    @Test
    fun `legacy plain string still maps to command`() {
        val spec = json.decodeFromString<VerificationSpec>("\"ls -la\"")
        assertEquals("ls -la", spec.command)
        assertNull(spec.cwd)
        assertNull(spec.timeoutSeconds)
    }

    @Test
    fun `empty string command remains valid (structured acceptance plans)`() {
        val spec = json.decodeFromString<VerificationSpec>("""{"command":""}""")
        assertEquals("", spec.command)
    }

    @Test
    fun `full object deserializes all fields`() {
        val spec = json.decodeFromString<VerificationSpec>(
            """{"command":"pytest","cwd":"sub","timeoutSeconds":30,"expected":"all pass","expectStdoutContains":["PASS"],"expectStdoutNotContains":["FAIL"]}"""
        )
        assertEquals("pytest", spec.command)
        assertEquals("sub", spec.cwd)
        assertEquals(30, spec.timeoutSeconds)
        assertEquals("all pass", spec.expected)
        assertEquals(listOf("PASS"), spec.expectStdoutContains)
        assertEquals(listOf("FAIL"), spec.expectStdoutNotContains)
    }
}
