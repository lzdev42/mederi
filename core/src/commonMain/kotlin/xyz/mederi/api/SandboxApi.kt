package xyz.mederi.api

import xyz.mederi.tools.sandbox.ExecutionApprovalRequest
import xyz.mederi.tools.sandbox.SandboxSettings
import xyz.mederi.tools.sandbox.SandboxStatus

/** 项目执行权限 API；配置与活体申请的唯一真理源在 SandboxManager。 */
interface SandboxApi {
    suspend fun getSettings(projectId: String): SandboxSettings
    suspend fun updateSettings(projectId: String, settings: SandboxSettings): SandboxSettings
    /** 查询宿主实际后端能力；可能涉及 OS 探测，不能在 UI 线程直接执行。 */
    suspend fun getStatus(projectId: String): SandboxStatus
    fun pendingApprovals(conversationId: String): List<ExecutionApprovalRequest>
    suspend fun resolveExecutionApproval(conversationId: String, requestId: String, approved: Boolean)
}
