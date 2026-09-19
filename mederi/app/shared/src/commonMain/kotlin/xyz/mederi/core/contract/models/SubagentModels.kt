package xyz.mederi.core.contract.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 子代理的 UI 缓存对象（MVVM 的 Model 层）。
 *
 * 数据源 = SUBAGENT_* 事件流（[SubagentTracker] 聚合），生命周期：
 * STARTED 建对象（带主代理派发的 task/briefing + 实际使用的模型元数据）→
 * COMPLETED / ERROR / STOPPED 置终态。"工作中" = status == RUNNING（真实 job 状态，
 * LLM 流式空闲超时 10 分钟兜底判死，不会永久假 RUNNING）。
 */
@Serializable
data class SubagentState(
    val agentId: String,
    /** 父会话 ID——UI 按当前会话过滤子代理列表。 */
    val parentSessionId: String,
    /** EXECUTOR / RESEARCHER。 */
    val role: String,
    /** spawn 实际使用的模型（动态读 session 后的值，即批准时刻/发送时刻的选择）。 */
    val modelId: String,
    val modelName: String,
    val reasoningLevel: String?,
    /** 主代理派发的命令。 */
    val task: String,
    val briefing: String?,
    /** RUNNING / COMPLETED / ERROR / STOPPED。 */
    val status: String,
    /** 事件时间戳（ISO 8601），列表排序键。 */
    val startedAt: String
)

/**
 * wait_agent / agent_status 工具输出 JSON 的契约镜像（core SubagentManager.StatusResult）。
 *
 * 落库在 tool result 里的就是这个 JSON——汇报全文在 [result] 字段，
 * [SubagentReportMarkdown] 据此把它转成可渲染的 markdown（UI 折叠卡片点开详情）。
 */
@Serializable
data class SubagentToolResult(
    val agentId: String,
    val status: String,
    val progress: String? = null,
    val result: String? = null,
    val modelId: String? = null,
    val modelName: String? = null,
    val reasoningLevel: String? = null,
    val recovery: String? = null
) {
    companion object {
        /** 宽松解码：容忍 core 侧 StatusResult 未来新增字段。 */
        private val json = Json { ignoreUnknownKeys = true }

        fun decode(raw: String): SubagentToolResult? = runCatching {
            json.decodeFromString(serializer(), raw)
        }.getOrNull()
    }
}
