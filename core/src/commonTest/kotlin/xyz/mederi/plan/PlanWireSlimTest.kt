package xyz.mederi.plan

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 事件 wire 瘦身自检：spec / planDetail / acceptanceCases 全文不得进
 * PLAN_PROGRESS / PLAN_APPROVAL_REQUESTED payload，只下发 UI 投影所需最小事实。
 * 字段名与契约 PlanSubtaskItem 严格一致（客户端 reducer 直接解码，无需变换）。
 */
class PlanWireSlimTest {
    private val json = Json

    private val specSubtask = Subtask(
        index = 0,
        name = "Task 0",
        planDetail = "brief 0",
        spec = "1. do the thing\n2. verify",
        verification = VerificationSpec(""),
        acceptanceCases = listOf(
            AcceptanceCase(id = "V-01", objective = "o1", setup = "s1", input = "i1", steps = "st1",
                observationSource = "obs1", expected = "1", detects = "dup1"),
            AcceptanceCase(id = "V-02", objective = "o2", setup = "s2", input = "i2", steps = "st2",
                observationSource = "obs2", expected = "2", detects = "dup2", manual = true),
        ),
    )

    private val emptySpecSubtask = Subtask(
        index = 1,
        name = "Task 1",
        planDetail = "brief 1",
        spec = null,
        verification = VerificationSpec(""),
    )

    @Test
    fun wireCarriesOnlyMinimalFieldsAndHasSpecReflectsSpecExistence() {
        val arr = json.parseToJsonElement(encodeSubtasksWire(listOf(specSubtask, emptySpecSubtask))).jsonArray
        assertEquals(2, arr.size)

        val first = arr[0].jsonObject
        assertNull(first["spec"], "spec 全文不得进 wire")
        assertNull(first["planDetail"], "planDetail 全文不得进 wire")
        assertNull(first["verification"], "verification 不得进 wire")
        assertEquals(setOf("index", "name", "status", "hasSpec", "acceptanceCases"), first.keys)
        assertEquals(0, first["index"]!!.jsonPrimitive.content.toInt())
        assertEquals("Task 0", first["name"]!!.jsonPrimitive.content)
        assertEquals("PENDING", first["status"]!!.jsonPrimitive.content)
        assertEquals("true", first["hasSpec"]!!.jsonPrimitive.content)

        val second = arr[1].jsonObject
        assertNull(second["spec"])
        assertNull(second["planDetail"])
        assertEquals("false", second["hasSpec"]!!.jsonPrimitive.content)
    }

    @Test
    fun blankSpecCountsAsNoSpec() {
        val arr = json.parseToJsonElement(encodeSubtasksWire(listOf(emptySpecSubtask.copy(spec = "  ")))).jsonArray
        assertEquals("false", arr[0].jsonObject["hasSpec"]!!.jsonPrimitive.content)
    }

    @Test
    fun acceptanceCasesCarryOnlyIdAndManual() {
        val arr = json.parseToJsonElement(encodeSubtasksWire(listOf(specSubtask))).jsonArray
        val cases = arr[0].jsonObject["acceptanceCases"]!!.jsonArray
        assertEquals(2, cases.size)
        assertEquals(setOf("id", "manual"), cases[0].jsonObject.keys)
        assertEquals("V-01", cases[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("false", cases[0].jsonObject["manual"]!!.jsonPrimitive.content)
        assertEquals("V-02", cases[1].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("true", cases[1].jsonObject["manual"]!!.jsonPrimitive.content)
    }
}