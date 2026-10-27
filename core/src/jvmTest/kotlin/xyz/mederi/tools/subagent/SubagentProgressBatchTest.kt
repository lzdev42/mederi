package xyz.mederi.tools.subagent

import xyz.mederi.domain.model.EventType
import xyz.mederi.domain.model.MederiEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SubagentProgressBatchTest {
    private fun delta(kind: String, content: String, response: String = "r1", turn: String = "work") =
        MederiEvent(EventType.MESSAGE_DELTA, "child", response,
            mapOf("type" to kind, "content" to content, "turnId" to turn), "2026-10-27T08:00:00Z")

    @Test
    fun sameKindAndResponseCoalesceWithoutLosingWhitespace() {
        var now = 0L
        val batch = SubagentProgressBatch(nowMillis = { now })
        assertEquals(emptyList(), batch.add(delta("reasoning", "plan_d87")))
        assertEquals(emptyList(), batch.add(delta("reasoning", "a4db4. ")))
        assertEquals(emptyList(), batch.add(delta("reasoning", "\n")))
        now = 150
        val frame = batch.flushIfDue()!!
        assertEquals("plan_d87a4db4. \n", frame.payload["content"])
        assertEquals("reasoning", frame.payload["type"])
        assertNull(batch.flushNow())
    }

    @Test
    fun typeAndToolBoundariesNeverRelabelOrDropBufferedText() {
        val batch = SubagentProgressBatch(nowMillis = { 0L })
        val out = mutableListOf<MederiEvent>()
        out += batch.add(delta("reasoning", "plan_d87"))
        out += batch.add(delta("reasoning", "a4db4."))
        out += batch.add(delta("text", "The notebook's recent entries will"))
        val call = MederiEvent(EventType.TOOL_CALLED, "child", "r1",
            mapOf("tool" to "list_directory", "toolCallId" to "directory-1", "args" to "directory-arguments"), "now")
        val result = MederiEvent(EventType.TOOL_RESULT, "child", "r1",
            mapOf("tool" to "list_directory", "toolCallId" to "directory-1", "output" to "complete directory output", "isError" to "false"), "now")
        out += batch.add(call)
        out += batch.add(result)
        out += batch.add(delta("reasoning", "next analysis"))
        out += listOfNotNull(batch.flushNow())
        assertEquals(listOf("reasoning", "text", null, null, "reasoning"), out.map { it.payload["type"] })
        assertEquals("plan_d87a4db4.", out[0].payload["content"])
        assertEquals("The notebook's recent entries will", out[1].payload["content"])
        assertEquals(call, out[2])
        assertEquals(result, out[3])
        assertEquals("next analysis", out[4].payload["content"])
        assertNull(batch.flushNow())
    }

    @Test
    fun responseBoundariesAndFinalMarkerAreForwardedInOrder() {
        val batch = SubagentProgressBatch(nowMillis = { 0L })
        val out = mutableListOf<MederiEvent>()
        out += batch.add(delta("text", "first", "r1"))
        val boundary = delta("response_completed", "", "r1")
        out += batch.add(boundary)
        out += batch.add(delta("text", "second", "r2"))
        val complete = MederiEvent(EventType.MESSAGE_COMPLETED, "child", payload = mapOf("finalMessageId" to "r2", "turnId" to "work"), timestamp = "now")
        out += batch.add(complete)
        assertEquals(listOf("first", "", "second", null), out.map { it.payload["content"] })
        assertEquals(boundary, out[1])
        assertEquals(complete, out.last())
        assertEquals(listOf("r1", "r1", "r2", null), out.map { it.messageId })
    }

    @Test
    fun distinctResponsesCannotShareOneTextBuffer() {
        val batch = SubagentProgressBatch(nowMillis = { 0L })
        assertEquals(emptyList(), batch.add(delta("text", "one", "r1")))
        assertEquals("one", batch.add(delta("text", "two", "r2")).single().payload["content"])
        assertEquals("two", batch.flushNow()!!.payload["content"])
    }

    @Test
    fun toolArgumentDeltasAreTypedEventsNotNarration() {
        val batch = SubagentProgressBatch(nowMillis = { 0L })
        val frame = delta("tool_call", "{", "r1").copy(payload = mapOf("type" to "tool_call", "name" to "read_file", "content" to "{", "turnId" to "work"))
        assertEquals(listOf(frame), batch.add(frame))
        assertNull(batch.flushNow())
    }
}
