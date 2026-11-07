package xyz.mederi.core.contract.models

import kotlinx.serialization.Serializable

/** 项目执行权限；与计划审批 AgentMode 相互独立。 */
@Serializable
enum class ExecutionPermissionMode { NEEDS_APPROVAL, FULL_ACCESS }

@Serializable
data class SandboxSettings(
    val mode: ExecutionPermissionMode = ExecutionPermissionMode.NEEDS_APPROVAL,
    val extraWritablePaths: List<String> = emptyList(),
    /** 项目配置版本；修改时携带读取到的版本，防止另一客户端的旧表单覆盖新权限。 */
    val version: Long = 0,
    /** 明确禁止读写的路径，完全访问模式也不得绕过。 */
    val deniedPaths: List<String> = emptyList(),
    /** 静态 argv 前缀允许列表；复杂 shell 不能据此自动放行。 */
    val allowedCommands: List<String> = emptyList(),
    /** 静态 argv 前缀拒绝列表，优先于允许列表及完全访问模式。 */
    val deniedCommands: List<String> = emptyList(),
)

/** 一次存活的执行申请，不能由历史工具调用恢复为可批准状态。 */
@Serializable
data class ExecutionApprovalRequest(
    val id: String,
    val conversationId: String,
    val executionSessionId: String,
    val toolName: String,
    val command: String? = null,
    val paths: List<String> = emptyList(),
    val cwd: String? = null,
    val access: String,
    val reason: String? = null,
)

@Serializable
data class ResolveExecutionApprovalInput(val approved: Boolean)
