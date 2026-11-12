package xyz.mederi.mcp.engine

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import xyz.mederi.mcp.servers.domain.McpConnection
import xyz.mederi.mcp.servers.domain.McpServerConfig
import xyz.mederi.prompt.SystemPrompts
import xyz.mederi.store.InMemoryMcpServersStore
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpConnectionFeedbackTest {
    @Test
    fun missingUvReachesTheModelWithoutExposingMcpCredentials() = runBlocking {
        val enabled = McpServerConfig(
            name = "python-search",
            raw = JsonObject(emptyMap()),
            connection = McpConnection.Stdio(
                command = "uvx",
                args = listOf("example-mcp", "--token", "argument-secret"),
                env = mapOf("PATH" to "", "TOKEN" to "environment-secret"),
            ),
        )
        val store = InMemoryMcpServersStore()
        store.save(enabled)
        store.save(enabled.copy(name = "disabled-server", enabled = false))

        val session = McpConnector(store).openSession()
        try {
            assertTrue(session.tools.isEmpty())
            val prompt = SystemPrompts.withMcpConnectionFailures("Base system prompt", session.connectionFailures)
            assertTrue(prompt.startsWith("Base system prompt"))
            assertContains(prompt, "python-search")
            assertContains(prompt, "uv")
            assertContains(prompt, "PATH")
            assertFalse(prompt.contains("argument-secret"))
            assertFalse(prompt.contains("environment-secret"))
            assertFalse(prompt.contains("disabled-server"))
        } finally {
            session.close()
        }
    }
}
