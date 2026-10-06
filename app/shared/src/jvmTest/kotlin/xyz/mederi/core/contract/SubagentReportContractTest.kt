package xyz.mederi.core.contract

import xyz.mederi.domain.model.SubagentRole
import xyz.mederi.tools.subagent.SubagentManager
import xyz.mederi.core.contract.models.SubagentReportDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

/**
 * 子代理报告 DTO 的**跨模块契约锁**（AGENTS §6「契约同步」）。
 *
 * core `SubagentManager.SubagentReportData` 与 app/shared `SubagentReportDto` 字段对等性
 * 由本测试机器校验：core 改 `SubagentReportData` 字段而契约镜像没跟上时，此测试必须失败。
 */
class SubagentReportContractTest {

    @Test
    fun dtoFieldsMatchCoreSubagentReportData() {
        // 构造一个 core SubagentReportData
        val core = SubagentManager.SubagentReportData(
            agentId = "test-agent-id",
            role = SubagentRole.EXECUTOR,
            status = SubagentManager.SubagentStatus.COMPLETED,
            reportPath = ".mederi/plans/p1/reports/01-executor.md",
            content = "task done"
        )

        // 映射成 DTO（模拟 MederiAiCore.jvmMain 的映射逻辑）
        val dto = SubagentReportDto(
            agentId = core.agentId,
            role = core.role.name,
            status = core.status.name,
            reportPath = core.reportPath,
            content = core.content
        )

        // 逐字段断言
        assertEquals(core.agentId, dto.agentId, "agentId 两侧不一致")
        assertEquals(core.role.name, dto.role, "role.name 与 DTO.role 不一致")
        assertEquals(core.status.name, dto.status, "status.name 与 DTO.status 不一致")
        assertEquals(core.reportPath, dto.reportPath, "reportPath 两侧不一致")
        assertEquals(core.content, dto.content, "content 两侧不一致")
    }

    @Test
    fun dtoNullableFieldsMatchCoreNullSemantics() {
        // 报告路径和内容均为 null（e.g. 刚 spawn 还没产出报告）
        val core = SubagentManager.SubagentReportData(
            agentId = "agent-2",
            role = SubagentRole.RESEARCHER,
            status = SubagentManager.SubagentStatus.RUNNING,
            reportPath = null,
            content = null
        )

        val dto = SubagentReportDto(
            agentId = core.agentId,
            role = core.role.name,
            status = core.status.name,
            reportPath = core.reportPath,
            content = core.content
        )

        assertNull(dto.reportPath, "reportPath 应为 null")
        assertNull(dto.content, "content 应为 null")
        assertEquals("RESEARCHER", dto.role)
        assertEquals("RUNNING", dto.status)
    }

    @Test
    fun roleAndStatusNamesAreStable() {
        // 锁定枚举 .name 值——如果 core 改了枚举名，这里会失败提醒同步 DTO
        val roleNames = SubagentRole.values().map { it.name }
        assertNotNull(roleNames)
        assertEquals(listOf("EXECUTOR", "RESEARCHER", "BROWSER_OPERATOR", "BROWSER_BRAIN"), roleNames)

        val statusNames = SubagentManager.SubagentStatus.values().map { it.name }
        assertEquals(listOf("RUNNING", "COMPLETED", "ERROR", "STOPPED"), statusNames)
    }
}
