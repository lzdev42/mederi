package xyz.mederi.tools.subagent

import kotlinx.serialization.json.Json
import xyz.mederi.domain.model.AIModel
import xyz.mederi.domain.model.SubagentModelConfig
import xyz.mederi.domain.model.SubagentRole
import xyz.mederi.provider.ProviderManager
import xyz.mederi.provider.domain.model.ReasoningLevel
import xyz.mederi.store.SettingsStore

/**
 * 子代理模型配置管理器。
 *
 * 负责管理各内置 Agent 角色（如 EXECUTOR、RESEARCHER）的模型与推理等级偏好。
 * 配置持久化在 [SettingsStore]（key 为 `subagent.config.<ROLE>`）。
 * 运行时模型解析以 [ProviderManager] 为唯一真理源，若配置的模型已被删除或不存在，
 * 自动回退（fallback）到父会话的模型。
 */
class SubagentConfigManager(
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager
) {
    companion object {
        const val KEY_PREFIX = "subagent.config."

        fun settingKey(role: SubagentRole): String = "$KEY_PREFIX${role.name}"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * 获取指定角色的配置。若未配置或解析失败，返回默认配置（完全继承）。
     */
    suspend fun get(role: SubagentRole): SubagentModelConfig {
        val raw = settingsStore.get(settingKey(role)) ?: return SubagentModelConfig()
        return runCatching {
            json.decodeFromString<SubagentModelConfig>(raw)
        }.getOrDefault(SubagentModelConfig())
    }

    /**
     * 列出所有内置角色及其当前配置。
     */
    suspend fun list(): Map<SubagentRole, SubagentModelConfig> {
        return SubagentRole.entries.associateWith { role ->
            get(role)
        }
    }

    /**
     * 保存指定角色的配置。
     * 若配置为完全继承（modelId == null && reasoningLevel == null），则删除对应设置项。
     */
    suspend fun set(role: SubagentRole, config: SubagentModelConfig) {
        val key = settingKey(role)
        if (config.isInheriting) {
            settingsStore.delete(key)
        } else {
            val serialized = json.encodeToString(SubagentModelConfig.serializer(), config)
            settingsStore.set(key, serialized)
        }
    }

    /**
     * 清除指定角色的配置（恢复为继承父会话）。
     */
    suspend fun clear(role: SubagentRole) {
        settingsStore.delete(settingKey(role))
    }

    /**
     * 运行时解析：为指定角色解析实际使用的模型与推理等级。
     *
     * 解析优先级：
     * 1. 若配置了 modelId，且 [ProviderManager] 中能查到对应模型，使用该模型；
     *    否则回退到 [fallbackModel]。
     * 2. 若配置了 reasoningLevel，使用配置的值；
     *    若未配置（null），则回退到 [fallbackReasoning]。
     *
     * @param role 子代理角色
     * @param fallbackModel 父 Session 当前生效的模型
     * @param fallbackReasoning 父 Session 当前生效的推理等级
     * @return 最终选用的 (AIModel, ReasoningLevel)
     */
    suspend fun resolve(
        role: SubagentRole,
        fallbackModel: AIModel,
        fallbackReasoning: ReasoningLevel
    ): Pair<AIModel, ReasoningLevel> {
        val config = get(role)
        val model = config.modelId?.let { providerManager.getModel(it) } ?: fallbackModel
        val reasoning = config.reasoningLevel ?: fallbackReasoning
        return model to reasoning
    }
}
