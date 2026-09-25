package xyz.mederi.koog

import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
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
 * HistoryStoreChatHistoryProvider 的图片能力专项测试：
 * - load(includeImages=false) 剔除用户消息图片（AI 视图按模型能力过滤）；
 * - store 按 id 过滤 AI 视图回显，防止无图回显覆盖库中带图历史；
 * - 常规追加与 TLDR/压缩回写行为不回归。
 */
class HistoryStoreChatHistoryProviderTest {

    private val IMAGE_URL = "data:image/png;base64,AAAA"

    private fun mederiMessage(
        id: String,
        role: MessageRole,
        parts: List<MederiMessagePart>,
        createdAt: String = "2026-09-08T12:00:00Z"
    ): MederiMessage = MederiMessage(
        id = id,
        sessionId = "s1",
        role = role,
        parts = parts,
        status = MessageStatus.COMPLETED,
        createdAt = createdAt
    )

    private fun koogImage(url: String = IMAGE_URL): KoogMessagePart.Attachment =
        KoogMessagePart.Attachment(
            source = AttachmentSource.Image(
                content = AttachmentContent.URL(url),
                format = "png",
                mimeType = "image/png"
            )
        )

    // ── ① load includeImages=false 剔除图片 ──────────────────────────────
    @Test
    fun testLoadStripsImagesWhenIncludeImagesFalse() = runBlocking {
        val store = InMemoryHistoryStore()
        store.append(
            "s1",
            mederiMessage(
                id = "u1",
                role = MessageRole.USER,
                parts = listOf(MederiMessagePart.Text("看图"), MederiMessagePart.Image(url = IMAGE_URL))
            )
        )

        // includeImages=false：AI 视图剔除图片（文本保留）
        val providerNoImages = HistoryStoreChatHistoryProvider(store, includeImages = false)
        val loaded = providerNoImages.load("s1")
        assertEquals(1, loaded.size)
        val user = loaded.single() as KoogMessage.User
        assertEquals(1, user.parts.filterIsInstance<KoogMessagePart.Text>().size, "文本 part 应保留")
        assertEquals(
            0,
            user.parts.filterIsInstance<KoogMessagePart.Attachment>().size,
            "includeImages=false 时 load 应剔除图片"
        )

        // 默认构造（includeImages=true）：行为不变，保留 Attachment
        val providerDefault = HistoryStoreChatHistoryProvider(store)
        val loadedDefault = providerDefault.load("s1")
        val userDefault = loadedDefault.single() as KoogMessage.User
        assertTrue(
            userDefault.parts.filterIsInstance<KoogMessagePart.Attachment>().isNotEmpty(),
            "默认构造时 load 应保留 Attachment"
        )
    }

    // ── ② store 同 id 用户消息不覆盖（原带图保留） ──────────────────────
    @Test
    fun testStoreDoesNotOverwriteExistingSameId() = runBlocking {
        val store = InMemoryHistoryStore()
        store.append(
            "s1",
            mederiMessage(
                id = "u1",
                role = MessageRole.USER,
                parts = listOf(MederiMessagePart.Text("看图"), MederiMessagePart.Image(url = IMAGE_URL))
            )
        )

        // store 收到"AI 视图全量"：同 id 用户消息的无图版本 + 一条新 assistant
        val provider = HistoryStoreChatHistoryProvider(store, includeImages = false)
        provider.store(
            "s1",
            listOf(
                KoogMessage.User(
                    parts = listOf(KoogMessagePart.Text("看图")),
                    metaInfo = RequestMetaInfo(KoogInstant.parse("2026-09-08T12:00:00Z")),
                    id = "u1"
                ),
                KoogMessage.Assistant(
                    parts = listOf(KoogMessagePart.Text("hi")),
                    metaInfo = ResponseMetaInfo(KoogInstant.parse("2026-09-08T12:01:00Z")),
                    finishReason = "stop",
                    id = "a1"
                )
            )
        )

        val stored = store.load("s1")
        assertEquals(2, stored.size, "历史应 = 原 1 条用户消息（未被覆盖）+ 1 条新 assistant")
        val userMessages = stored.filter { it.role == MessageRole.USER }
        assertEquals(1, userMessages.size)
        assertTrue(
            userMessages.single().parts.any { it is MederiMessagePart.Image },
            "同 id 的无图回显不得覆盖已落库的带图消息（原版 Image 应保留）"
        )
        assertTrue(stored.any { it.role == MessageRole.ASSISTANT }, "新 assistant 应已追加")
    }

    // ── ③ 常规追加不回归 ────────────────────────────────────────────────
    @Test
    fun testStoreAppendStillWorks() = runBlocking {
        val store = InMemoryHistoryStore()
        val provider = HistoryStoreChatHistoryProvider(store)

        // 第一次 store：空库 → 用户消息（带图）落库
        provider.store(
            "s1",
            listOf(
                KoogMessage.User(
                    parts = listOf(KoogMessagePart.Text("看图"), koogImage()),
                    metaInfo = RequestMetaInfo(KoogInstant.parse("2026-09-08T12:00:00Z")),
                    id = "u1"
                )
            )
        )
        var stored = store.load("s1")
        assertEquals(1, stored.size)
        assertTrue(stored.single().parts.any { it is MederiMessagePart.Image }, "首次 store 带图用户消息应完整落库")

        // 第二次 store：同 id 用户消息的无图回显 + 新 assistant → 只追加新消息
        provider.store(
            "s1",
            listOf(
                KoogMessage.User(
                    parts = listOf(KoogMessagePart.Text("看图")),
                    metaInfo = RequestMetaInfo(KoogInstant.parse("2026-09-08T12:00:00Z")),
                    id = "u1"
                ),
                KoogMessage.Assistant(
                    parts = listOf(KoogMessagePart.Text("hi")),
                    metaInfo = ResponseMetaInfo(KoogInstant.parse("2026-09-08T12:01:00Z")),
                    finishReason = "stop",
                    id = "a1"
                )
            )
        )
        stored = store.load("s1")
        assertEquals(2, stored.size, "应 = 用户（带图原版）+ assistant")
        val userMessages = stored.filter { it.role == MessageRole.USER }
        assertEquals(1, userMessages.size)
        assertTrue(userMessages.single().parts.any { it is MederiMessagePart.Image }, "用户消息保持带图原版")
        assertTrue(stored.any { it.role == MessageRole.ASSISTANT })
    }

