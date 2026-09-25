package xyz.mederi.core.bridge

import xyz.mederi.core.contract.models.AgentMode
import xyz.mederi.core.contract.models.AgentOption

/**
 * 内置 Agent 预设清单（唯一真理源）。
 *
 * 不存在自定义 Agent。Agent = 运行时实例，由 AgentMode 单维度配置行为
 * （AUTONOMOUS 自动审批 / APPROVAL 人工审批），不再区分编程/通用工作用途。
 */
object BuiltinAgents {

    val ALL = listOf(
        AgentOption(
            id = "autonomous",
            name = "自动审批",
            description = "AI 自动批准计划并直接执行",
            mode = AgentMode.AUTONOMOUS
        ),
        AgentOption(
            id = "approval",
            name = "人工审批",
            description = "每次需求先列计划，等批准后再执行",
            mode = AgentMode.APPROVAL
        )
    )

    /** 按 ID 查找预设，未命中返回 null。 */
    fun byId(id: String?): AgentOption? = ALL.find { it.id == id }
}
