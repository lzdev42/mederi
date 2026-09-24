package xyz.mederi.domain.model

/**
 * 子代理角色。
 *
 * - [EXECUTOR]：执行型。与主代理工具集一致（全量文件工具 + 命令），
 *   但无 plan/spawn/verify/converge/ask_user 工具。
 *   只能通过计划流程 spawn（需要 planId + subtaskIndex + 已有 spec）。
 * - [RESEARCHER]：研究型。只读文件工具（read_file / list_directory），
 *   无任何写工具、无命令执行。用于调研代码、查文件、汇总等。
 *   不需要计划，task 直接给调研任务。
 */
enum class SubagentRole {
    EXECUTOR,
    RESEARCHER,

    /**
     * 浏览器操作员（BrowserOperator，手和眼）。配置专属角色：仅用于在设置页为浏览器子代理
     * 单独配置模型/推理档；实际执行不走 [SubagentRunnerImpl]（turn 子代理），
     * 由 BrowserTaskManager 在后台驱动（4-phase 循环：perceive→decide→execute→postprocess）。
     * 浏览器实现（JCEF/Camoufox）由主代理在 browser(RUN) 的 browser 参数选择，与角色无关。
     */
    BROWSER_OPERATOR,

    /**
     * 浏览器大脑（BrowserBrain，判定与报告）。配置专属角色：仅用于在设置页为浏览器子代理
     * 单独配置模型/推理档；实际执行不走 turn 子代理路径，由 BrowserTaskManager 后台执行
     * （内容判定 judge + 终态简报/报告 generateFinalReport）。
     */
    BROWSER_BRAIN
}