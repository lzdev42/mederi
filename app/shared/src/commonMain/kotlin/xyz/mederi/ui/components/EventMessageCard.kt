package xyz.mederi.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertCircle
import compose.icons.feathericons.Check
import compose.icons.feathericons.Info
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.event_message_no_content
import mederi.app.shared.generated.resources.event_message_open_report_title
import mederi.app.shared.generated.resources.event_message_strip_completed
import mederi.app.shared.generated.resources.event_message_strip_completed_role
import mederi.app.shared.generated.resources.event_message_strip_failed
import mederi.app.shared.generated.resources.event_message_strip_failed_role
import mederi.app.shared.generated.resources.event_message_strip_stopped
import mederi.app.shared.generated.resources.event_message_strip_stopped_role
import mederi.app.shared.generated.resources.event_message_subagent_title
import mederi.app.shared.generated.resources.event_message_subagent_title_role
import mederi.app.shared.generated.resources.event_message_view_detail
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.ui.ChatListItem
import xyz.mederi.ui.components.atoms.StatusStrip
import xyz.mederi.ui.host.LocalProjectFileTreeProvider

/**
 * 通用事件消息卡片 (EventMessageCard)
 *
 * 极简单行微条：单行显示子代理终态（完成 / 失败 / 被手动取消）+ 角色名，低空间占用。
 * 报告摘要不再塞进状态行——点击整行直接滑开右侧阅读器（RightDockPanel.PLAN / Reader）查看完整 Markdown 汇报。
 *
 * 职责边界：本组件**只负责三件事**——
 * ① 终态判定（三档语义色映射）、② 报告正文取数（注入的文件树 provider，失败回落 summary）、
 * ③ 点击开阅读器（把标题与正文交给调用方）。
 * 单行微条的**外观壳**（8dp 圆角 / surfaceCard 底 / hover 边框 / 前置图标 / 单行省略文本 /
 * 右侧「文字 + 箭头」入口 / 整行可点与 pointer 手型光标）统一由 [StatusStrip] 提供，
 * 本文件不再自绘任何壳代码，只把「文案 + 图标 + 颜色」三组决策交给它。
 * 注意：状态**不再染色文字**（错误态红字已删），只保留图标色与 35% 边框色。
 *
 * 终态判定的**唯一真理源**在 `SubagentLabels.subagentTerminalKind`：历史事故 = 只判 ERROR/FAILED 二值、
 * 其余一律走 else，导致 STOPPED（手动取消）被显示成「已完成」。STOPPED 必须单独命中自己的分支。
 */
@Composable
fun EventMessageCard(
    item: ChatListItem.EventMessageCard,
    onOpenReport: ((title: String, content: String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val colors = LocalMederiColors.current

    // 终态判定的**唯一真理源**在 SubagentLabels.subagentTerminalKind：
    // 历史事故 = 这里只判 ERROR/FAILED 二值、其余一律走 else，导致 STOPPED（手动取消）
    // 被显示成「已完成」。STOPPED 必须单独命中自己的分支。
    val terminalKind = subagentTerminalKind(item.status)
    // 错误色语义：ERROR + OTHER（未知/非终态兜底，不得回落成「已完成」）
    val useErrorStyle = terminalKind == SubagentTerminalKind.ERROR || terminalKind == SubagentTerminalKind.OTHER
    // 中性色语义：STOPPED（被取消不是成功，不用成功绿）
    val useNeutralStyle = terminalKind == SubagentTerminalKind.STOPPED

    // 读取完整报告正文：优先走注入的 LocalProjectFileTreeProvider（commonMain 禁用 JVM 文件 API，
    // KMP 硬性约束）；provider 为 null（未注入的平台）或读不出内容时回落 item.summary 原文。
    // 注意：CompositionLocal.current 是 @Composable 读，必须在 composable 作用域内取出，
    // 再作为 remember 的 key 传进计算块（不能写进 remember lambda 内部）。
    val fileTreeProvider = LocalProjectFileTreeProvider.current
    val reportContent = remember(item.reportPath, item.summary, fileTreeProvider) {
        val fileText = item.reportPath?.takeIf { it.isNotBlank() }
            ?.let { path -> fileTreeProvider?.readText(path) }
        if (!fileText.isNullOrBlank()) fileText else item.summary
    }

    val hasRole = hasSpecificRole(item.role)
    val roleLabel = roleLabelOf(item.role)
    val displayTitle = if (hasRole) {
        stringResource(Res.string.event_message_subagent_title_role, roleLabel)
    } else {
        stringResource(Res.string.event_message_subagent_title)
    }
    val openReportTitle = stringResource(Res.string.event_message_open_report_title, displayTitle)

    // 状态行 = 纯状态文案（三档，不再拼报告摘要/方括号）；摘要只在右侧阅读器里看
    val statusText = when (terminalKind) {
        SubagentTerminalKind.COMPLETED -> if (hasRole) {
            stringResource(Res.string.event_message_strip_completed_role, roleLabel)
        } else {
            stringResource(Res.string.event_message_strip_completed)
        }
        SubagentTerminalKind.ERROR -> if (hasRole) {
            stringResource(Res.string.event_message_strip_failed_role, roleLabel)
        } else {
            stringResource(Res.string.event_message_strip_failed)
        }
        SubagentTerminalKind.STOPPED -> if (hasRole) {
            stringResource(Res.string.event_message_strip_stopped_role, roleLabel)
        } else {
            stringResource(Res.string.event_message_strip_stopped)
        }
        // 兜底按失败展示，绝不回落成「已完成」
        SubagentTerminalKind.OTHER -> if (hasRole) {
            stringResource(Res.string.event_message_strip_failed_role, roleLabel)
        } else {
            stringResource(Res.string.event_message_strip_failed)
        }
    }

    val contentToOpen = reportContent.ifBlank { stringResource(Res.string.event_message_no_content) }

    StatusStrip(
        text = statusText,
        // 1. 左侧状态图标：COMPLETED=对勾(绿) / STOPPED=Info(中性灰) / ERROR+OTHER=AlertCircle(红)
        icon = when {
            useErrorStyle -> FeatherIcons.AlertCircle
            useNeutralStyle -> FeatherIcons.Info
            else -> FeatherIcons.Check
        },
        iconTint = when {
            useErrorStyle -> colors.statusError
            useNeutralStyle -> colors.textSecondary
            else -> colors.accentSuccess
        },
        // TODO(i18n)：这里仍是 core 的原始英文枚举（COMPLETED/ERROR/STOPPED），
        // 只在无障碍树朗读，本次不引入新的 event_message_status_* 资源键。
        iconContentDescription = item.status,
        // 2. 状态表达只在图标色与边框色上：文字一律主文字色（STOPPED/ERROR 都不染色）
        borderColor = if (useErrorStyle) colors.statusError.copy(alpha = 0.35f) else colors.divider,
        // 3. 右侧「查看详情 + 箭头」入口：仅在有阅读器回调时出现
        actionLabel = if (onOpenReport != null) stringResource(Res.string.event_message_view_detail) else null,
        // onOpenReport 是 (title, content) -> Unit，与 StatusStrip 的 (() -> Unit) 不是同一类型，
        // 必须包一层把标题与正文固定住再交给壳；为 null 时整行不可点。
        onAction = onOpenReport?.let { open -> { open(openReportTitle, contentToOpen) } },
        modifier = modifier
    )
}
