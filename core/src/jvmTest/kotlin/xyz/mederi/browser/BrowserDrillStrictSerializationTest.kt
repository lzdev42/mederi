package xyz.mederi.browser

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import xyz.mederi.browser.drill.models.DrillScript
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Drill 链路严格化反序列化测试：
 * - LooseMapStringSerializer（selector_options）：值必须是标量(string/number/boolean)，object/array 抛 SerializationException；
 * - ContentFromSerializer（content_from）：必须是字符串或字符串数组，number/boolean/对象抛 SerializationException；
 * - LenientJsonStringSerializer（drill script 字段）：只接受 JSON 字符串（内容为 DrillScript 对象），对象形直接报错。
 */
class BrowserDrillStrictSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ── 1. selector_options（LooseMapStringSerializer）──

    @Test
    fun `selector_options with array value throws SerializationException`() {
        val ex = assertFailsWith<SerializationException> {
            json.decodeFromString<DrillScript>(
                """{"task_name":"t","steps":[{"step_name":"s","action":"click","selector_value":".x","selector_options":{"exact":[true]}}]}"""
            )
        }
        assertTrue(ex.message?.contains("selector_options") == true, "错误信息应指明 selector_options, msg=${ex.message}")
    }

    @Test
    fun `selector_options scalar values are preserved as string content`() {
        val script = json.decodeFromString<DrillScript>(
            """{"task_name":"t","steps":[{"step_name":"w","action":"wait","selector_options":{"wait_ms":3000,"exact":true}}]}"""
        )
        val options = script.steps[0].selector_options
        assertTrue(options != null, "selector_options 应为非空")
        assertEquals("3000", options["wait_ms"], "数字标量应保留为字符串内容")
        assertEquals("true", options["exact"], "布尔标量应保留为字符串内容")
    }

    // ── 2. content_from（ContentFromSerializer）──

    @Test
    fun `content_from number throws SerializationException`() {
        val ex = assertFailsWith<SerializationException> {
            json.decodeFromString<DrillScript>(
                """{"task_name":"t","steps":[{"step_name":"a","action":"ask_ai","content_from":42}]}"""
            )
        }
        assertTrue(ex.message?.contains("content_from") == true, "错误信息应指明 content_from, msg=${ex.message}")
    }

    @Test
    fun `content_from array with non-primitive element throws SerializationException`() {
        val ex = assertFailsWith<SerializationException> {
            json.decodeFromString<DrillScript>(
                """{"task_name":"t","steps":[{"step_name":"a","action":"ask_ai","content_from":["title",{"bad":"x"}]}]}"""
            )
        }
        assertTrue(ex.message?.contains("content_from") == true, "错误信息应指明 content_from, msg=${ex.message}")
    }

    @Test
    fun `content_from string and array of strings deserialize normally`() {
        val single = json.decodeFromString<DrillScript>(
            """{"task_name":"t","steps":[{"step_name":"a","action":"ask_ai","content_from":"title"}]}"""
        )
        assertEquals(listOf("title"), single.steps[0].content_from)

        val multi = json.decodeFromString<DrillScript>(
            """{"task_name":"t","steps":[{"step_name":"a","action":"ask_ai","content_from":["a","b"]}]}"""
        )
        assertEquals(listOf("a", "b"), multi.steps[0].content_from)
    }

    // ── 3. drill script 字段（LenientJsonStringSerializer）──

    @Test
    fun `drill script object value in args is rejected`() {
        val ex = assertFailsWith<SerializationException> {
            json.decodeFromString<DrillArgs>("""{"script":{"task_name":"t","steps":[]}}""")
        }
        assertTrue(ex.message?.contains("script") == true, "错误信息应指明 script 字段, msg=${ex.message}")
    }

    @Test
    fun `drill script string value in args deserializes normally`() {
        val args = json.decodeFromString<DrillArgs>("""{"script":"{\"task_name\":\"t\",\"steps\":[]}"}""")
        assertEquals("""{"task_name":"t","steps":[]}""", args.script)
    }
}