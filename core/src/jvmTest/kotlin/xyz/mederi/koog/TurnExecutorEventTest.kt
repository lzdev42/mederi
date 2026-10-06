package xyz.mederi.koog

import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.utils.time.KoogClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.mederi.infrastructure.koog.extractUsagePayload

/**
 * [extractUsagePayload] 从 LLM 响应 [ResponseMetaInfo] 提取单次请求用量 payload
 * （供 LLM_REQUEST_COMPLETED 事件发射；门禁 = inputTokensCount 为 null 即不发射）。
 */
class TurnExecutorEventTest {

    @Test
    fun extractUsagePayload_returnsTokens() {
        val meta = ResponseMetaInfo.create(
            KoogClock.System,
            totalTokensCount = 150,
            inputTokensCount = 100,
            outputTokensCount = 50,
            metadata = buildJsonObject { put("cachedTokens", 80) },
        )
        val payload = extractUsagePayload(meta)
        assertEquals(
            mapOf("inputTokens" to "100", "outputTokens" to "50", "cachedTokens" to "80"),
            payload,
        )
    }

    @Test
    fun extractUsagePayload_noInput_returnsNull() {
        val meta = ResponseMetaInfo.create(
            KoogClock.System,
            inputTokensCount = null,
            outputTokensCount = 50,
        )
        assertNull(extractUsagePayload(meta), "inputTokensCount 为 null = 无用量数据 → 不发射")
    }

    @Test
    fun extractUsagePayload_inputOnly_omitsAbsent() {
        val meta = ResponseMetaInfo.create(
            KoogClock.System,
            inputTokensCount = 100,
        )
        val payload = extractUsagePayload(meta)
        assertEquals(mapOf("inputTokens" to "100"), payload, "无 output/metadata → 省略对应 key")
        assertTrue(payload != null)
    }
}
