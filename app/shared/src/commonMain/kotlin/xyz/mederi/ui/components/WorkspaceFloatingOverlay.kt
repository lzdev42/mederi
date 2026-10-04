package xyz.mederi.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.Play
import compose.icons.feathericons.X
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.close
import mederi.app.shared.generated.resources.subagent_strip_view_overview
import mederi.app.shared.generated.resources.subagent_strip_working
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.ui.RightDockPanel
import xyz.mederi.ui.TurnStatus
import xyz.mederi.ui.WorkspaceViewModel
import xyz.mederi.ui.components.atoms.StatusStrip

/**
 * 工作区输入框顶部顶层浮动层 (WorkspaceFloatingOverlay)
 *
 * 永远处于 Z 轴最顶层（z-index 顶层），悬浮挂载在对话框（ChatInputCard）正上方，
 * 不推挤消息列表周围布局。
 *
 * 承载两类非消息正文的运转与通知态：
 * 1. 子智能体工作态单一浮动条：当前有子智能体在工作时显示一行固定通知，
 *    支持 [查看概览 ↗] 跳转查看详情，支持用户手动点 [✕] 关闭，无子智能体工作时自动关闭。
 * 2. 对话轮次状态栏 (StatusBar)：Option 3 Linear Tech Capsule 极简胶囊，
 *    实时指示当前 AI 运转态（排队/思考中/工具调用/生成中/重试中）。
 */
@Composable
fun WorkspaceFloatingOverlay(
    viewModel: WorkspaceViewModel,
    modifier: Modifier = Modifier,
) {
    val turnStatus = viewModel.turnStatus
    val showSubagentBanner = viewModel.showSubagentRunningBanner
    val colors = LocalMederiColors.current

    // 压缩进行中：用 isCompacting 覆盖派生状态，状态栏显示"压缩中"而非 Prepare 等通用态
    val status = if (viewModel.isCompacting) TurnStatus.Compacting else turnStatus
    val showStatusBar = status.shouldDisplayInStatusBar

    if (!showStatusBar && !showSubagentBanner) return

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 1. 子智能体工作态单一浮条（固定文案，支持查看概览与手动关闭，结束自动消失）
        AnimatedVisibility(
            visible = showSubagentBanner,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    StatusStrip(
                        text = stringResource(Res.string.subagent_strip_working),
                        icon = FeatherIcons.Play,
                        iconTint = colors.accentSecondary,
                        borderColor = colors.accentSecondary.copy(alpha = 0.35f),
                        spinning = false,
                        animated = true,
                        actionLabel = stringResource(Res.string.subagent_strip_view_overview),
                        onAction = { viewModel.openDockPanel(RightDockPanel.OVERVIEW) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(
                    onClick = { viewModel.dismissSubagentRunningBanner() },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = FeatherIcons.X,
                        contentDescription = stringResource(Res.string.close),
                        tint = colors.textMuted,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
        }

        // 2. 对话轮次状态栏（Option 3 极简胶囊，靠左悬浮贴近输入框）
        AnimatedVisibility(
            visible = showStatusBar,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
            ) {
                StatusBar(
                    status = status,
                    startedAtMillis = viewModel.turnStartedAt,
                    statusHint = viewModel.statusHint,
                )
            }
        }
    }
}
