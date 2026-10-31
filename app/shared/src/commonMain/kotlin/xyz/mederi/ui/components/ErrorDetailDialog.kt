package xyz.mederi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import xyz.mederi.util.rememberClipboardCopy
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.delay
import xyz.mederi.theme.WorkspaceStyle
import xyz.mederi.ui.components.atoms.WorkspaceActionButton
import xyz.mederi.ui.components.atoms.WorkspaceIconButton
import xyz.mederi.ui.buildBugReportMarkdown
import xyz.mederi.ui.cleanErrorSummary
import xyz.mederi.ui.extractErrorCategory
import xyz.mederi.ui.extractErrorSuggestion
import xyz.mederi.theme.LocalMederiColors
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.close
import mederi.app.shared.generated.resources.error_report_app_version
import mederi.app.shared.generated.resources.error_report_client_platform
import mederi.app.shared.generated.resources.error_report_copy_done
import mederi.app.shared.generated.resources.error_report_copy_logs
import mederi.app.shared.generated.resources.error_report_copied_github
import mederi.app.shared.generated.resources.error_report_details_summary
import mederi.app.shared.generated.resources.error_report_env_title
import mederi.app.shared.generated.resources.error_report_fallback_summary
import mederi.app.shared.generated.resources.error_report_footer
import mederi.app.shared.generated.resources.error_report_logs_title
import mederi.app.shared.generated.resources.error_report_no_logs
import mederi.app.shared.generated.resources.error_report_no_summary
import mederi.app.shared.generated.resources.error_report_submit_bug
import mederi.app.shared.generated.resources.error_report_summary_title
import mederi.app.shared.generated.resources.error_report_title
import org.jetbrains.compose.resources.stringResource

/**
 * 详细错误诊断报告对话框。
 *
 * 默认不展开大段堆栈，仅在用户点击错误简报时弹出：
 * 1. 结构化展示错误分类与简述
 * 2. 醒目展示恢复建议（Suggestion）
 * 3. 暗色等宽代码块完整展示全量诊断日志与堆栈（可滚动、可选择）
 * 4. 底部支持一键“复制日志”与预填好内容的“提交Bug”（跳转 GitHub Issue）
 */
@Composable
fun ErrorDetailDialog(
    errorSummary: String,
    errorDiagnostic: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalMederiColors.current
    val copyToClipboard = rememberClipboardCopy()
    val uriHandler = LocalUriHandler.current

    var copyHint by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(copyHint) {
        if (copyHint != null) {
            delay(WorkspaceStyle.CopyFeedbackMillis)
            copyHint = null
        }
    }

    // 从 errorSummary 中提取分类 Badge，例如 "[API] KoogHttpClientException..."（解析下沉 core/ui/ErrorDetailFormatter.kt）
    val categoryBadge = remember(errorSummary) { extractErrorCategory(errorSummary) }
    val cleanSummary = remember(errorSummary, categoryBadge) { cleanErrorSummary(errorSummary, categoryBadge) }
    // copyHint 文案在组合上下文取值（Button onClick 不是 @Composable）
    val copyDoneHint = stringResource(Res.string.error_report_copy_done)
    val copiedGithubHint = stringResource(Res.string.error_report_copied_github)

    // 从诊断报告中提取 Suggestion 恢复建议（如有）——兼容 `Suggestion:`（异常路径）与 `建议：`（collectWarning 断流路径）
    val suggestion = remember(errorDiagnostic) { extractErrorSuggestion(errorDiagnostic) }

    // 格式化为 Markdown Bug Report 模板（用于剪贴板复制和 GitHub Issue 内容填充）
    val envTitle = stringResource(Res.string.error_report_env_title)
    val appVersionLabel = stringResource(Res.string.error_report_app_version)
    val platformLabel = stringResource(Res.string.error_report_client_platform)
    val summaryTitle = stringResource(Res.string.error_report_summary_title)
    val noSummaryText = stringResource(Res.string.error_report_no_summary)
    val logsTitle = stringResource(Res.string.error_report_logs_title)
    val detailsSummary = stringResource(Res.string.error_report_details_summary)
    val reportFooter = stringResource(Res.string.error_report_footer)
    // 模板拼装下沉 core/ui/ErrorDetailFormatter.kt（内部取 AppInfo.VERSION / getPlatform()）
    val bugReportMarkdown = remember(errorSummary, errorDiagnostic, envTitle, appVersionLabel, platformLabel, summaryTitle, noSummaryText, logsTitle, detailsSummary, reportFooter) {
        buildBugReportMarkdown(
            envTitle = envTitle,
            appVersionLabel = appVersionLabel,
            platformLabel = platformLabel,
            summaryTitle = summaryTitle,
            noSummaryText = noSummaryText,
            logsTitle = logsTitle,
            detailsSummary = detailsSummary,
            reportFooter = reportFooter,
            errorSummary = errorSummary,
            errorDiagnostic = errorDiagnostic,
        )
    }

    xyz.mederi.ui.components.atoms.MederiDialog(onDismiss, modifier, width = 680.dp) {
        ErrorDetailContent(
            categoryBadge, cleanSummary, suggestion,
            errorDiagnostic.ifBlank { errorSummary.ifBlank { stringResource(Res.string.error_report_no_logs) } },
            copyHint,
            onCopy = { copyToClipboard(bugReportMarkdown); copyHint = copyDoneHint },
            onSubmitBug = {
                copyToClipboard(bugReportMarkdown)
                copyHint = copiedGithubHint
                val issueTitle = "[Bug]: ${cleanSummary.take(80)}".encodeURLParameter()
                val issueBody = bugReportMarkdown.encodeURLParameter()
                uriHandler.openUri("https://github.com/lzdev42/mederi/issues/new?title=$issueTitle&body=$issueBody")
            },
            onDismiss = onDismiss,
        )
    }
}

