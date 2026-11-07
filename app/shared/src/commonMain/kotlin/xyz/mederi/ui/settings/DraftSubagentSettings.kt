package xyz.mederi.ui.settings

import xyz.mederi.core.contract.AiCore
import xyz.mederi.core.contract.models.*

/** 未发送的新会话偏好：只存覆盖项，不复制系统默认，也不提前建立会话。 */
class DraftSubagentSettings {
    internal val id = kotlin.uuid.Uuid.random().toString()
    private val roles = mutableMapOf<String, UpdateSubagentConfigInput>()
    private var concurrency: UpdateSubagentGlobalSettingsInput? = null

    internal fun applyDefaults(defaults: List<SubagentConfigItem>): List<SubagentConfigItem> =
        defaults.filter { it.role == "EXECUTOR" || it.role == "RESEARCHER" }.map { item ->
            roles[item.role]?.let { override ->
                item.copy(modelId = override.modelId, modelName = null, reasoningLevel = override.reasoningLevel,
                    apiKeyId = override.apiKeyId,
                    isInheriting = override.modelId == null && override.reasoningLevel == null && override.apiKeyId == null,
                    usesDefault = false)
            } ?: item.copy(usesDefault = true)
        }

    internal fun applyDefaults(defaults: SubagentGlobalSettings): SubagentGlobalSettings =
        concurrency?.let { SubagentGlobalSettings(it.maxConcurrentAgents, usesDefault = false) }
            ?: defaults.copy(usesDefault = true)

    internal fun update(role: String, input: UpdateSubagentConfigInput?) {
        require(role == "EXECUTOR" || role == "RESEARCHER")
        if (input == null) roles.remove(role) else roles[role] = input
    }

    internal fun update(input: UpdateSubagentGlobalSettingsInput?) {
        require(input == null || input.maxConcurrentAgents >= 1)
        concurrency = input
    }

    fun snapshot(): DraftSubagentSettings = DraftSubagentSettings().also {
        it.roles.putAll(roles)
        it.concurrency = concurrency
    }

    /** 只由首发在创建会话后调用；空草稿不写任何覆盖。 */
    suspend fun persist(core: AiCore, conversationId: String): Result<Unit> = runCatching {
        roles.forEach { (role, input) -> core.updateSessionSubagentConfig(conversationId, role, input).getOrThrow() }
        concurrency?.let { core.updateSessionSubagentGlobalSettings(conversationId, it).getOrThrow() }
    }
}
