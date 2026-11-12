package xyz.mederi.tools

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * plan 工具参数解码严格化回归测试（AGENTS.md §5 不兜底）：
 * 宽松 serializer（LenientWritePlanArgs/LenientSubtaskArg）已删除——
 * 双层编码字符串 / 单对象当列表等错形不再在解码层矫正，一律抛 kotlinx
 * SerializationException，由 Koog GenericAgentEnvironment 把原始错误回喂模型自纠。
 *
 * Json 配置与 mederiToolSerializer 对齐（ignoreUnknownKeys=true、explicitNulls=false、无 coerce）。
 */
class LenientCreatePlanArgsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    // 最小合法 WritePlanArgs（只含必填字段）
    private fun baseArgs(extra: Map<String, String> = emptyMap()): String {
        val fields = mutableMapOf(
            "title" to "\"t\"",
            "summary" to "\"s\"",
            "projectContext" to "\"BROWNFIELD\"",
            "background" to "\"b\"",
            "problemAnalysis" to "\"p\"",
            "approach" to "\"a\"",
            "expectedOutcome" to "\"e\"",
            "testMethod" to "\"t\"",
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
    fun `string keyDecisions is rejected`() {
        // 模型把 keyDecisions 发成 JSON 字符串（"[{...}]"，内层真实可解析）→ 严格解码抛错
        val args = baseArgs(mapOf(
            "keyDecisions" to "\"[{\\\"question\\\":\\\"q\\\",\\\"choice\\\":\\\"c\\\"}]\""
        ))
        assertFailsWith<SerializationException> {
            json.decodeFromString(PlanTools.WritePlanArgs.serializer(), args)
        }
    }

    @Test
    fun `single object decisions is rejected`() {
        // 模型把 decisions 发成裸对象（{...}）而非数组 → 严格解码抛错
        val args = baseArgs(mapOf(
            "subtasks" to "[{\"name\":\"s0\",\"planDetail\":\"d\",\"targetFiles\":[],\"verification\":\"v\",\"decisions\":{\"question\":\"q\",\"choice\":\"c\"}}]"
        ))
        assertFailsWith<SerializationException> {
            json.decodeFromString(PlanTools.WritePlanArgs.serializer(), args)
        }
    }

    @Test
    fun `string changes is rejected`() {
        val args = baseArgs(mapOf(
            "changes" to "\"[{\\\"module\\\":\\\"core\\\",\\\"action\\\":\\\"MODIFY\\\",\\\"filePath\\\":\\\"f\\\",\\\"description\\\":\\\"d\\\",\\\"rationale\\\":\\\"r\\\"}]\""
        ))
        assertFailsWith<SerializationException> {
            json.decodeFromString(PlanTools.WritePlanArgs.serializer(), args)
        }
    }

    @Test
    fun `correct array shape passes through unchanged`() {
        // 正确 JsonArray 正常解码，严格化不得误伤合法形状
        val args = baseArgs(mapOf(
            "keyDecisions" to "[{\"question\":\"q1\",\"choice\":\"c1\"},{\"question\":\"q2\",\"choice\":\"c2\"}]"
        ))
        val parsed = json.decodeFromString(PlanTools.WritePlanArgs.serializer(), args)
        assertEquals(2, parsed.keyDecisions.size)
        assertEquals("q2", parsed.keyDecisions[1].question)
    }
}