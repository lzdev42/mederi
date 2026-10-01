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
import compose.icons.feathericons.X
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.ui.RightDockPanel
import xyz.mederi.ui.WorkspaceViewModel

/**
 * 工作区输入框顶部顶层浮动层 (WorkspaceFloatingOverlay)
 *
 * 永远处于 Z 轴最顶层（z-index 顶层），悬浮挂载在对话框（ChatInputCard）正上方，
 * 不推挤消息列表周围布局。
 *
 * 承载两类非消息正文的运转与通知态：
 * 1. 子智能体任务派发/运行状态条（SubagentCallsBlock）：展示正在进行或刚派发的后台子代理任务，
 *    支持 [查看概览 ↗] 查看执行详情，支持手动点击 [✕] 关闭浮条。
 * 2. 对话轮次状态栏 (StatusBar)：Option 3 Linear Tech Capsule 极简胶囊，
 *    实时指示当前 AI 运转态（排队/思考中/工具调用/生成中/重试中）。
 */
@Composable
fun WorkspaceFloatingOverlay(
    viewModel: WorkspaceViewModel,
    modifier: Modifier = Modifier,
) {
    val turnStatus = viewModel.turnStatus
    val activeSubagentCalls = viewModel.activeSubagentCalls
    val colors = LocalMederiColors.current

    val showStatusBar = turnStatus.shouldDisplayInStatusBar
    val showSubagentCalls = activeSubagentCalls != null

    if (!showStatusBar && !showSubagentCalls) return

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 1. 子智能体任务派发与运行条（带查看概览与关闭按钮）
        AnimatedVisibility(
            visible = showSubagentCalls,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            activeSubagentCalls?.let { calls ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        SubagentCallsBlock(
                            subagents = calls.subagents,
                            isStreaming = calls.isStreaming,
                            isRunning = calls.isRunning,
                            hasFailed = calls.hasFailed,
                            onOpenOverview = { viewModel.openDockPanel(RightDockPanel.OVERVIEW) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(
                        onClick = { viewModel.dismissSubagentCall(calls.key) },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = FeatherIcons.X,
                            contentDescription = "Close",
                            tint = colors.textMuted,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }
        }

        // 2. 对话轮次状态栏（Option 3 极简胶囊，居中悬浮）
        AnimatedVisibility(
            visible = showStatusBar,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                StatusBar(
                    status = turnStatus,
                    startedAtMillis = viewModel.turnStartedAt,
                    statusHint = viewModel.statusHint,
                )
            }
        }
    }
}
