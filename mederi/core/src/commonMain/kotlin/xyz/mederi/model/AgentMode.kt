package xyz.mederi.domain.model

import kotlinx.serialization.Serializable

/**
 * Agent 执行策略。
 *
 * 两种模式的工具集完全一致（Triage Flow——是否建计划由 AI 判断，无代码门禁）。
 *
 * 唯一区别是计划的批准者：
 * - [APPROVAL]：计划由用户批准，批准后才执行。
 * - [AUTONOMOUS]：计划自动批准（自己批准自己），立即执行。
 */
@Serializable
enum class AgentMode {
    APPROVAL,
    AUTONOMOUS
}
