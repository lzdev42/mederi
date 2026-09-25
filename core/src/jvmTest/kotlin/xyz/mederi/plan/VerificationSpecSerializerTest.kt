package xyz.mederi.plan

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * VerificationSpec 序列化往返单测：
 * - 旧 JSON 形态（verification 为纯 String）能正确加载
 * - 新 JSON 形态（verification 为 Object）各字段正确
 * - encode → decode 往返一致
 */
class VerificationSpecSerializerTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `legacy string format deserializes to VerificationSpec with command only`() {
        // 旧格式：verification 是纯字符串
        val serialized = json.encodeToString(VerificationSpec.serializer(), VerificationSpec(command = "pytest -x"))
        // 直接用 JsonPrimitive 模拟旧形态
        val legacyJson = JsonPrimitive("pytest -x").toString()
        val result = json.decodeFromString<VerificationSpec>(legacyJson)
        assertEquals("pytest -x", result.command)
        assertNull(result.cwd)
        assertNull(result.timeoutSeconds)
    }

    @Test
    fun `new object format deserializes all fields`() {
        val objJson = """{"command":"pytest","cwd":"sub","timeoutSeconds":30}"""
        val result = json.decodeFromString<VerificationSpec>(objJson)
        assertEquals("pytest", result.command)
        assertEquals("sub", result.cwd)
        assertEquals(30, result.timeoutSeconds)
    }

    @Test
    fun `new object format with only command deserializes`() {
        val objJson = """{"command":"echo hello"}"""
        val result = json.decodeFromString<VerificationSpec>(objJson)
        assertEquals("echo hello", result.command)
        assertNull(result.cwd)
        assertNull(result.timeoutSeconds)
    }

    @Test
    fun `round-trip encode then decode preserves all fields`() {
        val original = VerificationSpec(command = "gradle test", cwd = "/tmp/project", timeoutSeconds = 120)
        val encoded = json.encodeToString(VerificationSpec.serializer(), original)
        val decoded = json.decodeFromString<VerificationSpec>(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `round-trip with command only`() {
        val original = VerificationSpec(command = "ls -la")
        val encoded = json.encodeToString(VerificationSpec.serializer(), original)
        val decoded = json.decodeFromString<VerificationSpec>(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `legacy Subtask JSON with string verification loads correctly`() {
        // 模拟旧版持久化的 Subtask JSON（verification 为纯字符串）
        val legacySubtaskJson = """
            {"index":0,"name":"t","planDetail":"d","verification":"pytest -x"}
        """.trimIndent()
        val subtask = json.decodeFromString<Subtask>(legacySubtaskJson)
        assertEquals("pytest -x", subtask.verification.command)
        assertNull(subtask.verification.cwd)
        assertNull(subtask.verification.timeoutSeconds)
    }

    @Test
    fun `new Subtask JSON with object verification loads correctly`() {
        val newSubtaskJson = """
            {"index":1,"name":"build","planDetail":"d","verification":{"command":"gradle build","cwd":"sub","timeoutSeconds":60}}
        """.trimIndent()
        val subtask = json.decodeFromString<Subtask>(newSubtaskJson)
        assertEquals("gradle build", subtask.verification.command)
        assertEquals("sub", subtask.verification.cwd)
        assertEquals(60, subtask.verification.timeoutSeconds)
    }

    @Test
    fun `Subtask round-trip with VerificationSpec preserves all fields`() {
        val original = Subtask(
            index = 0,
            name = "test-task",
            planDetail = "run tests",
            verification = VerificationSpec(command = "pytest", cwd = "/tmp", timeoutSeconds = 30),
            executorTouchedFiles = listOf("a.kt", "b.kt"),
            executorProgress = "done"
        )
        val encoded = json.encodeToString(Subtask.serializer(), original)
        val decoded = json.decodeFromString<Subtask>(encoded)
        assertEquals(original, decoded)
    }
}
