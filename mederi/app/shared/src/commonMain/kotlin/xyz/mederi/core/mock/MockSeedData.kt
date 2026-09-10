package xyz.mederi.core.mock

import xyz.mederi.core.contract.dto.ConversationSnapshot
import xyz.mederi.core.contract.models.*
import xyz.mederi.currentTimeMillis

object MockSeedData {
    val builtinPresets = listOf("Google Gemini", "Agnes SG", "Agnes CN", "Hetzner", "商汤 SenseNova")

    val projects = listOf(
        Project(
            id = "proj_1",
            name = "mederi-dev",
            directories = listOf("/Users/mock/projects/mederi-dev"),
            conversations = listOf(
                Conversation(id = "conv_101", projectId = "proj_1", title = "重构对话引擎", status = ConversationStatus.Idle, createdAt = currentTimeMillis() - 100000, updatedAt = currentTimeMillis() - 50000),
                Conversation(id = "conv_102", projectId = "proj_1", title = "新增 diff 功能", status = ConversationStatus.Idle, createdAt = currentTimeMillis() - 80000, updatedAt = currentTimeMillis() - 60000),
            )
        ),
        Project(
            id = "proj_2",
            name = "side-project",
            directories = listOf("/Users/mock/projects/side-project"),
            conversations = listOf(
                Conversation(id = "conv_103", projectId = "proj_2", title = "整理 notes", status = ConversationStatus.Idle, createdAt = currentTimeMillis() - 70000, updatedAt = currentTimeMillis() - 70000),
            )
        ),
        Project(
            id = "proj_3",
            name = "notes",
            directories = listOf("/Users/mock/projects/notes"),
            conversations = emptyList()
        ),
    )

    val conv101Snapshot = ConversationSnapshot(
        conversation = projects[0].conversations[0],
        messages = listOf(
            ChatMessage(id = "m1", conversationId = "conv_101", role = ChatRole.User, blocks = listOf(ChatBlock.Text("1", "看看现在的 SessionEventProcessor 是怎么合并消息的")), createdAt = currentTimeMillis() - 100000, completedAt = currentTimeMillis() - 100000, parentMessageId = null, model = null, agent = null),
            ChatMessage(id = "m2", conversationId = "conv_101", role = ChatRole.Assistant, blocks = listOf(
                ChatBlock.Reasoning("r1", "这个类做了很多工作，包括合并消息、处理流式数据等。"),
                ChatBlock.ToolCall("t1", "read", ToolCallState.Completed(mapOf("path" to "SessionEventProcessor.kt"), "fun mergeMessages() {...}")),
                ChatBlock.Text("3", "目前的 merge 逻辑是：1. 过滤本地消息 2. 合并服务端消息 3. 按时间排序。"),
            ), createdAt = currentTimeMillis() - 95000, completedAt = currentTimeMillis() - 90000, parentMessageId = "m1", model = "claude-sonnet-4", agent = "build"),
            ChatMessage(id = "m3", conversationId = "conv_101", role = ChatRole.User, blocks = listOf(ChatBlock.Text("4", "我觉得这块应该按官方 webui 重写")), createdAt = currentTimeMillis() - 55000, completedAt = currentTimeMillis() - 55000, parentMessageId = null, model = null, agent = null),
            ChatMessage(id = "m4", conversationId = "conv_101", role = ChatRole.Assistant, blocks = listOf(
                ChatBlock.Reasoning("r2", "你说得对，官方 webui 的实现更清晰。"),
                ChatBlock.Text("5", "分三步：1. 定义统一的数据模型 2. 用 Flow 处理事件 3. 用快照替代增量合并。"),
            ), createdAt = currentTimeMillis() - 50000, completedAt = currentTimeMillis() - 50000, parentMessageId = "m3", model = "claude-sonnet-4", agent = "build"),
        ),
        tokenUsage = TokenUsage(input = 150, output = 80, reasoning = 200),
        cost = CostSummary(total = 0.0012),
        childConversations = listOf(
            Conversation(id = "conv_101_sub_1", projectId = "proj_1", title = "探索 module-kt 包结构", status = ConversationStatus.Idle, createdAt = currentTimeMillis() - 90000, updatedAt = currentTimeMillis() - 85000, parentConversationId = "conv_101"),
        )
    )

