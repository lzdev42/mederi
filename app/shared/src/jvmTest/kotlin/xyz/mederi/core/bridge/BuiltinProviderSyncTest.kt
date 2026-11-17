package xyz.mederi.core.bridge

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import xyz.mederi.Mederi
import xyz.mederi.api.CreateApiKeyRequest
import xyz.mederi.api.CreateModelRequest
import xyz.mederi.api.CreateProviderRequest
import xyz.mederi.core.contract.dto.CreateCustomProviderInput
import xyz.mederi.core.contract.models.ProtocolType
import xyz.mederi.provider.domain.model.ProviderType

class BuiltinProviderSyncTest {
    @Test
    fun startupUpdatesStoredProtocolAndUsesPresetReasoning() = runBlocking {
        val scratch = Path.of("../../temp").createDirectories()
        val configDir = createTempDirectory(scratch, "builtin-provider-sync-").toFile()
        val core = MederiAiCore(configDir.absolutePath)
        val setup = Mederi.create { this.configDir = configDir.absolutePath }
        try {
            val preset = BuiltinProviders.allEntries().single { it.name == "Hetzner" }
            val expectedReasoning = preset.def.effectiveReasoningParameter
            // 复现旧启动同步已经写入新推理参数，但持久化协议仍旧的情况。
            val saved = setup.providers.create(
                CreateProviderRequest(
                    name = preset.name,
                    type = ProviderType.GOOGLE.name,
                    baseUrl = preset.baseUrl,
                    apiKeys = listOf(CreateApiKeyRequest("default", "test-key", isDefault = true)),
                    models = listOf(CreateModelRequest("test-model", "Test model")),
                    reasoningParameter = expectedReasoning,
                    responseSanitization = preset.def.responseSanitization,
                    fetchModels = false,
                    modelsDevKey = preset.def.modelsDevKey,
                )
            )
            core.initialize().getOrThrow()

            val updated = setup.providers.get(saved.id)
            assertEquals(preset.def.type, updated.type)
            assertEquals(expectedReasoning, updated.reasoningParameter)
            assertEquals(saved.models, updated.models)
            assertEquals(saved.defaultApiKey?.id, updated.defaultApiKey?.id)
            assertEquals(ProtocolType.valueOf(preset.def.type.name), core.providers.value.single().protocolType)

            val customGoogle = core.createCustomProvider(
                CreateCustomProviderInput(
                    name = "Custom Google",
                    type = ProtocolType.GOOGLE,
                    baseUrl = "https://example.invalid",
                    apiKey = null,
                )
            )
            assertTrue(customGoogle.isFailure)
            assertEquals(listOf(saved.id), setup.providers.list().map { it.id })
        } finally {
            core.shutdown()
            setup.dataDatabaseManager?.drainAndClose(2000)
            configDir.deleteRecursively()
        }
    }
}
