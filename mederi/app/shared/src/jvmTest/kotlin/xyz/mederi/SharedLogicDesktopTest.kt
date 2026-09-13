package xyz.mederi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.toByteString
import xyz.mederi.core.bridge.BuiltinProviders
import xyz.mederi.core.bridge.MederiModelMapper
import xyz.mederi.core.contract.ToolArgParser
import xyz.mederi.core.contract.models.ChatBlock
import xyz.mederi.core.contract.models.ToolCallState
import xyz.mederi.domain.model.Message as CoreMessage
import xyz.mederi.domain.model.MessagePart as CoreMessagePart
import xyz.mederi.domain.model.MessageRole
import xyz.mederi.provider.domain.model.ProviderType

class SharedLogicDesktopTest {

    /** 工具参数宽容解析：数字/布尔等非字符串值不能导致整条参数丢失（read_file.max_lines / execute_command.timeout_seconds 等） */
    @Test
    fun toolArgParserToleratesNonStringValues() {
        assertEquals(mapOf("pid" to "2627"), ToolArgParser.parse("""{"pid":2627}"""))
        val cmd = ToolArgParser.parse("""{"command":"curl -s https://api.open-meteo.com | head -c 2000","timeout_seconds":30}""")
        assertEquals("curl -s https://api.open-meteo.com | head -c 2000", cmd["command"])
        assertEquals("30", cmd["timeout_seconds"])
        assertEquals(mapOf("path" to "weather", "max_lines" to "200"), ToolArgParser.parse("""{"path":"weather","max_lines":200}"""))
        // 非对象结构 / 畸形输入 → 空 map，不崩
        assertTrue(ToolArgParser.parse("""[1,2,3]""").isEmpty())
        assertTrue(ToolArgParser.parse("not-json").isEmpty())
    }

