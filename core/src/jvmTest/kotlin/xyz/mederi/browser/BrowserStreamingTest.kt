package xyz.mederi.browser

import ai.koog.agents.core.tools.ToolBase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import xyz.mederi.domain.model.AIModel
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ST3 浏览器任务 LLM 流式增量：
 * - BrowserOperator.run 的 decide 阶段消费 callWithToolsStreaming 的 ReasoningDelta/TextDelta，
 *   经 onStream 实时推送（step + kind + content 增量）；
 * - Completed 帧承载最终结构化回复（thought / 工具执行语义不变）。
 */
class BrowserStreamingTest {

    /** 流式 fake：只实现 callWithToolsStreaming，call/callWithTools 走接口默认（不会被调）。 */
    private class FakeStreamingCaller : BrowserLLMCaller {
        override suspend fun call(systemPrompt: String, userPrompt: String, image: ByteArray?): String = ""

        override suspend fun callWithToolsStreaming(
            systemPrompt: String,
            userPrompt: String,
            tools: List<ToolBase<*, *>>,
            image: ByteArray?
        ): Flow<BrowserLLMStreamEvent> = flow {
            emit(BrowserLLMStreamEvent.ReasoningDelta("think"))
            emit(BrowserLLMStreamEvent.TextDelta("hello"))
            emit(
                BrowserLLMStreamEvent.Completed(
                    BrowserLLMResponse(
                        text = "hi",
                        toolCalls = listOf(
                            BrowserToolCall(
                                id = "end",
                                name = BrowserConstants.ToolNames.DONE,
                                argsJson = """{"message":"done"}"""
                            )
                        )
                    )
                )
            )
        }
    }

    @Test
    fun `streaming reasoning and text deltas reach onStream and completed response drives result`() = runBlocking {
        val control = FakeBrowserControl()
        val records = mutableListOf<Triple<Int, String, String>>()
        val fake = FakeStreamingCaller()
        val operator = BrowserOperator(
            control = control,
            aiModel = AIModel(id = "m", providerModelId = "m", name = "m"),
            llmCaller = fake,
            onStream = { step, kind, delta -> records.add(Triple(step, kind, delta)) }
        )

        val r = operator.run("test")

        assertTrue(r.success, "任务应成功完成: ${r.message}")
        assertTrue(
            records.any { it.second == "reasoning" && it.third.contains("think") },
            "onStream 应收到 reasoning 增量: $records"
        )
        assertTrue(
            records.any { it.second == "text" && it.third.contains("hello") },
            "onStream 应收到 text 增量: $records"
        )
        assertTrue(r.message.contains("done"), "最终 message 应含 done: ${r.message}")
    }
}
