package xyz.mederi.api.impl

import xyz.mederi.api.SubagentConfigApi
import xyz.mederi.api.SubagentGlobalSettingsDto
import xyz.mederi.api.SubagentRoleConfigDto
import xyz.mederi.api.UpdateSubagentConfigRequest
import xyz.mederi.api.UpdateSubagentGlobalSettingsRequest
import xyz.mederi.domain.model.SubagentModelConfig
import xyz.mederi.domain.model.SubagentRole
import xyz.mederi.provider.ProviderManager
import xyz.mederi.tools.subagent.SubagentConfigManager

/**
 * [SubagentConfigApi] 实现。
 */
class SubagentConfigApiImpl(
    private val subagentConfigManager: SubagentConfigManager,
    private val providerManager: ProviderManager
) : SubagentConfigApi {

    override suspend fun list(): List<SubagentRoleConfigDto> {
        return SubagentRole.entries.map { role ->
            get(role)
        }
    }

    override suspend fun get(role: SubagentRole): SubagentRoleConfigDto {
        val config = subagentConfigManager.get(role)
        val model = config.modelId?.let { providerManager.getModel(it) }
        val (displayName, description) = roleMetadata(role)
        return SubagentRoleConfigDto(
            role = role,
            displayName = displayName,
            description = description,
            modelId = config.modelId,
            modelName = model?.name,
            reasoningLevel = config.reasoningLevel,
            isInheriting = config.isInheriting
        )
    }

    override suspend fun update(role: SubagentRole, request: UpdateSubagentConfigRequest) {
        subagentConfigManager.set(
            role,
            SubagentModelConfig(
                modelId = request.modelId,
                reasoningLevel = request.reasoningLevel
            )
        )
    }

    override suspend fun getGlobalSettings(): SubagentGlobalSettingsDto =
        SubagentGlobalSettingsDto(maxConcurrentAgents = subagentConfigManager.getMaxConcurrentSubagents())

    override suspend fun updateGlobalSettings(request: UpdateSubagentGlobalSettingsRequest) {
        subagentConfigManager.setMaxConcurrentSubagents(request.maxConcurrentAgents)
    }

    private fun roleMetadata(role: SubagentRole): Pair<String, String> = when (role) {
        SubagentRole.EXECUTOR -> "执行器 (Executor)" to "负责执行计划中的具体子任务，拥有代码修改与命令执行权限"
        SubagentRole.RESEARCHER -> "研究员 (Researcher)" to "负责只读调研代码库与分析上下文，无写入与命令执行权限"
        SubagentRole.BROWSER_OPERATOR ->
            "浏览器操作员 (Operator)" to "浏览器子代理的操作者（手和眼）：导航/点击/输入/滚动/截图，4-phase 循环驱动，负责页面物理操作与感知"
        SubagentRole.BROWSER_BRAIN ->
            "浏览器大脑 (Brain)" to "浏览器子代理的大脑：负责内容判定（judge）与终态简报/详细报告生成（generateFinalReport），分析页面内容与判定结果"
    }
}
