package xyz.mederi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import xyz.mederi.core.bridge.BuiltinProviders
import xyz.mederi.provider.domain.model.ProviderType

class SharedLogicDesktopTest {

    /** 等待快照静止（连续两次间隔读取相同 = 异步回合动作全部完成），再交由断言校验内容 */
    private suspend fun xyz.mederi.core.mock.MockAiCore.awaitSettledSnapshot(
        convId: String,
        settleMs: Long = 150,
    ): xyz.mederi.core.contract.dto.ConversationSnapshot = kotlinx.coroutines.withTimeout(5_000) {
        var last = getSnapshot(convId).getOrThrow()
        while (true) {
            kotlinx.coroutines.delay(settleMs)
            val next = getSnapshot(convId).getOrThrow()
            if (next == last) return@withTimeout next
            last = next
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    @Test
    fun example() {
        assertEquals(3, 1 + 2)
    }

    @Test
    fun testBuiltinProvidersIncludesSenseNova() {
        val entries = BuiltinProviders.allEntries()
        val senseNova = entries.find { it.name == "商汤 SenseNova" }
        assertNotNull(senseNova, "BuiltinProviders.allEntries() 应包含 '商汤 SenseNova'")
        assertEquals("https://token.sensenova.cn/v1", senseNova.baseUrl)
        assertEquals(ProviderType.OPENAI_CHAT, senseNova.def.type)
        assertEquals("sensenova", senseNova.def.modelsDevKey)
        assertTrue(senseNova.def.responseSanitization)
        assertTrue(BuiltinProviders.isBuiltinName("商汤 SenseNova"))
        assertEquals(
            """{"reasoning_effort":"none"}""",
            senseNova.def.reasoningParameter?.resolve(xyz.mederi.provider.domain.model.ReasoningLevel.NONE)
        )
        assertEquals(
            """{"reasoning_effort":"high"}""",
            senseNova.def.reasoningParameter?.resolve(xyz.mederi.provider.domain.model.ReasoningLevel.HIGH)
        )
    }

    @Test
    fun testBuiltinProvidersDisplayNameNoTrailingWhitespace() {
        for (entry in BuiltinProviders.allEntries()) {
            assertEquals(entry.name.trim(), entry.name, "预设名 '${entry.name}' 不应包含首尾空格")
        }
    }

    @Test
    fun testUserMessageToChatMessageTextMapping() {
        val userPrompt = "Hello Mederi"
        val metaNote = "\n${xyz.mederi.domain.model.UI_HIDDEN_MARKER}\nNOTE FOR AI: ..."
        val coreMsg = xyz.mederi.domain.model.Message(
            id = "msg_123",
            sessionId = "conv_1",
            role = xyz.mederi.domain.model.MessageRole.USER,
            parts = listOf(xyz.mederi.domain.model.MessagePart.Text(userPrompt + metaNote)),
            status = xyz.mederi.domain.model.MessageStatus.COMPLETED,
            createdAt = "2026-09-08T12:00:00Z"
        )
        val chatMsg = xyz.mederi.core.bridge.MederiModelMapper.toChatMessage(coreMsg)
        val textBlock = chatMsg.blocks.filterIsInstance<xyz.mederi.core.contract.models.ChatBlock.Text>().first()
        println("DEBUG_TEST: textBlock.text='${textBlock.text}' (len=${textBlock.text.length}), expected='$userPrompt' (len=${userPrompt.length})")
        assertEquals(userPrompt, textBlock.text, "真实用户消息解析后不应残留多余换行符")
    }

    @Test
    fun testAgentModeSelectionState() = kotlinx.coroutines.runBlocking {
        val testScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            val mockAiCore = xyz.mederi.core.mock.MockAiCore()
            mockAiCore.initialize()
            val prefs = xyz.mederi.core.contract.preferences.InMemoryPreferencesStore()
            val appState = xyz.mederi.core.ui.appstate.AppState(
                aiCore = mockAiCore,
                preferences = prefs,
                scope = testScope
            )
            appState.hydrate()
            val viewModel = xyz.mederi.core.ui.WorkspaceViewModel(appState)

            println("DEBUG_TEST: initial selectedAgentId=${appState.selectedAgentId.value}, selectedAgentMode=${viewModel.selectedAgentMode.value}")
            assertEquals(xyz.mederi.core.contract.models.AgentMode.AUTONOMOUS, viewModel.selectedAgentMode.value)
            assertEquals(xyz.mederi.core.contract.models.AgentMode.AUTONOMOUS, appState.selectedAgentMode.value)

            println("DEBUG_TEST: switching to APPROVAL...")
            viewModel.selectAgentMode(xyz.mederi.core.contract.models.AgentMode.APPROVAL)
            // 派生 StateFlow（combine）异步更新：先等待流发射，再断言（同步断言会与派生更新竞态）
            kotlinx.coroutines.withTimeout(1_000) {
                appState.selectedAgentMode.first { it == xyz.mederi.core.contract.models.AgentMode.APPROVAL }
            }
            println("DEBUG_TEST: after selectAgentMode APPROVAL: selectedAgentId=${appState.selectedAgentId.value}, selectedAgentMode=${viewModel.selectedAgentMode.value}")
            assertEquals(xyz.mederi.core.contract.models.AgentMode.APPROVAL, viewModel.selectedAgentMode.value)
            assertEquals(xyz.mederi.core.contract.models.AgentMode.APPROVAL, appState.selectedAgentMode.value)
            assertEquals("approval-code", appState.selectedAgentId.value)

            println("DEBUG_TEST: switching back to AUTONOMOUS...")
            viewModel.selectAgentMode(xyz.mederi.core.contract.models.AgentMode.AUTONOMOUS)
            kotlinx.coroutines.withTimeout(1_000) {
                appState.selectedAgentMode.first { it == xyz.mederi.core.contract.models.AgentMode.AUTONOMOUS }
            }
            assertEquals(xyz.mederi.core.contract.models.AgentMode.AUTONOMOUS, appState.selectedAgentMode.value)
            assertEquals(xyz.mederi.core.contract.models.AgentMode.AUTONOMOUS, viewModel.selectedAgentMode.value)
            assertEquals("autonomous-code", appState.selectedAgentId.value)

            println("DEBUG_TEST: switching workType to WORK while mode is AUTONOMOUS...")
            viewModel.selectWorkType(xyz.mederi.core.contract.models.WorkType.WORK)
            kotlinx.coroutines.withTimeout(1_000) {
                appState.selectedWorkType.first { it == xyz.mederi.core.contract.models.WorkType.WORK }
            }
            assertEquals(xyz.mederi.core.contract.models.AgentMode.AUTONOMOUS, viewModel.selectedAgentMode.value)
            assertEquals("autonomous-work", appState.selectedAgentId.value)

            println("DEBUG_TEST: switching mode to APPROVAL while workType is WORK...")
            viewModel.selectAgentMode(xyz.mederi.core.contract.models.AgentMode.APPROVAL)
            kotlinx.coroutines.withTimeout(1_000) {
                appState.selectedAgentMode.first { it == xyz.mederi.core.contract.models.AgentMode.APPROVAL }
            }
            assertEquals(xyz.mederi.core.contract.models.AgentMode.APPROVAL, viewModel.selectedAgentMode.value)
            assertEquals("approval-work", appState.selectedAgentId.value)
        } finally {
            testScope.cancel()
        }
    }

    @Test
    fun testRollbackAndResendMessage() = kotlinx.coroutines.runBlocking {
        val testScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            val mockAiCore = xyz.mederi.core.mock.MockAiCore()
            mockAiCore.initialize()
            val prefs = xyz.mederi.core.contract.preferences.InMemoryPreferencesStore()
            val appState = xyz.mederi.core.ui.appstate.AppState(
                aiCore = mockAiCore,
                preferences = prefs,
                scope = testScope
            )
            appState.hydrate()
            val viewModel = xyz.mederi.core.ui.WorkspaceViewModel(appState)

            // 1. 创建会话并选中模型与会话
            val conv = mockAiCore.createConversation("proj_1", null).getOrThrow()
            appState.selectConversation(conv.id)
            appState.selectModel(appState.availableModels.value.first())
            // 等待 WorkspaceViewModel init 的 selectedConversationId.collect → attach 异步完成：
            // send 读的是 viewModel 内部 conversationId（而非 appState），未 attach 且未选项目时
            // 会被"请先选择项目"分支静默拦截（本测试未 selectProject），产生与调度时序相关的 flaky
            kotlinx.coroutines.withTimeout(5_000) {
                while (viewModel.conversationId != conv.id) kotlinx.coroutines.delay(50)
            }

            // 2. 发送两条消息
            viewModel.send("First message")
            val snap1 = mockAiCore.awaitSettledSnapshot(conv.id)
            assertEquals(2, snap1.messages.size, "应有一条用户消息和一条AI回复")
            val firstUserMsgId = snap1.messages[0].id

            viewModel.send("Second message")
            val snap2 = mockAiCore.awaitSettledSnapshot(conv.id)
            assertEquals(4, snap2.messages.size, "应有两条用户消息和两条AI回复")
            val secondUserMsgId = snap2.messages[2].id

            // 3. 对第二条用户消息执行重试 (rollbackMessage)
            viewModel.rollbackMessage(conv.id, secondUserMsgId, "Second message")
            val snapAfterSecondRollback = mockAiCore.awaitSettledSnapshot(conv.id)
            assertEquals(4, snapAfterSecondRollback.messages.size, "第二条消息重试后总数应依然是4")
            assertEquals(
                "Second message",
                (snapAfterSecondRollback.messages[2].blocks[0] as xyz.mederi.core.contract.models.ChatBlock.Text).text
            )

            // 4. 对第一条用户消息执行重试 (rollbackMessage)
            // 应该删除第一条消息及其之后的所有记录（原4条全部撤回），并重新发送第一条消息
            viewModel.rollbackMessage(conv.id, firstUserMsgId, "First message")
            val snapAfterFirstRollback = mockAiCore.awaitSettledSnapshot(conv.id)
            assertEquals(2, snapAfterFirstRollback.messages.size, "重试第一条消息应删除后续所有记录并重新发送")
            assertEquals(
                "First message",
                (snapAfterFirstRollback.messages[0].blocks[0] as xyz.mederi.core.contract.models.ChatBlock.Text).text
            )
        } finally {
            testScope.cancel()
        }
    }

