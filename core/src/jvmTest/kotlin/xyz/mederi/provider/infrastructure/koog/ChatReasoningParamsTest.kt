package xyz.mederi.provider.infrastructure.koog

import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import ai.koog.prompt.executor.clients.openai.OpenAIResponsesParams
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import xyz.mederi.provider.domain.model.ProviderType
import xyz.mederi.provider.domain.model.ReasoningLevel
import xyz.mederi.provider.domain.model.ReasoningParameter

class ChatReasoningParamsTest {
    @Test
    fun chatNoneSendsDefaultForCurrentAndLegacyConfigurations() {
        val configurations = listOf(
            ReasoningParameter.forOpenAIChat(),
            ReasoningParameter(mapOf(ReasoningLevel.NONE to null)),
            ReasoningParameter(mapOf(ReasoningLevel.NONE to "")),
            ReasoningParameter(mapOf(ReasoningLevel.LOW to """{"reasoning_effort":"low"}""")),
            null,
        )
        for (configuration in configurations) {
            val params = KoogParamsBuilder.build(ProviderType.OPENAI_CHAT, ReasoningLevel.NONE, configuration) as OpenAIChatParams
            assertEquals(mapOf("reasoning_effort" to JsonPrimitive("none")), params.additionalProperties)
        }
    }

    @Test
    fun chatNonePreservesExplicitProviderFragment() {
        val configuration = ReasoningParameter(mapOf(ReasoningLevel.NONE to """{"enable_thinking":false}"""))
        val params = KoogParamsBuilder.build(ProviderType.OPENAI_CHAT, ReasoningLevel.NONE, configuration) as OpenAIChatParams
        assertEquals(mapOf("enable_thinking" to JsonPrimitive(false)), params.additionalProperties)
    }

    @Test
    fun customOverridesApplyOnlyToConfiguredLevels() {
        val configuration = ReasoningParameter(mapOf(ReasoningLevel.HIGH to """{"custom_thinking":true}"""))
        val high = KoogParamsBuilder.build(ProviderType.OPENAI_CHAT, ReasoningLevel.HIGH, configuration) as OpenAIChatParams
        val low = KoogParamsBuilder.build(ProviderType.OPENAI_CHAT, ReasoningLevel.LOW, configuration) as OpenAIChatParams
        val responses = KoogParamsBuilder.build(ProviderType.OPENAI_RESPONSES, ReasoningLevel.LOW, configuration) as OpenAIResponsesParams

        assertEquals(mapOf("custom_thinking" to JsonPrimitive(true)), high.additionalProperties)
        assertEquals(mapOf("reasoning_effort" to JsonPrimitive("low")), low.additionalProperties)
        assertEquals(mapOf("reasoning" to buildJsonObject { put("effort", "low") }), responses.additionalProperties)
    }

    @Test
    fun explicitPresetDoesNotInheritMissingLevels() {
        val configuration = ReasoningParameter(
            levels = mapOf(ReasoningLevel.HIGH to """{"enable_thinking":true}"""),
            useProtocolDefaults = false,
        )
        val params = KoogParamsBuilder.build(ProviderType.OPENAI_CHAT, ReasoningLevel.LOW, configuration) as OpenAIChatParams
        assertNull(params.additionalProperties)
    }

    @Test
    fun responsesNoneOmitsReasoningByDefault() {
        val configuration = ReasoningParameter.forOpenAIResponses()
        val params = KoogParamsBuilder.build(ProviderType.OPENAI_RESPONSES, ReasoningLevel.NONE, configuration) as OpenAIResponsesParams
        assertNull(params.additionalProperties)
    }
}
