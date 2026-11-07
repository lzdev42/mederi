package xyz.mederi.ui.settings

import kotlinx.coroutines.runBlocking
import xyz.mederi.core.contract.AiCore
import xyz.mederi.core.contract.models.*
import xyz.mederi.core.mock.MockAiCore
import kotlin.test.*

class DraftSubagentSettingsTest {
    @Test
    fun resetRemovesOnlyDraftOverridesAndEmptyDraftDoesNotWrite() = runBlocking {
        val defaults = listOf(SubagentConfigItem("EXECUTOR", "Executor", "", modelId = "default"))
        val draft = DraftSubagentSettings()
        draft.update("EXECUTOR", UpdateSubagentConfigInput(modelId = "custom"))
        draft.update(UpdateSubagentGlobalSettingsInput(7))
        val captured = draft.snapshot()
        draft.update("EXECUTOR", null)
        draft.update(null)
        assertEquals("default", draft.applyDefaults(defaults).single().modelId)
        assertEquals(SubagentGlobalSettings(3), draft.applyDefaults(SubagentGlobalSettings(3)))
        val calls = mutableListOf<String>()
        val core = object : AiCore by MockAiCore() {
            override suspend fun updateSessionSubagentConfig(conversationId: String, role: String, input: UpdateSubagentConfigInput?): Result<Unit> {
                calls += "$conversationId:$role:${input?.modelId}"
                return Result.success(Unit)
            }
            override suspend fun updateSessionSubagentGlobalSettings(conversationId: String, input: UpdateSubagentGlobalSettingsInput?): Result<Unit> {
                calls += "$conversationId:${input?.maxConcurrentAgents}"
                return Result.success(Unit)
            }
        }
        draft.persist(core, "empty").getOrThrow()
        assertTrue(calls.isEmpty())
        captured.persist(core, "created").getOrThrow()
        assertEquals(listOf("created:EXECUTOR:custom", "created:7"), calls)
    }
}
