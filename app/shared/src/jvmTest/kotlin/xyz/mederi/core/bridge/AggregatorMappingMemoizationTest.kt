package xyz.mederi.core.bridge

import xyz.mederi.core.contract.models.ChatBlock
import xyz.mederi.core.contract.models.ChatMessage
import xyz.mederi.core.contract.models.ToolCallState
import xyz.mederi.domain.model.Message
import xyz.mederi.domain.model.MessagePart
import xyz.mederi.domain.model.MessageRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 子任务 2：refreshPage 消息映射引用记忆化（[ChatMessageMappingCache]）的单元测试。
 * - T3_MAPPING_ONCE：同一不可变列表、同 revision 二次 mapAll → 第二次 mapper 调用次数 == 0
 * - T3_REMAP_ON_UPDATE：同 id 换新引用（模拟 updateMessage）→ 该条换新 mapper 结果，缓存不返回旧值
 * - T3_MAPPING_RESULTS_REVISION：toolResultsById 加一项（revision+1）→ 全量重映射（计数 == N）；
 *   同 revision 再调一次 → 计数 == 0
 * - T3_SHRINK_CLEARS：mapAll(N) → mapAll(N-1 截断列表）→ 再 mapAll(N-1 同列表）→ 计数 == 0
 *   （收缩清缓存，旧引用不污染截断后的映射）
 */
class AggregatorMappingMemoizationTest {

    private val timestamp = "2026-11-05T00:00:00Z"

    private fun textMessage(id: String, text: String) = Message(
        id = id,
        sessionId = "s",
        role = MessageRole.ASSISTANT,
        parts = listOf(MessagePart.Text(text)),
        createdAt = timestamp,
    )

    private fun toolCallMessage(id: String, toolId: String) = Message(
        id = id,
        sessionId = "s",
        role = MessageRole.ASSISTANT,
        parts = listOf(MessagePart.ToolCall(id = toolId, tool = "shell", args = "{}")),
        createdAt = timestamp,
    )

    private fun toolResultMessage(id: String, toolId: String, output: String) = Message(
        id = id,
        sessionId = "s",
        role = MessageRole.USER,
        parts = listOf(MessagePart.ToolResult(id = toolId, tool = "shell", output = output)),
        createdAt = timestamp,
    )

    private fun countingMapper(calls: java.util.concurrent.atomic.AtomicInteger): (Message, Map<String, MessagePart.ToolResult>) -> ChatMessage =
        { m, results ->
            calls.incrementAndGet()
            MederiModelMapper.toChatMessage(m, results)
        }

    private fun textOf(msg: ChatMessage): String =
        (msg.blocks.single() as ChatBlock.Text).text

    // ------------------------------------------------------------------
    // T3_MAPPING_ONCE：同引用同 revision → 第二次 mapper 调用次数 == 0
    // ------------------------------------------------------------------

    @Test
    fun t3MappingOnceSameReferencesSameRevisionReuse() {
        val messages = listOf(textMessage("a", "hello"), textMessage("b", "world"))
        val results = MederiModelMapper.buildToolResultsById(messages)
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val cache = ChatMessageMappingCache()

        val first = cache.mapAll(messages, results, countingMapper(calls))
        assertEquals(2, calls.get(), "首次映射：每条消息都走 mapper")

        val second = cache.mapAll(messages, results, countingMapper(calls))
        assertEquals(2, calls.get(), "第二次（同列表同 revision）mapper 调用次数 == 0")
        assertEquals(first, second, "缓存命中返回与首次一致的结果")
        assertEquals(listOf("hello", "world"), second.map { textOf(it) })
    }

    // ------------------------------------------------------------------
    // T3_REMAP_ON_UPDATE：同 id 新引用（模拟 updateMessage）→ 重映射该条
    // ------------------------------------------------------------------

    @Test
    fun t3RemapOnUpdateSameIdNewReference() {
        val messages = listOf(textMessage("a", "hello"), textMessage("b", "world"), textMessage("c", "third"))
        val results = MederiModelMapper.buildToolResultsById(messages)
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val cache = ChatMessageMappingCache()

        cache.mapAll(messages, results, countingMapper(calls))
        assertEquals(3, calls.get())

        // 模拟 updateMessage：id 相同、内容不同、引用不同的新实例
        val updated = messages[1].copy(parts = listOf(MessagePart.Text("updated content")))
        assertTrue(updated !== messages[1], "模拟 updateMessage 必须换新引用")
        assertEquals("b", updated.id, "updateMessage 保留 id")

        val remapped = cache.mapAll(listOf(messages[0], updated, messages[2]), results, countingMapper(calls))

        assertEquals(4, calls.get(), "同 id 换新引用 → 只重映射该条（其余命中缓存）")
        assertEquals(listOf("hello", "updated content", "third"), remapped.map { textOf(it) },
            "新引用产出新 mapper 结果（缓存未返回旧值）")
    }

    // ------------------------------------------------------------------
    // T3 版本失效：toolResultsById 加一项（revision+1）→ 全量重映射
    // ------------------------------------------------------------------

    @Test
    fun t3ToolResultRevisionBumpRemapsAll() {
        val messages = listOf(toolCallMessage("m1", "t1"), toolCallMessage("m2", "t2"))
        val resultsV1 = MederiModelMapper.buildToolResultsById(listOf(toolResultMessage("u1", "t1", "out-1")))
        val resultsV2 = MederiModelMapper.buildToolResultsById(
            listOf(toolResultMessage("u1", "t1", "out-1"), toolResultMessage("u2", "t2", "out-2"))
        )
        assertEquals(1, resultsV1.size, "前置：版本 1 的结果表")
        assertEquals(2, resultsV2.size, "前置：版本 2 的结果表（加一项）")
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val cache = ChatMessageMappingCache()

        cache.mapAll(messages, resultsV1, countingMapper(calls))
        assertEquals(2, calls.get(), "首轮映射 N=2")
        cache.mapAll(messages, resultsV1, countingMapper(calls))
        assertEquals(2, calls.get(), "同 revision 同引用 → 全命中")

        val remapped = cache.mapAll(messages, resultsV2, countingMapper(calls))
        assertEquals(4, calls.get(), "toolResultsById 加一项（revision+1）→ 全量重映射（mapper 计数 == N=2）")
        assertEquals(
            ToolCallState.Completed(input = emptyMap(), output = "out-1"),
            (remapped[0].blocks.single() as ChatBlock.ToolCall).state,
            "revision 提升后 assistant ToolCall 拿到最新 ToolResult 回填"
        )

        cache.mapAll(messages, resultsV2, countingMapper(calls))
        assertEquals(4, calls.get(), "同 revision 再调一次 → 计数 == 0")
    }

    // ------------------------------------------------------------------
    // T3 收缩清缓存：回滚/截断后旧引用不得污染截断列表的映射
    // ------------------------------------------------------------------

    @Test
    fun t3ShrinkClearsCacheOldReferencesNotReused() {
        val messages = listOf(textMessage("a", "one"), textMessage("b", "two"), textMessage("c", "three"))
        val results = MederiModelMapper.buildToolResultsById(messages)
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val cache = ChatMessageMappingCache()

        cache.mapAll(messages, results, countingMapper(calls))
        assertEquals(3, calls.get(), "首轮 mapAll(N=3)")

        // 回滚/截断：列表收缩到 2 条（元素仍是旧列表前两条的引用）
        val truncated = messages.take(2)
        val afterShrink = cache.mapAll(truncated, results, countingMapper(calls))
        assertEquals(5, calls.get(), "收缩触发 clear → 截断列表全量重映射")
        assertEquals(listOf("one", "two"), afterShrink.map { textOf(it) },
            "截断后的映射内容是当前消息内容，而非旧缓存")

        cache.mapAll(truncated, results, countingMapper(calls))
        assertEquals(5, calls.get(), "同列表再调一次 → 计数 == 0（旧引用未污染）")
    }
}