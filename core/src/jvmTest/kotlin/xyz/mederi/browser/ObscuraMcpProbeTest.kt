package xyz.mederi.browser

import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import xyz.mederi.browser.obscura.ObscuraMcpClient

/**
 * Obscura MCP 真实二进制探针（ST2 采集，collector 观测协议版）：全程无网络。
 *
 * - 二进制定位：从 user.dir 逐级向上（≤6 级）找 release/xyz.mederi.app/Contents/Resources/obscura；
 *   找不到或不可执行则整类跳过（assumeTrue）。
 * - 单测试方法全流程：累积 MutableMap<String,String>，每完成一步整文件重写
 *   core/build/obscura-probe.json（kotlinx.serialization 构造，禁止手拼 JSON 字符串）；
 *   失败写实际值并让测试失败（不静默放水改断言）。
 * - 全部工具名逐行写 core/build/obscura-tools.txt（ST3 映射对表用）。
 * - 导航只用 about:blank + tools/list，全部本地无网络。
 */
class ObscuraMcpProbeTest {

    private var client: ObscuraMcpClient? = null

    private val probeFile: File = File("build/obscura-probe.json")
    private val toolsFile: File = File("build/obscura-tools.txt")
    private val observations = linkedMapOf<String, String>()

    private val json = Json { prettyPrint = true }

    /** 每完成一步整文件重写观测 JSON（kotlinx.serialization，不手拼字符串）。 */
    private fun persist() {
        probeFile.parentFile?.mkdirs()
        val obj = JsonObject(observations.mapValues { (_, v) -> JsonPrimitive(v) })
        probeFile.writeText(json.encodeToString(JsonObject.serializer(), obj))
    }

    private fun record(key: String, value: String) {
        observations[key] = value
        persist()
    }

    @AfterTest
    fun after() {
        runBlocking {
            runCatching { client?.close() }
        }
    }

    @Test
    fun `probe writes observation json`() = runBlocking {
        // ---- 定位二进制（≤6 级向上）----
        val binary = locateObscura()
        assumeTrue("obscura binary not found under release/xyz.mederi.app/Contents/Resources", binary != null)
        val binaryPath: String = binary!!
        val c = ObscuraMcpClient(binaryPath)
        client = c

        // ---- st2_init：start + listTools 握手 ----
        val tools = try {
            c.start()
            c.listTools()
        } catch (e: Exception) {
            record("st2_init", "handshake-failed:${e.message ?: e.javaClass.simpleName}")
            throw AssertionError("st2_init handshake failed: ${e.message}", e)
        }
        record("st2_init", "connected")
        val toolNames = tools.map { it.name }
        toolsFile.parentFile?.mkdirs()
        toolsFile.writeText(toolNames.joinToString("\n") + "\n")

        // ---- st2_tools：三工具齐（顺序也需是前三，与批准 expected 对齐）----
        val required = listOf("browser_navigate", "browser_snapshot", "browser_click")
        val missing = required.filterNot { it in toolNames }
        if (missing.isEmpty() && toolNames.take(3) == required) {
            record("st2_tools", required.joinToString(","))
        } else {
            val actual = if (missing.isNotEmpty()) "missing:${missing.joinToString(",")}" else "order:${toolNames.take(3).joinToString(",")}"
            record("st2_tools", actual)
            throw AssertionError("st2_tools mismatch: $actual (all=$toolNames)")
        }

        // ---- st2_blank：about:blank 导航 + snapshot 全本地 ----
        val blankOk = try {
            val nav = c.callTool("browser_navigate", JsonObject(mapOf("url" to JsonPrimitive("about:blank"))))
            val snap = c.callTool("browser_snapshot", JsonObject(mapOf("max_chars" to JsonPrimitive(20000))))
            val navText = nav.content.filterIsInstance<TextContent>().joinToString("")
            val snapText = snap.content.filterIsInstance<TextContent>().joinToString("")
            nav.isError != true && snap.isError != true && snapText.isNotBlank()
        } catch (e: Exception) {
            record("st2_blank", "exception:${e.message ?: e.javaClass.simpleName}")
            throw AssertionError("st2_blank exception", e)
        }
        if (blankOk) {
            record("st2_blank", "url=about:blank;snapshot=yes")
        } else {
            record("st2_blank", "url=about:blank;snapshot=no")
            throw AssertionError("st2_blank failed: navigate/snapshot not ok")
        }

        // ---- st2_reap：close 后 ≤3s 进程退出 ----
        c.close()
        client = null
        val proc = c.process
        val exited = if (proc == null) {
            false
        } else {
            val deadline = System.currentTimeMillis() + 3000
            var alive = proc.isAlive
            while (alive && System.currentTimeMillis() < deadline) {
                Thread.sleep(100)
                alive = proc.isAlive
            }
            !alive
        }
        if (exited) {
            record("st2_reap", "process-exited")
        } else {
            record("st2_reap", "still-alive")
            throw AssertionError("st2_reap failed: child process still alive after close")
        }

        // ---- st2_stealth：永远 mcp --stealth --allow-private-network（不可配）----
        val stealthArgs = c.commandLine.drop(1).joinToString(" ")
        record("st2_stealth", stealthArgs)
        if (stealthArgs != "mcp --stealth --allow-private-network") {
            throw AssertionError("st2_stealth mismatch: $stealthArgs")
        }
    }

    private fun locateObscura(): String? {
        var dir: File? = File(System.getProperty("user.dir"))
        repeat(7) {
            val d = dir ?: return null
            val candidate = File(d, "release/xyz.mederi.app/Contents/Resources/obscura")
            if (candidate.isFile && candidate.canExecute()) return candidate.canonicalPath
            dir = d.parentFile
        }
        return null
    }
}
