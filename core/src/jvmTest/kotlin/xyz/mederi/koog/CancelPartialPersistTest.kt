package xyz.mederi.koog

import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import xyz.mederi.domain.model.MessagePart
import xyz.mederi.infrastructure.koog.TurnIncrementalPersister
import xyz.mederi.infrastructure.koog.collectAssistantPreservingPartial
import xyz.mederi.store.InMemoryHistoryStore
import xyz.mederi.store.db.TestObservations
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * ST5：流式中途取消时半截正文必须落库且 CancellationException 仍上抛
 * （collectAssistantPreservingPartial 的 persist-then-rethrow——"手动关闭一定保存"）。
 */
class CancelPartialPersistTest {

    @Test
    fun partialPersistedAndCancelReraised() = runBlocking {
        val historyStore = InMemoryHistoryStore()
        val persister = TurnIncrementalPersister(
            sessionId = "sess_cancel",
            historyStore = historyStore,
        )

        // 3 个 TextDelta 后取消（用户中止/关闭应用的流式取消路径）
        val stream = flow<StreamFrame> {
            emit(StreamFrame.TextDelta("Hel"))
            emit(StreamFrame.TextDelta("lo "))
            emit(StreamFrame.TextDelta("world"))
            throw CancellationException("user abort")
        }

        assertFailsWith<CancellationException> {
            stream.collectAssistantPreservingPartial(persister, null)
        }

        val messages = historyStore.load("sess_cancel")
        assertEquals(1, messages.size, "取消路径必须落库半截 assistant 消息")
        val text = messages.single().parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }
        assertEquals("Hello world", text, "半截正文 = 已收 delta 拼接")
        TestObservations.record("CLOSE_PARTIAL_PERSIST", "partial_persisted_and_cancel_reraised")
    }
}
