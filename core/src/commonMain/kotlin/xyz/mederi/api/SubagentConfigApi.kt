package xyz.mederi.api

import kotlinx.serialization.Serializable
import xyz.mederi.domain.model.SubagentRole
import xyz.mederi.provider.API_KEY_ROTATION_ID
import xyz.mederi.provider.domain.model.ReasoningLevel

/**
 * 子代理配置 API。
 */
interface SubagentConfigApi {

    /** 列出所有内置 Agent 角色及其模型配置。 */
    suspend fun list(): List<SubagentRoleConfigDto>

    /** 获取指定角色的配置。 */
    suspend fun get(role: SubagentRole): SubagentRoleConfigDto

    /** 更新指定角色的模型配置。 */
    suspend fun update(role: SubagentRole, request: UpdateSubagentConfigRequest)

    /** 全局子代理设置（与角色无关的项：单会话并发上限等）。 */
    suspend fun getGlobalSettings(): SubagentGlobalSettingsDto

    /** 更新全局子代理设置。 */
    suspend fun updateGlobalSettings(request: UpdateSubagentGlobalSettingsRequest)
}

/**
 * 展示用 DTO：包含角色元信息 + 当前配置 + 解析后的模型名（便于 UI 显示）。
 */
@Serializable
data class SubagentRoleConfigDto(
    val role: SubagentRole,
    val displayName: String,
    val description: String,
    val modelId: String? = null,
    val modelName: String? = null,
    val reasoningLevel: ReasoningLevel? = null,
    /**
     * 子代理的 API Key 策略：null=继承父会话选定 key；具体 keyId=指定 key；
     * [API_KEY_ROTATION_ID]（`xyz.mederi.provider.API_KEY_ROTATION_ID`）=轮询。
     */
    val apiKeyId: String? = null,
    val isInheriting: Boolean = true
)

/**
 * 更新角色配置请求。
 * [modelId]、[reasoningLevel] 与 [apiKeyId] 均为 null 时恢复为继承。
 */
@Serializable
data class UpdateSubagentConfigRequest(
    val modelId: String? = null,
    val reasoningLevel: ReasoningLevel? = null,
    /**
     * 子代理的 API Key 策略：null=继承父会话选定 key；具体 keyId=指定 key；
     * [API_KEY_ROTATION_ID]（`xyz.mederi.provider.API_KEY_ROTATION_ID`）=轮询。
     */
    val apiKeyId: String? = null
)

/**
 * 全局子代理设置（与角色无关）。
 *
 * [maxConcurrentAgents] 单会话并发子代理上限：spawn 原子门禁拒绝超发（EXECUTOR/RESEARCHER
 * 同池计数，与类型无关）；浏览器任务（BrowserTaskManager 驱动）不经 SubagentManager，不受限。
 */
@Serializable
data class SubagentGlobalSettingsDto(
    val maxConcurrentAgents: Int
)

/** 更新全局子代理设置请求。[maxConcurrentAgents] 必须 >= 1。 */
@Serializable
data class UpdateSubagentGlobalSettingsRequest(
    val maxConcurrentAgents: Int
)
