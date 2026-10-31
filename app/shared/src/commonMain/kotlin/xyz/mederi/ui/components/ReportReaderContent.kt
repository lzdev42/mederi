package xyz.mederi.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.theme.MederiColors
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.theme.WorkspaceStyle
import xyz.mederi.ui.ReportLoadState
import xyz.mederi.ui.ReportRequest
import xyz.mederi.ui.components.atoms.WorkspaceActionButton
import xyz.mederi.ui.components.atoms.WorkspaceLoadingIndicator

/** 只呈现报告读取投影；加载/错误提示不会成为可复制或导出的文档。 */
@Composable
internal fun ReportReaderContent(
    state: ReportLoadState,
    request: ReportRequest,
    title: String,
    onRetry: () -> Unit,
    colors: MederiColors,
    showTitle: Boolean = true,
) {
    when (state) {
        is ReportLoadState.Content -> ReportText(title, state.text, colors, showTitle)
        ReportLoadState.Loading -> Column(Modifier.fillMaxSize().padding(MederiSpacing.MD),
            verticalArrangement = Arrangement.spacedBy(MederiSpacing.SM)) {
            if (showTitle) Text(title, color = colors.textPrimary, style = WorkspaceStyle.Title)
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MederiSpacing.SM)) {
                WorkspaceLoadingIndicator(Modifier.size(WorkspaceStyle.IconSize))
                Text(stringResource(Res.string.report_loading), color = colors.textSecondary, style = WorkspaceStyle.Body)
            }
        }
        ReportLoadState.Idle, ReportLoadState.Missing, is ReportLoadState.Error -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(MederiSpacing.SM)) {
            Column(Modifier.fillMaxWidth().padding(MederiSpacing.MD),
                verticalArrangement = Arrangement.spacedBy(MederiSpacing.SM)) {
                if (showTitle && request.summary.isBlank()) Text(title, color = colors.textPrimary, style = WorkspaceStyle.Title)
                if (state is ReportLoadState.Error) {
                    ErrorBoard(errorMessage = stringResource(state.message.key, *state.message.args.toTypedArray()),
                        showContinue = true, onContinue = onRetry,
                        continueLabel = stringResource(Res.string.report_reload))
                } else {
                    Text(stringResource(if (state == ReportLoadState.Missing) Res.string.report_missing else Res.string.report_read_paused),
                        color = colors.textSecondary, style = WorkspaceStyle.Body)
                    WorkspaceActionButton(stringResource(Res.string.report_reload), onClick = onRetry)
                }
                Text(stringResource(if (request.summary.isNotBlank()) Res.string.report_summary_notice else Res.string.event_message_no_content),
                    color = colors.textMuted, style = WorkspaceStyle.Caption)
            }
            if (request.summary.isNotBlank()) Box(Modifier.weight(1f)) {
                ReportText(title, request.summary, colors, showTitle)
            }
        }
    }
}

/** 保留原文和原导出机制；摘要与全文只由外层来源标识区分，不拼入提示文案。 */
@Composable
private fun ReportText(title: String, text: String, colors: MederiColors, showTitle: Boolean) {
    TextReaderTabContent(title = title, content = text, lineCount = text.lines().size,
        charCount = text.length, colors = colors, showTitle = showTitle)
}
