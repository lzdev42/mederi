package xyz.mederi.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.mederi.domain.model.EventType
import xyz.mederi.domain.model.MederiEvent
import xyz.mederi.domain.model.Message
import xyz.mederi.domain.model.MessageRole
import xyz.mederi.domain.model.TodoItem
import xyz.mederi.domain.model.TodoStatus
import xyz.mederi.domain.model.encodeTodos
import xyz.mederi.infrastructure.koog.aiViewContextUsedTokens
import xyz.mederi.store.HistoryStore
import xyz.mederi.store.SessionStore
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Agent 自管理工具集。
 *
 * 包含轻量 todo 跟踪、上下文余量查询、开新上下文窗口、当前时间、ask_user。
 * 这些工具不涉及文件系统操作，不需要目录限制。
 *
 * @param sessionId 当前 Session ID。
 * @param historyStore 对话历史存储（get_context_remaining 读取、new_context_window 清空）。
 * @param sessionStore 会话存储（update_todo 把 todo 列表持久化到 sessions.todos 列——唯一真理源）；null 时 todo 工具不可用。
 * @param eventBus 事件总线（update_todo 发 TODO_UPDATED；ask_user 发 QUESTION_REQUESTED）。
 * @param modelContextWindow 模型上下文窗口大小（token 数），用于计算剩余量。
 * @param newContextWindowFlag 开新上下文窗口请求标志（execute 只置标志，TurnExecutor 在 agent.run 结束后消费）。
 * @param questionRequester 问题请求器（ask_user 工具需要）。
 */
