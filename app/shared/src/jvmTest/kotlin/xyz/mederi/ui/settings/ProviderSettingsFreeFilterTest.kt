package xyz.mederi.ui.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderSettingsFreeFilterTest {

    @Test
    fun testIsModelFreeLogic() {
        // 1. 名字中包含 free（不区分大小写）
        assertTrue(isModelFree(name = "Gemini Flash Free", providerModelId = "gemini-flash", inputPricePerMillion = null, outputPricePerMillion = null))
        assertTrue(isModelFree(name = "deepseek", providerModelId = "meta/llama-3-8b:free", inputPricePerMillion = null, outputPricePerMillion = null))
        assertTrue(isModelFree(name = "FREE MODEL", providerModelId = "custom", inputPricePerMillion = 1.0, outputPricePerMillion = 2.0))

        // 2. 输入输出价格均为 0.0
        assertTrue(isModelFree(name = "Local Qwen", providerModelId = "qwen-2.5", inputPricePerMillion = 0.0, outputPricePerMillion = 0.0))

        // 3. 收费模型（名字不含 free，价格大于 0 或未知）
        assertFalse(isModelFree(name = "Claude 3.5 Sonnet", providerModelId = "claude-3-5-sonnet", inputPricePerMillion = 3.0, outputPricePerMillion = 15.0))
        assertFalse(isModelFree(name = "GPT-4o", providerModelId = "gpt-4o", inputPricePerMillion = null, outputPricePerMillion = null))
        assertFalse(isModelFree(name = "Test Model", providerModelId = "test", inputPricePerMillion = 0.0, outputPricePerMillion = 1.0))
        assertFalse(isModelFree(name = "Test Model 2", providerModelId = "test2", inputPricePerMillion = 1.0, outputPricePerMillion = 0.0))
    }

    @Test
    fun testFilteredModelsWithFreeFilter() {
        val freeByName = ModelItemUiState(
            id = "m1",
            providerModelId = "openrouter/free-gemini",
            name = "Gemini Free",
            isFree = isModelFree("Gemini Free", "openrouter/free-gemini", null, null)
        )
        val freeByPrice = ModelItemUiState(
            id = "m2",
            providerModelId = "qwen-local",
            name = "Qwen 2.5",
            isFree = isModelFree("Qwen 2.5", "qwen-local", 0.0, 0.0)
        )
        val paidModel = ModelItemUiState(
            id = "m3",
            providerModelId = "gpt-4o",
            name = "GPT-4o",
            isFree = isModelFree("GPT-4o", "gpt-4o", 2.5, 10.0)
        )

        val provider = ProviderItemUiState(
            id = "p1",
            name = "Test Provider",
            isBuiltin = false,
            isConnected = true,
            models = listOf(freeByName, freeByPrice, paidModel)
        )

        val stateAll = ProviderSettingsUiState(
            providers = listOf(provider),
            selectedProviderId = "p1",
            capabilityFilter = CapabilityFilter.ALL
        )
        assertEquals(3, stateAll.filteredModels.size)

        val stateFree = ProviderSettingsUiState(
            providers = listOf(provider),
            selectedProviderId = "p1",
            capabilityFilter = CapabilityFilter.FREE
        )
        assertEquals(2, stateFree.filteredModels.size)
        assertEquals(listOf("m1", "m2"), stateFree.filteredModels.map { it.id })
    }
}
