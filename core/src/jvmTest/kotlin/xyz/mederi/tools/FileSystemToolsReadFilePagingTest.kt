package xyz.mederi.tools

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory

/**
 * read_file 的 offset/max_lines 分页（1-based，对齐 opencode read.ts toModelContent）行为锁定：
 * - offset 1-based，输出每行带 `N: ` 前缀，header `Read file <path>, lines X-Y`
 * - truncated 时尾部 `[Output truncated. Continue reading with offset: <next>]`（next 1-based）
 * - offset 越界、空文件、max_lines=0 全量等边界
 */
class FileSystemToolsReadFilePagingTest {

    private fun tenLineFile(): File =
        createTempDirectory("readpage").toFile().let { dir ->
            File(dir, "f.txt").apply { writeText((0 until 10).joinToString("\n") { "line$it" }) }
        }

    private fun read(path: String, offset: Int = 1, maxLines: Int = 2000): String = runBlocking {
        FileSystemTools(allowedDirectories = listOf(File(path).parentFile.absolutePath))
            .ReadFileTool()
            .execute(FileSystemTools.ReadFileArgs(path = path, offset = offset, maxLines = maxLines))
    }

    @Test
    fun `read from start with truncation exposes next offset`() {
        val file = tenLineFile()

        val r = read(file.absolutePath, offset = 1, maxLines = 3)

        // displayPath 对 temp 文件返回 f.txt（相对 allowedDirectory）
        assertTrue("header 应报 lines 1-3: $r", r.contains("Read file f.txt, lines 1-3"))
        assertTrue("首行带 1: 前缀: $r", r.contains("1: line0"))
        assertTrue("第三行 line2 带 3: 前缀: $r", r.contains("3: line2"))
        assertTrue("line3 应被截断", !r.contains("line3"))
        assertTrue("应提示从 offset 4 续读: $r", r.contains("[Output truncated. Continue reading with offset: 4]"))
    }

    @Test
    fun `resume from returned offset continues at correct lines`() {
        val file = tenLineFile()

        val r = read(file.absolutePath, offset = 3, maxLines = 3)

        // offset=3(1-based) → start=2(0-based)，slice=lines[2,5) → line2,line3,line4
        assertTrue("header 应报 lines 3-5: $r", r.contains("Read file f.txt, lines 3-5"))
        assertTrue("3: line2 在列: $r", r.contains("3: line2"))
        assertTrue("5: line4 在列: $r", r.contains("5: line4"))
        assertTrue("line5 不在列", !r.contains("line5"))
        assertTrue("应提示从 offset 6 续读: $r", r.contains("Continue reading with offset: 6"))
    }

    @Test
    fun `middle chunk with no more lines omits next offset`() {
        val file = tenLineFile()

        // offset=7(1-based) maxLines=4 → start=6, end=10, slice=line6..line9，恰到尾
        val last = read(file.absolutePath, offset = 7, maxLines = 4)

        assertTrue("header 应报 lines 7-10: $last", last.contains("Read file f.txt, lines 7-10"))
        assertTrue("无 next offset", !last.contains("next offset"))
        assertTrue("无 truncated", !last.contains("truncated"))
        assertTrue("10: line9 在列: $last", last.contains("10: line9"))
    }

    @Test
    fun `maxLines 0 returns full file`() {
        val file = tenLineFile()

        val r = read(file.absolutePath, offset = 1, maxLines = 0)

        assertTrue("maxLines=0 返全量 header lines 1-10: $r", r.contains("Read file f.txt, lines 1-10"))
        assertTrue("无 truncated", !r.contains("truncated"))
        assertTrue("10: line9 在列: $r", r.contains("10: line9"))
    }

    @Test
    fun `offset beyond end reports boundary without throwing`() {
        val file = tenLineFile()

        val r = read(file.absolutePath, offset = 50)

        assertTrue("报告越界: $r", r.contains("Offset 50 is out of range for this file (10 lines)"))
    }

    @Test
    fun `empty file reports empty`() {
        val dir = createTempDirectory("readpage-empty").toFile()
        val file = File(dir, "empty.txt").apply { writeText("") }

        val r = read(file.absolutePath)

        assertTrue("空文件: $r", r.contains("Read file empty.txt, 0 lines"))
    }
}
