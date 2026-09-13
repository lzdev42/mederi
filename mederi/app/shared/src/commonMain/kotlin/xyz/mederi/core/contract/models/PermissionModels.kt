package xyz.mederi.core.contract.models

import kotlinx.serialization.Serializable

@Serializable
data class QuestionRequest(
    val id: String = "",
    val conversationId: String = "",
    val questions: List<Question> = emptyList(),
) {
    @Serializable
    data class Question(
        val id: String = "",
        val prompt: String = "",
        val options: List<String> = emptyList(),
        val allowCustom: Boolean = false,
        val multiSelect: Boolean = false,
    )
}

/**
 * 计划审批请求(APPROVAL 模式下 AI 调 create_plan 后产生)。
 * 卡片只展示标题 + 子任务数 + [查看详细计划]按钮——摘要由 AI 在调 create_plan 前的聊天消息提供，
 * 不从 plan 里提取。planPath 是计划 Markdown 文件路径，用户点"查看详细计划"时打开。
 */
@Serializable
data class PlanApprovalRequest(
    val id: String,
    val conversationId: String,
    val planPath: String,
    val title: String,
    val summary: String = "",
    val subtaskCount: Int = 0,
    val planContent: String? = null,
    val status: String = "PENDING",
)
