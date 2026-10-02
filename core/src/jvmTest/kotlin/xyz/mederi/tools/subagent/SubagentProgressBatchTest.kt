package xyz.mederi.tools.subagent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * SubagentProgressBatch 窗口聚合的时序与规则单测。
 *
 * 覆盖三类关键行为：
 * 1. 窗口内多帧合并为一次 emit（事件量随 token 线性增长的问题被窗口吸收）；
 * 2. tool_call 帧只记录工具名、参数 JSON 分片不进 delta（不污染 UI 输出视窗）；
 * 3. flushNow 尾部冲刷（窗口未满也补发，不丢最后一段流式输出）。
 */
class SubagentProgressBatchTest {

    private class FakeClock(var now: Long = 0L) {
        fun tick(ms: Long) { now += ms }
        fun fn(): () -> Long = { now }
    }

    @Test
    fun burstWithinWindowCoalescesToSingleFrame() {
        val clock = FakeClock()
        val batch = SubagentProgressBatch(throttleMillis = 120, nowMillis = clock.fn())

        batch.add(SubagentActivity.OUTPUT, "a", null, true)
        clock.tick(10)
        batch.add(SubagentActivity.OUTPUT, "b", null, true)
        clock.tick(10)
        batch.add(SubagentActivity.OUTPUT, "c", null, true)

        // 窗口未到 → 无输出
        clock.tick(50) // 距上次 flush 仍是 70ms
        assertNull(batch.flushIfDue(), "窗口未满不应产出")

        // 窗口到（80→200ms）
        clock.tick(130)
        val frame = batch.flushIfDue()
        assertEquals(SubagentActivity.OUTPUT, frame?.activity)
        assertEquals("abc", frame?.delta, "窗口内帧应合并为一段文本")
        assertEquals(true, frame?.isMessage)
        assertNull(batch.flushIfDue(), "flush 后无新内容不应重复产出")
    }

    @Test
    fun toolCallFramesKeepNameButDropArgFragments() {
        val clock = FakeClock()
        val batch = SubagentProgressBatch(throttleMillis = 120, nowMillis = clock.fn())

        // 模拟工具参数流式分片（type=tool_call 的 content 是部分 JSON）
        batch.add(SubagentActivity.TOOL_CALL, """{"path":"src/""", "read_file", false)
        clock.tick(10)
        batch.add(SubagentActivity.TOOL_CALL, """Main.kt"}""", "read_file", false)
        clock.tick(10)
        batch.add(SubagentActivity.TOOL_CALL, "", "read_file", false)

        clock.tick(150) // 窗口已到
        val frame = batch.flushIfDue()
        assertEquals(SubagentActivity.TOOL_CALL, frame?.activity)
        assertEquals("read_file", frame?.toolName)
        assertEquals("", frame?.delta, "工具参数分片不得进入 delta")
        assertEquals(false, frame?.isMessage)
    }

    @Test
    fun thinkingFramesBatchLikeText() {
        val clock = FakeClock()
        val batch = SubagentProgressBatch(throttleMillis = 120, nowMillis = clock.fn())

        // 推理帧 isMessage=false（不进 lastMessage），但 accumulateDelta=true（进输出视窗）
        batch.add(SubagentActivity.THINKING, "先分析", null, false, accumulateDelta = true)
        clock.tick(10)
        batch.add(SubagentActivity.THINKING, "再动手", null, false, accumulateDelta = true)
        clock.tick(150)

        val frame = batch.flushIfDue()
        assertEquals(SubagentActivity.THINKING, frame?.activity)
        assertEquals("先分析再动手", frame?.delta)
        // 推理帧 isMessage=false：不参与 lastMessage 提取
        assertEquals(false, frame?.isMessage)
    }

    @Test
    fun flushNowEmitsTailEvenBeforeWindow() {
        val clock = FakeClock()
        val batch = SubagentProgressBatch(throttleMillis = 120, nowMillis = clock.fn())

        batch.add(SubagentActivity.OUTPUT, "尾巴", null, true)
        // 未做任何 flushIfDue、窗口也没到——flushNow 必须补发（会话终止兜底）
        val frame = batch.flushNow()
        assertEquals("尾巴", frame?.delta)
        assertNull(batch.flushNow(), "flushNow 后不应重复产出")
    }

    @Test
    fun activityAndToolResetAfterFlush() {
        val clock = FakeClock()
        val batch = SubagentProgressBatch(throttleMillis = 120, nowMillis = clock.fn())

        batch.add(SubagentActivity.TOOL_CALL, "", "ls", false)
        clock.tick(150)
        assertEquals("ls", batch.flushIfDue()?.toolName)

        // 下一窗口只有文本帧时，不应残留上一次工具名
        batch.add(SubagentActivity.OUTPUT, "结果", null, true)
        clock.tick(150)
        val frame = batch.flushIfDue()
        assertEquals(SubagentActivity.OUTPUT, frame?.activity)
        assertNull(frame?.toolName, "flush 后工具名应复位")
        assertEquals("结果", frame?.delta)
    }
}
