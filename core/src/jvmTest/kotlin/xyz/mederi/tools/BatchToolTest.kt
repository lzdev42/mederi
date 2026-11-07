package xyz.mederi.tools

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import xyz.mederi.domain.model.SubagentRole
import xyz.mederi.store.InMemoryHistoryStore

class BatchToolTest {
    private val empty = BatchTool.Parameters()

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
