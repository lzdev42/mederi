package xyz.mederi.tools.subagent

/**
 * 子代理执行动作词表（[ai.koog.prompt.message.Message] 流式帧 → SUBAGENT_PROGRESS 的映射结果）。
 *
 * **唯一取值来源**：`SUBAGENT_PROGRESS` 事件 payload["activity"] 只允许取本对象的三个值，
 * 生产端（[SubagentRunnerImpl] 的 progress 订阅）与消费端（UI 活动徽标）必须引用同一份定义，
 * 禁止任何地方再写 `"THINKING"` / `"TOOL_CALL"` / `"OUTPUT"` 字面量。
 *
 * 跨层镜像：core 不能依赖 app/shared，故契约层持有一份同名镜像
 * `xyz.mederi.core.contract.models.SubagentActivity`（UI 消费方引用契约那份），
 * 两侧逐值相等由 app/shared jvmTest 的 `SubagentActivityContractTest` 锁定——
 * 改动本词表而契约没跟上时该测试必须失败。
 */
object SubagentActivity {
    /** 模型正在输出推理（思考）内容。 */
    const val THINKING = "THINKING"

    /** 模型正在发起 / 流式输出工具调用。 */
    const val TOOL_CALL = "TOOL_CALL"

    /** 模型正在输出正文（含工具轮之后的最终回复）。 */
    const val OUTPUT = "OUTPUT"
}
