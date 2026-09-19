package xyz.mederi.core

import xyz.mederi.core.contract.SubagentTracker
import xyz.mederi.core.contract.models.CoreEvent
import xyz.mederi.core.contract.models.CoreEventType
import xyz.mederi.core.contract.models.SubagentState
import xyz.mederi.core.ui.SubagentReportMarkdown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P4 ViewModel 层纯逻辑锁定：
 * - SubagentTracker：STARTED 建缓存对象（全量元数据）→ 终态覆盖 status；乱序终态安全跳过
 * - SubagentReportMarkdown：wait_agent/agent_status 的 COMPLETED 结果 → markdown；
 *   其他工具 / 未完成 / 坏 JSON → null
 */
class SubagentTrackerAndReportTest {

    private fun event(type: CoreEventType, agentId: String, vararg payload: Pair<String, String>) =
        CoreEvent(
            type = type,
            sessionId = "sess_parent",
            payload = mapOf("agentId" to agentId, *payload),
            timestamp = "2026-09-19T10:00:00Z"
        )

    @Test
    fun `tracker builds state from started and applies terminal status`() {
        var states: Map<String, SubagentState> = emptyMap()

        states = SubagentTracker.apply(states, event(
            CoreEventType.SUBAGENT_STARTED, "sub_1",
            "role" to "EXECUTOR", "modelId" to "m1", "modelName" to "ModelB",
            "reasoningLevel" to "HIGH", "task" to "implement X", "briefing" to "watch Y"
        ))

        val s = states.getValue("sub_1")
        assertEquals("sess_parent", s.parentSessionId)
        assertEquals("EXECUTOR", s.role)
        assertEquals("ModelB", s.modelName)
        assertEquals("HIGH", s.reasoningLevel)
        assertEquals("implement X", s.task)
        assertEquals("watch Y", s.briefing)
        assertEquals("RUNNING", s.status)

        states = SubagentTracker.apply(states, event(CoreEventType.SUBAGENT_COMPLETED, "sub_1"))
        assertEquals("COMPLETED", states.getValue("sub_1").status)

        // 乱序终态（无 STARTED 的 agentId）安全跳过，不抛错不建条目
        states = SubagentTracker.apply(states, event(CoreEventType.SUBAGENT_STOPPED, "sub_unknown"))
        assertTrue("sub_unknown" !in states)

        // 非子代理事件原样返回
        val unchanged = SubagentTracker.apply(states, CoreEvent(
            type = CoreEventType.MESSAGE_DELTA, sessionId = "sess_parent", timestamp = "t"
        ))
        assertEquals(states, unchanged)
    }

    @Test
    fun `report markdown renders completed wait_agent result`() {
        val json = """
            {"agentId":"sub_1","status":"COMPLETED","progress":"completed",
             "result":"All three files updated and tests pass.","modelName":"ModelB","reasoningLevel":"HIGH"}
        """.trimIndent()

        val md = SubagentReportMarkdown.fromToolResult("wait_agent", json)

        assertTrue(md != null, "COMPLETED + 非空 result 应产出 markdown")
        assertTrue(md!!.contains("### Subagent Report"))
        assertTrue(md.contains("`sub_1`"), "应含 agentId")
        assertTrue(md.contains("ModelB (HIGH)"), "应含模型与推理档位")
        assertTrue(md.contains("All three files updated and tests pass."), "应含汇报正文")
    }

    @Test
    fun `report markdown returns null for non-reportable results`() {
        // 非目标工具
        assertNull(SubagentReportMarkdown.fromToolResult("read_file", "{\"result\":\"x\"}"))
        // TIMEOUT / RUNNING（等待中态，无汇报）
        assertNull(SubagentReportMarkdown.fromToolResult("wait_agent",
            """{"agentId":"sub_1","status":"TIMEOUT","progress":"running"}"""))
        // COMPLETED 但无结果文本
        assertNull(SubagentReportMarkdown.fromToolResult("agent_status",
            """{"agentId":"sub_1","status":"COMPLETED","result":""}"""))
        // 坏 JSON
        assertNull(SubagentReportMarkdown.fromToolResult("wait_agent", "not json at all"))
    }
}
