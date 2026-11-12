package xyz.mederi.koog

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import xyz.mederi.infrastructure.koog.mederiToolSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Serializable
enum class SampleToolMode { AUTO, MANUAL }

/**
 * 两个字段都带默认值：旧配置（coerceInputValues=true）会把错误枚举/null 静默回退成默认值，
 * 新配置必须抛错——本测试就是为钉住"不回退"而存在。
 */
@Serializable
data class SampleToolArgs(
    val path: String = "",
    val mode: SampleToolMode = SampleToolMode.AUTO,
)

/**
 * tool-serializer-strict 验收：mederiToolSerializer 不再开 coerceInputValues。
 *
 * 真实路径：KotlinxSerializer.json 是 public 属性，decodeArgs → decodeFromJSONElement
 * 与 json.decodeFromString 走同一个 Json 实例、同一套配置，直接用它解码即测真实机制。
 * 断言：错误枚举字面量 / null 传给非可空字段 → SerializationException，绝不回退字段默认值；
 * 多余字段忽略、缺省字段落默认值的宽容必须保留（ignoreUnknownKeys/explicitNulls）。
 */
class ToolSerializerStrictTest {

    @Test
    fun `invalid enum value throws instead of falling back to default`() {
        val e = assertFailsWith<SerializationException> {
            mederiToolSerializer.json.decodeFromString<SampleToolArgs>(
                """{"path":"a.txt","mode":"BOGUS"}"""
            )
        }
        assertTrue(
            e.message!!.contains("BOGUS"),
            "error must mention the bad enum literal: ${e.message}"
        )
    }

    @Test
    fun `null for non-nullable field with default throws instead of coercing`() {
        assertFailsWith<SerializationException> {
            mederiToolSerializer.json.decodeFromString<SampleToolArgs>(
                """{"path":null}"""
            )
        }
        // 不校验 message 细节：kotlinx 对 null literal 的报错文案随版本有差异，类型即契约。
    }

    @Test
    fun `leniency preserved - unknown keys ignored and defaults applied`() {
        val args = mederiToolSerializer.json.decodeFromString<SampleToolArgs>(
            """{"path":"a.txt","extraField":123}"""
        )
        assertEquals("a.txt", args.path)
        assertEquals(SampleToolMode.AUTO, args.mode)
    }
}