class AgentTools(
    private val sessionId: String,
    private val historyStore: HistoryStore,
    private val sessionStore: SessionStore?,
    private val eventBus: MutableSharedFlow<MederiEvent>,
    private val modelContextWindow: Int?,
    private val newContextWindowFlag: AtomicBoolean,
    private val questionRequester: xyz.mederi.question.QuestionRequester? = null
) {

    // ==================== update_todo ====================

    @Serializable
    data class TodoItemArg(
        @LLMDescription("Todo content: one concrete, actionable step.")
        val content: String,
        @LLMDescription("Status: pending, in_progress, completed, or cancelled.")
        val status: TodoStatus
    )

    @Serializable
    data class UpdateTodoArgs(
        @LLMDescription("Optional one-line explanation for this todo update.")
        val explanation: String? = null,
        @LLMDescription("The FULL todo list. One call REPLACES the entire list. An empty list clears it.")
        val todos: List<TodoItemArg>
    )

    inner class UpdateTodoTool : SimpleTool<UpdateTodoArgs>(
        argsType = typeToken<UpdateTodoArgs>(),
        name = "update_todo",
        description = "Replace the entire current todo list. One call sets the whole list (passing an empty " +
            "list clears it); at most one item may be in_progress."
    ) {
        override suspend fun execute(args: UpdateTodoArgs): String {
            if (sessionStore == null) {
                return "Error: todo persistence is not available in this context."
            }
            // 聚合校验：一轮列出全部问题（逐条报错实测爬不完）
            val errors = mutableListOf<String>()
            args.todos.forEachIndexed { i, item ->
                if (item.content.isBlank()) errors.add("Todo $i: content must not be blank.")
                if (item.status == TodoStatus.FAILED)
                    errors.add("Todo $i: status 'failed' is reserved for plan verification results — " +
                        "use pending / in_progress / completed / cancelled.")
            }
            val inProgressCount = args.todos.count { it.status == TodoStatus.IN_PROGRESS }
            if (inProgressCount > 1)
                errors.add("At most ONE todo may be in_progress (got $inProgressCount).")
            if (errors.isNotEmpty())
                return "Error: todo validation failed — fix ALL of the following, then retry once:\n" +
                    errors.mapIndexed { i, e -> "${i + 1}. $e" }.joinToString("\n")

            // 唯一真理源落库；事件与提示词挂载都只是这份持久化状态的投影
            val items = args.todos.map { TodoItem(content = it.content, status = it.status) }
            sessionStore.updateTodos(sessionId, items)

            eventBus.emit(
                MederiEvent(
                    type = EventType.TODO_UPDATED,
                    sessionId = sessionId,
                    payload = buildMap {
                        put("todos", items.encodeTodos())
                        args.explanation?.takeIf { it.isNotBlank() }?.let { put("explanation", it) }
                    },
                    timestamp = Instant.now().toString()
                )
            )

            return if (items.isEmpty()) {
                "Todo list cleared."
            } else {
                val done = items.count { it.status == TodoStatus.COMPLETED }
                "Todo list updated (${items.size} items, $done completed)."
            }
        }
    }

    // ==================== get_context_remaining ====================

    @Serializable
    data class GetContextRemainingArgs(
        @LLMDescription("Leave empty; reserved for future use.")
        val placeholder: String = ""
    )

    inner class GetContextRemainingTool : SimpleTool<GetContextRemainingArgs>(
        argsType = typeToken<GetContextRemainingArgs>(),
        name = "get_context_remaining",
        description = "Get the remaining tokens in the current context window."
    ) {
        override suspend fun execute(args: GetContextRemainingArgs): String {
            // 与 AI 实际收到的视图一致（压缩判定同源唯一真理源：AI 视图窗口，
            // 压缩后走估算，否则用 API 报告的真实 inputTokens）
            val estimatedTokens = aiViewContextUsedTokens(
                historyStore,
                sessionId,
                sessionStore?.get(sessionId)?.aiModel?.supportsImages ?: true
            )
            val remaining = modelContextWindow?.let { limit ->
                (limit - estimatedTokens).coerceAtLeast(0)
            }
            return if (remaining != null) {
                "You have $remaining tokens left in this context window."
            } else {
                "You have unknown tokens left in this context window."
            }
        }
    }

    // ==================== new_context_window ====================

    @Serializable
    data class NewContextWindowArgs(
        @LLMDescription("Leave empty; reserved for future use.")
        val placeholder: String = ""
    )

    inner class NewContextWindowTool : SimpleTool<NewContextWindowArgs>(
        argsType = typeToken<NewContextWindowArgs>(),
        name = "new_context",
        description = "Start a new context window. Does not clear, reset, or otherwise affect environment state."
    ) {
        override suspend fun execute(args: NewContextWindowArgs): String {
            // 只设置请求标志，TurnExecutor 在 agent.run() 结束后消费
            newContextWindowFlag.set(true)
            return "A new context window will start without summarizing conversation history."
        }
    }

    // ==================== ask_user ====================

    @Serializable
    data class AskUserAnswer(
        @LLMDescription("Question ID, matching an id from the ask_user call.")
        val questionId: String,
        @LLMDescription("Answers selected by the user; supports multi-select.")
        val answers: List<String>
    )

    @Serializable
    data class AskUserResult(
        @LLMDescription("All of the answers, one entry per question asked.")
        val answers: List<AskUserAnswer>
    )

    @Serializable
    data class AskUserQuestionArg(
        @LLMDescription("Question ID, unique.")
        val id: String,
        @LLMDescription("Question text.")
        val prompt: String,
        @LLMDescription("List of options. Empty = free-text input.")
        val options: List<String> = emptyList(),
        @LLMDescription("true = allow selecting multiple answers.")
        val multiSelect: Boolean = false
    )

    @Serializable
    data class AskUserArgs(
        @LLMDescription("Questions to ask the user. May contain multiple.")
        val questions: List<AskUserQuestionArg>
    )

    inner class AskUserTool : SimpleTool<AskUserArgs>(
        argsType = typeToken<AskUserArgs>(),
        name = "ask_user",
        description = "Ask the user one or more questions. Use this when you need clarification, " +
            "confirmation, or a decision from the user. Supports multiple questions, " +
            "multiple choice, and multi-select."
    ) {
        override suspend fun execute(args: AskUserArgs): String {
            if (questionRequester == null) {
                return "Error: question requesting is not available in this context."
            }

            val questions = args.questions.map { q ->
                xyz.mederi.question.Question(
                    id = q.id,
                    prompt = q.prompt,
                    options = q.options,
                    multiSelect = q.multiSelect
                )
            }

            val result = questionRequester.request(questions)

            return if (result.rejected) {
                "User declined to answer the questions."
            } else {
                val askUserResult = AskUserResult(
                    answers = result.answers.mapIndexed { i, ans ->
                        AskUserAnswer(questionId = args.questions[i].id, answers = ans)
                    }
                )
                Json.encodeToString(AskUserResult.serializer(), askUserResult)
            }
        }
    }
}
