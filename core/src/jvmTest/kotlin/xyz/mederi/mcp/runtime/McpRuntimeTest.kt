package xyz.mederi.mcp.runtime

import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class McpRuntimeTest {
    @Test
    fun emptyPathReportsEachMissingComponent() {
        assertEquals(McpRuntime.Component.entries.toSet(), McpRuntime.missing(mapOf("PATH" to "")))
    }

    @Test
    fun portableInstallProvidesCommandsUsedByMcp() {
        val runtimeHome = System.getenv("MEDERI_TEST_RUNTIME_HOME")
        assumeTrue("Run the runtime installer smoke test first", runtimeHome != null)
        val root = File(checkNotNull(runtimeHome))
        val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val path = listOf(
            root.resolve(if (windows) "node" else "node/bin").path,
            root.resolve("python-bin").path,
            root.resolve("uv").path,
        ).joinToString(File.pathSeparator)
        val env = McpRuntime.environment(mapOf("PATH" to path))
        assertEquals(emptySet(), McpRuntime.missing(env))

        if (windows) {
            val arguments = listOf("-y", "example", "a & b")
            assertEquals(
                listOf(root.resolve("node/node.exe").path, root.resolve("node/node_modules/npm/bin/npx-cli.js").path) + arguments,
                McpRuntime.commandLine("npx", arguments, env),
            )
        }
    }
}