    val subConv101Snapshot = ConversationSnapshot(
        conversation = projects[0].conversations[0].copy(id = "conv_101_sub_1"),
        messages = listOf(
            ChatMessage(id = "m5", conversationId = "conv_101_sub_1", role = ChatRole.User, blocks = listOf(ChatBlock.Text("6", "看看 module-kt 包有哪些文件")), createdAt = currentTimeMillis() - 90000, completedAt = currentTimeMillis() - 90000, parentMessageId = null, model = null, agent = null),
            ChatMessage(id = "m6", conversationId = "conv_101_sub_1", role = ChatRole.Assistant, blocks = listOf(
                ChatBlock.Text("7", "module-kt 包包含：ChatModels.kt, ProjectModels.kt 等。"),
            ), createdAt = currentTimeMillis() - 85000, completedAt = currentTimeMillis() - 85000, parentMessageId = "m5", model = "claude-sonnet-4", agent = "explore"),
        ),
        tokenUsage = TokenUsage(input = 50, output = 30, reasoning = 20),
        cost = CostSummary(total = 0.0002),
    )

    val convErrorSnapshot = ConversationSnapshot(
        conversation = Conversation(id = "conv_error", projectId = "proj_1", title = "错误测试", status = ConversationStatus.Error, createdAt = currentTimeMillis(), updatedAt = currentTimeMillis()),
        messages = emptyList(),
        tokenUsage = TokenUsage(),
        cost = CostSummary(),
    )

    val models = listOf(
        ModelOption(id = "claude-sonnet-4", name = "Claude Sonnet 4", provider = "anthropic", supportsThinking = true, reasoningLevels = listOf("LOW", "MEDIUM", "HIGH")),
        ModelOption(id = "gpt-5", name = "GPT-5", provider = "openai", supportsThinking = false),
        ModelOption(id = "gemini-2.5-pro", name = "Gemini 2.5 Pro", provider = "google", supportsThinking = true, reasoningLevels = listOf("LOW", "HIGH")),
        ModelOption(id = "qwen-3-coder", name = "Qwen 3 Coder", provider = "custom_local", supportsThinking = false),
    )

    val agents = listOf(
        AgentOption(id = "autonomous-code", name = "自主 · 编程", description = "AI 自主判断直接执行，适合写代码、调试", mode = AgentMode.AUTONOMOUS, workType = WorkType.CODE),
        AgentOption(id = "approval-code", name = "审批 · 编程", description = "先列计划等批准后再执行", mode = AgentMode.APPROVAL, workType = WorkType.CODE),
        AgentOption(id = "autonomous-work", name = "自主 · 通用", description = "AI 自主执行，适合文档处理、协助创作", mode = AgentMode.AUTONOMOUS, workType = WorkType.WORK),
        AgentOption(id = "approval-work", name = "审批 · 通用", description = "先列计划等批准后再执行", mode = AgentMode.APPROVAL, workType = WorkType.WORK),
    )

    val providers = listOf(
        ProviderConfig(id = "anthropic", name = "Anthropic", type = ProviderType.Builtin, baseUrl = null, isConnected = true, models = listOf(models[0]), supportsApiKey = true, supportsBaseUrl = false, protocolType = ProtocolType.OPENAI_CHAT),
        ProviderConfig(id = "openai", name = "OpenAI", type = ProviderType.Builtin, baseUrl = null, isConnected = true, models = listOf(models[1]), supportsApiKey = true, supportsBaseUrl = false, protocolType = ProtocolType.OPENAI_CHAT),
        ProviderConfig(id = "google", name = "Google", type = ProviderType.Builtin, baseUrl = null, isConnected = false, models = listOf(models[2]), supportsApiKey = true, supportsBaseUrl = false, protocolType = ProtocolType.GOOGLE),
        ProviderConfig(id = "custom_local", name = "Local LLM", type = ProviderType.Custom, baseUrl = "http://localhost:11434", isConnected = true, models = listOf(models[3]), customModels = listOf(CustomModelEntry(id = "qwen-3-coder", name = "Qwen 3 Coder")), supportsApiKey = false, supportsBaseUrl = false, protocolType = ProtocolType.OPENAI_CHAT),
    )

    val compactionConfig = CompactionConfig(auto = true, tailTurns = 4, preserveRecentTokens = 4096, reserved = 2048, prune = true)
}
