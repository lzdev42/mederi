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
    RESEARCHER
}