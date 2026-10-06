package xyz.mederi.core.contract.models

import kotlinx.serialization.Serializable

/**
 * core `xyz.mederi.tools.subagent.SubagentManager.SubagentReportData` 的 commonMain 契约镜像。
 *
 * core 是 JVM 侧引擎、不能依赖 app/shared，故此处镜像一份；两侧字段对等由
 * app/shared jvmTest 的 [SubagentReportContractTest]（`xyz.mederi.core.contract` 包）机器锁定——
 * core 改 `SubagentReportData` 字段而契约没跟上时该测试必须失败。
 *
 * 与 core 的映射关系：
 * - [role]：core [xyz.mederi.domain.model.SubagentRole] 的 `.name`
 * - [status]：core [xyz.mederi.tools.subagent.SubagentManager.SubagentStatus] 的 `.name`
 *
 * 消费方（UI / 遥控端）**只允许引用本 DTO**，禁止直接引用 core 类型。
 */
@Serializable
data class SubagentReportDto(
    val agentId: String,
    /** core `SubagentRole.name`（EXECUTOR / RESEARCHER / BROWSER_OPERATOR / BROWSER_BRAIN）。 */
    val role: String,
    /** core `SubagentManager.SubagentStatus.name`（RUNNING / COMPLETED / ERROR / STOPPED）。 */
    val status: String,
    val reportPath: String? = null,
    val content: String? = null
)
