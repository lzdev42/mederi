package xyz.mederi.tools

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ask_user 参数标准解析测试 + plan 工具参数严格解码回归测试。
 *
 * ask_user 不做兜底：格式错即报错，让模型根据错误信息自纠。
 * plan 工具同样不做兜底（宽松 serializer 已删除）：错形抛 kotlinx SerializationException 回喂。
 */
class LenientArgsTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ── ask_user：标准解析，格式正确就过，错了就报错 ──

    @Test
    fun `ask_user - proper array passes through`() {
        val args = json.decodeFromString<AgentTools.AskUserArgs>(
            """{"questions":[{"id":"q1","prompt":"p","options":["a","b"],"multiSelect":true},{"id":"q2","prompt":"p2"}]}"""
        )
        assertEquals(2, args.questions.size)
        assertEquals(listOf("a", "b"), args.questions[0].options)
        assertTrue(args.questions[0].multiSelect)
        assertEquals(emptyList(), args.questions[1].options)
    }

    @Test
    fun `ask_user - stringified JSON array is rejected`() {
        // 双层编码（模型把数组包成字符串）：不兜底，直接报错
        val incidentArgs =
            """{"questions":"[{\"id\": \"db_scope\", \"prompt\": \"讨论中说过\\\"config.db 与 data.db 各一条队列\\\"，本计划接管哪些？\", \"options\": [\"仅 data.db（推荐）\", \"实现\\\"driver 唯一所有权\\\"\"]}]"}"""
        val e = assertFailsWith<SerializationException> {
            json.decodeFromString<AgentTools.AskUserArgs>(incidentArgs)
        }
        // 标准 kotlinx 报 "Expected start of the array '[', but had '\"'"
        assertTrue(e.message!!.contains("questions"), e.message)
    }

    @Test
    fun `ask_user - single object instead of array is rejected`() {
        val e = assertFailsWith<SerializationException> {
            json.decodeFromString<AgentTools.AskUserArgs>(
                """{"questions":{"id":"q1","prompt":"p","options":[]}}"""
            )
        }
        assertTrue(e.message!!.contains("questions"), e.message)
    }

    @Test
    fun `ask_user - unparseable string is rejected`() {
        val e = assertFailsWith<SerializationException> {
            json.decodeFromString<AgentTools.AskUserArgs>("""{"questions":"not a json ["}""")
        }
        assertTrue(e.message!!.contains("questions"), e.message)
    }

    @Test
    fun `ask_user - numeric literal is rejected`() {
        val e = assertFailsWith<SerializationException> {
            json.decodeFromString<AgentTools.AskUserArgs>("""{"questions":42}""")
        }
        assertTrue(e.message!!.contains("questions"), e.message)
    }

    // ── plan 工具：严格解码（错形不矫正，直接报错回喂）──

    @Test
    fun `plan args unparseable object-list string fails fast`() {
        val e = assertFailsWith<SerializationException> {
            json.decodeFromString<PlanTools.WritePlanArgs>(
                """{"title":"t","subtasks":[],"keyDecisions":"garbage ["}"""
            )
        }
        // kotlinx 原生报错，错误含字段路径：Expected start of the array '[', but had '"' instead at path: $.keyDecisions
        assertTrue(e.message!!.contains("keyDecisions"), e.message)
    }
}