    // ── ④ TLDR/压缩回写不回归 ───────────────────────────────────────────
    @Test
    fun testStoreTldrInsertionUnaffected() = runBlocking {
        val store = InMemoryHistoryStore()
        // existing 3 条普通消息（无图）
        store.append("s1", mederiMessage("m1", MessageRole.USER, listOf(MederiMessagePart.Text("q1")), "2026-09-08T11:00:00Z"))
        store.append("s1", mederiMessage("m2", MessageRole.ASSISTANT, listOf(MederiMessagePart.Text("a1")), "2026-09-08T11:01:00Z"))
        store.append("s1", mederiMessage("m3", MessageRole.USER, listOf(MederiMessagePart.Text("q2")), "2026-09-08T11:02:00Z"))

        // store [新 TLDR（id 全新）+ existing 尾部 2 条的无图回显（同 id）+ 1 条新消息]
        val provider = HistoryStoreChatHistoryProvider(store)
        provider.store(
            "s1",
            listOf(
                KoogMessage.Assistant(
                    parts = listOf(KoogMessagePart.Text("TLDR: 前面聊了什么")),
                    metaInfo = ResponseMetaInfo(KoogInstant.parse("2026-09-08T12:00:00Z")),
                    id = "tldr1"
                ),
                KoogMessage.Assistant(
                    parts = listOf(KoogMessagePart.Text("a1")),
                    metaInfo = ResponseMetaInfo(KoogInstant.parse("2026-09-08T11:01:00Z")),
                    id = "m2"
                ),
                KoogMessage.User(
                    parts = listOf(KoogMessagePart.Text("q2")),
                    metaInfo = RequestMetaInfo(KoogInstant.parse("2026-09-08T11:02:00Z")),
                    id = "m3"
                ),
                KoogMessage.Assistant(
                    parts = listOf(KoogMessagePart.Text("压缩后的新回复")),
                    metaInfo = ResponseMetaInfo(KoogInstant.parse("2026-09-08T12:02:00Z")),
                    id = "n1"
                )
            )
        )

        val stored = store.load("s1")
        assertEquals(5, stored.size, "应 = 原 3 条 + 1 SUMMARY 标记 + 1 条新消息")
        assertEquals(1, stored.count { it.role == MessageRole.SUMMARY }, "应恰好 1 条 SUMMARY 标记")
        assertEquals(5, stored.map { it.id }.distinct().size, "不允许出现 id 重复")
        assertTrue(
            stored.any {
                it.role == MessageRole.ASSISTANT &&
                    it.parts.filterIsInstance<MederiMessagePart.Text>().any { p -> p.text == "压缩后的新回复" }
            },
            "新消息应落库"
        )
    }

    // ── ⑤（strengthen）兜底 replace 必须保留 existing ────────────────────
    @Test
    fun testReplaceFallbackKeepsExisting() = runBlocking {
        val store = InMemoryHistoryStore()
        // existing 两条带图用户消息
        store.append("s1", mederiMessage("u1", MessageRole.USER, listOf(MederiMessagePart.Text("看图1"), MederiMessagePart.Image(url = IMAGE_URL)), "2026-09-08T11:00:00Z"))
        store.append("s1", mederiMessage("u2", MessageRole.USER, listOf(MederiMessagePart.Text("看图2"), MederiMessagePart.Image(url = IMAGE_URL)), "2026-09-08T11:01:00Z"))

        // 必然触发对齐失败场景：全无图回显（同 id）+ 新 assistant
        val provider = HistoryStoreChatHistoryProvider(store, includeImages = false)
        provider.store(
            "s1",
            listOf(
                KoogMessage.User(
                    parts = listOf(KoogMessagePart.Text("看图1")),
                    metaInfo = RequestMetaInfo(KoogInstant.parse("2026-09-08T11:00:00Z")),
                    id = "u1"
                ),
                KoogMessage.User(
                    parts = listOf(KoogMessagePart.Text("看图2")),
                    metaInfo = RequestMetaInfo(KoogInstant.parse("2026-09-08T11:01:00Z")),
                    id = "u2"
                ),
                KoogMessage.Assistant(
                    parts = listOf(KoogMessagePart.Text("hi")),
                    metaInfo = ResponseMetaInfo(KoogInstant.parse("2026-09-08T12:00:00Z")),
                    finishReason = "stop",
                    id = "a1"
                )
            )
        )

        val stored = store.load("s1")
        assertEquals(3, stored.size, "兜底 replace 必须保留 existing + freshIncoming，不得删旧消息")
        assertEquals(3, stored.map { it.id }.distinct().size, "不允许 id 重复")
        val storedImages = stored.filter { it.role == MessageRole.USER }
            .flatMap { it.parts }
            .filterIsInstance<MederiMessagePart.Image>()
        assertEquals(2, storedImages.size, "u1/u2 带图原版必须保留")
        assertTrue(stored.any { it.role == MessageRole.ASSISTANT }, "新 assistant 应落库")
    }
}