    @Test
    fun testSnapshotReducerImageDelta() {
        val conv = xyz.mederi.core.contract.models.Conversation(
            id = "conv_img",
            projectId = "proj_1",
            title = "Test",
            status = xyz.mederi.core.contract.models.ConversationStatus.Idle,
            createdAt = 1000L,
            updatedAt = 1000L
        )
        val initialSnap = xyz.mederi.core.contract.dto.ConversationSnapshot(
            conversation = conv,
            messages = emptyList(),
            tokenUsage = xyz.mederi.core.contract.models.TokenUsage(0, 0, 0),
            cost = xyz.mederi.core.contract.models.CostSummary(0.0)
        )
        val imageDeltaEvent = xyz.mederi.core.contract.models.CoreEvent(
            type = xyz.mederi.core.contract.models.CoreEventType.MESSAGE_DELTA,
            sessionId = "conv_img",
            payload = mapOf(
                "type" to "image",
                "url" to "https://example.com/ai_art.png",
                "mimeType" to "image/png",
                "name" to "ai_art.png"
            )
        )
        val updatedSnap = xyz.mederi.core.contract.SnapshotReducer.apply(initialSnap, imageDeltaEvent)
        assertEquals(1, updatedSnap.messages.size)
        val streamMsg = updatedSnap.messages.first()
        assertTrue(streamMsg.isStreaming)
        assertEquals(1, streamMsg.blocks.size)
        val fileBlock = streamMsg.blocks.first() as xyz.mederi.core.contract.models.ChatBlock.File
        assertEquals("https://example.com/ai_art.png", fileBlock.url)
        assertEquals("image/png", fileBlock.mimeType)
        assertEquals("ai_art.png", fileBlock.name)
    }

