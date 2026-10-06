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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import xyz.mederi.ui.components.ContainNestedScrollConnection
import xyz.mederi.core.bridge.BuiltinProviders
import xyz.mederi.core.bridge.MederiModelMapper
import xyz.mederi.core.bridge.mapMessageErrorToStatus
import xyz.mederi.core.bridge.mapMessageErrorToStatusByName
import xyz.mederi.core.contract.models.ConversationStatus
import xyz.mederi.domain.model.SessionStatus
import xyz.mederi.core.contract.ToolArgParser
import xyz.mederi.core.contract.models.ChatBlock
import xyz.mederi.core.contract.models.ToolCallState
import xyz.mederi.core.contract.models.ToolCallUi
import xyz.mederi.ui.ChatListItem
import xyz.mederi.ui.WorkspaceViewModel
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

    @Test
    fun testExactSeq385CommandHandling() {
        val rawArgs = """{"command":"grep -n \"SUMMARY\\|Summary\\|ChatMessage(\" mederi/app/shared/src/jvmMain/kotlin/xyz/mederi/core/bridge/MederiModelMapper.kt | head -30"}"""
        val parsed = ToolArgParser.parse(rawArgs)
        println("TEST_DEBUG_PARSED: $parsed")
        assertEquals(1, parsed.size)
        assertTrue(parsed.containsKey("command"))
    }

    @Test
    fun testExactSeq385MederiModelMapper() {
        val json = Json { ignoreUnknownKeys = true }
        val asstStr = """{"id":"msg_5179b7f3","sessionId":"sess_e87fb0d8","role":"ASSISTANT","parts":[{"type":"xyz.mederi.domain.model.MessagePart.Reasoning","content":["test"],"summary":null,"encrypted":null,"id":null},{"type":"xyz.mederi.domain.model.MessagePart.ToolCall","id":"call_s6jt43g1ewfzzdt8f7gnh72k","tool":"execute_command","args":"{\"command\":\"grep -n \\\"SUMMARY\\\\|Summary\\\\|ChatMessage(\\\" mederi/app/shared/src/jvmMain/kotlin/xyz/mederi/core/bridge/MederiModelMapper.kt | head -30\"}"}],"status":"COMPLETED","createdAt":"2026-09-20T08:50:20.029062Z"}"""
        val userStr = """{"id":"msg_df9c3231","sessionId":"sess_e87fb0d8","role":"USER","parts":[{"type":"xyz.mederi.domain.model.MessagePart.ToolResult","id":"call_s6jt43g1ewfzzdt8f7gnh72k","tool":"execute_command","output":"12:import ...","isError":false,"status":null,"durationMs":null,"error":null}],"status":"COMPLETED","createdAt":"2026-09-20T08:50:20.077755Z"}"""
        val asstMsg = json.decodeFromString<CoreMessage>(asstStr)
        val userMsg = json.decodeFromString<CoreMessage>(userStr)
        val toolResults = xyz.mederi.core.bridge.MederiModelMapper.buildToolResultsById(listOf(asstMsg, userMsg))
        val chatMsg = xyz.mederi.core.bridge.MederiModelMapper.toChatMessage(asstMsg, toolResults)
        val toolBlock = chatMsg.blocks.filterIsInstance<xyz.mederi.core.contract.models.ChatBlock.ToolCall>().first()
        println("TEST_DEBUG_TOOL_BLOCK: name=${toolBlock.name}, state=${toolBlock.state}")
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
    fun testUserInterventionToChatMessageTextMapping() {
        val interventionPrompt = """
            <user_intervention>
            [System Note: The user submitted the following guidance while you were executing tools. Incorporate this guidance into your ongoing task without restarting from scratch]:
            任务卡死了，你重调一下
            </user_intervention>
        """.trimIndent()
        val coreMsg = xyz.mederi.domain.model.Message(
            id = "msg_steer_1",
            sessionId = "conv_1",
            role = xyz.mederi.domain.model.MessageRole.USER,
            parts = listOf(xyz.mederi.domain.model.MessagePart.Text(interventionPrompt)),
            status = xyz.mederi.domain.model.MessageStatus.COMPLETED,
            createdAt = "2026-09-08T12:00:00Z"
        )
        val chatMsg = xyz.mederi.core.bridge.MederiModelMapper.toChatMessage(coreMsg)
        val textBlock = chatMsg.blocks.filterIsInstance<xyz.mederi.core.contract.models.ChatBlock.Text>().first()
        println("[Test-Log] toChatMessage stripped text: '${textBlock.text}'")
        assertEquals("任务卡死了，你重调一下", textBlock.text, "引导消息渲染时应剥离 user_intervention 标签和系统提示")
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
            val appState = xyz.mederi.ui.appstate.AppState(
                aiCore = mockAiCore,
                preferences = prefs,
                scope = testScope
            )
            appState.hydrate()
            val viewModel = xyz.mederi.ui.SidebarViewModel(appState)

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
            val appState = xyz.mederi.ui.appstate.AppState(
                aiCore = mockAiCore,
                preferences = prefs,
                scope = testScope
            )
            appState.hydrate()
            val viewModel = xyz.mederi.ui.WorkspaceViewModel(appState)

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
            val appState = xyz.mederi.ui.appstate.AppState(
                aiCore = mockAiCore,
                preferences = prefs,
                scope = testScope
            )
            appState.hydrate()
            val viewModel = xyz.mederi.ui.WorkspaceViewModel(appState)

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
        val restored = xyz.mederi.ui.restoreInputFromMessage(msg, "fallback")
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
        val plain = xyz.mederi.ui.restoreInputFromMessage(null, "just text")
        assertEquals("just text", plain.instruction)
        assertTrue(plain.pastedTexts.isEmpty())
        assertTrue(plain.images.isEmpty())

        // 带有 user_intervention 的消息回退（标签应完全剥离，还原为普通用户指令）
        val interventionFallback = """
            <user_intervention>
            [System Note: The user submitted the following guidance while you were executing tools. Incorporate this guidance into your ongoing task without restarting from scratch]:
            任务卡死了，你重调一下
            </user_intervention>
        """.trimIndent()
        val restoredIntervention = xyz.mederi.ui.restoreInputFromMessage(null, interventionFallback)
        println("[Test-Log] restoredIntervention instruction: '${restoredIntervention.instruction}'")
        assertEquals("任务卡死了，你重调一下", restoredIntervention.instruction, "回退到引导消息时，标签必须完全剥离")
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
    fun testSnapshotReducerPlanProgressDoesNotChangeTodos() {
        // 初始快照 todos 为空（testSnapshot 默认）；种子一条已 APPROVED 的 plan 审批用于验证 planApprovals 迁移
        val initialSnap = testSnapshot("conv_plan").copy(
            planApprovals = listOf(
                xyz.mederi.core.contract.models.PlanApprovalRequest(
                    id = "plan_x",
                    conversationId = "conv_plan",
                    planPath = "",
                    title = "Test Plan",
                    summary = "summary",
                    subtaskCount = 3,
                    planContent = null,
                    status = "APPROVED",
                    subtasks = emptyList()
                )
            )
        )
        val event = xyz.mederi.core.contract.models.CoreEvent(
            type = xyz.mederi.core.contract.models.CoreEventType.PLAN_PROGRESS,
            sessionId = "conv_plan",
            payload = mapOf(
                "planId" to "plan_x",
                "action" to "subtask-started",
                // 保留 todos 字段以证明 reducer 不再消费它（todo-list 与 plan 解耦）
                "todos" to """[{"content":"Subtask 1: bootstrap","status":"completed"},{"content":"Subtask 2: implement","status":"in_progress"},{"content":"Subtask 3: cleanup","status":"pending"}]"""
            )
        )
        val updated = xyz.mederi.core.contract.SnapshotReducer.apply(initialSnap, event)
        assertEquals(0, updated.todos.size, "PLAN_PROGRESS 不再改变快照 todos（todo-list 与 plan 解耦）")
        // planApprovals 投影路径未受损：subtask-started → IN_PROGRESS 迁移仍然生效
        assertEquals(1, updated.planApprovals.size)
        assertEquals("plan_x", updated.planApprovals[0].id)
        assertEquals("IN_PROGRESS", updated.planApprovals[0].status)
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
            val appState = xyz.mederi.ui.appstate.AppState(aiCore = mockAiCore, preferences = prefs, scope = testScope)
            appState.hydrate()
            val viewModel = xyz.mederi.ui.WorkspaceViewModel(appState)

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

            val firstItem = items[0] as xyz.mederi.ui.ChatListItem.TextMessage
            assertEquals("这是为您生成的图片：", firstItem.text)
            assertEquals(listOf("https://example.com/gen.png"), firstItem.images)
            assertEquals(false, firstItem.isUser)

            val secondItem = items[1] as xyz.mederi.ui.ChatListItem.TextMessage
            assertEquals("", secondItem.text)
            assertEquals(listOf("https://example.com/pure.png"), secondItem.images)
            assertEquals(false, secondItem.isUser)

            assertTrue(items[2] is xyz.mederi.ui.ChatListItem.Footer, "轮次结束应挂沉底 Footer")
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
            val appState = xyz.mederi.ui.appstate.AppState(
                aiCore = mockAiCore,
                preferences = prefs,
                scope = testScope
            )
            appState.hydrate()
            val viewModel = xyz.mederi.ui.WorkspaceViewModel(appState)

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
            val attached = viewModel.tryAttachImage("a.png", "image/png", byteArrayOf(1, 2, 3))
            assertEquals(true, attached, "不支持图片的模型也允许附加（在发送时由 core 剔除）")
            assertEquals("Claude Sonnet 4", viewModel.imageStrippedNotice, "应提示该模型不支持图片并将被剔除")
            assertEquals(1, viewModel.pendingImages.size, "图片应正常入列")
            viewModel.pendingImages.clear()

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
        assertEquals(xyz.mederi.ui.TurnStatus.WaitingAnswer, xyz.mederi.ui.deriveTurnStatus(waitingSnap))

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
            {"id":"q3","prompt":"选择功能","options":["A","B"],"multiSelect":true}
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

        // 第一题：默认 multiSelect = false
        assertEquals("q1", pq.questions[0].id)
        assertEquals("选择端口", pq.questions[0].prompt)
        assertEquals(listOf("8080", "3000"), pq.questions[0].options)
        assertEquals(false, pq.questions[0].multiSelect)

        // 第二题：自由输入（options 为空）
        assertEquals("q2", pq.questions[1].id)
        assertEquals(emptyList(), pq.questions[1].options)

        // 第三题：多选
        assertEquals("q3", pq.questions[2].id)
        assertEquals(true, pq.questions[2].multiSelect)

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
            val appState = xyz.mederi.ui.appstate.AppState(
                aiCore = mockAiCore,
                preferences = prefs,
                scope = testScope
            )
            appState.hydrate()
            val viewModel = xyz.mederi.ui.WorkspaceViewModel(appState)

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
            val appState = xyz.mederi.ui.appstate.AppState(
                aiCore = mockAiCore,
                preferences = prefs,
                scope = testScope
            )
            appState.hydrate()
            val viewModel = xyz.mederi.ui.WorkspaceViewModel(appState)

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
        assertEquals(xyz.mederi.ui.TurnStatus.WaitingAnswer, xyz.mederi.ui.deriveTurnStatus(waitingSnap))

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
        assertEquals(xyz.mederi.ui.TurnStatus.Idle, xyz.mederi.ui.deriveTurnStatus(errorSnap))
    }

    /** MESSAGE_ERROR payload 带 finalStatus=IDLE 时，SnapshotReducer 应设会话状态为 Idle（蓝点） */
    @Test
    fun testSnapshotReducerMessageErrorWithFinalStatusIdle() {
        val conv = xyz.mederi.core.contract.models.Conversation(
            id = "conv_err_idle",
            projectId = "proj_1",
            title = "Test Transient Error",
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
            "error" to "[ENV] Rate limit exhausted after retries",
            "errorId" to "err_999",
            "fullDiagnostic" to "[RECOVERABLE] ENV: Rate limit\nRetries exhausted",
            "finalStatus" to "IDLE"
        )
        val errorEvent = xyz.mederi.core.contract.models.CoreEvent(
            type = xyz.mederi.core.contract.models.CoreEventType.MESSAGE_ERROR,
            sessionId = "conv_err_idle",
            payload = errorPayload
        )

        val snap = xyz.mederi.core.contract.SnapshotReducer.apply(initialSnap, errorEvent)
        // finalStatus=IDLE → 蓝点（transient 可恢复）
        assertEquals(xyz.mederi.core.contract.models.ConversationStatus.Idle, snap.conversation.status)
        // 错误字段仍正常填入
        assertEquals("[ENV] Rate limit exhausted after retries", snap.errorMessage)
        assertEquals("err_999", snap.errorId)
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
        assertEquals(xyz.mederi.ui.TurnStatus.Idle, xyz.mederi.ui.deriveTurnStatus(warnSnap))
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
            val appState = xyz.mederi.ui.appstate.AppState(aiCore = mockAiCore, preferences = prefs, scope = testScope)
            appState.hydrate()
            val viewModel = xyz.mederi.ui.WorkspaceViewModel(appState)

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

            val footerItem = items[3] as ChatListItem.Footer
            assertNotNull(footerItem.footer)
            assertEquals("这是最终的清晰回答。", footerItem.lastMessageText)
            assertTrue(footerItem.fullTurnText.contains("这是最终的清晰回答。"))
            Unit
        } finally {
            testScope.cancel()
        }
    }

    /**
     * ask_user 挂起内联化：Running + pendingQuestion 非空 → computeChatItems 在 ask 工具行位置
     * 改发内联 ChatListItem.QuestionCard（不再发该工具的 ToolCalls 行）；
     * 回答后（Completed + pendingQuestion 清空）→ 工具行回归 ToolCalls，无 QuestionCard。
     */
    @Test
    fun testComputeChatItemsInlinesQuestionCardWhileAskUserRunning() {
        val pendingQuestion = xyz.mederi.core.contract.models.QuestionRequest(
            id = "q3",
            conversationId = "conv_qcard",
            questions = listOf(
                xyz.mederi.core.contract.models.QuestionRequest.Question(
                    id = "q3",
                    prompt = "选择功能",
                    options = listOf("A", "B"),
                    multiSelect = true
                )
            )
        )

        fun buildAssistantMsg(state: xyz.mederi.core.contract.models.ToolCallState) =
            xyz.mederi.core.contract.models.ChatMessage(
                id = "msg_asst_ask",
                conversationId = "conv_qcard",
                role = xyz.mederi.core.contract.models.ChatRole.Assistant,
                blocks = listOf(
                    xyz.mederi.core.contract.models.ChatBlock.Text(id = "txt_intro", text = "我需要先确认一下"),
                    xyz.mederi.core.contract.models.ChatBlock.ToolCall(
                        id = "t_ask",
                        name = "ask_user",
                        state = state
                    )
                ),
                createdAt = 2000L,
                completedAt = if (state is xyz.mederi.core.contract.models.ToolCallState.Completed) 3000L else null,
                parentMessageId = null,
                model = null,
                agent = null,
                isStreaming = false
            )

        fun buildSnapshot(pending: xyz.mederi.core.contract.models.QuestionRequest?) =
            xyz.mederi.core.contract.dto.ConversationSnapshot(
                conversation = xyz.mederi.core.contract.models.Conversation(
                    id = "conv_qcard",
                    projectId = "proj_1",
                    title = "Question Card",
                    status = xyz.mederi.core.contract.models.ConversationStatus.WaitingUser,
                    createdAt = 1000L,
                    updatedAt = 2000L
                ),
                messages = emptyList(),
                tokenUsage = xyz.mederi.core.contract.models.TokenUsage(0, 0, 0),
                cost = xyz.mederi.core.contract.models.CostSummary(0.0),
                pendingQuestion = pending
            )

        // 状态 A：ask_user 处于 Running + pendingQuestion 非空 → 内联 QuestionCard，ask 的 ToolCalls 行被取代
        val itemsA = xyz.mederi.ui.computeChatItems(
            msgs = listOf(buildAssistantMsg(xyz.mederi.core.contract.models.ToolCallState.Running())),
            isWorking = true,
            snapshot = buildSnapshot(pendingQuestion)
        )
        val questionCardsA = itemsA.filterIsInstance<ChatListItem.QuestionCard>()
        assertEquals(1, questionCardsA.size, "挂起状态必须恰好有一个内联 QuestionCard")
        assertEquals("q3", questionCardsA[0].request.id)
        assertTrue(
            itemsA.none { it is ChatListItem.ToolCalls && it.toolCalls.any { tc -> tc.name == "ask_user" } },
            "挂起状态不得再发 ask 工具的 ToolCalls 行（消除等待回答的重复标签）"
        )

        // 状态 B：ask_user 已完成（出参含答案）+ pendingQuestion 清空 → 工具行回归 ToolCalls，无 QuestionCard
        val itemsB = xyz.mederi.ui.computeChatItems(
            msgs = listOf(
                buildAssistantMsg(
                    xyz.mederi.core.contract.models.ToolCallState.Completed(
                        input = mapOf("questions" to """[{"id":"q3","prompt":"选择功能","options":["A","B"]}]"""),
                        // 真实 AskUserResult JSON（ask_user 工具经 Json.encodeToString 返回，
                        // 此前用非真实 output 掩盖了 parseAskItems 对真实形态的解析问题）
                        output = """{"answers":[{"questionId":"q3","answers":["A","B"]}]}"""
                    )
                )
            ),
            isWorking = true,
            snapshot = buildSnapshot(null)
        )
        assertTrue(itemsB.none { it is ChatListItem.QuestionCard }, "回答后不应再出现 QuestionCard")
        val askToolLine = itemsB.filterIsInstance<ChatListItem.ToolCalls>()
            .firstOrNull { item -> item.toolCalls.any { it.name == "ask_user" } }
        assertNotNull(askToolLine, "回答后 ask 工具的 ToolCalls 行必须回归")
    }

    @Test
    fun testMultiStepAssistantTurnLogging() = kotlinx.coroutines.runBlocking {
        val testScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            val mockAiCore = xyz.mederi.core.mock.MockAiCore()
            mockAiCore.initialize()
            val prefs = xyz.mederi.core.contract.preferences.InMemoryPreferencesStore()
            val appState = xyz.mederi.ui.appstate.AppState(
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
                    xyz.mederi.core.contract.models.ChatBlock.ToolCall("call_2", "subagent", xyz.mederi.core.contract.models.ToolCallState.Completed(mapOf("action" to "SPAWN", "task" to "子任务0"), "done report")),
                    xyz.mederi.core.contract.models.ChatBlock.Text("t2", "修复完成，子代理汇报成功。")
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

            // 预期分为：1用户文本 + 1工作过程汇总栏 + 1助手正文 + 1Footer = 4项
            assertEquals(4, currentItems.size, "多步轮次的所有非 Message 工作步骤应汇聚为一个 WorkTraceBlock 汇总栏")

            assertTrue(currentItems[0] is ChatListItem.TextMessage)
            assertEquals("请修复并验证", (currentItems[0] as ChatListItem.TextMessage).text)

            assertTrue(currentItems[1] is ChatListItem.WorkTraceBlock)
            val workTrace = currentItems[1] as ChatListItem.WorkTraceBlock
            assertEquals(2, workTrace.totalToolsCount)
            assertEquals(2000L, workTrace.totalDurationMs)
            assertEquals(4, workTrace.items.size)

            // 检查 WorkTrace 内部的 4 个工作步骤（Reasoning, ToolCalls, Reasoning, SubagentCalls）
            assertTrue(workTrace.items[0] is ChatListItem.Reasoning)
            assertEquals("先阅读代码...", (workTrace.items[0] as ChatListItem.Reasoning).text)

            assertTrue(workTrace.items[1] is ChatListItem.ToolCalls)
            val toolCalls = (workTrace.items[1] as ChatListItem.ToolCalls).toolCalls
            assertEquals(1, toolCalls.size)
            assertEquals("read_file", toolCalls.first().name)

            assertTrue(workTrace.items[2] is ChatListItem.Reasoning)
            assertEquals("派发子代理执行...", (workTrace.items[2] as ChatListItem.Reasoning).text)

            assertTrue(workTrace.items[3] is ChatListItem.SubagentCalls)
            val subagents = (workTrace.items[3] as ChatListItem.SubagentCalls).subagents
            assertEquals(1, subagents.size)
            assertEquals("subagent", subagents.first().name)
            assertEquals("子任务0", subagents.first().target)

            // 交付正文留在外部
            assertTrue(currentItems[2] is ChatListItem.TextMessage)
            assertEquals("修复完成，子代理汇报成功。", (currentItems[2] as ChatListItem.TextMessage).text)

            // 沉底 Footer
            assertTrue(currentItems[3] is ChatListItem.Footer)
        } finally {
            testScope.cancel()
        }
    }

    @Test
    fun testNonMessageItemsFoldedIntoWorkTrace() = kotlinx.coroutines.runBlocking {
        val testScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            val mockAiCore = xyz.mederi.core.mock.MockAiCore()
            mockAiCore.initialize()
            val prefs = xyz.mederi.core.contract.preferences.InMemoryPreferencesStore()
            val appState = xyz.mederi.ui.appstate.AppState(
                aiCore = mockAiCore,
                preferences = prefs,
                scope = testScope
            )
            appState.hydrate()
            val viewModel = xyz.mederi.ui.WorkspaceViewModel(appState)

            // 构造包含推理、工具调用及最终回答的多步轮次
            val step1 = xyz.mederi.core.contract.models.ChatMessage(
                id = "msg_step_1",
                conversationId = "conv_test",
                role = xyz.mederi.core.contract.models.ChatRole.Assistant,
                blocks = listOf(
                    xyz.mederi.core.contract.models.ChatBlock.Reasoning(id = "r_step", text = "我先调研这两个 UI 问题的相关代码。"),
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

            // 验证非 Message 项（推理和工具调用）全部收入 WorkTraceBlock 内部
            val reasoningItem = workTraceItem.items.filterIsInstance<ChatListItem.Reasoning>().first { it.key == "msg_step_1_r_step" }
            assertEquals("我先调研这两个 UI 问题的相关代码。", reasoningItem.text)

            val toolCallItem = workTraceItem.items.filterIsInstance<ChatListItem.ToolCalls>().first()
            assertEquals("msg_step_1_tc_step", toolCallItem.key, "工具调用必须就地关联到触发它的 step1 消息及工具块 id")

            // 最终答复 Message 留在外部主流中
            val finalItem = items.filterIsInstance<ChatListItem.TextMessage>().first { it.partId == "t_final" }
            assertEquals("两个问题都已修复，编译通过。", finalItem.text)
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
            val appState = xyz.mederi.ui.appstate.AppState(
                aiCore = mockAiCore,
                preferences = prefs,
                scope = testScope
            )
            appState.hydrate()
            val viewModel = xyz.mederi.ui.WorkspaceViewModel(appState)

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
            val appState = xyz.mederi.ui.appstate.AppState(
                aiCore = mockAiCore,
                preferences = prefs,
                scope = testScope
            )
            appState.hydrate()
            val viewModel = xyz.mederi.ui.WorkspaceViewModel(appState)

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
            val appState = xyz.mederi.ui.appstate.AppState(
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
        val parsed = xyz.mederi.ui.parseRetryHint(hintStr)
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
        val parsed = xyz.mederi.ui.parseRetryHint(hintStr)
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
        val parsed = xyz.mederi.ui.parseRetryHint(hintStr)
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
            val appState = xyz.mederi.ui.appstate.AppState(
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
            val appState = xyz.mederi.ui.appstate.AppState(aiCore = mockAiCore, preferences = prefs, scope = testScope)
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

    /** payload finalStatus 字符串映射：ERROR→红点，IDLE→蓝点，其他/缺失→保守回退红点 */
    @Test
    fun testMapMessageErrorToStatusByName() {
        assertEquals(ConversationStatus.Error, mapMessageErrorToStatusByName("ERROR"))
        assertEquals(ConversationStatus.Idle, mapMessageErrorToStatusByName("IDLE"))
        // 未知值 → 保守回退 Error（不静默）
        assertEquals(ConversationStatus.Error, mapMessageErrorToStatusByName("RUNNING"))
        assertEquals(ConversationStatus.Error, mapMessageErrorToStatusByName(""))
    }

    /**
     * 验证时间线动作分类与同类连续工具聚合（ran command / readfile / edit_file 等）
     */
    @Test
    fun testToolActionClassificationAndGrouping() {
        val kindCmd = xyz.mederi.ui.chat.classifyToolAction("run_command")
        val kindRead = xyz.mederi.ui.chat.classifyToolAction("read_file")
        val kindEdit = xyz.mederi.ui.chat.classifyToolAction("edit_file")
        val kindSearch = xyz.mederi.ui.chat.classifyToolAction("grep_search")
        val kindList = xyz.mederi.ui.chat.classifyToolAction("list_dir")

        assertEquals(xyz.mederi.ui.chat.ToolActionKind.COMMAND, kindCmd)
        assertEquals(xyz.mederi.ui.chat.ToolActionKind.READ, kindRead)
        assertEquals(xyz.mederi.ui.chat.ToolActionKind.EDIT, kindEdit)
        assertEquals(xyz.mederi.ui.chat.ToolActionKind.SEARCH, kindSearch)
        assertEquals(xyz.mederi.ui.chat.ToolActionKind.LIST, kindList)

        val calls = listOf(
            xyz.mederi.core.contract.models.ToolCallUi(id = "c1", name = "run_command", target = "git status", state = xyz.mederi.core.contract.models.ToolCallState.Completed(mapOf("command" to "git status"), "clean")),
            xyz.mederi.core.contract.models.ToolCallUi(id = "c2", name = "run_command", target = "git diff", state = xyz.mederi.core.contract.models.ToolCallState.Completed(mapOf("command" to "git diff"), "")),
            xyz.mederi.core.contract.models.ToolCallUi(id = "c3", name = "read_file", target = "AGENTS.md", state = xyz.mederi.core.contract.models.ToolCallState.Completed(mapOf("path" to "AGENTS.md"), "# Agents")),
            xyz.mederi.core.contract.models.ToolCallUi(id = "c4", name = "read_file", target = "Workspace.kt", state = xyz.mederi.core.contract.models.ToolCallState.Completed(mapOf("path" to "Workspace.kt"), "class Workspace")),
            xyz.mederi.core.contract.models.ToolCallUi(id = "c5", name = "edit_file", target = "Theme.kt", state = xyz.mederi.core.contract.models.ToolCallState.Completed(mapOf("path" to "Theme.kt"), "ok")),
        )

        val groups = xyz.mederi.ui.chat.groupToolCallsByAction(calls)
        xyz.mederi.ui.DebugLog.info("TEST", "groupToolCallsByAction produced ${groups.size} groups: ${groups.map { "${it.kind}(${it.calls.size})" }}")

        // 规范：toolcall 绝不跨条目汇总，每个工具调用必须作为独立项展示
        assertEquals(5, groups.size)
        assertEquals(xyz.mederi.ui.chat.ToolActionKind.COMMAND, groups[0].kind)
        assertEquals(1, groups[0].calls.size)
        assertEquals("c1", groups[0].calls[0].id)
        assertEquals(xyz.mederi.ui.chat.ToolActionKind.COMMAND, groups[1].kind)
        assertEquals(1, groups[1].calls.size)
        assertEquals("c2", groups[1].calls[0].id)
        assertEquals(xyz.mederi.ui.chat.ToolActionKind.READ, groups[2].kind)
        assertEquals(1, groups[2].calls.size)
        assertEquals("c3", groups[2].calls[0].id)
        assertEquals(xyz.mederi.ui.chat.ToolActionKind.READ, groups[3].kind)
        assertEquals(1, groups[3].calls.size)
        assertEquals("c4", groups[3].calls[0].id)
        assertEquals(xyz.mederi.ui.chat.ToolActionKind.EDIT, groups[4].kind)
        assertEquals(1, groups[4].calls.size)
        assertEquals("c5", groups[4].calls[0].id)
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
        xyz.mederi.ui.DebugLog.info("TEST", "Default reasoning scroll: $scrollDefault (expected true)")
        assertTrue(scrollDefault)

        // 场景 2：用户点击底部\"展开\"，切换为无界全部展开 -> 禁用内部限高与滚动（由 LazyColumn 自然滚动）
        val scrollUnbounded = xyz.mederi.ui.components.shouldEnableReasoningScroll(
            enforceMaxHeight = true,
            isUnbounded = true
        )
        xyz.mederi.ui.DebugLog.info("TEST", "Unbounded reasoning scroll: $scrollUnbounded (expected false, prevents infinity height crash)")
        assertFalse(scrollUnbounded)

        // 场景 3：处于 WorkTraceCard 内（enforceMaxHeight=false） -> 禁用子项自身限高与滚动
        val scrollInTrace = xyz.mederi.ui.components.shouldEnableReasoningScroll(
            enforceMaxHeight = false,
            isUnbounded = false
        )
        xyz.mederi.ui.DebugLog.info("TEST", "In-trace reasoning scroll: $scrollInTrace (expected false)")
        assertFalse(scrollInTrace)
    }

    /**
     * 验证「展开/收起」高度切换按钮的显隐逻辑（2026-09 动态高度改造）：
     * 高度上限改为"最大高度"语义后，内容装得下时不应该显示一个点了没反应的按钮。
     */
    @Test
    fun testHeightToggleVisibility() {
        // 场景 1：顶层独立展示 + 内容未溢出（hasOverflow=false）-> 隐藏按钮（容器已按内容收缩）
        assertFalse(
            xyz.mederi.ui.components.shouldShowHeightToggle(
                enforceMaxHeight = true,
                isUnbounded = false,
                hasOverflow = false
            )
        )

        // 场景 2：顶层独立展示 + 内容溢出视口 -> 显示按钮（可切换为全部摊开）
        assertTrue(
            xyz.mederi.ui.components.shouldShowHeightToggle(
                enforceMaxHeight = true,
                isUnbounded = false,
                hasOverflow = true
            )
        )

        // 场景 3：已点开全部摊开 -> 恒显示按钮（否则没有收回的入口）
        assertTrue(
            xyz.mederi.ui.components.shouldShowHeightToggle(
                enforceMaxHeight = true,
                isUnbounded = true,
                hasOverflow = false
            )
        )

        // 场景 4：WorkTraceCard 内的推理子项（enforceMaxHeight=false）-> 永不显示（没有自己的高度开关）
        assertFalse(
            xyz.mederi.ui.components.shouldShowHeightToggle(
                enforceMaxHeight = false,
                isUnbounded = false,
                hasOverflow = true
            )
        )
    }

    @Test
    fun testToolActionKindTodoAndClassification() {
        assertEquals(xyz.mederi.ui.chat.ToolActionKind.TODO, xyz.mederi.ui.chat.classifyToolAction("update_todo"))
        assertEquals(xyz.mederi.ui.chat.ToolActionKind.TODO, xyz.mederi.ui.chat.classifyToolAction("session_todo"))
    }

    @Test
    fun testToolActionAskAndTaskDisambiguation() {
        val resultVerifySubtask = xyz.mederi.ui.chat.classifyToolAction("verify_subtask")
        val resultSubtask = xyz.mederi.ui.chat.classifyToolAction("subtask")
        val resultAskUser = xyz.mederi.ui.chat.classifyToolAction("ask_user")
        val resultAskQuestion = xyz.mederi.ui.chat.classifyToolAction("ask_question")
        val resultAsk = xyz.mederi.ui.chat.classifyToolAction("ask")
        println("[LOG-VERIFY-FIXED] classifyToolAction('verify_subtask') = $resultVerifySubtask")
        println("[LOG-VERIFY-FIXED] classifyToolAction('subtask') = $resultSubtask")
        println("[LOG-VERIFY-FIXED] classifyToolAction('ask_user') = $resultAskUser")
        println("[LOG-VERIFY-FIXED] classifyToolAction('ask_question') = $resultAskQuestion")
        println("[LOG-VERIFY-FIXED] classifyToolAction('ask') = $resultAsk")

        assertEquals(xyz.mederi.ui.chat.ToolActionKind.VERIFY, resultVerifySubtask)
        assertEquals(xyz.mederi.ui.chat.ToolActionKind.OTHER, resultSubtask)
        assertEquals(xyz.mederi.ui.chat.ToolActionKind.ASK, resultAskUser)
        assertEquals(xyz.mederi.ui.chat.ToolActionKind.ASK, resultAskQuestion)
        assertEquals(xyz.mederi.ui.chat.ToolActionKind.ASK, resultAsk)
    }

    @Test
    fun testProbeToolTargetReadFileLineRange() = kotlinx.coroutines.runBlocking {
        val testScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            val mockAiCore = xyz.mederi.core.mock.MockAiCore()
            mockAiCore.initialize()
            val appState = xyz.mederi.ui.appstate.AppState(
                aiCore = mockAiCore,
                preferences = xyz.mederi.core.contract.preferences.InMemoryPreferencesStore(),
                scope = testScope
            )
            val vm = xyz.mederi.ui.WorkspaceViewModel(appState)

            // 1. 指定 0-based offset=1140, max_lines=120 -> 换算 1-based 为 #L1141-1260
            val t1 = vm.probeToolTarget("read_file", mapOf("path" to "TurnExecutor.kt", "offset" to "1140", "max_lines" to "120"))
            assertEquals("TurnExecutor.kt#L1141-1260", t1)

            // 2. 指定 offset=0, max_lines=50 -> #L1-50
            val t2 = vm.probeToolTarget("read_file", mapOf("path" to "TurnExecutor.kt", "offset" to "0", "max_lines" to "50"))
            assertEquals("TurnExecutor.kt#L1-50", t2)

            // 3. 全量读取（无 offset 且无小 max_lines）-> 不带行号后缀
            val t3 = vm.probeToolTarget("read_file", mapOf("path" to "TurnExecutor.kt"))
            assertEquals("TurnExecutor.kt", t3)

            // 4. 显式 startLine/endLine
            val t4 = vm.probeToolTarget("view_file", mapOf("path" to "TurnExecutor.kt", "startLine" to "10", "endLine" to "25"))
            assertEquals("TurnExecutor.kt#L10-25", t4)
        } finally {
            testScope.cancel()
        }
    }

    @Test
    fun testProbeToolTargetTodoSuppressedAndJsonDefense() = kotlinx.coroutines.runBlocking {
        val testScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            val mockAiCore = xyz.mederi.core.mock.MockAiCore()
            mockAiCore.initialize()
            val appState = xyz.mederi.ui.appstate.AppState(
                aiCore = mockAiCore,
                preferences = xyz.mederi.core.contract.preferences.InMemoryPreferencesStore(),
                scope = testScope
            )
            val vm = xyz.mederi.ui.WorkspaceViewModel(appState)

            // update_todo 必须返回 null，不给标题填充参数
            val todoTarget = vm.probeToolTarget("update_todo", mapOf("todos" to "[{\"content\":\"task 1\"}]"))
            assertNull(todoTarget)

            // 任何兜底命中原始 JSON 结构（以 [ 或 { 开头）必须被防御过滤返回 null
            val jsonArrayTarget = vm.probeToolTarget("mcp_arbitrary", mapOf("items" to "[{\"id\":123}]"))
            assertNull(jsonArrayTarget)

            val jsonObjectTarget = vm.probeToolTarget("mcp_complex", mapOf("data" to "{\"foo\":\"bar\"}"))
            assertNull(jsonObjectTarget)
        } finally {
            testScope.cancel()
        }
    }

    @Test
    fun testProbeToolTargetPlanAndVerifyTools() = kotlinx.coroutines.runBlocking {
        val testScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            val mockAiCore = xyz.mederi.core.mock.MockAiCore()
            mockAiCore.initialize()
            val appState = xyz.mederi.ui.appstate.AppState(
                aiCore = mockAiCore,
                preferences = xyz.mederi.core.contract.preferences.InMemoryPreferencesStore(),
                scope = testScope
            )
            val vm = xyz.mederi.ui.WorkspaceViewModel(appState)

            val verifyTarget = vm.probeToolTarget("verify_subtask", mapOf(
                "planId" to "plan_2e844161",
                "subtaskIndex" to "4",
                "status" to "PASS",
                "evidence" to "04-flows.md 验证全部通过：无 JcefBrowserHost/JCEFBrowserControl 字样"
            ))
            val writeLogTarget = vm.probeToolTarget("write_log", mapOf(
                "entry" to "5 通过（每篇的 grep 验证命令全部 PASS）。纯文档改写，未改源码。"
            ))
            val nonTargetFiltered = vm.probeToolTarget("unknown_tool", mapOf(
                "evidence" to "some long evidence",
                "summary" to "some long summary"
            ))
            println("[LOG-VERIFY-TARGET-FIXED] verify_subtask target = $verifyTarget")
            println("[LOG-VERIFY-TARGET-FIXED] write_log target = $writeLogTarget")
            println("[LOG-VERIFY-TARGET-FIXED] nonTargetFiltered = $nonTargetFiltered")

            assertEquals("#4 (PASS)", verifyTarget)
            assertEquals(".mederi/notebook.md", writeLogTarget)
            assertNull(nonTargetFiltered)
        } finally {
            testScope.cancel()
        }
    }

    @Test
    fun testNestedScrollContainmentLogic() = kotlinx.coroutines.runBlocking {
        // 模拟外部 LazyColumn 的 NestedScrollConnection
        var parentReceivedScroll = Offset.Zero
        var parentReceivedFling = Velocity.Zero

        val mockParentConnection = object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                parentReceivedScroll += available
                return available
            }

            override suspend fun onPostFling(
                consumed: Velocity,
                available: Velocity
            ): Velocity {
                parentReceivedFling += available
                return available
            }
        }

        // 场景 1：未加隔离（未展开或普通区域），内部容器到达边界，剩余滚动差量向上冒泡
        val incomingScroll = Offset(0f, 120f)
        val incomingFling = Velocity(0f, 800f)

        // 未隔离时：直接传递给父级 LazyColumn
        mockParentConnection.onPostScroll(Offset.Zero, incomingScroll, NestedScrollSource.UserInput)
        mockParentConnection.onPostFling(Velocity.Zero, incomingFling)

        xyz.mederi.ui.DebugLog.info(
            "TEST_SCROLL",
            "【未隔离场景】子容器越界量: scroll=$incomingScroll, fling=$incomingFling -> 父级接收到: scroll=$parentReceivedScroll, fling=$parentReceivedFling (导致整个页面被牵引滚动)"
        )
        assertEquals(120f, parentReceivedScroll.y)
        assertEquals(800f, parentReceivedFling.y)

        // 重置父级接收计数
        parentReceivedScroll = Offset.Zero
        parentReceivedFling = Velocity.Zero

        // 场景 2：已加隔离（展开且限高），ContainNestedScrollConnection 先拦截处理
        val containConsumedScroll = ContainNestedScrollConnection.onPostScroll(
            consumed = Offset.Zero,
            available = incomingScroll,
            source = NestedScrollSource.UserInput
        )
        val scrollLeftForParent = incomingScroll - containConsumedScroll
        mockParentConnection.onPostScroll(
            consumed = containConsumedScroll,
            available = scrollLeftForParent,
            source = NestedScrollSource.UserInput
        )

        val containConsumedFling = ContainNestedScrollConnection.onPostFling(
            consumed = Velocity.Zero,
            available = incomingFling
        )
        val flingLeftForParent = incomingFling - containConsumedFling
        mockParentConnection.onPostFling(
            consumed = containConsumedFling,
            available = flingLeftForParent
        )

        xyz.mederi.ui.DebugLog.info(
            "TEST_SCROLL",
            "【隔离生效场景】内部越界量: scroll=$incomingScroll, fling=$incomingFling | " +
            "ContainConnection消费: scroll=$containConsumedScroll, fling=$containConsumedFling | " +
            "父级最终接收: scroll=$parentReceivedScroll, fling=$parentReceivedFling (成功阻断穿透！)"
        )

        assertEquals(120f, containConsumedScroll.y)
        assertEquals(800f, containConsumedFling.y)
        assertEquals(0f, parentReceivedScroll.y, "启用 containScroll 后，父级 LazyColumn 接收到的垂直滚动差量必须为 0")
        assertEquals(0f, parentReceivedFling.y, "启用 containScroll 后，父级 LazyColumn 接收到的垂直滑动速度必须为 0")
    }

    @Test
    fun testTurnDiffCardGeneration() = kotlinx.coroutines.runBlocking {
        val testScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            val mockAiCore = xyz.mederi.core.mock.MockAiCore()
            mockAiCore.initialize()
            val prefs = xyz.mederi.core.contract.preferences.InMemoryPreferencesStore()
            val appState = xyz.mederi.ui.appstate.AppState(
                aiCore = mockAiCore,
                preferences = prefs,
                scope = testScope
            )
            appState.hydrate()
            val viewModel = xyz.mederi.ui.WorkspaceViewModel(appState)

            val turnDiffSummary = xyz.mederi.core.contract.models.TurnDiffSummaryUi(
                files = listOf(
                    xyz.mederi.core.contract.models.FileDiffSummaryUi(
                        path = "src/main/App.kt",
                        status = "MODIFIED",
                        additions = 16,
                        deletions = 65
                    ),
                    xyz.mederi.core.contract.models.FileDiffSummaryUi(
                        path = "build.gradle.kts",
                        status = "ADDED",
                        additions = 5,
                        deletions = 0
                    )
                ),
                totalAdditions = 21,
                totalDeletions = 65
            )

            val userMsg = xyz.mederi.core.contract.models.ChatMessage(
                id = "msg_u1",
                conversationId = "conv_1",
                role = xyz.mederi.core.contract.models.ChatRole.User,
                blocks = listOf(xyz.mederi.core.contract.models.ChatBlock.Text("t1", "请修改文件")),
                createdAt = 1000L,
                completedAt = 1000L,
                parentMessageId = null,
                model = null,
                agent = null
            )

            val asstMsg = xyz.mederi.core.contract.models.ChatMessage(
                id = "msg_a1",
                conversationId = "conv_1",
                role = xyz.mederi.core.contract.models.ChatRole.Assistant,
                blocks = listOf(xyz.mederi.core.contract.models.ChatBlock.Text("t2", "已完成修改")),
                createdAt = 2000L,
                completedAt = 3000L,
                parentMessageId = "msg_u1",
                model = "test-model",
                agent = "AUTONOMOUS",
                isStreaming = false,
                turnDiffSummary = turnDiffSummary
            )

            val items = viewModel.computeChatItems(listOf(userMsg, asstMsg))
            val diffCard = items.filterIsInstance<xyz.mederi.ui.ChatListItem.TurnDiffCard>().firstOrNull()
            assertNotNull(diffCard, "必须生成 TurnDiffCard")
            assertEquals("msg_a1", diffCard.messageId)
            assertEquals(2, diffCard.summary.files.size)
            assertEquals(21, diffCard.summary.totalAdditions)
            assertEquals(65, diffCard.summary.totalDeletions)

            val textIdx = items.indexOfFirst { it is xyz.mederi.ui.ChatListItem.TextMessage && !it.isUser }
            val cardIdx = items.indexOfFirst { it is xyz.mederi.ui.ChatListItem.TurnDiffCard }
            val footerIdx = items.indexOfFirst { it is xyz.mederi.ui.ChatListItem.Footer }
            assertTrue(textIdx < cardIdx, "TurnDiffCard 应在正文之后")
            assertTrue(cardIdx < footerIdx, "TurnDiffCard 应在 Footer 之前")
        } finally {
            testScope.cancel()
        }
    }

    @Test
    fun testSnapshotReducerTurnDiffSummary() {
        val initialSnapshot = xyz.mederi.core.contract.dto.ConversationSnapshot(
            conversation = xyz.mederi.core.contract.models.Conversation(
                id = "conv_1",
                projectId = "p1",
                title = "Test",
                status = xyz.mederi.core.contract.models.ConversationStatus.Working,
                createdAt = 1000L,
                updatedAt = 1000L
            ),
            messages = listOf(
                xyz.mederi.core.contract.models.ChatMessage(
                    id = "msg_a1",
                    conversationId = "conv_1",
                    role = xyz.mederi.core.contract.models.ChatRole.Assistant,
                    blocks = listOf(xyz.mederi.core.contract.models.ChatBlock.Text("b1", "正在编辑")),
                    createdAt = 1000L,
                    completedAt = null,
                    parentMessageId = null,
                    model = null,
                    agent = null,
                    isStreaming = true
                )
            ),
            tokenUsage = xyz.mederi.core.contract.models.TokenUsage(0, 0, 0),
            cost = xyz.mederi.core.contract.models.CostSummary(0.0)
        )

        val summary = xyz.mederi.core.contract.models.TurnDiffSummaryUi(
            files = listOf(
                xyz.mederi.core.contract.models.FileDiffSummaryUi("file.kt", "MODIFIED", 10, 2)
            ),
            totalAdditions = 10,
            totalDeletions = 2
        )
        val summaryJson = kotlinx.serialization.json.Json.Default.encodeToString(
            xyz.mederi.core.contract.models.TurnDiffSummaryUi.serializer(),
            summary
        )

        val event = xyz.mederi.core.contract.models.CoreEvent(
            type = xyz.mederi.core.contract.models.CoreEventType.MESSAGE_COMPLETED,
            sessionId = "conv_1",
            payload = mapOf(
                "turnDiffSummary" to summaryJson,
                "diffMessageId" to "msg_a1"
            )
        )

        val updatedSnapshot = xyz.mederi.core.contract.SnapshotReducer.apply(initialSnapshot, event)
        val msg = updatedSnapshot.messages.first()
        assertFalse(msg.isStreaming)
        assertNotNull(msg.turnDiffSummary)
        assertEquals(1, msg.turnDiffSummary?.files?.size)
        assertEquals(10, msg.turnDiffSummary?.totalAdditions)
        assertEquals(2, msg.turnDiffSummary?.totalDeletions)
    }

    @Test
    fun testComputeChatItemsInterleavedToolCallsOrderAndNoGrouping() = kotlinx.coroutines.runBlocking {
        val testScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            val mockAiCore = xyz.mederi.core.mock.MockAiCore()
            mockAiCore.initialize()
            val prefs = xyz.mederi.core.contract.preferences.InMemoryPreferencesStore()
            val appState = xyz.mederi.ui.appstate.AppState(aiCore = mockAiCore, preferences = prefs, scope = testScope)
            appState.hydrate()
            val viewModel = xyz.mederi.ui.WorkspaceViewModel(appState)

            // 构造交替块：Reasoning1 -> ToolCall1 -> Reasoning2 -> ToolCall2 -> Reasoning3 -> ToolCall3 -> FinalText
            val interleavedMsg = xyz.mederi.core.contract.models.ChatMessage(
                id = "msg_interleaved",
                conversationId = "conv_order",
                role = xyz.mederi.core.contract.models.ChatRole.Assistant,
                blocks = listOf(
                    xyz.mederi.core.contract.models.ChatBlock.Reasoning("r1", "思考步骤1"),
                    xyz.mederi.core.contract.models.ChatBlock.ToolCall(
                        "tc1", "execute_command",
                        xyz.mederi.core.contract.models.ToolCallState.Completed(mapOf("command" to "git status"), "clean")
                    ),
                    xyz.mederi.core.contract.models.ChatBlock.Reasoning("r2", "思考步骤2"),
                    xyz.mederi.core.contract.models.ChatBlock.ToolCall(
                        "tc2", "execute_command",
                        xyz.mederi.core.contract.models.ToolCallState.Completed(mapOf("command" to "git diff"), "")
                    ),
                    xyz.mederi.core.contract.models.ChatBlock.Reasoning("r3", "思考步骤3"),
                    xyz.mederi.core.contract.models.ChatBlock.ToolCall(
                        "tc3", "read_file",
                        xyz.mederi.core.contract.models.ToolCallState.Completed(mapOf("path" to "build.gradle.kts"), "...")
                    ),
                    xyz.mederi.core.contract.models.ChatBlock.Text("t_final", "所有步骤已完成！")
                ),
                createdAt = 1000L,
                completedAt = 2000L,
                parentMessageId = null,
                model = null,
                agent = null,
                isStreaming = true
            )

            // 1. 流式状态测试：必须严格就地保持交错时序，绝不沉底汇聚
            val activeItems = viewModel.computeChatItems(listOf(interleavedMsg))
            val activeItemTypes = activeItems.map { it::class.simpleName }
            println("TEST_DEBUG_activeItemTypes: $activeItemTypes")

            // 预期顺序：Reasoning -> ToolCalls -> Reasoning -> ToolCalls -> Reasoning -> ToolCalls -> TextMessage
            assertEquals(
                listOf("Reasoning", "ToolCalls", "Reasoning", "ToolCalls", "Reasoning", "ToolCalls", "TextMessage"),
                activeItemTypes,
                "流式活跃状态下，工具调用必须按发生时序就地交错排列，绝不沉底汇聚"
            )

            // 验证每一个 ToolCalls item 内部只有 1 个 toolCall（绝不跨调用聚合为 '运行 2 条命令'）
            val activeToolCallsItems = activeItems.filterIsInstance<ChatListItem.ToolCalls>()
            assertEquals(3, activeToolCallsItems.size)
            activeToolCallsItems.forEach {
                assertEquals(1, it.toolCalls.size, "每个工具调用必须作为独立项展示，禁止跨条目汇总")
            }
            assertEquals("git status", activeToolCallsItems[0].toolCalls[0].target)
            assertEquals("git diff", activeToolCallsItems[1].toolCalls[0].target)
            assertEquals("build.gradle.kts", activeToolCallsItems[2].toolCalls[0].target)

            // 2. 完成状态测试：isStreaming = false
            val completedMsg = interleavedMsg.copy(isStreaming = false)
            val completedItems = viewModel.computeChatItems(listOf(completedMsg))
            val wt = completedItems.filterIsInstance<ChatListItem.WorkTraceBlock>().firstOrNull()
            assertNotNull(wt, "轮次结束后过程步骤应收纳在 WorkTraceBlock")
            assertEquals(3, wt.totalToolsCount)

            // WorkTraceBlock 展开后的内部子项也必须严格保持交替时序：
            // Reasoning -> ToolCalls -> Reasoning -> ToolCalls -> Reasoning -> ToolCalls
            val wtItemTypes = wt.items.map { it::class.simpleName }
            println("TEST_DEBUG_wtItemTypes: $wtItemTypes")
            assertEquals(
                listOf("Reasoning", "ToolCalls", "Reasoning", "ToolCalls", "Reasoning", "ToolCalls"),
                wtItemTypes,
                "WorkTraceBlock 内部各步骤必须严格保持时序，禁止把工具调用后置沉底"
            )
            val wtToolCalls = wt.items.filterIsInstance<ChatListItem.ToolCalls>()
            assertEquals(3, wtToolCalls.size)
            wtToolCalls.forEach {
                assertEquals(1, it.toolCalls.size, "WorkTraceBlock 内部工具调用也必须 1:1 独立展示")
            }

            // 最终答复在 WorkTraceBlock 外部
            val finalDeliverable = completedItems.filterIsInstance<ChatListItem.TextMessage>().firstOrNull()
            assertNotNull(finalDeliverable)
            assertEquals("所有步骤已完成！", finalDeliverable.text)
        } finally {
            testScope.cancel()
        }
    }

    @Test
    fun testSnapshotReducerToolCallWithQuestionRequestedNoDuplicateAndPreservesArgs() {
        val initialSnap = testSnapshot("conv_q_dedup").copy(
            conversation = testSnapshot("conv_q_dedup").conversation.copy(status = ConversationStatus.Working)
        )

        // 1. LLM 增量流式阶段：产生 tool_call 增量事件（参数尚在生成，content 为空）
        val deltaEvent = xyz.mederi.core.contract.models.CoreEvent(
            type = xyz.mederi.core.contract.models.CoreEventType.MESSAGE_DELTA,
            sessionId = "conv_q_dedup",
            payload = mapOf(
                "type" to "tool_call",
                "name" to "ask_user",
                "content" to ""
            )
        )
        val snapAfterDelta = xyz.mederi.core.contract.SnapshotReducer.apply(initialSnap, deltaEvent)
        assertEquals(1, snapAfterDelta.messages.size)
        val deltaMsg = snapAfterDelta.messages.first()
        assertTrue(deltaMsg.isStreaming)
        assertEquals(1, deltaMsg.blocks.size)
        val deltaTool = deltaMsg.blocks.first() as ChatBlock.ToolCall
        assertEquals("ask_user", deltaTool.name)
        assertTrue(deltaTool.state is ToolCallState.Running)
        assertTrue((deltaTool.state as ToolCallState.Running).input.isEmpty())

        // 2. 工具执行触发 QUESTION_REQUESTED：挂起等待用户，isStreaming 置 false，状态变为 WaitingUser
        val questionEvent = xyz.mederi.core.contract.models.CoreEvent(
            type = xyz.mederi.core.contract.models.CoreEventType.QUESTION_REQUESTED,
            sessionId = "conv_q_dedup",
            payload = mapOf(
                "questionId" to "q_ask_1",
                "questions" to """[{"question":"确认执行吗？","header":"确认"}]"""
            )
        )
        val snapAfterQuestion = xyz.mederi.core.contract.SnapshotReducer.apply(snapAfterDelta, questionEvent)
        assertEquals(ConversationStatus.WaitingUser, snapAfterQuestion.conversation.status)
        assertEquals(1, snapAfterQuestion.messages.size)
        assertFalse(snapAfterQuestion.messages.first().isStreaming, "QUESTION_REQUESTED 必须将 isStreaming 置为 false")
        assertNotNull(snapAfterQuestion.pendingQuestion)

        // 3. TOOL_CALLED 到达（包含完整入参）：在 WaitingUser 状态下，必须复用已有 Assistant 消息并就地更新占位 ToolCall，绝不新建消息或产生空占位副本
        val toolCalledEvent = xyz.mederi.core.contract.models.CoreEvent(
            type = xyz.mederi.core.contract.models.CoreEventType.TOOL_CALLED,
            sessionId = "conv_q_dedup",
            payload = mapOf(
                "toolCallId" to "call_ask_123",
                "tool" to "ask_user",
                "args" to """{"questions":"[{\"question\":\"确认执行吗？\",\"header\":\"确认\"}]"}"""
            )
        )
        val snapAfterToolCalled = xyz.mederi.core.contract.SnapshotReducer.apply(snapAfterQuestion, toolCalledEvent)
        assertEquals(ConversationStatus.WaitingUser, snapAfterToolCalled.conversation.status, "TOOL_CALLED 在 WaitingUser 状态下应保持 WaitingUser")
        assertEquals(1, snapAfterToolCalled.messages.size, "绝不能新建第二条 Assistant 消息")
        val finalMsg = snapAfterToolCalled.messages.first()
        assertEquals(1, finalMsg.blocks.size, "消息内必须只有 1 个 ToolCall 块，不得产生空参数副本")
        val finalTool = finalMsg.blocks.first() as ChatBlock.ToolCall
        assertEquals("tool_call_ask_123", finalTool.id)
        assertEquals("ask_user", finalTool.name)
        assertTrue(finalTool.state is ToolCallState.Running)
        val runningState = finalTool.state as ToolCallState.Running
        assertFalse(runningState.input.isEmpty(), "TOOL_CALLED 后 input 必须包含解析出的入参")
        assertTrue(runningState.input.containsKey("questions"))
    }

    @Test
    fun testSnapshotReducerQuestionResolvedThenToolResult() {
        val initialSnap = testSnapshot("conv_q_flow").copy(
            conversation = testSnapshot("conv_q_flow").conversation.copy(status = ConversationStatus.Working)
        )

        // 1. MESSAGE_DELTA
        val deltaEvent = xyz.mederi.core.contract.models.CoreEvent(
            type = xyz.mederi.core.contract.models.CoreEventType.MESSAGE_DELTA,
            sessionId = "conv_q_flow",
            payload = mapOf("type" to "tool_call", "name" to "ask_user", "content" to "")
        )
        val snap1 = xyz.mederi.core.contract.SnapshotReducer.apply(initialSnap, deltaEvent)

        // 2. TOOL_CALLED
        val toolCalledEvent = xyz.mederi.core.contract.models.CoreEvent(
            type = xyz.mederi.core.contract.models.CoreEventType.TOOL_CALLED,
            sessionId = "conv_q_flow",
            payload = mapOf(
                "toolCallId" to "call_1",
                "tool" to "ask_user",
                "args" to """{"questions":"[{\"id\":\"q1\",\"prompt\":\"提交范围\",\"options\":[\"全部\",\"部分\"]}]"}"""
            )
        )
        val snap2 = xyz.mederi.core.contract.SnapshotReducer.apply(snap1, toolCalledEvent)

        // 3. QUESTION_REQUESTED
        val questionEvent = xyz.mederi.core.contract.models.CoreEvent(
            type = xyz.mederi.core.contract.models.CoreEventType.QUESTION_REQUESTED,
            sessionId = "conv_q_flow",
            payload = mapOf(
                "questionId" to "q_ask_1",
                "questions" to """[{"id":"q1","prompt":"提交范围","options":["全部","部分"]}]"""
            )
        )
        val snap3 = xyz.mederi.core.contract.SnapshotReducer.apply(snap2, questionEvent)
        println("=== PROBE 1: After QUESTION_REQUESTED ===")
        println("status: ${snap3.conversation.status}")
        println("messages.size: ${snap3.messages.size}")
        println("messages[0].isStreaming: ${snap3.messages.first().isStreaming}")
        println("messages[0].blocks[0].state: ${(snap3.messages.first().blocks.first() as ChatBlock.ToolCall).state}")

        // 4. 用户提交回答 -> QUESTION_RESOLVED
        val resolveEvent = xyz.mederi.core.contract.models.CoreEvent(
            type = xyz.mederi.core.contract.models.CoreEventType.QUESTION_RESOLVED,
            sessionId = "conv_q_flow",
            payload = mapOf("questionId" to "q_ask_1")
        )
        val snap4 = xyz.mederi.core.contract.SnapshotReducer.apply(snap3, resolveEvent)
        println("=== PROBE 2: After QUESTION_RESOLVED ===")
        println("status: ${snap4.conversation.status}")
        println("messages.size: ${snap4.messages.size}")
        println("messages[0].isStreaming: ${snap4.messages.first().isStreaming}")

        // 5. 工具执行结束 -> TOOL_RESULT
        val resultEvent = xyz.mederi.core.contract.models.CoreEvent(
            type = xyz.mederi.core.contract.models.CoreEventType.TOOL_RESULT,
            sessionId = "conv_q_flow",
            payload = mapOf(
                "toolCallId" to "call_1",
                "tool" to "ask_user",
                "output" to """{"answers":[{"questionId":"q1","answers":["全部"]}]}""",
                "isError" to "false"
            )
        )
        val snap5 = xyz.mederi.core.contract.SnapshotReducer.apply(snap4, resultEvent)
        // 严格断言：绝不产生多余 Assistant 消息，且 ToolCall 原地转为 Completed 并携带真实答案
        assertEquals(1, snap5.messages.size, "绝不得产生多余的空 Assistant 占位消息")
        val finalMsg = snap5.messages.first()
        assertTrue(finalMsg.isStreaming, "QUESTION_RESOLVED 后恢复执行，isStreaming 必须为 true")
        assertEquals(1, finalMsg.blocks.size)
        val finalTool = finalMsg.blocks.first() as ChatBlock.ToolCall
        assertEquals("tool_call_1", finalTool.id)
        assertTrue(finalTool.state is ToolCallState.Completed, "ToolCall 状态必须由 Running 迁为 Completed")
        val completedState = finalTool.state as ToolCallState.Completed
        assertEquals("""{"answers":[{"questionId":"q1","answers":["全部"]}]}""", completedState.output)
    }

    @Test
    fun testParseAskItems() {
        // 1. 单问题单答案
        val singleInput = mapOf("questions" to """[{"id":"q1","prompt":"是否提交改动？","options":["是","否"]}]""")
        val singleOutput = """{"answers":[{"questionId":"q1","answers":["是"]}]}"""
        val items1 = xyz.mederi.ui.components.parseAskItems(singleInput, singleOutput)
        assertEquals(1, items1.size)
        assertEquals("是否提交改动？", items1[0].prompt)
        assertEquals("是", items1[0].answer)
        assertFalse(items1[0].isDeclined)

        // 2. 多问题与多选答案
        val multiInput = mapOf(
            "questions" to """[{"id":"q1","prompt":"范围？"},{"id":"q2","prompt":"附加操作？"}]"""
        )
        val multiOutput = """{"answers":[{"questionId":"q1","answers":["全选"]}, {"questionId":"q2","answers":["测试","提交"]}]}"""
        val items2 = xyz.mederi.ui.components.parseAskItems(multiInput, multiOutput)
        assertEquals(2, items2.size)
        assertEquals("范围？", items2[0].prompt)
        assertEquals("全选", items2[0].answer)
        assertEquals("附加操作？", items2[1].prompt)
        assertEquals("测试, 提交", items2[1].answer)

        // 3. 用户拒绝回答
        val declinedOutput = "User declined to answer the questions."
        val items3 = xyz.mederi.ui.components.parseAskItems(singleInput, declinedOutput)
        assertEquals(1, items3.size)
        assertEquals("是否提交改动？", items3[0].prompt)
        assertNull(items3[0].answer)
        assertTrue(items3[0].isDeclined)

        // 4. 等待回答中（output 为 null）
        val pendingItems = xyz.mederi.ui.components.parseAskItems(singleInput, null)
        assertEquals(1, pendingItems.size)
        assertEquals("是否提交改动？", pendingItems[0].prompt)
        assertNull(pendingItems[0].answer)
        assertFalse(pendingItems[0].isDeclined)
    }

    @Test
    fun testStatusBarTurnStartPreservedWhenIdleBeforeWorking() = kotlinx.coroutines.runBlocking {
        val testScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            val mockAiCore = xyz.mederi.core.mock.MockAiCore()
            mockAiCore.initialize()
            val prefs = xyz.mederi.core.contract.preferences.InMemoryPreferencesStore()
            val appState = xyz.mederi.ui.appstate.AppState(aiCore = mockAiCore, preferences = prefs, scope = testScope)
            appState.hydrate()
            appState.selectModel(appState.availableModels.value.first())
            val viewModel = WorkspaceViewModel(appState)

            val conv = mockAiCore.createConversation("proj_1", null).getOrThrow()
            viewModel.attach(conv.id)

            // 发送消息，turnStartedAt 会被写入
            viewModel.send("Hello")
            assertNotNull(viewModel.turnStartedAt, "send() 之后 turnStartedAt 应该非空")

            // 模拟在进入 Working 之前，收到一个处于 Idle 的快照（例如 initial observe snapshot）
            val idleSnap = mockAiCore.getSnapshot(conv.id).getOrThrow().copy(
                conversation = conv.copy(status = ConversationStatus.Idle)
            )
            val applySnapshotMethod = viewModel::class.java.getDeclaredMethod(
                "applySnapshot",
                String::class.java,
                xyz.mederi.core.contract.dto.ConversationSnapshot::class.java
            ).apply { isAccessible = true }

            applySnapshotMethod.invoke(viewModel, conv.id, idleSnap)

            // 验证计时锚点没有被误清为 null
            assertNotNull(viewModel.turnStartedAt, "进入 Working 之前的 Idle 快照绝不能清除 turnStartedAt 计时锚点（避免显示 0 秒并卡死）")
        } finally {
            testScope.cancel()
        }
    }

    @Test
    fun testAutoContinueTriggeredOnceOnStreamInterruptedWithEmittedTokens() = kotlinx.coroutines.runBlocking {
        val testScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            val mockAiCore = xyz.mederi.core.mock.MockAiCore()
            mockAiCore.initialize()
            val prefs = xyz.mederi.core.contract.preferences.InMemoryPreferencesStore()
            val appState = xyz.mederi.ui.appstate.AppState(aiCore = mockAiCore, preferences = prefs, scope = testScope)
            appState.hydrate()
            appState.selectModel(appState.availableModels.value.first())
            val viewModel = WorkspaceViewModel(appState)

            val conv = mockAiCore.createConversation("proj_1", null).getOrThrow()
            viewModel.attach(conv.id)

            val applySnapshotMethod = viewModel::class.java.getDeclaredMethod(
                "applySnapshot",
                String::class.java,
                xyz.mederi.core.contract.dto.ConversationSnapshot::class.java
            ).apply { isAccessible = true }

            // 构造一个中途断流且已吐出部分字符的快照
            val partialAssistantMsg = xyz.mederi.core.contract.models.ChatMessage(
                id = "msg_asst_partial",
                conversationId = conv.id,
                role = xyz.mederi.core.contract.models.ChatRole.Assistant,
                blocks = listOf(ChatBlock.Text("t_partial", "这是半截回复")),
                createdAt = 1000L,
                completedAt = 2000L,
                parentMessageId = "msg_user_1",
                model = "test-model",
                agent = "AUTONOMOUS"
            )
            val interruptedSnap = xyz.mederi.core.contract.dto.ConversationSnapshot(
                conversation = conv.copy(status = ConversationStatus.Error),
                messages = listOf(partialAssistantMsg),
                tokenUsage = xyz.mederi.core.contract.models.TokenUsage(0, 0, 0),
                cost = xyz.mederi.core.contract.models.CostSummary(0.0),
                errorIsStreamInterrupted = true,
                errorMessage = "流式空闲超时"
            )

            applySnapshotMethod.invoke(viewModel, conv.id, interruptedSnap)

            // 等待 450ms 让自动补发协程执行（delay 300ms）
            kotlinx.coroutines.delay(450L)

            // 验证自动触发了 sendMessage("Continue")（由于 MockAiCore 响应极快，可能在乐观消息中或已落库进入 messages）
            val hasContinueUserMessage = viewModel.optimisticUserMessage?.blocks?.any { (it as? ChatBlock.Text)?.text == "Continue" } == true ||
                viewModel.messages.any { msg ->
                    msg.role == xyz.mederi.core.contract.models.ChatRole.User &&
                        msg.blocks.any { (it as? ChatBlock.Text)?.text == "Continue" }
                }
            assertTrue(hasContinueUserMessage, "应自动替用户补发一句 Continue")

            // 再次调用 applySnapshot 模拟相同中断快照再次推送，验证严格只发一次（防死循环）
            val autoContinueCountField = viewModel::class.java.getDeclaredField("sessionCache").apply { isAccessible = true }
            val sessionCache = autoContinueCountField.get(viewModel)
            val countMapField = sessionCache::class.java.getDeclaredField("autoContinueCountByConv").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val countMap = countMapField.get(sessionCache) as Map<String, Int>
            assertEquals(1, countMap[conv.id], "自动补发次数必须为 1")
        } finally {
            testScope.cancel()
        }
    }

    @Test
    fun testQueuedMessagesEnqueueRemoveAndSteer() = kotlinx.coroutines.runBlocking {
        val testScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            val mockAiCore = xyz.mederi.core.mock.MockAiCore()
            mockAiCore.initialize()
            val prefs = xyz.mederi.core.contract.preferences.InMemoryPreferencesStore()
            val appState = xyz.mederi.ui.appstate.AppState(aiCore = mockAiCore, preferences = prefs, scope = testScope)
            appState.hydrate()
            val viewModel = WorkspaceViewModel(appState)

            // 选择会话
            val conv = mockAiCore.createConversation("proj_1", null).getOrThrow()
            appState.selectConversation(conv.id)
            kotlinx.coroutines.withTimeout(5_000) {
                while (viewModel.conversationId != conv.id) kotlinx.coroutines.delay(50)
            }

            // 初始队列为空
            assertTrue(viewModel.currentQueuedMessages.isEmpty(), "初始排队应为空")

            // 入队 2 条消息
            viewModel.enqueueCurrentInput("Message 1")
            viewModel.enqueueCurrentInput("Message 2")

            val queued = viewModel.currentQueuedMessages
            assertEquals(2, queued.size, "排队队列应有 2 条消息")
            assertEquals("Message 1", queued[0].text)
            assertEquals("Message 2", queued[1].text)

            // 移出第一条
            viewModel.removeQueuedMessage(queued[0].id)
            val remaining = viewModel.currentQueuedMessages
            assertEquals(1, remaining.size, "移出后应剩 1 条")
            assertEquals("Message 2", remaining[0].text)

            // 立即发送（引导模式）
            viewModel.steerQueuedMessage(remaining[0])
            assertTrue(viewModel.currentQueuedMessages.isEmpty(), "引导发送后队列应清空")

            // 等待异步写入完成并校验 MockAiCore 成功收到了引导消息
            kotlinx.coroutines.withTimeout(5_000) {
                while (true) {
                    val convSnapshot = mockAiCore.getSnapshot(conv.id).getOrThrow()
                    val hasGuidance = convSnapshot.messages.any { msg ->
                        msg.blocks.any { (it as? ChatBlock.Text)?.text?.contains("[Guidance]: Message 2") == true }
                    }
                    if (hasGuidance) break
                    kotlinx.coroutines.delay(50)
                }
            }
        } finally {
            testScope.cancel()
        }
    }

    @Test
    fun testAutoDrainQueuedMessagesWhenTurnCompletes() = kotlinx.coroutines.runBlocking {
        val testScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            val mockAiCore = xyz.mederi.core.mock.MockAiCore()
            mockAiCore.initialize()
            val prefs = xyz.mederi.core.contract.preferences.InMemoryPreferencesStore()
            val appState = xyz.mederi.ui.appstate.AppState(aiCore = mockAiCore, preferences = prefs, scope = testScope)
            appState.hydrate()
            appState.selectModel(appState.availableModels.value.first())
            val viewModel = WorkspaceViewModel(appState)

            val conv = mockAiCore.createConversation("proj_1", null).getOrThrow()
            appState.selectConversation(conv.id)
            kotlinx.coroutines.withTimeout(5_000) {
                while (viewModel.conversationId != conv.id) kotlinx.coroutines.delay(50)
            }

            // 入队排队消息
            viewModel.enqueueCurrentInput("Next queued task")
            assertEquals(1, viewModel.currentQueuedMessages.size)

            val applySnapshotMethod = viewModel::class.java.getDeclaredMethod(
                "applySnapshot",
                String::class.java,
                xyz.mederi.core.contract.dto.ConversationSnapshot::class.java
            ).apply { isAccessible = true }

            // 模拟之前状态为 Working
            val workingSnap = xyz.mederi.core.contract.dto.ConversationSnapshot(
                conversation = conv.copy(status = ConversationStatus.Working),
                messages = emptyList(),
                tokenUsage = xyz.mederi.core.contract.models.TokenUsage(0, 0, 0),
                cost = xyz.mederi.core.contract.models.CostSummary(0.0)
            )
            applySnapshotMethod.invoke(viewModel, conv.id, workingSnap)

            // 模拟转变为 Idle 状态（回合执行结束）
            val idleSnap = workingSnap.copy(
                conversation = conv.copy(status = ConversationStatus.Idle)
            )
            applySnapshotMethod.invoke(viewModel, conv.id, idleSnap)

            // 等待出队发送协程完成（delay 100ms）
            kotlinx.coroutines.delay(200L)

            // 队列应被自动消费出队
            assertTrue(viewModel.currentQueuedMessages.isEmpty(), "Turn 结束恢复 Idle 时应自动出队发送")
        } finally {
            testScope.cancel()
        }
    }

    @Test
    fun testSubagentPreparingState() {
        val call = ToolCallUi(
            id = "call_sub_1",
            name = "subagent",
            state = ToolCallState.Running(mapOf("action" to "SPAWN_RESEARCHER", "task" to "research tech")),
            target = "research tech"
        )
        val groups = xyz.mederi.ui.chat.groupToolCallsByAction(listOf(call))
        assertEquals(1, groups.size)
        assertEquals(xyz.mederi.ui.chat.ToolActionKind.SUBAGENT, groups.first().kind)

        val isGroupRunning = true
        val isSubagentPreparing = groups.first().kind == xyz.mederi.ui.chat.ToolActionKind.SUBAGENT && isGroupRunning
        val canExpand = !isSubagentPreparing
        assertFalse(canExpand, "子agent准备中状态不可展开")
    }

    @Test
    fun testPlanApprovalCardDisappearsWhenUserContinuesConversationBugVerification() {
        val planTitle = "目录去嵌套化：移动 mederi/ 到上层 + 脚本适配"
        val planId = "plan_7b7db61d"

        val userTurn1 = xyz.mederi.core.contract.models.ChatMessage(
            id = "msg_user_1",
            conversationId = "conv_test",
            role = xyz.mederi.core.contract.models.ChatRole.User,
            blocks = listOf(ChatBlock.Text(id = "b_u1", text = "我们需要搞一下目录迁移")),
            createdAt = 1000L,
            completedAt = 1000L,
            parentMessageId = null,
            model = null,
            agent = null,
            isStreaming = false
        )

        // 大模型工具调用参数只有 title 等，绝无 planId
        val createPlanToolCall = ChatBlock.ToolCall(
            id = "b_tool_1",
            name = "create_plan",
            state = ToolCallState.Completed(
                input = mapOf(
                    "title" to planTitle,
                    "summary" to "把内层 mederi 移到上层"
                ),
                output = "Plan was not approved and not voided; it remains PENDING_APPROVAL."
            )
        )
        val asstTurn1 = xyz.mederi.core.contract.models.ChatMessage(
            id = "msg_asst_1",
            conversationId = "conv_test",
            role = xyz.mederi.core.contract.models.ChatRole.Assistant,
            blocks = listOf(createPlanToolCall),
            createdAt = 2000L,
            completedAt = 3000L,
            parentMessageId = "msg_user_1",
            model = "deepseek",
            agent = null,
            isStreaming = false
        )

        val planRequest = xyz.mederi.core.contract.models.PlanApprovalRequest(
            id = planId,
            conversationId = "conv_test",
            planPath = "/path/to/plan.md",
            title = planTitle,
            summary = "把内层 mederi 移到上层",
            subtaskCount = 3,
            status = "PENDING"
        )

        // 状态 A：等待用户审批时
        val snapshotWaiting = xyz.mederi.core.contract.dto.ConversationSnapshot(
            conversation = xyz.mederi.core.contract.models.Conversation(
                id = "conv_test",
                projectId = "proj_1",
                title = "Test",
                status = xyz.mederi.core.contract.models.ConversationStatus.WaitingUser,
                createdAt = 1000L,
                updatedAt = 3000L
            ),
            messages = listOf(userTurn1, asstTurn1),
            tokenUsage = xyz.mederi.core.contract.models.TokenUsage(),
            cost = xyz.mederi.core.contract.models.CostSummary(),
            pendingPlanApproval = planRequest,
            planApprovals = listOf(planRequest)
        )

        val itemsBefore = xyz.mederi.ui.computeChatItems(
            msgs = listOf(userTurn1, asstTurn1),
            isWorking = false,
            snapshot = snapshotWaiting
        )
        val planCardBefore = itemsBefore.filterIsInstance<ChatListItem.PlanApproval>().firstOrNull()
        println("=== [DEBUG_LOG_PLAN_CARD] 状态A（刚生成，等待审批）===")
        println("items count: ${itemsBefore.size}")
        println("planCardBefore: $planCardBefore")
        assertNotNull(planCardBefore, "状态A下必须存在 PlanApproval 卡片")

        // 状态 B：用户继续对话（触发 SESSION_UPDATED -> pendingPlanApproval = null）
        val userTurn2 = xyz.mederi.core.contract.models.ChatMessage(
            id = "msg_user_2",
            conversationId = "conv_test",
            role = xyz.mederi.core.contract.models.ChatRole.User,
            blocks = listOf(ChatBlock.Text(id = "b_u2", text = "openchamber/ 和 .zcode/ 都需要取消跟踪，都得忽略掉")),
            createdAt = 4000L,
            completedAt = 4000L,
            parentMessageId = null,
            model = null,
            agent = null,
            isStreaming = false
        )

        val snapshotAfterUserReply = snapshotWaiting.copy(
            conversation = snapshotWaiting.conversation.copy(status = xyz.mederi.core.contract.models.ConversationStatus.Working),
            pendingPlanApproval = null,
            planApprovals = listOf(planRequest)
        )

        val itemsAfter = xyz.mederi.ui.computeChatItems(
            msgs = listOf(userTurn1, asstTurn1, userTurn2),
            isWorking = true,
            snapshot = snapshotAfterUserReply
        )
        val planCardAfter = itemsAfter.filterIsInstance<ChatListItem.PlanApproval>().firstOrNull()
        // 修复后，用户继续对话后卡片依然存在且匹配正确！
        assertNotNull(planCardAfter, "【修复验证】用户继续对话后 PlanApproval 卡片依然必须正常展示！")
        assertEquals(planId, planCardAfter.request.id)
        assertEquals(planTitle, planCardAfter.request.title)
    }

    @Test
    fun testPlanOverviewAndSubtaskSpecMapping() = kotlinx.coroutines.runBlocking {
        val testScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            val mockAiCore = xyz.mederi.core.mock.MockAiCore()
            mockAiCore.initialize()
            val prefs = xyz.mederi.core.contract.preferences.InMemoryPreferencesStore()
            val appState = xyz.mederi.ui.appstate.AppState(aiCore = mockAiCore, preferences = prefs, scope = testScope)
            appState.hydrate()
            val viewModel = xyz.mederi.ui.WorkspaceViewModel(appState)

            val conv = mockAiCore.createConversation("p1", null).getOrThrow()
            appState.selectConversation(conv.id)
        kotlinx.coroutines.withTimeout(5_000) {
            while (viewModel.conversationId != conv.id) kotlinx.coroutines.delay(50)
        }

        val plan1 = xyz.mederi.core.contract.models.PlanApprovalRequest(
            id = "plan_pending",
            conversationId = conv.id,
            planPath = "/mock/plans/plan_pending/plan.md",
            title = "待批准的重构计划",
            summary = "测试重构计划",
            status = "PENDING",
            subtasks = listOf(
                xyz.mederi.core.contract.models.PlanSubtaskItem(
                    index = 0,
                    name = "分析当前依赖",
                    status = "PENDING",
                    spec = "#### 执行步骤\n1. 扫描所有 gradle 依赖\n2. 输出依赖报告",
                    planDetail = "分析依赖"
                ),
                xyz.mederi.core.contract.models.PlanSubtaskItem(
                    index = 1,
                    name = "移除废弃模块",
                    status = "PENDING",
                    spec = null,
                    planDetail = "移除模块"
                )
            )
        )

        val plan2 = xyz.mederi.core.contract.models.PlanApprovalRequest(
            id = "plan_done",
            conversationId = conv.id,
            planPath = "/mock/plans-done/plan_done/plan.md",
            title = "已完成的迁移计划",
            summary = "测试已完成迁移",
            status = "COMPLETED",
            subtasks = listOf(
                xyz.mederi.core.contract.models.PlanSubtaskItem(
                    index = 0,
                    name = "迁移文件",
                    status = "COMPLETED",
                    spec = "#### 执行步骤\n1. 移动目录",
                    planDetail = "迁移完成"
                )
            )
        )

        val plan3 = xyz.mederi.core.contract.models.PlanApprovalRequest(
            id = "plan_voided",
            conversationId = conv.id,
            planPath = "/mock/plans-voided/plan_voided/plan.md",
            title = "已作废的历史计划",
            summary = "旧计划被作废",
            status = "VOIDED",
            subtasks = emptyList()
        )

        val snap = xyz.mederi.core.contract.dto.ConversationSnapshot(
            conversation = xyz.mederi.core.contract.models.Conversation(
                id = conv.id,
                projectId = "p1",
                title = "Overview Test",
                status = xyz.mederi.core.contract.models.ConversationStatus.Idle,
                createdAt = 1000L,
                updatedAt = 2000L
            ),
            messages = emptyList(),
            tokenUsage = xyz.mederi.core.contract.models.TokenUsage(),
            cost = xyz.mederi.core.contract.models.CostSummary(),
            pendingPlanApproval = plan1,
            planApprovals = listOf(plan1, plan2, plan3)
        )

        // 模拟 ViewModel 观察到该快照
        val applySnapshotMethod = viewModel::class.java.getDeclaredMethod(
            "applySnapshot",
            String::class.java,
            xyz.mederi.core.contract.dto.ConversationSnapshot::class.java
        ).apply { isAccessible = true }
        applySnapshotMethod.invoke(viewModel, conv.id, snap)

        val overviewList = viewModel.planOverviewList
        assertEquals(3, overviewList.size, "概览列表必须包含会话所有历史与活跃计划")

        val p1 = overviewList.find { it.id == "plan_pending" }
        assertNotNull(p1)
        assertEquals(xyz.mederi.ui.PlanOverviewStatus.PendingApproval, p1.status)
        assertEquals(2, p1.subtasks.size)
        assertEquals("分析当前依赖", p1.subtasks[0].name)
        assertNotNull(p1.subtasks[0].spec)
        assertNull(p1.subtasks[1].spec)

        val p2 = overviewList.find { it.id == "plan_done" }
        assertNotNull(p2)
        assertEquals(xyz.mederi.ui.PlanOverviewStatus.Completed, p2.status)

        val p3 = overviewList.find { it.id == "plan_voided" }
        assertNotNull(p3)
        assertEquals(xyz.mederi.ui.PlanOverviewStatus.Voided, p3.status)

        // 验证阅读子任务 Spec 打开右侧阅读面板
        viewModel.openSpecInExtension(
            planId = p1.id,
            planTitle = p1.title,
            subtaskIndex = p1.subtasks[0].index,
            subtaskName = p1.subtasks[0].name,
            specContent = p1.subtasks[0].spec
        )
        assertEquals(xyz.mederi.ui.RightDockPanel.PLAN, viewModel.activeDockPanel)
        assertNotNull(viewModel.currentPlan)
        assertTrue(viewModel.currentPlan!!.title.contains("步骤 1 Spec"))
        assertTrue(viewModel.currentPlan!!.content.contains("扫描所有 gradle 依赖"))
        } finally {
            testScope.cancel()
        }
    }

    /**
     * 回归测试：验证两阶段 Assistant 执行流中的自然时序保真（彻底杜绝时序颠倒）。
     * 阶段 1：提出计划文本 + create_plan + 计划审批卡片
     * 阶段 2：批准后文本 + generate_spec
     * 验证在执行中状态（isActiveAssistant = true）下：
     * text1 < planApproval < text2 < generate_spec 工具调用，时序绝对正向。
     */
    @Test
    fun testPlanApprovalTwoPhaseAssistantChronologicalOrder() = kotlinx.coroutines.runBlocking {
        val testScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            val mockAiCore = xyz.mederi.core.mock.MockAiCore()
            mockAiCore.initialize()
            val prefs = xyz.mederi.core.contract.preferences.InMemoryPreferencesStore()
            val appState = xyz.mederi.ui.appstate.AppState(
                aiCore = mockAiCore,
                preferences = prefs,
                scope = testScope
            )
            appState.hydrate()
            val viewModel = xyz.mederi.ui.WorkspaceViewModel(appState)

            val planReq = xyz.mederi.core.contract.models.PlanApprovalRequest(
                id = "plan_test_order",
                conversationId = "conv_order",
                planPath = "",
                title = "测试计划",
                summary = "计划摘要",
                status = "APPROVED"
            )

            val userMsg = xyz.mederi.core.contract.models.ChatMessage(
                id = "msg_user",
                conversationId = "conv_order",
                role = xyz.mederi.core.contract.models.ChatRole.User,
                blocks = listOf(xyz.mederi.core.contract.models.ChatBlock.Text("u1", "请制定计划")),
                createdAt = 1000L,
                completedAt = 1000L,
                parentMessageId = null,
                model = null,
                agent = null,
                isStreaming = false
            )

            // 阶段 1：思考 + 计划说明文本 + create_plan
            val asstMsg1 = xyz.mederi.core.contract.models.ChatMessage(
                id = "msg_asst_1",
                conversationId = "conv_order",
                role = xyz.mederi.core.contract.models.ChatRole.Assistant,
                blocks = listOf(
                    xyz.mederi.core.contract.models.ChatBlock.Reasoning("r1", "阶段1思考中"),
                    xyz.mederi.core.contract.models.ChatBlock.Text("t1", "下面是计划，请你批。"),
                    xyz.mederi.core.contract.models.ChatBlock.ToolCall(
                        id = "tc_plan",
                        name = "create_plan",
                        state = xyz.mederi.core.contract.models.ToolCallState.Completed(
                            input = mapOf("planId" to "plan_test_order", "title" to "测试计划"),
                            output = "Plan ID: plan_test_order"
                        )
                    )
                ),
                createdAt = 2000L,
                completedAt = 2500L,
                parentMessageId = null,
                model = null,
                agent = null,
                isStreaming = false
            )

            // 阶段 2：同 Turn 继续执行（isStreaming = true 代表处于活跃执行中）
            val asstMsg2 = xyz.mederi.core.contract.models.ChatMessage(
                id = "msg_asst_2",
                conversationId = "conv_order",
                role = xyz.mederi.core.contract.models.ChatRole.Assistant,
                blocks = listOf(
                    xyz.mederi.core.contract.models.ChatBlock.Reasoning("r2", "阶段2思考中"),
                    xyz.mederi.core.contract.models.ChatBlock.Text("t2", "批准了，先生成详细 spec。"),
                    xyz.mederi.core.contract.models.ChatBlock.ToolCall(
                        id = "tc_spec",
                        name = "generate_spec",
                        state = xyz.mederi.core.contract.models.ToolCallState.Running(
                            input = mapOf("planId" to "plan_test_order", "subtaskIndex" to "0")
                        )
                    )
                ),
                createdAt = 3000L,
                completedAt = null,
                parentMessageId = null,
                model = null,
                agent = null,
                isStreaming = true
            )

            val snap = xyz.mederi.core.contract.dto.ConversationSnapshot(
                conversation = xyz.mederi.core.contract.models.Conversation(
                    id = "conv_order",
                    projectId = "proj_test",
                    title = "时序测试",
                    status = xyz.mederi.core.contract.models.ConversationStatus.Working,
                    createdAt = 1000L,
                    updatedAt = 3000L
                ),
                messages = listOf(userMsg, asstMsg1, asstMsg2),
                tokenUsage = xyz.mederi.core.contract.models.TokenUsage(),
                cost = xyz.mederi.core.contract.models.CostSummary(),
                planApprovals = listOf(planReq)
            )

            val items = xyz.mederi.ui.computeChatItems(
                msgs = listOf(userMsg, asstMsg1, asstMsg2),
                isWorking = true,
                snapshot = snap
            )

            val t1Index = items.indexOfFirst { it is ChatListItem.TextMessage && it.text.contains("下面是计划") }
            val planCardIndex = items.indexOfFirst { it is ChatListItem.PlanApproval && it.request.id == "plan_test_order" }
            val t2Index = items.indexOfFirst { it is ChatListItem.TextMessage && it.text.contains("批准了") }
            val specToolIndex = items.indexOfFirst { it is ChatListItem.ToolCalls && it.toolCalls.any { tc -> tc.name == "generate_spec" } }

            assertTrue(t1Index >= 0, "应找到阶段 1 的文本条目")
            assertTrue(planCardIndex >= 0, "应找到计划审批卡片")
            assertTrue(t2Index >= 0, "应找到阶段 2 的文本条目")
            assertTrue(specToolIndex >= 0, "应找到阶段 2 的 generate_spec 工具条目")

            // 核心断言：时序必须绝对向前，不得发生新消息在旧计划上方的颠倒
            assertTrue(t1Index < planCardIndex, "阶段 1 文本应当在计划审批卡片上方 (t1=$t1Index, plan=$planCardIndex)")
            assertTrue(planCardIndex < t2Index, "计划审批卡片应当在阶段 2 文本上方 (plan=$planCardIndex, t2=$t2Index)")
            assertTrue(t2Index < specToolIndex, "阶段 2 文本应当在 generate_spec 工具上方 (t2=$t2Index, specTool=$specToolIndex)")
        } finally {
            testScope.cancel()
        }
    }

    /**
     * 回归测试：验证计划审批卡片状态门禁——仅 PENDING/PENDING_APPROVAL 状态允许点击批准，
     * 派发子 Agent 后（IN_PROGRESS）或完成（COMPLETED）绝不可重新激活批准按钮。
     */
    @Test
    fun testPlanApprovalCardStatusGate() {
        val pendingReq = xyz.mederi.core.contract.models.PlanApprovalRequest(
            id = "p1", conversationId = "c1", planPath = "", title = "P1", status = "PENDING"
        )
        val inProgressReq = pendingReq.copy(status = "IN_PROGRESS")
        val completedReq = pendingReq.copy(status = "COMPLETED")
        val approvedReq = pendingReq.copy(status = "APPROVED")

        fun isPlanPending(req: xyz.mederi.core.contract.models.PlanApprovalRequest): Boolean =
            req.status.equals("PENDING", ignoreCase = true) || req.status.equals("PENDING_APPROVAL", ignoreCase = true)

        assertTrue(isPlanPending(pendingReq), "PENDING 状态应当处于待审批态（可点击批准）")
        assertFalse(isPlanPending(inProgressReq), "IN_PROGRESS 状态（已派发子Agent）绝不可重新激活待审批态")
        assertFalse(isPlanPending(completedReq), "COMPLETED 状态绝不可重新激活待审批态")
        assertFalse(isPlanPending(approvedReq), "APPROVED 状态绝不可重新激活待审批态")
    }

    @Test
    fun testNonFinalMessagesFoldedIntoWorkTrace() {
        val convId = "conv_fold_test"
        val userMsg = xyz.mederi.core.contract.models.ChatMessage(
            id = "u_1",
            conversationId = convId,
            role = xyz.mederi.core.contract.models.ChatRole.User,
            blocks = listOf(xyz.mederi.core.contract.models.ChatBlock.Text("ut_1", "开始执行计划")),
            createdAt = 1000L,
            completedAt = 1000L,
            parentMessageId = null,
            model = null,
            agent = null,
            isStreaming = false,
        )

        // Assistant Turn 1: 生成 spec -> 派发子任务 0 -> 尾随过渡文本 "子任务 0 已派发，等待完成。"
        val asstTurn1 = xyz.mederi.core.contract.models.ChatMessage(
            id = "a_1",
            conversationId = convId,
            role = xyz.mederi.core.contract.models.ChatRole.Assistant,
            blocks = listOf(
                xyz.mederi.core.contract.models.ChatBlock.Reasoning("r_1", "准备派发子任务 0"),
                xyz.mederi.core.contract.models.ChatBlock.Text("t_pre_spec", "先生成子任务 0 的 spec："),
                xyz.mederi.core.contract.models.ChatBlock.ToolCall(
                    "tc_spec",
                    "generate_spec",
                    xyz.mederi.core.contract.models.ToolCallState.Completed(mapOf("subtaskIndex" to "0"), "ok"),
                ),
                xyz.mederi.core.contract.models.ChatBlock.ToolCall(
                    "tc_spawn",
                    "subagent",
                    xyz.mederi.core.contract.models.ToolCallState.Completed(
                        mapOf("action" to "SPAWN", "subtaskIndex" to "0"),
                        "Subagent spawned: agent_0",
                    ),
                ),
                xyz.mederi.core.contract.models.ChatBlock.Text("t_handoff_0", "子任务 0 已派发，等待完成。"),
            ),
            createdAt = 2000L,
            completedAt = 3000L,
            parentMessageId = "u_1",
            model = "glm-5.1",
            agent = null,
            isStreaming = false,
        )

        // Event message: 子任务 0 完成唤醒
        val eventMsg = xyz.mederi.core.contract.models.ChatMessage(
            id = "ev_1",
            conversationId = convId,
            role = xyz.mederi.core.contract.models.ChatRole.User,
            blocks = listOf(
                xyz.mederi.core.contract.models.ChatBlock.Text(
                    "evt_1",
                    """<event_message type="subagent" agentId="agent_0" status="COMPLETED">
Role: EXECUTOR
Subtask: 0
Summary: 子任务 0 执行完毕
</event_message>""",
                )
            ),
            createdAt = 4000L,
            completedAt = 4000L,
            parentMessageId = null,
            model = null,
            agent = null,
            isStreaming = false,
        )

        // Assistant Turn 2 (最终轮次): 工具前碎碎念 "验证：" -> verify_subtask -> 工具前碎碎念 "记录完成：" -> write_log -> 最终回复
        val asstTurn2 = xyz.mederi.core.contract.models.ChatMessage(
            id = "a_2",
            conversationId = convId,
            role = xyz.mederi.core.contract.models.ChatRole.Assistant,
            blocks = listOf(
                xyz.mederi.core.contract.models.ChatBlock.Reasoning("r_2", "开始验证子任务 0"),
                xyz.mederi.core.contract.models.ChatBlock.Text("t_pre_verify", "验证："),
                xyz.mederi.core.contract.models.ChatBlock.ToolCall(
                    "tc_verify",
                    "verify_subtask",
                    xyz.mederi.core.contract.models.ToolCallState.Completed(mapOf("subtaskIndex" to "0"), "PASS"),
                ),
                xyz.mederi.core.contract.models.ChatBlock.Reasoning("r_3", "验证通过，写日志"),
                xyz.mederi.core.contract.models.ChatBlock.Text("t_pre_log", "记录完成："),
                xyz.mederi.core.contract.models.ChatBlock.ToolCall(
                    "tc_log",
                    "write_log",
                    xyz.mederi.core.contract.models.ToolCallState.Completed(mapOf("action" to "complete"), "ok"),
                ),
                xyz.mederi.core.contract.models.ChatBlock.Reasoning("r_4", "汇总最终结果"),
                xyz.mederi.core.contract.models.ChatBlock.Text("t_final", "完成。所有子任务均已验证通过！"),
            ),
            createdAt = 5000L,
            completedAt = 6000L,
            parentMessageId = "ev_1",
            model = "glm-5.1",
            agent = null,
            isStreaming = false,
        )

        val items = xyz.mederi.ui.computeChatItems(
            msgs = listOf(userMsg, asstTurn1, eventMsg, asstTurn2),
            isWorking = false,
            snapshot = null,
        )

        // 1. 顶层 AI TextMessage 只能有最后一条最终回复（"完成。所有子任务均已验证通过！"）
        val topLevelAssistantTexts = items.filterIsInstance<ChatListItem.TextMessage>().filter { !it.isUser }
        assertEquals(
            listOf("完成。所有子任务均已验证通过！"),
            topLevelAssistantTexts.map { it.text },
            "顶层对话流应仅保留整个用户周期的最后一条最终回复，其余中间碎碎念全部折叠进 WorkTraceBlock"
        )

        // 2. 第一轮（中间轮次）的 WorkTraceBlock 应按时序包含工具前过渡语和派发后等待语
        val workTraces = items.filterIsInstance<ChatListItem.WorkTraceBlock>()
        assertEquals(2, workTraces.size)
        val wt1Texts = workTraces[0].items.filterIsInstance<ChatListItem.TextMessage>().map { it.text }
        assertEquals(
            listOf("先生成子任务 0 的 spec：", "子任务 0 已派发，等待完成。"),
            wt1Texts,
            "中间轮次的所有过渡消息（含子代理派发尾随语）均应按时序收起进 WorkTraceBlock"
        )
        assertEquals(
            listOf("Reasoning", "TextMessage", "ToolCalls", "SubagentCalls", "TextMessage"),
            workTraces[0].items.map { it::class.simpleName },
            "第一轮 WorkTraceBlock 内部子项必须严格保持原始发生时序"
        )

        // 3. 第二轮（最终轮次）的 WorkTraceBlock 应按时序包含工具前过渡语（"验证："、"记录完成："），不含最终回复
        val wt2Texts = workTraces[1].items.filterIsInstance<ChatListItem.TextMessage>().map { it.text }
        assertEquals(
            listOf("验证：", "记录完成："),
            wt2Texts,
            "最终轮次中穿插在工具调用前的过渡语应按时序收起进 WorkTraceBlock"
        )
        assertEquals(
            listOf("Reasoning", "TextMessage", "ToolCalls", "Reasoning", "TextMessage", "ToolCalls", "Reasoning"),
            workTraces[1].items.map { it::class.simpleName },
            "第二轮 WorkTraceBlock 内部子项必须严格保持原始发生时序"
        )
    }
}
