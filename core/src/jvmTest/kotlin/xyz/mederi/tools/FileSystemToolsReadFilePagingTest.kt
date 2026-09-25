package xyz.mederi.tools

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory

/**
 * read_file 的 offset/max_lines 分页（0-based 统一）行为锁定：
 * - 返回区间 [start, end) 前闭后开（subList 语义），header 携带 next offset = end（续读零换算）
 * - offset 从文件中部起读、超界、空文件、max_lines=0 全量等边界
 */
class FileSystemToolsReadFilePagingTest {

    private fun tenLineFile(): File =
        createTempDirectory("readpage").toFile().let { dir ->
            File(dir, "f.txt").apply { writeText((0 until 10).joinToString("\n") { "line$it" }) }
        }

    private fun read(path: String, offset: Int = 0, maxLines: Int = 2000): String = runBlocking {
        FileSystemTools(allowedDirectories = listOf(File(path).parentFile.absolutePath))
            .ReadFileTool()
            .execute(FileSystemTools.ReadFileArgs(path = path, offset = offset, maxLines = maxLines))
    }

    @Test
    fun `read from start with truncation exposes next offset`() {
        val file = tenLineFile()

        val r = read(file.absolutePath, maxLines = 3)

        assertTrue("header 应带 next offset=3: $r", r.startsWith("read_file: lines[0, 3) (0-based, end-exclusive), total=10, next offset=3"))
        assertTrue("首行 line0 在列", r.contains("line0"))
        assertTrue("第三行（0-based index 2）line2 在列", r.contains("line2"))
        assertTrue("line3 应被截断", !r.contains("line3"))
        assertTrue("应提示从 offset 3 续读: $r", r.contains("continue from offset 3"))
    }

    @Test
    fun `resume from returned offset continues at correct lines`() {
        val file = tenLineFile()

        val r = read(file.absolutePath, offset = 3, maxLines = 3)

        assertTrue("header 应报 lines[3,6) next offset=6: $r", r.startsWith("read_file: lines[3, 6) (0-based, end-exclusive), total=10, next offset=6"))
        assertTrue("line3 在列", r.contains("line3"))
        assertTrue("line5 在列", r.contains("line5"))
        assertTrue("line6 不在列", !r.contains("line6"))
    }

    @Test
    fun `middle chunk with no more lines omits next offset`() {
        val file = tenLineFile()

        val last = read(file.absolutePath, offset = 6, maxLines = 4) // 6..9 恰好到尾

        assertTrue("到文件尾无 next offset: $last", last.startsWith("read_file: lines[6, 10) (0-based, end-exclusive), total=10"))
        assertTrue("无 next offset", !last.contains("next offset"))
        assertTrue("无 truncated", !last.contains("truncated"))
        assertTrue("line9 在列", last.contains("line9"))
    }

    @Test
    fun `maxLines 0 returns full file`() {
        val file = tenLineFile()

        val r = read(file.absolutePath, offset = 0, maxLines = 0)

        assertTrue("maxLines=0 返全量: $r", r.startsWith("read_file: lines[0, 10) (0-based, end-exclusive), total=10"))
        assertTrue("无 truncated", !r.contains("truncated"))
        assertTrue("line9 在列", r.contains("line9"))
    }

    @Test
    fun `offset beyond end reports boundary without throwing`() {
        val file = tenLineFile()

        val r = read(file.absolutePath, offset = 50)

        assertTrue("报告越界: $r", r.contains("offset 50 beyond end of file; file has 10 lines"))
    }

    @Test
    fun `empty file reports empty`() {
        val dir = createTempDirectory("readpage-empty").toFile()
        val file = File(dir, "empty.txt").apply { writeText("") }

        val r = read(file.absolutePath)

        assertTrue("空文件: $r", r.contains("(empty file, 0 lines)"))
    }
}