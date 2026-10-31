package xyz.mederi.ui.components

import xyz.mederi.theme.LocalWorkspaceMotion
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition

import xyz.mederi.theme.WorkspaceStyle
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertTriangle
import compose.icons.feathericons.Archive
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.compaction_completed_notice
import mederi.app.shared.generated.resources.file_open_failed
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.ui.TurnStatus
import xyz.mederi.ui.WorkspaceViewModel
import xyz.mederi.ui.components.atoms.StatusStrip

/**
 * 工作区输入框顶部顶层浮动层 (WorkspaceFloatingOverlay)
 *
 * 永远处于 Z 轴最顶层（z-index 顶层），悬浮挂载在对话框（ChatInputCard）正上方，
 * 不推挤消息列表周围布局。
 *
 * 保留主 AI 轮次状态、压缩完成与文件打开失败提示。
 * 子 Agent 运行提醒由对话派发记录与概览负责，不在输入框上方重复显示。
 * 截图预览独立于这些通知的可见性。
 */
@Composable
fun WorkspaceFloatingOverlay(
    viewModel: WorkspaceViewModel,
    modifier: Modifier = Modifier,
) {
    val turnStatus = viewModel.turnStatus
    val showCompactionNotice = viewModel.compactionCompletedNotice
    val showFileOpenError = viewModel.fileOpenErrorNotice
    val browserAutomationState = viewModel.browserAutomationState
    val colors = LocalMederiColors.current

    // 压缩进行中：用 isCompacting 覆盖派生状态，状态栏显示"压缩中"而非 Prepare 等通用态
    val status = if (viewModel.isCompacting) TurnStatus.Compacting else turnStatus
    val showStatusBar = status.shouldDisplayInStatusBar

    if (!showStatusBar && !showCompactionNotice && !showFileOpenError &&
        browserAutomationState.previewScreenshot == null) return

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 2. 压缩完成一次性提示条（复用 StatusStrip 样式，3 秒自动消失）
        AnimatedVisibility(
            visible = showCompactionNotice,
            enter = if (LocalWorkspaceMotion.current.reducedMotion) EnterTransition.None else fadeIn(LocalWorkspaceMotion.current.disclosureSpec()) + expandVertically(LocalWorkspaceMotion.current.disclosureSpec()),
            exit = if (LocalWorkspaceMotion.current.reducedMotion) ExitTransition.None else fadeOut(LocalWorkspaceMotion.current.disclosureSpec()) + shrinkVertically(LocalWorkspaceMotion.current.disclosureSpec()),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
            ) {
                StatusStrip(
                    text = stringResource(Res.string.compaction_completed_notice),
                    icon = FeatherIcons.Archive,
                    iconTint = colors.accentPrimary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // 2.5 文件打开失败一次性提示条（错误样式，5 秒自动消失）
        AnimatedVisibility(
            visible = showFileOpenError,
            enter = if (LocalWorkspaceMotion.current.reducedMotion) EnterTransition.None else fadeIn(LocalWorkspaceMotion.current.disclosureSpec()) + expandVertically(LocalWorkspaceMotion.current.disclosureSpec()),
            exit = if (LocalWorkspaceMotion.current.reducedMotion) ExitTransition.None else fadeOut(LocalWorkspaceMotion.current.disclosureSpec()) + shrinkVertically(LocalWorkspaceMotion.current.disclosureSpec()),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
            ) {
                StatusStrip(
                    text = stringResource(Res.string.file_open_failed),
                    icon = FeatherIcons.AlertTriangle,
                    iconTint = colors.accentDanger,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // 3. 主轮次轻量状态行；其显隐条件与计时来源保持不变。
        AnimatedVisibility(
            visible = showStatusBar,
            enter = if (LocalWorkspaceMotion.current.reducedMotion) EnterTransition.None else fadeIn(LocalWorkspaceMotion.current.disclosureSpec()) + expandVertically(LocalWorkspaceMotion.current.disclosureSpec()),
            exit = if (LocalWorkspaceMotion.current.reducedMotion) ExitTransition.None else fadeOut(LocalWorkspaceMotion.current.disclosureSpec()) + shrinkVertically(LocalWorkspaceMotion.current.disclosureSpec()),
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

    // 4. 点击流程内截图高清大图预览弹窗（Lightbox）
    browserAutomationState.previewScreenshot?.let { screenshot ->
        BrowserScreenshotLightbox(
            screenshot = screenshot,
            colors = colors,
            onDismiss = { viewModel.closeBrowserScreenshotPreview() },
        )
    }
}
