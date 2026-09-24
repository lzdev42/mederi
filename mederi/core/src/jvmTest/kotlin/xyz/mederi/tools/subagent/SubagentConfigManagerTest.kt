package xyz.mederi.tools.subagent

import kotlinx.coroutines.runBlocking
import xyz.mederi.domain.model.AIModel
import xyz.mederi.domain.model.SubagentModelConfig
import xyz.mederi.domain.model.SubagentRole
import xyz.mederi.provider.domain.model.ReasoningLevel
import xyz.mederi.store.InMemorySettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubagentConfigManagerTest {

    private fun model(id: String) = AIModel(id = id, providerModelId = id, name = "Model $id")

    @Test
    fun `default unconfigured returns inheriting config and resolves to fallback`() = runBlocking {
        val settingsStore = InMemorySettingsStore()
        val providerManager = FakeProviderManager(mapOf("mdl_1" to model("mdl_1")))
        val manager = SubagentConfigManager(settingsStore, providerManager)

        val config = manager.get(SubagentRole.EXECUTOR)
        assertNull(config.modelId)
        assertNull(config.reasoningLevel)
        assertTrue(config.isInheriting)

        val fallbackModel = model("parent_model")
        val (resolvedModel, resolvedReasoning) = manager.resolve(
            role = SubagentRole.EXECUTOR,
            fallbackModel = fallbackModel,
            fallbackReasoning = ReasoningLevel.MEDIUM
        )
        assertEquals("parent_model", resolvedModel.id)
        assertEquals(ReasoningLevel.MEDIUM, resolvedReasoning)
    }

    @Test
    fun `configured valid model resolves to configured model and reasoning level`() = runBlocking {
        val settingsStore = InMemorySettingsStore()
        val targetModel = model("mdl_custom")
        val providerManager = FakeProviderManager(mapOf("mdl_custom" to targetModel))
        val manager = SubagentConfigManager(settingsStore, providerManager)

        manager.set(
            SubagentRole.EXECUTOR,
            SubagentModelConfig(modelId = "mdl_custom", reasoningLevel = ReasoningLevel.HIGH)
        )

        val fallbackModel = model("parent_model")
        val (resolvedModel, resolvedReasoning) = manager.resolve(
            role = SubagentRole.EXECUTOR,
            fallbackModel = fallbackModel,
            fallbackReasoning = ReasoningLevel.NONE
        )
        assertEquals("mdl_custom", resolvedModel.id)
        assertEquals(ReasoningLevel.HIGH, resolvedReasoning)
    }

    @Test
    fun `configured deleted or non-existent model falls back to parent model`() = runBlocking {
        val settingsStore = InMemorySettingsStore()
        // providerManager 没有 mdl_deleted
        val providerManager = FakeProviderManager(emptyMap())
        val manager = SubagentConfigManager(settingsStore, providerManager)

        manager.set(
            SubagentRole.RESEARCHER,
            SubagentModelConfig(modelId = "mdl_deleted", reasoningLevel = ReasoningLevel.LOW)
        )

        val fallbackModel = model("parent_model")
        val (resolvedModel, resolvedReasoning) = manager.resolve(
            role = SubagentRole.RESEARCHER,
            fallbackModel = fallbackModel,
            fallbackReasoning = ReasoningLevel.HIGH
        )
        // 模型回退到父模型，但指定的 reasoningLevel 仍然生效（若配置了）
        assertEquals("parent_model", resolvedModel.id)
        assertEquals(ReasoningLevel.LOW, resolvedReasoning)
    }

    @Test
    fun `configuring only reasoningLevel uses parent model with configured reasoning level`() = runBlocking {
        val settingsStore = InMemorySettingsStore()
        val providerManager = FakeProviderManager(emptyMap())
        val manager = SubagentConfigManager(settingsStore, providerManager)

        manager.set(
            SubagentRole.EXECUTOR,
            SubagentModelConfig(modelId = null, reasoningLevel = ReasoningLevel.MAX)
        )

        val fallbackModel = model("parent_model")
        val (resolvedModel, resolvedReasoning) = manager.resolve(
            role = SubagentRole.EXECUTOR,
            fallbackModel = fallbackModel,
            fallbackReasoning = ReasoningLevel.NONE
        )
        assertEquals("parent_model", resolvedModel.id)
        assertEquals(ReasoningLevel.MAX, resolvedReasoning)
    }

    @Test
    fun `list returns all roles`() = runBlocking {
        val settingsStore = InMemorySettingsStore()
        val providerManager = FakeProviderManager(emptyMap())
        val manager = SubagentConfigManager(settingsStore, providerManager)

        manager.set(SubagentRole.EXECUTOR, SubagentModelConfig(modelId = "m1"))

        val list = manager.list()
        assertEquals(4, list.size)
        assertEquals("m1", list[SubagentRole.EXECUTOR]?.modelId)
        assertNull(list[SubagentRole.RESEARCHER]?.modelId)
    }

    @Test
    fun `browser roles resolve configured model and inherit when unconfigured`() = runBlocking {
        val settingsStore = InMemorySettingsStore()
        val targetModel = model("mdl_browser")
        val providerManager = FakeProviderManager(mapOf("mdl_browser" to targetModel))
        val manager = SubagentConfigManager(settingsStore, providerManager)

        manager.set(
            SubagentRole.BROWSER_OPERATOR,
            SubagentModelConfig(modelId = "mdl_browser", reasoningLevel = ReasoningLevel.MAX)
        )
        val fallbackModel = model("parent_model")
        val (opModel, opReasoning) = manager.resolve(
            role = SubagentRole.BROWSER_OPERATOR,
            fallbackModel = fallbackModel,
            fallbackReasoning = ReasoningLevel.NONE
        )
        assertEquals("mdl_browser", opModel.id)
        assertEquals(ReasoningLevel.MAX, opReasoning)

        val (brainModel, brainReasoning) = manager.resolve(
            role = SubagentRole.BROWSER_BRAIN,
            fallbackModel = fallbackModel,
            fallbackReasoning = ReasoningLevel.NONE
        )
        assertEquals("parent_model", brainModel.id)
        assertEquals(ReasoningLevel.NONE, brainReasoning)
    }

    @Test
    fun `clear restores inheriting state and deletes from settingsStore`() = runBlocking {
        val settingsStore = InMemorySettingsStore()
        val providerManager = FakeProviderManager(emptyMap())
        val manager = SubagentConfigManager(settingsStore, providerManager)

        manager.set(SubagentRole.EXECUTOR, SubagentModelConfig(modelId = "m1"))
        assertEquals("m1", manager.get(SubagentRole.EXECUTOR).modelId)

        manager.clear(SubagentRole.EXECUTOR)
        assertTrue(manager.get(SubagentRole.EXECUTOR).isInheriting)
        assertNull(settingsStore.get("subagent.config.EXECUTOR"))
    }
}