    /** 落库重放路径：ToolCall 在 assistant 消息、ToolResult 在紧随的 user 消息，必须跨消息聚合还原子工具状态 */
    @Test
    fun toolCallBlockReconstructedFromStoredParts() {
        val json = Json { ignoreUnknownKeys = true }
        val assistantPayload =
            """{"id":"m1","sessionId":"s","role":"ASSISTANT","parts":""" +
                """[{"type":"xyz.mederi.domain.model.MessagePart.Reasoning","content":["stop it"],"summary":null,"encrypted":null,"id":null},""" +
                """{"type":"xyz.mederi.domain.model.MessagePart.ToolCall","id":"call_x","tool":"stop_process","args":"{\"pid\":2627}"}],"status":"COMPLETED","createdAt":"2026-09-12T00:00:00Z"}"""
        val userPayload =
            """{"id":"m2","sessionId":"s","role":"USER","parts":""" +
                """[{"type":"xyz.mederi.domain.model.MessagePart.ToolResult","id":"call_x","tool":"stop_process","output":"Stopped pid 2627.\n(kill -TERM -- -2627 rc=0)","isError":false,"status":null,"durationMs":null,"error":null}],"status":"COMPLETED","createdAt":"2026-09-12T00:00:01Z"}"""

        val assistant = json.decodeFromString<CoreMessage>(assistantPayload)
        val user = json.decodeFromString<CoreMessage>(userPayload)
        val allMessages = listOf(assistant, user)

        // 修复路径：跨消息聚合 ToolResult（历史缺陷 = 只查本条消息 → 重开后工具退化为 Pending、命令/结果全丢）
        val toolResults = MederiModelMapper.buildToolResultsById(allMessages)
        val chatMessage = MederiModelMapper.toChatMessage(assistant, toolResults)

        val toolBlock = chatMessage.blocks.filterIsInstance<ChatBlock.ToolCall>().single()
        assertTrue(toolBlock.state is ToolCallState.Completed, "预期 Completed，实际 ${toolBlock.state::class.simpleName}")

        val state = toolBlock.state as ToolCallState.Completed
        // input 必须解析出 pid（数字参数宽容解析）→ UI target 数据依赖
        assertEquals("2627", state.input["pid"])
        // output 必须带上工具结果 → UI 结果区数据依赖
        assertTrue(state.output.startsWith("Stopped pid 2627"))
    }

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
    fun testRollbackMessageSlicesAndRestoresText() = kotlinx.coroutines.runBlocking {
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

            // 3. 对第二条用户消息执行退回 (rollbackMessage)：截断本条及后续记录，内容粘贴回输入框，不再自动重发
            viewModel.rollbackMessage(conv.id, secondUserMsgId, "Second message")
            kotlinx.coroutines.withTimeout(5_000) {
                while (viewModel.inputDraft.text != "Second message") kotlinx.coroutines.delay(50)
            }
            val snapAfterSecondRollback = mockAiCore.awaitSettledSnapshot(conv.id)
            assertEquals(2, snapAfterSecondRollback.messages.size, "退回第二条消息后应只剩第一条用户消息及其回复")

            // 4. 对第一条用户消息执行退回：应删除第一条消息及其后所有记录，会话清空、内容回输入框
            viewModel.rollbackMessage(conv.id, firstUserMsgId, "First message")
            kotlinx.coroutines.withTimeout(5_000) {
                while (viewModel.inputDraft.text != "First message") kotlinx.coroutines.delay(50)
            }
            val snapAfterFirstRollback = mockAiCore.awaitSettledSnapshot(conv.id)
            assertEquals(0, snapAfterFirstRollback.messages.size, "退回第一条消息应清空会话（不自动重发）")
        } finally {
            testScope.cancel()
        }
    }

    @Test
    fun testRestoreInputFromMessageDecomposesPastedTextAndImage() {
        // 主指令 + 大段文本附件（PromptComposer 编码格式）+ 图片附件（data: File block）反解
        val pastedBody = "line1\nline2\nline3"
        val composed = xyz.mederi.util.PromptComposer.compose(
            "请修改这段配置",
            listOf(xyz.mederi.core.contract.models.PastedTextAttachment("p1", 1, pastedBody, 3, pastedBody.length))
        )
        val imgBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val dataUrl = "data:image/png;base64," + imgBytes.toByteString().base64()
        val msg = xyz.mederi.core.contract.models.ChatMessage(
            id = "user_1",
            conversationId = "conv_1",
            role = xyz.mederi.core.contract.models.ChatRole.User,
            blocks = listOf(
                xyz.mederi.core.contract.models.ChatBlock.File(id = "f1", name = "截图.png", url = dataUrl, mimeType = "image/png"),
                xyz.mederi.core.contract.models.ChatBlock.Text(id = "t1", text = composed)
            ),
            createdAt = 1000L,
            completedAt = 1000L,
            parentMessageId = null,
            model = null,
            agent = null,
            isStreaming = false,
            error = null
        )
        val restored = xyz.mederi.core.ui.restoreInputFromMessage(msg, "fallback")
        assertEquals("请修改这段配置", restored.instruction, "主指令应剥离大段文本标签积分还原")
        assertEquals(1, restored.pastedTexts.size)
        assertEquals(pastedBody, restored.pastedTexts[0].text)
        assertEquals(1, restored.pastedTexts[0].index, "附件 index 应重新从 1 编号")
        assertEquals(1, restored.images.size)
        assertEquals("截图.png", restored.images[0].name)
        assertEquals("image/png", restored.images[0].mimeType)
        assertTrue(restored.images[0].bytes.contentEquals(imgBytes), "图片 bytes 应从 data: URL base64 解码还原")
        assertEquals(dataUrl, restored.images[0].base64DataUrl)

        // 真实纯文本消息：主指令即全文、无附件
        val plain = xyz.mederi.core.ui.restoreInputFromMessage(null, "just text")
        assertEquals("just text", plain.instruction)
        assertTrue(plain.pastedTexts.isEmpty())
        assertTrue(plain.images.isEmpty())
    }

    @Test
    fun testSnapshotReducerTodoUpdatedReplacesStatelessly() {
        val initialSnap = testSnapshot("conv_todo")
        val event = xyz.mederi.core.contract.models.CoreEvent(
            type = xyz.mederi.core.contract.models.CoreEventType.TODO_UPDATED,
            sessionId = "conv_todo",
            payload = mapOf(
                "todos" to """[{"content":"step one","status":"completed"},{"content":"step two","status":"in_progress"}]"""
            )
        )
        val updated = xyz.mederi.core.contract.SnapshotReducer.apply(initialSnap, event)
        assertEquals(2, updated.todos.size, "TODO_UPDATED 必须整体替换快照 todos（无状态投影，不合并）")
        assertEquals(xyz.mederi.core.contract.models.TodoStatus.Completed, updated.todos[0].status)
        assertEquals("t0", updated.todos[0].id, "id 是渲染 key，投影边界按序合成")
        assertEquals(xyz.mederi.core.contract.models.TodoStatus.InProgress, updated.todos[1].status)
    }

    @Test
    fun testSnapshotReducerPlanProgressProjectsSubtasks() {
        val initialSnap = testSnapshot("conv_plan")
        val event = xyz.mederi.core.contract.models.CoreEvent(
            type = xyz.mederi.core.contract.models.CoreEventType.PLAN_PROGRESS,
            sessionId = "conv_plan",
            payload = mapOf(
                "planId" to "plan_x",
                "action" to "subtask-started",
                "todos" to """[{"content":"Subtask 1: bootstrap","status":"completed"},{"content":"Subtask 2: implement","status":"in_progress"},{"content":"Subtask 3: cleanup","status":"pending"}]"""
            )
        )
        val updated = xyz.mederi.core.contract.SnapshotReducer.apply(initialSnap, event)
        assertEquals(3, updated.todos.size, "PLAN_PROGRESS 的 todos 投影必须进入快照")
        assertEquals("Subtask 2: implement", updated.todos[1].content)
    }

    @Test
    fun testSnapshotReducerEmptyTodosClears() {
        val withTodos = testSnapshot("conv_clear").copy(
            todos = listOf(
                xyz.mederi.core.contract.models.TodoItem("t0", "old", xyz.mederi.core.contract.models.TodoStatus.Pending)
            )
        )
        val event = xyz.mederi.core.contract.models.CoreEvent(
            type = xyz.mederi.core.contract.models.CoreEventType.TODO_UPDATED,
            sessionId = "conv_clear",
            payload = mapOf("todos" to "[]")
        )
        val updated = xyz.mederi.core.contract.SnapshotReducer.apply(withTodos, event)
        assertEquals(0, updated.todos.size, "空列表是合法值 = 清空 todo")
    }

    @Test
    fun testSnapshotReducerMalformedTodoPayloadKeepsSnapshot() {
        val withTodos = testSnapshot("conv_bad").copy(
            todos = listOf(
                xyz.mederi.core.contract.models.TodoItem("t0", "keep", xyz.mederi.core.contract.models.TodoStatus.Pending)
            )
        )
        val event = xyz.mederi.core.contract.models.CoreEvent(
            type = xyz.mederi.core.contract.models.CoreEventType.TODO_UPDATED,
            sessionId = "conv_bad",
            payload = mapOf("todos" to "not-json-at-all")
        )
        val updated = xyz.mederi.core.contract.SnapshotReducer.apply(withTodos, event)
        assertEquals(
            listOf(xyz.mederi.core.contract.models.TodoItem("t0", "keep", xyz.mederi.core.contract.models.TodoStatus.Pending)),
            updated.todos,
            "畸形 payload 安全降级：丢弃该事件、保留先前快照，绝不字符串手术挽救"
        )
    }

    private fun testSnapshot(convId: String): xyz.mederi.core.contract.dto.ConversationSnapshot {
        val conv = xyz.mederi.core.contract.models.Conversation(
            id = convId,
            projectId = "proj_1",
            title = "Test",
            status = xyz.mederi.core.contract.models.ConversationStatus.Idle,
            createdAt = 1000L,
            updatedAt = 1000L
        )
        return xyz.mederi.core.contract.dto.ConversationSnapshot(
            conversation = conv,
            messages = emptyList(),
            tokenUsage = xyz.mederi.core.contract.models.TokenUsage(0, 0, 0),
            cost = xyz.mederi.core.contract.models.CostSummary(0.0)
        )
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

    @Test
    fun testSnapshotReducerQuestionRequestedSetsWaitingUserAndResolvedRestoresWorking() {
        val conv = xyz.mederi.core.contract.models.Conversation(
            id = "conv_q",
            projectId = "proj_1",
            title = "Test Question",
            status = xyz.mederi.core.contract.models.ConversationStatus.Working,
            createdAt = 1000L,
            updatedAt = 1000L
        )
        val initialSnap = xyz.mederi.core.contract.dto.ConversationSnapshot(
            conversation = conv,
            messages = emptyList(),
            tokenUsage = xyz.mederi.core.contract.models.TokenUsage(0, 0, 0),
            cost = xyz.mederi.core.contract.models.CostSummary(0.0)
        )

        // 1. QUESTION_REQUESTED -> status 变为 WaitingUser，TurnStatus 派生为 WaitingAnswer
        val questionEvent = xyz.mederi.core.contract.models.CoreEvent(
            type = xyz.mederi.core.contract.models.CoreEventType.QUESTION_REQUESTED,
            sessionId = "conv_q",
            payload = mapOf(
                "questionId" to "q_1",
                "questions" to """[{"question":"确认执行操作吗？","header":"确认"}]"""
            )
        )
        val waitingSnap = xyz.mederi.core.contract.SnapshotReducer.apply(initialSnap, questionEvent)
        assertEquals(xyz.mederi.core.contract.models.ConversationStatus.WaitingUser, waitingSnap.conversation.status)
        assertNotNull(waitingSnap.pendingQuestion)
        assertEquals(xyz.mederi.ui.components.TurnStatus.WaitingAnswer, xyz.mederi.ui.components.deriveTurnStatus(waitingSnap))

        // 2. QUESTION_RESOLVED -> status 恢复为 Working
        val resolveEvent = xyz.mederi.core.contract.models.CoreEvent(
            type = xyz.mederi.core.contract.models.CoreEventType.QUESTION_RESOLVED,
            sessionId = "conv_q",
            payload = mapOf("questionId" to "q_1")
        )
        val resumedSnap = xyz.mederi.core.contract.SnapshotReducer.apply(waitingSnap, resolveEvent)
        assertEquals(xyz.mederi.core.contract.models.ConversationStatus.Working, resumedSnap.conversation.status)
        assertEquals(null, resumedSnap.pendingQuestion)
    }

    @Test
    fun testQuestionRequestedDecodingAndStateHandling() {
        val jsonPayload = """[
            {"id":"q1","prompt":"选择端口","options":["8080","3000"]},
            {"id":"q2","prompt":"请输入你的名字","options":[]},
            {"id":"q3","prompt":"选择功能","options":["A","B"],"multiSelect":true,"allowCustom":true}
        ]"""

        val conv = xyz.mederi.core.contract.models.Conversation(
            id = "conv_q",
            projectId = "proj_1",
            title = "Test Question",
            status = xyz.mederi.core.contract.models.ConversationStatus.Working,
            createdAt = 1000L,
            updatedAt = 1000L
        )
        val initialSnap = xyz.mederi.core.contract.dto.ConversationSnapshot(
            conversation = conv,
            messages = emptyList(),
            tokenUsage = xyz.mederi.core.contract.models.TokenUsage(0, 0, 0),
            cost = xyz.mederi.core.contract.models.CostSummary(0.0)
        )
        val event = xyz.mederi.core.contract.models.CoreEvent(
            type = xyz.mederi.core.contract.models.CoreEventType.QUESTION_REQUESTED,
            sessionId = "conv_q",
            payload = mapOf(
                "questionId" to "q_1",
                "questions" to jsonPayload
            )
        )
        val snap = xyz.mederi.core.contract.SnapshotReducer.apply(initialSnap, event)
        val pq = snap.pendingQuestion
        assertNotNull(pq, "pendingQuestion 不应为空")
        assertEquals("q_1", pq.id)
        assertEquals(3, pq.questions.size)

        // 第一题：默认 allowCustom = false, multiSelect = false
        assertEquals("q1", pq.questions[0].id)
        assertEquals("选择端口", pq.questions[0].prompt)
        assertEquals(listOf("8080", "3000"), pq.questions[0].options)
        assertEquals(false, pq.questions[0].allowCustom)
        assertEquals(false, pq.questions[0].multiSelect)

        // 第二题：自由输入（options 为空）
        assertEquals("q2", pq.questions[1].id)
        assertEquals(emptyList(), pq.questions[1].options)

        // 第三题：多选且允许自定义
        assertEquals("q3", pq.questions[2].id)
        assertEquals(true, pq.questions[2].multiSelect)
        assertEquals(true, pq.questions[2].allowCustom)

        // SESSION_UPDATED 应自动清理 pendingQuestion
        val sessionUpdatedEvent = xyz.mederi.core.contract.models.CoreEvent(
            type = xyz.mederi.core.contract.models.CoreEventType.SESSION_UPDATED,
            sessionId = "conv_q"
        )
        val cleanedSnap = xyz.mederi.core.contract.SnapshotReducer.apply(snap, sessionUpdatedEvent)
        assertNull(cleanedSnap.pendingQuestion, "SESSION_UPDATED 必须清空 pendingQuestion")
        assertNull(cleanedSnap.pendingPlanApproval, "SESSION_UPDATED 必须清空 pendingPlanApproval")
    }

    @Test
    fun testWorkspaceViewModelQuestionInteraction() = kotlinx.coroutines.runBlocking {
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
            kotlinx.coroutines.withTimeout(5_000) {
                while (viewModel.conversationId != conv.id) kotlinx.coroutines.delay(50)
            }

            // 模拟 pendingQuestion 注入
            val jsonPayload = """[{"id":"q1","prompt":"端口","options":["8080","3000"]},{"id":"q2","prompt":"特性","options":["A","B"],"multiSelect":true}]"""
            val reqEvent = xyz.mederi.core.contract.models.CoreEvent(
                type = xyz.mederi.core.contract.models.CoreEventType.QUESTION_REQUESTED,
                sessionId = conv.id,
                payload = mapOf("questionId" to "q_batch", "questions" to jsonPayload)
            )
            val currentSnap = mockAiCore.awaitSettledSnapshot(conv.id)
            mockAiCore.injectSnapshot(conv.id, xyz.mederi.core.contract.SnapshotReducer.apply(currentSnap, reqEvent))

            kotlinx.coroutines.withTimeout(5_000) {
                while (viewModel.pendingQuestion == null) kotlinx.coroutines.delay(50)
            }

            // 测试回答单选与多选
            viewModel.answerQuestion(0, "8080")
            viewModel.answerQuestion(1, listOf("A", "B"))
            assertEquals(listOf("8080"), viewModel.questionAnswers[0])
            assertEquals(listOf("A", "B"), viewModel.questionAnswers[1])

            // 测试提交
            viewModel.submitQuestion()
            val resolvedSnap = mockAiCore.awaitSettledSnapshot(conv.id)
            assertNull(resolvedSnap.pendingQuestion)
            assertEquals(emptyMap(), viewModel.questionAnswers)
        } finally {
            testScope.cancel()
        }
    }


    @Test
    fun testSnapshotReducerPlanApprovalRequestedSetsWaitingUserAndResolvedRestoresWorking() {
        val conv = xyz.mederi.core.contract.models.Conversation(
            id = "conv_plan",
            projectId = "proj_1",
            title = "Test Plan",
            status = xyz.mederi.core.contract.models.ConversationStatus.Working,
            createdAt = 1000L,
            updatedAt = 1000L
        )
        val initialSnap = xyz.mederi.core.contract.dto.ConversationSnapshot(
            conversation = conv,
            messages = emptyList(),
            tokenUsage = xyz.mederi.core.contract.models.TokenUsage(0, 0, 0),
            cost = xyz.mederi.core.contract.models.CostSummary(0.0)
        )

        // 1. PLAN_APPROVAL_REQUESTED -> status 变为 WaitingUser
        val planEvent = xyz.mederi.core.contract.models.CoreEvent(
            type = xyz.mederi.core.contract.models.CoreEventType.PLAN_APPROVAL_REQUESTED,
            sessionId = "conv_plan",
            payload = mapOf(
                "planId" to "plan_1",
                "title" to "架构重构计划",
                "summary" to "优化侧边栏指示灯"
            )
        )
        val waitingSnap = xyz.mederi.core.contract.SnapshotReducer.apply(initialSnap, planEvent)
        assertEquals(xyz.mederi.core.contract.models.ConversationStatus.WaitingUser, waitingSnap.conversation.status)
        assertNotNull(waitingSnap.pendingPlanApproval)
        assertEquals(xyz.mederi.ui.components.TurnStatus.WaitingAnswer, xyz.mederi.ui.components.deriveTurnStatus(waitingSnap))

        // 2. PLAN_APPROVAL_RESOLVED -> status 恢复为 Working
        val resolvePlanEvent = xyz.mederi.core.contract.models.CoreEvent(
            type = xyz.mederi.core.contract.models.CoreEventType.PLAN_APPROVAL_RESOLVED,
            sessionId = "conv_plan",
            payload = mapOf(
                "planId" to "plan_1",
                "approved" to "true"
            )
        )
        val resumedSnap = xyz.mederi.core.contract.SnapshotReducer.apply(waitingSnap, resolvePlanEvent)
        assertEquals(xyz.mederi.core.contract.models.ConversationStatus.Working, resumedSnap.conversation.status)
        assertEquals(null, resumedSnap.pendingPlanApproval)
    }

    @Test
    fun testSnapshotReducerMessageErrorRichPayload() {
        val conv = xyz.mederi.core.contract.models.Conversation(
            id = "conv_err",
            projectId = "proj_1",
            title = "Test Error",
            status = xyz.mederi.core.contract.models.ConversationStatus.Working,
            createdAt = 1000L,
            updatedAt = 1000L
        )
        val initialSnap = xyz.mederi.core.contract.dto.ConversationSnapshot(
            conversation = conv,
            messages = emptyList(),
            tokenUsage = xyz.mederi.core.contract.models.TokenUsage(0, 0, 0),
            cost = xyz.mederi.core.contract.models.CostSummary(0.0)
        )

        val errorPayload = mapOf(
            "error" to "[API] KoogHttpClientException: Model not found (HTTP 404)",
            "errorId" to "err_12345",
            "fullDiagnostic" to "[FATAL] API: KoogHttpClientException\nHTTP Status: 404\nSuggestion: 请检查模型参数",
            "errorCategory" to "API"
        )
        val errorEvent = xyz.mederi.core.contract.models.CoreEvent(
            type = xyz.mederi.core.contract.models.CoreEventType.MESSAGE_ERROR,
            sessionId = "conv_err",
            payload = errorPayload
        )

        val errorSnap = xyz.mederi.core.contract.SnapshotReducer.apply(initialSnap, errorEvent)
        assertEquals(xyz.mederi.core.contract.models.ConversationStatus.Error, errorSnap.conversation.status)
        assertEquals("[API] KoogHttpClientException: Model not found (HTTP 404)", errorSnap.errorMessage)
        assertEquals("err_12345", errorSnap.errorId)
        assertEquals("[FATAL] API: KoogHttpClientException\nHTTP Status: 404\nSuggestion: 请检查模型参数", errorSnap.errorDiagnostic)
        // 报错后轮次结束，StatusBar 只显示运转状态（错误走 ErrorBoard），故此处 deriveTurnStatus 保持 Idle
        assertEquals(xyz.mederi.ui.components.TurnStatus.Idle, xyz.mederi.ui.components.deriveTurnStatus(errorSnap))
    }

    @Test
    fun testSnapshotReducerMessageCompletedCarriesStreamWarning() {
        // 断流警告以 ErrorRecord payload 随 MESSAGE_COMPLETED 到达：会话保持 Idle（turn 真实完成），
        // 但 errorMessage/errorId/errorDiagnostic 写入快照 → ErrorBoard（输入框上方）展示 + 可展开详细报告。
        // statusHint 不再承载警告（原死区：Idle 时永不渲染）→ 断言清空。
        val conv = xyz.mederi.core.contract.models.Conversation(
            id = "conv_warn",
            projectId = "proj_1",
            title = "Test Stream Warning",
            status = xyz.mederi.core.contract.models.ConversationStatus.Working,
            createdAt = 1000L,
            updatedAt = 1000L
        )
        val initialSnap = xyz.mederi.core.contract.dto.ConversationSnapshot(
            conversation = conv,
            messages = emptyList(),
            tokenUsage = xyz.mederi.core.contract.models.TokenUsage(0, 0, 0),
            cost = xyz.mederi.core.contract.models.CostSummary(0.0)
        )
        val streamWarning = "流式连接提前中断：服务器关闭连接但未发送结束标记（已接收 3 帧 / 12 字符）"
        val warnEvent = xyz.mederi.core.contract.models.CoreEvent(
            type = xyz.mederi.core.contract.models.CoreEventType.MESSAGE_COMPLETED,
            sessionId = "conv_warn",
            payload = mapOf(
                "warning" to streamWarning,
                "error" to "[NETWORK] StreamInterrupted: $streamWarning",
                "errorId" to "err_warn01",
                "fullDiagnostic" to "WARNING（无异常，静默失败）：$streamWarning",
                "errorCategory" to "NETWORK",
                "errorSeverity" to "WARNING"
            )
        )
        val warnSnap = xyz.mederi.core.contract.SnapshotReducer.apply(initialSnap, warnEvent)
        assertEquals(xyz.mederi.core.contract.models.ConversationStatus.Idle, warnSnap.conversation.status)
        assertEquals("err_warn01", warnSnap.errorId)
        assertEquals("[NETWORK] StreamInterrupted: $streamWarning", warnSnap.errorMessage)
        assertTrue(warnSnap.errorDiagnostic.orEmpty().contains("WARNING（无异常，静默失败）"))
        assertNull(warnSnap.statusHint, "stream warning must not land in statusHint (the old invisible dead-end)")
        // StatusBar 只显示运转状态：Idle 状态下即使有 errorMessage 也保持 Idle（错误由 ErrorBoard 呈现）
        assertEquals(xyz.mederi.ui.components.TurnStatus.Idle, xyz.mederi.ui.components.deriveTurnStatus(warnSnap))
    }

    @Test
    fun testAssistantFooterFieldsMapping() {
        val coreMsg = xyz.mederi.domain.model.Message(
            id = "msg_assist_1",
            sessionId = "conv_1",
            role = xyz.mederi.domain.model.MessageRole.ASSISTANT,
            parts = listOf(xyz.mederi.domain.model.MessagePart.Text("回复内容")),
            status = xyz.mederi.domain.model.MessageStatus.COMPLETED,
            createdAt = "2026-09-08T12:00:00Z",
            providerId = "prov_1",
            modelId = "mdl_1",
            modelName = "Claude Sonnet",
            reasoningLevel = "HIGH",
            agentMode = "APPROVAL",
            workType = "CODE",
            projectId = "proj_1",
            durationMs = 1500
        )
        val chatMsg = xyz.mederi.core.bridge.MederiModelMapper.toChatMessage(coreMsg)
        assertEquals("Claude Sonnet", chatMsg.modelName, "footer 应带模型显示名")
        assertEquals("APPROVAL", chatMsg.agentMode, "footer 应带 Agent 模式")
        assertEquals("HIGH", chatMsg.thinkingLevel, "footer 应带推理档位")
        assertEquals(1500L, chatMsg.durationMs, "footer 应带耗时")
        // completedAt ≈ createdAt + durationMs（下界估计）
        val createdAtMs = chatMsg.createdAt
        assertEquals(createdAtMs + 1500L, chatMsg.completedAt)
    }

    @Test
    fun testStreamInterruptedFlagFromFailureMode() {
        val conv = xyz.mederi.core.contract.models.Conversation(
            id = "conv_warn2",
            projectId = "proj_1",
            title = "warn2",
            status = xyz.mederi.core.contract.models.ConversationStatus.Working,
            createdAt = 1000L,
            updatedAt = 1000L
        )
        val initialSnap = xyz.mederi.core.contract.dto.ConversationSnapshot(
            conversation = conv,
            messages = emptyList(),
            tokenUsage = xyz.mederi.core.contract.models.TokenUsage(0, 0, 0),
            cost = xyz.mederi.core.contract.models.CostSummary(0.0)
        )
        val streamWarning = "流式连接提前中断：服务器关闭连接但未发送结束标记（已接收 3 帧 / 12 字符）"
        val warnEvent = xyz.mederi.core.contract.models.CoreEvent(
            type = xyz.mederi.core.contract.models.CoreEventType.MESSAGE_COMPLETED,
            sessionId = "conv_warn2",
            payload = mapOf(
                "warning" to streamWarning,
                "error" to "[NETWORK] StreamInterrupted: $streamWarning",
                "errorId" to "err_warn02",
                "fullDiagnostic" to "WARNING（无异常，静默失败）：$streamWarning",
                "errorCategory" to "NETWORK",
                "errorSeverity" to "WARNING",
                "failureMode" to "PREMATURE_CLOSE"
            )
        )
        val snap = xyz.mederi.core.contract.SnapshotReducer.apply(initialSnap, warnEvent)
        assertTrue(snap.errorIsStreamInterrupted, "failureMode=PREMATURE_CLOSE 应标记断流（ErrorBoard 显示继续按钮）")

        // MESSAGE_DELTA 推进应复位断流标记
        val deltaEvent = xyz.mederi.core.contract.models.CoreEvent(
            type = xyz.mederi.core.contract.models.CoreEventType.MESSAGE_DELTA,
            sessionId = "conv_warn2",
            payload = mapOf("messageId" to "m1", "blockId" to "b1", "type" to "text", "text" to "hi")
        )
        val afterDelta = xyz.mederi.core.contract.SnapshotReducer.apply(snap, deltaEvent)
        assertTrue(!afterDelta.errorIsStreamInterrupted, "MESSAGE_DELTA 后应复位断流标记")
    }
}