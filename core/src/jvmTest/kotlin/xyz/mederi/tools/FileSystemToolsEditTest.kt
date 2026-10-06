package xyz.mederi.tools

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
 * edit_file 行为与并发写防护回归（照抄 opencode edit.ts 三级降级 + CRLF + no-op）：
 * - 三级匹配降级（exact → unicode 归一化 → 行级容差）、CRLF 行尾归一化、no-op 拦截；
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

        val result = runBlocking {
            tools.EditFileTool().execute(
                FileSystemTools.EditFileArgs(path = target.absolutePath, original = "x = 1", replacement = "x = 9")
            )
        }

        // 照抄 opencode 文案
        assertTrue("multi-match must be rejected: $result", result.contains("Found 2 matches"))
        assertTrue(result.contains("expected exactly one"))
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

    // ==================== 三级降级（照抄 opencode）====================

    @Test
    fun `智能引号降级匹配`() {
        val project = tempProject()
        // 文件用直引号，original 传智能引号（U+2018/U+201C）
        val target = File(project, "a.txt").apply { writeText("it's \"code\" here\n") }
        val tools = FileSystemTools(allowedDirectories = listOf(project.absolutePath))

        val result = runBlocking {
            tools.EditFileTool().execute(
                FileSystemTools.EditFileArgs(
                    path = target.absolutePath,
                    original = "it’s “code” here",
                    replacement = "it’s “code” done"
                )
            )
        }

        assertTrue("fuzzy 命中应成功: $result", result.startsWith("Edited"))
        assertTrue("应标注 fuzzy 降级: $result", result.contains("matched via fuzzy fallback"))
        assertEquals("it’s “code” done\n", target.readText())
    }

    @Test
    fun `全角空格降级匹配`() {
        val project = tempProject()
        // 文件用普通空格，original 含 U+00A0 不间断空格
        val target = File(project, "a.txt").apply { writeText("val a = 1\n") }
        val tools = FileSystemTools(allowedDirectories = listOf(project.absolutePath))

        // replacement 用普通空格（fuzzy 只归一化查找 oldString，replacement 原样插入——照抄 opencode）
        val result = runBlocking {
            tools.EditFileTool().execute(
                FileSystemTools.EditFileArgs(
                    path = target.absolutePath,
                    original = "val a\u00A0= 1",
                    replacement = "val b = 1"
                )
            )
        }

        assertTrue("全角空格 fuzzy 命中: $result", result.startsWith("Edited"))
        assertTrue(result.contains("fuzzy fallback"))
        assertEquals("val b = 1\n", target.readText())
    }

    @Test
    fun `破折号降级匹配`() {
        val project = tempProject()
        // 文件用连字符 -，original 用 en-dash – (U+2013)
        val target = File(project, "a.txt").apply { writeText("a - b\n") }
        val tools = FileSystemTools(allowedDirectories = listOf(project.absolutePath))

        // replacement 用连字符（fuzzy 只归一化查找 oldString，replacement 原样插入——照抄 opencode）
        val result = runBlocking {
            tools.EditFileTool().execute(
                FileSystemTools.EditFileArgs(
                    path = target.absolutePath,
                    original = "a – b",
                    replacement = "a - c"
                )
            )
        }

        assertTrue("破折号 fuzzy 命中: $result", result.startsWith("Edited"))
        assertTrue(result.contains("fuzzy fallback"))
        assertEquals("a - c\n", target.readText())
    }

    // ==================== CRLF 归一化 ====================

    @Test
    fun `CRLF 文件用 LF original 匹配且保留 CRLF`() {
        val project = tempProject()
        val target = File(project, "a.txt").apply { writeText("line1\r\nline2\r\n") }
        val tools = FileSystemTools(allowedDirectories = listOf(project.absolutePath))

        // original 用 LF，文件是 CRLF——归一化后应匹配，替换结果保留 CRLF
        val result = runBlocking {
            tools.EditFileTool().execute(
                FileSystemTools.EditFileArgs(
                    path = target.absolutePath,
                    original = "line1\nline2",
                    replacement = "lineX\nline2"
                )
            )
        }

        assertTrue("CRLF 归一化匹配: $result", result.startsWith("Edited"))
        // 替换后文件仍全 CRLF
        assertEquals("lineX\r\nline2\r\n", target.readText())
    }

    // ==================== no-op 拦截 ====================

    @Test
    fun `no-op original 等于 replacement 被拒绝`() {
        val project = tempProject()
        val original = "same text"
        val target = File(project, "a.txt").apply { writeText("$original\n") }
        val tools = FileSystemTools(allowedDirectories = listOf(project.absolutePath))

        val result = runBlocking {
            tools.EditFileTool().execute(
                FileSystemTools.EditFileArgs(
                    path = target.absolutePath, original = original, replacement = original
                )
            )
        }

        assertTrue("no-op 应拒绝: $result", result.contains("identical"))
        assertTrue(result.contains("no changes"))
        // 文件不变
        assertEquals("$original\n", target.readText())
    }

    // ==================== 三级全败通用错误 ====================

    @Test
    fun `三级全败返回 opencode 通用错误`() {
        val project = tempProject()
        val target = File(project, "a.txt").apply { writeText("completely different content\n") }
        val tools = FileSystemTools(allowedDirectories = listOf(project.absolutePath))

        val result = runBlocking {
            tools.EditFileTool().execute(
                FileSystemTools.EditFileArgs(
                    path = target.absolutePath, original = "not anywhere near", replacement = "x"
                )
            )
        }

        assertTrue("应返回 opencode 通用错误: $result", result.contains("Could not find oldString"))
        assertTrue(result.contains("match exactly"))
        // 文件不变
        assertEquals("completely different content\n", target.readText())
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

        // original 找不到 → 返回错误字符串 → finally 释放
        runBlocking {
            tools.EditFileTool().execute(
                FileSystemTools.EditFileArgs(path = target.absolutePath, original = "not-exist", replacement = "x")
            )
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

        // 无丢失更新（调度无关不变量）：每个「成功」的编辑都必须在最终文件里
        val finalContent = target.readText()
        (0 until n).forEach { i ->
            if (results[i].startsWith("Edited")) {
                assertTrue("edit $i reported success but its change was lost", finalContent.contains("\nX$i\n"))
            }
        }
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
