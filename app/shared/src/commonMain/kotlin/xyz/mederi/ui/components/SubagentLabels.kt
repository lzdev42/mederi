package xyz.mederi.ui.components

import androidx.compose.runtime.Composable
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.subagent_role_executor
import mederi.app.shared.generated.resources.subagent_role_generic
import mederi.app.shared.generated.resources.subagent_role_researcher
import org.jetbrains.compose.resources.stringResource

/**
 * 子代理终态的**唯一判定真理源**。
 *
 * 与 core 的 `SubagentManager.SubagentStatus`（RUNNING / COMPLETED / ERROR / STOPPED）对应关系：
 * - [SubagentTerminalKind.COMPLETED] ← COMPLETED（正常跑完）
 * - [SubagentTerminalKind.ERROR] ← ERROR（执行报错）；另兼容历史防御性取值 FAILED
 * - [SubagentTerminalKind.STOPPED] ← STOPPED（用户手动取消 / 被 stop 掉）
 * - [SubagentTerminalKind.OTHER] —— **仅兜底展示用**：RUNNING、空串、乱串等非终态或未知取值
 *   才会落到这里。正常链路不该出现 OTHER；出现即说明上游传了意料之外的状态串。
 */
internal enum class SubagentTerminalKind { COMPLETED, ERROR, STOPPED, OTHER }

/**
 * 把子代理状态串（来自 core 的事件 payload / REST 客户端的 status 字段）映射为终态种类。
 *
 * 历史事故：UI 侧只判 ERROR/FAILED、其余一律走「已完成」分支，导致 **STOPPED（用户手动取消）
 * 的子代理被显示成「已完成」**。STOPPED 必须命中 [SubagentTerminalKind.STOPPED]，不能落 else/OTHER。
 *
 * 判定规则（大小写不敏感、忽略首尾空白——沿用 UI 侧既有的 `equals(..., ignoreCase = true)` 行为，
 * 不收窄）：COMPLETED→COMPLETED；ERROR/FAILED→ERROR；STOPPED→STOPPED；其余→OTHER。
 */
internal fun subagentTerminalKind(status: String): SubagentTerminalKind =
    when (status.trim().uppercase()) {
        "COMPLETED" -> SubagentTerminalKind.COMPLETED
        // FAILED 是历史防御性取值：core 的 SubagentStatus 实际不产生它，
        // 但 UI 侧历史上写过 equals("FAILED") 判断，保留兼容不破坏既有行为。
        "ERROR", "FAILED" -> SubagentTerminalKind.ERROR
        "STOPPED" -> SubagentTerminalKind.STOPPED
        else -> SubagentTerminalKind.OTHER
    }

/**
 * 子代理角色名 → 本地化展示名。
 *
 * - EXECUTOR / RESEARCHER 走本地化资源（[Res.string.subagent_role_executor] /
 *   [Res.string.subagent_role_researcher]）
 * - 其他非空值：「首字母大写 + 其余小写」（与既有 `role.lowercase().replaceFirstChar { it.uppercase() }` 一致）
 * - 空串 / 纯空白：兜底走 [Res.string.subagent_role_generic]（子代理 / Subagent）
 */
@Composable
internal fun roleLabelOf(role: String): String = when {
    role.equals("EXECUTOR", ignoreCase = true) -> stringResource(Res.string.subagent_role_executor)
    role.equals("RESEARCHER", ignoreCase = true) -> stringResource(Res.string.subagent_role_researcher)
    role.isNotBlank() -> role.lowercase().replaceFirstChar { it.uppercase() }
    else -> stringResource(Res.string.subagent_role_generic)
}
