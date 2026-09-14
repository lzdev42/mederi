package xyz.mederi.domain.model

/**
 * 代理能力声明——子代理/主代理"继承父代理哪些能力"的**唯一中心化配置表**。
 *
 * 每种代理身份在此统一声明 `inheritMcp`（是否继承 MCP server 工具）与
 * `inheritSkills`（是否在系统提示词里注入已安装 skills）。装配方（[xyz.mederi.koog.TurnExecutor]
 * 的 MCP 会话开启、[xyz.mederi.prompt.SystemPrompts] 的 skills 段注入）只读这张表，
 * 不再各自写 if/else。
 *
 * **新增子代理角色时只需两步**，其余自动生效：
 * 1. 在 [SubagentRole] 加枚举值；
 * 2. 在本表加一个枚举值（声明继承策略）+ 在 [of] 加一个分支。
 */
enum class AgentCapabilities(
    /** 是否继承 MCP server 工具（合并进工具注册表）。 */
    val inheritMcp: Boolean,
    /** 是否在系统提示词注入已安装 skills 清单。 */
    val inheritSkills: Boolean
) {
    MAIN(inheritMcp = true, inheritSkills = true),
    EXECUTOR(inheritMcp = true, inheritSkills = true),
    RESEARCHER(inheritMcp = true, inheritSkills = false);

    companion object {
        /** 主代理传 null；子代理按角色取。 */
        fun of(subagentRole: SubagentRole?): AgentCapabilities = when (subagentRole) {
            null -> MAIN
            SubagentRole.EXECUTOR -> EXECUTOR
            SubagentRole.RESEARCHER -> RESEARCHER
        }
    }
}
