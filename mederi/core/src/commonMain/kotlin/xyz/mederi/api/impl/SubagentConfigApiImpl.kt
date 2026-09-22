package xyz.mederi.api.impl

import xyz.mederi.api.SubagentConfigApi
import xyz.mederi.api.SubagentRoleConfigDto
import xyz.mederi.api.UpdateSubagentConfigRequest
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

    private fun roleMetadata(role: SubagentRole): Pair<String, String> = when (role) {
        SubagentRole.EXECUTOR -> "执行器 (Executor)" to "负责执行计划中的具体子任务，拥有代码修改与命令执行权限"
        SubagentRole.RESEARCHER -> "研究员 (Researcher)" to "负责只读调研代码库与分析上下文，无写入与命令执行权限"
    }
}
