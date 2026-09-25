package xyz.mederi.tools.subagent

import xyz.mederi.domain.model.AIModel
import xyz.mederi.domain.model.SubagentRole
import xyz.mederi.plan.PlanStore
import xyz.mederi.provider.domain.model.ReasoningLevel

/**
 * 子 Agent 执行器。
 *
 * 负责在内存中运行一个单 turn 的子 Agent，不持久化到数据库，
 * 执行结束后把结果以字符串形式返回给父 Agent。
 * 子 Agent 继承父 Agent 的所有配置，使用 AUTONOMOUS agentMode。
 */
interface SubagentRunner {

    /**
     * 运行子 Agent。
     *
     * @param task 任务描述。
     * @param briefing 补充信息（父 Agent 的关键结论、限制条件等）。
     * @param plan 子任务的 spec 执行清单（EXECUTOR 用，generate_spec 写入 Subtask.spec）；RESEARCHER 传 null。
     * @param role 子代理角色：EXECUTOR 执行计划内子任务（全工具、无 plan/spawn）、RESEARCHER 只读调研。
     * @param directories 可操作的项目目录列表（绝对路径）。
     * @param aiModel 使用的模型。
     * @param reasoningLevel 推理等级。
     * @param projectId 子 Agent 关联的项目 ID。
     * @param parentSessionId 父 Session ID，用于日志/追踪。
     * @return 子 Agent 的最终输出文本。若执行失败应抛出异常。
     */
    suspend fun run(
        task: String,
        briefing: String?,
        plan: String?,
        role: SubagentRole,
        directories: List<String>,
        aiModel: AIModel,
        reasoningLevel: ReasoningLevel,
        projectId: String,
        parentSessionId: String,
        apiKeyId: String? = null,
        /**
         * 子代理所属的 plan（SpawnAgentTool 传入；researcher 在有活跃 plan 时由 SpawnResearcherTool 传入）。
         * 非 null 时：
         * - EXECUTOR：把 touched files flush 到 Subtask.executorTouchedFiles（需配合 executorSubtaskIndex）
         *   并把完整报告落盘到 {planId}/reports/NN-executor.md，父上下文只收摘要+路径
         * - RESEARCHER：把完整报告落盘到 {planId}/research.md，父上下文只收摘要+路径
         *   （无活跃 plan 时 planId=null，保持原有行为：全文回灌父上下文）
         */
        planId: String? = null,
        executorSubtaskIndex: Int? = null,
        planStore: PlanStore? = null
    ): String
}
