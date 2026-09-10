package xyz.mederi.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.mederi.domain.model.EventType
import xyz.mederi.domain.model.MederiEvent
import xyz.mederi.domain.model.Message
import xyz.mederi.domain.model.MessageRole
import xyz.mederi.store.HistoryStore
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Agent 自管理工具集。
 *
 * 包含计划管理、上下文余量查询、开新上下文窗口、当前时间 4 个工具。
 * 这些工具不涉及文件系统操作，不需要目录限制。
 *
 * @param sessionId 当前 Session ID。
 * @param historyStore 对话历史存储（get_context_remaining 读取、new_context_window 清空）。
 * @param eventBus 事件总线（update_plan 发出计划更新事件）。
 * @param modelContextWindow 模型上下文窗口大小（token 数），用于计算剩余量。
 * @param newContextWindowFlag 开新上下文窗口请求标志（execute 只置标志，TurnExecutor 在 agent.run 结束后消费）。
 * @param questionRequester 问题请求器（ask_user 工具需要）。
 */
class AgentTools(
    private val sessionId: String,
    private val historyStore: HistoryStore,
    private val eventBus: MutableSharedFlow<MederiEvent>,
    private val modelContextWindow: Int?,
    private val newContextWindowFlag: AtomicBoolean,
    private val questionRequester: xyz.mederi.question.QuestionRequester? = null
) {

    // ==================== update_plan ====================

    @Serializable
    enum class StepStatus {
        @SerialName("pending") PENDING,
        @SerialName("in_progress") IN_PROGRESS,
        @SerialName("completed") COMPLETED
    }

    @Serializable
    data class PlanItemArg(
        @LLMDescription("步骤文本内容。")
        val step: String,
        @LLMDescription("步骤状态：pending（待执行）、in_progress（执行中）、completed（已完成）。")
        val status: StepStatus
    )

    @Serializable
    data class UpdatePlanArgs(
        @LLMDescription("对本次计划更新的可选解释说明。")
        val explanation: String? = null,
        @LLMDescription("计划步骤列表，整体替换当前计划。")
        val plan: List<PlanItemArg>
    )

    inner class UpdatePlanTool : SimpleTool<UpdatePlanArgs>(
        argsType = typeToken<UpdatePlanArgs>(),
        name = "update_plan",
        description = "Updates the task plan. Provide an optional explanation and a list of plan items, each with a step and status. At most one step can be in_progress at a time."
    ) {
        override suspend fun execute(args: UpdatePlanArgs): String {
            eventBus.emit(
                MederiEvent(
                    type = EventType.SESSION_UPDATED,
                    sessionId = sessionId,
                    payload = mapOf(
                        "plan_update" to (args.explanation ?: ""),
                        "plan" to args.plan.joinToString("; ") { "${it.status.name}:${it.step}" }
                    ),
                    timestamp = Instant.now().toString()
                )
            )
            return "Plan updated"
        }
    }

    // ==================== get_context_remaining ====================

    @Serializable
    data class GetContextRemainingArgs(
        @LLMDescription("留空即可，此参数保留用于未来扩展。")
        val placeholder: String = ""
    )

    inner class GetContextRemainingTool : SimpleTool<GetContextRemainingArgs>(
        argsType = typeToken<GetContextRemainingArgs>(),
        name = "get_context_remaining",
        description = "Get the remaining tokens in the current context window."
    ) {
        override suspend fun execute(args: GetContextRemainingArgs): String {
            // 与 AI 实际收到的视图一致：只统计最后一条压缩标记及其之后的内容；
            // 真实 inputTokens 优先，端点不报告时回退加权估算（统一走 TokenEstimator）
            val messages = xyz.mederi.infrastructure.koog.HistoryStoreChatHistoryProvider
                .aiViewWindow(historyStore, sessionId)
            val estimatedTokens = contextUsedTokens(messages)
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
        @LLMDescription("留空即可，此参数保留用于未来扩展。")
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
        @LLMDescription("问题 ID，与 ask_user 工具传回的 id 对应。")
        val questionId: String,
        @LLMDescription("用户选择的答案列表，支持多选。")
        val answers: List<String>
    )

    @Serializable
    data class AskUserResult(
        @LLMDescription("所有问题的回答列表。")
        val answers: List<AskUserAnswer>
    )

    @Serializable
    data class AskUserQuestionArg(
        @LLMDescription("问题 ID，唯一标识。")
        val id: String,
        @LLMDescription("问题文本。")
        val prompt: String,
        @LLMDescription("可选项列表。空列表表示自由输入。")
        val options: List<String> = emptyList(),
        @LLMDescription("是否允许自定义答案（输入选项外的内容）。")
        val allowCustom: Boolean = false,
        @LLMDescription("是否允许多选。")
        val multiSelect: Boolean = false
    )

    @Serializable
    data class AskUserArgs(
        @LLMDescription("要问用户的问题列表。可包含多个问题。")
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
                    allowCustom = q.allowCustom,
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
