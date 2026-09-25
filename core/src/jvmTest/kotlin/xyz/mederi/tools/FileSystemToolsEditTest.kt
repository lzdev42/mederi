package xyz.mederi.tools

import ai.koog.agents.core.tools.ToolException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * edit_file 行为与并发写防护回归：
 * - 多处匹配默认报错（不静默改第一处）、replace_all 全量替换；
 * - FileWriteRegistry：占用中的文件直接拒绝、异常路径必释放、并发压力下无丢失更新。
 */
class FileSystemToolsEditTest {

    private val tempDirs = mutableListOf<File>()

    private fun tempProject(): File =
        createTempDirectory("edit-test").toFile().also { tempDirs.add(it) }

    @After
    fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
        // 注册表是进程级单例，测试之间必须确保无残留占用
        tempDirs.forEach { FileWriteRegistry.release(listOf(it)) }
    }

    // ==================== 匹配语义 ====================

    @Test
    fun `唯一匹配正常替换`() {
        val project = tempProject()
        val target = File(project, "a.kt").apply { writeText("fun main() {\n    println(1)\n}\n") }
        val tools = FileSystemTools(allowedDirectories = listOf(project.absolutePath))

        val result = runBlocking {
            tools.EditFileTool().execute(
                FileSystemTools.EditFileArgs(path = target.absolutePath, original = "println(1)", replacement = "println(2)")
            )
        }

        assertTrue(result.startsWith("Edited"))
        assertEquals("fun main() {\n    println(2)\n}\n", target.readText())
    }

    @Test
    fun `多处匹配且未开 replace_all 报错且文件不变`() {
        val project = tempProject()
        val target = File(project, "a.txt").apply { writeText("x = 1\ny = 2\nx = 1\n") }
        val tools = FileSystemTools(allowedDirectories = listOf(project.absolutePath))

        val error = runCatching {
            runBlocking {
                tools.EditFileTool().execute(
                    FileSystemTools.EditFileArgs(path = target.absolutePath, original = "x = 1", replacement = "x = 9")
                )
            }
        }.exceptionOrNull()

        assertTrue("multi-match must be rejected, got success", error is ToolException)
        assertTrue(error!!.message!!.contains("2 times"))
        // 文件保持原样
        assertEquals("x = 1\ny = 2\nx = 1\n", target.readText())
    }

    @Test
    fun `多处匹配开 replace_all 全部替换`() {
        val project = tempProject()
        val target = File(project, "a.txt").apply { writeText("x = 1\ny = 2\nx = 1\n") }
        val tools = FileSystemTools(allowedDirectories = listOf(project.absolutePath))

        val result = runBlocking {
            tools.EditFileTool().execute(
                FileSystemTools.EditFileArgs(
                    path = target.absolutePath, original = "x = 1", replacement = "x = 9", replaceAll = true
                )
            )
        }

        assertTrue(result.contains("replaced 2 occurrence(s)"))
        assertEquals("x = 9\ny = 2\nx = 9\n", target.readText())
    }

    // ==================== 并发写防护 ====================

    @Test
    fun `edit 占用中的文件直接报错`() {
        val project = tempProject()
        val target = File(project, "a.txt").apply { writeText("hello") }
        val tools = FileSystemTools(allowedDirectories = listOf(project.absolutePath))

        // 模拟另一个并发调用先占住了这个文件
        val conflict = FileWriteRegistry.tryAcquire(listOf(target))
        assertTrue(conflict == null)

        try {
            val result = runBlocking {
                tools.EditFileTool().execute(
                    FileSystemTools.EditFileArgs(path = target.absolutePath, original = "hello", replacement = "world")
                )
            }
            assertTrue(result.startsWith("Error:"))
            assertTrue(result.contains("concurrent"))
            // 文件未被改动
            assertEquals("hello", target.readText())
        } finally {
            FileWriteRegistry.release(listOf(target))
        }
    }

    @Test
    fun `write 占用中的文件直接报错`() {
        val project = tempProject()
        val target = File(project, "a.txt").apply { writeText("hello") }
        val tools = FileSystemTools(allowedDirectories = listOf(project.absolutePath))

        FileWriteRegistry.tryAcquire(listOf(target))
        try {
            val result = runBlocking {
                tools.WriteFileTool().execute(
                    FileSystemTools.WriteFileArgs(path = target.absolutePath, content = "new")
                )
            }
            assertTrue(result.startsWith("Error:"))
            assertEquals("hello", target.readText())
        } finally {
            FileWriteRegistry.release(listOf(target))
        }
    }

    @Test
    fun `edit 校验失败后注册表必然释放`() {
        val project = tempProject()
        val target = File(project, "a.txt").apply { writeText("hello") }
        val tools = FileSystemTools(allowedDirectories = listOf(project.absolutePath))

        // original 找不到 → validate 抛异常 → finally 释放
        runCatching {
            runBlocking {
                tools.EditFileTool().execute(
                    FileSystemTools.EditFileArgs(path = target.absolutePath, original = "not-exist", replacement = "x")
                )
            }
        }

        // 注册表未残留：可立即再次占用
        val reAcquired = FileWriteRegistry.tryAcquire(listOf(target))
        assertTrue("registry must be released after validation failure", reAcquired == null)
        FileWriteRegistry.release(listOf(target))

        // 且再次 edit 正常执行
        val result = runBlocking {
            tools.EditFileTool().execute(
                FileSystemTools.EditFileArgs(path = target.absolutePath, original = "hello", replacement = "world")
            )
        }
        assertTrue(result.startsWith("Edited"))
        assertEquals("world", target.readText())
    }

    @Test
    fun `并发压力 无丢失更新`() {
        val project = tempProject()
        val target = File(project, "a.txt")
        val n = 50
        // 行首统一补一个换行，保证每个 "\nLi\n" 标记（含首行）都能精确匹配且唯一
        target.writeText("\n" + (0 until n).joinToString("\n") { "L$it" } + "\n")
        // 两个实例模拟并行子代理：各自 turn 各自 FileSystemTools
        val toolsA = FileSystemTools(allowedDirectories = listOf(project.absolutePath))
        val toolsB = FileSystemTools(allowedDirectories = listOf(project.absolutePath))

        // Dispatchers.IO 多线程池：真正并行执行，制造注册表竞争
        val results = runBlocking(Dispatchers.IO) {
            (0 until n).map { i ->
                async {
                    val tools = if (i % 2 == 0) toolsA else toolsB
                    tools.EditFileTool().execute(
                        FileSystemTools.EditFileArgs(
                            path = target.absolutePath, original = "\nL$i\n", replacement = "\nX$i\n"
                        )
                    )
                }
            }.awaitAll()
        }

        val succeeded = results.count { it.startsWith("Edited") }
        val rejected = results.count { it.startsWith("Error:") }
        assertEquals(n, succeeded + rejected)

        // 无丢失更新（调度无关不变量）：每个「成功」的编辑都必须在最终文件里——
        // 若注册表失效（并发读改写互相覆盖），重叠的成功编辑会丢失标记；
        // 被拒绝（Error）的编辑合法缺席
        val finalContent = target.readText()
        (0 until n).forEach { i ->
            if (results[i].startsWith("Edited")) {
                assertTrue("edit $i reported success but its change was lost", finalContent.contains("\nX$i\n"))
            }
        }
        // 有重叠才有意义：注册表生效时重叠方被拒绝，成功数 < n（多线程池下 50 并发写几乎必然重叠；
        // 即使个别环境完全串行，上面的不变量仍然成立，本断言容忍退化为 succeeded == n）
        assertTrue(succeeded in 1..n)
    }

    @Test
    fun `不同文件互不阻塞`() {
        val project = tempProject()
        val fileA = File(project, "a.txt").apply { writeText("A") }
        val fileB = File(project, "b.txt").apply { writeText("B") }
        val tools = FileSystemTools(allowedDirectories = listOf(project.absolutePath))

        // fileA 被占用不应影响 fileB 的写入
        FileWriteRegistry.tryAcquire(listOf(fileA))
        try {
            val result = runBlocking {
                tools.EditFileTool().execute(
                    FileSystemTools.EditFileArgs(path = fileB.absolutePath, original = "B", replacement = "C")
                )
            }
            assertTrue(result.startsWith("Edited"))
        } finally {
            FileWriteRegistry.release(listOf(fileA))
        }
    }
}
