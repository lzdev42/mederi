package xyz.mederi.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory

/**
 * AGENTS.md 子树懒发现回归：read_file / list_directory 成功后触发
 * AgentsSubtreeDiscovery 回调，新发现的内容追加到工具返回文本末尾。
 */
class FileSystemToolsAgentsDiscoveryTest {

    private fun tempProject(): File =
        createTempDirectory("proj").toFile()

    @Test
    fun `read_file 后新发现的 AGENTS_md 追加到返回文本`() {
        val project = tempProject()
        val target = File(project, "f.kt").apply { writeText("println()") }
        val discovered = xyz.mederi.project.AgentsFileLoader.AgentsFile(
            path = File(project, "AGENTS.md").absolutePath,
            relativePath = "AGENTS.md",
            content = "sub rules"
        )
        val tools = FileSystemTools(
            allowedDirectories = listOf(project.absolutePath),
            agentsDiscovery = { _ -> discovered }
        )

        val result = runBlocking { tools.ReadFileTool().execute(FileSystemTools.ReadFileArgs(path = target.absolutePath)) }

        // read_file 现在以 0-based 区间头行返回；懒发现仍应追加到尾部
        assertTrue(result.startsWith("read_file: lines[0, 1)"))
        assertTrue(result.contains("println()"))
        assertTrue(result.contains("--- AGENTS.md (AGENTS.md) ---"))
        assertTrue(result.endsWith("sub rules"))
    }

    @Test
    fun `回调返回 null 时返回文本不变`() {
        val project = tempProject()
        val target = File(project, "f.kt").apply { writeText("println()") }
        val tools = FileSystemTools(
            allowedDirectories = listOf(project.absolutePath),
            agentsDiscovery = { _ -> null }
        )

        val result = runBlocking { tools.ReadFileTool().execute(FileSystemTools.ReadFileArgs(path = target.absolutePath)) }

        // 无懒发现追加：返回即是 read_file 自身输出（header + 内容），不含 AGENTS.md 段
        assertTrue(result.startsWith("read_file: lines[0, 1)"))
        assertTrue(result.contains("println()"))
        assertTrue(!result.contains("AGENTS.md"))
    }

    @Test
    fun `list_directory 后同样触发懒发现`() {
        val project = tempProject()
        File(project, "a.txt").writeText("x")
        val discovered = xyz.mederi.project.AgentsFileLoader.AgentsFile(
            path = File(project, "AGENTS.md").absolutePath,
            relativePath = "AGENTS.md",
            content = "rules"
        )
        var accessed: String? = null
        val tools = FileSystemTools(
            allowedDirectories = listOf(project.absolutePath),
            agentsDiscovery = { p -> accessed = p; discovered }
        )

        val result = runBlocking { tools.ListDirectoryTool().execute(FileSystemTools.ListDirectoryArgs(path = "")) }

        assertTrue(result.contains("[FILE] a.txt"))
        assertTrue(result.contains("--- AGENTS.md (AGENTS.md) ---"))
        // 回调收到的是访问目录的绝对规范化路径
        assertEquals(project.absolutePath, accessed)
    }
}
