package xyz.mederi.tools

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.mederi.domain.model.AgentMode
import xyz.mederi.domain.model.SubagentRole
import xyz.mederi.prompt.SystemPrompts
import xyz.mederi.store.InMemoryHistoryStore

/** 工具名过滤、角色裁剪和模型可见教程必须同时接通，避免实现存在但模型无法使用。 */
class ApplyPatchRegistrationTest {
    private fun registry(role: SubagentRole? = null, mode: AgentMode = AgentMode.AUTONOMOUS) =
        ToolFactory.build(
            directories = listOf(java.io.File(".").absolutePath),
            sessionId = "apply-patch-registration",
            historyStore = InMemoryHistoryStore(),
            eventBus = MutableSharedFlow(),
            modelContextWindow = null,
            newContextWindowFlag = AtomicBoolean(),
            subagentRole = role,
            agentMode = mode,
        )

    @Test
    fun registeredForBothModesAndExecutor() {
        assertTrue(ToolName.APPLY_PATCH in ToolFactory.FS_TOOL_NAMES)
        for (mode in AgentMode.entries) {
            assertNotNull(registry(mode = mode).getToolOrNull("apply_patch"))
            assertNotNull(registry(SubagentRole.EXECUTOR, mode).getToolOrNull("apply_patch"))
        }
    }

    @Test
    fun researcherRemainsReadOnly() {
        val tools = registry(SubagentRole.RESEARCHER)
        assertNull(tools.getToolOrNull("apply_patch"))
        assertNull(tools.getToolOrNull("write_file"))
        assertNull(tools.getToolOrNull("edit_file"))
        assertNotNull(tools.getToolOrNull("read_file"))
    }

    @Test
    fun mainAndExecutorLearnActualCallEnvelopeAndFailureRecovery() {
        val prompts = AgentMode.entries.map { SystemPrompts.build(it) } +
            SystemPrompts.forSubagent(SubagentRole.EXECUTOR)
        prompts.forEach {
            assertTrue(it.contains("apply_patch:"))
            assertTrue(it.contains("FREEFORM takes raw patch"))
            assertTrue(it.contains("JSON object with a patch string"))
            assertTrue(it.contains("remaining changes before retrying"))
        }
        assertFalse(SystemPrompts.forSubagent(SubagentRole.RESEARCHER).contains("apply_patch:"))
    }
}