    @Test
    fun testAssistantImageInComputeChatItems() = kotlinx.coroutines.runBlocking {
        val testScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            val mockAiCore = xyz.mederi.core.mock.MockAiCore()
            mockAiCore.initialize()
            val prefs = xyz.mederi.core.contract.preferences.InMemoryPreferencesStore()
            val appState = xyz.mederi.core.ui.appstate.AppState(aiCore = mockAiCore, preferences = prefs, scope = testScope)
            appState.hydrate()
            val viewModel = xyz.mederi.core.ui.WorkspaceViewModel(appState)

            val assistantMsgWithImage = xyz.mederi.core.contract.models.ChatMessage(
                id = "msg_asst",
                conversationId = "conv_test",
                role = xyz.mederi.core.contract.models.ChatRole.Assistant,
                blocks = listOf(
                    xyz.mederi.core.contract.models.ChatBlock.Text(id = "b1", text = "这是为您生成的图片："),
                    xyz.mederi.core.contract.models.ChatBlock.File(id = "b2", name = "gen.png", url = "https://example.com/gen.png", mimeType = "image/png")
                ),
                createdAt = 1000L,
                completedAt = null,
                parentMessageId = null,
                model = null,
                agent = null,
                isStreaming = false
            )
            val pureImageAssistantMsg = xyz.mederi.core.contract.models.ChatMessage(
                id = "msg_pure_img",
                conversationId = "conv_test",
                role = xyz.mederi.core.contract.models.ChatRole.Assistant,
                blocks = listOf(
                    xyz.mederi.core.contract.models.ChatBlock.File(id = "b3", name = "pure.png", url = "https://example.com/pure.png", mimeType = "image/png")
                ),
                createdAt = 2000L,
                completedAt = null,
                parentMessageId = null,
                model = null,
                agent = null,
                isStreaming = false
            )

            val items = viewModel.computeChatItems(listOf(assistantMsgWithImage, pureImageAssistantMsg))
            println("DEBUG_TEST: items count = ${items.size}")
            assertEquals(2, items.size, "应生成两条 TextMessage 列表项")

            val firstItem = items[0] as xyz.mederi.core.ui.ChatListItem.TextMessage
            assertEquals("这是为您生成的图片：", firstItem.text)
            assertEquals(listOf("https://example.com/gen.png"), firstItem.images)
            assertEquals(false, firstItem.isUser)

            val secondItem = items[1] as xyz.mederi.core.ui.ChatListItem.TextMessage
            assertEquals("", secondItem.text)
            assertEquals(listOf("https://example.com/pure.png"), secondItem.images)
            assertEquals(false, secondItem.isUser)
        } finally {
            testScope.cancel()
        }
    }

    @Test
    fun testImageGating() = kotlinx.coroutines.runBlocking {
        // 图片能力门禁唯一推导链：modelSupportsImages 派生流 / tryAttachImage 拦截 / 发送守卫
        val testScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            val mockAiCore = xyz.mederi.core.mock.MockAiCore()
            mockAiCore.initialize()
            val prefs = xyz.mederi.core.contract.preferences.InMemoryPreferencesStore()
            val appState = xyz.mederi.core.ui.appstate.AppState(
                aiCore = mockAiCore,
                preferences = prefs,
                scope = testScope
            )
            appState.hydrate()
            val viewModel = xyz.mederi.core.ui.WorkspaceViewModel(appState)

            val conv = mockAiCore.createConversation("proj_1", null).getOrThrow()
            appState.selectConversation(conv.id)
            // 种子模型均 supportsImages=false（claude-sonnet-4 是首个可选）
            appState.selectModel(appState.availableModels.value.first())
            kotlinx.coroutines.withTimeout(5_000) {
                while (viewModel.conversationId != conv.id) kotlinx.coroutines.delay(50)
            }

            // 1. 派生流初始 = false；tryAttachImage 拦截 + error 可见 + 不入列
            kotlinx.coroutines.withTimeout(5_000) {
                while (viewModel.modelSupportsImages.value) kotlinx.coroutines.delay(20)
            }
            assertEquals(false, viewModel.modelSupportsImages.value)
            val blocked = viewModel.tryAttachImage("a.png", "image/png", byteArrayOf(1, 2, 3))
            assertEquals(false, blocked, "不支持图片的模型必须拦截附加")
            assertTrue(viewModel.error?.contains("不支持图片") == true, "拦截必须给出可见反馈")
            assertEquals(0, viewModel.pendingImages.size, "被拦图片不得入列")

            // 2. 纯文本发送不受门禁影响
            viewModel.send("text only")
            val snap1 = mockAiCore.awaitSettledSnapshot(conv.id)
            assertEquals(2, snap1.messages.size, "纯文本发送不应被图片门禁拦截")

            // 3. 模型支持图片后：派生流翻转 + 附加成功入列
            mockAiCore.updateProviderModel(
                providerId = "anthropic",
                modelId = "claude-sonnet-4",
                supportsImages = true
            ).getOrThrow()
            kotlinx.coroutines.withTimeout(5_000) {
                while (!viewModel.modelSupportsImages.value) kotlinx.coroutines.delay(20)
            }
            val ok = viewModel.tryAttachImage("b.png", "image/png", byteArrayOf(4, 5, 6))
            assertEquals(true, ok, "支持图片的模型必须放行附加")
            assertEquals(1, viewModel.pendingImages.size)
        } finally {
            testScope.cancel()
        }
    }
}