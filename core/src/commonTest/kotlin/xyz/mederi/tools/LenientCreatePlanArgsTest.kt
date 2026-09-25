package xyz.mederi.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 验证 coerceObjectListField + LenientCreatePlanArgs/LenientSubtaskArg 的形状矫正。
 *
 * 测试背景：P0-2 修复了 List<DecisionArg>/List<PlannedChangeArg> 的宽松化（模型把
 * keyDecisions 发成 JSON 字符串、decisions 发成裸对象时 kotlinx 默认序列化器崩溃）。
 * 但端到端测试（T1）里模型从没发过错形——宽松化从未被真实触发过。
 * 本测试直接发三种错形，验证 coerceObjectListField 真工作。
 */
class LenientCreatePlanArgsTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // 最小合法 CreatePlanArgs（只含必填字段）
    private fun baseArgs(extra: Map<String, String> = emptyMap()): String {
        val fields = mutableMapOf(
            "title" to "\"t\"",
            "summary" to "\"s\"",
            "projectContext" to "\"BROWNFIELD\"",
            "overview" to "\"o\"",
            "keyDecisions" to "[]",
            "changes" to "[]",
            "inScope" to "[\"a\"]",
            "outOfScope" to "[\"b\"]",
            "successCriteria" to "[\"c\"]",
            "subtasks" to "[{\"name\":\"s0\",\"planDetail\":\"d\",\"targetFiles\":[],\"verification\":\"v\"}]"
        )
        fields.putAll(extra)
        return fields.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":$v" }
    }

    @Test
    fun `string keyDecisions coerced to array`() {
        // 模型把 keyDecisions 发成 JSON 字符串（"[{...}]"）
        val args = baseArgs(mapOf(
            "keyDecisions" to "\"[{\\\"question\\\":\\\"q\\\",\\\"choice\\\":\\\"c\\\"}]\""
        ))
        val parsed = json.decodeFromString(PlanTools.CreatePlanArgs.serializer(), args)
        assertEquals(1, parsed.keyDecisions.size)
        assertEquals("q", parsed.keyDecisions[0].question)
        assertEquals("c", parsed.keyDecisions[0].choice)
    }

    @Test
    fun `single object decisions coerced to array`() {
        // 模型把 decisions 发成裸对象（{...}）而非数组
        val args = baseArgs(mapOf(
            "subtasks" to "[{\"name\":\"s0\",\"planDetail\":\"d\",\"targetFiles\":[],\"verification\":\"v\",\"decisions\":{\"question\":\"q\",\"choice\":\"c\"}}]"
        ))
        val parsed = json.decodeFromString(PlanTools.CreatePlanArgs.serializer(), args)
        assertEquals(1, parsed.subtasks[0].decisions.size)
        assertEquals("q", parsed.subtasks[0].decisions[0].question)
    }

    @Test
    fun `string changes coerced to array`() {
        val args = baseArgs(mapOf(
            "changes" to "\"[{\\\"module\\\":\\\"core\\\",\\\"action\\\":\\\"MODIFY\\\",\\\"filePath\\\":\\\"f\\\",\\\"description\\\":\\\"d\\\",\\\"rationale\\\":\\\"r\\\"}]\""
        ))
        val parsed = json.decodeFromString(PlanTools.CreatePlanArgs.serializer(), args)
        assertEquals(1, parsed.changes.size)
        assertEquals("f", parsed.changes[0].filePath)
    }

    @Test
    fun `correct array shape passes through unchanged`() {
        // 正确 JsonArray 不应被篡改
        val args = baseArgs(mapOf(
            "keyDecisions" to "[{\"question\":\"q1\",\"choice\":\"c1\"},{\"question\":\"q2\",\"choice\":\"c2\"}]"
        ))
        val parsed = json.decodeFromString(PlanTools.CreatePlanArgs.serializer(), args)
        assertEquals(2, parsed.keyDecisions.size)
        assertEquals("q2", parsed.keyDecisions[1].question)
    }
}
