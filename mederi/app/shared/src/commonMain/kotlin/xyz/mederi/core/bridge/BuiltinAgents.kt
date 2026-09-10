package xyz.mederi.core.bridge

import xyz.mederi.core.contract.models.AgentOption

/**
 * 内置 Agent 预设清单（唯一真理源）。
 *
 * 不存在自定义 Agent。Agent = 运行时实例，由 AgentMode + WorkType 两个正交维度配置行为。
 * 此处静态结构化定义 AgentMode × WorkType 的四种组合，供 UI 选择与创建 Session 时解析。
 */
object BuiltinAgents {

    val ALL = listOf(
        AgentOption(
            id = "autonomous-code",
            name = "自主 · 编程",
            description = "AI 自主判断直接执行，适合写代码、调试",
            mode = xyz.mederi.core.contract.models.AgentMode.AUTONOMOUS,
            workType = xyz.mederi.core.contract.models.WorkType.CODE
        ),
        AgentOption(
            id = "approval-code",
            name = "审批 · 编程",
            description = "每次需求先列计划等批准后再执行，适合非平凡编程任务",
            mode = xyz.mederi.core.contract.models.AgentMode.APPROVAL,
            workType = xyz.mederi.core.contract.models.WorkType.CODE
        ),
        AgentOption(
            id = "autonomous-work",
            name = "自主 · 通用",
            description = "AI 自主执行，适合文档处理、总结资料、协助创作",
            mode = xyz.mederi.core.contract.models.AgentMode.AUTONOMOUS,
            workType = xyz.mederi.core.contract.models.WorkType.WORK
        ),
        AgentOption(
            id = "approval-work",
            name = "审批 · 通用",
            description = "先列计划等批准后再执行，适合非程序员",
            mode = xyz.mederi.core.contract.models.AgentMode.APPROVAL,
            workType = xyz.mederi.core.contract.models.WorkType.WORK
        )
    )

    /** 按 ID 查找预设，未命中返回 null。 */
    fun byId(id: String?): AgentOption? = ALL.find { it.id == id }
}
