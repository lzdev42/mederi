package xyz.mederi.tools.subagent

import kotlinx.serialization.json.Json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.mederi.store.SessionStore
import xyz.mederi.api.exception.MederiNotFoundException
import xyz.mederi.api.exception.MederiValidationException
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
 * 系统默认持久化在 [SettingsStore]；执行器/研究员的会话覆盖存入 [SessionStore]。
 * 未覆盖的角色动态读取系统默认，覆盖配置的 null 字段继承父会话。
 * 运行时模型解析以 [ProviderManager] 为唯一真理源，若配置的模型已被删除或不存在，
 * 自动回退（fallback）到父会话的模型。
 */
class SubagentConfigManager(
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
    private val sessionStore: SessionStore? = null
) {
    companion object {
        const val KEY_PREFIX = "subagent.config."

        /** 单会话并发子代理上限（与角色无关；浏览器任务不经 SubagentManager，天然不受限）。 */
        const val KEY_MAX_CONCURRENT = "subagent.maxConcurrent"

        /** 未配置时的默认并发上限。 */
        const val DEFAULT_MAX_CONCURRENT = 2

        fun settingKey(role: SubagentRole): String = "$KEY_PREFIX${role.name}"
    }

    private fun sessionRole(role: SubagentRole) = role == SubagentRole.EXECUTOR || role == SubagentRole.RESEARCHER

    private fun requireSessionRole(role: SubagentRole) {
        if (!sessionRole(role)) throw MederiValidationException("Session subagent configuration is unsupported for $role")
    }

    private val sessionWrites = Mutex()

    private suspend fun session(id: String) = sessionStore?.get(id)
        ?: throw MederiNotFoundException("Session not found: $id")

    suspend fun usesDefault(role: SubagentRole, sessionId: String?): Boolean =
        sessionId == null || !sessionRole(role) || !session(sessionId).subagentConfigs.containsKey(role)

    suspend fun usesDefaultConcurrency(sessionId: String?): Boolean =
        sessionId == null || session(sessionId).subagentMaxConcurrent == null

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * 获取指定角色的生效配置。会话角色未覆盖时读取系统默认。
     */
    suspend fun get(role: SubagentRole, sessionId: String? = null): SubagentModelConfig {
        if (sessionId != null && sessionRole(role)) session(sessionId).subagentConfigs[role]?.let { return it }
        val raw = settingsStore.get(settingKey(role)) ?: return SubagentModelConfig()
        return runCatching {
            json.decodeFromString<SubagentModelConfig>(raw)
        }.getOrDefault(SubagentModelConfig())
    }

    /**
     * 列出所有内置角色及其当前配置。
     */
    suspend fun list(sessionId: String? = null): Map<SubagentRole, SubagentModelConfig> {
        return SubagentRole.entries.filter { sessionId == null || sessionRole(it) }.associateWith { role ->
            get(role, sessionId)
        }
    }

    /**
     * 保存指定角色的配置。
     * 全局完全继承时删除设置项；会话空配置仍需保存，用来显式继承父会话。
     */
    suspend fun set(role: SubagentRole, config: SubagentModelConfig, sessionId: String? = null) {
        if (sessionId != null) {
            requireSessionRole(role)
            sessionWrites.withLock {
                val current = session(sessionId)
                sessionStore!!.updateSubagentConfigs(sessionId, current.subagentConfigs + (role to config))
            }
            return
        }
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
    suspend fun clear(role: SubagentRole, sessionId: String? = null) {
        if (sessionId != null) {
            requireSessionRole(role)
            sessionWrites.withLock {
                val current = session(sessionId)
                sessionStore!!.updateSubagentConfigs(sessionId, current.subagentConfigs - role)
            }
            return
        }
        settingsStore.delete(settingKey(role))
    }

    /**
     * 读取单会话并发子代理上限（全局设置，与角色无关）。
     * 未配置或值非法（非数字/小于 1）时返回默认值 [DEFAULT_MAX_CONCURRENT]。
     */
    suspend fun getMaxConcurrentSubagents(sessionId: String? = null): Int {
        if (sessionId != null) session(sessionId).subagentMaxConcurrent?.let { return it }
        val raw = settingsStore.get(KEY_MAX_CONCURRENT)?.toIntOrNull() ?: return DEFAULT_MAX_CONCURRENT
        return if (raw >= 1) raw else DEFAULT_MAX_CONCURRENT
    }

    /**
     * 设置单会话并发子代理上限。要求 >= 1（0 或负数没有"派发能力"语义）。
     */
    suspend fun setMaxConcurrentSubagents(count: Int, sessionId: String? = null) {
        require(count >= 1) { "max concurrent subagents must be >= 1, got $count" }
        if (sessionId != null) {
            session(sessionId)
            sessionStore!!.updateSubagentMaxConcurrent(sessionId, count)
        } else settingsStore.set(KEY_MAX_CONCURRENT, count.toString())
    }

    suspend fun clearMaxConcurrentSubagents(sessionId: String) {
        session(sessionId)
        sessionStore!!.updateSubagentMaxConcurrent(sessionId, null)
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
        fallbackReasoning: ReasoningLevel,
        sessionId: String? = null
    ): Pair<AIModel, ReasoningLevel> {
        val config = get(role, sessionId)
        val model = config.modelId?.let { providerManager.getModel(it) } ?: fallbackModel
        val reasoning = config.reasoningLevel ?: fallbackReasoning
        return model to reasoning
    }
}
