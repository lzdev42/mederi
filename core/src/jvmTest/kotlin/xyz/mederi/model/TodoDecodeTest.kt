package xyz.mederi.model

import xyz.mederi.domain.model.TodoDetail
import xyz.mederi.domain.model.TodoItem
import xyz.mederi.domain.model.TodoStatus
import xyz.mederi.domain.model.decodeTodos
import xyz.mederi.domain.model.encodeTodos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * decodeTodos 的"无数据"契约：损坏 JSON → null（调用方安全降级），
 * 空列表是合法值（= 清空）。损坏路径要求 DebugLog.error 有输出
 * （todos-audible 验收内容），但 DebugLog 只写 stderr 无可采集钩子，
 * 此处以返回值为行为断言，日志由实现路径直接保障。
 */
class TodoDecodeTest {

    @Test
    fun `corrupt todos json decodes to null instead of crashing`() {
        assertNull(decodeTodos("not-json"), "非法 JSON 必须返回 null")
        assertNull(decodeTodos("{broken"), "截断 JSON 必须返回 null")
        assertNull(decodeTodos("""{"a":1}"""), "合法 JSON 但形状不是列表必须返回 null")
    }

    @Test
    fun `legit todos list decodes normally`() {
        val json = """[{"content":"task","status":"completed","detail":{"background":"ctx"}}]"""
        assertEquals(
            listOf(TodoItem("task", TodoStatus.COMPLETED, TodoDetail(background = "ctx"))),
            decodeTodos(json)
        )
    }

    @Test
    fun `encode decode roundtrip is symmetric`() {
        val todos = listOf(
            TodoItem("first", TodoStatus.PENDING),
            TodoItem("second", TodoStatus.IN_PROGRESS, TodoDetail(requirement = "req", done = "check"))
        )
        assertEquals(todos, decodeTodos(todos.encodeTodos()))
        // 空列表是合法值（= 清空），不是 null
        assertEquals(emptyList(), decodeTodos(emptyList<TodoItem>().encodeTodos()))
    }
}