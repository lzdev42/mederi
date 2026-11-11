package xyz.mederi.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking

/**
 * 锁定真实事故 `Path is not a file: !` 的两个成因修复（错误消息给 AI 全英文）：
 * - read_file 漏传/空 path 必须在入口报清晰英文错误，而不是把空路径解析成项目根目录后
 *   抛出冒号后为空的消息（叠加 Koog 模板的 `!` 后缀变成 `Path is not a file: !`）。
 * - displayPath 对允许目录本身不得返回空串，必须回退到绝对路径。
 */
class FileSystemToolsPathValidationTest {

    private fun tools(dir: File) = FileSystemTools(allowedDirectories = listOf(dir.absolutePath))

    @Test
    fun `blank path is rejected with an explicit English error`() = runBlocking {
        val ex = kotlin.test.assertFailsWith<IllegalArgumentException> {
            tools(createTempDirectory("readpath").toFile()).ReadFileTool()
                .execute(FileSystemTools.ReadFileArgs(path = ""))
        }
        assertTrue(
            "错误消息应指明 path 为空并指引填写路径，实际: ${ex.message}",
            ex.message!!.contains("read_file requires a non-empty 'path' argument")
        )
    }

    @Test
    fun `missing path fails argument decoding instead of silently defaulting to empty`() {
        // 模型漏传 path 的形态：{"offset":1,"max_lines":100}
        val decoded = Json.parseToJsonElement("""{"offset":1,"max_lines":100}""")
        val ex = kotlin.test.assertFails {
            Json.decodeFromJsonElement<FileSystemTools.ReadFileArgs>(decoded)
        }
        assertTrue(
            "漏传 path 应报 required 字段错误，实际: ${ex.message}",
            ex.message!!.contains("Field 'path' is required")
        )
    }

    @Test
    fun `directory path error shows the resolved path not an empty display string`() = runBlocking {
        val dir = createTempDirectory("readpath").toFile()
        val ex = kotlin.test.assertFailsWith<IllegalArgumentException> {
            tools(dir).ReadFileTool().execute(FileSystemTools.ReadFileArgs(path = dir.absolutePath))
        }
        // displayPath 对允许目录本身回退绝对路径，错误消息不得以 "not a file: " 结尾
        assertTrue(
            "错误消息应包含解析后的绝对路径，实际: ${ex.message}",
            ex.message!!.contains(dir.absolutePath)
        )
        assertTrue(
            "错误消息冒号后不得为空（旧 bug 会拼成 `Path is not a file: !`）",
            !ex.message!!.endsWith("Path is not a file: ")
        )
    }

    @Test
    fun `batch description shows an inline parameters example`() {
        val tool = ToolFactory.build(
            toolNames = listOf(ToolName.BATCH, ToolName.READ_FILE, ToolName.BASH),
            directories = listOf(System.getProperty("user.dir")), sessionId = "desc-test",
            historyStore = xyz.mederi.store.InMemoryHistoryStore(), eventBus = kotlinx.coroutines.flow.MutableSharedFlow(),
            modelContextWindow = null, newContextWindowFlag = java.util.concurrent.atomic.AtomicBoolean(false),
            subagentRole = null,
        ).getTool("batch")
        assertTrue(
            "batch 描述应包含 parameters 嵌套的内联 JSON 示例，实际: ${tool.descriptor.description}",
            tool.descriptor.description.contains("\"parameters\"")
        )
    }
}
