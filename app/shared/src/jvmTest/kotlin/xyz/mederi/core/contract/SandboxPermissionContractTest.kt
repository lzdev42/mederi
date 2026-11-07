package xyz.mederi.core.contract

import kotlinx.serialization.json.Json
import xyz.mederi.core.contract.models.ExecutionApprovalRequest
import xyz.mederi.core.contract.models.ExecutionPermissionMode
import xyz.mederi.core.contract.models.SandboxSettings
import kotlin.test.Test
import kotlin.test.assertEquals

class SandboxPermissionContractTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun coreAndContractModesAndSettingsMatchOnWire() {
        assertEquals(xyz.mederi.tools.sandbox.ExecutionPermissionMode.entries.map { it.name },
            ExecutionPermissionMode.entries.map { it.name })
        val core = xyz.mederi.tools.sandbox.SandboxSettings(
            mode = xyz.mederi.tools.sandbox.ExecutionPermissionMode.FULL_ACCESS,
            extraWritablePaths = listOf("/path with spaces", "/extra"), version = 7,
            deniedPaths = listOf("/private/project"), allowedCommands = listOf("git status"),
            deniedCommands = listOf("git push"))
        val wire = json.encodeToString(xyz.mederi.tools.sandbox.SandboxSettings.serializer(), core)
        assertEquals(SandboxSettings(ExecutionPermissionMode.FULL_ACCESS, core.extraWritablePaths,
            version = 7, deniedPaths = core.deniedPaths, allowedCommands = core.allowedCommands, deniedCommands = core.deniedCommands),
            json.decodeFromString<SandboxSettings>(wire))
        assertEquals(core, json.decodeFromString<xyz.mederi.tools.sandbox.SandboxSettings>(
            json.encodeToString(SandboxSettings.serializer(), json.decodeFromString<SandboxSettings>(wire))))
    }

    @Test
    fun coreAndContractExecutionRequestFieldsMatchOnWire() {
        val core = xyz.mederi.tools.sandbox.ExecutionApprovalRequest("request", "parent", "child", "bash",
            "printf 'hello'", listOf("/outside/a", "/outside/b"), "/project", "unsandboxed", "Need permissions")
        val wire = json.encodeToString(xyz.mederi.tools.sandbox.ExecutionApprovalRequest.serializer(), core)
        val contract = json.decodeFromString<ExecutionApprovalRequest>(wire)
        assertEquals(ExecutionApprovalRequest("request", "parent", "child", "bash", "printf 'hello'",
            listOf("/outside/a", "/outside/b"), "/project", "unsandboxed", "Need permissions"), contract)
        assertEquals(core, json.decodeFromString<xyz.mederi.tools.sandbox.ExecutionApprovalRequest>(
            json.encodeToString(ExecutionApprovalRequest.serializer(), contract)))
    }
}
