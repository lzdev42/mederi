package xyz.mederi.tools

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 工具入参「对象列表字段」形状矫正回归测试（LenientArgs.coerceObjectListField）。
 *
 * 事故样本（glm-5.3，sess_e5a60397 seq119 原文）：question 文本内嵌 ASCII 引号时，
 * 模型把 questions 整个数组序列化成 JSON 字符串（双层编码）→ kotlinx 抛
 * "Expected JsonArray, but had JsonLiteral as the serialized body of kotlin.collections.ArrayList at path: $.questions"。
 */
class LenientArgsTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** 事故原文（sess_e5a60397 seq119，verbatim：双层编码 + 文本内嵌转义引号） */
    private val incidentArgs =
        """{"questions":"[{\"id\": \"db_scope\", \"prompt\": \"讨论中说过\\\"config.db 与 data.db 各一条队列\\\"，本计划接管哪些？\", \"options\": [\"仅 data.db（推荐）\", \"实现\\\"driver 唯一所有权\\\"\"]}]"}"""

    @Test
    fun `questions as stringified JSON array is repaired`() {
        val args = json.decodeFromString<AgentTools.AskUserArgs>(incidentArgs)
        assertEquals(1, args.questions.size)
        val q = args.questions[0]
        assertEquals("db_scope", q.id)
        // 内嵌引号必须原样保留（矫正只剥掉双层编码的一层）
        assertEquals("讨论中说过\"config.db 与 data.db 各一条队列\"，本计划接管哪些？", q.prompt)
        assertEquals("实现\"driver 唯一所有权\"", q.options[1])
    }

    @Test
    fun `questions as single object is wrapped into list`() {
        val args = json.decodeFromString<AgentTools.AskUserArgs>(
            """{"questions":{"id":"q1","prompt":"p","options":[]}}"""
        )
        assertEquals(1, args.questions.size)
        assertEquals("q1", args.questions[0].id)
    }

    @Test
    fun `questions as proper array passes through`() {
        val args = json.decodeFromString<AgentTools.AskUserArgs>(
            """{"questions":[{"id":"q1","prompt":"p","options":["a","b"],"multiSelect":true},{"id":"q2","prompt":"p2"}]}"""
        )
        assertEquals(2, args.questions.size)
        assertEquals(listOf("a", "b"), args.questions[0].options)
        assertTrue(args.questions[0].multiSelect)
        assertEquals(emptyList(), args.questions[1].options)
    }

    @Test
    fun `unparseable questions string keeps original decode error`() {
        // 不兜底空数组：解析失败必须让原始错误浮出（回喂后模型一轮可自纠）
        val e = assertFailsWith<SerializationException> {
            json.decodeFromString<AgentTools.AskUserArgs>("""{"questions":"not a json ["}""")
        }
        assertTrue(e.message!!.contains("Expected JsonArray"), e.message)
    }

    @Test
    fun `non-string questions literal keeps original decode error`() {
        val e = assertFailsWith<SerializationException> {
            json.decodeFromString<AgentTools.AskUserArgs>("""{"questions":42}""")
        }
        assertTrue(e.message!!.contains("Expected JsonArray"), e.message)
    }

    @Test
    fun `inner keyless-object options in string-wrapped array degrades safely`() {
        // ask_user#1 故障（sess_e5a60397 2026-11-10）：双层错形——
        // 外层 questions=字符串（宽容层能修），内层 options=[{"A","B"}] keyless-object（非法 JSON、parse 失败）。
        // 宽容层 parseToJsonElement(字符串内容) 失败 → 保留原值 → 下游报错（安全降级，不字符串手术挽救，per AGENTS.md §5）。
        // 模型一轮自纠（重试用 \u 转义）。
        val innerInvalid =
            """{"questions":"[{\"id\":\"q\",\"prompt\":\"p\",\"options\":[{\"A\",\"B\"}]}]"}"""
        val e = assertFailsWith<SerializationException> {
            json.decodeFromString<AgentTools.AskUserArgs>(innerInvalid)
        }
        assertTrue(e.message!!.contains("Expected JsonArray"), e.message)
    }

    @Test
    fun `plan args unparseable object-list string also fails fast now`() {
        // 共享严格语义回归：write_plan 的 keyDecisions 解析不出时不再静默兜底空数组
        val e = assertFailsWith<SerializationException> {
            json.decodeFromString<PlanTools.WritePlanArgs>(
                """{"title":"t","subtasks":[],"keyDecisions":"garbage ["}"""
            )
        }
        assertTrue(e.message!!.contains("Expected JsonArray"), e.message)
    }
}
