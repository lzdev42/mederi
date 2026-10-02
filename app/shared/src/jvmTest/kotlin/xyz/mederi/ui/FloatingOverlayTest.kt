package xyz.mederi.ui

import xyz.mederi.core.contract.models.ChatBlock
import xyz.mederi.core.contract.models.ChatMessage
import xyz.mederi.core.contract.models.ChatRole
import xyz.mederi.core.contract.models.ToolCallState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FloatingOverlayTest {

    @Test
    fun testSubagentCallsExtractedFromToolCall() {
        val toolCallMsg = ChatMessage(
            id = "msg_sub_spawn",
            conversationId = "conv_test",
            role = ChatRole.Assistant,
            blocks = listOf(
                ChatBlock.ToolCall(
                    id = "tc_1",
                    name = "subagent",
                    state = ToolCallState.Completed(
                        input = mapOf("action" to "SPAWN", "role" to "EXECUTOR", "task" to "执行 ST0"),
                        output = """{"agentId":"agent_123"}"""
                    )
                )
            ),
            createdAt = 1000L,
            completedAt = 1000L,
            parentMessageId = null,
            model = null,
            agent = null
        )

        val items = computeChatItems(
            msgs = listOf(toolCallMsg),
            isWorking = false,
            snapshot = null
        )

        val allSubagentCalls = items.flatMap {
            when (it) {
                is ChatListItem.SubagentCalls -> listOf(it)
                is ChatListItem.WorkTraceBlock -> it.items.filterIsInstance<ChatListItem.SubagentCalls>()
                else -> emptyList()
            }
        }
        val subagentItem = allSubagentCalls.firstOrNull()
        assertNotNull(subagentItem, "应当解析出 SubagentCalls")
        assertEquals(1, subagentItem.subagents.size)
        assertEquals("subagent", subagentItem.subagents[0].name)
        val input = (subagentItem.subagents[0].state as ToolCallState.Completed).input
        assertEquals("SPAWN", input["action"])
        assertEquals("EXECUTOR", input["role"])
    }

    @Test
    fun testSpawnResearcherToolCallParsedAsSubagentCalls() {
        val toolCallMsg = ChatMessage(
            id = "msg_researcher",
            conversationId = "conv_test",
            role = ChatRole.Assistant,
            blocks = listOf(
                ChatBlock.ToolCall(
                    id = "tc_researcher",
                    name = "subagent",
                    state = ToolCallState.Completed(
                        input = mapOf(
                            "action" to "SPAWN_RESEARCHER",
                            "task" to "为「对话中文档点击事件分级处理」做调研。需求: 点击对话里引用到的文件时，根据情况分..."
                        ),
                        output = """{"agentId":"agent_456","status":"RUNNING"}"""
                    )
                )
            ),
            createdAt = 1000L,
            completedAt = 1000L,
            parentMessageId = null,
            model = null,
            agent = null
        )

        val items = computeChatItems(
            msgs = listOf(toolCallMsg),
            isWorking = false,
            snapshot = null
        )

        val subagentItem = items.filterIsInstance<ChatListItem.SubagentCalls>().firstOrNull()
            ?: (items.filterIsInstance<ChatListItem.WorkTraceBlock>().firstOrNull()?.items?.filterIsInstance<ChatListItem.SubagentCalls>()?.firstOrNull())
        assertNotNull(subagentItem)
        assertEquals(1, subagentItem.subagents.size)
        assertEquals("subagent", subagentItem.subagents[0].name)
        val input = (subagentItem.subagents[0].state as ToolCallState.Completed).input
        assertEquals("SPAWN_RESEARCHER", input["action"])
    }

    @Test
    fun testHasSpecificRole() {
        assertTrue(!xyz.mederi.ui.components.hasSpecificRole(""))
        assertTrue(!xyz.mederi.ui.components.hasSpecificRole("  "))
        assertTrue(!xyz.mederi.ui.components.hasSpecificRole("generic"))
        assertTrue(!xyz.mederi.ui.components.hasSpecificRole("subagent"))
        assertTrue(xyz.mederi.ui.components.hasSpecificRole("RESEARCHER"))
        assertTrue(xyz.mederi.ui.components.hasSpecificRole("EXECUTOR"))
        assertTrue(xyz.mederi.ui.components.hasSpecificRole("BROWSER_OPERATOR"))
    }

    @Test
    fun testLifecycleStatusChangeNotificationTransitions() {
        println("=== [DEBUG LOG] 验证生命周期通知状态流转 ===")
        val agentId = "agent_research_123"
        val convId = "conv_1"

        // 1. 模拟收到 SUBAGENT_STARTED 事件
        val startEvent = xyz.mederi.core.contract.models.CoreEvent(
            type = xyz.mederi.core.contract.models.CoreEventType.SUBAGENT_STARTED,
            sessionId = convId,
            timestamp = "1000",
            payload = mapOf(
                "agentId" to agentId,
                "role" to "RESEARCHER",
                "task" to "调研文档点击事件"
            )
        )
        var states = xyz.mederi.core.contract.SubagentTracker.apply(emptyMap(), startEvent)
        var subagent = states[agentId]
        assertNotNull(subagent)
        assertEquals("RUNNING", subagent.status)

        val runningSubagent = subagent
        val runningKey = "${agentId}_${runningSubagent.status}"
        println("阶段 1 - 派发运行中: key=$runningKey, status=${runningSubagent.status}, role=${runningSubagent.role}")
        assertEquals("agent_research_123_RUNNING", runningKey)

        // 阶段 1 验证：此时未关闭，浮动条显示派发运行态
        val notif1 = deriveActiveSubagentNotification(listOf(runningSubagent), emptySet())
        assertNotNull(notif1)
        assertEquals(runningKey, notif1.notificationKey)
        assertTrue(notif1.isRunning)

        // 假设用户点击 X 关闭了 RUNNING 通知的 key
        val dismissedKeys = mutableSetOf(runningKey)
        println("用户点击 X 关闭了派发态通知: dismissedKeys=$dismissedKeys")
        val notifDismissed = deriveActiveSubagentNotification(listOf(runningSubagent), dismissedKeys)
        assertEquals(null, notifDismissed, "RUNNING 态已被用户关闭，返回 null")

        // 2. 模拟后台执行完成，收到 SUBAGENT_COMPLETED 事件
        val completeEvent = xyz.mederi.core.contract.models.CoreEvent(
            type = xyz.mederi.core.contract.models.CoreEventType.SUBAGENT_COMPLETED,
            sessionId = convId,
            timestamp = "2000",
            payload = mapOf(
                "agentId" to agentId,
                "status" to "COMPLETED"
            )
        )
        states = xyz.mederi.core.contract.SubagentTracker.apply(states, completeEvent)
        val completedSubagent = states[agentId]
        assertNotNull(completedSubagent)
        assertEquals("COMPLETED", completedSubagent.status)

        val completedKey = "${agentId}_${completedSubagent.status}"
        println("阶段 2 - 后台任务执行完成: key=$completedKey, status=${completedSubagent.status}")
        assertEquals("agent_research_123_COMPLETED", completedKey)

        // 核心验证：尽管 dismissedKeys 包含之前的 runningKey，但由于状态变为 COMPLETED 生成了 completedKey，通知条重新亮起！
        val notifCompleted = deriveActiveSubagentNotification(listOf(completedSubagent), dismissedKeys)
        assertNotNull(notifCompleted, "COMPLETED 态应当重新产生通知")
        assertEquals(completedKey, notifCompleted.notificationKey)
        assertTrue(notifCompleted.isCompleted)
        println("验证成功: 新的 COMPLETED 状态未被关闭，浮动通知条将向用户展示‘调研子智能体任务已完成’！")
        println("=== [DEBUG LOG 结束] ===")
    }

    @Test
    fun testMultipleSubagentsNotificationStacking() {
        println("=== [DEBUG LOG] 验证多子智能体创建任务时的通知重叠/覆盖问题 ===")
        val convId = "conv_stack"
        
        // 创建 3 个子任务
        val subagent1 = xyz.mederi.core.contract.models.SubagentState(
            agentId = "sub_01",
            parentSessionId = convId,
            role = "RESEARCHER",
            modelId = "gemini-flash",
            modelName = "Gemini Flash",
            reasoningLevel = null,
            task = "调研任务 1",
            briefing = null,
            status = "RUNNING",
            startedAt = "1000"
        )
        val subagent2 = xyz.mederi.core.contract.models.SubagentState(
            agentId = "sub_02",
            parentSessionId = convId,
            role = "EXECUTOR",
            modelId = "gemini-flash",
            modelName = "Gemini Flash",
            reasoningLevel = null,
            task = "执行任务 2",
            briefing = null,
            status = "RUNNING",
            startedAt = "2000"
        )
        val subagent3 = xyz.mederi.core.contract.models.SubagentState(
            agentId = "sub_03",
            parentSessionId = convId,
            role = "EXECUTOR",
            modelId = "gemini-flash",
            modelName = "Gemini Flash",
            reasoningLevel = null,
            task = "执行任务 3",
            briefing = null,
            status = "RUNNING",
            startedAt = "3000"
        )
        
        val subagents = listOf(subagent1, subagent2, subagent3)
        println("当前运行中的子任务列表大小: ${subagents.size}")
        subagents.forEachIndexed { idx, s ->
            println("  子任务[${idx + 1}]: id=${s.agentId}, role=${s.role}, status=${s.status}")
        }

        // 调用列表堆叠推导 deriveActiveSubagentNotifications
        val stackedNotifs = deriveActiveSubagentNotifications(subagents, emptySet())
        println("积木堆叠列表 deriveActiveSubagentNotifications 结果: size=${stackedNotifs.size}")
        stackedNotifs.forEachIndexed { i, n ->
            println("  积木[${i + 1}]: key=${n.notificationKey}, agentId=${n.agentId}, role=${n.role}, task=${n.task}")
        }
        assertEquals(3, stackedNotifs.size, "3 个子任务应该堆叠生成 3 条积木通知")
        assertEquals("sub_01", stackedNotifs[0].agentId)
        assertEquals("sub_02", stackedNotifs[1].agentId)
        assertEquals("sub_03", stackedNotifs[2].agentId)

        // 模拟用户点击 X 关闭了子任务 2 的通知
        val dismissedKeys = setOf("sub_02_RUNNING")
        val afterDismissNotifs = deriveActiveSubagentNotifications(subagents, dismissedKeys)
        assertEquals(2, afterDismissNotifs.size, "关闭子任务 2 后，其余 2 个积木依然保留")
        assertEquals(listOf("sub_01", "sub_03"), afterDismissNotifs.map { it.agentId })

        // 模拟子任务 1 完成（状态变为 COMPLETED）
        val subagent1Completed = subagent1.copy(status = "COMPLETED")
        val updatedSubagents = listOf(subagent1Completed, subagent2, subagent3)
        val afterUpdateNotifs = deriveActiveSubagentNotifications(updatedSubagents, dismissedKeys)
        assertEquals(2, afterUpdateNotifs.size)
        val notif1 = afterUpdateNotifs.find { it.agentId == "sub_01" }
        assertNotNull(notif1)
        assertEquals("sub_01_COMPLETED", notif1.notificationKey)
        assertTrue(notif1.isCompleted, "子任务 1 状态变为 COMPLETED 后正常唤起完成态积木")
        println("=== [DEBUG LOG 积木堆叠验证成功] ===")
    }

    @Test
    fun testSimplifiedSubagentRunningBannerLifecycle() {
        println("=== [DEBUG LOG] 验证精简后的单一子智能体工作浮条生命周期 ===")
        val convId = "conv_simplified"

        // 阶段 1：初始无子智能体
        val emptyList = emptyList<xyz.mederi.core.contract.models.SubagentState>()
        var isDismissed = false
        val initialShow = shouldShowSubagentRunningBanner(emptyList, isDismissed)
        println("阶段 1 - 无子智能体: hasRunning=${hasRunningSubagents(emptyList)}, isDismissed=$isDismissed, show=$initialShow")
        assertTrue(!initialShow, "无子智能体时不应显示")

        // 阶段 2：启动两个子智能体（模拟并行执行，不再叠罗汉，仅显示单一浮条）
        val subagent1 = xyz.mederi.core.contract.models.SubagentState(
            agentId = "sub_01",
            parentSessionId = convId,
            role = "RESEARCHER",
            modelId = "gemini-flash",
            modelName = "Gemini Flash",
            reasoningLevel = null,
            task = "调研文档点击事件",
            briefing = null,
            status = "RUNNING",
            startedAt = "1000"
        )
        val subagent2 = xyz.mederi.core.contract.models.SubagentState(
            agentId = "sub_02",
            parentSessionId = convId,
            role = "EXECUTOR",
            modelId = "gemini-flash",
            modelName = "Gemini Flash",
            reasoningLevel = null,
            task = "执行 ST0",
            briefing = null,
            status = "RUNNING",
            startedAt = "2000"
        )
        var currentSubagents = listOf(subagent1, subagent2)
        val runningShow = shouldShowSubagentRunningBanner(currentSubagents, isDismissed)
        println("阶段 2 - 多个子智能体同时 RUNNING: runningCount=${currentSubagents.count { it.status == "RUNNING" }}, isDismissed=$isDismissed, show=$runningShow")
        assertTrue(runningShow, "有子智能体处于 RUNNING 状态时应当显示单一工作浮条")

        // 阶段 3：用户手动点击 ✕ 关闭浮条
        isDismissed = true
        val dismissedShow = shouldShowSubagentRunningBanner(currentSubagents, isDismissed)
        println("阶段 3 - 用户点击 X 关闭: isDismissed=$isDismissed, show=$dismissedShow")
        assertTrue(!dismissedShow, "用户手动关闭后，浮条应当隐藏")

        // 阶段 4：所有子智能体工作结束（变成 COMPLETED）
        currentSubagents = listOf(
            subagent1.copy(status = "COMPLETED"),
            subagent2.copy(status = "COMPLETED")
        )
        val hasRunningAfterComplete = hasRunningSubagents(currentSubagents)
        // 没有子智能体在工作时，自动隐藏，并自动重置 isDismissed 为 false
        if (!hasRunningAfterComplete) {
            isDismissed = false
        }
        val completedShow = shouldShowSubagentRunningBanner(currentSubagents, isDismissed)
        println("阶段 4 - 全部子智能体完成: hasRunning=$hasRunningAfterComplete, isDismissed(已自动复位)=$isDismissed, show=$completedShow")
        assertTrue(!completedShow, "没有子智能体工作时，浮条自动关闭")
        assertTrue(!isDismissed, "子智能体全部停止工作后，dismiss 状态应当自动复位为 false")

        // 阶段 5：后续新子智能体启动（状态为 RUNNING）
        val subagent3 = xyz.mederi.core.contract.models.SubagentState(
            agentId = "sub_03",
            parentSessionId = convId,
            role = "EXECUTOR",
            modelId = "gemini-flash",
            modelName = "Gemini Flash",
            reasoningLevel = null,
            task = "执行新任务",
            briefing = null,
            status = "RUNNING",
            startedAt = "3000"
        )
        currentSubagents = currentSubagents + subagent3
        val nextRunningShow = shouldShowSubagentRunningBanner(currentSubagents, isDismissed)
        println("阶段 5 - 新子智能体启动: hasRunning=${hasRunningSubagents(currentSubagents)}, isDismissed=$isDismissed, show=$nextRunningShow")
        assertTrue(nextRunningShow, "新子任务启动时，浮条应能够再次自动显示")
        println("=== [DEBUG LOG 精简单一工作浮条生命周期验证通过] ===")
    }
}

