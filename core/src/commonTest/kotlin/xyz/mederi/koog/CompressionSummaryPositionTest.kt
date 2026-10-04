package xyz.mederi.koog

import ai.koog.prompt.message.Message as KoogMessage
import ai.koog.prompt.message.MessagePart as KoogMessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant as KoogInstant
import xyz.mederi.domain.model.Message as MederiMessage
import xyz.mederi.domain.model.MessagePart as MederiMessagePart
import xyz.mederi.domain.model.MessageRole
import xyz.mederi.domain.model.MessageStatus
import xyz.mederi.infrastructure.koog.HistoryStoreChatHistoryProvider
import xyz.mederi.store.InMemoryHistoryStore

/**
 * 压缩回写 SUMMARY 标记的插入位置与 AI 视图窗口回归测试。
 *
 * 场景（真实链路）：Koog ChatMemory 在 strategy 完成时把压缩后的历史
 * （[TLDR, recent...]）整体交给 store，recent 携带与库中一致的 id。
 * 回归点：SUMMARY 必须插在保留段（recent）起点之前，AI 视图 =
 * SUMMARY + recent 原文 + 新消息——否则压缩后模型看不到保留的最近几句
 * （历史上 existingIds 过滤让对齐恒失败，SUMMARY 追加到历史末尾）。
 */
class CompressionSummaryPositionTest {

    private fun m(id: String, role: MessageRole, text: String, createdAt: String): MederiMessage =
        MederiMessage(
            id = id, sessionId = "s1", role = role,
            parts = listOf(MederiMessagePart.Text(text)),
            status = MessageStatus.COMPLETED, createdAt = createdAt
        )

    @Test
    fun summaryInsertsBeforeRecentAndAiViewKeepsRecent() = runBlocking {
        val store = InMemoryHistoryStore()
        // 旧历史 4 条
        store.append("s1", m("u1", MessageRole.USER, "q1", "2026-10-01T10:00:00Z"))
        store.append("s1", m("a1", MessageRole.ASSISTANT, "r1", "2026-10-01T10:01:00Z"))
        store.append("s1", m("u2", MessageRole.USER, "q2", "2026-10-01T10:02:00Z"))
        store.append("s1", m("a2", MessageRole.ASSISTANT, "r2", "2026-10-01T10:03:00Z"))

        // 压缩后保留段 = 最后 1 条 a2；Koog load 时 recent 携带库中原始 id "a2"
        val provider = HistoryStoreChatHistoryProvider(store)
        provider.store(
            "s1",
            listOf(
                KoogMessage.Assistant(
                    parts = listOf(KoogMessagePart.Text("TLDR: 前面聊了什么")),
                    metaInfo = ResponseMetaInfo(KoogInstant.parse("2026-10-01T11:00:00Z")),
                    id = "tldr1"
                ),
                KoogMessage.Assistant(
                    parts = listOf(KoogMessagePart.Text("r2")),
                    metaInfo = ResponseMetaInfo(KoogInstant.parse("2026-10-01T10:03:00Z")),
                    finishReason = "stop",
                    id = "a2"
                )
            )
        )

        val stored = store.load("s1")
        val window = HistoryStoreChatHistoryProvider.aiViewWindow(store, "s1")

        // 全量历史 5 条：u1 a1 u2 + SUMMARY + a2（无 id 重复、一条不删）
        assertEquals(5, stored.size)
        assertEquals(1, stored.count { it.role == MessageRole.SUMMARY })
        assertEquals(stored.size, stored.map { it.id }.distinct().size)

        // SUMMARY 必须插在保留段 a2 之前
        val summaryIdx = stored.indexOfFirst { it.role == MessageRole.SUMMARY }
        val recentIdx = stored.indexOfFirst { it.id == "a2" }
        assertTrue(
            summaryIdx < recentIdx,
            "SUMMARY@$summaryIdx 应在保留段 a2@$recentIdx 之前"
        )

        // AI 视图 = SUMMARY + recent 原文
        assertEquals(2, window.size, "AI 视图应为 SUMMARY + 保留的 a2")
        assertEquals(MessageRole.SUMMARY, window.first().role)
        assertTrue(window.any { it.id == "a2" }, "AI 视图必须包含保留的 recent 原文")
    }

    /** 多条 recent + 新消息混合：对齐多条 recent，新消息追加在 SUMMARY 之后。 */
    @Test
    fun summaryWithMultiRecentAndNewMessage() = runBlocking {
        val store = InMemoryHistoryStore()
        store.append("s1", m("m1", MessageRole.USER, "q1", "2026-10-01T10:00:00Z"))
        store.append("s1", m("m2", MessageRole.ASSISTANT, "a1", "2026-10-01T10:01:00Z"))
        store.append("s1", m("m3", MessageRole.USER, "q2", "2026-10-01T10:02:00Z"))

        // incoming = [TLDR, m2(同 id), m3(同 id), n1(新)]
        val provider = HistoryStoreChatHistoryProvider(store)
        provider.store(
            "s1",
            listOf(
                KoogMessage.Assistant(
                    parts = listOf(KoogMessagePart.Text("TLDR: 前面聊了什么")),
                    metaInfo = ResponseMetaInfo(KoogInstant.parse("2026-10-01T12:00:00Z")),
                    id = "tldr1"
                ),
                KoogMessage.Assistant(
                    parts = listOf(KoogMessagePart.Text("a1")),
                    metaInfo = ResponseMetaInfo(KoogInstant.parse("2026-10-01T10:01:00Z")),
                    finishReason = "stop",
                    id = "m2"
                ),
                KoogMessage.User(
                    parts = listOf(KoogMessagePart.Text("q2")),
                    metaInfo = RequestMetaInfo(KoogInstant.parse("2026-10-01T10:02:00Z")),
                    id = "m3"
                ),
                KoogMessage.Assistant(
                    parts = listOf(KoogMessagePart.Text("压缩后的新回复")),
                    metaInfo = ResponseMetaInfo(KoogInstant.parse("2026-10-01T12:02:00Z")),
                    id = "n1"
                )
            )
        )

        val stored = store.load("s1")
        val window = HistoryStoreChatHistoryProvider.aiViewWindow(store, "s1")

        // m1, SUMMARY, m2, m3, n1 —— SUMMARY 在保留段 m2/m3 之前，新消息 n1 在其后
        assertEquals(5, stored.size, "应 = 原 3 条 + SUMMARY + 新消息，无重复")
        assertEquals(1, stored.count { it.role == MessageRole.SUMMARY })
        val summaryIdx = stored.indexOfFirst { it.role == MessageRole.SUMMARY }
        assertEquals("m1", stored[summaryIdx - 1].id, "SUMMARY 前一条是被压掉的历史")
        assertEquals("m2", stored[summaryIdx + 1].id, "SUMMARY 后第一条是保留段最旧的 recent")
        assertEquals("n1", stored.last().id, "新消息追加在最后")
        assertTrue(window.any { it.id == "m3" }, "AI 视图应含保留段 m3")
        assertTrue(window.any { it.id == "n1" }, "AI 视图应含新消息 n1")
    }
}