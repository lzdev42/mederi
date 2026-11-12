package xyz.mederi.mcp.runtime

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import xyz.mederi.mcp.engine.McpRuntimeUnavailableException
import xyz.mederi.mcp.engine.mcpStdioTransport
import xyz.mederi.tools.ShellTools
import xyz.mederi.tools.sandbox.CommandSandbox
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class McpRuntimeTest {
    @Test
    fun emptyPathReportsEachMissingComponent() {
        assertEquals(McpRuntime.Component.entries.toSet(), McpRuntime.missing(mapOf("PATH" to "")))
    }

    @Test
    fun detectsRuntimesInstalledOnTheHost() {
        assumeTrue("Requires Node.js, Python 3 and Git on the host", System.getenv("MEDERI_TEST_SYSTEM_RUNTIME") == "1")
        assertEquals(emptySet(), McpRuntime.missing())
    }

    @Test
    fun startsNodeMcpAndListsTools() = runBlocking {
        assumeTrue("Requires system Node.js", System.getenv("MEDERI_TEST_SYSTEM_RUNTIME") == "1")
        val server = """
            const input = require('node:readline').createInterface({input: process.stdin});
            input.on('line', line => {
              const request = JSON.parse(line);
              if (request.id === undefined) return;
              const result = request.method === 'initialize'
                ? {protocolVersion: request.params.protocolVersion, capabilities: {tools: {}},
                   serverInfo: {name: 'system-node', version: '1'}}
                : {tools: [{name: 'smoke', inputSchema: {type: 'object'}}]};
              console.log(JSON.stringify({jsonrpc: '2.0', id: request.id, result}));
            });
        """.trimIndent()
        val client = Client(Implementation("runtime-test", "1"))
        val transport = mcpStdioTransport("node", listOf("-e", server), emptyMap())
        try {
            withTimeout(15_000) {
                client.connect(transport)
                assertEquals(listOf("smoke"), client.listTools().tools.map { it.name })
            }
        } finally {
            try { client.close() } finally { transport.close() }
        }
    }

    @Test
    fun runsPythonWorkThroughTheShellTool() = runBlocking {
        assumeTrue("Requires system Python 3", System.getenv("MEDERI_TEST_SYSTEM_RUNTIME") == "1")
        val directory = Files.createTempDirectory("system-python-test").toFile().canonicalFile
        try {
            val sandbox = CommandSandbox(listOf(directory.path), emptyList())
            val status = sandbox.status()
            assumeTrue(status.detail, status.available)
            val result = ShellTools(listOf(directory.path), sandbox).runCommand(
                """python3 -c 'import json; print(sum(json.loads("[12, 30]")))'"""
            )
            assertEquals(0, result.exitCode, result.output)
            assertContains(result.output, "42")
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun missingUvExplainsHowToRunTheAffectedMcpServer() {
        val error = assertFailsWith<McpRuntimeUnavailableException> {
            McpRuntime.stdioCommandLine("uvx", listOf("example-mcp"), mapOf("PATH" to ""))
        }
        assertContains(error.message.orEmpty(), "Install it and add it to PATH")
    }

    @Test
    fun requiresGitButNotUv() {
        assumeTrue("The command fixtures use a Unix shell", !System.getProperty("os.name").startsWith("Windows"))
        val bin = Files.createTempDirectory("system-runtime-test").toFile()
        try {
            fun addExecutable(name: String) {
                bin.resolve(name).apply {
                    writeText("#!/bin/sh\nexit 0\n")
                    check(setExecutable(true))
                }
            }
            listOf("node", "npm", "npx", "python3").forEach(::addExecutable)
            val environment = mapOf("PATH" to bin.absolutePath)
            assertEquals(setOf(McpRuntime.Component.GIT), McpRuntime.missing(environment))
            addExecutable("git")
            assertEquals(emptySet(), McpRuntime.missing(environment))
            addExecutable("uv")
            assertEquals(
                listOf(bin.resolve("uv").absolutePath, "tool", "run", "example-mcp"),
                McpRuntime.stdioCommandLine("uvx", listOf("example-mcp"), environment),
            )
        } finally {
            bin.deleteRecursively()
        }
    }
}