/** 诊断纯展示：报告构造/剪贴板/URI保持在调用层，操作不等同停止工作。 */
@Composable
internal fun ErrorDetailContent(
    category: String?, summary: String, suggestion: String?, diagnostic: String, copyHint: String?,
    onCopy: () -> Unit, onSubmitBug: () -> Unit, onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMederiColors.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(FeatherIcons.AlertCircle, null, tint = colors.dangerText, modifier = Modifier.size(WorkspaceStyle.IconSize))
            Text(stringResource(Res.string.error_report_title), style = WorkspaceStyle.Title,
                color = colors.textPrimary, modifier = Modifier.weight(1f))
            WorkspaceIconButton(FeatherIcons.X, onDismiss, stringResource(Res.string.close))
        }
        if (!category.isNullOrBlank()) Text(category, style = WorkspaceStyle.Caption, color = colors.textSecondary)
        SelectionContainer {
            Text(summary.ifBlank { stringResource(Res.string.error_report_fallback_summary) },
                style = WorkspaceStyle.Body, color = colors.textPrimary)
        }
        if (!suggestion.isNullOrBlank()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Icon(FeatherIcons.Info, null, tint = colors.textSecondary, modifier = Modifier.size(WorkspaceStyle.IconSize))
                Text(suggestion, style = WorkspaceStyle.Body, color = colors.textSecondary)
            }
        }
        Text(stringResource(Res.string.error_report_logs_title), style = WorkspaceStyle.Caption, color = colors.textSecondary)
        SelectionContainer {
            Text(diagnostic, style = WorkspaceStyle.Caption, fontFamily = FontFamily.Monospace,
                color = colors.textPrimary,
                modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp)
                    .clip(RoundedCornerShape(WorkspaceStyle.ObjectRadius)).background(colors.surfaceCode)
                    .containScroll().verticalScroll(rememberScrollState()).padding(10.dp))
        }
        if (copyHint != null) Text(copyHint, style = WorkspaceStyle.Caption, color = colors.accentText)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            WorkspaceActionButton(stringResource(Res.string.error_report_copy_logs), onCopy, icon = FeatherIcons.Copy)
            WorkspaceActionButton(stringResource(Res.string.error_report_submit_bug), onSubmitBug, icon = FeatherIcons.ExternalLink)
            WorkspaceActionButton(stringResource(Res.string.close), onDismiss)
        }
    }
}
