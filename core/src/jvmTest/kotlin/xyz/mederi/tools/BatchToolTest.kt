package xyz.mederi.tools

import ai.koog.prompt.executor.clients.openai.base.OpenAICompatibleToolDescriptorSchemaGenerator
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.mederi.domain.model.SubagentRole
import xyz.mederi.store.InMemoryHistoryStore

class BatchToolTest {
    private val empty = BatchTool.Parameters()

    @Test
    fun `published schema explains the required array and member fields`() {
        val tool = BatchTool(mapOf("bash" to { "shell" }, "read_file" to { "file" }))
        val schema = OpenAICompatibleToolDescriptorSchemaGenerator().generate(tool.descriptor)
        val calls = schema.getValue("properties").jsonObject.getValue("calls").jsonObject
        assertEquals("array", calls.getValue("type").jsonPrimitive.content)
        assertTrue(calls.getValue("description").jsonPrimitive.content.contains("without surrounding quotes"))
        val members = calls.getValue("items").jsonObject.getValue("properties").jsonObject
        assertTrue(members.getValue("tool").jsonObject.getValue("description").jsonPrimitive.content.startsWith("Required."))
        val parameters = members.getValue("parameters").jsonObject
        assertEquals("object", parameters.getValue("type").jsonPrimitive.content)
        assertTrue(parameters.getValue("description").jsonPrimitive.content.contains("inside this object"))
    }

    @Test
    fun `published example uses only available tools and current argument shape`() {
        val tool = BatchTool(mapOf("read_file" to { "file" }, "list_directory" to { "directory" }))
        val example = tool.descriptor.description.lineSequence().single { it.startsWith("Example arguments: ") }
            .removePrefix("Example arguments: ")
        val args = Json.decodeFromString<BatchTool.Args>(example)
        assertEquals(listOf("read_file", "list_directory"), args.calls.map { it.tool })
        assertEquals(listOf("src/Main.kt", "src"), args.calls.map { it.parameters.path })
    }

    @Test
    fun `argument decoder rejects quoted arrays and incomplete call objects`() {
        val malformedCalls = listOf(
            """{"calls":"[{\"tool\":\"bash\",\"parameters\":{\"command\":\"pwd\"}}]"}""",
            """{"calls":[{"tool":"bash","command":"pwd"}]}""",
            """{"calls":[{"parameters":{"command":"pwd"}}]}""",
        )
        val json = Json { ignoreUnknownKeys = true }
        malformedCalls.forEach { assertFailsWith<SerializationException> { json.decodeFromString<BatchTool.Args>(it) } }
    }

    @Test
    fun `independent operations run concurrently and return in input order`() = runBlocking {
        val secondStarted = CompletableDeferred<Unit>()
        val tool = BatchTool(mapOf(
            "first" to { withTimeout(5_000) { secondStarted.await() }; "first output" },
            "second" to { secondStarted.complete(Unit); "second output" },
        ))
        val results = Json.decodeFromString<List<BatchTool.Result>>(tool.execute(BatchTool.Args(listOf(
            BatchTool.Call("first", empty), BatchTool.Call("second", empty),
        ))))
        assertEquals(listOf("first output", "second output"), results.map { it.output })
        assertEquals(listOf(0, 1), results.map { it.index })
    }

    @Test
    fun `failures and unavailable tools do not discard other results`() = runBlocking {
        val tool = BatchTool(mapOf("read_file" to { error("original failure") }, "list_directory" to { "listing" }))
        val results = Json.decodeFromString<List<BatchTool.Result>>(tool.execute(BatchTool.Args(listOf(
            BatchTool.Call("read_file", empty), BatchTool.Call("batch", empty), BatchTool.Call("list_directory", empty),
        ))))
        assertTrue(results[0].error.orEmpty().contains("original failure"))
        assertTrue(results[1].error.orEmpty().contains("not available"))
        assertEquals("listing", results[2].output)
    }

    @Test
    fun `cancellation propagates instead of becoming a successful batch result`() = runBlocking {
        val tool = BatchTool(mapOf("read_file" to { throw CancellationException("stopped") }))
        val error = kotlin.test.assertFails { tool.execute(BatchTool.Args(listOf(BatchTool.Call("read_file", empty)))) }
        assertTrue(error is CancellationException)
    }

    private fun registeredBatch(role: SubagentRole?, names: List<ToolName>): BatchTool =
        ToolFactory.build(
            toolNames = names, directories = listOf(System.getProperty("user.dir")), sessionId = "batch-test",
            historyStore = InMemoryHistoryStore(), eventBus = MutableSharedFlow(), modelContextWindow = null,
            newContextWindowFlag = AtomicBoolean(false), subagentRole = role,
        ).getTool("batch") as BatchTool

    @Test
    fun `researcher cannot acquire bash through batch`() = runBlocking {
        val tool = registeredBatch(SubagentRole.RESEARCHER, listOf(ToolName.BATCH, ToolName.READ_FILE, ToolName.BASH))
        val results = Json.decodeFromString<List<BatchTool.Result>>(tool.execute(BatchTool.Args(listOf(
            BatchTool.Call("bash", BatchTool.Parameters(command = "echo forbidden")),
        ))))
        assertTrue(results.single().error.orEmpty().contains("not available"))
    }

    @Test
    fun `batch honors tool selection and rejects background commands`() = runBlocking {
        val tool = registeredBatch(null, listOf(ToolName.BATCH, ToolName.BASH))
        val results = Json.decodeFromString<List<BatchTool.Result>>(tool.execute(BatchTool.Args(listOf(
            BatchTool.Call("read_file", BatchTool.Parameters(path = "build.gradle.kts")),
            BatchTool.Call("bash", BatchTool.Parameters(command = "echo background", background = true)),
        ))))
        assertTrue(results[0].error.orEmpty().contains("not available"))
        assertTrue(results[1].error.orEmpty().contains("foreground"))
        assertEquals("batch", tool.descriptor.name)
    }
}
