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
    val startedAt: String,
    /** 当前执行动作（THINKING / TOOL_CALL / OUTPUT / null）。 */
    val currentActivity: String? = null,
    /** 当前调用的工具名称（若有）。 */
    val currentTool: String? = null,
    /** 提取的最新一句话 / 最后一句话。 */
    val lastMessage: String? = null,
    /** 最近尾部输出内容（流式蹦字视窗用，上限 1000 字符）。 */
    val recentOutput: String? = null,
    /** 终态时间戳（ISO 8601），用于计算最终工作总耗时。 */
    val completedAt: String? = null
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

/**
 * 子代理模型配置（UI 契约层）。
 */
@Serializable
data class SubagentConfigItem(
    val role: String,
    val displayName: String,
    val description: String,
    val modelId: String? = null,
    val modelName: String? = null,
    val reasoningLevel: String? = null,
    val isInheriting: Boolean = true
)

/**
 * 更新子代理配置输入（UI 契约层）。
 */
@Serializable
data class UpdateSubagentConfigInput(
    val modelId: String? = null,
    val reasoningLevel: String? = null
)

/**
 * 全局子代理设置（与角色无关的项）。
 *
 * [maxConcurrentAgents] 单会话并发子代理上限：core 的 spawn 原子门禁拒绝超发
 * （EXECUTOR/RESEARCHER 同池计数）；浏览器任务不经 SubagentManager，不受此限制。
 */
@Serializable
data class SubagentGlobalSettings(
    val maxConcurrentAgents: Int = 2
)

/**
 * 更新全局子代理设置输入（UI 契约层）。[maxConcurrentAgents] 必须 >= 1。
 */
@Serializable
data class UpdateSubagentGlobalSettingsInput(
    val maxConcurrentAgents: Int
)

/**
 * 子代理执行动作词表（SUBAGENT_PROGRESS 事件 payload["activity"]）——**core
 * `xyz.mederi.tools.subagent.SubagentActivity` 的契约镜像**。
 *
 * core 是 JVM 侧引擎、不能依赖 app/shared，故此处只能镜像一份；两侧逐值相等由
 * app/shared jvmTest 的 `SubagentActivityContractTest` 机器锁定——core 改词表而契约没跟上时该测试必须失败。
 *
 * 消费方（UI 活动徽标等）**只允许引用本常量**，禁止再写 `"THINKING"` / `"TOOL_CALL"` / `"OUTPUT"` 字面量。
 */
object SubagentActivity {
    /** 模型正在输出推理（思考）内容。 */
    const val THINKING = "THINKING"

    /** 模型正在发起 / 流式输出工具调用。 */
    const val TOOL_CALL = "TOOL_CALL"

    /** 模型正在输出正文（含工具轮之后的最终回复）。 */
    const val OUTPUT = "OUTPUT"
}

