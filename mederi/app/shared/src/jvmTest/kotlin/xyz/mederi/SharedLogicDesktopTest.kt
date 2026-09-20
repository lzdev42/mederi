package xyz.mederi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.toByteString
import xyz.mederi.core.bridge.BuiltinProviders
import xyz.mederi.core.bridge.MederiModelMapper
import xyz.mederi.core.bridge.mapMessageErrorToStatus
import xyz.mederi.core.contract.models.ConversationStatus
import xyz.mederi.domain.model.SessionStatus
import xyz.mederi.core.contract.ToolArgParser
import xyz.mederi.core.contract.models.ChatBlock
import xyz.mederi.core.contract.models.ToolCallState
import xyz.mederi.core.ui.ChatListItem
import xyz.mederi.core.ui.WorkspaceViewModel
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
        assertEquals(userPrompt, textBlock.text, "真实用户消息解析后不应残留多余换行符")
    }

    @Test
    fun testFragmentedReasoningPartsMergedToSingleBlock() {
        val coreMsg = xyz.mederi.domain.model.Message(
            id = "msg_a7c1c280",
            sessionId = "sess_8ed809f2",
            role = xyz.mederi.domain.model.MessageRole.ASSISTANT,
            parts = listOf(
                xyz.mederi.domain.model.MessagePart.Reasoning(content = listOf("The")),
                xyz.mederi.domain.model.MessagePart.Reasoning(content = listOf(" user")),
                xyz.mederi.domain.model.MessagePart.Reasoning(content = listOf(" describes")),
                xyz.mederi.domain.model.MessagePart.Reasoning(content = listOf(" a UI bug.")),
                xyz.mederi.domain.model.MessagePart.ToolCall(
                    id = "call_1",
                    tool = "read_file",
                    args = "{\"path\":\"foo.kt\"}"
                )
            ),
            status = xyz.mederi.domain.model.MessageStatus.COMPLETED,
            createdAt = "2026-09-17T12:26:44Z"
        )
        val chatMsg = xyz.mederi.core.bridge.MederiModelMapper.toChatMessage(coreMsg)
        val reasoningBlocks = chatMsg.blocks.filterIsInstance<xyz.mederi.core.contract.models.ChatBlock.Reasoning>()
        val toolCallBlocks = chatMsg.blocks.filterIsInstance<xyz.mederi.core.contract.models.ChatBlock.ToolCall>()
        assertEquals(1, reasoningBlocks.size, "连续的碎片化 Reasoning 必须合并为单个 ChatBlock.Reasoning")
        assertEquals("The user describes a UI bug.", reasoningBlocks.first().text, "合并后的 Reasoning 文本必须自然拼接无多余分隔符")
        assertEquals(1, toolCallBlocks.size, "工具调用块保持正常")
    }

    @Test
    fun testToProjectIncludesCreatedAt() {
        val coreProject = xyz.mederi.domain.model.Project(
            id = "proj_1",
            name = "Test Project",
            directory = "/path/to/dir",
            createdAt = "2026-09-17T12:00:00Z",
            updatedAt = "2026-09-17T12:00:00Z"
        )
        val uiProject = xyz.mederi.core.bridge.MederiModelMapper.toProject(coreProject, emptyList())
        assertTrue(uiProject.createdAt > 0L, "toProject 应正确映射 createdAt")
    }

    @Test
    fun testCreateProjectFromDirectoryAutoSelectsProject() = kotlinx.coroutines.runBlocking {
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
            val viewModel = xyz.mederi.core.ui.SidebarViewModel(appState)

            // 1. 创建新目录
            viewModel.createProjectFromDirectory("/tmp/new_project_1")
            kotlinx.coroutines.delay(100)

            val selectedId = appState.selectedProjectId.value
            assertTrue(selectedId != null, "创建项目后应自动设置 selectedProjectId")
            assertTrue(viewModel.uiState.expandedProjectIds.contains(selectedId), "新建的项目应自动在侧边栏展开")

            // 2. 传入相同目录查重，应直接选中已有项目
            viewModel.createProjectFromDirectory("/tmp/new_project_1")
            kotlinx.coroutines.delay(100)
            assertEquals(selectedId, appState.selectedProjectId.value, "重复目录应直接复用并保持选中")
        } finally {
            testScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
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
            assertEquals("approval", appState.selectedAgentId.value)

            println("DEBUG_TEST: switching back to AUTONOMOUS...")
            viewModel.selectAgentMode(xyz.mederi.core.contract.models.AgentMode.AUTONOMOUS)
            kotlinx.coroutines.withTimeout(1_000) {
                appState.selectedAgentMode.first { it == xyz.mederi.core.contract.models.AgentMode.AUTONOMOUS }
            }
            assertEquals(xyz.mederi.core.contract.models.AgentMode.AUTONOMOUS, appState.selectedAgentMode.value)
            assertEquals(xyz.mederi.core.contract.models.AgentMode.AUTONOMOUS, viewModel.selectedAgentMode.value)
            assertEquals("autonomous", appState.selectedAgentId.value)
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
            // 两条 assistant 消息构成同一轮次：正文(带图) + 纯图 + 轮次底部 Footer
            assertEquals(3, items.size, "应生成两条 TextMessage 列表项 + 一条沉底 Footer")

            val firstItem = items[0] as xyz.mederi.core.ui.ChatListItem.TextMessage
            assertEquals("这是为您生成的图片：", firstItem.text)
            assertEquals(listOf("https://example.com/gen.png"), firstItem.images)
            assertEquals(false, firstItem.isUser)

            val secondItem = items[1] as xyz.mederi.core.ui.ChatListItem.TextMessage
            assertEquals("", secondItem.text)
            assertEquals(listOf("https://example.com/pure.png"), secondItem.images)
            assertEquals(false, secondItem.isUser)

            assertTrue(items[2] is xyz.mederi.core.ui.ChatListItem.Footer, "轮次结束应挂沉底 Footer")
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
    fun testOptimisticMessageOrderingBugVerification() = kotlinx.coroutines.runBlocking {
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
            appState.selectModel(appState.availableModels.value.first())
            kotlinx.coroutines.withTimeout(5_000) {
                while (viewModel.conversationId != conv.id) kotlinx.coroutines.delay(50)
            }

            // 构造上文：用户提问 (t=1000) -> AI 生成中调 ask_user (t=2000, isStreaming=true)
            val userMsg = xyz.mederi.core.contract.models.ChatMessage(
                id = "msg_user_1",
                conversationId = conv.id,
                role = xyz.mederi.core.contract.models.ChatRole.User,
                blocks = listOf(xyz.mederi.core.contract.models.ChatBlock.Text("u1", "问我多个问题测试")),
                createdAt = 1000L,
                completedAt = 1000L,
                parentMessageId = null,
                model = null,
                agent = null,
                isStreaming = false,
                error = null
            )
            val assistantStreamingMsg = xyz.mederi.core.contract.models.ChatMessage(
                id = "msg_asst_1",
                conversationId = conv.id,
                role = xyz.mederi.core.contract.models.ChatRole.Assistant,
                blocks = listOf(xyz.mederi.core.contract.models.ChatBlock.Reasoning("r1", "Thinking... ask_user")),
                createdAt = 2000L,
                completedAt = null,
                parentMessageId = "msg_user_1",
                model = null,
                agent = null,
                isStreaming = true,
                error = null
            )

            val currentSnap = mockAiCore.awaitSettledSnapshot(conv.id)
            val snapWithStreaming = currentSnap.copy(
                messages = listOf(userMsg, assistantStreamingMsg)
            )
            mockAiCore.injectSnapshot(conv.id, snapWithStreaming)

            // 发出 QUESTION_REQUESTED 事件
            val reqEvent = xyz.mederi.core.contract.models.CoreEvent(
                type = xyz.mederi.core.contract.models.CoreEventType.QUESTION_REQUESTED,
                sessionId = conv.id,
                payload = mapOf(
                    "questionId" to "q_test",
                    "questions" to """[{"id":"q1","prompt":"选择构建工具","options":["Gradle","Maven"]}]"""
                )
            )
            val snapAfterQuestion = xyz.mederi.core.contract.SnapshotReducer.apply(snapWithStreaming, reqEvent)
            assertEquals(false, snapAfterQuestion.messages.last().isStreaming, "QUESTION_REQUESTED 必须将前序 Assistant 消息的 isStreaming 设为 false")
            mockAiCore.injectSnapshot(conv.id, snapAfterQuestion)

            kotlinx.coroutines.withTimeout(5_000) {
                while (viewModel.pendingQuestion == null) kotlinx.coroutines.delay(50)
            }

            // 用户发送 "呵呵"
            viewModel.send("呵呵")

            val currentMessagesInVm = viewModel.messages
            assertEquals(3, currentMessagesInVm.size, "应包含历史提问、AI问询、以及刚发送的呵呵")
            assertEquals(xyz.mederi.core.contract.models.ChatRole.User, currentMessagesInVm[0].role)
            assertEquals("问我多个问题测试", (currentMessagesInVm[0].blocks[0] as xyz.mederi.core.contract.models.ChatBlock.Text).text)

            // 第 2 条必须是 AI 的 ask_user 消息，绝对不能被"呵呵"插到前面
            assertEquals(xyz.mederi.core.contract.models.ChatRole.Assistant, currentMessagesInVm[1].role)

            // 第 3 条必须是新发送的用户消息"呵呵"
            assertEquals(xyz.mederi.core.contract.models.ChatRole.User, currentMessagesInVm[2].role)
            assertEquals("呵呵", (currentMessagesInVm[2].blocks[0] as xyz.mederi.core.contract.models.ChatBlock.Text).text)
        } finally {
            testScope.cancel()
        }
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

    @Test
    fun testComputeChatItemsSeparatesReasoningAndToolCalls() = kotlinx.coroutines.runBlocking {
        val testScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            val mockAiCore = xyz.mederi.core.mock.MockAiCore()
            mockAiCore.initialize()
            val prefs = xyz.mederi.core.contract.preferences.InMemoryPreferencesStore()
            val appState = xyz.mederi.core.ui.appstate.AppState(aiCore = mockAiCore, preferences = prefs, scope = testScope)
            appState.hydrate()
            val viewModel = xyz.mederi.core.ui.WorkspaceViewModel(appState)

            val userMsg = xyz.mederi.core.contract.models.ChatMessage(
                id = "msg_user_1",
                conversationId = "conv_sep",
                role = xyz.mederi.core.contract.models.ChatRole.User,
                blocks = listOf(xyz.mederi.core.contract.models.ChatBlock.Text(id = "u1", text = "你好")),
                createdAt = 1000L,
                completedAt = 1000L,
                parentMessageId = null,
                model = null,
                agent = null,
                isStreaming = false
            )

            val assistantMsg = xyz.mederi.core.contract.models.ChatMessage(
                id = "msg_asst_1",
                conversationId = "conv_sep",
                role = xyz.mederi.core.contract.models.ChatRole.Assistant,
                blocks = listOf(
                    xyz.mederi.core.contract.models.ChatBlock.Reasoning(id = "r1", text = "正在分析用户的意图..."),
                    xyz.mederi.core.contract.models.ChatBlock.ToolCall(
                        id = "t1",
                        name = "search_code",
                        state = xyz.mederi.core.contract.models.ToolCallState.Completed(
                            input = mapOf("query" to "test"),
                            output = "found 1 match"
                        )
                    ),
                    xyz.mederi.core.contract.models.ChatBlock.Text(id = "txt1", text = "这是最终的清晰回答。")
                ),
                createdAt = 2000L,
                completedAt = 3500L,
                parentMessageId = null,
                model = null,
                agent = null,
                isStreaming = false
            )

            val items = viewModel.computeChatItems(listOf(userMsg, assistantMsg))

            // 预期分为：1条用户文本、1条工作过程汇总栏(内置思考与工具调用)、1条助手交付正文、1条Footer
            assertEquals(4, items.size, "单步含工具调用的轮次应生成4个项：用户消息、工作过程汇总栏、助手正文、Footer")

            val userItem = items[0] as ChatListItem.TextMessage
            assertEquals("你好", userItem.text)
            assertEquals(true, userItem.isUser)

            val workTraceItem = items[1] as ChatListItem.WorkTraceBlock
            assertEquals(true, workTraceItem.isTurnStart)
            assertEquals(1, workTraceItem.totalToolsCount)
            assertEquals(2, workTraceItem.items.size)

            val reasoningSubItem = workTraceItem.items[0] as ChatListItem.Reasoning
            assertEquals("正在分析用户的意图...", reasoningSubItem.text)
            assertEquals(1500L, reasoningSubItem.durationMs)

            val toolCallsSubItem = workTraceItem.items[1] as ChatListItem.ToolCalls
            assertEquals(1, toolCallsSubItem.toolCalls.size)
            assertEquals("search_code", toolCallsSubItem.toolCalls.first().name)

            val textItem = items[2] as ChatListItem.TextMessage
            assertEquals("这是最终的清晰回答。", textItem.text)
            assertEquals(false, textItem.isUser)
            assertEquals(false, textItem.isStepNarration)

            val footerItem = items[3] as ChatListItem.Footer
            assertNotNull(footerItem.footer)
            assertEquals("这是最终的清晰回答。", footerItem.lastMessageText)
            assertTrue(footerItem.fullTurnText.contains("这是最终的清晰回答。"))
            Unit
        } finally {
            testScope.cancel()
        }
    }

    @Test
    fun testMultiStepAssistantTurnLogging() = kotlinx.coroutines.runBlocking {
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
            val viewModel = WorkspaceViewModel(appState)

            val userMsg = xyz.mederi.core.contract.models.ChatMessage(
                id = "u1",
                conversationId = "c1",
                role = xyz.mederi.core.contract.models.ChatRole.User,
                blocks = listOf(xyz.mederi.core.contract.models.ChatBlock.Text("t_u1", "请修复并验证")),
                createdAt = 1000L,
                completedAt = 1000L,
                parentMessageId = null,
                model = null,
                agent = null
            )
            val asst1 = xyz.mederi.core.contract.models.ChatMessage(
                id = "a1",
                conversationId = "c1",
                role = xyz.mederi.core.contract.models.ChatRole.Assistant,
                blocks = listOf(
                    xyz.mederi.core.contract.models.ChatBlock.Reasoning("r1", "先阅读代码..."),
                    xyz.mederi.core.contract.models.ChatBlock.Text("t1", "先读现有代码："),
                    xyz.mederi.core.contract.models.ChatBlock.ToolCall("call_1", "read_file", xyz.mederi.core.contract.models.ToolCallState.Completed(mapOf("path" to "file.kt"), "content"))
                ),
                createdAt = 2000L,
                completedAt = 3000L,
                parentMessageId = null,
                model = "test-model",
                agent = "AUTONOMOUS",
                durationMs = 1000L
            )
            // 数据库中持久化的中间工具结果消息（role=USER, blocks为空，因为ToolResult被ToolCall吸收）
            val toolResultUser1 = xyz.mederi.core.contract.models.ChatMessage(
                id = "tr1",
                conversationId = "c1",
                role = xyz.mederi.core.contract.models.ChatRole.User,
                blocks = emptyList(),
                createdAt = 3100L,
                completedAt = 3100L,
                parentMessageId = null,
                model = null,
                agent = null
            )
            val asst2 = xyz.mederi.core.contract.models.ChatMessage(
                id = "a2",
                conversationId = "c1",
                role = xyz.mederi.core.contract.models.ChatRole.Assistant,
                blocks = listOf(
                    xyz.mederi.core.contract.models.ChatBlock.Reasoning("r2", "派发子代理执行..."),
                    xyz.mederi.core.contract.models.ChatBlock.Text("t2", "派子代理执行任务："),
                    xyz.mederi.core.contract.models.ChatBlock.ToolCall("call_2", "subagent", xyz.mederi.core.contract.models.ToolCallState.Completed(mapOf("action" to "SPAWN", "task" to "子任务0"), "done report"))
                ),
                createdAt = 4000L,
                completedAt = 5000L,
                parentMessageId = null,
                model = "test-model",
                agent = "AUTONOMOUS",
                durationMs = 1000L
            )

            val rawMessages = listOf(userMsg, asst1, toolResultUser1, asst2)
            val currentItems = viewModel.computeChatItems(rawMessages)

            // 预期分为：1用户文本 + 1工作过程汇总栏 + 1Footer = 3项
            assertEquals(3, currentItems.size, "多步轮次的所有工作步骤应汇聚为一个 WorkTraceBlock 汇总栏")

            assertTrue(currentItems[0] is ChatListItem.TextMessage)
            assertEquals("请修复并验证", (currentItems[0] as ChatListItem.TextMessage).text)
            assertEquals(false, (currentItems[0] as ChatListItem.TextMessage).isStepNarration)

            assertTrue(currentItems[1] is ChatListItem.WorkTraceBlock)
            val workTrace = currentItems[1] as ChatListItem.WorkTraceBlock
            assertEquals(2, workTrace.totalToolsCount)
            assertEquals(2000L, workTrace.totalDurationMs)
            assertEquals(6, workTrace.items.size)

            // 检查 WorkTrace 内部的 6 个时序步骤
            assertTrue(workTrace.items[0] is ChatListItem.Reasoning)
            assertEquals("先阅读代码...", (workTrace.items[0] as ChatListItem.Reasoning).text)

            assertTrue(workTrace.items[1] is ChatListItem.TextMessage)
            val asst1Text = workTrace.items[1] as ChatListItem.TextMessage
            assertEquals("先读现有代码：", asst1Text.text)
            assertTrue(asst1Text.isStepNarration, "伴随工具调用的文本应被标记为步骤过渡语")

            assertTrue(workTrace.items[2] is ChatListItem.ToolCalls)
            val toolCalls = (workTrace.items[2] as ChatListItem.ToolCalls).toolCalls
            assertEquals(1, toolCalls.size)
            assertEquals("read_file", toolCalls.first().name)

            assertTrue(workTrace.items[3] is ChatListItem.Reasoning)
            assertEquals("派发子代理执行...", (workTrace.items[3] as ChatListItem.Reasoning).text)

            assertTrue(workTrace.items[4] is ChatListItem.TextMessage)
            val asst2Text = workTrace.items[4] as ChatListItem.TextMessage
            assertEquals("派子代理执行任务：", asst2Text.text)
            assertTrue(asst2Text.isStepNarration, "伴随子代理调用的文本应被标记为步骤过渡语")

            assertTrue(workTrace.items[5] is ChatListItem.SubagentCalls)
            val subagents = (workTrace.items[5] as ChatListItem.SubagentCalls).subagents
            assertEquals(1, subagents.size)
            assertEquals("subagent", subagents.first().name)
            assertEquals("子任务0", subagents.first().target)

            // 沉底 Footer
            assertTrue(currentItems[2] is ChatListItem.Footer)
        } finally {
            testScope.cancel()
        }
    }

    @Test
    fun testFinalResponseVsStepNarrationDistinction() = kotlinx.coroutines.runBlocking {
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

            // 构造多步调试后最终回答的场景（类似真实 sess_ac33d9f8）
            val step1 = xyz.mederi.core.contract.models.ChatMessage(
                id = "msg_step_1",
                conversationId = "conv_test",
                role = xyz.mederi.core.contract.models.ChatRole.Assistant,
                blocks = listOf(
                    xyz.mederi.core.contract.models.ChatBlock.Text(id = "t_step", text = "我先调研这两个 UI 问题的相关代码。"),
                    xyz.mederi.core.contract.models.ChatBlock.ToolCall(
                        id = "tc_step",
                        name = "read_file",
                        state = xyz.mederi.core.contract.models.ToolCallState.Completed(
                            input = mapOf("path" to "Workspace.kt"),
                            output = "// code"
                        )
                    )
                ),
                createdAt = 1000L,
                completedAt = 2000L,
                parentMessageId = null,
                model = null,
                agent = null,
                isStreaming = false
            )
            val stepFinal = xyz.mederi.core.contract.models.ChatMessage(
                id = "msg_step_final",
                conversationId = "conv_test",
                role = xyz.mederi.core.contract.models.ChatRole.Assistant,
                blocks = listOf(
                    xyz.mederi.core.contract.models.ChatBlock.Text(id = "t_final", text = "两个问题都已修复，编译通过。")
                ),
                createdAt = 3000L,
                completedAt = 4000L,
                parentMessageId = null,
                model = null,
                agent = null,
                isStreaming = false
            )

            val items = viewModel.computeChatItems(listOf(step1, stepFinal))

            // 包含 WorkTraceBlock、最终交付正文 TextMessage 和 Footer
            val workTraceItem = items.filterIsInstance<ChatListItem.WorkTraceBlock>().first()

            // 第一条文本伴随工具调用，必须收入 WorkTraceBlock 且 isStepNarration = true
            val narrationItem = workTraceItem.items.filterIsInstance<ChatListItem.TextMessage>().first { it.partId == "t_step" }
            assertTrue(narrationItem.isStepNarration, "中间过渡语必须被识别为 isStepNarration=true")

            // 最终答复不含工具调用，必须留在外部主流中且 isStepNarration = false
            val finalItem = items.filterIsInstance<ChatListItem.TextMessage>().first { it.partId == "t_final" }
            assertFalse(finalItem.isStepNarration, "最终交付回答必须为 isStepNarration=false")

            // 验证工具调用就地挂载在 WorkTraceBlock 内部
            val toolCallItem = workTraceItem.items.filterIsInstance<ChatListItem.ToolCalls>().first()
            assertEquals("msg_step_1_toolcalls", toolCallItem.key, "工具调用必须就地关联到触发它的 step1 消息")
        } finally {
            testScope.cancel()
        }
    }

    /**
     * 有工具调用的轮次（存在 WorkTraceCard）时，所有推理一律收进 WorkTraceBlock，
     * 外部只保留最终回复正文（"只有最终 message 不在 WorkTraceCard 里"）。
     * 最后一个工具调用之后的总结前推理同样进卡，不再作为顶层 deliverable。
     */
    @Test
    fun testAllReasoningInsideWorkTraceWhenToolsPresent() = kotlinx.coroutines.runBlocking {
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

            val userMsg = xyz.mederi.core.contract.models.ChatMessage(
                id = "msg_user_final_reasoning",
                conversationId = "conv_fr",
                role = xyz.mederi.core.contract.models.ChatRole.User,
                blocks = listOf(xyz.mederi.core.contract.models.ChatBlock.Text(id = "u1", text = "帮我分析问题")),
                createdAt = 1000L,
                completedAt = 1000L,
                parentMessageId = null,
                model = null,
                agent = null,
                isStreaming = false
            )
            // 三块顺序：Reasoning("先分析") → ToolCall(read_file, Completed) → Reasoning("综合结论：问题在 X")
            val asstMsg = xyz.mederi.core.contract.models.ChatMessage(
                id = "msg_asst_final_reasoning",
                conversationId = "conv_fr",
                role = xyz.mederi.core.contract.models.ChatRole.Assistant,
                blocks = listOf(
                    xyz.mederi.core.contract.models.ChatBlock.Reasoning(id = "r1", text = "先分析"),
                    xyz.mederi.core.contract.models.ChatBlock.ToolCall(
                        id = "t1",
                        name = "read_file",
                        state = xyz.mederi.core.contract.models.ToolCallState.Completed(
                            input = mapOf("path" to "foo.kt"),
                            output = "// code"
                        )
                    ),
                    xyz.mederi.core.contract.models.ChatBlock.Reasoning(id = "r2", text = "综合结论：问题在 X")
                ),
                createdAt = 2000L,
                completedAt = 3500L,
                parentMessageId = null,
                model = null,
                agent = null,
                isStreaming = false
            )

            val items = viewModel.computeChatItems(listOf(userMsg, asstMsg))

            // 存在 WorkTraceBlock
            val workTrace = items.filterIsInstance<ChatListItem.WorkTraceBlock>().firstOrNull()
            assertNotNull(workTrace, "应存在 WorkTraceBlock（轮次含工具调用 + 前置推理）")

            // 第一个 Reasoning（"先分析"）在工具调用之前 → 归 workItems
            val firstReasoningInWork = workTrace.items.filterIsInstance<ChatListItem.Reasoning>()
                .firstOrNull { it.text == "先分析" }
            assertNotNull(firstReasoningInWork, "第一个 Reasoning（工具调用之前）应在 WorkTraceBlock.items 内")

            // 第二个 Reasoning（"综合结论"）在最后一个工具调用之后 → 同样归 workItems（有工具轮次所有推理都在卡内）
            val secondReasoningInWork = workTrace.items.filterIsInstance<ChatListItem.Reasoning>()
                .firstOrNull { it.text == "综合结论：问题在 X" }
            assertNotNull(secondReasoningInWork, "最后一个工具调用之后的 Reasoning 也应归入 WorkTraceBlock.items")

            // 顶层不应残留任何 Reasoning：外部只保留最终回复正文
            val topReasonings = items.filterIsInstance<ChatListItem.Reasoning>()
            assertTrue(topReasonings.isEmpty(), "有工具轮次顶层不应残留 Reasoning，推理全部在 WorkTraceBlock 内")
        } finally {
            testScope.cancel()
        }
    }

    /**
     * 回归测试：活跃 assistant 流式输出、轮次无工具调用、仅有真实 Reasoning 时，
     * computeChatItems 不得同时产生"空文本占位思考条 + 真实推理条"两个 Reasoning。
     * 历史 bug：占位思考条条件（isActiveAssistant && !turnHasFirstItem）未检查 deliverableItems，
     * 导致占位条与真实推理重复渲染两个"思考中..."。
     */
    @Test
    fun testStreamingReasoningWithoutToolsShowsSingleBlock() = kotlinx.coroutines.runBlocking {
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

            val userMsg = xyz.mederi.core.contract.models.ChatMessage(
                id = "msg_user_stream_reasoning",
                conversationId = "conv_sr",
                role = xyz.mederi.core.contract.models.ChatRole.User,
                blocks = listOf(xyz.mederi.core.contract.models.ChatBlock.Text(id = "u1", text = "分析一下")),
                createdAt = 1000L,
                completedAt = 1000L,
                parentMessageId = null,
                model = null,
                agent = null,
                isStreaming = false
            )
            // 流式中、无工具调用、仅一段真实推理
            val asstMsg = xyz.mederi.core.contract.models.ChatMessage(
                id = "msg_asst_stream_reasoning",
                conversationId = "conv_sr",
                role = xyz.mederi.core.contract.models.ChatRole.Assistant,
                blocks = listOf(
                    xyz.mederi.core.contract.models.ChatBlock.Reasoning(id = "r1", text = "用户想让我分析当前未提交代码的质量问题")
                ),
                createdAt = 2000L,
                completedAt = null,
                parentMessageId = null,
                model = null,
                agent = null,
                isStreaming = true
            )

            val items = viewModel.computeChatItems(listOf(userMsg, asstMsg))

            val reasoningItems = items.filterIsInstance<ChatListItem.Reasoning>()
            assertEquals(1, reasoningItems.size, "流式无工具轮次只应有一个 Reasoning，不能占位与真实推理重复")
            assertEquals("用户想让我分析当前未提交代码的质量问题", reasoningItems.first().text)
            assertTrue(reasoningItems.first().text.isNotBlank(), "不允许存在空文本占位 Reasoning")
        } finally {
            testScope.cancel()
        }
    }

    @Test
    fun testToolCallTargetExtractionAndDisplay() = kotlinx.coroutines.runBlocking {
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
            val viewModel = WorkspaceViewModel(appState)

            // 测试 1: list_directory 参数为空时
            val assistantMsgEmptyInput = xyz.mederi.core.contract.models.ChatMessage(
                id = "msg_asst_dir",
                conversationId = "conv_dir",
                role = xyz.mederi.core.contract.models.ChatRole.Assistant,
                blocks = listOf(
                    xyz.mederi.core.contract.models.ChatBlock.ToolCall(
                        id = "t_dir",
                        name = "list_directory",
                        state = xyz.mederi.core.contract.models.ToolCallState.Completed(
                            input = emptyMap(),
                            output = "[FILE] .DS_Store\n[DIR] src"
                        )
                    )
                ),
                createdAt = 2000L,
                completedAt = 3000L,
                parentMessageId = null,
                model = null,
                agent = null,
                isStreaming = false
            )

            val items = viewModel.computeChatItems(listOf(assistantMsgEmptyInput))
            val workTrace = items.filterIsInstance<ChatListItem.WorkTraceBlock>().first()
            val toolCallsItem = workTrace.items.filterIsInstance<ChatListItem.ToolCalls>().first()
            val toolCallUi = toolCallsItem.toolCalls.first()

            assertEquals("directory", toolCallUi.target, "list_directory 缺省参数应指向 'directory'")

            // 测试 2: 带有 DirectoryPath 参数时
            val assistantMsgWithPath = xyz.mederi.core.contract.models.ChatMessage(
                id = "msg_asst_dir2",
                conversationId = "conv_dir",
                role = xyz.mederi.core.contract.models.ChatRole.Assistant,
                blocks = listOf(
                    xyz.mederi.core.contract.models.ChatBlock.ToolCall(
                        id = "t_dir2",
                        name = "list_directory",
                        state = xyz.mederi.core.contract.models.ToolCallState.Completed(
                            input = mapOf("DirectoryPath" to "app/shared"),
                            output = "[FILE] build.gradle.kts"
                        )
                    )
                ),
                createdAt = 3100L,
                completedAt = 4000L,
                parentMessageId = null,
                model = null,
                agent = null,
                isStreaming = false
            )
            val items2 = viewModel.computeChatItems(listOf(assistantMsgWithPath))
            val workTrace2 = items2.filterIsInstance<ChatListItem.WorkTraceBlock>().first()
            val toolCallUi2 = workTrace2.items.filterIsInstance<ChatListItem.ToolCalls>().first().toolCalls.first()
            assertEquals("app/shared", toolCallUi2.target, "带有 DirectoryPath 参数时应正确提取目标路径")
        } finally {
            testScope.cancel()
        }
    }

    @Test
    fun testRetryHintCountdownParsing() {
        val now = 1000000L
        val retryAt = now + 4000L
        val hintStr = "2/11|Inference exceeds tpm/rpm limit|$retryAt"
        val parsed = xyz.mederi.ui.components.parseRetryHint(hintStr)
        assertNotNull(parsed)
        val remainingSec = ((parsed.retryAtMillis!! - now + 999) / 1000).coerceAtLeast(0L)
        assertEquals("2", parsed.attempt)
        assertEquals("11", parsed.max)
        assertEquals("Inference exceeds tpm/rpm limit", parsed.serverMsg)
        assertEquals(retryAt, parsed.retryAtMillis)
        assertEquals(4L, remainingSec)
    }

    @Test
    fun testParseRetryHintNoThirdSegment() {
        // 无第三段（delayMs 缺失，SnapshotReducer 不拼 retryAt）→ retryAtMillis == null
        val hintStr = "2/11|限流"
        val parsed = xyz.mederi.ui.components.parseRetryHint(hintStr)
        assertNotNull(parsed, "hint 应成功解析")
        assertEquals("2", parsed.attempt)
        assertEquals("11", parsed.max)
        assertEquals("限流", parsed.serverMsg)
        assertNull(parsed.retryAtMillis, "无第三段时 retryAtMillis 必须为 null，不得兜底伪造")
    }

    @Test
    fun testParseRetryHintServerMsgWithPipe() {
        // 右向左解析：末段是数字 → retryAt，中间是 serverMsg（可含 |）
        val hintStr = "1/3|msg with | pipe|1700000000000"
        val parsed = xyz.mederi.ui.components.parseRetryHint(hintStr)
        assertNotNull(parsed)
        assertEquals("1", parsed.attempt)
        assertEquals("3", parsed.max)
        assertEquals("msg with | pipe", parsed.serverMsg, "serverMsg 含 | 不应被截断（右向左解析保护）")
        assertEquals(1700000000000L, parsed.retryAtMillis)
    }

    @Test
    fun testWorkTraceRunningStabilityAndActiveActivityText() = kotlinx.coroutines.runBlocking {
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
            val viewModel = WorkspaceViewModel(appState)

            // 场景 1: 调用命令工具流式运行中 -> 步骤直接平铺在对话流中（不进折叠卡）
            val cmdMsg = xyz.mederi.core.contract.models.ChatMessage(
                id = "m_cmd",
                conversationId = "c1",
                role = xyz.mederi.core.contract.models.ChatRole.Assistant,
                blocks = listOf(
                    xyz.mederi.core.contract.models.ChatBlock.Reasoning("r1", "分析中..."),
                    xyz.mederi.core.contract.models.ChatBlock.ToolCall("tc1", "execute_command", xyz.mederi.core.contract.models.ToolCallState.Running(mapOf("command" to "git status")))
                ),
                createdAt = 1000L,
                completedAt = null,
                parentMessageId = null,
                model = null,
                agent = null,
                isStreaming = true
            )
            val activeItems = viewModel.computeChatItems(listOf(cmdMsg))
            val activeReasoning = activeItems.filterIsInstance<ChatListItem.Reasoning>().firstOrNull()
            assertNotNull(activeReasoning, "流式运行中推理应直接出现在对话时间线中")
            val activeToolCall = activeItems.filterIsInstance<ChatListItem.ToolCalls>().firstOrNull()
            assertNotNull(activeToolCall, "流式运行中工具调用应直接出现在对话时间线中")
            assertTrue(activeToolCall.isRunning, "流式执行命令中 isRunning 必须为 true")

            // 场景 2: 轮次结束后（isStreaming = false） -> 步骤聚合折叠入 WorkTraceBlock
            val completedCmdMsg = cmdMsg.copy(
                isStreaming = false,
                completedAt = 2000L,
                blocks = listOf(
                    xyz.mederi.core.contract.models.ChatBlock.Reasoning("r1", "分析完毕"),
                    xyz.mederi.core.contract.models.ChatBlock.ToolCall("tc1", "execute_command", xyz.mederi.core.contract.models.ToolCallState.Completed(mapOf("command" to "git status"), "On branch main")),
                    xyz.mederi.core.contract.models.ChatBlock.Text("t_done", "检查完成，当前分支为 main。")
                )
            )
            val completedItems = viewModel.computeChatItems(listOf(completedCmdMsg))
            val wt = completedItems.filterIsInstance<ChatListItem.WorkTraceBlock>().firstOrNull()
            assertNotNull(wt, "轮次结束后过程步骤必须折叠打包入 WorkTraceBlock")
            assertEquals(1, wt.totalToolsCount)
            assertTrue(wt.items.any { it is ChatListItem.Reasoning })
            assertTrue(wt.items.any { it is ChatListItem.ToolCalls })
            val topText = completedItems.filterIsInstance<ChatListItem.TextMessage>().firstOrNull()
            assertNotNull(topText, "轮次结束后外部应仅保留最终答复正文")
            assertEquals("检查完成，当前分支为 main。", topText.text)
        } finally {
            testScope.cancel()
        }
    }

    @Test
    fun testLastErrorFromPayload() {
        val payload = mapOf(
            "error" to "boom",
            "errorId" to "e1",
            "fullDiagnostic" to "diag",
            "failureMode" to "PREMATURE_CLOSE"
        )
        val le = xyz.mederi.core.bridge.lastErrorFromPayload(payload)
        assertEquals("boom", le.errorMessage)
        assertEquals("e1", le.errorId)
        assertEquals("diag", le.errorDiagnostic)
        assertTrue(le.isStreamInterrupted, "failureMode=PREMATURE_CLOSE → isStreamInterrupted=true")
        assertTrue(le.atMs > 0)

        // failureMode 非 PREMATURE_CLOSE → isStreamInterrupted=false
        val payloadHttp = mapOf(
            "error" to "oops",
            "errorId" to "e2",
            "fullDiagnostic" to "diag2",
            "failureMode" to "HTTP_ERROR"
        )
        val le2 = xyz.mederi.core.bridge.lastErrorFromPayload(payloadHttp)
        assertFalse(le2.isStreamInterrupted, "failureMode=HTTP_ERROR → isStreamInterrupted=false")
    }

    @Test
    fun testHydrateLastError() {
        val conv = xyz.mederi.core.contract.models.Conversation(
            id = "conv_hydrate",
            projectId = "proj_1",
            title = "Hydrate Test",
            status = xyz.mederi.core.contract.models.ConversationStatus.Idle,
            createdAt = 1000L,
            updatedAt = 1000L
        )
        val base = xyz.mederi.core.contract.dto.ConversationSnapshot(
            conversation = conv,
            messages = emptyList(),
            tokenUsage = xyz.mederi.core.contract.models.TokenUsage(0, 0, 0),
            cost = xyz.mederi.core.contract.models.CostSummary(0.0)
        )

        // null → 原样返回
        val unchanged = xyz.mederi.core.bridge.hydrateLastError(base, null)
        assertNull(unchanged.errorMessage)
        assertNull(unchanged.errorId)
        assertNull(unchanged.errorDiagnostic)
        assertFalse(unchanged.errorIsStreamInterrupted)

        // 非空 → 各错误字段被写入
        val err = xyz.mederi.core.bridge.LastSessionError(
            errorMessage = "boom",
            errorId = "e1",
            errorDiagnostic = "diag",
            isStreamInterrupted = true,
            atMs = 12345L
        )
        val hydrated = xyz.mederi.core.bridge.hydrateLastError(base, err)
        assertEquals("boom", hydrated.errorMessage)
        assertEquals("e1", hydrated.errorId)
        assertEquals("diag", hydrated.errorDiagnostic)
        assertTrue(hydrated.errorIsStreamInterrupted)
    }

    @Test
    fun testSilentFailureWhenNavigatingToErroredConversation() = kotlinx.coroutines.runBlocking {
        val testScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            val mockAiCore = xyz.mederi.core.mock.MockAiCore()
            mockAiCore.initialize()
            val prefs = xyz.mederi.core.contract.preferences.InMemoryPreferencesStore()
            val appState = xyz.mederi.core.ui.appstate.AppState(aiCore = mockAiCore, preferences = prefs, scope = testScope)
            appState.hydrate()
            val viewModel = WorkspaceViewModel(appState)

            // 测试场景 1: 直接切换到已处于 Error 状态的会话 (conv_error)
            val snapBefore = mockAiCore.getSnapshot("conv_error").getOrThrow()

            viewModel.attach("conv_error")
            kotlinx.coroutines.delay(100)

            // 测试场景 2: 用户在会话 A，后台会话 B 发生错误，然后用户切进会话 B
            val conv101 = "conv_101"
            viewModel.attach(conv101)
            kotlinx.coroutines.delay(100)
            val erroredSnap = snapBefore.copy(
                errorMessage = "API Rate limit exceeded (429)",
                errorId = "err_rate_limit",
                errorDiagnostic = "Detailed 429 diagnostic"
            )
            // 触发 conv_error 快照更新
            mockAiCore.injectSnapshot("conv_error", erroredSnap)
            kotlinx.coroutines.delay(100)

            // 此时切换到 conv_error
            viewModel.attach("conv_error")
            kotlinx.coroutines.delay(100)
        } finally {
            testScope.cancel()
        }
    }

    /** MESSAGE_ERROR 事件按权威 sessionStore 状态决定侧边栏状态点（红/蓝），避免 transient 错误误显红点 */
    @Test
    fun testMapMessageErrorToStatus() {
        // session ERROR（非 transient）→ 红点
        assertEquals(ConversationStatus.Error, mapMessageErrorToStatus(SessionStatus.ERROR))
        // session IDLE（transient 可恢复，如限流重试耗尽）→ 蓝点
        assertEquals(ConversationStatus.Idle, mapMessageErrorToStatus(SessionStatus.IDLE))
    }

    /**
     * 验证时间线动作分类与同类连续工具聚合（ran command / readfile / edit_file 等）
     */
    @Test
    fun testToolActionClassificationAndGrouping() {
        val kindCmd = xyz.mederi.ui.components.classifyToolAction("run_command")
        val kindRead = xyz.mederi.ui.components.classifyToolAction("read_file")
        val kindEdit = xyz.mederi.ui.components.classifyToolAction("edit_file")
        val kindSearch = xyz.mederi.ui.components.classifyToolAction("grep_search")
        val kindList = xyz.mederi.ui.components.classifyToolAction("list_dir")

        assertEquals(xyz.mederi.ui.components.ToolActionKind.COMMAND, kindCmd)
        assertEquals(xyz.mederi.ui.components.ToolActionKind.READ, kindRead)
        assertEquals(xyz.mederi.ui.components.ToolActionKind.EDIT, kindEdit)
        assertEquals(xyz.mederi.ui.components.ToolActionKind.SEARCH, kindSearch)
        assertEquals(xyz.mederi.ui.components.ToolActionKind.LIST, kindList)

        val calls = listOf(
            xyz.mederi.core.contract.models.ToolCallUi(id = "c1", name = "run_command", target = "git status", state = xyz.mederi.core.contract.models.ToolCallState.Completed(mapOf("command" to "git status"), "clean")),
            xyz.mederi.core.contract.models.ToolCallUi(id = "c2", name = "run_command", target = "git diff", state = xyz.mederi.core.contract.models.ToolCallState.Completed(mapOf("command" to "git diff"), "")),
            xyz.mederi.core.contract.models.ToolCallUi(id = "c3", name = "read_file", target = "AGENTS.md", state = xyz.mederi.core.contract.models.ToolCallState.Completed(mapOf("path" to "AGENTS.md"), "# Agents")),
            xyz.mederi.core.contract.models.ToolCallUi(id = "c4", name = "read_file", target = "Workspace.kt", state = xyz.mederi.core.contract.models.ToolCallState.Completed(mapOf("path" to "Workspace.kt"), "class Workspace")),
            xyz.mederi.core.contract.models.ToolCallUi(id = "c5", name = "edit_file", target = "Theme.kt", state = xyz.mederi.core.contract.models.ToolCallState.Completed(mapOf("path" to "Theme.kt"), "ok")),
        )

        val groups = xyz.mederi.ui.components.groupToolCallsByAction(calls)
        xyz.mederi.core.ui.DebugLog.info("TEST", "groupToolCallsByAction produced ${groups.size} groups: ${groups.map { "${it.kind}(${it.calls.size})" }}")

        assertEquals(3, groups.size)
        assertEquals(xyz.mederi.ui.components.ToolActionKind.COMMAND, groups[0].kind)
        assertEquals(2, groups[0].calls.size)
        assertEquals(xyz.mederi.ui.components.ToolActionKind.READ, groups[1].kind)
        assertEquals(2, groups[1].calls.size)
        assertEquals(xyz.mederi.ui.components.ToolActionKind.EDIT, groups[2].kind)
        assertEquals(1, groups[2].calls.size)
    }

    /**
     * 验证 ReasoningBlock 展开时滚动容器挂载逻辑：
     * 当 isUnbounded=true 或 enforceMaxHeight=false 时，绝对不能挂载 verticalScroll，
     * 否则在 LazyColumn 的无限最大高度 (Constraints.Infinity) 下会触发崩溃。
     */
    @Test
    fun testReasoningScrollConstraintLogic() {
        // 场景 1：顶层独立展示且未切换为全部展开 -> 启用限高与滚动
        val scrollDefault = xyz.mederi.ui.components.shouldEnableReasoningScroll(
            enforceMaxHeight = true,
            isUnbounded = false
        )
        xyz.mederi.core.ui.DebugLog.info("TEST", "Default reasoning scroll: $scrollDefault (expected true)")
        assertTrue(scrollDefault)

        // 场景 2：用户点击底部\"展开\"，切换为无界全部展开 -> 禁用内部限高与滚动（由 LazyColumn 自然滚动）
        val scrollUnbounded = xyz.mederi.ui.components.shouldEnableReasoningScroll(
            enforceMaxHeight = true,
            isUnbounded = true
        )
        xyz.mederi.core.ui.DebugLog.info("TEST", "Unbounded reasoning scroll: $scrollUnbounded (expected false, prevents infinity height crash)")
        assertFalse(scrollUnbounded)

        // 场景 3：处于 WorkTraceCard 内（enforceMaxHeight=false） -> 禁用子项自身限高与滚动
        val scrollInTrace = xyz.mederi.ui.components.shouldEnableReasoningScroll(
            enforceMaxHeight = false,
            isUnbounded = false
        )
        xyz.mederi.core.ui.DebugLog.info("TEST", "In-trace reasoning scroll: $scrollInTrace (expected false)")
        assertFalse(scrollInTrace)
    }
}

