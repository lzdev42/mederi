package xyz.mederi.core.contract

import kotlinx.coroutines.test.runTest
import xyz.mederi.core.contract.dto.CreateProjectInput
import xyz.mederi.core.contract.models.ExecutionPermissionMode
import xyz.mederi.core.contract.models.SandboxSettings
import xyz.mederi.core.mock.MockAiCore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SandboxProjectMockTest {
    @Test
    fun projectSettingsAndVersionsAreIsolatedAndUnknownProjectsFail() = runTest {
        val mock = MockAiCore()
        val first = mock.createProject(CreateProjectInput("first", "/mock/first")).getOrThrow()
        val second = mock.createProject(CreateProjectInput("second", "/mock/second")).getOrThrow()
        val input = SandboxSettings(ExecutionPermissionMode.FULL_ACCESS,
            deniedPaths = listOf("/mock/private"), allowedCommands = listOf("git status"), deniedCommands = listOf("git push"))
        val saved = mock.updateSandboxSettings(first.id, input).getOrThrow()
        assertEquals(input.copy(version = 1), saved)
        assertEquals(saved, mock.getSandboxSettings(first.id).getOrThrow())
        assertEquals(SandboxSettings(), mock.getSandboxSettings(second.id).getOrThrow())
        assertTrue(mock.updateSandboxSettings(first.id, input).isFailure)
        assertEquals(saved, mock.getSandboxSettings(first.id).getOrThrow())
        assertTrue(mock.getSandboxSettings("missing").isFailure)
        assertTrue(mock.updateSandboxSettings("missing", input).isFailure)
        assertTrue(mock.getSandboxStatus("missing").isFailure)
    }
}
